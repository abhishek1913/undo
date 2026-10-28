package dev.undo.core.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.IdempotencyClass;
import dev.undo.core.connector.LookupOutcome;
import dev.undo.core.connector.ToolCall;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Attempt;
import dev.undo.core.model.AttemptKind;
import dev.undo.core.model.AttemptOutcome;
import dev.undo.core.model.Run;
import dev.undo.core.port.Bug;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.TxContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns expired leases and UNKNOWN outcomes into known outcomes (Sections 9.3 SWEEP/RESOLVE, 9.5).
 *
 * <p>Transactions: sweepExpiredLeases = one TX; resolveUnknownBatch = one claim TX, then per action
 * either the execution TXs (re-execute) or one fenced lookup-result TX. Crash point:
 * RESOLVE_AFTER_LOOKUP.
 */
public final class RecoveryService {
  public enum Strategy {
    REEXECUTE,
    LOOKUP,
    REVIEW
  }

  private record LookupWork(UUID actionId, long fence, int lookupNo, String node) {}

  /** One claimed UNKNOWN action: either a re-execution or a lookup, to perform outside the TX. */
  public static final class Work {
    final ExecutionService.Claimed reexecute;
    final LookupWork lookup;

    Work(ExecutionService.Claimed reexecute, LookupWork lookup) {
      this.reexecute = reexecute;
      this.lookup = lookup;
    }

    public UUID actionId() {
      return reexecute != null ? reexecute.actionId() : lookup.actionId();
    }
  }

  private final Ports p;
  private final ExecutionService execution;
  private final EngineConfig cfg;

  public RecoveryService(Ports p, ExecutionService execution, EngineConfig cfg) {
    this.p = p;
    this.execution = execution;
    this.cfg = cfg;
  }

  /** SWEEP: EXECUTING with an expired lease → UNKNOWN (NONE class → NEEDS_REVIEW), fence + 1. */
  public int sweepExpiredLeases(String node, int limit) {
    try (Metrics.Span sp = p.metrics().span("undo.sweep", "node", node)) {
      return sweep0(node, limit);
    }
  }

  private int sweep0(String node, int limit) {
    return p.store()
        .tx(
            tx -> {
              Instant now = p.clock().now();
              List<Action> expired = tx.actions().lockExpiredExecuting(now, limit);
              for (Action a : expired) {
                ActionStatus prev = a.status;
                long prevFence = a.leaseFence;
                a.leaseOwner = null;
                a.leaseExpiresAt = null;
                a.leaseFence = prevFence + 1;
                tx.attempts()
                    .get(a.id, AttemptKind.EXECUTE, a.attemptCount)
                    .filter(at -> at.finishedAt == null)
                    .ifPresent(
                        at -> {
                          at.finishedAt = now;
                          at.outcome = AttemptOutcome.UNKNOWN;
                          ObjectNode d = Json.obj();
                          d.put("cause", "lease_expired");
                          at.detail = d;
                          tx.attempts().update(at);
                        });
                execution.toUnknown(tx, a, now, "system:sweeper", "lease_expired");
                if (!tx.actions().update(a, prev, prevFence)) {
                  throw new IllegalStateException(
                      "locked action changed during sweep: " + a.display());
                }
                p.metrics().count("undo_lease_expirations_total", "entity", "action");
              }
              return expired.size();
            });
  }

  /** Section 9.5 decide(). */
  Strategy decide(Action a, ToolSpec tool, boolean frozen, Instant now) {
    if (a.unknownSince != null
        && Duration.between(a.unknownSince, now).compareTo(cfg.maxUnknownAge()) > 0) {
      return Strategy.REVIEW;
    }
    if (a.idempotencyClass == IdempotencyClass.NONE && !p.bugs().on(Bug.NONE_CLASS_RETRY)) {
      return Strategy.REVIEW;
    }
    if (frozen) {
      return Strategy.LOOKUP;
    }
    if (a.attemptCount >= cfg.maxAttempts()) {
      return Strategy.LOOKUP;
    }
    return switch (a.idempotencyClass) {
      case NATURAL -> Strategy.REEXECUTE;
      case NATIVE_KEY -> {
        Duration keyAge =
            a.firstAttemptAt == null ? Duration.ZERO : Duration.between(a.firstAttemptAt, now);
        Duration safe = tool.nativeKeyTtl().minus(cfg.nativeKeySafetyMargin());
        yield keyAge.compareTo(safe) < 0 ? Strategy.REEXECUTE : Strategy.LOOKUP;
      }
      case LOOKUP_BY_REFERENCE ->
          p.bugs().on(Bug.RETRY_UNKNOWN_WITHOUT_LOOKUP) ? Strategy.REEXECUTE : Strategy.LOOKUP;
      case NONE -> Strategy.REEXECUTE; // only reachable with bug flag NONE_CLASS_RETRY
    };
  }

  private boolean frozen(Action a, Run run) {
    if (p.bugs().on(Bug.RESOLVER_IGNORES_FREEZE)) {
      return false;
    }
    // COMPENSATION and REMEDIATION actions are never frozen.
    return a.kind == ActionKind.PRIMARY && !run.status.allowsPrimaryExecution();
  }

  /** RESOLVE: claims due UNKNOWN actions and re-executes, looks up, or sends them to review. */
  public int resolveUnknownBatch(String node, int limit) {
    List<Work> work = claimResolvable(node, limit);
    for (Work w : work) {
      perform(w);
    }
    return work.size();
  }

  /** Performs one claimed item (provider call outside any TX). */
  public void perform(Work w) {
    try (Metrics.Span sp = p.metrics().span("undo.resolve")) {
      sp.attr("strategy", w.reexecute != null ? "reexecute" : "lookup");
      if (w.reexecute != null) {
        execution.executeClaimed(w.reexecute);
      } else {
        lookup(w.lookup);
      }
    }
  }

  /**
   * The claim TX of RESOLVE: decides the strategy per action; REVIEW is applied here, re-executions
   * and lookups are returned to perform. TX: one.
   */
  public List<Work> claimResolvable(String node, int limit) {
    List<Work> out = new ArrayList<>();
    p.store()
        .tx(
            tx -> {
              Instant now = p.clock().now();
              List<Action> due = tx.actions().lockResolvable(now, limit);
              for (Action a : due) {
                Run run = tx.runs().get(a.runId).orElseThrow();
                ToolSpec tool = p.registry().tool(a.tool);
                Strategy s = decide(a, tool, frozen(a, run), now);
                ActionStatus prev = a.status;
                long prevFence = a.leaseFence;
                switch (s) {
                  case REEXECUTE -> {
                    a.leaseOwner = node;
                    a.leaseExpiresAt = now.plus(cfg.lease());
                    a.leaseFence = prevFence + 1;
                    a.attemptCount = a.attemptCount + 1;
                    a.firstAttemptAt = a.firstAttemptAt == null ? now : a.firstAttemptAt;
                    a.providerIdempotencyKey =
                        a.providerIdempotencyKey == null
                            ? "undo_" + a.id
                            : a.providerIdempotencyKey;
                    Transitions.move(
                        tx, now, a, ActionStatus.EXECUTING, "worker:" + node, "resolve_reexecute");
                    update(tx, a, prev, prevFence);
                    Attempt at =
                        ExecutionService.newAttempt(
                            p, a, AttemptKind.EXECUTE, a.attemptCount, node, now);
                    tx.attempts().insert(at);
                    out.add(
                        new Work(
                            new ExecutionService.Claimed(
                                a.id, a.leaseFence, a.attemptCount, node, at.id),
                            null));
                  }
                  case LOOKUP -> {
                    a.leaseOwner = node;
                    a.leaseExpiresAt = now.plus(cfg.lease());
                    a.leaseFence = prevFence + 1;
                    a.lookupCount = a.lookupCount + 1;
                    a.updatedAt = now;
                    update(tx, a, prev, prevFence);
                    Attempt at =
                        ExecutionService.newAttempt(
                            p, a, AttemptKind.LOOKUP, a.lookupCount, node, now);
                    tx.attempts().insert(at);
                    out.add(
                        new Work(null, new LookupWork(a.id, a.leaseFence, a.lookupCount, node)));
                  }
                  case REVIEW -> {
                    a.leaseOwner = null;
                    a.leaseExpiresAt = null;
                    String why =
                        a.idempotencyClass == IdempotencyClass.NONE
                            ? "outcome_unknown_no_idempotency"
                            : "max_unknown_age";
                    Transitions.move(tx, now, a, ActionStatus.NEEDS_REVIEW, "worker:" + node, why);
                    update(tx, a, prev, prevFence);
                    p.metrics()
                        .count(
                            "undo_unknown_resolutions_total",
                            "class",
                            a.idempotencyClass.name(),
                            "result",
                            "review");
                  }
                }
              }
              return due.size();
            });
    return out;
  }

  private static void update(TxContext tx, Action a, ActionStatus prev, long prevFence) {
    if (!tx.actions().update(a, prev, prevFence)) {
      throw new IllegalStateException("locked action changed during resolve: " + a.display());
    }
  }

  private void lookup(LookupWork w) {
    Action a = p.store().tx(tx -> tx.actions().get(w.actionId())).orElseThrow();
    Connector connector = p.registry().connector(a.connector);
    ToolCall call =
        new ToolCall(
            a.tool,
            a.args,
            a.id,
            a.providerIdempotencyKey,
            a.attemptCount,
            a.runId,
            a.beforeSnapshot);
    LookupOutcome r = connector.lookup(call);
    p.crash().hit(CrashPoint.RESOLVE_AFTER_LOOKUP);
    p.store().tx(tx -> applyLookup(tx, w, r));
    p.notifier().actionChanged(a.id);
  }

  private Void applyLookup(TxContext tx, LookupWork w, LookupOutcome r) {
    Instant now = p.clock().now();
    Action cur = tx.actions().lock(w.actionId());
    Attempt at = tx.attempts().get(cur.id, AttemptKind.LOOKUP, w.lookupNo()).orElse(null);
    if (cur.status != ActionStatus.UNKNOWN || cur.leaseFence != w.fence()) {
      finish(tx, at, AttemptOutcome.STALE_FENCE, now, "stale");
      p.metrics().count("undo_stale_fence_rejections_total", "entity", "action");
      return null;
    }
    Run run = tx.runs().get(cur.runId).orElseThrow();
    boolean frozen = frozen(cur, run);
    ActionStatus prev = cur.status;
    long prevFence = cur.leaseFence;
    cur.leaseOwner = null;
    cur.leaseExpiresAt = null;
    cur.updatedAt = now;
    String actor = "worker:" + w.node();
    switch (r) {
      case LookupOutcome.Found f -> {
        Instant since = cur.unknownSince;
        execution.applySuccess(
            tx,
            cur,
            f.providerRef(),
            f.providerStatus(),
            f.result(),
            now,
            actor,
            "execution",
            ActionStatus.SUCCEEDED);
        finish(tx, at, AttemptOutcome.FOUND, now, f.providerRef());
        resolved(cur, since, now, "found");
      }
      case LookupOutcome.NotFound nf -> {
        finish(tx, at, AttemptOutcome.NOT_FOUND, now, null);
        Instant trustAt = trustAt(tx, cur);
        if (now.isBefore(trustAt)) {
          cur.resolveAfter = trustAt; // assumption A1: a late request may still land
        } else if (frozen) {
          Instant since = cur.unknownSince;
          execution.failNoEffect(tx, cur, now, actor, "not_applied_run_frozen");
          resolved(cur, since, now, "not_applied");
        } else if (cur.attemptCount >= cfg.maxAttempts()) {
          Instant since = cur.unknownSince;
          execution.failNoEffect(tx, cur, now, actor, "max_attempts_not_applied");
          resolved(cur, since, now, "not_applied");
        } else {
          cur.nextAttemptAt = null;
          Transitions.move(tx, now, cur, ActionStatus.APPROVED, actor, "lookup_not_found_requeue");
        }
      }
      case LookupOutcome.Unavailable u -> {
        finish(tx, at, AttemptOutcome.LOOKUP_UNAVAILABLE, now, u.cause());
        cur.resolveAfter =
            now.plus(Backoff.of(cur.lookupCount, cfg.backoffBase(), cfg.backoffCap(), p.entropy()));
      }
    }
    if (!tx.actions().update(cur, prev, prevFence)) {
      throw new IllegalStateException("locked action changed during lookup: " + cur.display());
    }
    return null;
  }

  /** NotFound is trusted only after the last request's provider timeout plus the A1 grace. */
  private Instant trustAt(TxContext tx, Action a) {
    Attempt last = tx.attempts().lastExecute(a.id).orElse(null);
    if (last == null) {
      return Instant.MIN;
    }
    if (p.bugs().on(Bug.SKIP_LOOKUP_GRACE)) {
      return last.startedAt;
    }
    return last.startedAt.plus(cfg.providerTimeout()).plus(cfg.lookupGrace());
  }

  private void resolved(Action a, Instant since, Instant now, String result) {
    p.metrics()
        .count(
            "undo_unknown_resolutions_total", "class", a.idempotencyClass.name(), "result", result);
    if (since != null) {
      p.metrics()
          .time(
              "undo_unknown_resolution_seconds",
              Duration.between(since, now).toNanos(),
              "class",
              a.idempotencyClass.name());
    }
  }

  private static void finish(
      TxContext tx, Attempt at, AttemptOutcome o, Instant now, String detail) {
    if (at == null) {
      return;
    }
    at.finishedAt = now;
    at.outcome = o;
    if (detail != null) {
      ObjectNode d = Json.obj();
      d.put("detail", detail);
      at.detail = d;
    }
    tx.attempts().update(at);
  }

  /**
   * FR-REC-5: a human resolves NEEDS_REVIEW as "effect happened" (with provider reference) or
   * "effect did not happen". TX: one.
   */
  public Action review(
      UUID actionId,
      boolean happened,
      String providerRef,
      String providerStatus,
      String note,
      String actor) {
    if (note == null || note.isBlank()) {
      throw new UndoException(UndoException.Code.INVALID_REQUEST, "A note is required.");
    }
    if (happened && (providerRef == null || providerRef.isBlank())) {
      throw new UndoException(
          UndoException.Code.INVALID_REQUEST,
          "A provider reference is required when the effect happened.");
    }
    Action out =
        p.store()
            .tx(
                tx -> {
                  Instant now = p.clock().now();
                  Action a = tx.actions().lock(actionId);
                  if (a.status != ActionStatus.NEEDS_REVIEW) {
                    throw new UndoException(
                        UndoException.Code.NOT_REVIEWABLE, a.display() + " is " + a.status);
                  }
                  ActionStatus prev = a.status;
                  long fence = a.leaseFence;
                  ObjectNode result = Json.obj();
                  result.put("reviewed", true);
                  result.put("note", note);
                  if (happened) {
                    result.put("provider_ref", providerRef);
                    execution.applySuccess(
                        tx,
                        a,
                        providerRef,
                        providerStatus,
                        result,
                        now,
                        actor,
                        "review",
                        ActionStatus.SUCCEEDED);
                  } else {
                    a.result = result;
                    execution.failNoEffect(tx, a, now, actor, "review_did_not_happen");
                  }
                  if (!tx.actions().update(a, prev, fence)) {
                    throw new IllegalStateException("locked action changed during review");
                  }
                  ObjectNode d = EventLog.actionData(a);
                  d.put("note", note);
                  d.put("outcome", happened ? "happened" : "did_not_happen");
                  d.put("actor", actor);
                  EventLog.append(tx, now, a.runId, a.id, "action.reviewed", d);
                  return a;
                });
    p.notifier().actionChanged(actionId);
    return out;
  }
}
