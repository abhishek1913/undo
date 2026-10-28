package dev.undo.server.store;

import static dev.undo.server.store.Rows.RUN;
import static dev.undo.server.store.Rows.json;
import static dev.undo.server.store.Rows.name;
import static dev.undo.server.store.Rows.ts;

import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.model.RunStatus;
import dev.undo.core.port.repo.RunRepo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgRunRepo implements RunRepo {
  private final JdbcClient db;

  PgRunRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public Run insert(Run r) {
    return db.sql(
            """
            insert into runs(id, agent_id, kind, status, title, scenario, model_mode, labels, policy_version_id, currency,
                             money_cap_minor, money_reserved_minor, money_spent_minor, action_cap, action_count,
                             llm_token_cap, llm_tokens_used, mcp_session_id, lease_owner, lease_expires_at, lease_fence,
                             audit_head_hash, failure_reason, last_activity_at, created_at, completed_at, frozen_at)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning *
            """)
        .params(
            r.id,
            r.agentId,
            name(r.kind),
            name(r.status),
            r.title,
            r.scenario,
            r.modelMode,
            json(r.labels),
            r.policyVersionId,
            r.currency,
            r.moneyCapMinor,
            r.moneyReservedMinor,
            r.moneySpentMinor,
            r.actionCap,
            r.actionCount,
            r.llmTokenCap,
            r.llmTokensUsed,
            r.mcpSessionId,
            r.leaseOwner,
            ts(r.leaseExpiresAt),
            r.leaseFence,
            r.auditHeadHash,
            r.failureReason,
            ts(r.lastActivityAt),
            ts(r.createdAt),
            ts(r.completedAt),
            ts(r.frozenAt))
        .query(RUN)
        .single();
  }

  @Override
  public Optional<Run> get(UUID id) {
    return db.sql("select * from runs where id = ?").param(id).query(RUN).optional();
  }

  @Override
  public Optional<Run> byNumber(long number) {
    return db.sql("select * from runs where number = ?").param(number).query(RUN).optional();
  }

  @Override
  public Optional<Run> byMcpSession(String sessionId) {
    return db.sql("select * from runs where mcp_session_id = ?")
        .param(sessionId)
        .query(RUN)
        .optional();
  }

  @Override
  public Run lock(UUID id) {
    return db.sql("select * from runs where id = ? for update")
        .param(id)
        .query(RUN)
        .optional()
        .orElseThrow(() -> new IllegalStateException("no run " + id));
  }

  /**
   * Every mutable column except the budget columns (they change only through the methods below).
   */
  @Override
  public void update(Run r) {
    db.sql(
            """
            update runs set status = ?, title = ?, scenario = ?, model_mode = ?, labels = ?::jsonb, mcp_session_id = ?,
                            lease_owner = ?, lease_expires_at = ?, lease_fence = ?, audit_head_hash = ?, failure_reason = ?,
                            last_activity_at = ?, completed_at = ?, frozen_at = ?
            where id = ?
            """)
        .params(
            name(r.status),
            r.title,
            r.scenario,
            r.modelMode,
            json(r.labels),
            r.mcpSessionId,
            r.leaseOwner,
            ts(r.leaseExpiresAt),
            r.leaseFence,
            r.auditHeadHash,
            r.failureReason,
            ts(r.lastActivityAt),
            ts(r.completedAt),
            ts(r.frozenAt),
            r.id)
        .update();
  }

  /** One conditional update that cannot overshoot under concurrency (FR-BUD-2). */
  @Override
  public boolean reserve(UUID id, long money, int actions) {
    return db.sql(
                """
                update runs set money_reserved_minor = money_reserved_minor + :money, action_count = action_count + :n
                where id = :id
                  and money_reserved_minor + money_spent_minor + :money <= money_cap_minor
                  and action_count + :n <= action_cap
                """)
            .param("money", money)
            .param("n", actions)
            .param("id", id)
            .update()
        == 1;
  }

  @Override
  public void release(UUID id, long money, int actions) {
    db.sql(
            "update runs set money_reserved_minor = money_reserved_minor - ?, action_count = action_count - ? where id = ?")
        .params(money, actions, id)
        .update();
  }

  @Override
  public void settle(UUID id, long money) {
    db.sql(
            "update runs set money_reserved_minor = money_reserved_minor - ?, money_spent_minor = money_spent_minor + ? where id = ?")
        .params(money, money, id)
        .update();
  }

  @Override
  public void reserveUnchecked(UUID id, long money, int actions) {
    db.sql(
            "update runs set money_reserved_minor = money_reserved_minor + ?, action_count = action_count + ? where id = ?")
        .params(money, actions, id)
        .update();
  }

  @Override
  public void addTokens(UUID id, long tokens) {
    db.sql("update runs set llm_tokens_used = llm_tokens_used + ? where id = ?")
        .params(tokens, id)
        .update();
  }

  @Override
  public Optional<Run> claimHosted(String owner, Instant now, Instant leaseUntil) {
    return db.sql(
            """
            update runs set lease_owner = ?, lease_expires_at = ?, lease_fence = lease_fence + 1
            where id = (select id from runs
                        where kind = 'HOSTED' and status = 'ACTIVE' and (lease_expires_at is null or lease_expires_at < ?)
                        order by number limit 1 for update skip locked)
            returning *
            """)
        .params(owner, ts(leaseUntil), ts(now))
        .query(RUN)
        .optional();
  }

  @Override
  public boolean heartbeat(UUID id, long fence, Instant leaseUntil) {
    return db.sql("update runs set lease_expires_at = ? where id = ? and lease_fence = ?")
            .params(ts(leaseUntil), id, fence)
            .update()
        == 1;
  }

  @Override
  public List<Run> lockIdleExternal(Instant idleBefore, int limit) {
    return db.sql(
            """
            select * from runs where kind = 'EXTERNAL' and status = 'ACTIVE' and last_activity_at < ?
            order by number limit ? for update skip locked
            """)
        .params(ts(idleBefore), limit)
        .query(RUN)
        .list();
  }

  @Override
  public List<Run> list(RunStatus status, RunKind kind, Long beforeNumber, int limit) {
    return db.sql(
            """
            select * from runs where (cast(? as text) is null or status = ?) and (cast(? as text) is null or kind = ?)
              and (cast(? as bigint) is null or number < ?)
            order by number desc limit ?
            """)
        .params(
            name(status), name(status), name(kind), name(kind), beforeNumber, beforeNumber, limit)
        .query(RUN)
        .list();
  }
}
