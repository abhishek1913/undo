package dev.undo.sim.world;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.connector.Effect;
import dev.undo.core.engine.UndoException;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.PlanScope;
import dev.undo.core.model.PlanStatus;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * The humans: approve or reject approvals (sometimes with a stale or wrong hash), freeze runs, edit
 * order notes, spend store credit, and resolve NEEDS_REVIEW actions from provider truth.
 */
final class SimOperator {
  private final World w;
  private final SplittableRandom r;
  private final Set<UUID> seenApprovals = new HashSet<>();
  private final Set<UUID> seenReviews = new HashSet<>();
  private final List<Approval> forgotten = new ArrayList<>();
  int pendingDecisions;

  /** Revert requests not yet started (chaos lasts until they have started, 21.4). */
  int revertsWaiting;

  static final UUID OPERATOR = UUID.fromString("00000000-0000-7000-8000-0000000000ff");
  private boolean finishing;

  SimOperator(World w, SplittableRandom r) {
    this.w = w;
    this.r = r;
  }

  void start() {
    w.loop.after(WorldParams.s(5), "operator approvals", this::approvalsTick);
    w.loop.after(WorldParams.s(30), "operator reviews", this::reviewsTick);
    if (w.params.noteEdits()) {
      w.loop.after(
          WorldParams.s(60) + (long) (r.nextDouble() * WorldParams.s(1800)),
          "operator note edit",
          this::editNote);
    }
    if (r.nextDouble() < 0.5) {
      w.loop.after(
          WorldParams.s(60) + (long) (r.nextDouble() * WorldParams.s(3600)),
          "operator recon",
          () ->
              w.sched.spawn(
                  "operator recon",
                  w.immortal,
                  () -> {
                    if (!w.chaos) {
                      return;
                    }
                    try {
                      rpc(
                          wk ->
                              wk.recon.runNow(
                                  dev.undo.connector.shopfront.ShopfrontConnector.ID,
                                  "user:operator",
                                  wk.node));
                    } catch (UndoException e) {
                      // RECON_RUNNING: a scheduled run holds the lease
                    }
                  }));
    }
    if (w.params.creditSpends()) {
      w.loop.after(
          WorldParams.s(120) + (long) (r.nextDouble() * WorldParams.s(1800)),
          "operator credit spend",
          this::spendCredit);
    }
  }

  /** Quiescence: decide everything that is still open, quickly. */
  void finish() {
    finishing = true;
  }

  void onRunCreated(Run run) {
    if (r.nextDouble() < w.params.freezeRate()) {
      long at = WorldParams.s(5) + (long) (r.nextDouble() * WorldParams.s(1200));
      pendingDecisions++;
      w.loop.after(
          at,
          "operator freeze " + run.number,
          () ->
              w.sched.spawn(
                  "freeze run " + run.number,
                  w.immortal,
                  () -> {
                    try {
                      if (w.chaos) {
                        rpc(wk -> wk.runs.freeze(run.id, "user:operator"));
                      }
                    } catch (UndoException e) {
                      // RUN_NOT_ACTIVE: already completed
                    } finally {
                      pendingDecisions--;
                    }
                  }));
    }
  }

  /**
   * With p = revert rate, asks for a revert (RUN, or ACTIONS with a random subset) some time after
   * the run ended, possibly while its approvals are still pending.
   */
  void onRunEnded(Run run) {
    if (r.nextDouble() >= w.params.revertRate()) {
      return;
    }
    boolean actionsScope = r.nextDouble() < 0.3;
    long subsetSeed = r.nextLong();
    long at = WorldParams.s(5) + (long) (r.nextDouble() * WorldParams.s(600));
    pendingDecisions++;
    revertsWaiting++;
    w.loop.after(
        at,
        "operator reverts " + run.number,
        () ->
            w.sched.spawn(
                "revert run " + run.number,
                w.immortal,
                () -> requestRevert(run, actionsScope, subsetSeed, false)));
  }

  /**
   * One revert request. {@code followUp}: a RUN revert asked after an earlier ACTIONS plan, which
   * waits for that plan to end and then finds its targets ALREADY_REVERTED.
   */
  private void requestRevert(Run run, boolean actionsScope, long subsetSeed, boolean followUp) {
    boolean waiting = true;
    try {
      PlanScope scope = PlanScope.RUN;
      List<Long> numbers = null;
      if (actionsScope) {
        SplittableRandom sub = new SplittableRandom(subsetSeed);
        List<Long> picked = new ArrayList<>();
        for (Action a : w.actionsOfRun(run.id)) {
          if (a.kind == ActionKind.PRIMARY && a.effect == Effect.WRITE && sub.nextBoolean()) {
            picked.add(a.number);
          }
        }
        if (!picked.isEmpty()) {
          scope = PlanScope.ACTIONS;
          numbers = picked;
        }
      }
      PlanScope sc = scope;
      List<Long> nums = numbers;
      for (int attempt = 0; attempt < 40; attempt++) {
        RevertPlan plan;
        try {
          plan = rpc(wk -> wk.reverts.preview(run.number, sc, nums, OPERATOR, "user:operator"));
        } catch (UndoException e) {
          return; // ALREADY_REVERTED, REVERT_IN_PROGRESS, or a target that is not revertible
        }
        if (plan.status != PlanStatus.PREVIEW) {
          if (!followUp) {
            return; // another plan of this run is already executing
          }
          w.sched.sleep(WorldParams.s(60), "operator waits for the earlier plan");
          continue;
        }
        w.sched.sleep(
            WorldParams.s(2) + (long) (r.nextDouble() * WorldParams.s(60)), "operator reads");
        try {
          long planNumber = plan.number;
          rpc(wk -> wk.reverts.execute(planNumber, run.number, "user:operator"));
          w.revertsStarted++;
          if (sc == PlanScope.ACTIONS && !followUp && r.nextDouble() < 0.5) {
            pendingDecisions++;
            w.loop.after(
                WorldParams.s(30) + (long) (r.nextDouble() * WorldParams.s(600)),
                "operator reverts the rest of " + run.number,
                () ->
                    w.sched.spawn(
                        "revert rest of run " + run.number,
                        w.immortal,
                        () -> requestRevert(run, false, 0, true)));
          }
          return;
        } catch (UndoException e) {
          switch (e.code()) {
            case REVERT_BLOCKED -> {
              if (waiting) {
                waiting = false;
                revertsWaiting--; // a blocked revert may wait for quiescence
              }
              w.sched.sleep(WorldParams.s(60), "operator waits for in-flight actions");
              long planNumber = plan.number;
              try {
                rpc(wk -> wk.reverts.refresh(planNumber, "user:operator"));
              } catch (UndoException ignored) {
                // the plan expired or was cancelled; the next preview makes a new one
              }
            }
            case REVERT_PREVIEW_EXPIRED -> {
              // the next preview cancels the old one and makes a new one
            }
            default -> {
              return;
            }
          }
        }
      }
    } finally {
      if (waiting) {
        revertsWaiting--;
      }
      pendingDecisions--;
    }
  }

  private void approvalsTick() {
    if (w.ended) {
      return;
    }
    for (Approval ap : w.store.snapshot().approvals()) {
      if (ap.status != ApprovalStatus.PENDING || !seenApprovals.add(ap.id)) {
        continue;
      }
      if (!finishing && r.nextDouble() < w.params.forgetApprovalRate()) {
        forgotten.add(ap); // nobody looks at this card for a while; it may expire
        continue;
      }
      schedule(ap);
    }
    if (finishing && !forgotten.isEmpty()) {
      for (Approval ap : forgotten) {
        schedule(ap);
      }
      forgotten.clear();
    }
    w.loop.after(
        finishing
            ? WorldParams.s(2)
            : WorldParams.s(5) + (long) (r.nextDouble() * WorldParams.s(25)),
        "operator approvals",
        this::approvalsTick);
  }

  private void schedule(Approval ap) {
    boolean approve = r.nextDouble() < w.params.approveRate();
    boolean wrongHash = approve && r.nextDouble() < w.params.wrongHashRate();
    long delay =
        finishing
            ? WorldParams.s(1) + (long) (r.nextDouble() * WorldParams.s(60))
            : WorldParams.s(1) + (long) (r.nextDouble() * WorldParams.s(600));
    pendingDecisions++;
    UUID id = ap.id;
    String hash = ap.argsHash;
    w.loop.after(
        delay,
        "operator decides " + id,
        () ->
            w.sched.spawn("decide " + id, w.immortal, () -> decide(id, hash, approve, wrongHash)));
  }

  private void decide(UUID approvalId, String hash, boolean approve, boolean wrongHash) {
    try {
      if (approve) {
        if (wrongHash) {
          String bad = Long.toHexString(r.nextLong()) + hash.substring(16);
          try {
            rpc(
                wk ->
                    wk.approvals.approve(
                        approvalId, bad, null, "user:operator", "approve (stale card)"));
            w.wrongHashAccepted++;
          } catch (UndoException e) {
            // APPROVAL_HASH_MISMATCH is the expected answer; then approve what is shown now
          }
        }
        rpc(wk -> wk.approvals.approve(approvalId, hash, null, "user:operator", "approved"));
      } else {
        rpc(
            wk ->
                wk.approvals.reject(approvalId, null, "user:operator", "rejected by the operator"));
      }
    } catch (UndoException e) {
      // NOT_PENDING (cancelled by a freeze, expired, decided twice) or RUN_NOT_ACTIVE
    } finally {
      pendingDecisions--;
    }
  }

  private void reviewsTick() {
    if (w.ended) {
      return;
    }
    for (Action a : w.store.snapshot().actionsWithStatus(ActionStatus.NEEDS_REVIEW)) {
      if (!seenReviews.add(a.id)) {
        continue;
      }
      if (r.nextDouble() >= w.params.reviewRate()) {
        continue;
      }
      long delay =
          finishing
              ? WorldParams.s(60) + (long) (r.nextDouble() * WorldParams.s(240))
              : WorldParams.s(60) + (long) (r.nextDouble() * WorldParams.s(29 * 60));
      pendingDecisions++;
      UUID id = a.id;
      w.loop.after(
          delay,
          "operator reviews " + id,
          () -> w.sched.spawn("review " + id, w.immortal, () -> review(id)));
    }
    w.loop.after(
        finishing ? WorldParams.s(5) : WorldParams.s(30), "operator reviews", this::reviewsTick);
  }

  /** Resolves from provider truth, the way a careful human would check the provider page. */
  private void review(UUID actionId) {
    try {
      Action a = w.store.snapshot().action(actionId);
      if (a == null || a.status != ActionStatus.NEEDS_REVIEW) {
        return;
      }
      String ref = w.providerRefFor(a);
      try {
        rpc(
            wk ->
                wk.recovery.review(
                    actionId, ref != null, ref, null, "checked the provider", "user:operator"));
      } catch (UndoException e) {
        // already resolved
      }
    } finally {
      pendingDecisions--;
    }
  }

  private void editNote() {
    if (w.ended || !w.chaos) {
      return;
    }
    int n = 1 + r.nextInt(WorldParams.ORDERS);
    w.shop.admin(
        "PUT",
        String.format("/admin/orders/ORD-%04d/note", n),
        "{\"note\":\"edited by a person\"}");
    w.loop.after(
        WorldParams.s(300) + (long) (r.nextDouble() * WorldParams.s(1200)),
        "operator note edit",
        this::editNote);
  }

  private void spendCredit() {
    if (w.ended || !w.chaos) {
      return;
    }
    List<JsonNode> active = new ArrayList<>();
    for (var c : w.shop.store.tx(tx -> tx.credits())) {
      if (c.status().equals("active") && c.balanceMinor() > 0) {
        active.add(
            dev.shopfront.core.json.Json.parse(
                ("{\"id\":\"" + c.id() + "\",\"balance\":" + c.balanceMinor() + "}").getBytes()));
      }
    }
    if (!active.isEmpty()) {
      JsonNode c = active.get(r.nextInt(active.size()));
      long amount = 1 + r.nextInt((int) Math.min(Integer.MAX_VALUE, c.get("balance").asLong()));
      w.shop.admin(
          "POST",
          "/admin/store-credits/" + c.get("id").asText() + "/spend",
          "{\"amount_minor\":" + amount + "}");
    }
    w.loop.after(
        WorldParams.s(300) + (long) (r.nextDouble() * WorldParams.s(1200)),
        "operator credit spend",
        this::spendCredit);
  }

  interface WorkerCall<T> {
    T apply(SimWorker wk);
  }

  private <T> T rpc(WorkerCall<T> f) {
    while (true) {
      SimWorker wk = w.randomLiveWorker(r);
      if (wk == null) {
        w.sched.sleep(WorldParams.s(2), "operator waits for a node");
        continue;
      }
      try {
        return w.sched.call(wk.owner, "operator on " + wk.node, () -> f.apply(wk));
      } catch (CoopScheduler.RpcFailed e) {
        w.sched.sleep(WorldParams.s(2), "operator retry");
      }
    }
  }

  boolean idle() {
    if (pendingDecisions > 0) {
      return false;
    }
    for (Approval ap : w.store.snapshot().approvals()) {
      if (ap.status == ApprovalStatus.PENDING) {
        Run run = w.store.snapshot().run(ap.runId);
        if (run != null && run.status != RunStatus.REVERTED) {
          return false;
        }
      }
    }
    return true;
  }
}
