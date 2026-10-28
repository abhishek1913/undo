#!/usr/bin/env bash
# PRD 22.5 chaos test. 3 workers that also halt on their own (UNDO_CHAOS_HALT_PROBABILITY), Undo
# reaching Shopfront through Toxiproxy, Shopfront on the "chaos" fault preset, four scripted
# adversarial runs over disjoint tickets. For CHAOS_SECONDS (default 600): a random worker JVM is
# killed with SIGKILL every 20 s and a random Toxiproxy toxic is on for 10 s of every 15 s.
# Then chaos stops, the system drains, recon runs, chaos/check.sh judges SM4 and SM6, and the run
# is appended to docs/CHAOS_RESULTS.md. Reseeds Shopfront and empties Undo's run data.
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .env || cp .env.example .env
set -a; source .env; set +a
CHAOS_SECONDS=${CHAOS_SECONDS:-600}
# .env carries UNDO_CHAOS_HALT_PROBABILITY=0.0 for every other stack; the chaos test sets its own.
export SHOP_BASE_URL=http://toxiproxy:18090 UNDO_CHAOS_HALT_PROBABILITY=${CHAOS_HALT_PROBABILITY:-0.0001}
export SHOP_SETTLEMENT_DELAY=PT30S SHOP_EXPORT_LAG=PT10S UNDO_DEMO=true UNDO_WORKER_RESTART=always
export UNDO_DEMO_AGENT_KEY=${UNDO_DEMO_AGENT_KEY:-undo_sk_KillTest00000000000000000000000000000000000}
COMPOSE="docker compose -f deploy/docker-compose.yml --env-file .env"
API=http://localhost:8080; SHOP=http://localhost:8090; TOXI=http://localhost:8474
OUT=out/chaos; rm -rf $OUT; mkdir -p $OUT
psql_undo() { docker exec -i undo-postgres-1 psql -U undo -d undo -tA -c "$1"; }
now_ms() { docker exec undo-postgres-1 date +%s%3N; }   # the VM clock the JVMs and Postgres use
shop_faults() { curl -fsS -X POST $SHOP/admin/faults -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d "$1" >/dev/null; }

echo "== stack: 3 workers, Toxiproxy, settlement delay 30 s"
$COMPOSE --profile chaos up -d --wait --force-recreate --scale undo-worker=3 postgres shopfront undo-api undo-worker toxiproxy >/dev/null
for _ in $(seq 1 30); do curl -fsS $TOXI/proxies/shopfront >/dev/null 2>&1 && break; sleep 1; done
psql_undo "truncate events, action_transitions, postings, journal_entries, attempts, approvals, revert_items, revert_plans, recon_findings, recon_runs, webhook_events, llm_turns, actions, tasks, runs cascade" >/dev/null
curl -fsS -X POST $SHOP/admin/seed -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"seed":7,"customers":200,"orders":3000,"tickets":1200}' >/dev/null
shop_faults '{"preset":"chaos"}'
WORKERS=$($COMPOSE ps -q undo-worker)
echo "   halt probability in the workers: $(docker exec $(echo $WORKERS | cut -d' ' -f1) printenv UNDO_CHAOS_HALT_PROBABILITY)"
for c in $WORKERS; do echo "   worker $(docker inspect -f '{{.Name}} node={{.Config.Hostname}} restarts={{.RestartCount}}' $c)"; done
for c in $WORKERS; do docker inspect -f '{{.Name}} {{.Config.Hostname}} {{.RestartCount}}' $c; done > $OUT/restarts-before.txt

echo "== four scripted adversarial runs, 300 tickets each, disjoint (offsets 0, 300, 600, 900)"
JAR=$(mktemp); CSRF=$(curl -fsS -c "$JAR" -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@undo.local","password":"undo-demo-admin"}' | sed -E 's/.*"csrf_token":"([^"]+)".*/\1/')
post() { curl -fsS -b "$JAR" -X POST "$API$1" -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' -d "$2"; }
: > $OUT/runs.txt
for off in 0 300 600 900; do
  post /api/hosted-runs "{\"scenario\":\"support-triage\",\"model_mode\":\"scripted\",\"adversarial\":true,\"ticket_offset\":$off,\"limit\":300}" \
    | sed -E 's/.*"id":"([^"]+)".*/\1/' >> $OUT/runs.txt
done
T_START=$(now_ms); echo "$T_START" > $OUT/start_ms.txt
# A person approving cards while the chaos goes on: every 20 s, all pending approvals (hash-bound, bulk).
(
  AJAR=$(mktemp); ACSRF=$(curl -fsS -c "$AJAR" -X POST $API/api/auth/login -H 'Content-Type: application/json' \
    -d '{"email":"approver@undo.local","password":"undo-demo-approver"}' | sed -E 's/.*"csrf_token":"([^"]+)".*/\1/')
  while true; do
    BODY=$(psql_undo "select json_build_object('decision','approve','items',coalesce(json_agg(json_build_object('approval_id',id,'args_hash',args_hash)),'[]'::json)) from (select id, args_hash from approvals where status = 'PENDING' limit 200) q")
    curl -fsS -b "$AJAR" -X POST $API/api/approvals/bulk -H "X-CSRF-Token: $ACSRF" -H 'Content-Type: application/json' -d "$BODY" >/dev/null 2>&1 || true
    sleep 20
  done
) &
APPROVER=$!

echo "== chaos for $CHAOS_SECONDS s: kill -9 a random worker every 20 s, a random toxic every 15 s"
END=$((SECONDS + CHAOS_SECONDS))
: > $OUT/kills.tsv
(
  sleep 2
  while [ $SECONDS -lt $END ]; do
    for c in $(echo "$WORKERS" | sort -R); do
      T=$(now_ms)
      if docker exec "$c" pkill -9 java 2>/dev/null; then
        printf '%s\t%s\t%s\n' "$T" "$(docker inspect -f '{{.Name}}' $c | tr -d /)" "$(docker inspect -f '{{.Config.Hostname}}' $c)" >> $OUT/kills.tsv
        break
      fi
    done
    sleep 20
  done
) &
KILLER=$!
(
  TOXICS=(latency timeout reset_peer)
  while [ $SECONDS -lt $END ]; do
    t=${TOXICS[$((RANDOM % 3))]}
    case $t in
      latency) body="{\"name\":\"chaos\",\"type\":\"latency\",\"stream\":\"downstream\",\"toxicity\":1.0,\"attributes\":{\"latency\":$((50 + RANDOM % 451)),\"jitter\":$((RANDOM % 100))}}" ;;
      timeout) body='{"name":"chaos","type":"timeout","stream":"downstream","toxicity":0.05,"attributes":{"timeout":0}}' ;;
      reset_peer) body='{"name":"chaos","type":"reset_peer","stream":"downstream","toxicity":0.02,"attributes":{"timeout":0}}' ;;
    esac
    curl -fsS -X POST $TOXI/proxies/shopfront/toxics -d "$body" >/dev/null || true
    printf '%s\t%s\n' "$(now_ms)" "$t" >> $OUT/toxics.tsv
    sleep 10
    curl -fsS -X DELETE $TOXI/proxies/shopfront/toxics/chaos >/dev/null || true
    sleep 5
  done
) &
TOXER=$!
while [ $SECONDS -lt $END ]; do
  sleep 60
  echo "   t=$((CHAOS_SECONDS - (END - SECONDS))) s: kills=$(wc -l < $OUT/kills.tsv | tr -d ' ') actions=$(psql_undo "select count(*) from actions") in flight=$(psql_undo "select count(*) from actions where status in ('EXECUTING','RETRY_WAIT','UNKNOWN')")"
done
wait $KILLER $TOXER || true

echo "== chaos off: toxics removed, Shopfront calm; waiting for every runnable action to finish (max 5 min)"
curl -fsS -X DELETE $TOXI/proxies/shopfront/toxics/chaos >/dev/null 2>&1 || true
shop_faults '{"preset":"calm"}'
T_STOP=$(now_ms); echo "$T_STOP" > $OUT/stop_ms.txt
for i in $(seq 1 150); do
  LEFT=$(psql_undo "select (select count(*) from actions where status in ('PROPOSED','APPROVED','EXECUTING','RETRY_WAIT','UNKNOWN')) + (select count(*) from runs where kind = 'HOSTED' and status = 'ACTIVE')")
  [ "$LEFT" = "0" ] && break
  sleep 2
done
echo "   unfinished after wait: $LEFT"
kill $APPROVER 2>/dev/null || true
echo "   waiting 15 s so the lagging email export (SHOP_EXPORT_LAG=PT10S) shows every email"; sleep 15
echo "== recon"
RECON=$(post /api/recon/runs '{}' | sed -E 's/.*"number":([0-9]+).*/\1/')
for _ in $(seq 1 60); do
  S=$(psql_undo "select status from recon_runs order by number desc limit 1"); [ "$S" != "RUNNING" ] && break; sleep 2
done
echo "   recon run $RECON: $S"
for c in $WORKERS; do docker inspect -f '{{.Name}} {{.Config.Hostname}} {{.RestartCount}}' $c; done > $OUT/restarts-after.txt
rm -f "$JAR"

echo "== check"
set +e
bash chaos/check.sh "$CHAOS_SECONDS" | tee $OUT/check.txt
RC=${PIPESTATUS[0]}
# docs/CHAOS_RESULTS.md: append this run
mkdir -p docs
[ -f docs/CHAOS_RESULTS.md ] || printf '# Chaos results\n\nOne section per `make chaos` run (PRD 22.5, SM4 and SM6).\n' > docs/CHAOS_RESULTS.md
{
  printf '\n## %s · commit %s · %s\n\n' "$(date -u +%Y-%m-%d)" "$(git rev-parse --short HEAD)" "$([ $RC -eq 0 ] && echo PASS || echo FAIL)"
  printf '```\n'; cat $OUT/check.txt; printf '```\n'
} >> docs/CHAOS_RESULTS.md
exit $RC
