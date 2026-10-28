package dev.undo.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.ConnectorException;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.json.Hashing;
import dev.undo.core.json.Json;
import dev.undo.core.json.SchemaValidator;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Agent;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.Attempt;
import dev.undo.core.model.AttemptKind;
import dev.undo.core.model.BudgetState;
import dev.undo.core.model.Decision;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.core.policy.PolicyEvaluator;
import dev.undo.core.policy.PolicyService;
import dev.undo.core.port.Bug;
import dev.undo.core.port.ConstraintViolationException;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.TxContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * validate → dedupe → facts → policy → budget → insert → (inline claim) → wait. Section 9.3
 * PROPOSE, steps P1–P11.
 *
 * <p>Transactions: one read TX for the call-key check (P4), one proposal TX (P7, retried once on
 * the expected unique violations), then the execution TXs of the inline path. Crash points:
 * PROPOSE_BEFORE_COMMIT (inside P7), PROPOSE_AFTER_COMMIT (P8), plus those of ExecutionService.
 */
public final class ProposalService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ProposalService.class);

  public record Request(
      Caller caller,
      UUID runId,
      UUID taskId,
      ActionKind kind,
      UUID compensatesActionId,
      UUID revertPlanId,
      String tool,
      JsonNode args,
      String agentCallId,
      String clientIdempotencyKey,
      long waitMs) {

    public static Request primary(Caller caller, UUID runId, String tool, JsonNode args) {
      return new Request(
          caller, runId, null, ActionKind.PRIMARY, null, null, tool, args, null, null, 0);
    }

    public Request withCallId(String id) {
      return new Request(
          caller,
          runId,
          taskId,
          kind,
          compensatesActionId,
          revertPlanId,
          tool,
          args,
          id,
          clientIdempotencyKey,
          waitMs);
    }

    public Request withClientKey(String key) {
      return new Request(
          caller,
          runId,
          taskId,
          kind,
          compensatesActionId,
          revertPlanId,
          tool,
          args,
          agentCallId,
          key,
          waitMs);
    }

    public Request withWait(long ms) {
      return new Request(
          caller,
          runId,
          taskId,
          kind,
          compensatesActionId,
          revertPlanId,
          tool,
          args,
          agentCallId,
          clientIdempotencyKey,
          ms);
    }

    public Request withTask(UUID id) {
      return new Request(
          caller,
          runId,
          id,
          kind,
          compensatesActionId,
          revertPlanId,
          tool,
          args,
          agentCallId,
          clientIdempotencyKey,
          waitMs);
    }
  }

  public sealed interface Outcome {
    /** Schema validation failed; no action row (FR-TOOL-2). */
    record Invalid(List<String> errors) implements Outcome {}

    /** Tool not registered; no action row. */
    record UnknownTool(String tool) implements Outcome {}

    /** Same call key, different args (FR-PROP-2). */
    record KeyReused(Action existing) implements Outcome {}

    /** A zombie runner tried to propose; no action row. */
    record StaleFence() implements Outcome {}

    /**
     * The action row (current status after waiting). {@code original} is set for business
     * duplicates. Timings in nanoseconds, for the Server-Timing header.
     */
    record Recorded(
        Action action, boolean replayed, Action original, long undoNanos, long providerNanos)
        implements Outcome {}
  }

  private final Ports p;
  private final PolicyService policies;
  private final ExecutionService execution;
  private final EngineConfig cfg;
  private final SchemaValidator validator = new SchemaValidator();

  public ProposalService(
      Ports p, PolicyService policies, ExecutionService execution, EngineConfig cfg) {
    this.p = p;
    this.policies = policies;
    this.execution = execution;
    this.cfg = cfg;
  }

  public Outcome propose(Request r) {
    try (Metrics.Span sp =
        p.metrics().span("undo.propose", "tool", r.tool(), "run", String.valueOf(r.runId()))) {
      Outcome o = propose0(r);
      sp.attr("outcome", o.getClass().getSimpleName());
      return o;
    }
  }

  private Outcome propose0(Request r) {
    Instant start = p.clock().now();
    // P1
    ToolSpec tool = p.registry().tool(r.tool());
    if (tool == null) {
      return new Outcome.UnknownTool(r.tool());
    }
    // P2
    List<String> errors = validator.validate(tool.name(), tool.inputSchema(), r.args());
    if (!errors.isEmpty()) {
      return new Outcome.Invalid(errors);
    }
    // P3
    String argsHash = Hashing.argsHash(tool.name(), r.args());
    // P4
    Outcome replay = replayIfKnown(r, argsHash, start);
    if (replay != null) {
      return replay;
    }
    Connector connector = p.registry().connector(tool.connectorId());
    // Bug flag BUDGET_CHECK_THEN_ACT: read the budget in its own TX, before the slow fact reads.
    Boolean budgetPrecheck = null;
    if (p.bugs().on(Bug.BUDGET_CHECK_THEN_ACT)
        && r.kind() == ActionKind.PRIMARY
        && tool.effect() == Effect.WRITE) {
      long money = moneyOut(tool, r.args());
      budgetPrecheck =
          p.store()
              .tx(
                  tx -> {
                    Run run = tx.runs().get(r.runId()).orElseThrow();
                    return run.moneyReservedMinor + run.moneySpentMinor + money <= run.moneyCapMinor
                        && run.actionCount + 1 <= run.actionCap;
                  });
    }
    // P5, P6 (outside any TX)
    JsonNode facts = null;
    JsonNode before = null;
    String forcedDeny = null;
    try {
      try (Metrics.Span sp = p.metrics().span("undo.facts", "tool", tool.name())) {
        facts = connector.gatherFacts(tool.name(), r.args());
        if (tool.capturesBefore()) {
          before = connector.captureBefore(tool.name(), r.args());
        }
      }
    } catch (ConnectorException | RuntimeException e) {
      log.warn("facts unavailable for {}: {}", tool.name(), e.toString());
      facts = null;
      forcedDeny = "facts_unavailable";
    }
    // P7 (retried once on the expected unique violations)
    Proposed proposed = null;
    for (int attempt = 0; attempt < 3 && proposed == null; attempt++) {
      try {
        final JsonNode f = facts;
        final JsonNode b = before;
        final String forced = forcedDeny;
        final Boolean pre = budgetPrecheck;
        proposed = p.store().tx(tx -> decideAndInsert(tx, r, tool, argsHash, f, b, forced, pre));
      } catch (ConstraintViolationException e) {
        String c = e.constraint();
        if (c.equals("actions_agent_call") || c.equals("actions_client_key")) {
          Outcome again = replayIfKnown(r, argsHash, start);
          if (again != null) {
            return again;
          }
        } else if (!c.equals("actions_dedupe")) {
          throw e;
        }
        if (attempt == 2) {
          throw e;
        }
      }
    }
    if (proposed.stale) {
      return new Outcome.StaleFence();
    }
    // P8
    p.crash().hit(CrashPoint.PROPOSE_AFTER_COMMIT);
    p.notifier().actionChanged(proposed.action.id);
    // P9
    long providerNanos = 0;
    if (proposed.claimed != null) {
      providerNanos = execution.executeClaimed(proposed.claimed);
    }
    // P10, P11
    Action current = waitFor(proposed.action.id, r.waitMs());
    Action original = original(current);
    long total = Duration.between(start, p.clock().now()).toNanos();
    p.metrics()
        .time(
            "undo_action_overhead_seconds",
            Math.max(0, total - providerNanos),
            "tool",
            tool.name());
    return new Outcome.Recorded(
        current, false, original, Math.max(0, total - providerNanos), providerNanos);
  }

  private Outcome replayIfKnown(Request r, String argsHash, Instant start) {
    if (r.agentCallId() == null && r.clientIdempotencyKey() == null) {
      return null;
    }
    Optional<Action> existing =
        p.store()
            .tx(tx -> tx.actions().byCallKey(r.runId(), r.agentCallId(), r.clientIdempotencyKey()));
    if (existing.isEmpty()) {
      return null;
    }
    Action a = existing.get();
    if (!a.argsHash.equals(argsHash)) {
      return new Outcome.KeyReused(a);
    }
    p.metrics().count("undo_action_duplicates_total", "tool", a.tool, "type", "call_key");
    Action current = waitFor(a.id, r.waitMs());
    long total = Duration.between(start, p.clock().now()).toNanos();
    return new Outcome.Recorded(current, true, original(current), total, 0);
  }

  private Action original(Action a) {
    if (a.duplicateOfActionId == null) {
      return null;
    }
    return p.store().tx(tx -> tx.actions().get(a.duplicateOfActionId)).orElse(null);
  }

  /** P10: waits until terminal or AWAITING_APPROVAL or the wait elapsed. */
  private Action waitFor(UUID id, long waitMs) {
    long ms = Math.min(Math.max(waitMs, 0), cfg.maxWaitMs());
    Instant deadline = p.clock().now().plusMillis(ms);
    while (true) {
      Action a = p.store().tx(tx -> tx.actions().get(id)).orElseThrow();
      if (a.status.isTerminal()
          || a.status == ActionStatus.AWAITING_APPROVAL
          || a.status == ActionStatus.NEEDS_REVIEW) {
        return a;
      }
      if (!p.clock().now().isBefore(deadline)) {
        return a;
      }
      p.waiter().awaitActionChange(id, deadline);
    }
  }

  private static final class Proposed {
    Action action;
    ExecutionService.Claimed claimed;
    boolean stale;
  }

  private Proposed decideAndInsert(
      TxContext tx,
      Request r,
      ToolSpec tool,
      String argsHash,
      JsonNode facts,
      JsonNode before,
      String forcedDeny,
      Boolean budgetPrecheck) {
    Instant now = p.clock().now();
    Proposed out = new Proposed();
    Run run = tx.runs().lock(r.runId());
    if (r.caller().isRunner() && run.leaseFence != r.caller().runFence()) {
      out.stale = true;
      return out;
    }
    String forced = forcedDeny;
    if (r.kind() == ActionKind.PRIMARY && run.status != RunStatus.ACTIVE) {
      forced = "run_not_active";
    }
    if (r.kind() == ActionKind.PRIMARY && tool.visibility() == Visibility.INTERNAL) {
      forced = "internal_tool";
    }
    Action a = new Action();
    a.id = p.ids().next();
    a.runId = run.id;
    a.taskId = r.taskId();
    a.kind = r.kind();
    a.compensatesActionId = r.compensatesActionId();
    a.revertPlanId = r.revertPlanId();
    a.connector = tool.connectorId();
    a.tool = tool.name();
    a.effect = tool.effect();
    a.idempotencyClass = tool.idempotencyClass();
    a.args = r.args();
    a.argsHash = argsHash;
    a.agentCallId = r.agentCallId();
    a.clientIdempotencyKey = r.clientIdempotencyKey();
    a.policyVersionId = run.policyVersionId;
    a.facts = facts;
    a.beforeSnapshot = before;
    a.matchedRules = Json.arr();
    a.createdAt = now;
    a.updatedAt = now;

    Decision decision;
    String reason;
    ObjectNode error = null;
    if (forced != null) {
      decision = Decision.DENY;
      reason = forced;
    } else {
      PolicyEvaluator.Result pr;
      try (Metrics.Span sp = p.metrics().span("undo.policy", "tool", tool.name())) {
        pr = policies.evaluate(tx, run.policyVersionId, tool.name(), context(tx, r, run, facts));
        sp.attr("decision", pr.decision().name());
      }
      decision = pr.decision();
      reason = pr.decision() == Decision.DENY ? pr.reasonCode() : null;
      ArrayNode rules = Json.arr();
      for (PolicyEvaluator.MatchedRule m : pr.matched()) {
        ObjectNode o = rules.addObject();
        o.put("id", m.id());
        o.put("decision", m.decision().name().toLowerCase());
        o.put("reason", m.reason());
      }
      a.matchedRules = rules;
      if (pr.error() != null) {
        error = Json.obj();
        error.put("code", "policy_error");
        error.put("message", pr.error());
      } else if (decision == Decision.DENY) {
        error = Json.obj();
        error.put("code", "policy_denied");
        error.put("message", pr.reasonText());
        if (pr.ruleId() != null) {
          error.put("rule", pr.ruleId());
        }
      }
    }
    // Business dedupe (FR-PROP-3, FR-PROP-4)
    String dedupeKey =
        tool.effect() == Effect.WRITE && r.kind() == ActionKind.PRIMARY
            ? renderDedupeKey(tool, r.args())
            : null;
    a.dedupeKey = dedupeKey;
    if (dedupeKey != null && forced == null) {
      Optional<Action> dup = tx.actions().activeByDedupeKey(dedupeKey);
      if (dup.isPresent()) {
        decision = Decision.DENY;
        reason = "duplicate";
        a.duplicateOfActionId = dup.get().id;
        error = Json.obj();
        error.put("code", "duplicate");
        error.put("message", "Same as " + dup.get().display() + ".");
        error.put("duplicate_of", dup.get().display());
        p.metrics().count("undo_action_duplicates_total", "tool", tool.name(), "type", "business");
      }
    }
    // Budget reservation (FR-BUD-2): one conditional update in this TX.
    if ((decision == Decision.ALLOW || decision == Decision.REQUIRE_APPROVAL)
        && tool.effect() == Effect.WRITE
        && r.kind() == ActionKind.PRIMARY) {
      long money = moneyOut(tool, r.args());
      a.moneyOutMinor = money;
      boolean ok;
      if (budgetPrecheck != null) {
        ok = budgetPrecheck;
        if (ok) {
          tx.runs().reserveUnchecked(run.id, money, 1);
        }
      } else {
        ok = tx.runs().reserve(run.id, money, 1);
      }
      if (!ok) {
        decision = Decision.DENY;
        reason = "budget_exceeded";
        error = budgetError(tx, run.id, money);
        p.metrics().count("undo_budget_denials_total", "budget", error.path("budget").asText());
      } else {
        a.budgetState = BudgetState.RESERVED;
      }
    } else if (tool.effect() == Effect.WRITE && r.kind() == ActionKind.PRIMARY) {
      a.moneyOutMinor = moneyOut(tool, r.args());
    }
    if (r.kind() == ActionKind.REMEDIATION && decision == Decision.ALLOW) {
      decision = Decision.REQUIRE_APPROVAL; // FR-APR-7
    }
    a.decision = decision;
    a.error = error;
    a.dedupeActive = dedupeKey != null && decision != Decision.DENY;
    ActionStatus to =
        switch (decision) {
          case DENY -> ActionStatus.DENIED;
          case REQUIRE_APPROVAL -> ActionStatus.AWAITING_APPROVAL;
          case ALLOW -> ActionStatus.APPROVED;
        };
    a.status = to;
    a.statusReason = reason;
    if (to == ActionStatus.DENIED) {
      a.completedAt = now;
    }
    ExecutionService.Claimed claimed = null;
    Attempt attempt = null;
    boolean inline =
        to == ActionStatus.APPROVED
            && r.caller().inline()
            && (cfg.inline() || r.caller().isRunner());
    if (inline) {
      // Inline fast path (FR-EXEC-5): PROPOSED→APPROVED→EXECUTING in one TX, two transition rows.
      a.status = ActionStatus.EXECUTING;
      a.leaseOwner = r.caller().node();
      a.leaseExpiresAt = now.plus(cfg.lease());
      a.leaseFence = 1;
      a.attemptCount = 1;
      a.firstAttemptAt = now;
      a.providerIdempotencyKey = "undo_" + a.id;
    }
    Action saved = tx.actions().insert(a);
    String actor = r.caller().actor();
    Transitions.record(tx, now, saved, null, ActionStatus.PROPOSED, actor, null);
    Transitions.record(tx, now, saved, ActionStatus.PROPOSED, to, actor, reason);
    if (inline) {
      Transitions.record(
          tx, now, saved, ActionStatus.APPROVED, ActionStatus.EXECUTING, actor, null);
      attempt =
          ExecutionService.newAttempt(p, saved, AttemptKind.EXECUTE, 1, r.caller().node(), now);
      tx.attempts().insert(attempt);
      claimed =
          new ExecutionService.Claimed(
              saved.id, saved.leaseFence, 1, r.caller().node(), attempt.id);
    }
    if (reason != null && reason.equals("duplicate")) {
      EventLog.append(
          tx, now, saved.runId, saved.id, "action.duplicate", EventLog.actionData(saved));
    }
    if (reason != null && reason.equals("budget_exceeded")) {
      ObjectNode d = EventLog.actionData(saved);
      d.set("budget", error);
      EventLog.append(tx, now, saved.runId, saved.id, "budget.denied", d);
    }
    if (to == ActionStatus.AWAITING_APPROVAL) {
      insertApproval(tx, saved, run, now);
    }
    run.lastActivityAt = now;
    tx.runs().update(run);
    p.metrics()
        .count(
            "undo_actions_proposed_total",
            "tool",
            tool.name(),
            "decision",
            decision.name(),
            "kind",
            r.kind().name());
    p.crash().hit(CrashPoint.PROPOSE_BEFORE_COMMIT);
    out.action = saved;
    out.claimed = claimed;
    return out;
  }

  private void insertApproval(TxContext tx, Action a, Run run, Instant now) {
    JsonNode compiled = policies.compiled(tx, run.policyVersionId);
    Duration expiry;
    try {
      expiry =
          Duration.parse(
              compiled
                  .path("approvals_expires_after")
                  .asText(cfg.defaultApprovalExpiry().toString()));
    } catch (RuntimeException e) {
      expiry = cfg.defaultApprovalExpiry();
    }
    Approval ap = new Approval();
    ap.id = p.ids().next();
    ap.actionId = a.id;
    ap.runId = a.runId;
    ap.argsHash = a.argsHash;
    ap.policyVersionId = a.policyVersionId;
    ap.reasons = a.matchedRules.isEmpty() ? defaultReasons(a) : a.matchedRules;
    ap.status = ApprovalStatus.PENDING;
    ap.requestedAt = now;
    ap.expiresAt = now.plus(expiry);
    tx.approvals().insert(ap);
    ObjectNode d = EventLog.actionData(a);
    d.put("approval_id", ap.id.toString());
    d.put("expires_at", ap.expiresAt.toString());
    EventLog.append(tx, now, a.runId, a.id, "approval.requested", d);
  }

  private static JsonNode defaultReasons(Action a) {
    ArrayNode arr = Json.arr();
    ObjectNode o = arr.addObject();
    if (a.kind == ActionKind.REMEDIATION) {
      o.put("id", "remediation.always-needs-human");
      o.put("decision", "require_approval");
      o.put("reason", "Remediation actions always need a human.");
    } else {
      o.put("id", "default");
      o.put("decision", "require_approval");
      o.put("reason", "This tool always needs a human.");
    }
    return arr;
  }

  private PolicyEvaluator.Context context(TxContext tx, Request r, Run run, JsonNode facts) {
    ObjectNode runCtx = Json.obj();
    runCtx.put("number", run.number);
    runCtx.put("kind", run.kind.name());
    runCtx.put("scenario", run.scenario);
    runCtx.set("labels", run.labels == null ? Json.obj() : run.labels);
    runCtx.put("money_spent_minor", run.moneySpentMinor);
    runCtx.put("money_reserved_minor", run.moneyReservedMinor);
    runCtx.put("action_count", run.actionCount);
    ObjectNode agent = Json.obj();
    agent.put("name", tx.agents().get(run.agentId).map((Agent ag) -> ag.name).orElse(null));
    ObjectNode ctx = Json.obj();
    ctx.put("kind", r.kind().name());
    ctx.put("is_compensation", r.kind() == ActionKind.COMPENSATION);
    return new PolicyEvaluator.Context(
        r.args(), facts == null ? Json.obj() : facts, runCtx, agent, ctx);
  }

  private ObjectNode budgetError(TxContext tx, UUID runId, long money) {
    Run run = tx.runs().get(runId).orElseThrow();
    ObjectNode e = Json.obj();
    e.put("code", "budget_exceeded");
    boolean moneyShort = run.moneyReservedMinor + run.moneySpentMinor + money > run.moneyCapMinor;
    e.put("budget", moneyShort ? "money" : "actions");
    if (moneyShort) {
      e.put("cap_minor", run.moneyCapMinor);
      e.put("reserved_minor", run.moneyReservedMinor);
      e.put("spent_minor", run.moneySpentMinor);
      e.put("requested_minor", money);
      e.put(
          "message",
          "Run budget exceeded: money (requested "
              + Money.format(money)
              + ", remaining "
              + Money.format(
                  Math.max(0, run.moneyCapMinor - run.moneyReservedMinor - run.moneySpentMinor))
              + ").");
    } else {
      e.put("cap", run.actionCap);
      e.put("used", run.actionCount);
      e.put(
          "message",
          "Run budget exceeded: write actions ("
              + run.actionCount
              + " of "
              + run.actionCap
              + " used).");
    }
    return e;
  }

  /** "refund:order:{order_id}" → "refund:order:ORD-0042"; null if any placeholder is null. */
  static String renderDedupeKey(ToolSpec tool, JsonNode args) {
    if (tool.dedupeKeyTemplate().isEmpty()) {
      return null;
    }
    String tpl = tool.dedupeKeyTemplate().get();
    Matcher m = java.util.regex.Pattern.compile("\\{([a-z0-9_]+)}").matcher(tpl);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      JsonNode v = args.get(m.group(1));
      if (v == null || v.isNull()) {
        return null;
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(v.asText()));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  static long moneyOut(ToolSpec tool, JsonNode args) {
    if (tool.moneyOutPointer().isEmpty()) {
      return 0;
    }
    JsonNode v = args.at(tool.moneyOutPointer().get());
    return v.isIntegralNumber() ? v.asLong() : 0;
  }
}
