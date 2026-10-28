# ADR-007: Outbox gap handling

**Context.** Events get `seq` from an identity column. Two transactions can commit out of order:
seq 101 becomes visible before seq 100. A reader that jumps its cursor to 101 misses 100 forever.

**Decision.** The SSE streamer (and the optional Kafka relay) reads `seq > cursor ORDER BY seq` and
delivers only up to the first gap. If the gap is still there after 2 s, the missing seq is treated as
rolled back (identity values are never reused, so a rolled-back seq never appears) and reading
continues. Gaps are judged on the global sequence; a per-run stream filters after that decision.
`GapTracker` implements it and has its own unit tests.

**Alternatives.** Commit-ordered sequences (a single counter row: serializes every transaction);
logical replication (more infrastructure than a demo needs).

**Consequences.** Live views may lag by up to 2 s when a transaction is slow; no event is lost or
reordered for the reader.
