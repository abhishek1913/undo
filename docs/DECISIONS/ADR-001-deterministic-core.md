# ADR-001: Deterministic core with ports

**Context.** The simulator must run the real engine code against a simulated world, and any failure
must be reproducible from its seed. That is impossible if engine code reads the wall clock, makes
random numbers, starts threads or does I/O itself.

**Decision.** `undo-core` and `shopfront-core` contain only pure Java. Everything that touches the
outside world is a port in `dev.undo.core.port`: `Clock`, `Ids`, `CrashPoints`, `Store`/`TxContext`,
`ConnectorRegistry`, `ModelClient`, `Notifier`, `BugFlags`, `Entropy`, `Waiter`, `Locks`,
`GroundTruth`. The server binds real implementations (system clock, UUIDv7, Postgres, HTTP); the
simulator binds seeded, virtual ones. ArchUnit tests (`CoreArchitectureTest`,
`ShopfrontCoreArchitectureTest`) fail the build on Spring, JDBC, HTTP, the Claude SDK, wall-clock
reads, randomness, threads, executors and `Thread.sleep` in the core modules.

Two small additions to the PRD's port list:

- `TxContext.agents()` — the policy root `agent.name` needs the agent of a run.
- `ConstraintViolationException(constraint)` — both stores report a stopped write with the DDL
  constraint name, so the engine handles exactly the expected ones (`actions_agent_call`,
  `actions_client_key`, `actions_dedupe`) and the simulator can count every other one (INV-13).

Repositories mirror SQL statements one to one (`lock` = `FOR UPDATE`, `lockRunnable` = `FOR UPDATE
SKIP LOCKED`, `update(action, expectedStatus, expectedFence)` = conditional `UPDATE ... WHERE status
= ? AND lease_fence = ?`), so the state logic lives in core and runs unchanged in both worlds. The
`StoreContractTest` suite keeps the in-memory store honest against Postgres.

**Alternatives.** Mocking time and I/O inside a Spring context (slow, not deterministic); writing
the engine twice (proves nothing about the real code).

**Consequences.** Core code is written in plain blocking style but may block only at port calls,
which is what makes cooperative scheduling in the simulator possible (Section 21.10).
