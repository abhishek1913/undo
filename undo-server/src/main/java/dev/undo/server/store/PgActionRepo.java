package dev.undo.server.store;

import static dev.undo.server.store.Rows.ACTION;
import static dev.undo.server.store.Rows.TRANSITION;
import static dev.undo.server.store.Rows.json;
import static dev.undo.server.store.Rows.name;
import static dev.undo.server.store.Rows.ts;

import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ActionTransition;
import dev.undo.core.port.repo.ActionRepo;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgActionRepo implements ActionRepo {
  private final JdbcClient db;

  PgActionRepo(JdbcClient db) {
    this.db = db;
  }

  private static final String COLUMNS =
      """
      id, run_id, task_id, kind, compensates_action_id, revert_plan_id, connector, tool, effect, idempotency_class, args,
      args_hash, agent_call_id, client_idempotency_key, dedupe_key, dedupe_active, duplicate_of_action_id, status,
      status_reason, policy_version_id, decision, matched_rules, facts, before_snapshot, money_out_minor, budget_state,
      provider_idempotency_key, provider_ref, provider_status, result, error, compensated_by_action_id, attempt_count,
      lookup_count, first_attempt_at, next_attempt_at, unknown_since, resolve_after, lease_owner, lease_expires_at,
      lease_fence, created_at, updated_at, completed_at
      """;

  private static Object[] values(Action a) {
    return new Object[] {
      a.id,
      a.runId,
      a.taskId,
      name(a.kind),
      a.compensatesActionId,
      a.revertPlanId,
      a.connector,
      a.tool,
      name(a.effect),
      name(a.idempotencyClass),
      json(a.args),
      a.argsHash,
      a.agentCallId,
      a.clientIdempotencyKey,
      a.dedupeKey,
      a.dedupeActive,
      a.duplicateOfActionId,
      name(a.status),
      a.statusReason,
      a.policyVersionId,
      name(a.decision),
      json(a.matchedRules),
      json(a.facts),
      json(a.beforeSnapshot),
      a.moneyOutMinor,
      name(a.budgetState),
      a.providerIdempotencyKey,
      a.providerRef,
      a.providerStatus,
      json(a.result),
      json(a.error),
      a.compensatedByActionId,
      a.attemptCount,
      a.lookupCount,
      ts(a.firstAttemptAt),
      ts(a.nextAttemptAt),
      ts(a.unknownSince),
      ts(a.resolveAfter),
      a.leaseOwner,
      ts(a.leaseExpiresAt),
      a.leaseFence,
      ts(a.createdAt),
      ts(a.updatedAt),
      ts(a.completedAt)
    };
  }

  @Override
  public Action insert(Action a) {
    return db.sql(
            "insert into actions("
                + COLUMNS
                + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,"
                + " ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning *")
        .params(values(a))
        .query(ACTION)
        .single();
  }

  @Override
  public Optional<Action> get(UUID id) {
    return db.sql("select * from actions where id = ?").param(id).query(ACTION).optional();
  }

  @Override
  public Optional<Action> byNumber(long number) {
    return db.sql("select * from actions where number = ?").param(number).query(ACTION).optional();
  }

  @Override
  public Action lock(UUID id) {
    return db.sql("select * from actions where id = ? for update")
        .param(id)
        .query(ACTION)
        .optional()
        .orElseThrow(() -> new IllegalStateException("no action " + id));
  }

  @Override
  public Optional<Action> byCallKey(UUID runId, String agentCallId, String clientKey) {
    return db.sql(
            """
            select * from actions where run_id = ?
              and ((cast(? as text) is not null and agent_call_id = ?) or (cast(? as text) is not null and client_idempotency_key = ?))
            order by number limit 1
            """)
        .params(runId, agentCallId, agentCallId, clientKey, clientKey)
        .query(ACTION)
        .optional();
  }

  @Override
  public Optional<Action> activeByDedupeKey(String dedupeKey) {
    return db.sql("select * from actions where dedupe_key = ? and dedupe_active")
        .param(dedupeKey)
        .query(ACTION)
        .optional();
  }

  private static final String SET =
      """
      update actions set task_id = ?, kind = ?, compensates_action_id = ?, revert_plan_id = ?, args = ?::jsonb, args_hash = ?,
        agent_call_id = ?, client_idempotency_key = ?, dedupe_key = ?, dedupe_active = ?, duplicate_of_action_id = ?,
        status = ?, status_reason = ?, decision = ?, matched_rules = ?::jsonb, facts = ?::jsonb, before_snapshot = ?::jsonb,
        money_out_minor = ?, budget_state = ?, provider_idempotency_key = ?, provider_ref = ?, provider_status = ?,
        result = ?::jsonb, error = ?::jsonb, compensated_by_action_id = ?, attempt_count = ?, lookup_count = ?,
        first_attempt_at = ?, next_attempt_at = ?, unknown_since = ?, resolve_after = ?, lease_owner = ?,
        lease_expires_at = ?, lease_fence = ?, updated_at = ?, completed_at = ?
      """;

  private static Object[] setValues(Action a, Object... where) {
    Object[] base = {
      a.taskId,
      name(a.kind),
      a.compensatesActionId,
      a.revertPlanId,
      json(a.args),
      a.argsHash,
      a.agentCallId,
      a.clientIdempotencyKey,
      a.dedupeKey,
      a.dedupeActive,
      a.duplicateOfActionId,
      name(a.status),
      a.statusReason,
      name(a.decision),
      json(a.matchedRules),
      json(a.facts),
      json(a.beforeSnapshot),
      a.moneyOutMinor,
      name(a.budgetState),
      a.providerIdempotencyKey,
      a.providerRef,
      a.providerStatus,
      json(a.result),
      json(a.error),
      a.compensatedByActionId,
      a.attemptCount,
      a.lookupCount,
      ts(a.firstAttemptAt),
      ts(a.nextAttemptAt),
      ts(a.unknownSince),
      ts(a.resolveAfter),
      a.leaseOwner,
      ts(a.leaseExpiresAt),
      a.leaseFence,
      ts(a.updatedAt),
      ts(a.completedAt)
    };
    Object[] out = new Object[base.length + where.length];
    System.arraycopy(base, 0, out, 0, base.length);
    System.arraycopy(where, 0, out, base.length, where.length);
    return out;
  }

  /** The fenced write: changes nothing unless status and fence are still what the caller read. */
  @Override
  public boolean update(Action a, ActionStatus expectedStatus, long expectedFence) {
    return db.sql(SET + " where id = ? and status = ? and lease_fence = ?")
            .params(setValues(a, a.id, expectedStatus.name(), expectedFence))
            .update()
        == 1;
  }

  @Override
  public void updateUnconditional(Action a, long writerFence) {
    db.sql(SET + " where id = ?").params(setValues(a, a.id)).update();
  }

  @Override
  public List<Action> lockRunnable(Instant now, int limit) {
    return db.sql(
            """
            select a.* from actions a join runs r on r.id = a.run_id
            where a.status in ('APPROVED','RETRY_WAIT')
              and (a.next_attempt_at is null or a.next_attempt_at <= ?)
              and (a.kind <> 'PRIMARY' or r.status in ('ACTIVE','COMPLETED'))
            order by a.number limit ? for update of a skip locked
            """)
        .params(ts(now), limit)
        .query(ACTION)
        .list();
  }

  @Override
  public List<Action> lockExpiredExecuting(Instant now, int limit) {
    return db.sql(
            "select * from actions where status = 'EXECUTING' and lease_expires_at < ? order by number limit ? for update skip locked")
        .params(ts(now), limit)
        .query(ACTION)
        .list();
  }

  @Override
  public List<Action> lockResolvable(Instant now, int limit) {
    return db.sql(
            """
            select * from actions where status = 'UNKNOWN' and (resolve_after is null or resolve_after <= ?)
              and (lease_expires_at is null or lease_expires_at < ?)
            order by number limit ? for update skip locked
            """)
        .params(ts(now), ts(now), limit)
        .query(ACTION)
        .list();
  }

  @Override
  public List<Action> lockQueuedPrimary(UUID runId) {
    return db.sql(
            """
            select * from actions where run_id = ? and kind = 'PRIMARY'
              and status in ('AWAITING_APPROVAL','APPROVED','RETRY_WAIT')
            order by number for update
            """)
        .param(runId)
        .query(ACTION)
        .list();
  }

  @Override
  public List<Action> byRun(UUID runId) {
    return db.sql("select * from actions where run_id = ? order by number")
        .param(runId)
        .query(ACTION)
        .list();
  }

  @Override
  public Map<ActionStatus, Long> countByStatus(UUID runId) {
    Map<ActionStatus, Long> m = new EnumMap<>(ActionStatus.class);
    db.sql("select status, count(*) n from actions where run_id = ? group by status")
        .param(runId)
        .query((rs, i) -> m.put(ActionStatus.valueOf(rs.getString("status")), rs.getLong("n")))
        .list();
    return m;
  }

  @Override
  public Optional<Action> liveCompensationOf(UUID targetActionId) {
    return db.sql(
            """
            select * from actions where compensates_action_id = ?
              and status not in ('FAILED','DENIED','CANCELLED','REJECTED','EXPIRED')
            """)
        .param(targetActionId)
        .query(ACTION)
        .optional();
  }

  @Override
  public Optional<Action> byProviderRef(String connector, String providerRef) {
    return db.sql(
            """
            select * from actions where connector = ? and provider_ref = ?
            order by (kind = 'PRIMARY') desc, number limit 1
            """)
        .params(connector, providerRef)
        .query(ACTION)
        .optional();
  }

  @Override
  public List<Action> forRecon(String connector, Instant from, Instant to) {
    return db.sql(
            """
            select * from actions where connector = ?
              and (status in ('UNKNOWN','NEEDS_REVIEW') or (updated_at >= ? and updated_at < ?))
            order by number
            """)
        .params(connector, ts(from), ts(to))
        .query(ACTION)
        .list();
  }

  @Override
  public List<Action> byStatus(ActionStatus status, int limit) {
    return db.sql("select * from actions where status = ? order by number desc limit ?")
        .params(status.name(), limit)
        .query(ACTION)
        .list();
  }

  @Override
  public Map<ActionStatus, Long> globalCounts() {
    Map<ActionStatus, Long> m = new EnumMap<>(ActionStatus.class);
    db.sql("select status, count(*) n from actions group by status")
        .query((rs, i) -> m.put(ActionStatus.valueOf(rs.getString("status")), rs.getLong("n")))
        .list();
    return m;
  }

  @Override
  public long moneyOutSince(Instant since) {
    return db.sql(
            "select coalesce(sum(money_out_minor), 0) from actions where kind = 'PRIMARY' and status = 'SUCCEEDED' and completed_at >= ?")
        .param(ts(since))
        .query(Long.class)
        .single();
  }

  @Override
  public long countInFlight() {
    return db.sql(
            "select count(*) from actions where status in ('APPROVED','EXECUTING','RETRY_WAIT','UNKNOWN')")
        .query(Long.class)
        .single();
  }

  @Override
  public void addTransition(ActionTransition t) {
    db.sql(
            "insert into action_transitions(action_id, from_status, to_status, fence, actor, reason, at) values (?, ?, ?, ?, ?, ?, ?)")
        .params(
            t.actionId, name(t.fromStatus), name(t.toStatus), t.fence, t.actor, t.reason, ts(t.at))
        .update();
  }

  @Override
  public List<ActionTransition> transitions(UUID actionId) {
    return db.sql("select * from action_transitions where action_id = ? order by id")
        .param(actionId)
        .query(TRANSITION)
        .list();
  }
}
