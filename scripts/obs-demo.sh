#!/usr/bin/env bash
# M9 observability check: a scripted demo run on the obs profile, then every panel of the
# provisioned Grafana dashboard "Undo overview" is evaluated through Grafana's own HTTP API
# (datasource proxy to Prometheus). Passes when every panel query returns at least one series with a sample.
# Needs: make up-obs (UNDO_DEMO=true in .env). Reseeds Shopfront and empties Undo's run data.
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .env || cp .env.example .env
set -a; source .env; set +a
API=http://localhost:8080; SHOP=http://localhost:8090; PROM=http://localhost:9090; GRAF=http://localhost:3001
FAIL=0
psql_undo() { docker exec -i undo-postgres-1 psql -U undo -d undo -tA -c "$1"; }
TMP=$(mktemp -d); JAR="$TMP/cookies"
CSRF=$(curl -fsS -c "$JAR" -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@undo.local","password":"undo-demo-admin"}' | sed -E 's/.*"csrf_token":"([^"]+)".*/\1/')
post() { curl -fsS -b "$JAR" -X POST "$API$1" -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' -d "$2"; }

echo "== demo: seed 42, flaky network, 60-ticket scripted adversarial run, worker kill, settlements, recon"
post /api/demo/seed '{"seed":42}' >/dev/null
post /api/demo/faults '{"preset":"flaky","drop_after_commit_rate":0.2}' >/dev/null
RUN=$(post /api/hosted-runs '{"scenario":"support-triage","model_mode":"scripted","limit":60,"adversarial":true}')
RID=$(echo "$RUN" | sed -E 's/.*"id":"([^"]+)".*/\1/'); RNUM=$(echo "$RUN" | sed -E 's/.*"number":([0-9]+).*/\1/')
sleep 4; post /api/demo/kill-worker '{}' >/dev/null || docker exec undo-undo-worker-1 pkill -9 java || true
for _ in $(seq 1 420); do
  S=$(psql_undo "select status from runs where id = '$RID'"); [ "$S" != "ACTIVE" ] && break; sleep 1
done
echo "   run $RNUM: $S"
post /api/demo/faults '{"preset":"calm","webhook_drop_rate":1}' >/dev/null
post /api/demo/settle "{\"limit\":10,\"run_number\":$RNUM}" >/dev/null
# a refund created straight at the provider for an action that never ran (a DENIED refund): a CRITICAL recon finding
ACTION=$(psql_undo "select id from actions where run_id = '$RID' and status = 'DENIED' and tool = 'shop_issue_refund' order by number limit 1")
ORDER=$(docker exec -i undo-postgres-1 psql -U shopfront -d shopfront -tA -c "select id from orders where id not in (select order_id from refunds) and total_minor > 100 order by id desc limit 1")
curl -fsS -o /dev/null -X POST $SHOP/v1/refunds -H "Authorization: Bearer $SHOP_API_KEY" -H "Idempotency-Key: by-hand-$ACTION" \
  -H 'Content-Type: application/json' \
  -d "{\"order_id\":\"$ORDER\",\"amount_minor\":100,\"reason\":\"other\",\"metadata\":{\"undo_action_id\":\"$ACTION\"}}" || echo "   (provider refused the by-hand refund)"
post /api/recon/runs '{}' >/dev/null
echo "   recon findings: $(psql_undo "select string_agg(severity || ' ' || type || ' ' || status, ', ') from recon_findings")"
post /api/demo/faults '{"preset":"calm"}' >/dev/null
echo "   waiting 50 s for the 30 s recon round, the 10 s gauges and the 5 s scrapes"; sleep 50

echo "== every panel of the provisioned dashboard, evaluated through Grafana"
curl -fsS -u admin:admin "$GRAF/api/dashboards/uid/undo-overview" > "$TMP/dash.json"
python3 - "$TMP/dash.json" "$GRAF" <<'PY' || FAIL=1
import base64, json, sys, urllib.parse, urllib.request
dash = json.load(open(sys.argv[1]))["dashboard"]
graf = sys.argv[2]
bad = 0
for p in dash["panels"]:
    series = 0; top = 0.0
    for t in p["targets"]:
        url = graf + "/api/datasources/proxy/uid/prometheus/api/v1/query?" + urllib.parse.urlencode({"query": t["expr"]})
        req = urllib.request.Request(url, headers={"Authorization": "Basic " + base64.b64encode(b"admin:admin").decode()})
        res = json.load(urllib.request.urlopen(req))["data"]["result"]
        vals = [float(r["value"][1]) for r in res if r["value"][1] not in ("NaN",)]
        series += len(vals); top = max([top] + vals)
    ok = series > 0
    bad += (not ok)
    print(f"   {'OK  ' if ok else 'EMPTY'} {p['title']}: {series} series, highest value {top:g}")
print(f"   {len(dash['panels']) - bad}/{len(dash['panels'])} panels with data")
sys.exit(1 if bad else 0)
PY
echo "== Prometheus: required metric families present"
for m in undo_actions_proposed_total undo_action_outcomes_total undo_action_execution_seconds_count undo_action_overhead_seconds_count \
  undo_actions_by_status undo_approvals_pending undo_unknown_resolutions_total undo_unknown_resolution_seconds_count undo_recon_findings_open \
  undo_provider_requests_total undo_llm_tokens_total undo_llm_turn_seconds_count undo_webhooks_total undo_lease_expirations_total; do
  N=$(curl -fsS "$PROM/api/v1/query" --data-urlencode "query=count($m)" | python3 -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else 0)")
  printf "   %-45s %s\n" "$m" "$N"; [ "$N" != "0" ] || FAIL=1
done
echo "== Jaeger: spans of service undo"
SPANS=$(curl -fsS "http://localhost:16686/api/services" | python3 -c "import json,sys; print(','.join(json.load(sys.stdin)['data']))")
echo "   services: $SPANS"
OPS=$(curl -fsS "http://localhost:16686/api/operations?service=$(echo "$SPANS" | cut -d, -f1)" | python3 -c "import json,sys; print(' '.join(sorted({o['name'] for o in json.load(sys.stdin)['data']})))")
echo "   operations: $OPS"
rm -rf "$TMP"
if [ $FAIL -eq 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
