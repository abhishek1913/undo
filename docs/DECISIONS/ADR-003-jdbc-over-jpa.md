# ADR-003: JDBC over JPA

**Context.** Correctness depends on conditional updates (`WHERE status = ? AND lease_fence = ?`),
row locks (`FOR UPDATE`, `SKIP LOCKED`) and one-statement budget reservations. An ORM hides these
behind dirty checking and flush order.

**Decision.** No JPA, no Hibernate, no Lombok. Every statement is explicit SQL in a repository class
next to the method that uses it (`dev.undo.server.store`), through Spring `JdbcClient`. Only the store
package touches `JdbcClient` (ArchUnit rule 3). Integrity errors are translated to
`ConstraintViolationException` with the DDL constraint name, the same names `InMemoryStore` uses.

**Alternatives.** JPA with optimistic locking (version columns): cannot express "claim N rows that
nobody else is claiming" or "reserve only if it fits" as one statement.

**Consequences.** More code for row mapping; in exchange every lock and condition is visible in
review and mirrored one to one by the simulator's store.
