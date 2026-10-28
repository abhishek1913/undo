# ADR-015: Simulator world details beyond Section 21.4

**Context.** Section 21.4 lists what to sample per seed. Building the simulator showed that some
code paths can never happen with only those faults, so their bugs could never be caught. The
simulator must also run fast enough (≥ 300 seeds/s at M3, target 500).

**Decision.** In addition to 21.4, each seed samples:

| Addition | Why |
|---|---|
| **Process stalls** after a response arrived (0–2% of provider calls, 25–90 s) | The provider timeout (10 s) is shorter than the action lease (30 s), so a live worker can never outlive its lease on its own. Real processes pause (GC, VM suspend). A stall *after* the response creates true zombies whose outcome write carries a stale fence (INV-10) without breaking assumption A1 (the request was already applied or refused). |
| **Provider outage windows** (30% of seeds, 1–3 windows of 1–15 min, connection refused or timeout) | Needed for `UNKNOWN → NEEDS_REVIEW` (max unknown age) and long `facts_unavailable` / lookup `Unavailable` streaks. |
| **max unknown age** 1 h (50%) or 3–20 min (50%) | Same reason; with 1 h only, no seed ever stays UNKNOWN that long. |
| **Approval expiry** 24 h (50%) or 5–60 min (50%), and 10% of cards are left alone until quiescence | Needed for `AWAITING_APPROVAL → EXPIRED`. |
| **Inline execution off** in 30% of seeds | Queued `APPROVED` actions, so freezes hit `APPROVED → CANCELLED`. |
| **Agent concurrency** 1–3 tickets in parallel per run | Real agents work in parallel; needed for `BUDGET_CHECK_THEN_ACT` races. |
| **Tight budgets** in 30% of runs (₹500–₹5,000, 5–45 actions) | Exercises budget denials and the reservation race. |
| **Worker loop intervals** sampled (executor 0.2–2 s, sweeper 1–5 s, resolver 0.5–3 s) | Different interleavings per seed; correctness must not depend on the server's exact intervals. |

The ranges of 21.4 are kept as given (workers 1–4, runs 1–3, 5–40 tickets, crash 0–5%, delays,
drops, 5xx, 429, webhooks, settlement, operator behavior, restart delay 1–30 s).

**Performance.** Each seed's event loop runs on a virtual thread, like its sim tasks, so the baton
handoff between them stays inside the JDK's virtual-thread scheduler (3x faster than a platform
loop thread). Idle worker ticks first peek at committed state through store indexes and skip the
transaction when there is nothing to do. INV-3 is checked incrementally after every commit.

**Determinism.** Nothing in a seed may iterate a `Set.of` / `Set.copyOf` / `Map.of` collection:
their iteration order is randomized per JVM. The commit listener originally used `Set.copyOf` and
the simulator itself caught it (same seed, different traces across JVM runs). All such places use
insertion-ordered collections; `DeterminismTest` and `make sim-seed` (run twice, `cmp`) guard it.

**INV-12 interpretation.** Section 9.3 P7 records a PRIMARY call of an INTERNAL tool as a
`DENIED` action (reason `internal_tool`), so the row exists. INV-12 is checked as "no INTERNAL tool
action with kind PRIMARY is ever in a status other than DENIED".

**Consequences.** More code paths are covered (23/23 legal action edges); the extra faults are all
things that happen to real systems.
