package dev.undo.sim.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.shopfront.core.port.ShopConfig;
import dev.shopfront.core.port.ShopRandom;
import dev.shopfront.core.router.ShopRouter;
import dev.shopfront.core.store.InMemoryShopStore;
import dev.undo.connector.shopfront.ShopRequest;
import dev.undo.connector.shopfront.ShopResponse;
import dev.undo.connector.shopfront.ShopTransportException;
import dev.undo.connector.shopfront.ShopfrontApi;
import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.engine.ApprovalService;
import dev.undo.core.engine.Caller;
import dev.undo.core.engine.EngineConfig;
import dev.undo.core.engine.ExecutionService;
import dev.undo.core.engine.Metrics;
import dev.undo.core.engine.Ports;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.RecoveryService;
import dev.undo.core.engine.Registry;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoConnector;
import dev.undo.core.engine.WebhookService;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.Agent;
import dev.undo.core.model.PlanScope;
import dev.undo.core.model.PlanStatus;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.policy.BuiltinPolicies;
import dev.undo.core.policy.PolicyService;
import dev.undo.core.port.Bug;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.Waiter;
import dev.undo.core.revert.RevertService;
import dev.undo.sim.SimulatedCrash;
import dev.undo.sim.store.InMemoryStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * A synchronous engine on InMemoryStore with the real connector and the real Shopfront router.
 * Faults are scripted per request; crash points throw SimulatedCrash when armed.
 */
final class Harness {
  enum Fault {
    /** Apply, then lose the response (client sees a timeout). */
    DROP_RESPONSE,
    /** Never reaches the provider; client sees a timeout. */
    TIMEOUT_NOT_SENT,
    /** 503 before handling. */
    ERROR_5XX,
    /** 429 before handling. */
    RATE_LIMIT,
    /** Held in flight; delivered later by {@link #deliverHeld()}. Client sees a timeout. */
    HOLD
  }

  Instant now = Instant.parse("2026-10-24T10:00:00Z");
  final SplittableRandom rnd = new SplittableRandom(99);
  final InMemoryStore store = new InMemoryStore();
  final InMemoryShopStore shopStore = new InMemoryShopStore();
  final ShopRouter shop;
  final Deque<Fault> faults = new ArrayDeque<>();
  final List<ShopRequest> held = new ArrayList<>();
  final EnumSet<CrashPoint> armed = EnumSet.noneOf(CrashPoint.class);
  final EnumSet<Bug> bugs = EnumSet.noneOf(Bug.class);

  /** Every counter increment and timer record as "name [tag, value, ...]". */
  final List<String> metered = new ArrayList<>();

  /** Runs once just before the next provider read reaches Shopfront (a concurrent actor). */
  Runnable beforeNextRead;

  final EngineConfig cfg = EngineConfig.defaults();
  long idSeq;

  /** When set, every GET fails with connection refused (provider read outage). */
  boolean readsDown;

  final ShopfrontConnector connector;
  final Registry registry;
  final Ports ports;
  final PolicyService policies;
  final ExecutionService execution;
  final ProposalService proposals;
  final RecoveryService recovery;
  final ApprovalService approvals;
  final RunService runs;
  final WebhookService webhooks;
  final RevertService reverts;
  final dev.undo.sim.store.MemoryLocks locks;
  final dev.undo.core.recon.ReconService recon;
  final UUID agentId;

  Harness() {
    ShopRandom shopRandom =
        new ShopRandom() {
          public double nextDouble() {
            return rnd.nextDouble();
          }

          public int nextInt(int b) {
            return rnd.nextInt(b);
          }

          public long nextLong() {
            return rnd.nextLong();
          }
        };
    ShopConfig sc = ShopConfig.defaults("k", "adm", "whsec");
    shop = new ShopRouter(shopStore, () -> now, shopRandom, sc);
    shopAdmin("POST", "/admin/seed", "{\"seed\":42}");
    ShopfrontApi api = this::send;
    connector =
        new ShopfrontConnector(
            api,
            new ShopfrontConnector.Config(
                "k", "whsec", Duration.ofMinutes(15), Duration.ofSeconds(3)),
            () -> now);
    registry = new Registry(List.of(connector, new UndoConnector(store)));
    Waiter waiter =
        new Waiter() {
          public void sleep(Duration d) {
            now = now.plus(d);
          }

          public void awaitActionChange(UUID id, Instant deadline) {
            if (now.isBefore(deadline)) {
              now = deadline;
            }
          }
        };
    ports =
        new Ports(
            () -> now,
            () -> new UUID(0x0192000000007000L, ++idSeq | 0x8000000000000000L),
            p -> {
              if (armed.remove(p)) {
                throw new SimulatedCrash(p.name());
              }
            },
            store,
            registry,
            id -> {},
            bugs::contains,
            rnd::nextDouble,
            waiter,
            new Metrics() {
              @Override
              public void count(String name, String... tags) {
                metered.add(name + " " + java.util.Arrays.toString(tags));
              }

              @Override
              public void time(String name, long nanos, String... tags) {
                metered.add(name + " " + java.util.Arrays.toString(tags));
              }
            });
    policies = new PolicyService(store, ports.ids(), ports.clock(), registry);
    policies.bootstrap(BuiltinPolicies.supportDefaultYaml(), "test");
    execution = new ExecutionService(ports, cfg);
    proposals = new ProposalService(ports, policies, execution, cfg);
    recovery = new RecoveryService(ports, execution, cfg);
    approvals = new ApprovalService(ports);
    runs = new RunService(ports, policies, cfg);
    webhooks = new WebhookService(ports);
    reverts = new RevertService(ports, runs, proposals, webhooks, cfg);
    locks = new dev.undo.sim.store.MemoryLocks(ports.clock());
    recon =
        new dev.undo.core.recon.ReconService(
            ports, webhooks, execution, locks, dev.undo.core.recon.ReconService.Config.defaults());
    agentId = ports.ids().next();
    store.tx(
        tx -> {
          Agent a = new Agent();
          a.id = agentId;
          a.name = "demo-support";
          a.createdAt = now;
          tx.agents().insert(a);
          return null;
        });
  }

  private ShopResponse send(ShopRequest r) throws ShopTransportException {
    Map<String, String> h = new HashMap<>(r.headers());
    h.remove(ShopfrontApi.TIMEOUT_HEADER);
    ShopRequest clean = new ShopRequest(r.method(), r.path(), h, r.body());
    boolean write = !r.method().equals("GET");
    if (!write && readsDown) {
      throw new ShopTransportException(ShopTransportException.Kind.CONNECT_FAILED, "reads down");
    }
    if (!write && beforeNextRead != null) {
      Runnable r0 = beforeNextRead;
      beforeNextRead = null;
      r0.run();
    }
    Fault f = write ? faults.poll() : null;
    if (f != null) {
      switch (f) {
        case DROP_RESPONSE -> {
          deliver(clean);
          throw new ShopTransportException(ShopTransportException.Kind.TIMEOUT, "response lost");
        }
        case TIMEOUT_NOT_SENT ->
            throw new ShopTransportException(ShopTransportException.Kind.TIMEOUT, "never arrived");
        case ERROR_5XX -> {
          return new ShopResponse(
              503, Map.of(), "{\"error\":{\"code\":\"service_unavailable\"}}".getBytes());
        }
        case RATE_LIMIT -> {
          return new ShopResponse(
              429,
              Map.of("Retry-After", "1"),
              "{\"error\":{\"code\":\"rate_limited\"}}".getBytes());
        }
        case HOLD -> {
          held.add(clean);
          throw new ShopTransportException(ShopTransportException.Kind.TIMEOUT, "held");
        }
      }
    }
    return deliver(clean);
  }

  private ShopResponse deliver(ShopRequest r) {
    dev.shopfront.core.router.ShopResponse s =
        shop.handle(
            new dev.shopfront.core.router.ShopRequest(r.method(), r.path(), r.headers(), r.body()));
    return new ShopResponse(s.status(), s.headers(), s.body());
  }

  /** A late request finally reaches the provider (still within assumption A1). */
  void deliverHeld() {
    for (ShopRequest r : held) {
      deliver(r);
    }
    held.clear();
  }

  JsonNode shopAdmin(String method, String path, String body) {
    return Json.parse(
        shop.handle(
                dev.shopfront.core.router.ShopRequest.of(
                    method, path, Map.of("X-Admin-Token", "adm"), body))
            .body());
  }

  JsonNode shopGet(String path) {
    return Json.parse(
        shop.handle(
                dev.shopfront.core.router.ShopRequest.of(
                    "GET", path, Map.of("Authorization", "Bearer k"), null))
            .body());
  }

  Run run() {
    return run(null, null);
  }

  Run run(Long moneyCap, Integer actionCap) {
    return runs.create(
        new RunService.CreateRun(
            agentId,
            RunKind.EXTERNAL,
            "test run",
            null,
            null,
            null,
            "support-default",
            moneyCap,
            actionCap,
            null,
            null));
  }

  static final Caller API = Caller.api("api-1", "demo-support", true);
  static final Caller QUEUED = Caller.api("api-1", "demo-support", false);

  ProposalService.Outcome propose(Run run, String tool, String args) {
    return proposals.propose(ProposalService.Request.primary(API, run.id, tool, Json.parse(args)));
  }

  ProposalService.Outcome propose(ProposalService.Request r) {
    return proposals.propose(r);
  }

  static Action action(ProposalService.Outcome o) {
    if (o instanceof ProposalService.Outcome.Recorded r) {
      return r.action();
    }
    throw new AssertionError("not recorded: " + o);
  }

  Action reload(Action a) {
    return store.tx(tx -> tx.actions().get(a.id)).orElseThrow();
  }

  Run reload(Run r) {
    return store.tx(tx -> tx.runs().get(r.id)).orElseThrow();
  }

  void advance(Duration d) {
    now = now.plus(d);
  }

  /** Worker loop iterations until nothing more happens (or a limit). */
  void work(String node) {
    for (int i = 0; i < 50; i++) {
      int n = recovery.sweepExpiredLeases(node, 100) + recovery.resolveUnknownBatch(node, 20);
      List<ExecutionService.Claimed> claimed = execution.claimBatch(node, 20);
      for (ExecutionService.Claimed c : claimed) {
        execution.executeClaimed(c);
      }
      if (n + claimed.size() == 0) {
        return;
      }
    }
  }

  /** Runs the worker loops, advancing time 1 s per round, until no work is in flight. */
  void settle(String node) {
    for (int i = 0; i < 600; i++) {
      work(node);
      if (store.tx(tx -> tx.actions().countInFlight()) == 0) {
        return;
      }
      advance(Duration.ofSeconds(1));
    }
  }

  long refundsFor(UUID actionId) {
    return shopGet("/v1/refunds?metadata.undo_action_id=" + actionId).path("data").size();
  }

  static String refundArgs(String ticket, String order, long amount) {
    return "{\"ticket_id\":"
        + (ticket == null ? "null" : "\"" + ticket + "\"")
        + ",\"order_id\":\""
        + order
        + "\",\"amount_minor\":"
        + amount
        + ",\"reason\":\"damaged\"}";
  }

  /** The ticket's own order and its refundable amount. */
  String[] ownOrder(String ticketId) {
    JsonNode t = shopGet("/v1/tickets/" + ticketId);
    String order = t.get("order_id").asText();
    long refundable = shopGet("/v1/orders/" + order).get("refundable_minor").asLong();
    return new String[] {order, Long.toString(refundable), t.get("customer_id").asText()};
  }

  /** A damaged ticket whose order's refundable amount is at most ₹10,000 (auto-allowed). */
  String smallDamagedTicket(int skip) {
    for (JsonNode t : shopAdmin("GET", "/admin/ground-truth", null)) {
      if (t.get("category").asText().equals("damaged") && t.get("attack").isNull()) {
        String[] o = ownOrder(t.get("ticket_id").asText());
        if (Long.parseLong(o[1]) <= 1_000_000 && skip-- == 0) {
          return t.get("ticket_id").asText();
        }
      }
    }
    throw new AssertionError("no small damaged ticket");
  }

  static final UUID REVERTER = UUID.fromString("00000000-0000-7000-8000-00000000beef");

  RevertPlan preview(Run run) {
    return reverts.preview(reload(run).number, PlanScope.RUN, null, REVERTER, "user:reverter");
  }

  RevertPlan plan(RevertPlan p) {
    return store.tx(tx -> tx.reverts().plan(p.id)).orElseThrow();
  }

  /** Runs revert steps and the other worker loops until the plan ends (or a limit). */
  RevertPlan runRevert(RevertPlan plan, String node) {
    for (int i = 0; i < 400; i++) {
      reverts.stepAll(node, 10);
      work(node);
      RevertPlan cur = plan(plan);
      if (cur.status != PlanStatus.EXECUTING) {
        return cur;
      }
      advance(Duration.ofSeconds(1));
    }
    throw new AssertionError("revert did not finish");
  }

  RevertPlan revertAll(Run run, String node) {
    RevertPlan p = preview(run);
    reverts.execute(p.number, reload(run).number, "user:reverter");
    return runRevert(p, node);
  }

  /** Shopfront's settlement job with failure rate 1: every pending refund fails. */
  void failPendingRefunds() {
    dev.shopfront.core.faults.FaultConfig failAll =
        dev.shopfront.core.faults.FaultConfig.CALM.merge(
            Json.parse("{\"settlement_failure_rate\":1}"));
    ShopRandom r =
        new ShopRandom() {
          public double nextDouble() {
            return rnd.nextDouble();
          }

          public int nextInt(int b) {
            return rnd.nextInt(b);
          }

          public long nextLong() {
            return rnd.nextLong();
          }
        };
    shopStore.tx(
        tx ->
            dev.shopfront.core.settlement.Settlement.runDue(
                tx, r, now.plus(Duration.ofDays(30)), failAll));
  }
}
