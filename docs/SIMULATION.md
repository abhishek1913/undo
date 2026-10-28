# Simulation

The simulator runs the **real** engine (`undo-core` services, the real `ShopfrontConnector`, the
real Shopfront `ShopRouter`) in a simulated world where time, randomness, network and crashes come
from one seed. Any failure is reproducible from its seed.

## How to run it

```
make sim SEEDS=100000          # many seeds, prints violations and coverage, runs regression seeds
make sim-bugs                  # every bug flag, first seed that catches it (max 20,000 each)
make sim-seed SEED=88421       # one seed with a full trace in out/seed-88421.log
make sim-seed SEED=2 BUG=RETRY_UNKNOWN_WITHOUT_LOOKUP   # replay a failure
```

Underneath: `java -jar undo-sim/target/undo-sim.jar --seeds N [--start S] [--threads T] [--bug NAME]
[--assume-a1=false] [--seed X --trace FILE] [--report out/sim-report.json] [--all-bugs]`.
Exit code 1 on any violation, 2 when ≥ 10,000 seeds ran and a crash point was never hit.

## The world of one seed

- **Store:** `InMemoryStore`, with the same unique/check constraints as the Postgres DDL, journal
  balance at commit, identity values that are never reused. It passes the same
  `StoreContractTest` suite as `PostgresStore`.
- **Shopfront:** the real router on an in-memory store (20 customers, 80 orders, 60 tickets).
- **Workers:** 1–4, each with its own node id, engine services and loops. A worker can crash at
  any crash point and restarts after 1–30 s with a new node id.
- **Network:** per request: delivery delay (1–1,500 ms; 2–5% slow deliveries of 10–65 s, after
  the 10 s client timeout but inside assumption A1's 70 s), dropped requests, lost responses
  (timeout or reset), 5xx and 429 before handling, process stalls after a response, provider
  outage windows.
- **Agents:** 1–3 EXTERNAL runs of 5–40 tickets, worked by the scripted ticket agent
  (`TicketScript`) with seeded `adversarial`, `repeat_call_rate` and `parallel_dup_rate`. Calls go
  to a random live node; when it dies the agent retries with the same Idempotency-Key.
- **Operator:** approves or rejects approvals after 1 s–10 min (5% with a wrong hash first),
  freezes 20% of runs, edits notes, spends credit, resolves NEEDS_REVIEW from provider truth.
  With p = 50% it asks for a revert 5 s–10 min after a run ends (70% scope RUN, 30% ACTIONS with
  a random subset of the write actions; half of the ACTIONS reverts are followed by a RUN revert,
  which finds the earlier targets ALREADY_REVERTED). A blocked preview is refreshed every 60 s
  until it can execute.
- **Hosted runs:** 0–2 per seed (3–15 tickets each), created at 10 s and 50 s, worked by the
  hosted runner on the workers with the scripted model (0.3–4 s of virtual time per model turn).
  A worker that crashes mid-run leaves its lease to expire (60 s); another worker resumes the
  task from its last persisted turn. COMPLETED hosted runs can be reverted by the operator.
- **Workers** also run the revert executor every 300 ms (one step per open plan) and the recon
  scheduler (every 60 s each worker checks whether the connector is due; due every 10 virtual
  minutes; one node at a time through the job lease `recon:shopfront`). With p = 50% the
  operator also asks for an on-demand recon during chaos.
- **Phases:** chaos until every agent is done and every requested revert has started (or 2 h), then quiescence (faults and crashes off,
  the operator finishes every open decision) until nothing is in flight (or 4 h). Then one
  on-demand reconciliation (`sim:final`, last 24 h), then the final checks.

What the world adds beyond Section 21.4, and why, is in ADR-015.

## How blocking code runs deterministically

Core code is plain blocking code. Every sim activity (a claimed execution, an agent request, an
operator decision) is a sim task on its own virtual thread. A baton makes sure exactly one thread
runs at a time: a task gives it back only at a blocking point (a provider call, a sleep, a wait for
an action to change, or its end). The event loop pops `(time, seq)` events and resumes exactly one
task. Same seed → same order → same result. A crashed worker's tasks are resumed with
`SimulatedCrash` (an `Error`, never caught by core code) so they unwind without running more
engine code.

## Invariants

Checked after every commit (INV-3, INV-7 edges) and at the end (all). Checked so far: INV-1 to
INV-10, 12, 13 (INV-2 and INV-9 since M7: the final recon must complete without errors, no
CRITICAL finding may be open, and every provider status Undo missed must be applied or reported);
INV-11 since M8 (one action per tool_use id; never more actions in a task than tool_use blocks;
one tool_result per tool_use for DONE tasks). INV-6 also
checks that an ended plan went through EXECUTING and that a RUN plan's run ends in the same status.

## Results

Machine: Apple M4 Pro (12 cores), 24 GiB RAM, macOS 27.0.1, Java 21.0.9 (Temurin).

| Run | Command | Result |
|---|---|---|
| 100,000 seeds (M3) | `make sim SEEDS=100000` | 0 violations · 780 seeds/s · crash points 8/8 hit (min 36,412 reached, min 435 crashed) · action transitions 23/23 legal edges · attempt outcomes 8/8 · regression seeds 9/9 |
| 100,000 seeds (M6, with webhooks and reverts) | `make sim SEEDS=100000` | 0 violations · 622 seeds/s · crash points 10/10 hit (min 153,172 reached, min 609 crashed) · action transitions 23/23 · attempt outcomes 8/8 · revert item kinds 7/7 · regression seeds 10/10 |
| 100,000 seeds (M7, with recon: scheduled, operator and final) | `make sim SEEDS=100000` | 0 violations · 616 seeds/s · crash points 11/11 hit (min 235,202 reached, min 628 crashed) · action transitions 23/23 · attempt outcomes 8/8 · revert item kinds 7/7 · finding types 3/9 (GHOST_EFFECT/WARNING 144,445 auto-healed, STATUS_MISMATCH/INFO 49,102 auto-healed, UNMATCHED_WEBHOOK/WARNING 17; the other six are CRITICAL and appear only with bug flags) · regression seeds 12/12 |
| 100,000 seeds (M8, with hosted runs) | `make sim SEEDS=100000` | 0 violations · 448 seeds/s · crash points 13/13 hit (min 290,775 reached, min 1,317 crashed) · action transitions 23/23 · attempt outcomes 8/8 · revert item kinds 7/7 · finding types 3/9 · regression seeds 14/14 |
| 1,000,000 seeds (M9) | `make sim SEEDS=1000000` | **22 violations, all INV-6, in 22 seeds** (lowest seed 102230) · 438 seeds/s (38 min, 12 threads) · crash points 13/13 hit (min 2,913,362 reached, min 13,961 crashed) · action transitions 23/23 · outcomes 8/8 · revert item kinds 7/7 · finding types 3/9 · regression seeds 14/14. Open: revert plan reports are frozen when the plan ends, and money that comes back later (a later plan, a refund that fails at settlement) does not update them; see PROGRESS.md, Known issues |
| 100,000 seeds, A1 off (M9) | `--assume-a1=false` | 14,322 violations in 4,799 seeds, expected; see the A1 section |

## The A1 experiment: `--assume-a1=false` (M9, PRD 21.8)

Assumption A1: a request the provider has not answered within `provider_timeout + lookup_grace`
will never take effect later. Everything the engine does after an UNKNOWN rests on it: a lookup
that says NotFound after that grace is trusted, and the action ends FAILED with no effect. With
`--assume-a1=false` the simulated network may deliver a request up to 180 s late (65 s with A1),
so a request can land after the lookup that trusted its absence.

```
java -jar undo-sim/target/undo-sim.jar --seeds 100000 --threads 12 --assume-a1=false --report out/sim-a1-off-report.json
```

Run on 2026-10-24 (M9 build, Apple M4 Pro): 100,000 seeds in 4 m 15 s (391 seeds/s), **14,322
violations in 4,799 seeds** (4.8 %). Coverage was complete (crash points 13/13, 23/23 legal
edges); regression seeds 14/14. The same build with A1 has 0 violations (next section). The
class is the idempotency class of the tool the violation names (the simulator now prints and
reports this table).

| Invariant | LOOKUP_BY_REFERENCE (store credit) | NATIVE_KEY (refund) | NONE (email) | no tool |
|---|---|---|---|---|
| INV-1 at most one provider effect per action | **4,346** in 4,257 seeds | **0** | 0 | — |
| INV-2 SUCCEEDED ⇔ exactly one provider object; FAILED etc. ⇔ none | 4,400 in 4,307 seeds | 63 in 63 seeds | 461 in 460 seeds | — |
| INV-9 no CRITICAL finding after the final recon | 4,550 in 4,306 seeds | 62 in 62 seeds | 411 in 410 seeds | — |
| INV-6 revert accounting | — | — | — | 29 in 29 seeds |

What it shows:

- **Duplicates (INV-1) only for LOOKUP_BY_REFERENCE, never for NATIVE_KEY.** A store credit is
  re-executed after a lookup by reference says NotFound. If the first request lands after that,
  there are two credits for one action (for example seed 16: `#A-0111 shop_grant_store_credit`,
  two provider objects, then a CRITICAL DUPLICATE_EFFECT). A refund is re-executed with the same
  `Idempotency-Key`; Shopfront answers the late twin with the stored response, so however late
  it arrives there is one refund. That is what A1 buys and why provider idempotency keys are the
  strongest class: they do not depend on timing at all.
- **Ghost effects (INV-2, INV-9) for every class.** When the engine stops retrying, for example
  max attempts reached, a frozen run, or a NONE-class action a person resolved as "did not
  happen" after the provider showed nothing, it trusts NotFound and ends the action FAILED. A
  request that lands after that leaves a provider object for a FAILED action (577 of the INV-2
  rows are FAILED, 1 CANCELLED). Seed 1047: `#A-0241 shop_issue_refund FAILED` with a pending
  refund `RF-MZZ2PLUV` at Shopfront. Recon reports each one as CRITICAL GHOST_EFFECT, so nothing
  is silent; but recon only detects after the fact, it cannot prevent it.
- **INV-6 (29 seeds)** follows from the ghosts: a revert plan accounts for money with the
  provider state it saw, and a late effect changes that state afterwards (seed 5318: a
  compensated credit is active again).

The nightly workflow runs the same command (`|| true`, since violations are the expected result)
and keeps the report.

## Bug flags (tests that test the tests)

Each flag makes the engine do one specific wrong thing. `make sim-bugs` must catch every flag
within 20,000 seeds. First failing seed (also in `undo-sim/src/test/resources/regression-seeds.txt`):

| Flag | Wrong behavior | First seed (M8 world) | Invariants broken at that seed |
|---|---|---|---|
| `NEW_KEY_PER_ATTEMPT` | provider idempotency key includes the attempt number | 1 | INV-2, INV-9 |
| `NO_FENCE_CHECK` | outcome write ignores the fence | 6 | engine transition guard, INV-9 |
| `RETRY_UNKNOWN_WITHOUT_LOOKUP` | LOOKUP_BY_REFERENCE re-executes at once on UNKNOWN | 2 | INV-1, INV-2, INV-9 (two store credits) |
| `SKIP_LOOKUP_GRACE` | NotFound trusted at once, no A1 grace | 2 | INV-1, INV-2, INV-9 |
| `BUDGET_CHECK_THEN_ACT` | budget read in one TX, reserved in another | 33 | INV-13, engine error, INV-9 |
| `APPROVAL_NOT_HASH_BOUND` | approve does not compare the submitted hash | 17 | INV-4 |
| `NONE_CLASS_RETRY` | NONE-class UNKNOWN actions are retried | 2 | INV-1, INV-2, INV-9 (duplicate emails) |
| `RESOLVER_IGNORES_FREEZE` | UNKNOWN re-executed on frozen runs | 13 | INV-7 |
| `REVERT_TRUSTS_PREVIEW` | a COMPENSATE item is marked DONE with the preview's money, without looking at the compensation's outcome | 2 | INV-6 |
| `JOURNAL_SKIPS_EXISTENCE_CHECK` | journal writes skip the `(action_id, kind)` check, so a cancelled refund is posted by the compensation and again by the webhook or the recon heal | 2 | INV-13, INV-9 |
| `RUNNER_NO_CALL_ID` | the runner proposes without `agentCallId`, so a task resumed after a crash proposes its last turn again | 2 | INV-11 (a task has more actions than tool_use blocks) |

The seeds change whenever the world gains features (each world is sampled from the seed), so they
are re-hunted with `make sim-bugs` and stored again (the table above is the M8 hunt).
All 11 flags of Section 21.7 are implemented.

## Real bugs the simulator found

| Seed | Invariant | Bug | Fix |
|---|---|---|---|
| 2183 | INV-8 | An action was EXECUTING when its run was frozen; a 429 then moved it to RETRY_WAIT, and no worker ever claims PRIMARY actions of a frozen run, so it stayed there forever. | A not-applied answer on a frozen run ends the action `FAILED not_applied_run_frozen` (ADR-014 #7, unit test `notAppliedAnswerAfterFreezeEndsTheAction`). |
| — | determinism | Wake-ups after a commit were ordered by `Set.copyOf`, whose order changes per JVM, so a seed's trace differed between runs. | Insertion-ordered sets (ADR-015), `DeterminismTest`. |
| 17594 | INV-6 | A refund was resolved SUCCEEDED by a lookup after the provider had already failed it. `applySuccess` posted `REFUND_ISSUED` but not `REFUND_FAILED`, so the ledger showed money out that never left. | `applySuccess` also posts the entry for the provider status it saw (ADR-014 #8, unit test `successFoundByLookupAfterTheRefundFailedPostsTheFailedEntry`). |
| 18140 | INV-6 | A refund failed at the provider while its cancel was queued; the cancel answered `refund_not_cancellable` and the item was reported irreversible with the whole amount lost. | On `refund_not_cancellable` the revert re-reads the provider state and decides again: gone → DONE with nothing lost (ADR-017 #3, unit test `refundThatFailsWhileItsCancelIsQueuedIsAlreadyGone`). |
| 1235 (while adding follow-up reverts) | INV-6 | A RUN revert after an ACTIONS revert marked a partly recovered store credit ALREADY_REVERTED with nothing lost, so the plan's numbers missed the spent part. | ALREADY_REVERTED items carry what the earlier compensation did not recover (ADR-017 #4, unit test `runRevertAfterActionsRevertCountsWhatWasNotRecovered`). |
| 35294 (M7 world) | INV-6 | A revert step whose plan lease had expired created a compensation after a new holder started; the new holder read the provider state (credit already revoked by that compensation) and marked the item "already gone" with nothing recovered. | When the reversal is not COMPENSATE the step looks for a live compensation of the target again and links it (ADR-018 #10, unit test `lateCompensationOfAnEarlierLeaseHolderIsLinkedNotAlreadyGone`, regression seed `NONE 35294`). |
| 12055 (M8 world) | INV-6 | A cancel never reached Shopfront (FAILED after max attempts) while the refund failed at settlement; the item was reported FAILED with the whole amount "not recovered". | Every failed compensation that is not a conflict re-reads the provider state (ADR-019 #1, unit test `compensationThatFailsAfterTheRefundFailedIsAlreadyGone`, regression seed `NONE 12055`). |
| 55498 (M8 world, simulator bug) | INV-9, INV-6 | The simulated operator resolved a NEEDS_REVIEW cancel as "happened" without checking that the refund was cancelled (it had settled). Recon reported it as CRITICAL COMPENSATION_MISMATCH, which is the point of recon; the operator was wrong, not the engine. | The operator checks the provider for compensations too (refund `cancelled`, credit `revoked`, ticket `OPEN`). |
