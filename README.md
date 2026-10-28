# Undo

**Exactly-once actions and one-click rollback for AI agents.**

Undo is a service that sits between an AI agent and the real systems the agent changes: payments,
store credit, emails, orders. Every tool call the agent makes goes through Undo, and Undo makes
four promises:

1. **Exactly once.** If a worker crashes, the network drops, or the agent repeats itself, a refund
   still happens once. Never zero times by mistake, never twice.
2. **Policy before action.** Every call is checked against a written policy, a per-run budget and
   facts fetched from the real system. Risky calls wait for a human. The policy is code, not a
   prompt, so a ticket can't talk its way past it.
3. **One-click revert.** Every action records how to undo itself. Reverting a run undoes what can
   still be undone, in reverse order, and says clearly what can't be undone and why.
4. **Reconciliation.** Undo keeps comparing what it thinks happened with what the real system says
   happened, and reports every difference.

AI can propose. AI never decides what is correct.

## Run it

You need Docker and Java 21. Everything runs locally against a mock store (Shopfront), so no real
money moves.

```sh
make build && make up   # Postgres 17, Shopfront, the Undo API and workers, the console
                        # console: http://localhost:3000 · API: http://localhost:8080
make sim                # the deterministic simulator, 100,000 seeds
make sim-bugs           # plant known bugs and check that the simulator catches each one
make chaos              # 10 minutes of kill -9 against the real Docker stack
make bench              # the benchmarks
make down
```

`make` copies `.env.example` to `.env` on the first run. It holds local placeholders only.

## How a call flows

Every tool call takes the same ten steps:

| # | Step | What happens |
|---|---|---|
| 1 | Check the arguments | Every call is validated against the tool's JSON Schema. |
| 2 | Catch repeats | Same call id, or the same business key? Return the first result. |
| 3 | Fetch the facts | Read-only data from the store: who owns the order, what is refundable. |
| 4 | Decide by policy | Allow, ask a human, or deny. Deny is the default. |
| 5 | Reserve the budget | Money, action count and tokens, in one atomic update. |
| 6 | Write the intent | Saved to the ledger before anyone is called. |
| 7 | Call the store | With a stable idempotency key. |
| 8 | Record the outcome | Succeeded, failed, or UNKNOWN. Never a guess. |
| 9 | Resolve the unknowns | Same key, a lookup, or a person decides. |
| 10 | Keep the books | Journal postings, the undo plan, and every event. |

Workers hold leases with fence numbers, so a worker that wakes up after it was presumed dead
can't write over the one that took its place.

## Proof

Every number here was measured on 2026-10-24 on an Apple M4 Pro (12 cores, Docker Desktop with
12 CPUs and 7.65 GiB). The details and raw output are in
[docs/BENCHMARKS.md](docs/BENCHMARKS.md) and [docs/CHAOS_RESULTS.md](docs/CHAOS_RESULTS.md).

| What | Result | Command |
|---|---|---|
| Chaos: 10 minutes, 30 SIGKILLs of workers, 3,508 write actions | **0 duplicate provider effects**. Undo and Shopfront agree on all 581 refunds, 967 emails and 181 store credits. Recovery always started within 30.4 s. | `make chaos` |
| Simulator: 100,000 seeds at 8 threads | **0 violations**, 268 seeds/s | `ONLY=b6 make bench` |
| Undo's own overhead per call (p50 / p95 / p99) | 3.7 / 5.6 / 10.3 ms at 200 req/s | `ONLY=b1 make bench` |
| Queue drain, 5,000 approved actions | 1,108 actions/s with 2 workers | `ONLY=b2 make bench` |
| Policy evaluation | 853 ns/op | `ONLY=b4 make bench` |
| Revert preview of a run with 500 actions | 630 ms | `ONLY=b7 make bench` |

**Not everything is green yet.** A one-million-seed simulator run breaks one revert invariant
(INV-6) in 22 seeds. It is written down in [PROGRESS.md](PROGRESS.md) and still open.

## What's inside

| Path | What it is |
|---|---|
| `undo-core` | The engine. Deterministic: it never reads the clock, makes random numbers, starts threads or does I/O itself; it goes through ports. An ArchUnit test enforces this. |
| `undo-server` | Spring Boot 3.5 API and workers on PostgreSQL 17 with `JdbcClient` (no JPA): REST for agents, approvals, webhooks, server-sent events, the MCP server and the hosted runner. |
| `undo-sim` | A simulator that runs the real engine against crashes, timeouts, lost and duplicate messages and a misbehaving agent, checking the invariants after every step. |
| `shopfront-core`, `shopfront-server` | Shopfront, a mock payments and orders provider with idempotency keys, settlement, signed webhooks and injectable faults. |
| `connector-shopfront` | The connector between Undo and Shopfront, behind the connector SPI. |
| `console` | The React console: runs, approvals, revert previews, reconciliation findings. |
| `policies` | The default support policy (YAML). |
| `chaos`, `bench`, `deploy` | The chaos test, the benchmarks (k6, JMH) and the Docker Compose stack with Prometheus and Grafana. |
| `showcase` | A scroll-driven film that tells the story in six chapters. |

The hosted runner has a Claude adapter. It is tested with a scripted model; a live Claude run is
still to do. Any MCP client can use Undo through its MCP server.

## Docs

- [docs/PRD.md](docs/PRD.md): the product and the full build spec
- [docs/SIMULATION.md](docs/SIMULATION.md): the simulator and the invariants
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md) and [docs/CHAOS_RESULTS.md](docs/CHAOS_RESULTS.md): how the numbers above were measured
- [docs/DECISIONS](docs/DECISIONS): architecture decision records
- [PROGRESS.md](PROGRESS.md): what is done, what is next, known issues

## Status

Milestones M0 to M9 are done: the engine, Shopfront, the simulator, the server on Postgres,
approvals and budgets, revert, reconciliation, the hosted runner with MCP, and chaos, benchmarks
and observability. M10 (polish and story) is next.

## Licence

[MIT](LICENSE) © 2026 Abhishek Shukla
