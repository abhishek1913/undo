# ADR-002: Postgres SKIP LOCKED as the queue; Kafka only as an optional event relay

**Context.** Workers must find APPROVED and due RETRY_WAIT actions, expired leases and UNKNOWN
actions. The status change that makes an action runnable and the action's state must never
disagree, even across crashes.

**Decision.** The `actions` table is the queue. Workers claim with
`SELECT … FOR UPDATE OF a SKIP LOCKED` and move the row to EXECUTING (lease, fence + 1, attempt row)
in the same transaction. Partial indexes (`actions_runnable`, `actions_executing`,
`actions_unknown`) keep the polls cheap. Kafka (COULD) is only a relay of the `events` outbox for
outside consumers; nothing in Undo depends on it.

**Alternatives.** A message broker as the work queue: the state machine and the queue would commit
separately, so a crash between them loses or duplicates work — exactly what Undo exists to prevent.

**Consequences.** One Postgres primary is the scaling limit (a stated non-goal). The same claim
rules run in the simulator through `InMemoryStore`, and `StoreContractTest` checks that a claim never
returns the same row twice.
