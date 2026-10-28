package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.Attempt;
import dev.undo.core.model.AttemptKind;
import dev.undo.core.port.repo.AttemptRepo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgAttemptRepo implements AttemptRepo {
  private final JdbcClient db;

  PgAttemptRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public void insert(Attempt a) {
    db.sql(
            """
            insert into attempts(id, action_id, kind, attempt_no, node, fence, started_at, finished_at, outcome, http_status,
                                 latency_ms, detail)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """)
        .params(
            a.id,
            a.actionId,
            name(a.kind),
            a.attemptNo,
            a.node,
            a.fence,
            ts(a.startedAt),
            ts(a.finishedAt),
            name(a.outcome),
            a.httpStatus,
            a.latencyMs,
            json(a.detail))
        .update();
  }

  @Override
  public void update(Attempt a) {
    db.sql(
            "update attempts set finished_at = ?, outcome = ?, http_status = ?, latency_ms = ?, detail = ?::jsonb where id = ?")
        .params(ts(a.finishedAt), name(a.outcome), a.httpStatus, a.latencyMs, json(a.detail), a.id)
        .update();
  }

  @Override
  public Optional<Attempt> get(UUID actionId, AttemptKind kind, int attemptNo) {
    return db.sql("select * from attempts where action_id = ? and kind = ? and attempt_no = ?")
        .params(actionId, kind.name(), attemptNo)
        .query(ATTEMPT)
        .optional();
  }

  @Override
  public Optional<Attempt> lastExecute(UUID actionId) {
    return db.sql(
            "select * from attempts where action_id = ? and kind = 'EXECUTE' order by attempt_no desc limit 1")
        .param(actionId)
        .query(ATTEMPT)
        .optional();
  }

  @Override
  public List<Attempt> byAction(UUID actionId) {
    return db.sql(
            "select * from attempts where action_id = ? order by started_at, kind, attempt_no")
        .param(actionId)
        .query(ATTEMPT)
        .list();
  }
}
