package dev.undo.core.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ActionView;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.ConnectorException;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.Remediation;
import dev.undo.core.connector.ReversalPlan;
import dev.undo.core.engine.Caller;
import dev.undo.core.engine.EngineConfig;
import dev.undo.core.engine.EventLog;
import dev.undo.core.engine.Metrics;
import dev.undo.core.engine.Money;
import dev.undo.core.engine.Ports;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoException;
import dev.undo.core.engine.WebhookService;
import dev.undo.core.json.Json;
import dev.undo.core.ledger.Ledger;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ItemKind;
import dev.undo.core.model.ItemStatus;
import dev.undo.core.model.PlanScope;
import dev.undo.core.model.PlanStatus;
import dev.undo.core.model.Posting;
import dev.undo.core.model.RevertItem;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.core.port.Bug;
import dev.undo.core.port.ConstraintViolationException;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.TxContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Revert preview, refresh, execute, cancel and the worker step (Section 15, FR-REV-1..9).
 *
 * <p>Transactions: preview = freeze TX, provider reads outside any TX, insert TX. Execute = one TX
 * with no provider calls. Each worker step claims the plan lease (fence + 1) in one TX, works
 * outside it, and writes every item change in a TX that first checks the plan fence, so a worker
 * that lost its lease changes nothing. Compensations and remediations are normal actions created
 * through {@link ProposalService} with call keys {@code revert:<plan>:<order_no>} and {@code
 * remediate:<plan>:<order_no>}, so a crash between creating the action and linking it to the item
 * (crash point REVERT_AFTER_ITEM_CREATE) finds the same action again.
 */
public final class RevertService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(RevertService.class);

  private final Ports p;
  private final RunService runs;
  private final ProposalService proposals;
  private final WebhookService webhooks;
  private final EngineConfig cfg;

  public RevertService(
      Ports p,
      RunService runs,
      ProposalService proposals,
      WebhookService webhooks,
      EngineConfig cfg) {
    this.p = p;
    this.runs = runs;
    this.proposals = proposals;
    this.webhooks = webhooks;
    this.cfg = cfg;
  }

  // ── preview (15.1) ──────────────────────────────────────────────────

  public RevertPlan preview(
      long runNumber, PlanScope scope, List<Long> actionNumbers, UUID userId, String actor) {
    // Step 1: freeze / freeze side effects / existing plan.
    Object first =
        p.store()
            .tx(
                tx -> {
                  Run r =
                      tx.runs()
                          .byNumber(runNumber)
                          .orElseThrow(
                              () ->
                                  new UndoException(
                                      UndoException.Code.RUN_NOT_FOUND, "No run #" + runNumber));
                  r = tx.runs().lock(r.id);
                  if (r.status == RunStatus.REVERTING) {
                    throw new UndoException(
                        UndoException.Code.REVERT_IN_PROGRESS, r.display() + " is being reverted.");
                  }
                  if (scope == PlanScope.RUN
                      && (r.status == RunStatus.REVERTED
                          || r.status == RunStatus.PARTIALLY_REVERTED)) {
                    throw new UndoException(
                        UndoException.Code.ALREADY_REVERTED, r.display() + " is " + r.status + ".");
                  }
                  Optional<RevertPlan> open = tx.reverts().openForRun(r.id);
                  if (open.isPresent()) {
                    RevertPlan o = tx.reverts().lockPlan(open.get().id);
                    if (o.status == PlanStatus.PREVIEW && expired(o)) {
                      cancelPlan(tx, o, "expired");
                    } else {
                      return o;
                    }
                  }
                  if (r.status == RunStatus.ACTIVE) {
                    runs.freeze(tx, r.id, actor);
                  } else if (r.status == RunStatus.COMPLETED || r.status == RunStatus.FAILED) {
                    runs.freezeSideEffects(tx, r, actor);
                  }
                  return r;
                });
    if (first instanceof RevertPlan existing) {
      return existing;
    }
    Run run = (Run) first;
    // Step 2: targets, newest first.
    List<Action> targets = p.store().tx(tx -> targets(tx, run, scope, actionNumbers));
    // Step 3: classify each target (provider reads outside any TX).
    List<RevertItem> items = new ArrayList<>();
    int order = 1;
    for (Action t : targets) {
      RevertItem it = new RevertItem();
      it.id = p.ids().next();
      it.orderNo = order++;
      it.targetActionId = t.id;
      it.status = ItemStatus.PLANNED;
      classify(it, t);
      items.add(it);
    }
    // Step 4: insert plan + items.
    Instant now = p.clock().now();
    RevertPlan plan = new RevertPlan();
    plan.id = p.ids().next();
    plan.runId = run.id;
    plan.scope = scope;
    plan.requestedBy = userId;
    plan.status = PlanStatus.PREVIEW;
    plan.summary = previewSummary(items, now);
    plan.createdAt = now;
    try {
      return p.store()
          .tx(
              tx -> {
                RevertPlan saved = tx.reverts().insertPlan(plan);
                for (RevertItem it : items) {
                  it.planId = saved.id;
                  tx.reverts().insertItem(it);
                }
                EventLog.append(tx, now, run.id, null, "revert.previewed", planData(tx, saved));
                return saved;
              });
    } catch (ConstraintViolationException e) {
      if (!e.constraint().equals("revert_one_open_per_run")) {
        throw e;
      }
      // A concurrent preview won; return its plan (idempotent preview).
      return p.store().tx(tx -> tx.reverts().openForRun(run.id)).orElseThrow(() -> e);
    }
  }

  private List<Action> targets(TxContext tx, Run run, PlanScope scope, List<Long> actionNumbers) {
    List<Action> out = new ArrayList<>();
    if (scope == PlanScope.RUN) {
      for (Action a : tx.actions().byRun(run.id)) {
        if (a.kind == ActionKind.PRIMARY && a.effect == Effect.WRITE) {
          out.add(a);
        }
      }
    } else {
      if (actionNumbers == null || actionNumbers.isEmpty()) {
        throw new UndoException(
            UndoException.Code.INVALID_REQUEST, "Scope ACTIONS needs action_numbers.");
      }
      for (long n : new java.util.TreeSet<>(actionNumbers)) {
        Action a =
            tx.actions()
                .byNumber(n)
                .filter(
                    x ->
                        x.runId.equals(run.id)
                            && x.kind == ActionKind.PRIMARY
                            && x.effect == Effect.WRITE)
                .orElseThrow(
                    () ->
                        new UndoException(
                            UndoException.Code.INVALID_REQUEST,
                            "#A-"
                                + String.format("%04d", n)
                                + " is not a write action of "
                                + run.display()
                                + " (FR-REV-9)."));
        out.add(a);
      }
    }
    out.sort((x, y) -> Long.compare(y.number, x.number));
    return out;
  }

  /** Step 3 of 15.1 for one target; sets kind, money and note on the item. */
  private void classify(RevertItem it, Action target) {
    it.moneyRecoveredMinor = 0;
    it.moneyNotRecoveredMinor = 0;
    switch (target.status) {
      case DENIED, REJECTED, EXPIRED, FAILED, CANCELLED -> {
        it.kind = ItemKind.NO_EFFECT;
        it.note = note("The action never took effect (" + target.status + ").", null);
        return;
      }
      case PROPOSED, AWAITING_APPROVAL, APPROVED, EXECUTING, UNKNOWN, RETRY_WAIT -> {
        it.kind = ItemKind.BLOCKED_IN_FLIGHT;
        it.note = note("Still in flight (" + target.status + ").", null);
        return;
      }
      case NEEDS_REVIEW -> {
        it.kind = ItemKind.BLOCKED_NEEDS_REVIEW;
        it.note = note("A person must first resolve whether this happened.", null);
        return;
      }
      case SUCCEEDED -> {}
    }
    if (target.compensatedByActionId != null) {
      // What the earlier compensation did not recover (a partly spent credit) still counts.
      Action c = p.store().tx(tx -> tx.actions().get(target.compensatedByActionId)).orElseThrow();
      it.kind = ItemKind.ALREADY_REVERTED;
      it.moneyNotRecoveredMinor =
          Math.max(0, target.moneyOutMinor - Math.min(target.moneyOutMinor, recoveredBy(c)));
      it.note =
          note(
              "Already reverted by "
                  + c.display()
                  + (it.moneyNotRecoveredMinor > 0
                      ? " (" + Money.format(it.moneyNotRecoveredMinor) + " was not recoverable)"
                      : "")
                  + ".",
              null);
      return;
    }
    Action current;
    JsonNode state;
    try {
      Connector c = p.registry().connector(target.connector);
      state = c.currentState(ActionView.of(target));
      current = syncProviderStatus(target, state);
    } catch (ConnectorException | RuntimeException e) {
      log.warn("revert preview: state of {} unavailable: {}", target.display(), e.toString());
      it.kind = ItemKind.BLOCKED_IN_FLIGHT;
      it.note = note("Provider unreachable; refresh to try again.", null);
      return;
    }
    ReversalPlan plan =
        p.registry()
            .connector(current.connector)
            .reversal(ActionView.of(current), state, p.clock().now());
    switch (plan) {
      case ReversalPlan.Compensate c -> {
        it.kind = ItemKind.COMPENSATE;
        it.moneyRecoveredMinor = Math.min(c.moneyRecoveredMinor(), current.moneyOutMinor);
        it.moneyNotRecoveredMinor = Math.max(0, current.moneyOutMinor - it.moneyRecoveredMinor);
        it.note = note(c.description(), null);
      }
      case ReversalPlan.Irreversible ir -> {
        it.kind = ItemKind.IRREVERSIBLE;
        it.moneyNotRecoveredMinor = ir.moneyNotRecoveredMinor();
        it.note = note(ir.reason(), ir.remediation().orElse(null));
      }
      case ReversalPlan.Conflict cf -> {
        it.kind = ItemKind.CONFLICT;
        it.note = note(cf.reason(), null);
      }
      case ReversalPlan.NoEffect ne -> {
        it.kind = ItemKind.NO_EFFECT;
        it.note = note(ne.reason(), null);
      }
    }
  }

  /**
   * If the provider's object shows another status than the action records, apply it first (posts
   * the missing journal entry). Only when the state describes the action's own provider object
   * (same id as provider_ref) and the action has a real provider status (ADR-017).
   */
  private Action syncProviderStatus(Action target, JsonNode state) {
    String newStatus = providerStatusIn(target, state);
    if (newStatus == null) {
      return target;
    }
    return p.store()
        .tx(
            tx -> {
              Action locked = tx.actions().lock(target.id);
              webhooks.applyStatusChange(tx, locked, newStatus, "recon", null);
              return locked;
            });
  }

  static String providerStatusIn(Action a, JsonNode state) {
    if (a.providerRef == null || a.providerStatus == null || a.providerStatus.equals("ok")) {
      return null;
    }
    if (state == null || !a.providerRef.equals(state.path("id").asText(null))) {
      return null;
    }
    JsonNode s = state.get("status");
    if (s == null || !s.isTextual() || s.asText().equals(a.providerStatus)) {
      return null;
    }
    return s.asText();
  }

  // ── refresh / cancel / execute ──────────────────────────────────────

  /** Re-runs step 3 of 15.1 for BLOCKED items of a PREVIEW plan. */
  public RevertPlan refresh(long planNumber, String actor) {
    RevertPlan plan = planByNumber(planNumber);
    if (plan.status != PlanStatus.PREVIEW) {
      return plan;
    }
    List<RevertItem> items = p.store().tx(tx -> tx.reverts().items(plan.id));
    List<RevertItem> changed = new ArrayList<>();
    for (RevertItem it : items) {
      if (it.kind == ItemKind.BLOCKED_IN_FLIGHT || it.kind == ItemKind.BLOCKED_NEEDS_REVIEW) {
        Action t = p.store().tx(tx -> tx.actions().get(it.targetActionId)).orElseThrow();
        RevertItem copy = it.copy();
        classify(copy, t);
        changed.add(copy);
      }
    }
    Instant now = p.clock().now();
    return p.store()
        .tx(
            tx -> {
              RevertPlan locked = tx.reverts().lockPlan(plan.id);
              if (locked.status != PlanStatus.PREVIEW) {
                return locked;
              }
              for (RevertItem c : changed) {
                tx.reverts().updateItem(c);
              }
              locked.summary = previewSummary(tx.reverts().items(locked.id), now);
              tx.reverts().updatePlan(locked);
              return locked;
            });
  }

  public RevertPlan cancel(long planNumber, String actor) {
    RevertPlan plan = planByNumber(planNumber);
    return p.store()
        .tx(
            tx -> {
              RevertPlan locked = tx.reverts().lockPlan(plan.id);
              if (locked.status != PlanStatus.PREVIEW) {
                throw new UndoException(
                    locked.status == PlanStatus.EXECUTING
                        ? UndoException.Code.REVERT_IN_PROGRESS
                        : UndoException.Code.INVALID_REQUEST,
                    "Only a preview can be cancelled; this plan is " + locked.status + ".");
              }
              cancelPlan(tx, locked, "cancelled by " + actor);
              return locked;
            });
  }

  /** 15.2 step 1: one TX, no provider calls. The worker loop does the work. */
  public RevertPlan execute(long planNumber, long confirmRunNumber, String actor) {
    RevertPlan plan = planByNumber(planNumber);
    RevertPlan started =
        p.store()
            .tx(
                tx -> {
                  RevertPlan locked = tx.reverts().lockPlan(plan.id);
                  Run run = tx.runs().lock(locked.runId);
                  if (locked.status != PlanStatus.PREVIEW) {
                    throw new UndoException(
                        locked.status == PlanStatus.EXECUTING
                            ? UndoException.Code.REVERT_IN_PROGRESS
                            : UndoException.Code.INVALID_REQUEST,
                        "This plan is " + locked.status + ".");
                  }
                  if (expired(locked)) {
                    throw new UndoException(
                        UndoException.Code.REVERT_PREVIEW_EXPIRED,
                        "The preview is older than "
                            + cfg.previewTtl().toMinutes()
                            + " min. Create a new preview.");
                  }
                  if (confirmRunNumber != run.number) {
                    throw new UndoException(
                        UndoException.Code.CONFIRMATION_MISMATCH,
                        "Type the run number " + run.number + " to confirm.");
                  }
                  for (RevertItem it : tx.reverts().items(locked.id)) {
                    if (it.kind == ItemKind.BLOCKED_IN_FLIGHT
                        || it.kind == ItemKind.BLOCKED_NEEDS_REVIEW) {
                      throw new UndoException(
                          UndoException.Code.REVERT_BLOCKED,
                          "Item "
                              + it.orderNo
                              + " is blocked ("
                              + it.kind
                              + "). Refresh the preview when it is resolved.");
                    }
                  }
                  Instant now = p.clock().now();
                  locked.status = PlanStatus.EXECUTING;
                  locked.startedAt = now;
                  tx.reverts().updatePlan(locked);
                  if (locked.scope == PlanScope.RUN) {
                    moveRun(tx, run, RunStatus.REVERTING);
                    EventLog.append(tx, now, run.id, null, "run.reverting", runData(run, locked));
                  }
                  EventLog.append(tx, now, run.id, null, "revert.started", planData(tx, locked));
                  return locked;
                });
    return started;
  }

  /** Cancels PREVIEW plans older than the preview TTL (worker). Returns how many. */
  public int expirePreviews(int limit) {
    Instant cutoff = p.clock().now().minus(cfg.previewTtl());
    return p.store()
        .tx(
            tx -> {
              int n = 0;
              for (RevertPlan pl : tx.reverts().lockExpiredPreviews(cutoff, limit)) {
                cancelPlan(tx, pl, "expired");
                n++;
              }
              return n;
            });
  }

  // ── worker step (15.2 step 2–4) ─────────────────────────────────────

  /** One step for each executable plan whose lease is free. Returns the number of steps done. */
  public int stepAll(String node, int limit) {
    try (Metrics.Span sp = p.metrics().span("undo.revert.step", "node", node)) {
      return stepAll0(node, limit);
    }
  }

  private int stepAll0(String node, int limit) {
    Instant now = p.clock().now();
    List<long[]> claimed = new ArrayList<>();
    List<UUID> ids = new ArrayList<>();
    p.store()
        .tx(
            tx -> {
              for (RevertPlan pl : tx.reverts().lockExecutable(now, limit)) {
                pl.leaseOwner = node;
                pl.leaseExpiresAt = now.plus(cfg.lease());
                pl.leaseFence = pl.leaseFence + 1;
                tx.reverts().updatePlan(pl);
                ids.add(pl.id);
                claimed.add(new long[] {pl.leaseFence});
              }
              return null;
            });
    int steps = 0;
    for (int i = 0; i < ids.size(); i++) {
      UUID id = ids.get(i);
      long fence = claimed.get(i)[0];
      try {
        step(id, fence, node);
        steps++;
      } finally {
        release(id, fence);
      }
    }
    return steps;
  }

  private void release(UUID planId, long fence) {
    p.store()
        .tx(
            tx -> {
              RevertPlan pl = tx.reverts().lockPlan(planId);
              if (pl.leaseFence == fence) {
                pl.leaseOwner = null;
                pl.leaseExpiresAt = null;
                tx.reverts().updatePlan(pl);
              }
              return null;
            });
  }

  /** Runs body in a TX only if the plan is still EXECUTING under this fence. */
  private <T> Optional<T> fenced(
      UUID planId, long fence, BiFunction<TxContext, RevertPlan, T> body) {
    return p.store()
        .tx(
            tx -> {
              RevertPlan pl = tx.reverts().lockPlan(planId);
              if (pl.leaseFence != fence || pl.status != PlanStatus.EXECUTING) {
                return Optional.<T>empty();
              }
              return Optional.ofNullable(body.apply(tx, pl));
            });
  }

  void step(UUID planId, long fence, String node) {
    RevertPlan plan = p.store().tx(tx -> tx.reverts().plan(planId)).orElseThrow();
    if (plan.status != PlanStatus.EXECUTING || plan.leaseFence != fence) {
      return;
    }
    RevertItem item = nextItem(p.store().tx(tx -> tx.reverts().items(planId)));
    if (item == null) {
      finish(planId, fence);
      return;
    }
    Action target = p.store().tx(tx -> tx.actions().get(item.targetActionId)).orElseThrow();
    if (item.kind != ItemKind.COMPENSATE) {
      if (item.kind == ItemKind.IRREVERSIBLE
          && remediationOf(item) != null
          && item.remediationActionId == null) {
        UUID rem = remediate(plan, item, remediationOf(item), node);
        fenced(
            planId,
            fence,
            (tx, pl) -> {
              RevertItem cur = itemIn(tx, planId, item.id);
              cur.remediationActionId = rem;
              tx.reverts().updateItem(cur);
              return cur;
            });
        return;
      }
      fenced(
          planId,
          fence,
          (tx, pl) -> {
            RevertItem cur = itemIn(tx, planId, item.id);
            cur.status = ItemStatus.SKIPPED;
            cur.moneyRecoveredMinor = 0;
            tx.reverts().updateItem(cur);
            itemEvent(tx, pl, cur, target);
            return cur;
          });
      return;
    }
    if (item.compensationActionId == null) {
      startCompensation(plan, fence, item, target, node);
    } else {
      checkCompensation(plan, fence, item, target, node);
    }
  }

  /** Items are strictly sequential (15.2 step 3): the first one not yet finished. */
  private static RevertItem nextItem(List<RevertItem> items) {
    for (RevertItem it : items) {
      if (it.status == ItemStatus.PLANNED || it.status == ItemStatus.IN_PROGRESS) {
        return it;
      }
    }
    return null;
  }

  private void startCompensation(
      RevertPlan plan, long fence, RevertItem item, Action target, String node) {
    Optional<Action> existing = p.store().tx(tx -> tx.actions().liveCompensationOf(target.id));
    if (existing.isPresent()) {
      link(plan, fence, item, existing.get().id);
      return;
    }
    JsonNode state;
    Action current;
    Connector c = p.registry().connector(target.connector);
    try {
      state = c.currentState(ActionView.of(target));
      current = syncProviderStatus(target, state);
    } catch (ConnectorException | RuntimeException e) {
      log.warn("revert step: state of {} unavailable: {}", target.display(), e.toString());
      return; // try again on the next step
    }
    ReversalPlan rp = c.reversal(ActionView.of(current), state, p.clock().now());
    if (!(rp instanceof ReversalPlan.Compensate)) {
      // A step of an earlier lease holder may have created the compensation while we read, and the
      // state we read is its effect (ADR-018): its insert committed before our read returned.
      Optional<Action> late = p.store().tx(tx -> tx.actions().liveCompensationOf(target.id));
      if (late.isPresent()) {
        link(plan, fence, item, late.get().id);
        return;
      }
    }
    switch (rp) {
      case ReversalPlan.Compensate comp -> {
        ProposalService.Request req =
            new ProposalService.Request(
                Caller.system(node, "revert"),
                plan.runId,
                null,
                ActionKind.COMPENSATION,
                target.id,
                plan.id,
                comp.tool(),
                comp.args(),
                "revert:" + plan.id + ":" + item.orderNo,
                null,
                0);
        ProposalService.Outcome out;
        try {
          out = proposals.propose(req);
        } catch (ConstraintViolationException e) {
          // Another worker created the live compensation of this target; link it next step.
          log.info("revert step: compensation of {} already exists", target.display());
          return;
        }
        if (!(out instanceof ProposalService.Outcome.Recorded rec)) {
          log.error("revert step: compensation of {} not recorded: {}", target.display(), out);
          endItem(
              plan, fence, item, target, ItemStatus.FAILED, "Compensation could not be created.");
          return;
        }
        p.crash().hit(CrashPoint.REVERT_AFTER_ITEM_CREATE);
        link(plan, fence, item, rec.action().id);
      }
      case ReversalPlan.Irreversible ir -> {
        UUID rem =
            ir.remediation().isPresent()
                ? remediate(plan, item, ir.remediation().get(), node)
                : null;
        endItem(
            plan,
            fence,
            item,
            target,
            ItemStatus.BECAME_IRREVERSIBLE,
            "Became irreversible: " + ir.reason(),
            rem,
            ir.remediation().orElse(null),
            ir.moneyNotRecoveredMinor());
      }
      case ReversalPlan.Conflict cf ->
          endItem(plan, fence, item, target, ItemStatus.CONFLICT, cf.reason());
      case ReversalPlan.NoEffect ne -> alreadyGone(plan, fence, item, target, ne.reason());
    }
  }

  private void link(RevertPlan plan, long fence, RevertItem item, UUID compensationId) {
    fenced(
        plan.id,
        fence,
        (tx, pl) -> {
          RevertItem cur = itemIn(tx, plan.id, item.id);
          cur.compensationActionId = compensationId;
          cur.status = ItemStatus.IN_PROGRESS;
          tx.reverts().updateItem(cur);
          itemEvent(tx, pl, cur, tx.actions().get(cur.targetActionId).orElseThrow());
          return cur;
        });
  }

  private void checkCompensation(
      RevertPlan plan, long fence, RevertItem item, Action target, String node) {
    Action c = p.store().tx(tx -> tx.actions().get(item.compensationActionId)).orElseThrow();
    if (p.bugs().on(Bug.REVERT_TRUSTS_PREVIEW) && c.status != ActionStatus.SUCCEEDED) {
      // Bug flag: trust the preview's numbers and never look at the compensation's outcome.
      fenced(
          plan.id,
          fence,
          (tx, pl) -> {
            RevertItem cur = itemIn(tx, plan.id, item.id);
            cur.status = ItemStatus.DONE;
            tx.reverts().updateItem(cur);
            Action t = tx.actions().lock(target.id);
            t.compensatedByActionId = c.id;
            t.updatedAt = p.clock().now();
            tx.actions().update(t, t.status, t.leaseFence);
            itemEvent(tx, pl, cur, t);
            return cur;
          });
      return;
    }
    switch (c.status) {
      case SUCCEEDED -> {
        long recovered = Math.min(target.moneyOutMinor, recoveredBy(c));
        fenced(
            plan.id,
            fence,
            (tx, pl) -> {
              RevertItem cur = itemIn(tx, plan.id, item.id);
              Action t = tx.actions().lock(target.id);
              cur.status = ItemStatus.DONE;
              cur.moneyRecoveredMinor = recovered;
              cur.moneyNotRecoveredMinor = Math.max(0, t.moneyOutMinor - recovered);
              tx.reverts().updateItem(cur);
              Instant now = p.clock().now();
              t.compensatedByActionId = c.id;
              t.updatedAt = now;
              if (!tx.actions().update(t, t.status, t.leaseFence)) {
                throw new IllegalStateException("locked target changed");
              }
              Connector conn = p.registry().connector(t.connector);
              Ledger.write(
                  tx,
                  p.ids(),
                  p.bugs(),
                  now,
                  t.runId,
                  t.id,
                  conn.journalOnCompensated(ActionView.of(t), ActionView.of(c)),
                  "compensation",
                  c.display());
              itemEvent(tx, pl, cur, t);
              return cur;
            });
      }
      case FAILED -> {
        String code = c.error == null ? "" : c.error.path("code").asText("");
        boolean conflict = code.equals("conflict") || code.endsWith("_conflict");
        if (!conflict) {
          // The provider may have moved on (settled, failed, cancelled): decide from its current
          // state (ADR-017 #3, ADR-019 #1).
          ReversalPlan now = reread(target);
          switch (now) {
            case null -> {
              return; // provider unreachable: look again on the next step
            }
            case ReversalPlan.NoEffect ne -> alreadyGone(plan, fence, item, target, ne.reason());
            case ReversalPlan.Irreversible ir -> {
              UUID rem =
                  ir.remediation().isPresent()
                      ? remediate(plan, item, ir.remediation().get(), node)
                      : null;
              endItem(
                  plan,
                  fence,
                  item,
                  target,
                  ItemStatus.BECAME_IRREVERSIBLE,
                  "Became irreversible: " + ir.reason(),
                  rem,
                  ir.remediation().orElse(null),
                  ir.moneyNotRecoveredMinor());
            }
            default ->
                endItem(
                    plan,
                    fence,
                    item,
                    target,
                    ItemStatus.FAILED,
                    "Compensation failed: "
                        + (c.error == null
                            ? (c.statusReason == null ? "unknown error" : c.statusReason)
                            : c.error.path("message").asText(code)));
          }
        } else {
          endItem(
              plan,
              fence,
              item,
              target,
              ItemStatus.CONFLICT,
              c.error.path("message").asText("Changed by someone else; left as is."));
        }
      }
      case DENIED, REJECTED, EXPIRED, CANCELLED ->
          endItem(
              plan,
              fence,
              item,
              target,
              ItemStatus.FAILED,
              "Policy denied the compensation (" + c.status + ").");
      default -> {
        // EXECUTING, UNKNOWN, RETRY_WAIT, APPROVED: the executor and resolver own it; wait.
        // NEEDS_REVIEW: wait for a person; the plan stays EXECUTING.
      }
    }
  }

  /** Money a successful compensation recovered, from its provider result. */
  static long recoveredBy(Action compensation) {
    JsonNode r = compensation.result;
    if (r == null) {
      return 0;
    }
    if (r.path("revoked_minor").isIntegralNumber()) {
      return r.get("revoked_minor").asLong();
    }
    if (r.path("amount_minor").isIntegralNumber()) {
      return r.get("amount_minor").asLong();
    }
    return 0;
  }

  /** The reversal plan from the provider's current state (status change applied); null on error. */
  private ReversalPlan reread(Action target) {
    try {
      Connector c = p.registry().connector(target.connector);
      JsonNode state = c.currentState(ActionView.of(target));
      Action current = syncProviderStatus(target, state);
      return c.reversal(ActionView.of(current), state, p.clock().now());
    } catch (ConnectorException | RuntimeException e) {
      log.warn("revert step: state of {} unavailable: {}", target.display(), e.toString());
      return null;
    }
  }

  private void alreadyGone(
      RevertPlan plan, long fence, RevertItem item, Action target, String why) {
    fenced(
        plan.id,
        fence,
        (tx, pl) -> {
          RevertItem cur = itemIn(tx, plan.id, item.id);
          cur.status = ItemStatus.DONE;
          cur.moneyRecoveredMinor = 0;
          cur.moneyNotRecoveredMinor = 0;
          cur.note = note("Already gone: " + why, null);
          tx.reverts().updateItem(cur);
          itemEvent(tx, pl, cur, target);
          return cur;
        });
  }

  private UUID remediate(RevertPlan plan, RevertItem item, Remediation rem, String node) {
    ProposalService.Request req =
        new ProposalService.Request(
            Caller.system(node, "revert"),
            plan.runId,
            null,
            ActionKind.REMEDIATION,
            null,
            plan.id,
            rem.tool(),
            rem.args(),
            "remediate:" + plan.id + ":" + item.orderNo,
            null,
            0);
    ProposalService.Outcome out = proposals.propose(req);
    if (out instanceof ProposalService.Outcome.Recorded rec) {
      return rec.action().id;
    }
    log.error("revert step: remediation for item {} not recorded: {}", item.orderNo, out);
    return null;
  }

  private void endItem(
      RevertPlan plan, long fence, RevertItem item, Action target, ItemStatus status, String text) {
    endItem(plan, fence, item, target, status, text, null, null, target.moneyOutMinor);
  }

  /** Every non-DONE end state: recovered 0; not recovered = the target's money out unless known. */
  private void endItem(
      RevertPlan plan,
      long fence,
      RevertItem item,
      Action target,
      ItemStatus status,
      String text,
      UUID remediationId,
      Remediation remediation,
      long notRecovered) {
    fenced(
        plan.id,
        fence,
        (tx, pl) -> {
          RevertItem cur = itemIn(tx, plan.id, item.id);
          cur.status = status;
          cur.moneyRecoveredMinor = 0;
          cur.moneyNotRecoveredMinor = notRecovered;
          cur.note = note(text, remediation);
          if (remediationId != null) {
            cur.remediationActionId = remediationId;
          }
          tx.reverts().updateItem(cur);
          itemEvent(tx, pl, cur, target);
          return cur;
        });
  }

  /** 15.2 step 4. */
  private void finish(UUID planId, long fence) {
    fenced(
        planId,
        fence,
        (tx, pl) -> {
          List<RevertItem> items = tx.reverts().items(planId);
          boolean clean = true;
          for (RevertItem it : items) {
            boolean bad =
                switch (it.status) {
                  case FAILED, CONFLICT, BECAME_IRREVERSIBLE -> true;
                  case SKIPPED ->
                      it.kind == ItemKind.IRREVERSIBLE
                          || it.kind == ItemKind.CONFLICT
                          || it.moneyNotRecoveredMinor != 0;
                  case DONE -> it.moneyNotRecoveredMinor != 0;
                  default -> false;
                };
            if (bad) {
              clean = false;
            }
          }
          Instant now = p.clock().now();
          PlanStatus end = clean ? PlanStatus.REVERTED : PlanStatus.PARTIALLY_REVERTED;
          pl.status = end;
          pl.completedAt = now;
          pl.leaseOwner = null;
          pl.leaseExpiresAt = null;
          ObjectNode s = pl.summary == null ? Json.obj() : (ObjectNode) pl.summary.deepCopy();
          List<Action> targets = new ArrayList<>();
          for (RevertItem it : items) {
            targets.add(tx.actions().get(it.targetActionId).orElseThrow());
          }
          fillFinal(tx, pl, items, targets, s);
          pl.summary = s;
          tx.reverts().updatePlan(pl);
          Run run = tx.runs().lock(pl.runId);
          if (pl.scope == PlanScope.RUN && run.status == RunStatus.REVERTING) {
            moveRun(
                tx,
                run,
                end == PlanStatus.REVERTED ? RunStatus.REVERTED : RunStatus.PARTIALLY_REVERTED);
            EventLog.append(
                tx,
                now,
                run.id,
                null,
                end == PlanStatus.REVERTED ? "run.reverted" : "run.partially_reverted",
                runData(run, pl));
          }
          ObjectNode d = planData(tx, pl);
          d.put("report_text", s.path("report_text").asText());
          EventLog.append(tx, now, pl.runId, null, "revert.completed", d);
          for (RevertItem it : items) {
            p.metrics()
                .count(
                    "undo_revert_items_total", "kind", it.kind.name(), "status", it.status.name());
          }
          return pl;
        });
  }

  private void fillFinal(
      TxContext tx, RevertPlan pl, List<RevertItem> items, List<Action> targets, ObjectNode s) {
    Map<ItemStatus, Integer> byStatus = new EnumMap<>(ItemStatus.class);
    long recovered = 0;
    long notRecovered = 0;
    for (RevertItem it : items) {
      byStatus.merge(it.status, 1, Integer::sum);
      recovered += it.moneyRecoveredMinor;
      notRecovered += it.moneyNotRecoveredMinor;
    }
    s.put("done", byStatus.getOrDefault(ItemStatus.DONE, 0));
    s.put(
        "irreversible",
        byStatus.getOrDefault(ItemStatus.BECAME_IRREVERSIBLE, 0)
            + (int) items.stream().filter(i -> i.kind == ItemKind.IRREVERSIBLE).count());
    s.put(
        "conflict",
        byStatus.getOrDefault(ItemStatus.CONFLICT, 0)
            + (int)
                items.stream()
                    .filter(i -> i.kind == ItemKind.CONFLICT && i.status == ItemStatus.SKIPPED)
                    .count());
    s.put("failed", byStatus.getOrDefault(ItemStatus.FAILED, 0));
    s.put("money_recovered_minor", recovered);
    s.put("money_not_recovered_minor", notRecovered);
    s.put("net_exposure_after_minor", exposure(tx, pl, targets));
    s.put("report_text", report(items, targets));
  }

  /** Net expense:* postings of the run (scope RUN) or of the plan's targets (scope ACTIONS). */
  private static long exposure(TxContext tx, RevertPlan pl, List<Action> targets) {
    List<Posting> postings;
    if (pl.scope == PlanScope.RUN) {
      postings = tx.ledger().postingsByRun(pl.runId);
    } else {
      postings = new ArrayList<>();
      for (Action t : targets) {
        postings.addAll(tx.ledger().postingsByAction(t.id));
      }
    }
    return Ledger.expenseExposure(postings);
  }

  /**
   * The deterministic report (no LLM). Counts are grouped by the target's tool, in tool-name order.
   * Items without an effect (NO_EFFECT, ALREADY_REVERTED) are not counted.
   */
  static String report(List<RevertItem> items, List<Action> targets) {
    int withEffect = 0;
    int done = 0;
    Map<String, long[]> undone = new TreeMap<>();
    Map<String, long[]> irreversible = new TreeMap<>();
    Map<String, long[]> conflicts = new TreeMap<>();
    Map<String, long[]> failed = new TreeMap<>();
    int remediations = 0;
    for (int i = 0; i < items.size(); i++) {
      RevertItem it = items.get(i);
      String tool = targets.get(i).tool;
      if (it.kind == ItemKind.NO_EFFECT
          || it.kind == ItemKind.ALREADY_REVERTED
          || (it.status == ItemStatus.DONE && targets.get(i).compensatedByActionId == null)) {
        continue;
      }
      withEffect++;
      if (it.remediationActionId != null) {
        remediations++;
      }
      Map<String, long[]> bucket;
      long money;
      if (it.status == ItemStatus.DONE) {
        done++;
        bucket = undone;
        money = it.moneyRecoveredMinor;
      } else if (it.status == ItemStatus.CONFLICT || it.kind == ItemKind.CONFLICT) {
        bucket = conflicts;
        money = it.moneyNotRecoveredMinor;
      } else if (it.status == ItemStatus.FAILED) {
        bucket = failed;
        money = it.moneyNotRecoveredMinor;
      } else {
        bucket = irreversible;
        money = it.moneyNotRecoveredMinor;
      }
      long[] c = bucket.computeIfAbsent(tool, k -> new long[2]);
      c[0]++;
      c[1] += money;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Reverted ").append(done).append(" of ").append(withEffect).append(" actions.");
    line(sb, "Undone", undone, "recovered");
    line(sb, "Cannot be undone", irreversible, "not recoverable");
    line(sb, "Changed by someone else and left as is", conflicts, "not recovered");
    line(sb, "Failed", failed, "not recovered");
    if (remediations > 0) {
      sb.append(' ')
          .append(remediations)
          .append(remediations == 1 ? " remediation is" : " remediations are")
          .append(" waiting for approval.");
    }
    return sb.toString();
  }

  private static void line(
      StringBuilder sb, String label, Map<String, long[]> m, String moneyWord) {
    if (m.isEmpty()) {
      return;
    }
    sb.append(' ').append(label).append(": ");
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, long[]> e : m.entrySet()) {
      String part = e.getValue()[0] + " × " + e.getKey();
      if (e.getValue()[1] != 0) {
        part += " (" + Money.format(e.getValue()[1]) + " " + moneyWord + ")";
      }
      parts.add(part);
    }
    sb.append(String.join(", ", parts)).append('.');
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private boolean expired(RevertPlan plan) {
    return !p.clock().now().isBefore(plan.createdAt.plus(cfg.previewTtl()));
  }

  private void cancelPlan(TxContext tx, RevertPlan plan, String reason) {
    plan.status = PlanStatus.CANCELLED;
    plan.completedAt = p.clock().now();
    tx.reverts().updatePlan(plan);
    ObjectNode d = planData(tx, plan);
    d.put("reason", reason);
    EventLog.append(tx, p.clock().now(), plan.runId, null, "revert.cancelled", d);
  }

  private void moveRun(TxContext tx, Run run, RunStatus to) {
    if (!run.status.canMoveTo(to)) {
      throw new IllegalStateException(run.display() + ": illegal " + run.status + " -> " + to);
    }
    run.status = to;
    if (to == RunStatus.REVERTED || to == RunStatus.PARTIALLY_REVERTED) {
      run.completedAt = run.completedAt == null ? p.clock().now() : run.completedAt;
    }
    tx.runs().update(run);
  }

  private RevertPlan planByNumber(long number) {
    return p.store()
        .tx(tx -> tx.reverts().planByNumber(number))
        .orElseThrow(
            () -> new UndoException(UndoException.Code.PLAN_NOT_FOUND, "No revert plan " + number));
  }

  private static RevertItem itemIn(TxContext tx, UUID planId, UUID itemId) {
    for (RevertItem it : tx.reverts().items(planId)) {
      if (it.id.equals(itemId)) {
        return it;
      }
    }
    throw new IllegalStateException("no item " + itemId);
  }

  /** Item note: JSON text {text, remediation?{tool,args,description}}. */
  static String note(String text, Remediation remediation) {
    ObjectNode o = Json.obj();
    o.put("text", text);
    if (remediation != null) {
      ObjectNode r = o.putObject("remediation");
      r.put("tool", remediation.tool());
      r.set("args", remediation.args());
      r.put("description", remediation.description());
    }
    return o.toString();
  }

  static Remediation remediationOf(RevertItem it) {
    if (it.note == null) {
      return null;
    }
    JsonNode r = Json.parse(it.note).get("remediation");
    if (r == null || r.isNull()) {
      return null;
    }
    return new Remediation(r.path("tool").asText(), r.get("args"), r.path("description").asText());
  }

  private static ObjectNode previewSummary(List<RevertItem> items, Instant now) {
    ObjectNode s = Json.obj();
    Map<ItemKind, Integer> counts = new LinkedHashMap<>();
    for (ItemKind k : ItemKind.values()) {
      counts.put(k, 0);
    }
    long recoverable = 0;
    long notRecoverable = 0;
    int blocked = 0;
    int remediations = 0;
    for (RevertItem it : items) {
      counts.merge(it.kind, 1, Integer::sum);
      recoverable += it.moneyRecoveredMinor;
      notRecoverable += it.moneyNotRecoveredMinor;
      if (it.kind == ItemKind.BLOCKED_IN_FLIGHT || it.kind == ItemKind.BLOCKED_NEEDS_REVIEW) {
        blocked++;
      }
      if (it.kind == ItemKind.IRREVERSIBLE && remediationOf(it) != null) {
        remediations++;
      }
    }
    ObjectNode c = s.putObject("counts_by_kind");
    counts.forEach((k, v) -> c.put(k.name(), v));
    s.put("money_recoverable_minor", recoverable);
    s.put("money_not_recoverable_minor", notRecoverable);
    s.put("blocked", blocked);
    s.put("remediations", remediations);
    s.put("generated_at", now.toString());
    return s;
  }

  private static ObjectNode planData(TxContext tx, RevertPlan pl) {
    ObjectNode d = Json.obj();
    d.put("plan_number", pl.number);
    d.put("plan_id", pl.id.toString());
    d.put("scope", pl.scope.name());
    d.put("status", pl.status.name());
    tx.runs().get(pl.runId).ifPresent(r -> d.put("run_number", r.number));
    return d;
  }

  private static ObjectNode runData(Run r, RevertPlan pl) {
    ObjectNode d = Json.obj();
    d.put("run_number", r.number);
    d.put("status", r.status.name());
    d.put("plan_number", pl.number);
    return d;
  }

  private void itemEvent(TxContext tx, RevertPlan pl, RevertItem it, Action target) {
    ObjectNode d = Json.obj();
    d.put("plan_number", pl.number);
    d.put("order_no", it.orderNo);
    d.put("action_number", target.number);
    d.put("action", target.display());
    d.put("tool", target.tool);
    d.put("kind", it.kind.name());
    d.put("status", it.status.name());
    d.put("money_recovered_minor", it.moneyRecoveredMinor);
    d.put("money_not_recovered_minor", it.moneyNotRecoveredMinor);
    EventLog.append(tx, p.clock().now(), pl.runId, target.id, "revert.item_updated", d);
  }
}
