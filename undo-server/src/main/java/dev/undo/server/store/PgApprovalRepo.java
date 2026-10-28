package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.port.repo.ApprovalRepo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgApprovalRepo implements ApprovalRepo {
  private final JdbcClient db;

  PgApprovalRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public void insert(Approval a) {
    db.sql(
            """
            insert into approvals(id, action_id, run_id, args_hash, approved_args_hash, policy_version_id, reasons, advisory_note,
                                  status, requested_at, expires_at, decided_by, decided_at, decision_note)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            a.id,
            a.actionId,
            a.runId,
            a.argsHash,
            a.approvedArgsHash,
            a.policyVersionId,
            json(a.reasons),
            json(a.advisoryNote),
            name(a.status),
            ts(a.requestedAt),
            ts(a.expiresAt),
            a.decidedBy,
            ts(a.decidedAt),
            a.decisionNote)
        .update();
  }

  @Override
  public Optional<Approval> get(UUID id) {
    return db.sql("select * from approvals where id = ?").param(id).query(APPROVAL).optional();
  }

  @Override
  public Optional<Approval> byAction(UUID actionId) {
    return db.sql("select * from approvals where action_id = ?")
        .param(actionId)
        .query(APPROVAL)
        .optional();
  }

  @Override
  public Approval lock(UUID id) {
    return db.sql("select * from approvals where id = ? for update")
        .param(id)
        .query(APPROVAL)
        .optional()
        .orElseThrow(() -> new IllegalStateException("no approval " + id));
  }

  /** Decision columns only; the advisory note has its own writer. */
  @Override
  public void update(Approval a) {
    db.sql(
            "update approvals set approved_args_hash = ?, status = ?, decided_by = ?, decided_at = ?, decision_note = ? where id = ?")
        .params(
            a.approvedArgsHash, name(a.status), a.decidedBy, ts(a.decidedAt), a.decisionNote, a.id)
        .update();
  }

  @Override
  public List<Approval> lockExpired(Instant now, int limit) {
    return db.sql(
            "select * from approvals where status = 'PENDING' and expires_at <= ? order by expires_at limit ? for update skip locked")
        .params(ts(now), limit)
        .query(APPROVAL)
        .list();
  }

  @Override
  public List<Approval> list(ApprovalStatus status, UUID runId, int limit) {
    return db.sql(
            """
            select * from approvals where (cast(? as text) is null or status = ?) and (cast(? as uuid) is null or run_id = ?)
            order by requested_at desc, id desc limit ?
            """)
        .params(name(status), name(status), runId, runId, limit)
        .query(APPROVAL)
        .list();
  }

  @Override
  public List<Approval> missingAdvisory(int limit) {
    return db.sql(
            "select * from approvals where status = 'PENDING' and advisory_note is null order by requested_at limit ?")
        .param(limit)
        .query(APPROVAL)
        .list();
  }

  @Override
  public void setAdvisory(Approval a) {
    db.sql("update approvals set advisory_note = ?::jsonb where id = ?")
        .params(json(a.advisoryNote), a.id)
        .update();
  }
}
