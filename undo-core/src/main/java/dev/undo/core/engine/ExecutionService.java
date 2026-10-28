package dev.undo.core.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ActionView;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.ExecutionOutcome;
import dev.undo.core.connector.IdempotencyClass;
import dev.undo.core.connector.ToolCall;
import dev.undo.core.json.Json;
import dev.undo.core.ledger.Ledger;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Attempt;
import dev.undo.core.model.AttemptKind;
import dev.undo.core.model.AttemptOutcome;
import dev.undo.core.port.Bug;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.TxContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Claims runnable actions and performs their provider calls (Section 9.3 CLAIM and EXECUTE).
 *
 * <p>Transactions: claimBatch = one TX; executeClaimed = one read TX + one fenced outcome TX (E4).
 * Crash points: CLAIM_AFTER_COMMIT, PROVIDER_CALL_AFTER_RESPONSE (E3), COMPLETE_AFTER_COMMIT (E5).
 */
public final class ExecutionService {
  /** A lease on one action: the fence it was given and the attempt it opened. */
  public record Claimed(UUID actionId, long fence, int attemptNo, String node, UUID attemptId) {}

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ExecutionService.class);
  private final Ports p;
  private final EngineConfig cfg;

  public ExecutionService(Ports p, EngineConfig cfg) {
    this.p = p;
    this.cfg = cfg;
  }

  /** CLAIM: APPROVED and due RETRY_WAIT actions, FOR UPDATE SKIP LOCKED, oldest first. */
  public List<Claimed> claimBatch(String node, int limit) {
    List<Claimed> claimed =
        p.store()
            .tx(
                tx -> {
                  Instant now = p.clock().now();
                  List<Claimed> out = new ArrayList<>();
                  for (Action a : tx.actions().lockRunnable(now, limit)) {
                    ActionStatus prev = a.status;
                    long prevFence = a.leaseFence;
                    a.leaseOwner = node;
                    a.leaseExpiresAt = now.plus(cfg.lease());
                    a.leaseFence = prevFence + 1;
                    a.attemptCount = a.attemptCount + 1;
                    a.firstAttemptAt = a.firstAttemptAt == null ? now : a.firstAttemptAt;
                    a.providerIdempotencyKey =
                        a.providerIdempotencyKey == null
                            ? "undo_" + a.id
                            : a.providerIdempotencyKey;
                    a.nextAttemptAt = null;
                    Transitions.move(tx, now, a, ActionStatus.EXECUTING, "worker:" + node, null);
                    if (!tx.actions().update(a, prev, prevFence)) {
                      throw new IllegalStateException(
                          "locked action changed during claim: " + a.display());
                    }
                    Attempt at = newAttempt(p, a, AttemptKind.EXECUTE, a.attemptCount, node, now);
                    tx.attempts().insert(at);
                    out.add(new Claimed(a.id, a.leaseFence, a.attemptCount, node, at.id));
                  }
                  return out;
                });
    p.crash().hit(CrashPoint.CLAIM_AFTER_COMMIT);
    return claimed;
  }

  static Attempt newAttempt(Ports p, Action a, AttemptKind kind, int no, String node, Instant now) {
    Attempt at = new Attempt();
    at.id = p.ids().next();
    at.actionId = a.id;
    at.kind = kind;
    at.attemptNo = no;
    at.node = node;
    at.fence = a.leaseFence;
    at.startedAt = now;
    return at;
  }

  /**
   * EXECUTE (E1–E6). Returns the provider time in nanoseconds. Never throws for provider problems;
   * a stale fence (someone else owns the action now) changes nothing.
   */
  public long executeClaimed(Claimed c) {
    try (Metrics.Span sp =
        p.metrics()
            .span(
                "undo.execute",
                "action",
                c.actionId().toString(),
                "fence",
                Long.toString(c.fence()),
                "attempt",
                Integer.toString(c.attemptNo()),
                "node",
                c.node())) {
      return executeClaimed0(c, sp);
    }
  }

  private long executeClaimed0(Claimed c, Metrics.Span sp) {
    Action a = p.store().tx(tx -> tx.actions().get(c.actionId())).orElseThrow();
    Connector connector = p.registry().connector(a.connector);
    // E1
    String key = a.providerIdempotencyKey;
    if (p.bugs().on(Bug.NEW_KEY_PER_ATTEMPT)) {
      key = key + "_" + c.attemptNo();
    }
    ToolCall call =
        new ToolCall(a.tool, a.args, a.id, key, c.attemptNo(), a.runId, a.beforeSnapshot);
    // E2
    Instant t0 = p.clock().now();
    sp.attr("tool", a.tool);
    ExecutionOutcome outcome;
    try (Metrics.Span cs = p.metrics().span("connector." + a.connector + "." + a.tool)) {
      outcome = connector.execute(call);
      cs.attr("outcome", outcomeName(outcome));
    }
    sp.attr("outcome", outcomeName(outcome));
    Instant t1 = p.clock().now();
    long providerNanos = Duration.between(t0, t1).toNanos();
    // E3
    p.crash().hit(CrashPoint.PROVIDER_CALL_AFTER_RESPONSE);
    // E4
    p.store().tx(tx -> writeOutcome(tx, c, outcome, providerNanos));
    // E5
    p.crash().hit(CrashPoint.COMPLETE_AFTER_COMMIT);
    // E6
    p.notifier().actionChanged(a.id);
    p.metrics()
        .time(
            "undo_action_execution_seconds",
            providerNanos,
            "tool",
            a.tool,
            "outcome",
            outcomeName(outcome));
    return providerNanos;
  }

  private Void writeOutcome(TxContext tx, Claimed c, ExecutionOutcome outcome, long providerNanos) {
    Instant now = p.clock().now();
    Action cur = tx.actions().lock(c.actionId());
    Attempt attempt = tx.attempts().get(cur.id, AttemptKind.EXECUTE, c.attemptNo()).orElse(null);
    boolean noFence = p.bugs().on(Bug.NO_FENCE_CHECK);
    if (!noFence && (cur.status != ActionStatus.EXECUTING || cur.leaseFence != c.fence())) {
      finishAttempt(tx, attempt, AttemptOutcome.STALE_FENCE, outcome, now, providerNanos);
      p.metrics().count("undo_stale_fence_rejections_total", "entity", "action");
      return null;
    }
    ActionStatus prev = cur.status;
    long prevFence = cur.leaseFence;
    Instant unknownSince = cur.unknownSince;
    String actor = "worker:" + c.node();
    cur.leaseOwner = null;
    cur.leaseExpiresAt = null;
    AttemptOutcome attemptOutcome;
    switch (outcome) {
      case ExecutionOutcome.Succeeded s -> {
        attemptOutcome = AttemptOutcome.SUCCEEDED;
        applySuccess(
            tx,
            cur,
            s.providerRef(),
            s.providerStatus(),
            s.result(),
            now,
            actor,
            "execution",
            ActionStatus.SUCCEEDED);
      }
      case ExecutionOutcome.FailedDefinitive f -> {
        attemptOutcome = AttemptOutcome.FAILED_DEFINITIVE;
        ObjectNode err = Json.obj();
        err.put("code", f.code());
        err.put("message", f.message());
        err.put("http_status", f.httpStatus());
        cur.error = err;
        failNoEffect(tx, cur, now, actor, f.code());
      }
      case ExecutionOutcome.RetryableNotApplied r -> {
        attemptOutcome = AttemptOutcome.RETRYABLE_NOT_APPLIED;
        Duration wait =
            max(
                r.retryAfter(),
                Backoff.of(cur.attemptCount, cfg.backoffBase(), cfg.backoffCap(), p.entropy()));
        if (cur.unknownSince != null) {
          // An earlier attempt's outcome is still unknown: this answer proves nothing about it
          // (ADR-014).
          cur.resolveAfter = now.plus(wait);
          Transitions.move(
              tx, now, cur, ActionStatus.UNKNOWN, actor, "retryable_after_unknown:" + r.code());
        } else if (cur.kind == ActionKind.PRIMARY
            && !tx.runs().get(cur.runId).orElseThrow().status.allowsPrimaryExecution()) {
          // The run was frozen while this attempt was in flight. Nothing was applied, and a
          // RETRY_WAIT action of a frozen run would never be claimed again (ADR-014).
          failNoEffect(tx, cur, now, actor, "not_applied_run_frozen");
        } else if (cur.attemptCount >= cfg.maxAttempts()) {
          failNoEffect(tx, cur, now, actor, "max_attempts_not_applied");
        } else {
          cur.nextAttemptAt = now.plus(wait);
          Transitions.move(tx, now, cur, ActionStatus.RETRY_WAIT, actor, r.code());
        }
      }
      case ExecutionOutcome.Unknown u -> {
        attemptOutcome = AttemptOutcome.UNKNOWN;
        log.info(
            "outcome unknown: {} {} attempt {} ({})",
            cur.display(),
            cur.tool,
            c.attemptNo(),
            u.cause());
        toUnknown(tx, cur, now, actor, u.cause());
      }
    }
    if (noFence) {
      tx.actions().updateUnconditional(cur, c.fence());
    } else if (!tx.actions().update(cur, prev, prevFence)) {
      throw new IllegalStateException("locked action changed: " + cur.display());
    }
    finishAttempt(tx, attempt, attemptOutcome, outcome, now, providerNanos);
    p.metrics()
        .count("undo_action_outcomes_total", "tool", cur.tool, "outcome", attemptOutcome.name());
    if (unknownSince != null && cur.unknownSince == null) {
      // A re-execution settled an UNKNOWN action (lookups count themselves in RecoveryService).
      p.metrics()
          .count(
              "undo_unknown_resolutions_total",
              "class",
              cur.idempotencyClass.name(),
              "result",
              "reexecute_" + cur.status.name().toLowerCase(java.util.Locale.ROOT));
      p.metrics()
          .time(
              "undo_unknown_resolution_seconds",
              Duration.between(unknownSince, now).toNanos(),
              "class",
              cur.idempotencyClass.name());
    }
    return null;
  }

  /** UNKNOWN (or NEEDS_REVIEW for NONE class) after an ambiguous outcome or an expired lease. */
  void toUnknown(TxContext tx, Action cur, Instant now, String actor, String cause) {
    boolean none =
        cur.idempotencyClass == IdempotencyClass.NONE && !p.bugs().on(Bug.NONE_CLASS_RETRY);
    cur.unknownSince = cur.unknownSince == null ? now : cur.unknownSince;
    if (none) {
      Transitions.move(
          tx, now, cur, ActionStatus.NEEDS_REVIEW, actor, "outcome_unknown_no_idempotency");
    } else {
      cur.resolveAfter = now.plus(graceFor(cur.idempotencyClass, cur.attemptCount, cause));
      Transitions.move(tx, now, cur, ActionStatus.UNKNOWN, actor, cause);
    }
  }

  /** Section 9.5 graceFor. */
  Duration graceFor(IdempotencyClass cls, int attempt, String cause) {
    if (cause != null && cause.equals("idempotency_request_in_progress")) {
      return Duration.ofSeconds(1);
    }
    return switch (cls) {
      case NATURAL -> Duration.ZERO;
      case NATIVE_KEY -> Backoff.of(attempt, cfg.backoffBase(), cfg.backoffCap(), p.entropy());
      case LOOKUP_BY_REFERENCE ->
          p.bugs().on(Bug.SKIP_LOOKUP_GRACE) ? Duration.ZERO : cfg.lookupGrace();
      case NONE -> Duration.ZERO;
    };
  }

  /**
   * SUCCEEDED: provider fields, journal entries, budget RESERVED → SPENT. Used by execution,
   * lookups, recon heals and human review.
   */
  void applySuccess(
      TxContext tx,
      Action cur,
      String ref,
      String providerStatus,
      com.fasterxml.jackson.databind.JsonNode result,
      Instant now,
      String actor,
      String source,
      ActionStatus to) {
    cur.providerRef = ref;
    cur.providerStatus = providerStatus;
    cur.result = result;
    cur.completedAt = now;
    cur.unknownSince = null;
    cur.resolveAfter = null;
    cur.error = null;
    Transitions.move(tx, now, cur, to, actor, null);
    Connector connector = p.registry().connector(cur.connector);
    Ledger.write(
        tx,
        p.ids(),
        p.bugs(),
        now,
        cur.runId,
        cur.id,
        connector.journalOnSuccess(ActionView.of(cur)),
        source,
        ref);
    // A lookup or a late answer can already show a later provider status (a refund that settled
    // or failed): post its entry too, so the ledger matches the provider (ADR-014 #8).
    if (providerStatus != null) {
      Ledger.write(
          tx,
          p.ids(),
          p.bugs(),
          now,
          cur.runId,
          cur.id,
          connector.journalOnProviderStatus(ActionView.of(cur), providerStatus),
          source,
          ref);
    }
    Budget.settleIfReserved(tx, cur);
  }

  /**
   * Recon auto-heal of a GHOST_EFFECT (Section 16.3): the caller has locked an UNKNOWN or
   * NEEDS_REVIEW action and found its provider object. Same success write as a lookup FOUND; the
   * caller does the fenced update.
   */
  public void applyReconSuccess(
      TxContext tx,
      Action cur,
      String ref,
      String providerStatus,
      com.fasterxml.jackson.databind.JsonNode result,
      Instant now) {
    applySuccess(
        tx, cur, ref, providerStatus, result, now, "recon", "recon", ActionStatus.SUCCEEDED);
  }

  /** FAILED with no effect: dedupe key inactive, budget released. */
  void failNoEffect(TxContext tx, Action cur, Instant now, String actor, String reason) {
    cur.completedAt = now;
    cur.unknownSince = null;
    cur.resolveAfter = null;
    cur.dedupeActive = false;
    Transitions.move(tx, now, cur, ActionStatus.FAILED, actor, reason);
    Budget.releaseIfReserved(tx, cur);
  }

  private static void finishAttempt(
      TxContext tx,
      Attempt at,
      AttemptOutcome o,
      ExecutionOutcome outcome,
      Instant now,
      long providerNanos) {
    if (at == null) {
      return;
    }
    at.finishedAt = now;
    at.outcome = o;
    at.latencyMs = (int) Math.min(Integer.MAX_VALUE, providerNanos / 1_000_000);
    ObjectNode d = Json.obj();
    switch (outcome) {
      case ExecutionOutcome.Succeeded s -> d.put("provider_ref", s.providerRef());
      case ExecutionOutcome.FailedDefinitive f -> {
        at.httpStatus = f.httpStatus();
        d.put("code", f.code());
      }
      case ExecutionOutcome.RetryableNotApplied r -> d.put("code", r.code());
      case ExecutionOutcome.Unknown u -> d.put("cause", u.cause());
    }
    at.detail = d;
    tx.attempts().update(at);
  }

  static String outcomeName(ExecutionOutcome o) {
    return switch (o) {
      case ExecutionOutcome.Succeeded s -> "succeeded";
      case ExecutionOutcome.FailedDefinitive f -> "failed_definitive";
      case ExecutionOutcome.RetryableNotApplied r -> "retryable_not_applied";
      case ExecutionOutcome.Unknown u -> "unknown";
    };
  }

  private static Duration max(Duration a, Duration b) {
    if (a == null) {
      return b;
    }
    return a.compareTo(b) >= 0 ? a : b;
  }

  public EngineConfig config() {
    return cfg;
  }
}
