package dev.undo.server.store;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Demo only (Section 19.3, /api/demo/seed): empties every table except users, agents, api_keys and
 * policy_versions. TRUNCATE does not fire the row-level append-only triggers. Identity sequences
 * keep counting, so event seq numbers seen by open SSE clients stay increasing.
 */
public final class DemoReset {
  private static final String TABLES =
      "runs, tasks, llm_turns, actions, action_transitions, attempts, approvals, journal_entries,"
          + " postings, revert_plans, revert_items, webhook_events, recon_runs, recon_findings,"
          + " job_leases, events, relay_checkpoints";

  private final JdbcClient db;

  DemoReset(JdbcClient db) {
    this.db = db;
  }

  public void resetUndoData() {
    db.sql("truncate table " + TABLES + " cascade").update();
  }
}
