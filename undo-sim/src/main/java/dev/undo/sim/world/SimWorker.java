package dev.undo.sim.world;

import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.engine.ApprovalService;
import dev.undo.core.engine.EngineConfig;
import dev.undo.core.engine.ExecutionService;
import dev.undo.core.engine.Metrics;
import dev.undo.core.engine.Ports;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.RecoveryService;
import dev.undo.core.engine.Registry;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoConnector;
import dev.undo.core.policy.PolicyService;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.CrashPoints;
import dev.undo.sim.SimulatedCrash;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A logical worker (api + worker roles): its node id, engine services, and loops. Crashing kills
 * every task it runs; it restarts after 1–30 s with a new node id suffix.
 */
final class SimWorker {
  final World w;
  final int index;
  final SplittableRandom r;
  int incarnation;
  String node;
  CoopScheduler.Owner owner;
  Ports ports;
  PolicyService policies;
  ExecutionService execution;
  ProposalService proposals;
  RecoveryService recovery;
  ApprovalService approvals;
  RunService runs;
  dev.undo.core.engine.WebhookService webhooks;
  dev.undo.core.revert.RevertService reverts;
  dev.undo.core.recon.ReconService recon;
  dev.undo.core.agent.AgentRunner runner;
  private boolean runnerBusy;
  private boolean revertBusy;
  private boolean reconBusy;
  private CrashPoints crash;

  SimWorker(World w, int index, SplittableRandom r) {
    this.w = w;
    this.index = index;
    this.r = r;
  }

  boolean alive() {
    return owner != null && !owner.dead();
  }

  CrashPoints crashPoints() {
    return crash;
  }

  void start() {
    incarnation++;
    node = "w" + index + "." + incarnation;
    owner = new CoopScheduler.Owner(node);
    CoopScheduler.Owner me = owner;
    crash =
        point -> {
          if (me.dead()) {
            return;
          }
          w.coverage.reached(point);
          if (w.chaos && w.params.crashProb() > 0 && r.nextDouble() < w.params.crashProb()) {
            w.coverage.crashed(point);
            w.note("CRASH " + node + " at " + point);
            crash();
            throw new SimulatedCrash(point.name());
          }
        };
    SimNetwork net = new SimNetwork(w, this, r.split());
    ShopfrontConnector connector =
        new ShopfrontConnector(
            net,
            new ShopfrontConnector.Config(
                SimShopfront.API_KEY,
                SimShopfront.SECRET,
                Duration.ofMinutes(15),
                Duration.ofSeconds(3)),
            w.loop::now);
    Registry registry = new Registry(List.of(connector, new UndoConnector(w.store)));
    ports =
        new Ports(
            w.loop::now,
            w.ids,
            crash,
            w.store,
            registry,
            id -> {},
            w.bugs,
            r.split()::nextDouble,
            w.waiter,
            Metrics.NONE);
    EngineConfig cfg = w.engineConfig;
    policies = w.policies;
    execution = new ExecutionService(ports, cfg);
    proposals = new ProposalService(ports, policies, execution, cfg);
    recovery = new RecoveryService(ports, execution, cfg);
    approvals = new ApprovalService(ports);
    runs = new RunService(ports, policies, cfg);
    webhooks = new dev.undo.core.engine.WebhookService(ports);
    reverts = new dev.undo.core.revert.RevertService(ports, runs, proposals, webhooks, cfg);
    recon =
        new dev.undo.core.recon.ReconService(ports, webhooks, execution, w.locks, w.reconConfig);
    SplittableRandom modelRandom = r.split();
    dev.undo.core.port.ModelClient model =
        req -> {
          // A model turn takes 0.3–4 s of virtual time.
          w.waiter.sleep(Duration.ofMillis(300 + (long) (modelRandom.nextDouble() * 3700)));
          return w.scripted.create(req);
        };
    runner =
        new dev.undo.core.agent.AgentRunner(
            ports,
            proposals,
            runs,
            run -> model,
            dev.undo.core.agent.AgentRunner.Config.defaults());
    runnerBusy = false;
    revertBusy = false;
    reconBusy = false;
    w.note("START " + node);
    schedule(me, "exec", w.params.execTickMicros(), this::execTick);
    schedule(me, "sweep", w.params.sweepTickMicros(), this::sweepTick);
    schedule(me, "resolve", w.params.resolveTickMicros(), this::resolveTick);
    schedule(me, "expire", WorldParams.s(10), this::expiryTick);
    schedule(me, "idle", WorldParams.s(60), this::idleTick);
    schedule(me, "webhook-retry", WorldParams.s(5), this::webhookRetryTick);
    schedule(me, "revert", WorldParams.ms(300), this::revertTick);
    schedule(me, "recon", WorldParams.s(60), this::reconTick);
    schedule(me, "runner", WorldParams.s(1), this::runnerTick);
  }

  void crash() {
    w.sched.kill(owner);
    long delay = WorldParams.s(1) + (long) (r.nextDouble() * WorldParams.s(29));
    w.loop.after(delay, "restart w" + index, this::start);
  }

  private void schedule(CoopScheduler.Owner me, String name, long interval, Runnable tick) {
    long jitter = (long) (r.nextDouble() * interval);
    w.loop.after(
        jitter,
        name + " " + node,
        new Runnable() {
          @Override
          public void run() {
            if (me.dead() || w.ended) {
              return;
            }
            try {
              tick.run();
            } catch (SimulatedCrash c) {
              return; // crashed inside the tick (CLAIM_AFTER_COMMIT)
            } catch (RuntimeException e) {
              w.engineError(name + " tick on " + node, e);
            }
            if (!me.dead()) {
              w.loop.after(interval, name + " " + node, this);
            }
          }
        });
  }

  private void execTick() {
    if (!w.store.peekRunnable(w.loop.now())) {
      return;
    }
    for (ExecutionService.Claimed c : execution.claimBatch(node, 20)) {
      w.sched.spawn(
          "exec " + c.actionId() + " on " + node, owner, () -> execution.executeClaimed(c));
    }
  }

  private void sweepTick() {
    if (w.store.peekExpiredLeases(w.loop.now())) {
      recovery.sweepExpiredLeases(node, 100);
    }
  }

  private void resolveTick() {
    if (!w.store.peekResolvable(w.loop.now())) {
      return;
    }
    for (RecoveryService.Work work : recovery.claimResolvable(node, 20)) {
      w.sched.spawn(
          "resolve " + work.actionId() + " on " + node, owner, () -> recovery.perform(work));
    }
  }

  private void expiryTick() {
    if (w.store.peekExpiredApprovals(w.loop.now())) {
      approvals.expireDue(100);
    }
  }

  private void webhookRetryTick() {
    if (w.store.peekUnprocessedWebhooks()) {
      w.sched.spawn("webhook retry on " + node, owner, () -> webhooks.retryUnprocessed(100));
    }
  }

  /** Revert executor (7.6): expire old previews and run one step per open plan. */
  private void revertTick() {
    if (revertBusy
        || !w.store.peekRevertWork(w.loop.now(), w.loop.now().minus(w.engineConfig.previewTtl()))) {
      return;
    }
    revertBusy = true;
    CoopScheduler.Owner me = owner;
    w.sched.spawn(
        "revert on " + node,
        owner,
        () -> {
          try {
            reverts.expirePreviews(20);
            reverts.stepAll(node, 10);
          } finally {
            if (me == owner) {
              revertBusy = false;
            }
          }
        });
  }

  /** Hosted runner (7.6): claims one ACTIVE hosted run at a time and works it. */
  private void runnerTick() {
    if (runnerBusy || w.hostedRuns.isEmpty()) {
      return;
    }
    var claimed = runner.claim(node);
    if (claimed.isEmpty()) {
      return;
    }
    runnerBusy = true;
    CoopScheduler.Owner me = owner;
    w.sched.spawn(
        "runner " + claimed.get().run().number + " on " + node,
        owner,
        () -> {
          try {
            runner.process(claimed.get(), node);
          } finally {
            if (me == owner) {
              runnerBusy = false;
            }
          }
        });
  }

  /** Recon scheduler (7.6): due every 10 virtual minutes, one node at a time (job lease). */
  private void reconTick() {
    if (reconBusy) {
      return;
    }
    reconBusy = true;
    CoopScheduler.Owner me = owner;
    w.sched.spawn(
        "recon on " + node,
        owner,
        () -> {
          try {
            recon.runScheduledIfDue(ShopfrontConnector.ID, node);
          } finally {
            if (me == owner) {
              reconBusy = false;
            }
          }
        });
  }

  private void idleTick() {
    runs.closeIdle(w.engineConfig.idleTimeout(), 100);
  }

  /** Points the world itself must hit for this worker (e.g. after an RPC). */
  void hit(CrashPoint p) {
    crash.hit(p);
  }
}
