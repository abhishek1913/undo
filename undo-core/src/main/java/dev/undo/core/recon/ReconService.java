package dev.undo.core.recon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.ConnectorException;
import dev.undo.core.connector.EffectPage;
import dev.undo.core.connector.LookupOutcome;
import dev.undo.core.connector.ProviderEffect;
import dev.undo.core.connector.ToolCall;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.engine.EventLog;
import dev.undo.core.engine.ExecutionService;
import dev.undo.core.engine.Metrics;
import dev.undo.core.engine.Ports;
import dev.undo.core.engine.UndoException;
import dev.undo.core.engine.WebhookService;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.FindingStatus;
import dev.undo.core.model.FindingType;
import dev.undo.core.model.ReconFinding;
import dev.undo.core.model.ReconRun;
import dev.undo.core.model.ReconRunStatus;
import dev.undo.core.model.Severity;
import dev.undo.core.model.WebhookEvent;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.Locks;
import dev.undo.core.port.TxContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Reconciliation (Section 16, FR-RECON-1..4): compares the provider's objects with Undo's actions
 * and records typed findings. Auto-heals only STATUS_MISMATCH (through {@link
 * WebhookService#applyStatusChange}) and GHOST_EFFECT of UNKNOWN/NEEDS_REVIEW actions (the normal
 * fenced success write). One recon per connector at a time: job lease {@code recon:<connector>}.
 *
 * <p>Transactions: start TX, provider listing (no TX), crash point RECON_AFTER_FETCH, one TX per
 * checked action (lock, re-check, heal, findings), finish TX. An action that changed after the
 * listing started is skipped; the next run checks it.
 */
public final class ReconService {
  /** Recon settings (Section 26, undo.recon). */
  public record Config(
      Duration interval,
      Duration window,
      Duration overlap,
      Duration settle,
      Duration leaseTtl,
      Duration unmatchedWebhookAfter) {
    public static Config defaults() {
      return new Config(
          Duration.ofMinutes(5),
          Duration.ofHours(24),
          Duration.ofMinutes(10),
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(10));
    }
  }

  private static final Set<ActionStatus> GHOST_HEALABLE =
      EnumSet.of(ActionStatus.UNKNOWN, ActionStatus.NEEDS_REVIEW);
  private static final Set<ActionStatus> NO_EFFECT_EXPECTED =
      EnumSet.of(
          ActionStatus.FAILED,
          ActionStatus.CANCELLED,
          ActionStatus.DENIED,
          ActionStatus.REJECTED,
          ActionStatus.EXPIRED);
  private static final Set<String> MONEY_TYPES = Set.of("refund", "store_credit");
  private static final Set<String> REVERSED_STATUSES = Set.of("cancelled", "revoked");

  private final Ports p;
  private final WebhookService webhooks;
  private final ExecutionService execution;
  private final Locks locks;
  private final Config cfg;

  public ReconService(
      Ports p, WebhookService webhooks, ExecutionService execution, Locks locks, Config cfg) {
    this.p = p;
    this.webhooks = webhooks;
    this.execution = execution;
    this.locks = locks;
    this.cfg = cfg;
  }

  public static String leaseName(String connector) {
    return "recon:" + connector;
  }

  /**
   * The scheduler's round: when the connector is due (no completed run, or the last one finished
   * {@code interval} ago), takes the lease and reconciles the incremental window. Empty when not
   * due or another node holds the lease.
   */
  public Optional<ReconRun> runScheduledIfDue(String connector, String node) {
    Instant now = p.clock().now();
    Optional<ReconRun> last = p.store().tx(tx -> tx.recon().lastCompleted(connector));
    if (last.isPresent()
        && last.get().finishedAt != null
        && last.get().finishedAt.plus(cfg.interval()).isAfter(now)) {
      return Optional.empty();
    }
    Instant end = now.minus(cfg.settle());
    Instant start = last.map(r -> r.windowEnd.minus(cfg.overlap())).orElse(now.minus(cfg.window()));
    return withLease(connector, node, fence -> run(connector, start, end, "schedule"));
  }

  /** On demand over the last 24 h. Throws RECON_RUNNING when another recon holds the lease. */
  public ReconRun runNow(String connector, String triggeredBy, String node) {
    Instant now = p.clock().now();
    return withLease(
            connector, node, fence -> run(connector, now.minus(cfg.window()), now, triggeredBy))
        .orElseThrow(
            () ->
                new UndoException(
                    UndoException.Code.RECON_RUNNING,
                    "A reconciliation for " + connector + " is already running."));
  }

  private interface Body {
    ReconRun run(long fence);
  }

  private Optional<ReconRun> withLease(String connector, String node, Body body) {
    String name = leaseName(connector);
    Optional<Long> fence = locks.tryAcquire(name, node, cfg.leaseTtl());
    if (fence.isEmpty()) {
      return Optional.empty();
    }
    ReconRun r;
    try {
      r = body.run(fence.get());
    } catch (RuntimeException e) {
      locks.release(name, fence.get());
      throw e;
    }
    // A crash (an Error) keeps the lease until it expires, like a dead process.
    locks.release(name, fence.get());
    return Optional.of(r);
  }

  // ── one recon run ─────────────────────────────────────────────────

  private ReconRun run(String connectorId, Instant windowStart, Instant windowEnd, String trigger) {
    try (Metrics.Span sp = p.metrics().span("undo.recon", "connector", connectorId)) {
      return run0(connectorId, windowStart, windowEnd, trigger);
    }
  }

  private ReconRun run0(
      String connectorId, Instant windowStart, Instant windowEnd, String trigger) {
    Instant t0 = p.clock().now();
    Connector connector = p.registry().connector(connectorId);
    ReconRun run =
        p.store()
            .tx(
                tx -> {
                  Instant now = p.clock().now();
                  // We hold the lease, so a RUNNING run of this connector was abandoned (crash).
                  for (ReconRun old : tx.recon().runs(50)) {
                    if (old.connector.equals(connectorId) && old.status == ReconRunStatus.RUNNING) {
                      old.status = ReconRunStatus.FAILED;
                      old.finishedAt = now;
                      ObjectNode st = Json.obj();
                      st.put("error", "abandoned");
                      old.stats = st;
                      tx.recon().updateRun(old);
                    }
                  }
                  ReconRun r = new ReconRun();
                  r.id = p.ids().next();
                  r.connector = connectorId;
                  r.windowStart = windowStart;
                  r.windowEnd = windowEnd;
                  r.status = ReconRunStatus.RUNNING;
                  r.triggeredBy = trigger;
                  r.stats = Json.obj();
                  r.startedAt = now;
                  ReconRun saved = tx.recon().insertRun(r);
                  ObjectNode d = Json.obj();
                  d.put("recon_run_number", saved.number);
                  d.put("connector", connectorId);
                  d.put("triggered_by", trigger);
                  EventLog.append(tx, now, null, null, "recon.started", d);
                  return saved;
                });

    // Provider side, outside any TX. Listing starts lookupLag earlier and runs to now.
    Duration maxLag = Duration.ZERO;
    for (ToolSpec t : connector.tools()) {
      if (t.lookupLag().compareTo(maxLag) > 0) {
        maxLag = t.lookupLag();
      }
    }
    Instant fetchStart = p.clock().now();
    List<ProviderEffect> listed;
    try {
      listed = listAll(connector, windowStart.minus(maxLag), fetchStart);
    } catch (ConnectorException e) {
      return fail(run, "provider listing failed: " + e.getMessage(), t0);
    }
    p.crash().hit(CrashPoint.RECON_AFTER_FETCH);

    Map<UUID, List<ProviderEffect>> byAction = new LinkedHashMap<>();
    Map<String, Set<String>> humanRefundsByOrder = new TreeMap<>();
    for (ProviderEffect e : listed) {
      if (e.undoActionId().isPresent()) {
        byAction.computeIfAbsent(e.undoActionId().get(), k -> new ArrayList<>()).add(e);
      } else if (e.objectType().equals("refund")) {
        String order = e.raw().path("order_id").asText("");
        humanRefundsByOrder.computeIfAbsent(order, k -> new TreeSet<>()).add(e.providerRef());
      }
    }
    for (List<ProviderEffect> g : byAction.values()) {
      g.sort(
          Comparator.comparing(ProviderEffect::createdAt)
              .thenComparing(ProviderEffect::providerRef));
    }

    // Undo side: the window's actions, every UNKNOWN/NEEDS_REVIEW one, and every action named by a
    // provider object.
    Tally tally = new Tally();
    Map<UUID, Action> candidates = new LinkedHashMap<>();
    p.store()
        .tx(
            tx -> {
              for (Action a : tx.actions().forRecon(connectorId, windowStart, windowEnd)) {
                candidates.put(a.id, a);
              }
              for (UUID id : byAction.keySet()) {
                if (!candidates.containsKey(id)) {
                  Optional<Action> a = tx.actions().get(id);
                  if (a.isPresent() && a.get().connector.equals(connectorId)) {
                    candidates.put(id, a.get());
                  } else if (a.isEmpty()) {
                    unknownOrigin(tx, run, byAction.get(id), id, tally);
                  }
                }
              }
              return null;
            });

    Set<String> undoRefundOrders = new TreeSet<>();
    for (Action a : candidates.values()) {
      if (a.kind != ActionKind.PRIMARY) {
        continue;
      }
      ToolSpec spec = p.registry().tool(a.tool);
      if (spec == null || spec.providerObjectType().isEmpty()) {
        continue;
      }
      if (spec.providerObjectType().get().equals("refund")) {
        undoRefundOrders.add(a.args.path("order_id").asText(""));
      }
      List<ProviderEffect> objs = byAction.getOrDefault(a.id, List.of());
      boolean confirmMissing =
          objs.isEmpty()
              && a.status == ActionStatus.SUCCEEDED
              && a.completedAt != null
              && a.completedAt.isBefore(windowEnd.minus(spec.lookupLag()))
              && a.updatedAt.isBefore(fetchStart);
      boolean missing = confirmMissing && confirmedMissing(connector, a);
      tally.checked++;
      try {
        p.store().tx(tx -> check(tx, run, spec, a.id, objs, missing, fetchStart, tally));
      } catch (RuntimeException e) {
        tally.errors++;
      }
    }

    // Webhooks that never matched an action.
    p.store()
        .tx(
            tx -> {
              Instant cutoff = p.clock().now().minus(cfg.unmatchedWebhookAfter());
              for (WebhookEvent e : tx.webhooks().unprocessedReceivedBefore(cutoff)) {
                if (!e.provider.equals(connectorId)) {
                  continue;
                }
                String ref = e.payload.path("data").path("object").path("id").asText(e.eventId);
                ObjectNode actual = Json.obj();
                actual.put("event_id", e.eventId);
                actual.put("event_type", e.type);
                actual.put("received_at", e.receivedAt.toString());
                actual.put("process_attempts", e.processAttempts);
                raise(
                    tx,
                    run,
                    FindingType.UNMATCHED_WEBHOOK,
                    Severity.WARNING,
                    null,
                    ref,
                    null,
                    actual,
                    FindingStatus.OPEN,
                    tally);
              }
              return null;
            });

    long overRefunded =
        humanRefundsByOrder.keySet().stream().filter(undoRefundOrders::contains).count();
    return p.store()
        .tx(
            tx -> {
              Instant now = p.clock().now();
              ReconRun r = run.copy();
              r.status = ReconRunStatus.COMPLETED;
              r.finishedAt = now;
              ObjectNode st = Json.obj();
              st.put("provider_objects", listed.size());
              st.put("actions_checked", tally.checked);
              st.put("matched", tally.matched);
              ObjectNode byType = st.putObject("findings_by_type");
              tally.byType.forEach(byType::put);
              st.put("auto_healed", tally.healed);
              st.put("over_refunded_orders", overRefunded);
              st.put("errors", tally.errors);
              st.put("duration_ms", Duration.between(t0, p.clock().now()).toMillis());
              r.stats = st;
              tx.recon().updateRun(r);
              ObjectNode d = Json.obj();
              d.put("recon_run_number", r.number);
              d.put("connector", connectorId);
              d.set("stats", st);
              EventLog.append(tx, now, null, null, "recon.completed", d);
              p.metrics()
                  .count("undo_recon_runs_total", "connector", connectorId, "status", "completed");
              return r;
            });
  }

  private ReconRun fail(ReconRun run, String error, Instant t0) {
    return p.store()
        .tx(
            tx -> {
              ReconRun r = run.copy();
              r.status = ReconRunStatus.FAILED;
              r.finishedAt = p.clock().now();
              ObjectNode st = Json.obj();
              st.put("error", error);
              st.put("duration_ms", Duration.between(t0, p.clock().now()).toMillis());
              r.stats = st;
              tx.recon().updateRun(r);
              p.metrics()
                  .count("undo_recon_runs_total", "connector", r.connector, "status", "failed");
              return r;
            });
  }

  private static List<ProviderEffect> listAll(Connector c, Instant from, Instant to)
      throws ConnectorException {
    List<ProviderEffect> out = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    for (int page = 0; page < 10_000; page++) {
      EffectPage pg = c.listEffects(from, to, cursor);
      out.addAll(pg.effects());
      if (pg.nextCursor().isEmpty()) {
        return out;
      }
      cursor = pg.nextCursor();
    }
    throw new ConnectorException("too many pages");
  }

  /**
   * MISSING_EFFECT is raised only when confirmed: lookup NotFound, or (for tools without a lookup,
   * such as the email export) a narrow listing around the action's attempts that does not show it.
   * Any provider error means "not confirmed".
   */
  private boolean confirmedMissing(Connector connector, Action a) {
    LookupOutcome r =
        connector.lookup(
            new ToolCall(
                a.tool,
                a.args,
                a.id,
                a.providerIdempotencyKey,
                a.attemptCount,
                a.runId,
                a.beforeSnapshot));
    return switch (r) {
      case LookupOutcome.Found f -> false;
      case LookupOutcome.NotFound nf -> true;
      case LookupOutcome.Unavailable u -> {
        Instant from =
            (a.firstAttemptAt != null ? a.firstAttemptAt : a.createdAt)
                .minus(Duration.ofMinutes(1));
        try {
          yield listAll(connector, from, p.clock().now()).stream()
              .noneMatch(e -> e.undoActionId().filter(a.id::equals).isPresent());
        } catch (ConnectorException e) {
          yield false;
        }
      }
    };
  }

  private Void check(
      TxContext tx,
      ReconRun run,
      ToolSpec spec,
      UUID actionId,
      List<ProviderEffect> objs,
      boolean missing,
      Instant fetchStart,
      Tally tally) {
    Action a = tx.actions().lock(actionId);
    if (!a.updatedAt.isBefore(fetchStart)) {
      return null; // changed while we listed; the next run checks it
    }
    String type = spec.providerObjectType().orElseThrow();
    int before = tally.findings;
    if (objs.size() > 1) {
      ObjectNode exp = Json.obj();
      exp.put("provider_objects", 1);
      ObjectNode act = Json.obj();
      act.put("provider_objects", objs.size());
      ArrayNode refs = act.putArray("provider_refs");
      objs.forEach(o -> refs.add(o.providerRef()));
      raise(
          tx,
          run,
          FindingType.DUPLICATE_EFFECT,
          Severity.CRITICAL,
          a,
          objs.get(1).providerRef(),
          exp,
          act,
          FindingStatus.OPEN,
          tally);
    }
    if (objs.isEmpty()) {
      if (missing) {
        ObjectNode exp = Json.obj();
        exp.put("provider_object", type);
        exp.put("provider_ref", a.providerRef);
        ObjectNode act = Json.obj();
        act.put("provider_object", "none (lookup confirmed)");
        raise(
            tx,
            run,
            FindingType.MISSING_EFFECT,
            Severity.CRITICAL,
            a,
            a.providerRef,
            exp,
            act,
            FindingStatus.OPEN,
            tally);
      }
    } else if (GHOST_HEALABLE.contains(a.status)) {
      ProviderEffect o = objs.get(0);
      ActionStatus prev = a.status;
      long fence = a.leaseFence;
      ObjectNode exp = Json.obj();
      exp.put("status", prev.name());
      ObjectNode result = Json.obj();
      result.put("healed_by", "recon");
      result.put("provider_ref", o.providerRef());
      result.put("provider_status", o.status());
      a.leaseOwner = null;
      a.leaseExpiresAt = null;
      execution.applyReconSuccess(
          tx,
          a,
          o.providerRef(),
          MONEY_TYPES.contains(type) ? o.status() : null,
          result,
          p.clock().now());
      if (!tx.actions().update(a, prev, fence)) {
        throw new IllegalStateException("locked action changed during recon heal");
      }
      ObjectNode act = Json.obj();
      act.put("status", "SUCCEEDED");
      act.put("provider_ref", o.providerRef());
      act.put("provider_status", o.status());
      act.put("healed", "marked SUCCEEDED with the provider reference");
      raise(
          tx,
          run,
          FindingType.GHOST_EFFECT,
          Severity.WARNING,
          a,
          o.providerRef(),
          exp,
          act,
          FindingStatus.AUTO_HEALED,
          tally);
      tally.healed++;
    } else if (NO_EFFECT_EXPECTED.contains(a.status)) {
      ProviderEffect o = objs.get(0);
      ObjectNode exp = Json.obj();
      exp.put("status", a.status.name());
      exp.put("provider_object", "none");
      ObjectNode act = Json.obj();
      act.put("provider_ref", o.providerRef());
      act.put("provider_status", o.status());
      act.put("amount_minor", o.amountMinor());
      raise(
          tx,
          run,
          FindingType.GHOST_EFFECT,
          Severity.CRITICAL,
          a,
          o.providerRef(),
          exp,
          act,
          FindingStatus.OPEN,
          tally);
    } else if (a.status == ActionStatus.SUCCEEDED && MONEY_TYPES.contains(type)) {
      ProviderEffect o = objs.get(0);
      if (o.amountMinor() != a.moneyOutMinor) {
        ObjectNode exp = Json.obj();
        exp.put("amount_minor", a.moneyOutMinor);
        ObjectNode act = Json.obj();
        act.put("amount_minor", o.amountMinor());
        raise(
            tx,
            run,
            FindingType.AMOUNT_MISMATCH,
            Severity.CRITICAL,
            a,
            o.providerRef(),
            exp,
            act,
            FindingStatus.OPEN,
            tally);
      }
      if (a.compensatedByActionId != null && !REVERSED_STATUSES.contains(o.status())) {
        ObjectNode exp = Json.obj();
        exp.put("provider_status", type.equals("refund") ? "cancelled" : "revoked");
        exp.put("compensated_by", a.compensatedByActionId.toString());
        ObjectNode act = Json.obj();
        act.put("provider_status", o.status());
        raise(
            tx,
            run,
            FindingType.COMPENSATION_MISMATCH,
            Severity.CRITICAL,
            a,
            o.providerRef(),
            exp,
            act,
            FindingStatus.OPEN,
            tally);
      } else if (!Objects.equals(o.status(), a.providerStatus)) {
        String old = a.providerStatus;
        if (webhooks.applyStatusChange(tx, a, o.status(), "recon", run.id.toString())) {
          ObjectNode exp = Json.obj();
          exp.put("provider_status", old);
          ObjectNode act = Json.obj();
          act.put("provider_status", o.status());
          act.put("healed", "applied the provider status through the webhook code path");
          raise(
              tx,
              run,
              FindingType.STATUS_MISMATCH,
              Severity.INFO,
              a,
              o.providerRef(),
              exp,
              act,
              FindingStatus.AUTO_HEALED,
              tally);
          tally.healed++;
        }
      }
    }
    if (tally.findings == before && objs.size() == 1) {
      tally.matched++;
    }
    return null;
  }

  private void unknownOrigin(
      TxContext tx, ReconRun run, List<ProviderEffect> objs, UUID id, Tally tally) {
    for (ProviderEffect o : objs) {
      ObjectNode exp = Json.obj();
      exp.put("action", "an Undo action with this id");
      ObjectNode act = Json.obj();
      act.put("undo_action_id", id.toString());
      act.put("provider_ref", o.providerRef());
      act.put("object_type", o.objectType());
      act.put("amount_minor", o.amountMinor());
      raise(
          tx,
          run,
          FindingType.UNKNOWN_ORIGIN,
          Severity.CRITICAL,
          null,
          o.providerRef(),
          exp,
          act,
          FindingStatus.OPEN,
          tally);
    }
  }

  /** Inserts a finding unless an OPEN/ACKNOWLEDGED one with the same fingerprint exists. */
  private void raise(
      TxContext tx,
      ReconRun run,
      FindingType type,
      Severity severity,
      Action a,
      String providerRef,
      JsonNode expected,
      JsonNode actual,
      FindingStatus status,
      Tally tally) {
    tally.findings++;
    String fp = type.name() + ":" + (a != null ? a.id.toString() : providerRef);
    if (status == FindingStatus.OPEN && tx.recon().openByFingerprint(fp).isPresent()) {
      return;
    }
    Instant now = p.clock().now();
    ReconFinding f = new ReconFinding();
    f.id = p.ids().next();
    f.reconRunId = run.id;
    f.type = type;
    f.severity = severity;
    f.actionId = a != null ? a.id : null;
    f.providerObjectRef = providerRef;
    f.expected = expected;
    f.actual = actual;
    f.status = status;
    f.fingerprint = fp;
    f.createdAt = now;
    tx.recon().insertFinding(f);
    tally.byType.merge(type.name(), 1L, Long::sum);
    ObjectNode d = a != null ? EventLog.actionData(a) : Json.obj();
    d.put("recon_run_number", run.number);
    d.put("finding_id", f.id.toString());
    d.put("finding_type", type.name());
    d.put("severity", severity.name());
    d.put("finding_status", status.name());
    if (providerRef != null) {
      d.put("provider_ref", providerRef);
    }
    EventLog.append(tx, now, a != null ? a.runId : null, f.actionId, "recon.finding", d);
    p.metrics()
        .count("undo_recon_findings_total", "type", type.name(), "severity", severity.name());
  }

  // ── human handling of findings ────────────────────────────────────

  public ReconFinding acknowledge(UUID findingId, String actor) {
    return update(findingId, null, null, actor, false);
  }

  public ReconFinding resolve(UUID findingId, String note, UUID userId, String actor) {
    if (note == null || note.isBlank()) {
      throw new UndoException(UndoException.Code.INVALID_REQUEST, "A note is required.");
    }
    return update(findingId, note, userId, actor, true);
  }

  private ReconFinding update(
      UUID findingId, String note, UUID userId, String actor, boolean resolve) {
    return p.store()
        .tx(
            tx -> {
              ReconFinding f =
                  tx.recon()
                      .finding(findingId)
                      .orElseThrow(
                          () ->
                              new UndoException(
                                  UndoException.Code.FINDING_NOT_FOUND, "No such finding."));
              boolean ok =
                  resolve
                      ? f.status == FindingStatus.OPEN || f.status == FindingStatus.ACKNOWLEDGED
                      : f.status == FindingStatus.OPEN;
              if (!ok) {
                throw new UndoException(
                    UndoException.Code.FINDING_NOT_OPEN, "The finding is " + f.status + ".");
              }
              Instant now = p.clock().now();
              FindingStatus old = f.status;
              f.status = resolve ? FindingStatus.RESOLVED : FindingStatus.ACKNOWLEDGED;
              if (resolve) {
                f.resolutionNote = note;
                f.resolvedBy = userId;
                f.resolvedAt = now;
              }
              tx.recon().updateFinding(f);
              ObjectNode d = Json.obj();
              d.put("finding_id", f.id.toString());
              d.put("finding_type", f.type.name());
              d.put("severity", f.severity.name());
              d.put("finding_status", f.status.name());
              d.put("previous_status", old.name());
              d.put("actor", actor);
              if (note != null) {
                d.put("note", note);
              }
              UUID runId =
                  f.actionId == null
                      ? null
                      : tx.actions().get(f.actionId).map(x -> x.runId).orElse(null);
              EventLog.append(tx, now, runId, f.actionId, "recon.finding_updated", d);
              return f;
            });
  }

  private static final class Tally {
    int checked;
    int matched;
    int healed;
    int findings;
    int errors;
    final Map<String, Long> byType = new TreeMap<>();
  }
}
