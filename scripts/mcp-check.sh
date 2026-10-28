#!/usr/bin/env bash
# M8 MCP check: a plain JSON-RPC client (curl) against http://localhost:8080/mcp, the same calls
# MCP Inspector makes: initialize → notifications/initialized → tools/list → tools/call
# shop_get_ticket → DELETE (ends the session, completes its run).
# Key: $UNDO_KEY, else a new agent key made through the console API as the demo admin.
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .env && { set -a; source .env; set +a; }
API=${UNDO_URL:-http://localhost:8080}
URL=$API/mcp
KEY=${UNDO_KEY:-}
if [ -z "$KEY" ]; then
  JAR=$(mktemp)
  CSRF=$(curl -fsS -c "$JAR" -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
    -d '{"email":"admin@undo.local","password":"undo-demo-admin"}' | sed -E 's/.*"csrf_token":"([^"]+)".*/\1/')
  AGENT=$(curl -fsS -b "$JAR" -X POST "$API/api/agents" -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
    -d "{\"name\":\"mcp-check-$(date +%s)\"}" | sed -E 's/.*"id":"([^"]+)".*/\1/')
  KEY=$(curl -fsS -b "$JAR" -X POST "$API/api/agents/$AGENT/keys" -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' \
    -d '{"allowed_tools":["*"],"default_policy":"support-default"}' | sed -E 's/.*"key":"([^"]+)".*/\1/')
  rm -f "$JAR"
  echo "== made agent key ${KEY:0:12}… (shown once; console → Settings → Keys)"
fi
H=(-H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
TMP=$(mktemp -d)

echo "== initialize"
curl -fsS -D "$TMP/h" "${H[@]}" -X POST "$URL" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-check","version":"1"}}}'
echo
SESSION=$(grep -i '^mcp-session-id:' "$TMP/h" | awk '{print $2}' | tr -d '\r')
echo "   session: $SESSION"
curl -fsS -o /dev/null -w "== notifications/initialized → HTTP %{http_code}\n" "${H[@]}" -H "Mcp-Session-Id: $SESSION" -X POST "$URL" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

echo "== tools/list"
curl -fsS "${H[@]}" -H "Mcp-Session-Id: $SESSION" -X POST "$URL" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' > "$TMP/list"
python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["result"]["tools"]; print("  ", len(t), "tools:", ", ".join(x["name"] for x in t))' "$TMP/list"

echo "== tools/call shop_get_ticket TKT-0001"
curl -fsS "${H[@]}" -H "Mcp-Session-Id: $SESSION" -X POST "$URL" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"shop_get_ticket","arguments":{"ticket_id":"TKT-0001"}}}' > "$TMP/call"
python3 -c '
import json,sys
r=json.load(open(sys.argv[1]))["result"]; t=json.loads(r["content"][0]["text"])
print("   isError:", r["isError"], "· status:", t["status"], "· action:", t["action"], "· ticket:", t["result"]["id"], "-", t["result"]["subject"] if "subject" in t["result"] else "")
sys.exit(0 if not r["isError"] and t["status"]=="succeeded" else 1)' "$TMP/call"

curl -fsS -o /dev/null -w "== DELETE (end session) → HTTP %{http_code}\n" "${H[@]}" -H "Mcp-Session-Id: $SESSION" -X DELETE "$URL"
rm -rf "$TMP"
echo PASS
