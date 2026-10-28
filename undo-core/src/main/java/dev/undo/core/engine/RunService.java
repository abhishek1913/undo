package dev.undo.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.PolicyVersion;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.model.RunStatus;
import dev.undo.core.policy.PolicyService;
import dev.undo.core.port.TxContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Run lifecycle (Section 8.1, FR-RUN-1..7). */
public final class RunService {
  private final Ports p;
  private final PolicyService policies;
  private final EngineConfig cfg;

  public RunService(Ports p, PolicyService policies, EngineConfig cfg) {
    this.p = p;
    this.policies = policies;
    this.cfg = cfg;
  }

  /** Budget overrides may only lower the policy's values (FR-RUN-2). Null = policy value. */
  public record CreateRun(
      UUID agentId,
      RunKind kind,
      String title,
      String scenario,
      String modelMode,
      JsonNode labels,
      String policyName,
      Long moneyCapMinor,
      Integer actionCap,
      Long llmTokenCap,
      String mcpSessionId) {}

  /** TX: one. Pins the active version of the named policy. */
  public Run create(CreateRun req) {
    return p.store().tx(tx -> create(tx, req));
  }

  public Run create(TxContext tx, CreateRun req) {
    Instant now = p.clock().now();
    PolicyVersion pv =
        tx.policies()
            .active(req.policyName())
            .orElseThrow(
                () ->
                    new UndoException(
                        UndoException.Code.POLICY_NOT_FOUND,
                        "No active policy " + req.policyName()));
    JsonNode budgets = policies.compiled(tx, pv.id).path("budgets");
    Run r = new Run();
    r.id = p.ids().next();
    r.agentId = req.agentId();
    r.kind = req.kind();
    r.status = RunStatus.ACTIVE;
    r.title = req.title();
    r.scenario = req.scenario();
    r.modelMode = req.modelMode();
    r.labels = req.labels() == null ? Json.obj() : req.labels();
    r.policyVersionId = pv.id;
    r.moneyCapMinor = lower(budgets.path("money_out_minor").asLong(), req.moneyCapMinor());
    r.actionCap =
        (int)
            lower(
                budgets.path("actions").asLong(),
                req.actionCap() == null ? null : req.actionCap().longValue());
    r.llmTokenCap = lower(budgets.path("llm_tokens").asLong(), req.llmTokenCap());
    r.mcpSessionId = req.mcpSessionId();
    r.lastActivityAt = now;
    r.createdAt = now;
    Run saved = tx.runs().insert(r);
    ObjectNode d = runData(saved);
    d.put("policy", pv.name + " v" + pv.version);
    EventLog.append(tx, now, saved.id, null, "run.created", d);
    return saved;
  }

  private static long lower(long policyValue, Long requested) {
    if (requested == null || requested <= 0) {
      return policyValue;
    }
    return Math.min(policyValue, requested);
  }

  static ObjectNode runData(Run r) {
    ObjectNode d = Json.obj();
    d.put("run_number", r.number);
    d.put("title", r.title);
    d.put("kind", r.kind.name());
    d.put("status", r.status.name());
    if (r.failureReason != null) {
      d.put("reason", r.failureReason);
    }
    return d;
  }

  /** ACTIVE -> COMPLETED. TX: one. Throws RUN_NOT_ACTIVE otherwise. */
  public Run complete(UUID runId, String actor) {
    return p.store().tx(tx -> complete(tx, runId, actor, null));
  }

  /** Fenced when fence != null (hosted runner). */
  public Run complete(TxContext tx, UUID runId, String actor, Long fence) {
    Run r = tx.runs().lock(runId);
    if (fence != null && r.leaseFence != fence) {
      throw new UndoException(UndoException.Code.STALE_FENCE, "runner lease lost");
    }
    if (r.status != RunStatus.ACTIVE) {
      throw new UndoException(UndoException.Code.RUN_NOT_ACTIVE, r.display() + " is " + r.status);
    }
    Instant now = p.clock().now();
    r.status = RunStatus.COMPLETED;
    r.completedAt = now;
    r.leaseOwner = null;
    r.leaseExpiresAt = null;
    tx.runs().update(r);
    ObjectNode d = runData(r);
    d.put("actor", actor);
    EventLog.append(tx, now, r.id, null, "run.completed", d);
    return r;
  }

  /** ACTIVE -> FROZEN plus freeze side effects (FR-RUN-5). TX: one. */
  public Run freeze(UUID runId, String actor) {
    return p.store().tx(tx -> freeze(tx, runId, actor));
  }

  public Run freeze(TxContext tx, UUID runId, String actor) {
    Run r = tx.runs().lock(runId);
    if (r.status != RunStatus.ACTIVE) {
      throw new UndoException(UndoException.Code.RUN_NOT_ACTIVE, r.display() + " is " + r.status);
    }
    Instant now = p.clock().now();
    r.status = RunStatus.FROZEN;
    r.frozenAt = now;
    r.leaseOwner = null;
    r.leaseExpiresAt = null;
    tx.runs().update(r);
    int cancelled = freezeSideEffects(tx, r, actor);
    ObjectNode d = runData(r);
    d.put("actor", actor);
    d.put("cancelled_actions", cancelled);
    EventLog.append(tx, now, r.id, null, "run.frozen", d);
    return r;
  }

  /** ACTIVE -> FAILED plus freeze side effects. TX: one. Fenced when fence != null. */
  public Run fail(TxContext tx, UUID runId, String reason, String actor, Long fence) {
    Run r = tx.runs().lock(runId);
    if (fence != null && r.leaseFence != fence) {
      throw new UndoException(UndoException.Code.STALE_FENCE, "runner lease lost");
    }
    if (r.status != RunStatus.ACTIVE) {
      return r;
    }
    Instant now = p.clock().now();
    r.status = RunStatus.FAILED;
    r.failureReason = reason;
    r.completedAt = now;
    r.leaseOwner = null;
    r.leaseExpiresAt = null;
    tx.runs().update(r);
    freezeSideEffects(tx, r, actor);
    EventLog.append(tx, now, r.id, null, "run.failed", runData(r));
    return r;
  }

  /**
   * Cancels pending approvals and queued (not started) PRIMARY actions and releases their budget.
   * In-flight actions are left to the resolver, which only looks them up on a frozen run.
   * COMPENSATION and REMEDIATION actions are never touched. Returns how many were cancelled.
   */
  public int freezeSideEffects(TxContext tx, Run run, String actor) {
    Instant now = p.clock().now();
    List<Action> queued = tx.actions().lockQueuedPrimary(run.id);
    int n = 0;
    for (Action a : queued) {
      ActionStatus prev = a.status;
      long fence = a.leaseFence;
      if (prev == ActionStatus.AWAITING_APPROVAL) {
        tx.approvals()
            .byAction(a.id)
            .ifPresent(
                ap -> {
                  Approval locked = tx.approvals().lock(ap.id);
                  if (locked.status == ApprovalStatus.PENDING) {
                    locked.status = ApprovalStatus.CANCELLED;
                    locked.decidedAt = now;
                    locked.decisionNote = "run frozen";
                    tx.approvals().update(locked);
                    EventLog.append(
                        tx, now, a.runId, a.id, "approval.cancelled", EventLog.actionData(a));
                  }
                });
      }
      Transitions.move(tx, now, a, ActionStatus.CANCELLED, actor, "run_frozen");
      a.dedupeActive = false;
      a.completedAt = now;
      a.nextAttemptAt = null;
      Budget.releaseIfReserved(tx, a);
      if (!tx.actions().update(a, prev, fence)) {
        throw new IllegalStateException("locked action changed under freeze: " + a.display());
      }
      n++;
    }
    return n;
  }

  /** External runs without proposals for the idle timeout auto-complete (FR-RUN-6). TX: one. */
  public int closeIdle(Duration idleTimeout, int limit) {
    return p.store()
        .tx(
            tx -> {
              Instant now = p.clock().now();
              List<Run> idle = tx.runs().lockIdleExternal(now.minus(idleTimeout), limit);
              for (Run r : idle) {
                r.status = RunStatus.COMPLETED;
                r.completedAt = now;
                tx.runs().update(r);
                ObjectNode d = runData(r);
                d.put("actor", "system:idle-closer");
                EventLog.append(tx, now, r.id, null, "run.completed", d);
              }
              return idle.size();
            });
  }

  public EngineConfig config() {
    return cfg;
  }
}
