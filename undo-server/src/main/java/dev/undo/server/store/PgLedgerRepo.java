package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.Posting;
import dev.undo.core.port.repo.LedgerRepo;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgLedgerRepo implements LedgerRepo {
  private final JdbcClient db;

  PgLedgerRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public boolean entryExists(UUID actionId, String kind) {
    return db.sql("select exists(select 1 from journal_entries where action_id = ? and kind = ?)")
        .params(actionId, kind)
        .query(Boolean.class)
        .single();
  }

  /** Balance is checked by the deferred constraint trigger postings_balanced at commit. */
  @Override
  public void insert(JournalEntry e, List<Posting> postings) {
    db.sql(
            "insert into journal_entries(id, run_id, action_id, kind, source, source_ref, created_at) values (?, ?, ?, ?, ?, ?, ?)")
        .params(e.id, e.runId, e.actionId, e.kind, e.source, e.sourceRef, ts(e.createdAt))
        .update();
    for (Posting p : postings) {
      db.sql("insert into postings(entry_id, account, amount_minor, currency) values (?, ?, ?, ?)")
          .params(e.id, p.account, p.amountMinor, p.currency == null ? "INR" : p.currency)
          .update();
    }
  }

  @Override
  public List<JournalEntry> entriesByRun(UUID runId) {
    return db.sql("select * from journal_entries where run_id = ? order by created_at, id")
        .param(runId)
        .query(ENTRY)
        .list();
  }

  @Override
  public List<Posting> postingsByRun(UUID runId) {
    return db.sql(
            "select p.* from postings p join journal_entries e on e.id = p.entry_id where e.run_id = ? order by p.id")
        .param(runId)
        .query(POSTING)
        .list();
  }

  @Override
  public List<Posting> postingsByAction(UUID actionId) {
    return db.sql(
            "select p.* from postings p join journal_entries e on e.id = p.entry_id where e.action_id = ? order by p.id")
        .param(actionId)
        .query(POSTING)
        .list();
  }
}
