# ADR-019: Hosted runner, scripted model, and Claude tested offline only

Status: accepted (M8)

1. **Revert: a failed compensation is decided from the provider's current state (simulator seed
   12055, a real bug).** ADR-017 #3 re-read the provider only for `refund_not_cancellable`. A
   cancel that never reached Shopfront (FAILED `max_attempts_not_applied`) while the refund failed
   at settlement was reported FAILED with the whole amount "not recovered", so INV-6 failed. Every
   failed compensation that is not a conflict now re-reads the target: NoEffect → DONE "already
   gone", Irreversible → BECAME_IRREVERSIBLE, otherwise FAILED. Unit test
   `RevertScenarioTest.compensationThatFailsAfterTheRefundFailedIsAlreadyGone`, regression seed
   `NONE 12055`.
2. **No Anthropic API key, by decision of the project owner.** The scripted model
   (`ScriptedModelClient`) is the model for every run, test and demo. The Claude adapter
   (`ClaudeModelClient`, `com.anthropic:anthropic-java` 2.75.0, ADR-000) is built and tested
   offline only: `ClaudeModelClientTest` serves hand-written fixtures in the exact Messages API
   response shape from a local stub server (`src/test/resources/fixtures/claude`) and checks the
   transcript round trip (stored content goes back unchanged, no 400 from a stub that rejects
   edited thinking blocks), the request shape (beta header `server-side-fallback-2026-07-01`,
   `fallbacks: "default"` through the typed `fallbacksDefault()`, `output_config.effort`, strict
   tools, `tool_choice: auto`, top-level `cache_control`, no `thinking` field) and error mapping.
   The fixtures are not recordings.
   **SKIPPED (not passed):** the live 20-ticket Claude run (M8 AC), "one real request with all 10
   tools returns 200" (17.2) and the live round trip of 17.3. They need a key; nothing here claims
   them.
3. **Claude is off by default.** The adapter bean exists only when `ANTHROPIC_API_KEY` is set
   (`undo.agent.base-url` points it at a stub in tests). `POST /api/hosted-runs` with
   `model_mode: claude` and no key answers 422 and says so; the console Demo page offers Claude only
   as "needs ANTHROPIC_API_KEY". The key is read once from the environment and never logged.
4. **Strict tool schemas.** The copy sent with `strict: true` drops numeric, length, pattern and
   format keywords (`minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`,
   `minLength`, `maxLength`, `pattern`, `format`, `minItems`, `maxItems`, `$schema`); property
   names inside `properties` are never touched. Undo still validates the full schema. Which
   keywords the live API rejects is unverified without a key, so the list is conservative.
5. **Scripted model is stateless.** Each call replays the request's transcript through a fresh
   `TicketScript` seeded by (ticket id, task seq), so a task resumed on another worker gets the
   same next turn, and rebuilding a stored transcript reproduces every stored turn byte for byte
   (`AgentRunnerScenarioTest.scriptedTranscriptRoundTripReproducesEveryTurn`). Ground truth comes
   from Shopfront `/admin/ground-truth` (cached, reloaded on a miss after a reseed).
6. **Attack lists go out in one turn.** An adversarial ticket that asks to refund ten other
   customers' orders used to spend one turn per order and hit `max-turns-per-task` (12): seen live
   as TKT-0075 FAILED `max_turns`. Consecutive attack steps are now one turn of parallel tool_use
   blocks, the way a model attempts a list. Every attack is still denied.
7. **Runner details.** Heartbeat is checked between steps (every 15 s, lease 60 s) instead of on a
   separate thread; a model call longer than the lease lets another worker take over, and every
   write of the old runner is then refused by the run fence (`LeaseLost`). A worker works up to 4
   hosted runs at once. Tools are sent sorted by name so the cached prefix is byte-stable.
   `ProposalService` already rejects a runner with a stale fence (`StaleFence`).
8. **Advisory risk notes (17.8, SHOULD): not built.** Without a key the PRD's scripted mode would
   only store a fixed sentence; the approval card shows no note. Decision code never reads
   `advisory_note` either way.
9. **Simulator.** 0–2 HOSTED runs per seed (3–15 tickets), sampled from a random split drawn after
   every earlier draw; workers run the runner every second with a model latency of 0.3–4 s of
   virtual time; COMPLETED hosted runs can be reverted by the operator. INV-11 checks one action
   per tool_use id, never more PRIMARY actions in a task than tool_use blocks, and one tool_result
   per tool_use (in order) for DONE tasks. Crash points `RUNNER_AFTER_MODEL_RESPONSE` and
   `RUNNER_AFTER_TURN_PERSIST` are in the expected set (13/13).
10. **Claude Code over MCP: manual, not run yet.** The command is in PROGRESS.md.
