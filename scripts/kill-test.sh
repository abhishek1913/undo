#!/usr/bin/env bash
# M4 kill test: 300 REST refunds while Shopfront drops 30% of responses after commit, and the
# worker process is killed with SIGKILL twice. Passes when Shopfront's invariants are all 0, every
# action is terminal, and the provider holds exactly one refund per succeeded action.
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .env || cp .env.example .env
set -a; source .env; set +a
export UNDO_DEMO=true
export UNDO_DEMO_AGENT_KEY=${UNDO_DEMO_AGENT_KEY:-undo_sk_KillTest00000000000000000000000000000000000}
COMPOSE="docker compose -f deploy/docker-compose.yml --env-file .env"
API=http://localhost:8080
SHOP=http://localhost:8090
N=${N:-300}

echo "== starting the stack"
$COMPOSE up -d --wait --force-recreate undo-api undo-worker shopfront postgres >/dev/null
psql_undo() { docker exec -i undo-postgres-1 psql -U undo -d undo -tA -c "$1"; }
psql_undo "truncate events, action_transitions, postings, journal_entries, attempts, approvals, revert_items, revert_plans, recon_findings, recon_runs, webhook_events, llm_turns, actions, tasks, runs cascade" >/dev/null

echo "== seeding Shopfront (no tickets, 400 orders) and dropping 30% of responses after commit"
curl -fsS -X POST $SHOP/admin/seed -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"seed":7,"customers":60,"orders":400,"tickets":0}' >/dev/null
curl -fsS -X POST $SHOP/admin/faults -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"preset":"calm","drop_after_commit_rate":0.3}' >/dev/null

RUN=$(curl -fsS -X POST $API/v1/runs -H "Authorization: Bearer $UNDO_DEMO_AGENT_KEY" -H 'Content-Type: application/json' -d '{"title":"kill test"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "== run $RUN: sending $N refunds (16 in parallel)"

(
  # SIGKILL the worker JVM (not PID 1: compose runs an init), so Docker sees a crash and restarts it.
  sleep 4; echo "== kill -9 worker (1)"; docker exec undo-undo-worker-1 pkill -9 java || true
  sleep 15; echo "== kill -9 worker (2)"; docker exec undo-undo-worker-1 pkill -9 java || true
) &
KILLER=$!

send_one() {
  local order; order=$(printf "ORD-%04d" "$1")
  curl -sS -o /dev/null -X POST "$API/v1/runs/$RUN/actions" \
    -H "Authorization: Bearer $UNDO_DEMO_AGENT_KEY" -H "Content-Type: application/json" -H "Idempotency-Key: kill-$1" \
    -d "{\"tool\":\"shop_issue_refund\",\"args\":{\"ticket_id\":null,\"order_id\":\"$order\",\"amount_minor\":1000,\"reason\":\"other\"},\"wait_ms\":2000}" || true
}
export -f send_one
export API RUN UNDO_DEMO_AGENT_KEY
seq 1 "$N" | xargs -P 16 -n 1 bash -c 'send_one "$0"'
wait $KILLER || true

echo "== faults off; waiting for every action to reach a terminal state"
curl -fsS -X POST $SHOP/admin/faults -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"preset":"calm"}' >/dev/null
for i in $(seq 1 120); do
  LEFT=$(psql_undo "select count(*) from actions where status in ('PROPOSED','APPROVED','EXECUTING','RETRY_WAIT','UNKNOWN')")
  [ "$LEFT" = "0" ] && break
  sleep 2
done

echo "== results"
echo "   worker restarts: $(docker inspect -f '{{.RestartCount}}' undo-undo-worker-1)"
TOTAL=$(psql_undo "select count(*) from actions where run_id = '$RUN'")
psql_undo "select status, count(*) from actions where run_id = '$RUN' group by status order by status" | sed 's/^/   /'
SUCC=$(psql_undo "select count(*) from actions where run_id = '$RUN' and status = 'SUCCEEDED'")
NONTERM=$(psql_undo "select count(*) from actions where status not in ('SUCCEEDED','FAILED','DENIED','REJECTED','EXPIRED','CANCELLED','NEEDS_REVIEW')")
UNKNOWNS=$(psql_undo "select count(distinct action_id) from action_transitions where to_status = 'UNKNOWN'")
INV=$(curl -fsS $SHOP/admin/invariants -H "X-Admin-Token: $SHOP_ADMIN_TOKEN")
REFUNDS=$(curl -fsS $SHOP/admin/stats -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" | sed -E 's/.*"refunds":\{"total":([0-9]+).*/\1/')
echo "   actions: $TOTAL, succeeded: $SUCC, went UNKNOWN at least once: $UNKNOWNS, non-terminal: $NONTERM"
echo "   Shopfront refunds: $REFUNDS"
echo "   Shopfront invariants: $INV"
FAIL=0
echo "$INV" | grep -qE '":[1-9]' && { echo "FAIL: a Shopfront invariant is not 0"; FAIL=1; }
[ "$NONTERM" = "0" ] || { echo "FAIL: $NONTERM actions are not terminal"; FAIL=1; }
[ "$TOTAL" = "$N" ] || { echo "FAIL: expected $N actions, got $TOTAL"; FAIL=1; }
[ "$REFUNDS" = "$SUCC" ] || { echo "FAIL: $REFUNDS refunds at Shopfront but $SUCC succeeded actions"; FAIL=1; }
[ $FAIL = 0 ] && echo "PASS"
exit $FAIL
