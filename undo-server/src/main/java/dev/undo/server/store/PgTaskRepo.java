package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.LlmTurn;
import dev.undo.core.model.Task;
import dev.undo.core.port.repo.TaskRepo;
import dev.undo.core.port.repo.TurnRepo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgTaskRepo implements TaskRepo, TurnRepo {
  private final JdbcClient db;

  PgTaskRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public void insert(Task t) {
    db.sql(
            """
            insert into tasks(id, run_id, seq, input, status, turns, failure_reason, created_at, started_at, completed_at)
            values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            t.id,
            t.runId,
            t.seq,
            json(t.input),
            name(t.status),
            t.turns,
            t.failureReason,
            ts(t.createdAt),
            ts(t.startedAt),
            ts(t.completedAt))
        .update();
  }

  @Override
  public Optional<Task> get(UUID id) {
    return db.sql("select * from tasks where id = ?").param(id).query(TASK).optional();
  }

  @Override
  public List<Task> byRun(UUID runId) {
    return db.sql("select * from tasks where run_id = ? order by seq")
        .param(runId)
        .query(TASK)
        .list();
  }

  @Override
  public void update(Task t) {
    db.sql(
            "update tasks set status = ?, turns = ?, failure_reason = ?, started_at = ?, completed_at = ? where id = ?")
        .params(name(t.status), t.turns, t.failureReason, ts(t.startedAt), ts(t.completedAt), t.id)
        .update();
  }

  @Override
  public void insert(LlmTurn t) {
    db.sql(
            """
            insert into llm_turns(id, run_id, task_id, turn_no, model, response, stop_reason, tool_results, input_tokens,
                                  output_tokens, cache_read_tokens, cache_write_tokens, latency_ms, run_fence, created_at)
            values (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            t.id,
            t.runId,
            t.taskId,
            t.turnNo,
            t.model,
            json(t.response),
            t.stopReason,
            json(t.toolResults),
            t.inputTokens,
            t.outputTokens,
            t.cacheReadTokens,
            t.cacheWriteTokens,
            t.latencyMs,
            t.runFence,
            ts(t.createdAt))
        .update();
  }

  @Override
  public List<LlmTurn> byTask(UUID taskId) {
    return db.sql("select * from llm_turns where task_id = ? order by turn_no")
        .param(taskId)
        .query(TURN)
        .list();
  }

  @Override
  public void setToolResults(LlmTurn t) {
    db.sql("update llm_turns set tool_results = ?::jsonb where id = ?")
        .params(json(t.toolResults), t.id)
        .update();
  }
}
