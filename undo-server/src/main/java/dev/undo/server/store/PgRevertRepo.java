package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.RevertItem;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.port.repo.RevertRepo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgRevertRepo implements RevertRepo {
  private final JdbcClient db;

  PgRevertRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public RevertPlan insertPlan(RevertPlan p) {
    return db.sql(
            """
            insert into revert_plans(id, run_id, scope, requested_by, status, summary, lease_owner, lease_expires_at, lease_fence,
                                     created_at, started_at, completed_at)
            values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?) returning *
            """)
        .params(
            p.id,
            p.runId,
            name(p.scope),
            p.requestedBy,
            name(p.status),
            json(p.summary),
            p.leaseOwner,
            ts(p.leaseExpiresAt),
            p.leaseFence,
            ts(p.createdAt),
            ts(p.startedAt),
            ts(p.completedAt))
        .query(PLAN)
        .single();
  }

  @Override
  public Optional<RevertPlan> plan(UUID id) {
    return db.sql("select * from revert_plans where id = ?").param(id).query(PLAN).optional();
  }

  @Override
  public Optional<RevertPlan> planByNumber(long number) {
    return db.sql("select * from revert_plans where number = ?")
        .param(number)
        .query(PLAN)
        .optional();
  }

  @Override
  public Optional<RevertPlan> openForRun(UUID runId) {
    return db.sql(
            "select * from revert_plans where run_id = ? and status in ('PREVIEW','EXECUTING')")
        .param(runId)
        .query(PLAN)
        .optional();
  }

  @Override
  public List<RevertPlan> byRun(UUID runId) {
    return db.sql("select * from revert_plans where run_id = ? order by number")
        .param(runId)
        .query(PLAN)
        .list();
  }

  @Override
  public RevertPlan lockPlan(UUID id) {
    return db.sql("select * from revert_plans where id = ? for update")
        .param(id)
        .query(PLAN)
        .optional()
        .orElseThrow(() -> new IllegalStateException("no plan " + id));
  }

  @Override
  public void updatePlan(RevertPlan p) {
    db.sql(
            """
            update revert_plans set status = ?, summary = ?::jsonb, lease_owner = ?, lease_expires_at = ?, lease_fence = ?,
                                    started_at = ?, completed_at = ?
            where id = ?
            """)
        .params(
            name(p.status),
            json(p.summary),
            p.leaseOwner,
            ts(p.leaseExpiresAt),
            p.leaseFence,
            ts(p.startedAt),
            ts(p.completedAt),
            p.id)
        .update();
  }

  @Override
  public List<RevertPlan> lockExecutable(Instant now, int limit) {
    return db.sql(
            """
            select * from revert_plans where status = 'EXECUTING' and (lease_expires_at is null or lease_expires_at < ?)
            order by number limit ? for update skip locked
            """)
        .params(ts(now), limit)
        .query(PLAN)
        .list();
  }

  @Override
  public List<RevertPlan> lockExpiredPreviews(Instant createdBefore, int limit) {
    return db.sql(
            "select * from revert_plans where status = 'PREVIEW' and created_at < ? order by number limit ? for update skip locked")
        .params(ts(createdBefore), limit)
        .query(PLAN)
        .list();
  }

  @Override
  public void insertItem(RevertItem i) {
    db.sql(
            """
            insert into revert_items(id, plan_id, order_no, target_action_id, kind, status, compensation_action_id,
                                     remediation_action_id, money_recovered_minor, money_not_recovered_minor, note)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            i.id,
            i.planId,
            i.orderNo,
            i.targetActionId,
            name(i.kind),
            name(i.status),
            i.compensationActionId,
            i.remediationActionId,
            i.moneyRecoveredMinor,
            i.moneyNotRecoveredMinor,
            i.note)
        .update();
  }

  @Override
  public void updateItem(RevertItem i) {
    db.sql(
            """
            update revert_items set kind = ?, status = ?, compensation_action_id = ?, remediation_action_id = ?,
                                    money_recovered_minor = ?, money_not_recovered_minor = ?, note = ?
            where id = ?
            """)
        .params(
            name(i.kind),
            name(i.status),
            i.compensationActionId,
            i.remediationActionId,
            i.moneyRecoveredMinor,
            i.moneyNotRecoveredMinor,
            i.note,
            i.id)
        .update();
  }

  @Override
  public List<RevertItem> items(UUID planId) {
    return db.sql("select * from revert_items where plan_id = ? order by order_no")
        .param(planId)
        .query(ITEM)
        .list();
  }
}
