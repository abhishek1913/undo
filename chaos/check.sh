#!/usr/bin/env bash
# Judges a chaos run (PRD 22.5 step 6, success metrics SM4 and SM6). Usage: chaos/check.sh <chaos seconds>
# Needs out/chaos/{kills.tsv,restarts-before.txt,restarts-after.txt} from chaos/run.sh and the stack up.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
OUT=out/chaos; SECS=${1:-600}; FAIL=0
psql_undo() { docker exec -i undo-postgres-1 psql -U undo -d undo -tA -F'|' -c "$1"; }
psql_shop() { docker exec -i undo-postgres-1 psql -U shopfront -d shopfront -tA -F'|' -c "$1"; }
bad() { FAIL=1; echo "   FAIL: $1"; }

echo "chaos run: ${SECS} s, commit $(git rev-parse --short HEAD), $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo
echo "-- volume"
WRITES=$(psql_undo "select count(*) from actions where effect = 'WRITE' and kind = 'PRIMARY'")
EXECUTED=$(psql_undo "select count(distinct a.id) from actions a join attempts t on t.action_id = a.id and t.kind = 'EXECUTE' where a.effect = 'WRITE' and a.kind = 'PRIMARY'")
KILLS=$(wc -l < $OUT/kills.tsv | tr -d ' ')
DEATHS=$(paste -d' ' $OUT/restarts-before.txt $OUT/restarts-after.txt | awk '{d+=$6-$3} END {print d+0}')
echo "   write actions proposed: $WRITES (executed at least once: $EXECUTED)"
psql_undo "select status, count(*) from actions where effect = 'WRITE' and kind = 'PRIMARY' group by status order by status" | sed 's/^/   /;s/|/ /'
echo "   tasks: $(psql_undo "select string_agg(status || '=' || n, ' ') from (select status, count(*) n from tasks group by status order by status) s")"
echo "   scripted worker kills (SIGKILL of the JVM): $KILLS · container restarts in total: $DEATHS · self-halts at crash points: $((DEATHS - KILLS))"
UNK=$(psql_undo "select count(distinct action_id) from action_transitions where to_status = 'UNKNOWN'")
echo "   actions that went UNKNOWN: $UNK"
[ "$WRITES" -ge 3000 ] || bad "SM4 needs >= 3000 write actions, got $WRITES"
[ "$KILLS" -ge 30 ] || bad "SM4 needs >= 30 worker kills, got $KILLS"

echo
echo "-- invariants on undo (chaos/invariants.sql), must all be 0"
while IFS='|' read -r name n; do
  [ -z "$name" ] && continue
  printf "   %-78s %s\n" "$name" "$n"; [ "$n" = "0" ] || bad "$name = $n"
done < <(docker exec -i undo-postgres-1 psql -U undo -d undo -tA -F'|' < chaos/invariants.sql)

echo
echo "-- Shopfront /admin/invariants, must all be 0 (SM4: duplicate provider effects)"
INV=$(curl -fsS localhost:8090/admin/invariants -H "X-Admin-Token: $SHOP_ADMIN_TOKEN")
echo "   $INV"
echo "$INV" | python3 -c "import json,sys; sys.exit(0 if all(v==0 for v in json.load(sys.stdin).values()) else 1)" || bad "Shopfront invariants not all zero"

echo
echo "-- Undo and Shopfront agree on which actions took effect (ids of SUCCEEDED actions vs provider rows)"
cmp_ids() {  # label undo-sql shop-sql
  psql_undo "$2" | sort -u > $OUT/undo-$1.ids; psql_shop "$3" | sort -u > $OUT/shop-$1.ids
  local U S ONLY_U ONLY_S
  U=$(wc -l < $OUT/undo-$1.ids | tr -d ' '); S=$(wc -l < $OUT/shop-$1.ids | tr -d ' ')
  ONLY_U=$(comm -23 $OUT/undo-$1.ids $OUT/shop-$1.ids | wc -l | tr -d ' '); ONLY_S=$(comm -13 $OUT/undo-$1.ids $OUT/shop-$1.ids | wc -l | tr -d ' ')
  printf "   %-14s undo SUCCEEDED %-5s provider rows %-5s only-in-undo %-3s only-at-provider %s\n" "$1" "$U" "$S" "$ONLY_U" "$ONLY_S"
  [ "$ONLY_U" = "0" ] && [ "$ONLY_S" = "0" ] || bad "$1: Undo and Shopfront disagree"
}
cmp_ids refunds "select id from actions where tool = 'shop_issue_refund' and kind = 'PRIMARY' and status = 'SUCCEEDED'" \
  "select metadata->>'undo_action_id' from refunds where metadata ? 'undo_action_id'"
cmp_ids emails "select id from actions where tool = 'shop_send_email' and kind = 'PRIMARY' and status = 'SUCCEEDED'" \
  "select metadata->>'undo_action_id' from emails where metadata ? 'undo_action_id'"
cmp_ids credits "select id from actions where tool = 'shop_grant_store_credit' and kind = 'PRIMARY' and status = 'SUCCEEDED'" \
  "select substr(reference, 6) from store_credits where reference like 'undo\_%'"
DUPS=$(psql_shop "select count(*) from (select metadata->>'undo_action_id' from refunds where metadata ? 'undo_action_id' group by 1 having count(*) > 1) d")
echo "   refunds with one undo_action_id on two rows: $DUPS"; [ "$DUPS" = "0" ] || bad "duplicate refunds"

echo
echo "-- SM6: time from a worker dying to its in-flight actions being recovered"
# Affected = an attempt the killed node never finished (the sweeper closes it with cause lease_expired), started before the
# kill and after the node's previous kill, and not already swept before the kill (that one was orphaned by a self-halt). Recovery start = the first later attempt (EXECUTE or LOOKUP) of that action.
# End to end = the first later transition into SUCCEEDED, FAILED, NEEDS_REVIEW or CANCELLED. The limits of SM6 are
# lease 30 s + 5 s for NATIVE_KEY and NATURAL (gated on the recovery start: what the engine controls; a provider that
# hangs 15 s or drops the answer again on the recovery attempt adds time the engine cannot remove) and 105 s for
# LOOKUP_BY_REFERENCE (end to end; it already includes a provider timeout and the lookup grace). NONE counts as
# resolved when the action reaches NEEDS_REVIEW within 35 s (end to end).
VALUES=$(awk -F'\t' '{printf "%s(to_timestamp(%s/1000.0), '"'"'%s'"'"')", (NR>1?",":""), $1, $3}' $OUT/kills.tsv)
SM6=$(cat <<SQL
with kills(k_at, node) as (values ${VALUES:-(now(), 'none')}),
affected as (
  select a.id action_id, a.idempotency_class cls, k.k_at, a.tool
    from kills k
    join attempts t on t.node = k.node and (t.finished_at is null or (t.detail->>'cause' = 'lease_expired' and t.finished_at > k.k_at)) and t.started_at <= k.k_at
     and t.started_at > coalesce((select max(k2.k_at) from kills k2 where k2.node = k.node and k2.k_at < k.k_at), '-infinity')
    join actions a on a.id = t.action_id),
r as (
  select af.*,
    (select min(x.at) from action_transitions x where x.action_id = af.action_id and x.at > af.k_at
        and x.to_status in ('SUCCEEDED','FAILED','NEEDS_REVIEW','CANCELLED')) res_at,
    (select min(t2.started_at) from attempts t2 where t2.action_id = af.action_id and t2.started_at > af.k_at) rec_at
  from affected af),
m as (select r.*, extract(epoch from res_at - k_at) e2e, extract(epoch from coalesce(rec_at, res_at) - k_at) rec from r)
select cls, count(*), count(*) filter (where res_at is null),
       round(coalesce(max(rec), 0)::numeric, 1),
       round(coalesce(max(e2e), 0)::numeric, 1),
       round(coalesce(percentile_cont(0.95) within group (order by e2e), 0)::numeric, 1)
  from m group by cls order by cls
SQL
)
printf "   %-22s %9s %11s %14s %12s %10s %s\n" class affected unresolved "max recovery s" "max e2e s" "p95 e2e s" "gate"
while IFS='|' read -r cls n unres rec e2e p95; do
  [ -z "$cls" ] && continue
  case $cls in
    LOOKUP_BY_REFERENCE) LIM=105; G=$e2e; WHAT="end to end <= 105 s" ;;
    NONE) LIM=35; G=$e2e; WHAT="NEEDS_REVIEW <= 35 s" ;;
    *) LIM=35; G=$rec; WHAT="recovery start <= 35 s" ;;
  esac
  printf "   %-22s %9s %11s %14s %12s %10s %s\n" "$cls" "$n" "$unres" "$rec" "$e2e" "$p95" "$WHAT"
  [ "$unres" = "0" ] || bad "SM6: $unres $cls actions never resolved"
  awk -v m="$G" -v l="$LIM" 'BEGIN {exit !(m > l)}' && bad "SM6: $cls $WHAT, got $G s"
done < <(psql_undo "$SM6")
echo "   (these rows are B3, recovery time, over $KILLS kills)"
HALT=$(cat <<SQL
with kills(k_at, node) as (values ${VALUES:-(now(), 'none')}),
orphan as (
  select t.action_id, a.idempotency_class cls, t.started_at, t.finished_at from attempts t join actions a on a.id = t.action_id
   where (t.finished_at is null or t.detail->>'cause' = 'lease_expired')
     and not exists (select 1 from kills k where k.node = t.node and t.started_at <= k.k_at and (t.finished_at is null or t.finished_at > k.k_at)
        and t.started_at > coalesce((select max(k2.k_at) from kills k2 where k2.node = k.node and k2.k_at < k.k_at), '-infinity'))),
r as (select o.*, (select min(x.at) from action_transitions x where x.action_id = o.action_id and x.at > o.started_at
        and x.to_status in ('SUCCEEDED','FAILED','NEEDS_REVIEW','CANCELLED')) res_at from orphan o)
select cls, count(*), count(*) filter (where res_at is null), round(coalesce(max(extract(epoch from res_at - started_at)), 0)::numeric, 1)
  from r group by cls order by cls
SQL
)
echo "   self-halt orphans (attempts left open with no scripted kill; measured from the attempt start, which overstates the wait):"
while IFS='|' read -r cls n unres mx; do
  [ -z "$cls" ] && continue
  printf "   %-22s %9s unresolved %-3s max %s s\n" "$cls" "$n" "$unres" "$mx"
  [ "$unres" = "0" ] || bad "SM6 (halts): $unres $cls never resolved"
done < <(psql_undo "$HALT")
echo
echo "-- UNKNOWN resolution (UNKNOWN to next transition)"
psql_undo "select count(*), round(max(extract(epoch from n.at - u.at))::numeric,1), round(percentile_cont(0.95) within group (order by extract(epoch from n.at - u.at))::numeric,1)
  from action_transitions u join lateral (select x.at from action_transitions x where x.action_id = u.action_id and x.id > u.id order by x.id limit 1) n on true where u.to_status = 'UNKNOWN'" \
  | awk -F'|' '{printf "   %s UNKNOWN episodes, max %s s, p95 %s s\n", $1, $2, $3}'
echo
if [ $FAIL -eq 0 ]; then echo "RESULT: PASS (SM4 and SM6)"; else echo "RESULT: FAIL"; exit 1; fi
