package dev.undo.sim.world;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.agent.TicketScript;
import dev.undo.core.agent.TicketTruth;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.engine.EngineConfig;
import dev.undo.core.engine.Registry;
import dev.undo.core.engine.UndoConnector;
import dev.undo.core.model.Action;
import dev.undo.core.model.Agent;
import dev.undo.core.model.Run;
import dev.undo.core.policy.BuiltinPolicies;
import dev.undo.core.policy.PolicyService;
import dev.undo.core.port.Bug;
import dev.undo.core.port.BugFlags;
import dev.undo.core.port.Ids;
import dev.undo.core.port.Waiter;
import dev.undo.sim.store.InMemoryStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * One seed's world (Section 21.2): clock, random, store, network, shop, workers, agents, operator.
 */
public final class World {
  public record Result(
      long seed,
      List<Violation> violations,
      Coverage coverage,
      long events,
      long virtualSeconds,
      List<String> needsReview) {}

  static final Instant EPOCH = Instant.parse("2026-10-24T08:00:00Z");
  static final long CHAOS_LIMIT = WorldParams.s(2 * 3600);
  static final long TOTAL_LIMIT = WorldParams.s(4 * 3600);

  final long seed;
  final WorldParams params;
  final EventLoop loop = new EventLoop(EPOCH);
  final CoopScheduler sched;
  final InMemoryStore store = new InMemoryStore();
  final SimShopfront shop;
  final Ids ids;
  final BugFlags bugs;
  final Waiter waiter;
  final PolicyService policies;
  final EngineConfig engineConfig;
  final Coverage coverage = new Coverage();
  final Invariants invariants;
  final List<Violation> violations = new ArrayList<>();
  final List<String> needsReview = new ArrayList<>();
  final Map<String, TicketTruth> truth = new HashMap<>();
  final TicketScript.Knobs knobs;
  final CoopScheduler.Owner immortal = new CoopScheduler.Owner("world");
  final List<SimWorker> workers = new ArrayList<>();
  final List<SimAgent> agents = new ArrayList<>();
  final SimOperator operator;
  final Registry toolRegistry;
  final SplittableRandom pick;
  UUID agentId;
  boolean chaos = true;
  boolean ended;
  final dev.undo.sim.store.MemoryLocks locks;
  final dev.undo.core.recon.ReconService.Config reconConfig =
      new dev.undo.core.recon.ReconService.Config(
          Duration.ofMinutes(10),
          Duration.ofHours(24),
          Duration.ofMinutes(10),
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(10));
  private boolean finalReconStarted;

  /**
   * Hosted runs (Section 17): ticket lists sampled per seed, worked by the runner on the workers.
   */
  final List<List<String>> hostedPlans = new ArrayList<>();

  final List<UUID> hostedRuns = new ArrayList<>();
  private final java.util.Set<UUID> hostedEnded = new java.util.HashSet<>();
  dev.undo.core.agent.ScriptedModelClient scripted;
  dev.undo.core.model.ReconRun finalRecon;
  int wrongHashAccepted;
  int revertsStarted;
  private final Trace trace;
  private final Map<UUID, List<CoopScheduler.Park>> waiters = new HashMap<>();
  private long idSeq;

  public World(long seed, Set<Bug> bugSet, boolean assumeA1, Trace trace) {
    this.seed = seed;
    this.trace = trace;
    SplittableRandom root = new SplittableRandom(seed);
    this.params = WorldParams.sample(root.split(), assumeA1);
    this.locks = new dev.undo.sim.store.MemoryLocks(loop::now);
    this.engineConfig =
        EngineConfig.defaults()
            .withInline(params.inline())
            .withMaxUnknownAge(Duration.ofNanos(params.maxUnknownAgeMicros() * 1000));
    this.bugs = bugSet::contains;
    this.sched = new CoopScheduler(loop, (t, e) -> engineError("task " + t.name(), e));
    if (trace != null) {
      loop.trace(trace);
    }
    long idBits = root.nextLong();
    this.ids =
        () ->
            new UUID(
                ((++idSeq) << 16) | 0x7000L, 0x8000000000000000L | (idBits & 0x3FFFFFFFFFFFFFFFL));
    this.shop = new SimShopfront(loop, root.split(), params, seed);
    this.waiter =
        new Waiter() {
          @Override
          public void sleep(Duration d) {
            sched.sleep(d.toNanos() / 1000, "sleep");
          }

          @Override
          public void awaitActionChange(UUID actionId, Instant deadline) {
            CoopScheduler.Park p = sched.newPark();
            waiters.computeIfAbsent(actionId, k -> new ArrayList<>()).add(p);
            sched.resumeAt(p, micros(deadline), "wait deadline " + actionId, null);
            sched.block(p);
          }
        };
    store.onActionsCommitted(
        changed -> {
          for (UUID id : changed) {
            List<CoopScheduler.Park> ps = waiters.remove(id);
            if (ps != null) {
              for (CoopScheduler.Park p : ps) {
                sched.resumeAt(p, loop.nowMicros(), "action changed " + id, null);
              }
            }
          }
        });
    this.invariants = new Invariants(this);
    store.onCommit(
        info -> {
          if (!info.actionIds().isEmpty() || !info.transitions().isEmpty()) {
            invariants.afterCommit(info);
          }
        });
    ShopfrontConnector toolsOnly =
        new ShopfrontConnector(
            r -> null,
            new ShopfrontConnector.Config(
                SimShopfront.API_KEY,
                SimShopfront.SECRET,
                Duration.ofMinutes(15),
                Duration.ofSeconds(3)),
            loop::now);
    this.toolRegistry = new Registry(List.of(toolsOnly, new UndoConnector(store)));
    this.policies = new PolicyService(store, ids, loop::now, toolRegistry);
    String expiry = Duration.ofNanos(params.approvalExpiryMicros() * 1000).withNanos(0).toString();
    policies.bootstrap(
        BuiltinPolicies.supportDefaultYaml()
            .replace("expiresAfter: PT24H", "expiresAfter: " + expiry),
        "sim");
    store.tx(
        tx -> {
          Agent a = new Agent();
          a.id = ids.next();
          a.name = "demo-support";
          a.createdAt = loop.now();
          tx.agents().insert(a);
          agentId = a.id;
          return null;
        });
    for (JsonNode t : shop.admin("GET", "/admin/ground-truth", null)) {
      truth.put(
          t.get("ticket_id").asText(),
          new TicketTruth(
              t.get("ticket_id").asText(),
              t.get("category").asText(),
              t.get("base_category").asText(),
              t.get("expected_resolution").asText(),
              t.get("attack").isNull() ? null : t.get("attack")));
    }
    this.knobs =
        new TicketScript.Knobs(params.adversarial(), params.repeatRate(), params.parallelDupRate());
    SplittableRandom workerRandom = root.split();
    for (int i = 0; i < params.workers(); i++) {
      workers.add(new SimWorker(this, i + 1, workerRandom.split()));
    }
    SplittableRandom agentRandom = root.split();
    for (int i = 0; i < params.runs(); i++) {
      List<String> tickets = new ArrayList<>();
      for (int k = 0; k < params.ticketsPerRun()[i]; k++) {
        tickets.add(String.format("TKT-%04d", params.ticketOffsetPerRun()[i] + k + 1));
      }
      agents.add(
          new SimAgent(
              this,
              i + 1,
              agentRandom.split(),
              tickets,
              params.agentConcurrency()[i],
              params.moneyCapPerRun()[i],
              params.actionCapPerRun()[i]));
    }
    this.operator = new SimOperator(this, root.split());
    this.pick = root.split();
    this.scripted =
        new dev.undo.core.agent.ScriptedModelClient(
            id -> java.util.Optional.ofNullable(truth.get(id)));
    SplittableRandom hr = root.split(); // drawn last: earlier draws of a seed stay the same
    int hosted = hr.nextInt(3);
    for (int i = 0; i < hosted; i++) {
      int n = 3 + hr.nextInt(13);
      int offset = hr.nextInt(WorldParams.TICKETS - n + 1);
      List<String> tickets = new ArrayList<>();
      for (int k = 0; k < n; k++) {
        tickets.add(String.format("TKT-%04d", offset + k + 1));
      }
      hostedPlans.add(tickets);
    }
  }

  static long micros(Instant t) {
    Duration d = Duration.between(EPOCH, t);
    return d.getSeconds() * 1_000_000 + d.getNano() / 1000;
  }

  public Result run() {
    for (SimWorker wk : workers) {
      wk.start();
    }
    for (int i = 0; i < agents.size(); i++) {
      agents.get(i).start(WorldParams.s(i * 20L));
    }
    operator.start();
    for (int i = 0; i < hostedPlans.size(); i++) {
      int k = i;
      loop.after(
          WorldParams.s(10 + 40L * i),
          "hosted run " + i,
          () -> sched.spawn("start hosted run " + k, immortal, () -> startHosted(k)));
    }
    loop.after(WorldParams.s(5), "settlement", this::settlementTick);
    loop.after(WorldParams.s(1), "webhooks", this::webhookTick);
    loop.after(WorldParams.s(10), "phase check", this::phaseCheck);
    while (!ended && loop.step()) {
      // events drive everything
    }
    List<CoopScheduler.Owner> owners = new ArrayList<>();
    owners.add(immortal);
    for (SimWorker wk : workers) {
      if (wk.owner != null) {
        owners.add(wk.owner);
      }
    }
    sched.shutdown(owners);
    invariants.finalChecks();
    for (var it : store.snapshot().items()) {
      coverage.revertKinds.merge(it.kind.name(), 1L, Long::sum);
    }
    for (var f : store.snapshot().findings()) {
      coverage.findingTypes.merge(f.type.name() + "/" + f.severity.name(), 1L, Long::sum);
    }
    for (var at : store.snapshot().attempts()) {
      coverage.outcome(at.outcome);
    }
    if (trace != null) {
      for (Violation v : violations) {
        trace.note(loop.nowMicros(), "VIOLATION " + v.invariant() + " " + v.message());
      }
      trace.flush();
    }
    return new Result(
        seed, violations, coverage, loop.executed(), loop.elapsedSeconds(), needsReview);
  }

  private void settlementTick() {
    if (ended) {
      return;
    }
    shop.settlementTick();
    loop.after(WorldParams.s(5), "settlement", this::settlementTick);
  }

  private final java.util.Set<String> webhooksInFlight = new java.util.HashSet<>();

  /** Shopfront's webhook job: due outbox rows are delivered to a random live node (12.4). */
  private void webhookTick() {
    if (ended) {
      return;
    }
    for (var row :
        shop.store.tx(tx -> dev.shopfront.core.webhooks.WebhookOutbox.due(tx, loop.now(), 100))) {
      if (!webhooksInFlight.add(row.id())) {
        continue;
      }
      var plan = dev.shopfront.core.webhooks.WebhookOutbox.plan(shop.faults, shop.random);
      if (plan.drop()) {
        shop.store.tx(
            tx -> {
              dev.shopfront.core.webhooks.WebhookOutbox.markDelivered(tx, row.id(), loop.now());
              return null;
            });
        webhooksInFlight.remove(row.id());
        continue;
      }
      loop.after(
          plan.delayMs() * 1000,
          "deliver webhook " + row.id(),
          () ->
              sched.spawn(
                  "webhook " + row.id(), immortal, () -> deliverWebhook(row, plan.copies())));
    }
    loop.after(WorldParams.s(1), "webhooks", this::webhookTick);
  }

  private void deliverWebhook(dev.shopfront.core.domain.OutboxRow row, int copies) {
    boolean ok = false;
    try {
      for (int i = 0; i < copies; i++) {
        SimWorker wk = randomLiveWorker(pick);
        if (wk == null) {
          continue;
        }
        byte[] body = dev.shopfront.core.webhooks.WebhookOutbox.body(row);
        java.util.Map<String, String> headers =
            dev.shopfront.core.webhooks.WebhookOutbox.headers(
                row, body, SimShopfront.SECRET, loop.now());
        try {
          sched.call(
              wk.owner,
              "webhook ingest on " + wk.node,
              () -> {
                try {
                  return wk.webhooks.ingest(
                      dev.undo.connector.shopfront.ShopfrontConnector.ID, headers, body);
                } catch (dev.undo.core.connector.InvalidWebhookException e) {
                  throw new IllegalStateException(
                      "simulated webhook rejected: " + e.getMessage(), e);
                }
              });
          ok = true;
        } catch (CoopScheduler.RpcFailed e) {
          // the node died while receiving: Shopfront retries later
        }
      }
    } finally {
      boolean delivered = ok;
      shop.store.tx(
          tx -> {
            if (delivered) {
              dev.shopfront.core.webhooks.WebhookOutbox.markDelivered(tx, row.id(), loop.now());
            } else {
              dev.shopfront.core.webhooks.WebhookOutbox.markFailed(tx, row.id(), loop.now());
            }
            return null;
          });
      webhooksInFlight.remove(row.id());
    }
  }

  private void phaseCheck() {
    if (ended) {
      return;
    }
    boolean agentsDone = agents.stream().allMatch(a -> a.done) && hostedDone();
    long now = loop.nowMicros();
    if (chaos && ((agentsDone && operator.revertsWaiting == 0) || now >= CHAOS_LIMIT)) {
      chaos = false;
      shop.calmWebhooks();
      operator.finish();
      note("QUIESCENCE");
    } else if (!chaos) {
      boolean quiet =
          agentsDone
              && store.snapshotInFlight() == 0
              && operator.idle()
              && !revertWorkLeft()
              && sched.liveTasks() == 0;
      if (finalRecon != null || now >= TOTAL_LIMIT) {
        ended = true;
        note(finalRecon != null ? "END quiet" : "END time limit");
        return;
      }
      if (quiet && !finalReconStarted) {
        finalReconStarted = true;
        note("FINAL RECON");
        sched.spawn("final recon", immortal, this::finalRecon);
      }
    }
    loop.after(WorldParams.s(10), "phase check", this::phaseCheck);
  }

  /** The runner's hosted runs: all created and none ACTIVE; a COMPLETED one may be reverted. */
  private boolean hostedDone() {
    if (hostedRuns.size() < hostedPlans.size()) {
      return false;
    }
    boolean done = true;
    for (UUID id : hostedRuns) {
      Run r = store.snapshot().run(id);
      if (r.status == dev.undo.core.model.RunStatus.ACTIVE) {
        done = false;
      } else if (hostedEnded.add(id) && r.status == dev.undo.core.model.RunStatus.COMPLETED) {
        operator.onRunEnded(r);
      }
    }
    return done;
  }

  private void startHosted(int i) {
    List<com.fasterxml.jackson.databind.JsonNode> tasks = new ArrayList<>();
    for (String t : hostedPlans.get(i)) {
      tasks.add(dev.undo.core.agent.SupportAgent.ticketTask(t, knobs));
    }
    while (true) {
      SimWorker wk = randomLiveWorker(pick);
      if (wk == null) {
        sched.sleep(WorldParams.s(2), "hosted run waits for a node");
        continue;
      }
      try {
        Run run =
            sched.call(
                wk.owner,
                "create hosted run on " + wk.node,
                () ->
                    wk.runner.createRun(
                        agentId,
                        "hosted " + i,
                        dev.undo.core.agent.SupportAgent.SUPPORT_TRIAGE,
                        "scripted",
                        dev.undo.core.json.Json.obj(),
                        tasks));
        hostedRuns.add(run.id);
        onRunCreated(run);
        return;
      } catch (CoopScheduler.RpcFailed e) {
        sched.sleep(WorldParams.s(2), "hosted run retry");
      }
    }
  }

  /** Section 21.4: after quiescence, one on-demand reconciliation, then the final checks. */
  private void finalRecon() {
    while (finalRecon == null) {
      SimWorker wk = randomLiveWorker(pick);
      if (wk == null) {
        sched.sleep(WorldParams.s(2), "final recon waits for a node");
        continue;
      }
      try {
        finalRecon =
            sched.call(
                wk.owner,
                "final recon on " + wk.node,
                () -> wk.recon.runNow(ShopfrontConnector.ID, "sim:final", wk.node));
      } catch (CoopScheduler.RpcFailed e) {
        sched.sleep(WorldParams.s(2), "final recon retry");
      } catch (dev.undo.core.engine.UndoException e) {
        sched.sleep(WorldParams.s(30), "final recon waits for the lease"); // RECON_RUNNING
      }
    }
  }

  /**
   * An EXECUTING plan can still make progress: its current item does not wait for a person to
   * resolve a NEEDS_REVIEW compensation.
   */
  private boolean revertWorkLeft() {
    var s = store.snapshot();
    for (var pl : s.plans()) {
      if (pl.status != dev.undo.core.model.PlanStatus.EXECUTING) {
        continue;
      }
      var next =
          s.items().stream()
              .filter(
                  i ->
                      i.planId.equals(pl.id)
                          && (i.status == dev.undo.core.model.ItemStatus.PLANNED
                              || i.status == dev.undo.core.model.ItemStatus.IN_PROGRESS))
              .min(java.util.Comparator.comparingInt(i -> i.orderNo));
      if (next.isEmpty()) {
        return true; // only the finish step is left
      }
      var it = next.get();
      Action c = it.compensationActionId == null ? null : s.action(it.compensationActionId);
      if (c == null || c.status != dev.undo.core.model.ActionStatus.NEEDS_REVIEW) {
        return true;
      }
    }
    return false;
  }

  // ── helpers used by the components ──────────────────────────────────

  void note(String what) {
    if (trace != null) {
      trace.note(loop.nowMicros(), what);
    }
  }

  void violation(String inv, String msg) {
    if (violations.size() < 50) {
      violations.add(new Violation(seed, inv, msg));
    }
  }

  void engineError(String where, Throwable e) {
    StringBuilder sb = new StringBuilder(where + ": " + e);
    StackTraceElement[] st = e.getStackTrace();
    for (int i = 0; i < Math.min(6, st.length); i++) {
      sb.append(" | ").append(st[i]);
    }
    violation("ENGINE", sb.toString());
  }

  SimWorker randomLiveWorker(SplittableRandom r) {
    List<SimWorker> live = new ArrayList<>();
    for (SimWorker wk : workers) {
      if (wk.alive()) {
        live.add(wk);
      }
    }
    return live.isEmpty() ? null : live.get(r.nextInt(live.size()));
  }

  /** Runs the bodies as parallel world-owned tasks and waits for all of them. */
  void parallel(String name, List<Runnable> bodies) {
    if (bodies.size() == 1) {
      bodies.get(0).run();
      return;
    }
    CoopScheduler.Park p = sched.newPark();
    int[] left = {bodies.size()};
    Throwable[] err = new Throwable[1];
    for (int i = 0; i < bodies.size(); i++) {
      Runnable b = bodies.get(i);
      CoopScheduler.Task t =
          sched.spawn(
              name + " #" + i,
              immortal,
              () -> {
                try {
                  b.run();
                } catch (RuntimeException e) {
                  err[0] = e;
                } finally {
                  if (--left[0] == 0) {
                    sched.resumeAt(p, loop.nowMicros(), name + " joined", null);
                  }
                }
              });
      if (t == null) {
        left[0]--;
      }
    }
    sched.block(p);
    if (err[0] instanceof RuntimeException re) {
      throw re;
    }
  }

  void onRunCreated(Run run) {
    operator.onRunCreated(run);
  }

  /** Human-readable history of one action (for --seed replays). */
  public String describe(long actionNumber) {
    StringBuilder sb = new StringBuilder();
    for (Action a : store.snapshot().actions()) {
      if (a.number != actionNumber) {
        continue;
      }
      Run run = store.snapshot().run(a.runId);
      sb.append(a.display())
          .append(' ')
          .append(a.tool)
          .append(' ')
          .append(a.kind)
          .append(' ')
          .append(a.idempotencyClass)
          .append(" status=")
          .append(a.status)
          .append(" reason=")
          .append(a.statusReason)
          .append(" run=")
          .append(run.number)
          .append(' ')
          .append(run.status)
          .append(" attempts=")
          .append(a.attemptCount)
          .append(" lookups=")
          .append(a.lookupCount)
          .append(" next=")
          .append(a.nextAttemptAt)
          .append(" unknownSince=")
          .append(a.unknownSince)
          .append('\n');
      sb.append("  args ").append(a.args).append('\n');
      for (var t : store.snapshot().transitionsOf(a.id)) {
        sb.append("  ")
            .append(t.at)
            .append(' ')
            .append(t.fromStatus)
            .append(" -> ")
            .append(t.toStatus)
            .append(" fence=")
            .append(t.fence)
            .append(' ')
            .append(t.actor)
            .append(' ')
            .append(t.reason)
            .append('\n');
      }
      store.snapshot().attempts().stream()
          .filter(at -> at.actionId.equals(a.id))
          .sorted(java.util.Comparator.comparing((dev.undo.core.model.Attempt at) -> at.startedAt))
          .forEach(
              at ->
                  sb.append("  attempt ")
                      .append(at.kind)
                      .append(' ')
                      .append(at.attemptNo)
                      .append(" fence=")
                      .append(at.fence)
                      .append(' ')
                      .append(at.node)
                      .append(' ')
                      .append(at.startedAt)
                      .append(" -> ")
                      .append(at.outcome)
                      .append(' ')
                      .append(at.detail)
                      .append('\n'));
    }
    return sb.toString();
  }

  /** Every revert plan with its items, targets, postings and provider state (for INV-6). */
  public String describePlans() {
    var s = store.snapshot();
    StringBuilder sb = new StringBuilder();
    for (var pl : s.plans()) {
      Run run = s.run(pl.runId);
      sb.append("plan ")
          .append(pl.number)
          .append(' ')
          .append(pl.scope)
          .append(' ')
          .append(pl.status)
          .append(" run=")
          .append(run.number)
          .append(' ')
          .append(run.status)
          .append(" created=")
          .append(pl.createdAt)
          .append(" started=")
          .append(pl.startedAt)
          .append('\n');
      s.items().stream()
          .filter(i -> i.planId.equals(pl.id))
          .sorted(java.util.Comparator.comparingInt(i -> i.orderNo))
          .forEach(
              it -> {
                Action t = s.action(it.targetActionId);
                sb.append("  #")
                    .append(it.orderNo)
                    .append(' ')
                    .append(t.display())
                    .append(' ')
                    .append(t.tool)
                    .append(' ')
                    .append(t.status)
                    .append(" provider=")
                    .append(t.providerRef)
                    .append('/')
                    .append(t.providerStatus)
                    .append(" out=")
                    .append(t.moneyOutMinor)
                    .append(" | ")
                    .append(it.kind)
                    .append(' ')
                    .append(it.status)
                    .append(" rec=")
                    .append(it.moneyRecoveredMinor)
                    .append(" not=")
                    .append(it.moneyNotRecoveredMinor)
                    .append(" comp=")
                    .append(
                        it.compensationActionId == null
                            ? "-"
                            : s.action(it.compensationActionId).display()
                                + " "
                                + s.action(it.compensationActionId).status)
                    .append(" note=")
                    .append(it.note)
                    .append('\n');
                for (Action c : s.actions()) {
                  if (t.id.equals(c.compensatesActionId)) {
                    sb.append("      compensation ")
                        .append(c.display())
                        .append(' ')
                        .append(c.status)
                        .append(" reason=")
                        .append(c.statusReason)
                        .append(" error=")
                        .append(c.error)
                        .append(" result=")
                        .append(c.result)
                        .append('\n');
                  }
                }
                for (var e : s.entries()) {
                  if (e.actionId.equals(t.id)) {
                    long sum = 0;
                    for (var p : s.postings()) {
                      if (p.entryId.equals(e.id) && p.account.startsWith("expense:")) {
                        sum += p.amountMinor;
                      }
                    }
                    sb.append("      journal ")
                        .append(e.kind)
                        .append(" source=")
                        .append(e.source)
                        .append(" at=")
                        .append(e.createdAt)
                        .append(" expense=")
                        .append(sum)
                        .append('\n');
                  }
                }
              });
    }
    return sb.toString();
  }

  ToolSpec registryTool(String name) {
    return toolRegistry.tool(name);
  }

  List<Action> actionsOfRun(UUID runId) {
    return store.snapshot().actionsOfRun(runId);
  }

  /** Provider truth for a human resolving NEEDS_REVIEW: the object this action created, if any. */
  String providerRefFor(Action a) {
    switch (a.tool) {
      case "shop_issue_refund" -> {
        JsonNode d = shop.get("/v1/refunds?metadata.undo_action_id=" + a.id).path("data");
        return d.isEmpty() ? null : d.get(0).get("id").asText();
      }
      case "shop_grant_store_credit" -> {
        JsonNode d = shop.get("/v1/store-credits?reference=undo_" + a.id).path("data");
        return d.isEmpty() ? null : d.get(0).get("id").asText();
      }
      case "shop_send_email" -> {
        for (var e : shop.store.tx(tx -> tx.emails())) {
          if (a.id.toString().equals(e.undoActionId())) {
            return e.id();
          }
        }
        return null;
      }
      case "shop_close_ticket" -> {
        JsonNode t = shop.get("/v1/tickets/" + a.args.get("ticket_id").asText());
        return "CLOSED".equals(t.path("status").asText())
                && a.args.get("resolution").asText().equals(t.path("resolution").asText())
            ? t.get("id").asText()
            : null;
      }
      case "shop_set_order_note", "shop_restore_order_note" -> {
        JsonNode o = shop.get("/v1/orders/" + a.args.get("order_id").asText());
        return a.args.get("note").equals(o.get("note")) ? o.get("id").asText() : null;
      }
      case "shop_cancel_refund" -> {
        String id = a.args.get("refund_id").asText();
        return "cancelled".equals(shop.get("/v1/refunds/" + id).path("status").asText())
            ? id
            : null;
      }
      case "shop_revoke_store_credit" -> {
        String id = a.args.get("store_credit_id").asText();
        return "revoked".equals(shop.get("/v1/store-credits/" + id).path("status").asText())
            ? id
            : null;
      }
      case "shop_reopen_ticket" -> {
        JsonNode t = shop.get("/v1/tickets/" + a.args.get("ticket_id").asText());
        return "OPEN".equals(t.path("status").asText()) ? t.get("id").asText() : null;
      }
      default -> {
        return a.args.toString();
      }
    }
  }
}
