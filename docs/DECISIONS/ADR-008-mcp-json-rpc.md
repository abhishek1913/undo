# ADR-008: MCP server as a hand-written JSON-RPC subset

Status: accepted (M8)

Section 18 prefers the official MCP Java SDK and allows a minimal hand-written JSON-RPC subset
when the SDK cannot do per-request auth or session-to-run mapping cleanly. We use the subset.

Why:
- Every request must be authenticated by the agent key (`ApiKeyFilter`, the same filter as
  `/v1`) and a session must map to exactly one Undo run (`runs.mcp_session_id`). The SDK's
  Streamable HTTP transport owns session ids and the servlet; wiring our key principal and run
  creation into its session lifecycle needs more glue than the five methods themselves.
- Responses are plain JSON (no server-initiated streams are needed, 18), so the transport part is
  one Spring controller.

What is implemented (`dev.undo.server.mcp.McpController`):
- `POST /mcp` with one JSON-RPC message. `initialize` creates an EXTERNAL run titled
  `MCP: <clientInfo.name>` with the key's default policy (or attaches the ACTIVE run named by
  `X-Undo-Run-Id`), answers `protocolVersion` (the client's if it is one of 2025-11-25,
  2025-06-18, 2025-03-26, else 2025-11-25), `capabilities.tools`, `serverInfo`, and returns the
  session in the `Mcp-Session-Id` header.
- Notifications (no `id`, e.g. `notifications/initialized`) → HTTP 202, no body. `ping` → `{}`.
- `tools/list`: AGENT-visible tools matching the key's `allowed_tools` globs, with `inputSchema`
  (13.1) and descriptions.
- `tools/call` → `ProposalService.propose(caller = mcp, clientIdempotencyKey =
  params._meta["undo.dev/idempotency-key"], wait 10 s)`; result `content: [{type: text, text:
  render(out)}]`, `isError` per 13.4. A tool outside the key's scopes is JSON-RPC error -32602.
- `GET /mcp` → 405. `DELETE /mcp` → completes the session's run, 204. A missing session header →
  400; an unknown session or one of another agent → 404. Batches → 400.

Tests: `McpIT` (initialize/list/call/delete, `mcpToolsListFiltersByKeyScopes`, transport rules);
`scripts/mcp-check.sh` is the equivalent of MCP Inspector's calls against the compose stack.
