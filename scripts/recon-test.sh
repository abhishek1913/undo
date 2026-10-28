#!/usr/bin/env bash
# M7 recon test on the compose stack.
#  1. Shopfront drops every webhook (webhook_drop_rate=1). 30 refunds succeed; Shopfront then
#     settles 20 of them and a person cancels 5 at Shopfront. Undo never
#     hears about it. One on-demand recon must apply every status (STATUS_MISMATCH, auto-healed).
#  2. A second refund is created directly at Shopfront carrying a real undo_action_id. The next
#     recon must report it as CRITICAL DUPLICATE_EFFECT.
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .env || cp .env.example .env
set -a; source .env; set +a
export UNDO_DEMO=true
export UNDO_DEMO_AGENT_KEY=${UNDO_DEMO_AGENT_KEY:-undo_sk_ReconTest0000000000000000000000000000000000}
COMPOSE="docker compose -f deploy/docker-compose.yml --env-file .env"
API=http://localhost:8080
SHOP=http://localhost:8090
N=30
FAIL=0

echo "== starting the stack"
$COMPOSE up -d --wait --force-recreate undo-api undo-worker shopfront postgres >/dev/null
psql_undo() { docker exec -i undo-postgres-1 psql -U undo -d undo -tA -c "$1"; }
psql_shop() { docker exec -i undo-postgres-1 psql -U shopfront -d shopfront -tA -F' ' -c "$1"; }
psql_undo "truncate events, action_transitions, postings, journal_entries, attempts, approvals, revert_items, revert_plans, recon_findings, recon_runs, webhook_events, llm_turns, actions, tasks, runs cascade" >/dev/null
curl -fsS -X POST $SHOP/admin/seed -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"seed":7,"customers":60,"orders":400,"tickets":0}' >/dev/null
curl -fsS -X POST $SHOP/admin/faults -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"preset":"calm","webhook_drop_rate":1,"settlement_failure_rate":0.3}' >/dev/null

RUN=$(curl -fsS -X POST $API/v1/runs -H "Authorization: Bearer $UNDO_DEMO_AGENT_KEY" -H 'Content-Type: application/json' -d '{"title":"recon test"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "== run $RUN: $N refunds with every webhook dropped"
for i in $(seq 1 $N); do
  order=$(printf "ORD-%04d" "$i")
  curl -fsS -o /dev/null -X POST "$API/v1/runs/$RUN/actions" \
    -H "Authorization: Bearer $UNDO_DEMO_AGENT_KEY" -H "Content-Type: application/json" \
    -d "{\"tool\":\"shop_issue_refund\",\"args\":{\"ticket_id\":null,\"order_id\":\"$order\",\"amount_minor\":1000,\"reason\":\"other\"},\"wait_ms\":3000}"
done
echo "   succeeded: $(psql_undo "select count(*) from actions where run_id = '$RUN' and status = 'SUCCEEDED'")/$N"

echo "== Shopfront settles 20 refunds and a person cancels 5 more (no webhooks reach Undo)"
curl -fsS -X POST "$SHOP/admin/settlement/run?limit=20" -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" >/dev/null
for id in $(psql_shop "select id from refunds where status = 'pending' order by id limit 5"); do
  curl -fsS -o /dev/null -X POST "$SHOP/v1/refunds/$id/cancel" -H "Authorization: Bearer $SHOP_API_KEY" -H "Idempotency-Key: person-$id"
done
psql_shop "select status, count(*) from refunds group by status order by status" | sed 's/^/   shopfront: /'

mismatches() {
  psql_shop "select metadata->>'undo_action_id', status from refunds where metadata ? 'undo_action_id' order by 1" > "$TMP/shop.txt"
  psql_undo "select id, coalesce(provider_status, '-') from actions where run_id = '$RUN' and status = 'SUCCEEDED' order by 1" | tr '|' ' ' > "$TMP/undo.txt"
  join "$TMP/shop.txt" "$TMP/undo.txt" | awk '$2 != $3' | wc -l | tr -d ' '
}
TMP=$(mktemp -d)
BEFORE=$(mismatches)
echo "   statuses Undo has wrong before recon: $BEFORE"

echo "== console login as admin; recon now"
JAR="$TMP/cookies"
CSRF=$(curl -fsS -c "$JAR" -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"admin@undo.local\",\"password\":\"undo-demo-admin\"}" | sed -E 's/.*"csrf_token":"([^"]+)".*/\1/')
recon_now() {
  curl -fsS -b "$JAR" -X POST $API/api/recon/runs -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' -d '{}'
}
R1=$(recon_now)
echo "   $(echo "$R1" | sed -E 's/.*"stats":(\{[^}]*\{[^}]*\}[^}]*\}).*/\1/')"
AFTER=$(mismatches)
HEALED=$(psql_undo "select count(*) from recon_findings where type = 'STATUS_MISMATCH' and status = 'AUTO_HEALED'")
echo "   statuses wrong after recon: $AFTER; STATUS_MISMATCH auto-healed: $HEALED"
psql_undo "select kind, count(*) from journal_entries where kind like 'REFUND_%' group by kind order by kind" | sed 's/^/   journal: /'
if [ "$BEFORE" -eq 0 ] || [ "$AFTER" -ne 0 ] || [ "$HEALED" -ne "$BEFORE" ]; then FAIL=1; fi

echo "== a second refund at Shopfront with a real undo_action_id"
read -r ACTION ORDER < <(psql_undo "select id, args->>'order_id' from actions where run_id = '$RUN' and status = 'SUCCEEDED' order by number limit 1" | tr '|' ' ')
curl -fsS -o /dev/null -X POST $SHOP/v1/refunds -H "Authorization: Bearer $SHOP_API_KEY" -H "Idempotency-Key: by-hand-$ACTION" \
  -H 'Content-Type: application/json' \
  -d "{\"order_id\":\"$ORDER\",\"amount_minor\":100,\"reason\":\"other\",\"metadata\":{\"undo_action_id\":\"$ACTION\"}}"
recon_now >/dev/null
DUP=$(psql_undo "select severity || ' ' || type || ' ' || status from recon_findings where action_id = '$ACTION' and type = 'DUPLICATE_EFFECT'")
echo "   finding for $ACTION: ${DUP:-none}"
[ "$DUP" = "CRITICAL DUPLICATE_EFFECT OPEN" ] || FAIL=1

curl -fsS -X POST $SHOP/admin/faults -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"preset":"calm"}' >/dev/null
rm -rf "$TMP"
if [ $FAIL -eq 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
