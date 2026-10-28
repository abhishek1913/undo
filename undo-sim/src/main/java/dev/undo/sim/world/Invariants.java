package dev.undo.sim.world;

import dev.shopfront.core.domain.Email;
import dev.shopfront.core.domain.Refund;
import dev.shopfront.core.domain.StoreCredit;
import dev.undo.core.connector.IdempotencyClass;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ActionTransition;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.BudgetState;
import dev.undo.core.model.Decision;
import dev.undo.core.model.ItemStatus;
import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.PlanScope;
import dev.undo.core.model.PlanStatus;
import dev.undo.core.model.Posting;
import dev.undo.core.model.RevertItem;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.sim.store.InMemoryStore;
import dev.undo.sim.store.Snapshot;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The invariants of Section 21.5 (so far: 1, 3, 4, 5, 6, 7, 8, 10, 12, 13). */
final class Invariants {
  static final Set<String> EXPECTED_VIOLATIONS =
      Set.of("actions_agent_call", "actions_client_key", "actions_dedupe");
  static final Set<ActionStatus> NO_EFFECT =
      EnumSet.of(
          ActionStatus.FAILED,
          ActionStatus.DENIED,
          ActionStatus.REJECTED,
          ActionStatus.EXPIRED,
          ActionStatus.CANCELLED);
  static final Set<ActionStatus> IN_FLIGHT =
      EnumSet.of(
          ActionStatus.EXECUTING,
          ActionStatus.APPROVED,
          ActionStatus.RETRY_WAIT,
          ActionStatus.UNKNOWN);

  private final World w;

  Invariants(World w) {
    this.w = w;
  }

  void fail(String inv, String msg) {
    w.violation(inv, msg);
  }

  /**
   * Per run: [money of SUCCEEDED PRIMARY actions, money of RESERVED actions], kept incrementally.
   */
  private final Map<UUID, long[]> sums = new HashMap<>();

  private static long succeeded(Action a) {
    return a != null && a.kind == ActionKind.PRIMARY && a.status == ActionStatus.SUCCEEDED
        ? a.moneyOutMinor
        : 0;
  }

  private static long reserved(Action a) {
    return a != null && a.budgetState == BudgetState.RESERVED ? a.moneyOutMinor : 0;
  }

  /** Cheap checks after every commit: INV-3 for the runs that changed, INV-7 edges. */
  void afterCommit(InMemoryStore.CommitInfo info) {
    Snapshot s = w.store.snapshot();
    Set<UUID> runs = new java.util.LinkedHashSet<>(info.runIds());
    for (Action[] ch : info.actionChanges()) {
      Action after = ch[1];
      long[] sum = sums.computeIfAbsent(after.runId, k -> new long[2]);
      sum[0] += succeeded(after) - succeeded(ch[0]);
      sum[1] += reserved(after) - reserved(ch[0]);
      runs.add(after.runId);
    }
    for (UUID runId : runs) {
      Run run = s.run(runId);
      long[] sum = sums.getOrDefault(runId, new long[2]);
      if (run != null
          && (sum[0] != run.moneySpentMinor
              || sum[1] != run.moneyReservedMinor
              || run.moneyReservedMinor + run.moneySpentMinor > run.moneyCapMinor)) {
        checkBudget(s, run);
      }
    }
    for (ActionTransition t : info.transitions()) {
      w.coverage.transition(t.fromStatus, t.toStatus);
      if (t.fromStatus != null && !t.fromStatus.canMoveTo(t.toStatus)) {
        fail(
            "INV-7",
            "illegal transition "
                + t.fromStatus
                + " -> "
                + t.toStatus
                + " for action "
                + t.actionId);
      }
    }
  }

  /** INV-3: reserved + spent ≤ cap; spent = Σ money_out of SUCCEEDED PRIMARY actions. */
  void checkBudget(Snapshot s, Run run) {
    if (run == null) {
      return;
    }
    if (run.moneyReservedMinor + run.moneySpentMinor > run.moneyCapMinor) {
      fail(
          "INV-3",
          "run "
              + run.number
              + " reserved+spent "
              + (run.moneyReservedMinor + run.moneySpentMinor)
              + " > cap "
              + run.moneyCapMinor);
    }
    long spent = 0;
    long reserved = 0;
    for (Action a : w.actionsOfRun(run.id)) {
      if (a.kind == ActionKind.PRIMARY && a.status == ActionStatus.SUCCEEDED) {
        spent += a.moneyOutMinor;
      }
      if (a.budgetState == BudgetState.RESERVED) {
        reserved += a.moneyOutMinor;
      }
    }
    if (spent != run.moneySpentMinor) {
      fail(
          "INV-3",
          "run "
              + run.number
              + " spent "
              + run.moneySpentMinor
              + " but SUCCEEDED PRIMARY actions sum to "
              + spent);
    }
    if (reserved != run.moneyReservedMinor) {
      fail(
          "INV-3",
          "run "
              + run.number
              + " reserved "
              + run.moneyReservedMinor
              + " but RESERVED actions sum to "
              + reserved);
    }
  }

  void finalChecks() {
    Snapshot s = w.store.snapshot();
    Map<UUID, Action> actions = new HashMap<>();
    for (Action a : s.actions()) {
      actions.put(a.id, a);
    }
    List<Refund> refunds = w.shop.store.tx(tx -> tx.refunds());
    List<StoreCredit> credits = w.shop.store.tx(tx -> tx.credits());
    List<Email> emails = w.shop.store.tx(tx -> tx.emails());

    // INV-1: at most one provider effect per undo_action_id.
    Map<String, Integer> perAction = new HashMap<>();
    Map<String, String> objectOf = new HashMap<>();
    for (Refund r : refunds) {
      if (r.undoActionId() != null) {
        perAction.merge("refund:" + r.undoActionId(), 1, Integer::sum);
        objectOf.put(r.undoActionId(), r.id());
      }
    }
    for (StoreCredit c : credits) {
      if (c.reference() != null && c.reference().startsWith("undo_")) {
        String id = c.reference().substring(5);
        perAction.merge("credit:" + id, 1, Integer::sum);
        objectOf.put(id, c.id());
      }
    }
    for (Email e : emails) {
      if (e.undoActionId() != null) {
        perAction.merge("email:" + e.undoActionId(), 1, Integer::sum);
        objectOf.put(e.undoActionId(), e.id());
      }
    }
    perAction.forEach(
        (k, n) -> {
          if (n > 1) {
            Action a = actions.get(UUID.fromString(k.substring(k.indexOf(':') + 1)));
            fail(
                "INV-1",
                n
                    + " provider effects for "
                    + k
                    + (a == null
                        ? ""
                        : " (" + a.display() + " " + a.tool + " " + a.idempotencyClass + ")"));
          }
        });

    // INV-4: every provider effect was allowed, approved with the exact hash, or a compensation.
    for (String undoId : objectOf.keySet()) {
      Action a;
      try {
        a = actions.get(UUID.fromString(undoId));
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (a == null) {
        fail(
            "INV-4",
            "provider object "
                + objectOf.get(undoId)
                + " carries unknown undo_action_id "
                + undoId);
        continue;
      }
      boolean ok = a.decision == Decision.ALLOW && a.kind != ActionKind.REMEDIATION;
      if (!ok && a.kind == ActionKind.COMPENSATION && a.revertPlanId != null) {
        ok = true;
      }
      if (!ok) {
        Approval ap = s.approvalOf(a.id);
        ok =
            ap != null
                && ap.status == ApprovalStatus.APPROVED
                && a.argsHash.equals(ap.approvedArgsHash);
      }
      if (!ok) {
        fail(
            "INV-4",
            "effect "
                + objectOf.get(undoId)
                + " of "
                + a.display()
                + " ("
                + a.tool
                + ") was neither allowed nor approved with its exact args hash");
      }
    }

    // INV-3 for every run, at the end too.
    for (Run run : s.runs()) {
      checkBudget(s, run);
    }

    // INV-5: entries balance; (action_id, kind) unique.
    Map<UUID, Long> sums = new HashMap<>();
    for (Posting p : s.postings()) {
      sums.merge(p.entryId, p.amountMinor, Long::sum);
    }
    Set<String> kinds = new HashSet<>();
    for (JournalEntry e : s.entries()) {
      if (sums.getOrDefault(e.id, 0L) != 0) {
        fail(
            "INV-5",
            "journal entry " + e.kind + " of action " + e.actionId + " sums to " + sums.get(e.id));
      }
      if (!kinds.add(e.actionId + ":" + e.kind)) {
        fail("INV-5", "journal entry " + e.kind + " posted twice for action " + e.actionId);
      }
    }

    checkReverts(s, actions, refunds, credits);

    // INV-7: legal transitions (recorded every commit) and the PRIMARY execution guard.
    for (String g : w.store.guardViolations) {
      fail("INV-7", g);
    }

    // INV-8: after quiescence nothing is in flight; NEEDS_REVIEW only where expected, listed.
    for (Action a : s.actions()) {
      if (IN_FLIGHT.contains(a.status)) {
        fail("INV-8", a.display() + " " + a.tool + " is still " + a.status + " after quiescence");
      } else if (a.status == ActionStatus.NEEDS_REVIEW) {
        boolean expected =
            a.idempotencyClass == IdempotencyClass.NONE || "max_unknown_age".equals(a.statusReason);
        if (expected) {
          w.needsReview.add(a.display() + " " + a.tool + " (" + a.statusReason + ")");
        } else {
          fail(
              "INV-8",
              a.display()
                  + " "
                  + a.tool
                  + " is NEEDS_REVIEW for an unexpected reason: "
                  + a.statusReason);
        }
      }
    }

    // INV-10: no stale-fence write was ever applied.
    for (String st : w.store.staleWritesApplied) {
      fail("INV-10", st);
    }

    // INV-12: an INTERNAL tool never runs as a PRIMARY action (such proposals are recorded DENIED).
    for (Action a : s.actions()) {
      ToolSpec t = w.registryTool(a.tool);
      if (t != null
          && t.visibility() == Visibility.INTERNAL
          && a.kind == ActionKind.PRIMARY
          && a.status != ActionStatus.DENIED) {
        fail(
            "INV-12",
            a.display()
                + " "
                + a.tool
                + " is a PRIMARY action of an INTERNAL tool in status "
                + a.status);
      }
    }

    // INV-2: every SUCCEEDED PRIMARY action of a reconciled tool has exactly one provider object;
    // every FAILED/DENIED/REJECTED/EXPIRED/CANCELLED one has none.
    for (Action a : s.actions()) {
      ToolSpec t = w.registryTool(a.tool);
      if (a.kind != ActionKind.PRIMARY || t == null || t.providerObjectType().isEmpty()) {
        continue;
      }
      String key = KEY_PREFIX.get(t.providerObjectType().get()) + a.id;
      int n = perAction.getOrDefault(key, 0);
      if (a.status == ActionStatus.SUCCEEDED && n != 1) {
        fail("INV-2", a.display() + " " + a.tool + " SUCCEEDED with " + n + " provider objects");
      } else if (NO_EFFECT.contains(a.status) && n != 0) {
        fail(
            "INV-2",
            a.display() + " " + a.tool + " is " + a.status + " but has " + n + " provider objects");
      }
    }

    checkFinalRecon(s, actions, refunds, credits);
    checkRunner(s);

    // INV-13: no constraint violation except the ones the engine expects and handles.
    for (String c : w.store.violations) {
      if (!EXPECTED_VIOLATIONS.contains(c)) {
        fail("INV-13", "constraint " + c + " was violated");
      }
    }
  }

  private static final Map<String, String> KEY_PREFIX =
      Map.of("refund", "refund:", "store_credit", "credit:", "email", "email:");

  /**
   * INV-11: no tool_use id produced more than one action; a task never has more actions than its
   * persisted turns have tool_use blocks; every tool_use of a DONE task has exactly one
   * tool_result, in tool_use order.
   */
  private void checkRunner(Snapshot s) {
    Map<String, Integer> perCall = new HashMap<>();
    Map<UUID, Integer> perTask = new HashMap<>();
    for (Action a : s.actions()) {
      if (a.taskId == null || a.kind != ActionKind.PRIMARY) {
        continue;
      }
      perTask.merge(a.taskId, 1, Integer::sum);
      if (a.agentCallId != null) {
        perCall.merge(a.runId + ":" + a.agentCallId, 1, Integer::sum);
      }
    }
    perCall.forEach(
        (k, n) -> {
          if (n > 1) {
            fail("INV-11", "tool_use " + k + " produced " + n + " actions");
          }
        });
    Map<UUID, Integer> uses = new HashMap<>();
    for (var t : s.turns()) {
      List<com.fasterxml.jackson.databind.JsonNode> tu =
          dev.undo.core.agent.Transcript.toolUses(t.response);
      uses.merge(t.taskId, tu.size(), Integer::sum);
      var task = s.tasks().stream().filter(x -> x.id.equals(t.taskId)).findFirst().orElse(null);
      if (task == null || task.status != dev.undo.core.model.TaskStatus.DONE || tu.isEmpty()) {
        continue;
      }
      boolean ok = t.toolResults != null && t.toolResults.size() == tu.size();
      for (int i = 0; ok && i < tu.size(); i++) {
        ok =
            t.toolResults.get(i).path("tool_use_id").asText().equals(tu.get(i).path("id").asText());
      }
      if (!ok) {
        fail(
            "INV-11",
            "task "
                + task.seq
                + " turn "
                + t.turnNo
                + " has "
                + tu.size()
                + " tool_use blocks but tool_results "
                + t.toolResults);
      }
    }
    perTask.forEach(
        (taskId, n) -> {
          int u = uses.getOrDefault(taskId, 0);
          if (n > u) {
            fail("INV-11", "a task has " + n + " actions but only " + u + " tool_use blocks");
          }
        });
  }

  /**
   * INV-9: the final reconciliation completed; no CRITICAL finding is open; every provider-side
   * status change Undo missed (dropped webhooks) is applied or reported.
   */
  private void checkFinalRecon(
      Snapshot s, Map<UUID, Action> actions, List<Refund> refunds, List<StoreCredit> credits) {
    var fr = w.finalRecon;
    if (fr == null || fr.status != dev.undo.core.model.ReconRunStatus.COMPLETED) {
      fail(
          "INV-9",
          "the final reconciliation did not complete" + (fr == null ? "" : " (" + fr.stats + ")"));
      return;
    }
    if (fr.stats.path("errors").asInt() > 0) {
      fail("INV-9", "the final reconciliation had " + fr.stats.path("errors").asInt() + " errors");
    }
    Set<UUID> reported = new HashSet<>();
    for (var f : s.findings()) {
      if (f.severity == dev.undo.core.model.Severity.CRITICAL
          && (f.status == dev.undo.core.model.FindingStatus.OPEN
              || f.status == dev.undo.core.model.FindingStatus.ACKNOWLEDGED)) {
        Action a = f.actionId == null ? null : actions.get(f.actionId);
        fail(
            "INV-9",
            "CRITICAL "
                + f.type
                + " after the final reconciliation: "
                + (a == null ? f.providerObjectRef : a.display() + " " + a.tool + " " + a.status)
                + " expected "
                + f.expected
                + " actual "
                + f.actual);
      }
      if (f.actionId != null) {
        reported.add(f.actionId);
      }
    }
    Map<String, String> providerStatus = new HashMap<>();
    for (Refund r : refunds) {
      if (r.undoActionId() != null) {
        providerStatus.put(r.undoActionId(), r.status());
      }
    }
    for (StoreCredit c : credits) {
      if (c.reference() != null && c.reference().startsWith("undo_")) {
        providerStatus.put(c.reference().substring(5), c.status());
      }
    }
    providerStatus.forEach(
        (id, st) -> {
          Action a;
          try {
            a = actions.get(UUID.fromString(id));
          } catch (IllegalArgumentException e) {
            return;
          }
          if (a != null
              && a.kind == ActionKind.PRIMARY
              && a.status == ActionStatus.SUCCEEDED
              && !st.equals(a.providerStatus)
              && !reported.contains(a.id)) {
            fail(
                "INV-9",
                a.display()
                    + " "
                    + a.tool
                    + " has provider_status "
                    + a.providerStatus
                    + " but the provider says "
                    + st
                    + " (neither applied nor reported)");
          }
        });
  }

  /**
   * INV-6: REVERTED ⇒ net expense:* postings of the plan's scope are 0, every compensated refund is
   * cancelled and every compensated credit revoked at the provider. PARTIALLY_REVERTED ⇒ net
   * exposure = Σ money_not_recovered. Also INV-7 for plans: an ended plan went through EXECUTING,
   * and a RUN plan's run ends in the same status.
   */
  private void checkReverts(
      Snapshot s, Map<UUID, Action> actions, List<Refund> refunds, List<StoreCredit> credits) {
    Map<UUID, JournalEntry> entries = new HashMap<>();
    for (JournalEntry e : s.entries()) {
      entries.put(e.id, e);
    }
    Map<String, String> refundStatus = new HashMap<>();
    for (Refund r : refunds) {
      if (r.undoActionId() != null) {
        refundStatus.put(r.undoActionId(), r.status());
      }
    }
    Map<String, String> creditStatus = new HashMap<>();
    for (StoreCredit c : credits) {
      if (c.reference() != null && c.reference().startsWith("undo_")) {
        creditStatus.put(c.reference().substring(5), c.status());
      }
    }
    for (RevertPlan pl : s.plans()) {
      if (pl.status != PlanStatus.REVERTED && pl.status != PlanStatus.PARTIALLY_REVERTED) {
        continue;
      }
      if (pl.startedAt == null) {
        fail("INV-7", "plan " + pl.number + " is " + pl.status + " but never executed");
      }
      Run run = s.run(pl.runId);
      if (pl.scope == PlanScope.RUN && run.status != RunStatus.valueOf(pl.status.name())) {
        fail("INV-7", "plan " + pl.number + " is " + pl.status + " but its run is " + run.status);
      }
      List<RevertItem> items = s.items().stream().filter(i -> i.planId.equals(pl.id)).toList();
      Set<UUID> targets = new HashSet<>();
      long notRecovered = 0;
      for (RevertItem it : items) {
        targets.add(it.targetActionId);
        notRecovered += it.moneyNotRecoveredMinor;
      }
      long exposure = 0;
      for (Posting p : s.postings()) {
        JournalEntry e = entries.get(p.entryId);
        boolean inScope =
            pl.scope == PlanScope.RUN ? e.runId.equals(pl.runId) : targets.contains(e.actionId);
        if (inScope && p.account.startsWith("expense:")) {
          exposure += p.amountMinor;
        }
      }
      if (pl.status == PlanStatus.REVERTED) {
        if (exposure != 0) {
          fail("INV-6", "plan " + pl.number + " is REVERTED but net exposure is " + exposure);
        }
        for (RevertItem it : items) {
          Action t = actions.get(it.targetActionId);
          if (it.status != ItemStatus.DONE || t.compensatedByActionId == null) {
            continue;
          }
          String id = t.id.toString();
          if (t.tool.equals("shop_issue_refund") && !"cancelled".equals(refundStatus.get(id))) {
            fail(
                "INV-6",
                "plan "
                    + pl.number
                    + " compensated "
                    + t.display()
                    + " but the refund is "
                    + refundStatus.get(id));
          }
          if (t.tool.equals("shop_grant_store_credit") && !"revoked".equals(creditStatus.get(id))) {
            fail(
                "INV-6",
                "plan "
                    + pl.number
                    + " compensated "
                    + t.display()
                    + " but the credit is "
                    + creditStatus.get(id));
          }
        }
      } else if (exposure != notRecovered) {
        fail(
            "INV-6",
            "plan "
                + pl.number
                + " is PARTIALLY_REVERTED with net exposure "
                + exposure
                + " but money not recovered "
                + notRecovered);
      }
    }
  }
}
