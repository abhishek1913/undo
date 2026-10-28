# Benchmarks

PRD Section 23. Every number below was measured on 2026-10-24 by the command next to it. Raw
output lands in `out/bench/` (not committed).

**Machine:** Apple M4 Pro, 12 cores, 24 GiB RAM, macOS (Darwin 27.0.0, arm64), OpenJDK 21.0.9
(Temurin). **Docker Desktop:** 12 CPUs, 7.65 GiB. **Code:** the M9 working tree on top of
commit `973ab31` (the M9 commit itself carries these files). The stack is the default compose
profile: one Postgres 17, one Shopfront, one `undo-api` (512 MiB heap), N `undo-worker`.

`make bench` runs everything except B3, which comes from `make chaos`. `ONLY="b1 b2" make bench`
runs a subset.

## Results

| Bench | What | Result |
|---|---|---|
| B1 | Inline overhead (Undo only, from `Server-Timing`) | p50 / p95 / p99: **5.3 / 8.2 / 13.5 ms** at 50 req/s · **3.7 / 5.6 / 10.3 ms** at 200 req/s · **4.0 / 8.1 / 16.5 ms** at 500 req/s |
| B2 | Queue drain, 5,000 approved actions | **623 actions/s** (1 worker) · **1,108** (2) · **850** (4) |
| B3 | Recovery after a worker kill (30 kills) | max / p95 to recovery start: NATIVE_KEY **30.4 / 30.3 s**, NATURAL **29.3 / 29.3 s**; NONE to NEEDS_REVIEW **29.9 / 29.4 s** |
| B4 | Policy evaluation (support-default, refund with facts) | **853 ns/op** ± 4 |
| B5 | JCS of 1 KB args / args_hash (JCS + SHA-256) | **3,338 ns/op** ± 31 / **4,360 ns/op** ± 337 |
| B6 | Simulator at 8 threads | **268 seeds/s** (100,000 seeds, 0 violations) |
| B7 | Revert preview of a run with 500 SUCCEEDED actions | **630 ms** |

## B1: inline overhead

```
ONLY=b1 make bench     # k6 1.2.3 (grafana/k6 image) running bench/k6/propose.js
```

REST propose of `shop_set_order_note` (NATURAL, fast) with `wait_ms: 2000`, so it executes
inline. Shopfront on the `calm` preset (no faults, latency 0). Three constant-arrival-rate stages
of 30 s at 50, 200 and 500 req/s, 10 s apart. Undo's overhead is the `undo;dur=` part of the
`Server-Timing` header (wall time of the request minus the provider call).

| Rate | Requests | Undo overhead p50 / p95 / p99 / max | Provider (all stages) | Request p50 / p95 / p99 |
|---|---|---|---|---|
| 50 req/s | 1,501 | 5.29 / 8.17 / 13.47 / 77.8 ms | p50 0.75 ms, p99 3.0 ms | 8.2 / 13.2 / 21.4 ms |
| 200 req/s | 6,001 | 3.70 / 5.59 / 10.30 / 51.5 ms | | 5.4 / 8.5 / 14.8 ms |
| 500 req/s | 15,000 | 3.98 / 8.11 / 16.45 / 59.8 ms | | 5.5 / 11.4 / 22.6 ms |

All 22,502 actions SUCCEEDED inline, 0 failed requests, 0 dropped iterations. The request time
includes Docker Desktop's port forwarding from the k6 container to the host and back. The 50 req/s
stage runs first on a cold JIT, which is why its median is the highest.

Setup notes (ADR-020 #11): the bench key's rate limit is raised (`UNDO_RATELIMIT_PER_KEY_RPS`,
default 50 per key) so the benchmark measures the engine, not the limiter. support-default caps a
run at 2,000 actions (`budgets.actions`), so each stage spreads its requests over enough runs
(1, 4 and 8 runs). The first B1 attempt used one run per stage and no `wait_ms`: 16,307 of
21,810 requests were queued or denied by the action budget, so its numbers are not used.

## B2: queue throughput

```
ONLY=b2 make bench     # bench/k6/queue.js + bulk approvals through the console API
```

For each worker count: a fresh stack; k6 creates 5,000 `shop_send_email` actions with template
`correction` (policy: require approval) over three runs; the workers are stopped; all 5,000 are
approved through `POST /api/approvals/bulk` (hash-bound, 500 per call); then W workers start.
Drain time is first attempt start to last completion, from the actions' own timestamps.

| Workers | Succeeded | Drain | Throughput |
|---|---|---|---|
| 1 | 5,000 | 8.02 s | 623 actions/s |
| 2 | 5,000 | 4.51 s | 1,108 actions/s |
| 4 | 5,000 | 5.88 s | 850 actions/s |

Four workers are slower than two. Everything shares one laptop: 4 worker JVMs, the API,
Postgres and a single Shopfront on the 12 CPUs Docker has. Each worker claims 20 actions per round with `FOR UPDATE SKIP LOCKED` and
runs them on virtual threads, so with 4 workers the bottleneck moves to Postgres and Shopfront.
This was not profiled further.

An earlier run approved while the workers were running; its drain (1 worker 7.9 s, 2 workers
6.6 s, 4 workers 7.3 s) was bounded by the approval calls, so it is not used.

## B3: recovery time

```
make chaos             # chaos/run.sh, then chaos/check.sh prints this table
```

From the final chaos run (`docs/CHAOS_RESULTS.md`, attempt 5): 30 SIGKILLs of a random worker
JVM, every 20 s, under the Chaos fault preset and Toxiproxy toxics. An action is affected when the
killed node left its attempt open. Recovery start is kill to the first new attempt; end to end is
kill to SUCCEEDED, FAILED, NEEDS_REVIEW or CANCELLED.

| Class | Affected actions | Max recovery start | Max end to end | p95 end to end |
|---|---|---|---|---|
| NATIVE_KEY | 4 | 30.4 s | 30.5 s | 30.3 s |
| NATURAL | 6 | 29.3 s | 29.4 s | 29.3 s |
| NONE (to NEEDS_REVIEW) | 3 | 29.9 s | 29.9 s | 29.4 s |
| LOOKUP_BY_REFERENCE | 0 in this run | — | — | — |

The 30 s is the action lease: it runs from the attempt start, the sweeper (every 1 s) then moves
the action to UNKNOWN and the resolver (every 0.5 s) picks it up. Only 13 of the 30 kills caught an action in
flight; provider calls take about 1 ms, so most kills hit a worker between attempts. In attempt 2
a store credit (LOOKUP_BY_REFERENCE) was caught and resolved end to end in 87.0 s (limit 105 s).

## B4 and B5: JMH

```
ONLY=b4 make bench     # java -jar bench/jmh/target/undo-bench-jmh.jar  (2 forks, 3 × 2 s warmup, 5 × 3 s measured)
```

| Benchmark | Score | Error (99.9 %) |
|---|---|---|
| `b4PolicyEvaluateRefund`: `PolicyEvaluator.evaluate` of support-default for `shop_issue_refund` with order, ticket and customer facts | 852.9 ns/op | ± 4.2 |
| `b5aJcs1kb`: RFC 8785 canonical form of ~1 KB of args | 3,337.8 ns/op | ± 30.6 |
| `b5bArgsHash1kb`: `args_hash` = SHA-256 of JCS({tool, args}) | 4,360.1 ns/op | ± 336.9 |

## B6: simulator

```
ONLY=b6 make bench     # java -jar undo-sim/target/undo-sim.jar --seeds 100000 --threads 8
```

100,000 seeds in 6 m 13 s at 8 threads: **268 seeds/s**, 0 violations, crash points 13/13,
23/23 legal edges, regression seeds 14/14. The same build at 12 threads did the 1,000,000-seed
run in `docs/SIMULATION.md`.

## B7: revert preview

```
ONLY=b7 make bench     # mvn verify -Dtest=RevertPreviewBenchIT (Postgres in Testcontainers, real HTTP Shopfront)
```

One run with 500 SUCCEEDED `shop_set_order_note` actions; one `POST /api/runs/{n}/revert-plans`
(freeze, provider read of each order's current note outside the transaction, 500 plan items):
**630 ms** wall time from the client (685 ms on the first try).
