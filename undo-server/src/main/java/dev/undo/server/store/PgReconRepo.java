package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.FindingStatus;
import dev.undo.core.model.FindingType;
import dev.undo.core.model.ReconFinding;
import dev.undo.core.model.ReconRun;
import dev.undo.core.model.Severity;
import dev.undo.core.port.repo.ReconRepo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgReconRepo implements ReconRepo {
  private final JdbcClient db;

  PgReconRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public ReconRun insertRun(ReconRun r) {
    return db.sql(
            """
            insert into recon_runs(id, connector, window_start, window_end, status, triggered_by, stats, started_at, finished_at)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) returning *
            """)
        .params(
            r.id,
            r.connector,
            ts(r.windowStart),
            ts(r.windowEnd),
            name(r.status),
            r.triggeredBy,
            json(r.stats),
            ts(r.startedAt),
            ts(r.finishedAt))
        .query(RECON_RUN)
        .single();
  }

  @Override
  public void updateRun(ReconRun r) {
    db.sql(
            "update recon_runs set status = ?, stats = ?::jsonb, finished_at = ?, window_end = ? where id = ?")
        .params(name(r.status), json(r.stats), ts(r.finishedAt), ts(r.windowEnd), r.id)
        .update();
  }

  @Override
  public Optional<ReconRun> lastCompleted(String connector) {
    return db.sql(
            "select * from recon_runs where connector = ? and status = 'COMPLETED' order by number desc limit 1")
        .param(connector)
        .query(RECON_RUN)
        .optional();
  }

  @Override
  public List<ReconRun> runs(int limit) {
    return db.sql("select * from recon_runs order by number desc limit ?")
        .param(limit)
        .query(RECON_RUN)
        .list();
  }

  @Override
  public Optional<ReconFinding> openByFingerprint(String fingerprint) {
    return db.sql(
            "select * from recon_findings where fingerprint = ? and status in ('OPEN','ACKNOWLEDGED')")
        .param(fingerprint)
        .query(FINDING)
        .optional();
  }

  @Override
  public void insertFinding(ReconFinding f) {
    db.sql(
            """
            insert into recon_findings(id, recon_run_id, type, severity, action_id, provider_object_ref, expected, actual, status,
                                       fingerprint, resolution_note, resolved_by, resolved_at, created_at)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            f.id,
            f.reconRunId,
            name(f.type),
            name(f.severity),
            f.actionId,
            f.providerObjectRef,
            json(f.expected),
            json(f.actual),
            name(f.status),
            f.fingerprint,
            f.resolutionNote,
            f.resolvedBy,
            ts(f.resolvedAt),
            ts(f.createdAt))
        .update();
  }

  @Override
  public void updateFinding(ReconFinding f) {
    db.sql(
            "update recon_findings set status = ?, resolution_note = ?, resolved_by = ?, resolved_at = ? where id = ?")
        .params(name(f.status), f.resolutionNote, f.resolvedBy, ts(f.resolvedAt), f.id)
        .update();
  }

  @Override
  public Optional<ReconFinding> finding(UUID id) {
    return db.sql("select * from recon_findings where id = ?").param(id).query(FINDING).optional();
  }

  @Override
  public List<ReconFinding> findings(FindingStatus status, FindingType type, int limit) {
    return db.sql(
            """
            select * from recon_findings where (cast(? as text) is null or status = ?) and (cast(? as text) is null or type = ?)
            order by created_at desc, id desc limit ?
            """)
        .params(name(status), name(status), name(type), name(type), limit)
        .query(FINDING)
        .list();
  }

  @Override
  public List<ReconFinding> findingsOfAction(UUID actionId) {
    return db.sql(
            "select * from recon_findings where action_id = ? order by created_at desc, id desc")
        .param(actionId)
        .query(FINDING)
        .list();
  }

  @Override
  public List<OpenCount> openCounts() {
    return db.sql(
            """
            select type, severity, count(*) as n from recon_findings
            where status in ('OPEN','ACKNOWLEDGED') group by type, severity order by type, severity
            """)
        .query(
            (rs, i) ->
                new OpenCount(
                    FindingType.valueOf(rs.getString("type")),
                    Severity.valueOf(rs.getString("severity")),
                    rs.getLong("n")))
        .list();
  }
}
