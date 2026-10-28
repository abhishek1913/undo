# ADR-006: Leases with fencing tokens

**Context.** A worker can pause (GC, VM suspend, network partition) after it took an action and wake
up after someone else took over. Without protection the "zombie" overwrites the newer outcome.

**Decision.** Every lease grant increments `lease_fence` (actions, hosted runs, revert plans, job
leases). The holder carries the fence; every write it makes is conditional on the fence
(`UPDATE … WHERE lease_fence = :fence`). A stale write changes nothing, is recorded as attempt outcome
`STALE_FENCE`, and increments `undo_stale_fence_rejections_total`. The hosted runner passes its run
fence into `ProposalService`, so a zombie runner cannot create actions. Lease 30 s > provider timeout
10 s, so a healthy worker never loses its lease during a normal call.

**Alternatives.** Heartbeats alone (do not stop a paused process from writing later); distributed
locks without fences (same problem, Kleppmann).

**Consequences.** For `NATIVE_KEY` tools a zombie's late *request* is also harmless (same
idempotency key). For `LOOKUP_BY_REFERENCE` tools safety relies on assumption A1 (ADR-004). The
simulator models zombies with process stalls (ADR-015); bug flag `NO_FENCE_CHECK` is caught by INV-10.
