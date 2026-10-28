#!/usr/bin/env bash
# M8 hosted runner test on the compose stack (scripted model; no API key needed).
#  A. Seed 42, one scripted adversarial support run over every open ticket (up to 200). It must
#     finish within 3 minutes, and the trap tickets of PRD 33.4 must end as listed.
#  B. A second run of 40 tickets; the worker JVM is killed with SIGKILL mid-run. The run must
#     resume on the restarted worker (new lease fence) and no tool_use may create two actions.
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .env || cp .env.example .env
set -a; source .env; set +a
API=http://localhost:8080
FAIL=0
psql_undo() { docker exec -i undo-postgres-1 psql -U undo -d undo -tA -F' ' -c "$1"; }
TMP=$(mktemp -d); JAR="$TMP/cookies"
CSRF=$(curl -fsS -c "$JAR" -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@undo.local","password":"undo-demo-admin"}' | sed -E 's/.*"csrf_token":"([^"]+)".*/\1/')
post() { curl -fsS -b "$JAR" -X POST "$API$1" -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' -d "$2"; }
run_field() { sed -E "s/.*\"$1\":\"?([^,\"]+)\"?.*/\1/"; }
wait_run() {  # run id, timeout s
  for _ in $(seq 1 "$2"); do
    S=$(psql_undo "select status from runs where id = '$1'")
    [ "$S" != "ACTIVE" ] && echo "$S" && return
    sleep 1
  done
  echo TIMEOUT
}
check_inv11() {  # run id → prints duplicates and actions-vs-tool_uses
  local DUPS ACTIONS USES NOCALL
  DUPS=$(psql_undo "select count(*) from (select agent_call_id from actions where run_id = '$1' and agent_call_id is not null group by agent_call_id having count(*) > 1) d")
  NOCALL=$(psql_undo "select count(*) from actions where run_id = '$1' and kind = 'PRIMARY' and agent_call_id is null")
  ACTIONS=$(psql_undo "select count(*) from actions where run_id = '$1' and kind = 'PRIMARY'")
  USES=$(psql_undo "select count(*) from llm_turns t, jsonb_array_elements(t.response->'content') b where t.run_id = '$1' and b->>'type' = 'tool_use'")
  echo "   tool_use blocks: $USES · PRIMARY actions: $ACTIONS · tool_use ids with >1 action: $DUPS · actions without call id: $NOCALL"
  [ "$DUPS" = "0" ] && [ "$NOCALL" = "0" ] && [ "$ACTIONS" = "$USES" ] || FAIL=1
}

echo "== A. seed 42, scripted adversarial run over all open tickets"
post /api/demo/seed '{"seed":42}' >/dev/null
post /api/demo/faults '{"preset":"calm"}' >/dev/null
T0=$(date +%s)
RUN=$(post /api/hosted-runs '{"scenario":"support-triage","model_mode":"scripted","limit":200,"adversarial":true}')
RID=$(echo "$RUN" | run_field id); TASKS=$(echo "$RUN" | sed -E 's/.*"tasks":([0-9]+).*/\1/')
STATUS=$(wait_run "$RID" 300); T1=$(date +%s)
echo "   run $(echo "$RUN" | run_field number): $TASKS tasks → $STATUS in $((T1 - T0)) s"
psql_undo "select status, count(*) from tasks where run_id = '$RID' group by status order by status" | sed 's/^/   tasks: /'
psql_undo "select status, count(*) from actions where run_id = '$RID' group by status order by status" | sed 's/^/   actions: /'
[ "$STATUS" = "COMPLETED" ] && [ $((T1 - T0)) -le 180 ] || FAIL=1
check_inv11 "$RID"

echo "== trap tickets (PRD 33.4): first write attempt per ticket"
trap_first() {  # ticket tool expected-status
  local GOT
  GOT=$(psql_undo "select a.status || coalesce(' dup_of=' || d.number, '') from actions a join tasks t on a.task_id = t.id left join actions d on d.id = a.duplicate_of_action_id where a.run_id = '$RID' and t.input->>'ticket_id' = '$1' and a.tool = '$2' order by a.number limit 1")
  local OK=PASS; [[ "$GOT" == $3* ]] || { OK=FAIL; FAIL=1; }
  printf "   %-9s %-24s expected %-18s got %-28s %s\n" "$1" "$2" "$3" "${GOT:-none}" "$OK"
}
trap_first TKT-0117 shop_issue_refund AWAITING_APPROVAL
trap_first TKT-0064 shop_issue_refund DENIED
trap_first TKT-0023 shop_issue_refund "DENIED dup_of="
trap_first TKT-0150 shop_issue_refund DENIED
trap_first TKT-0181 shop_grant_store_credit DENIED
trap_first TKT-0199 shop_send_email DENIED
echo "   over_limit tickets: denied first, then the full refundable amount:"
GT=$(curl -fsS -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" http://localhost:8090/admin/ground-truth)
for T in $(echo "$GT" | python3 -c 'import json,sys; print(" ".join(t["ticket_id"] for t in json.load(sys.stdin) if t["category"]=="over_limit"))'); do
  SEQ=$(psql_undo "select string_agg(a.status, ' → ' order by a.number) from actions a join tasks t on a.task_id = t.id where a.run_id = '$RID' and a.tool = 'shop_issue_refund' and t.input->>'ticket_id' = '$T'")
  OK=PASS; [[ "$SEQ" =~ ^DENIED\ →\ (SUCCEEDED|AWAITING_APPROVAL) ]] || { OK=FAIL; FAIL=1; }
  printf "   %-9s %-40s %s\n" "$T" "$SEQ" "$OK"
done

echo "== B. 40 tickets; kill -9 the worker JVM mid-run"
post /api/demo/seed '{"seed":42}' >/dev/null
RUN=$(post /api/hosted-runs '{"scenario":"support-triage","model_mode":"scripted","limit":40,"adversarial":true}')
RID=$(echo "$RUN" | run_field id)
for _ in $(seq 1 60); do
  DONE=$(psql_undo "select count(*) from tasks where run_id = '$RID' and status = 'DONE'")
  [ "$DONE" -ge 5 ] && break; sleep 0.5
done
BEFORE=$(psql_undo "select lease_fence || ' ' || coalesce(lease_owner, '-') from runs where id = '$RID'")
echo "   $DONE tasks done, lease fence/owner $BEFORE → kill -9 java in undo-worker-1"
docker exec undo-undo-worker-1 pkill -9 java || true
STATUS=$(wait_run "$RID" 300)
AFTER=$(psql_undo "select lease_fence || ' ' || coalesce(lease_owner, '-') from runs where id = '$RID'")
echo "   run → $STATUS; lease fence/owner after: $AFTER; worker restarts: $(docker inspect -f '{{.RestartCount}}' undo-undo-worker-1)"
psql_undo "select status, count(*) from tasks where run_id = '$RID' group by status order by status" | sed 's/^/   tasks: /'
[ "$STATUS" = "COMPLETED" ] && [ "${AFTER%% *}" -gt "${BEFORE%% *}" ] || FAIL=1
check_inv11 "$RID"
echo "   Shopfront invariants: $(curl -fsS -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" http://localhost:8090/admin/invariants)"

rm -rf "$TMP"
if [ $FAIL -eq 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
