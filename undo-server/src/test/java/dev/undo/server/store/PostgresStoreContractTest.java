package dev.undo.server.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Run;
import dev.undo.core.port.Store;
import dev.undo.core.store.StoreContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

/** Section 22.3 on Postgres 17 (Testcontainers), plus checks only a real database can make. */
class PostgresStoreContractTest extends StoreContractTest {
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17");
  static PGSimpleDataSource ds;
  static JdbcClient db;

  @BeforeAll
  static void start() {
    PG.start();
    ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    db = JdbcClient.create(ds);
  }

  @AfterAll
  static void stop() {
    PG.stop();
  }

  @Override
  protected Store createStore(UUID userId) {
    // A fresh store per test, like the in-memory one (row triggers do not fire on TRUNCATE).
    db.sql(
            """
            truncate events, action_transitions, postings, journal_entries, attempts, approvals, revert_items, revert_plans,
                     recon_findings, recon_runs, webhook_events, llm_turns, actions, tasks, runs, api_keys, users, agents,
                     policy_versions, job_leases, relay_checkpoints cascade
            """)
        .update();
    db.sql(
            "insert into users(id, email, password_hash, roles, created_at) values (?, ?, 'x', '{ADMIN}', now())")
        .params(userId, userId + "@test.local")
        .update();
    return new PostgresStore(db, new DataSourceTransactionManager(ds));
  }

  @Test
  void appendOnlyTablesRejectUpdatesAndDeletes() {
    Run r = run(1000, 10);
    insert(action(r.id, ActionStatus.APPROVED));
    db.sql(
            "insert into action_transitions(action_id, to_status, fence, actor, at) select id, 'APPROVED', 0, 'x', now() from actions where run_id = ?")
        .param(r.id)
        .update();
    assertThatThrownBy(() -> db.sql("update action_transitions set actor = 'y'").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> db.sql("delete from action_transitions").update())
        .hasMessageContaining("append-only");
    db.sql("insert into events(run_id, type, data, at) values (?, 'x', '{}', ?)")
        .params(r.id, Timestamp.from(Instant.now()))
        .update();
    assertThatThrownBy(() -> db.sql("update events set type = 'y'").update())
        .hasMessageContaining("append-only");
  }

  @Test
  void checkConstraintNamesMatchTheInMemoryStore() {
    Run r = run(1000, 2);
    assertViolates(
        "runs_check2",
        () ->
            store.tx(
                tx -> {
                  tx.runs().reserveUnchecked(r.id, 0, 5);
                  return null;
                }));
    assertViolates(
        "runs_check",
        () ->
            store.tx(
                tx -> {
                  tx.runs().release(r.id, 1, 0);
                  return null;
                }));
    assertThat(store.tx(tx -> tx.runs().get(r.id)).orElseThrow().actionCount).isZero();
  }
}
