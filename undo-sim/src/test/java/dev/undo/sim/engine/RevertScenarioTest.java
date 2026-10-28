package dev.undo.sim.engine;

import static dev.undo.sim.engine.Harness.action;
import static dev.undo.sim.engine.Harness.refundArgs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.ledger.Ledger;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.ItemKind;
import dev.undo.core.model.ItemStatus;
import dev.undo.core.model.PlanScope;
import dev.undo.core.model.PlanStatus;
import dev.undo.core.model.RevertItem;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.core.port.Bug;
import dev.undo.core.port.CrashPoint;
import dev.undo.sim.SimulatedCrash;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Revert (Section 15) on the in-memory engine with the real connector and Shopfront router. */
class RevertScenarioTest {
  final Harness h = new Harness();

  {
    h.shopAdmin(
        "POST", "/admin/seed", "{\"seed\":42,\"customers\":60,\"orders\":400,\"tickets\":200}");
  }

  private Action refund(Run run, int skip) {
    String ticket = h.smallDamagedTicket(skip);
    String[] o = h.ownOrder(ticket);
    long amount = Math.min(Long.parseLong(o[1]), 250_000);
    Action a = action(h.propose(run, "shop_issue_refund", refundArgs(ticket, o[0], amount)));
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.SUCCEEDED);
    return h.reload(a);
  }

  private List<RevertItem> items(RevertPlan p) {
    return h.store.tx(tx -> tx.reverts().items(p.id));
  }

  private RevertItem itemFor(RevertPlan p, Action target) {
    return items(p).stream()
        .filter(i -> i.targetActionId.equals(target.id))
        .findFirst()
        .orElseThrow();
  }

  private long exposure(Run run) {
    return Ledger.expenseExposure(h.store.tx(tx -> tx.ledger().postingsByRun(run.id)));
  }

  private String refundStatus(Action a) {
    return h.shopGet("/v1/refunds/" + a.providerRef).path("status").asText();
  }

  @Test
  void revertCancelsPendingRefundAndReportsSettledAsIrreversible() {
    Run run = h.run();
    Action first = refund(run, 0);
    Action second = refund(run, 1);
    h.runs.complete(run.id, "agent");
    // The oldest pending refund settles at the provider; no webhook reaches Undo.
    h.shopAdmin("POST", "/admin/settlement/run?limit=1", null);
    assertThat(refundStatus(first)).isEqualTo("settled");

    RevertPlan p = h.preview(run);
    assertThat(itemFor(p, first).kind).isEqualTo(ItemKind.IRREVERSIBLE);
    assertThat(itemFor(p, second).kind).isEqualTo(ItemKind.COMPENSATE);
    assertThat(p.summary.path("money_recoverable_minor").asLong()).isEqualTo(second.moneyOutMinor);
    assertThat(p.summary.path("money_not_recoverable_minor").asLong())
        .isEqualTo(first.moneyOutMinor);
    // Preview applied the missed settlement first (journal REFUND_SETTLED posted).
    assertThat(h.reload(first).providerStatus).isEqualTo("settled");

    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    assertThat(h.reload(run).status).isEqualTo(RunStatus.REVERTING);
    RevertPlan done = h.runRevert(p, "w1");

    assertThat(done.status).isEqualTo(PlanStatus.PARTIALLY_REVERTED);
    assertThat(h.reload(run).status).isEqualTo(RunStatus.PARTIALLY_REVERTED);
    assertThat(refundStatus(second)).isEqualTo("cancelled");
    assertThat(refundStatus(first)).isEqualTo("settled");
    RevertItem cancelled = itemFor(done, second);
    assertThat(cancelled.status).isEqualTo(ItemStatus.DONE);
    assertThat(cancelled.moneyRecoveredMinor).isEqualTo(second.moneyOutMinor);
    assertThat(itemFor(done, first).status).isEqualTo(ItemStatus.SKIPPED);
    assertThat(h.reload(second).compensatedByActionId).isEqualTo(cancelled.compensationActionId);
    // INV-6 for PARTIALLY_REVERTED: net exposure = sum of money not recovered.
    long notRecovered = items(done).stream().mapToLong(i -> i.moneyNotRecoveredMinor).sum();
    assertThat(exposure(run)).isEqualTo(notRecovered).isEqualTo(first.moneyOutMinor);
    assertThat(done.summary.path("net_exposure_after_minor").asLong()).isEqualTo(notRecovered);
    assertThat(done.summary.path("report_text").asText())
        .startsWith("Reverted 1 of 2 actions.")
        .contains("Cannot be undone: 1 × shop_issue_refund");
  }

  @Test
  void fullRevertEndsRevertedWithZeroExposure() {
    Run run = h.run();
    Action a = refund(run, 0);
    Action b = refund(run, 1);
    RevertPlan done = h.revertAll(run, "w1");
    assertThat(done.status).isEqualTo(PlanStatus.REVERTED);
    assertThat(h.reload(run).status).isEqualTo(RunStatus.REVERTED);
    assertThat(refundStatus(a)).isEqualTo("cancelled");
    assertThat(refundStatus(b)).isEqualTo("cancelled");
    assertThat(exposure(run)).isZero();
    // The second preview of a reverted run is refused; a compensation is never a target.
    assertThatThrownBy(() -> h.preview(run))
        .isInstanceOfSatisfying(
            UndoException.class,
            e -> assertThat(e.code()).isEqualTo(UndoException.Code.ALREADY_REVERTED));
  }

  @Test
  void revertPreviewCancelsPendingApprovalsOfCompletedRun() {
    Run run = h.run();
    Action small = refund(run, 0);
    String[] big = h.ownOrder("TKT-0117");
    Action pending =
        action(h.propose(run, "shop_issue_refund", refundArgs(null, big[0], 1_050_000)));
    assertThat(h.reload(pending).status).isEqualTo(ActionStatus.AWAITING_APPROVAL);
    h.runs.complete(run.id, "agent");
    long reservedBefore = h.reload(run).moneyReservedMinor;
    assertThat(reservedBefore).isEqualTo(1_050_000);

    RevertPlan p = h.preview(run);
    assertThat(h.reload(pending).status).isEqualTo(ActionStatus.CANCELLED);
    assertThat(h.store.tx(tx -> tx.approvals().byAction(pending.id)).orElseThrow().status)
        .isEqualTo(ApprovalStatus.CANCELLED);
    assertThat(h.reload(run).moneyReservedMinor).isZero();
    assertThat(h.reload(run).status).isEqualTo(RunStatus.COMPLETED);
    assertThat(itemFor(p, pending).kind).isEqualTo(ItemKind.NO_EFFECT);
    assertThat(itemFor(p, small).kind).isEqualTo(ItemKind.COMPENSATE);
    // The preview is idempotent: asking again returns the same open plan.
    assertThat(h.preview(run).id).isEqualTo(p.id);
  }

  @Test
  void remediationCanBeApprovedAfterRunIsReverted() {
    Run run = h.run();
    String[] o = h.ownOrder("TKT-0001");
    Action email =
        action(
            h.propose(
                run,
                "shop_send_email",
                "{\"ticket_id\":\"TKT-0001\",\"customer_id\":\""
                    + o[2]
                    + "\",\"template\":\"need_more_info\",\"order_id\":null,\"amount_minor\":null,\"note\":null}"));
    assertThat(h.reload(email).status).isEqualTo(ActionStatus.SUCCEEDED);
    RevertPlan done = h.revertAll(run, "w1");
    assertThat(done.status).isEqualTo(PlanStatus.PARTIALLY_REVERTED);
    RevertItem it = itemFor(done, email);
    assertThat(it.kind).isEqualTo(ItemKind.IRREVERSIBLE);
    assertThat(it.status).isEqualTo(ItemStatus.SKIPPED);
    assertThat(it.remediationActionId).isNotNull();
    Action rem = h.store.tx(tx -> tx.actions().get(it.remediationActionId)).orElseThrow();
    assertThat(rem.kind).isEqualTo(ActionKind.REMEDIATION);
    assertThat(rem.status).isEqualTo(ActionStatus.AWAITING_APPROVAL);
    assertThat(rem.args.path("template").asText()).isEqualTo("correction");
    assertThat(h.reload(run).status).isEqualTo(RunStatus.PARTIALLY_REVERTED);

    var ap = h.store.tx(tx -> tx.approvals().byAction(rem.id)).orElseThrow();
    h.approvals.approve(ap.id, ap.argsHash, Harness.REVERTER, "user:approver", null);
    h.settle("w1");
    assertThat(h.reload(rem).status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(done.summary.path("report_text").asText())
        .contains("1 remediation is waiting for approval.");
  }

  @Test
  void compensationUnknownIsReexecutedNotFailed() {
    Run run = h.run();
    Action a = refund(run, 0);
    RevertPlan p = h.preview(run);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    // The cancel request never reaches Shopfront; the client sees a timeout → UNKNOWN.
    h.faults.add(Harness.Fault.TIMEOUT_NOT_SENT);
    RevertPlan done = h.runRevert(p, "w1");
    RevertItem it = itemFor(done, a);
    Action comp = h.store.tx(tx -> tx.actions().get(it.compensationActionId)).orElseThrow();
    assertThat(comp.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(comp.attemptCount).isGreaterThanOrEqualTo(2);
    List<dev.undo.core.model.ActionTransition> trs =
        h.store.tx(tx -> tx.actions().transitions(comp.id));
    assertThat(trs).anyMatch(t -> t.toStatus == ActionStatus.UNKNOWN);
    assertThat(it.status).isEqualTo(ItemStatus.DONE);
    assertThat(done.status).isEqualTo(PlanStatus.REVERTED);
    assertThat(refundStatus(a)).isEqualTo("cancelled");
  }

  @Test
  void revertReportsConflictWhenNoteChangedByHuman() {
    Run run = h.run();
    JsonNode before = h.shopGet("/v1/orders/ORD-0010").path("note");
    Action kept =
        action(
            h.propose(
                run, "shop_set_order_note", "{\"order_id\":\"ORD-0010\",\"note\":\"agent A\"}"));
    Action edited =
        action(
            h.propose(
                run, "shop_set_order_note", "{\"order_id\":\"ORD-0011\",\"note\":\"agent B\"}"));
    Action late =
        action(
            h.propose(
                run, "shop_set_order_note", "{\"order_id\":\"ORD-0012\",\"note\":\"agent C\"}"));
    assertThat(h.reload(late).status).isEqualTo(ActionStatus.SUCCEEDED);
    h.shopAdmin("PUT", "/admin/orders/ORD-0011/note", "{\"note\":\"a person wrote this\"}");
    RevertPlan p = h.preview(run);
    assertThat(itemFor(p, edited).kind).isEqualTo(ItemKind.CONFLICT);
    assertThat(itemFor(p, late).kind).isEqualTo(ItemKind.COMPENSATE);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    h.reverts.stepAll("w1", 10); // newest first: the restore of ORD-0012 is created (queued)
    // A person edits ORD-0012 before the restore runs: the CAS write must not overwrite it.
    h.shopAdmin("PUT", "/admin/orders/ORD-0012/note", "{\"note\":\"also a person\"}");
    RevertPlan done = h.runRevert(p, "w1");

    assertThat(done.status).isEqualTo(PlanStatus.PARTIALLY_REVERTED);
    assertThat(itemFor(done, kept).status).isEqualTo(ItemStatus.DONE);
    assertThat(itemFor(done, edited).status).isEqualTo(ItemStatus.SKIPPED);
    assertThat(itemFor(done, late).status).isEqualTo(ItemStatus.CONFLICT);
    assertThat(h.shopGet("/v1/orders/ORD-0010").path("note")).isEqualTo(before);
    java.util.UUID restoreId = itemFor(done, late).compensationActionId;
    Action restore = h.store.tx(tx -> tx.actions().get(restoreId)).orElseThrow();
    assertThat(restore.status).isEqualTo(ActionStatus.FAILED);
    assertThat(restore.error.path("code").asText()).isEqualTo("conflict");
    assertThat(h.shopGet("/v1/orders/ORD-0011").path("note").asText())
        .isEqualTo("a person wrote this");
    assertThat(h.shopGet("/v1/orders/ORD-0012").path("note").asText()).isEqualTo("also a person");
    assertThat(done.summary.path("report_text").asText())
        .contains("Changed by someone else and left as is: 2 × shop_set_order_note");
  }

  @Test
  void revertPartialStoreCreditReportsNotRecoveredAmount() {
    Run run = h.run();
    String[] o = h.ownOrder("TKT-0001");
    Action credit =
        action(
            h.propose(
                run,
                "shop_grant_store_credit",
                "{\"ticket_id\":\"TKT-0001\",\"customer_id\":\""
                    + o[2]
                    + "\",\"amount_minor\":150000,\"reason\":\"goodwill\"}"));
    assertThat(h.reload(credit).status).isEqualTo(ActionStatus.SUCCEEDED);
    h.shopAdmin(
        "POST",
        "/admin/store-credits/" + h.reload(credit).providerRef + "/spend",
        "{\"amount_minor\":50000}");
    RevertPlan p = h.preview(run);
    assertThat(itemFor(p, credit).kind).isEqualTo(ItemKind.COMPENSATE);
    assertThat(itemFor(p, credit).moneyRecoveredMinor).isEqualTo(100_000);
    assertThat(itemFor(p, credit).moneyNotRecoveredMinor).isEqualTo(50_000);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    RevertPlan done = h.runRevert(p, "w1");
    RevertItem it = itemFor(done, credit);
    assertThat(it.status).isEqualTo(ItemStatus.DONE);
    assertThat(it.moneyRecoveredMinor).isEqualTo(100_000);
    assertThat(it.moneyNotRecoveredMinor).isEqualTo(50_000);
    assertThat(done.status).isEqualTo(PlanStatus.PARTIALLY_REVERTED);
    assertThat(exposure(run)).isEqualTo(50_000);
    assertThat(
            h.shopGet("/v1/store-credits/" + h.reload(credit).providerRef).path("status").asText())
        .isEqualTo("revoked");
  }

  @Test
  void revertExecuteFailsWhileItemsBlocked() {
    Run run = h.run();
    String ticket = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(ticket);
    h.faults.add(Harness.Fault.HOLD);
    Action a =
        action(h.propose(run, "shop_issue_refund", refundArgs(ticket, o[0], Long.parseLong(o[1]))));
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.UNKNOWN);
    RevertPlan p = h.preview(run);
    assertThat(itemFor(p, a).kind).isEqualTo(ItemKind.BLOCKED_IN_FLIGHT);
    assertThatThrownBy(() -> h.reverts.execute(p.number, h.reload(run).number, "user:reverter"))
        .isInstanceOfSatisfying(
            UndoException.class,
            e -> assertThat(e.code()).isEqualTo(UndoException.Code.REVERT_BLOCKED));
    // The late request lands; the resolver finds it; refresh unblocks the item.
    h.deliverHeld();
    h.advance(Duration.ofMinutes(2));
    h.settle("w1");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.SUCCEEDED);
    h.reverts.refresh(p.number, "user:reverter");
    assertThat(itemFor(h.plan(p), a).kind).isEqualTo(ItemKind.COMPENSATE);
    assertThatThrownBy(() -> h.reverts.execute(p.number, 999_999, "user:reverter"))
        .isInstanceOfSatisfying(
            UndoException.class,
            e -> assertThat(e.code()).isEqualTo(UndoException.Code.CONFIRMATION_MISMATCH));
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    assertThat(h.runRevert(p, "w1").status).isEqualTo(PlanStatus.REVERTED);
  }

  @Test
  void crashAfterCompensationCreateLinksTheSameCompensation() {
    Run run = h.run();
    Action a = refund(run, 0);
    RevertPlan p = h.preview(run);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    h.armed.add(CrashPoint.REVERT_AFTER_ITEM_CREATE);
    assertThatThrownBy(() -> h.reverts.stepAll("w1", 10)).isInstanceOf(SimulatedCrash.class);
    assertThat(itemFor(p, a).compensationActionId).isNull();
    h.advance(Duration.ofMinutes(1)); // the dead worker's plan lease expires
    RevertPlan done = h.runRevert(p, "w2");
    assertThat(done.status).isEqualTo(PlanStatus.REVERTED);
    long compensations =
        h.store.tx(tx -> tx.actions().byRun(run.id)).stream()
            .filter(x -> x.kind == ActionKind.COMPENSATION)
            .count();
    assertThat(compensations).isEqualTo(1);
  }

  @Test
  void previewExpiresAfterTtlAndExecuteIsRefused() {
    Run run = h.run();
    refund(run, 0);
    RevertPlan p = h.preview(run);
    h.advance(Duration.ofMinutes(16));
    assertThatThrownBy(() -> h.reverts.execute(p.number, h.reload(run).number, "user:reverter"))
        .isInstanceOfSatisfying(
            UndoException.class,
            e -> assertThat(e.code()).isEqualTo(UndoException.Code.REVERT_PREVIEW_EXPIRED));
    assertThat(h.reverts.expirePreviews(10)).isEqualTo(1);
    assertThat(h.plan(p).status).isEqualTo(PlanStatus.CANCELLED);
    assertThat(h.preview(run).id).isNotEqualTo(p.id);
  }

  @Test
  void actionsScopeRejectsCompensationsAsTargets() {
    Run run = h.run();
    Action a = refund(run, 0);
    RevertPlan done =
        h.runRevert(
            started(
                h.reverts.preview(
                    h.reload(run).number,
                    PlanScope.ACTIONS,
                    List.of(a.number),
                    Harness.REVERTER,
                    "u")),
            "w1");
    assertThat(done.status).isEqualTo(PlanStatus.REVERTED);
    assertThat(h.reload(run).status).isEqualTo(RunStatus.FROZEN);
    java.util.UUID compId = h.reload(a).compensatedByActionId;
    Action comp = h.store.tx(tx -> tx.actions().get(compId)).orElseThrow();
    assertThatThrownBy(
            () ->
                h.reverts.preview(
                    h.reload(run).number,
                    PlanScope.ACTIONS,
                    List.of(comp.number),
                    Harness.REVERTER,
                    "u"))
        .isInstanceOfSatisfying(
            UndoException.class,
            e -> assertThat(e.code()).isEqualTo(UndoException.Code.INVALID_REQUEST));
  }

  private RevertPlan started(RevertPlan p) {
    h.reverts.execute(p.number, h.store.tx(tx -> tx.runs().get(p.runId)).orElseThrow().number, "u");
    return p;
  }

  @Test
  void bugRevertTrustsPreviewMarksUncancelledRefundDone() {
    h.bugs.add(Bug.REVERT_TRUSTS_PREVIEW);
    Run run = h.run();
    Action a = refund(run, 0);
    RevertPlan p = h.preview(run);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    h.reverts.stepAll("w1", 10); // creates the cancel compensation (queued)
    // The refund settles before the executor runs the cancel; the cancel then fails at Shopfront.
    h.shopAdmin("POST", "/admin/settlement/run", null);
    RevertPlan done = h.runRevert(p, "w1");
    assertThat(done.status).isEqualTo(PlanStatus.REVERTED);
    assertThat(refundStatus(a)).isEqualTo("settled");
    assertThat(exposure(run)).isNotZero(); // INV-6 flags this plan
  }

  @Test
  void withoutTheBugAFailedCancelBecomesIrreversible() {
    Run run = h.run();
    Action a = refund(run, 0);
    RevertPlan p = h.preview(run);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    h.reverts.stepAll("w1", 10);
    h.shopAdmin("POST", "/admin/settlement/run", null);
    RevertPlan done = h.runRevert(p, "w1");
    RevertItem it = itemFor(done, a);
    assertThat(it.status).isEqualTo(ItemStatus.BECAME_IRREVERSIBLE);
    Action comp = h.store.tx(tx -> tx.actions().get(it.compensationActionId)).orElseThrow();
    assertThat(comp.status).isEqualTo(ActionStatus.FAILED);
    assertThat(comp.error.path("code").asText()).isEqualTo("refund_not_cancellable");
    assertThat(done.status).isEqualTo(PlanStatus.PARTIALLY_REVERTED);
    assertThat(exposure(run)).isEqualTo(it.moneyNotRecoveredMinor).isEqualTo(a.moneyOutMinor);
    JsonNode note = Json.parse(it.note);
    assertThat(note.path("text").asText()).startsWith("Became irreversible");
  }

  @Test
  void reversibilityIsCheckedAgainAtExecution() {
    Run run = h.run();
    Action a = refund(run, 0);
    RevertPlan p = h.preview(run);
    assertThat(itemFor(p, a).kind).isEqualTo(ItemKind.COMPENSATE);
    h.shopAdmin("POST", "/admin/settlement/run", null);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    RevertPlan done = h.runRevert(p, "w1");
    RevertItem it = itemFor(done, a);
    assertThat(it.status).isEqualTo(ItemStatus.BECAME_IRREVERSIBLE);
    assertThat(it.compensationActionId).isNull(); // FR-REV-5: no cancel was even sent
    assertThat(exposure(run)).isEqualTo(it.moneyNotRecoveredMinor);
  }

  @Test
  void refundThatFailsWhileItsCancelIsQueuedIsAlreadyGone() {
    // Simulator seed 18140: the cancel answers refund_not_cancellable because the refund failed.
    Run run = h.run();
    Action a = refund(run, 0);
    RevertPlan p = h.preview(run);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    h.reverts.stepAll("w1", 10); // the cancel is created (queued)
    h.failPendingRefunds();
    assertThat(refundStatus(a)).isEqualTo("failed");
    RevertPlan done = h.runRevert(p, "w1");
    RevertItem it = itemFor(done, a);
    assertThat(it.status).isEqualTo(ItemStatus.DONE);
    assertThat(it.moneyNotRecoveredMinor).isZero();
    assertThat(Json.parse(it.note).path("text").asText()).startsWith("Already gone");
    assertThat(done.status).isEqualTo(PlanStatus.REVERTED);
    assertThat(exposure(run)).isZero();
    assertThat(done.summary.path("report_text").asText()).startsWith("Reverted 0 of 0 actions.");
  }

  @Test
  void runRevertAfterActionsRevertCountsWhatWasNotRecovered() {
    Run run = h.run();
    String[] o = h.ownOrder("TKT-0001");
    Action credit =
        action(
            h.propose(
                run,
                "shop_grant_store_credit",
                "{\"ticket_id\":\"TKT-0001\",\"customer_id\":\""
                    + o[2]
                    + "\",\"amount_minor\":150000,\"reason\":\"goodwill\"}"));
    Action r = refund(run, 0);
    h.shopAdmin(
        "POST",
        "/admin/store-credits/" + h.reload(credit).providerRef + "/spend",
        "{\"amount_minor\":50000}");
    RevertPlan first =
        started(
            h.reverts.preview(
                h.reload(run).number,
                PlanScope.ACTIONS,
                List.of(credit.number),
                Harness.REVERTER,
                "u"));
    assertThat(h.runRevert(first, "w1").status).isEqualTo(PlanStatus.PARTIALLY_REVERTED);
    RevertPlan rest = h.preview(run);
    RevertItem again = itemFor(rest, credit);
    assertThat(again.kind).isEqualTo(ItemKind.ALREADY_REVERTED);
    assertThat(again.moneyNotRecoveredMinor).isEqualTo(50_000);
    h.reverts.execute(rest.number, h.reload(run).number, "u");
    RevertPlan done = h.runRevert(rest, "w1");
    assertThat(itemFor(done, r).status).isEqualTo(ItemStatus.DONE);
    // The refund was fully undone, but 50,000 of the credit was spent: not a full revert.
    assertThat(done.status).isEqualTo(PlanStatus.PARTIALLY_REVERTED);
    assertThat(exposure(run)).isEqualTo(50_000);
  }

  /**
   * Seed 35294 (ADR-018): an earlier lease holder created the compensation while the current holder
   * read the provider state; the state it read is that compensation's effect. The step links the
   * late compensation instead of reporting "already gone" with nothing recovered.
   */
  @Test
  void lateCompensationOfAnEarlierLeaseHolderIsLinkedNotAlreadyGone() {
    Run run = h.run();
    Action a = refund(run, 0);
    h.runs.complete(run.id, "agent");
    RevertPlan p = h.preview(run);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    RevertItem item = itemFor(p, a);
    // While the step reads the refund, the stale holder's step proposes the compensation with the
    // plan's call key and it runs.
    var req =
        new dev.undo.core.engine.ProposalService.Request(
            dev.undo.core.engine.Caller.system("w0", "revert"),
            run.id,
            null,
            ActionKind.COMPENSATION,
            a.id,
            p.id,
            "shop_cancel_refund",
            Json.parse("{\"refund_id\":\"" + a.providerRef + "\"}"),
            "revert:" + p.id + ":" + item.orderNo,
            null,
            0);
    h.beforeNextRead =
        () -> {
          h.proposals.propose(req);
          h.settle("w0");
        };

    RevertPlan done = h.runRevert(p, "w1");
    RevertItem it = itemFor(done, a);
    assertThat(it.status).isEqualTo(ItemStatus.DONE);
    assertThat(it.compensationActionId).isNotNull();
    assertThat(it.moneyRecoveredMinor).isEqualTo(a.moneyOutMinor);
    assertThat(done.status).isEqualTo(PlanStatus.REVERTED);
    assertThat(exposure(run)).isZero();
  }

  /**
   * Simulator seed 12055 (ADR-019 #1): the cancel never reached Shopfront (max attempts, not
   * applied) and meanwhile the refund failed at settlement. The item is DONE "already gone" with
   * nothing lost, not FAILED with the whole amount reported as not recovered.
   */
  @Test
  void compensationThatFailsAfterTheRefundFailedIsAlreadyGone() {
    Run run = h.run();
    Action a = refund(run, 0);
    h.runs.complete(run.id, "agent");
    RevertPlan p = h.preview(run);
    h.reverts.execute(p.number, h.reload(run).number, "user:reverter");
    for (int i = 0; i < 20; i++) {
      h.faults.add(Harness.Fault.TIMEOUT_NOT_SENT); // every cancel attempt is lost on the way
    }
    // First read: the step reads the refund (still pending) and creates the cancel. Second read:
    // the cancel's first lookup; by then the refund has failed at settlement.
    h.beforeNextRead = () -> h.beforeNextRead = h::failPendingRefunds;
    RevertPlan done = h.runRevert(p, "w1");
    h.faults.clear();
    RevertItem it = itemFor(done, a);
    Action cancel = h.store.tx(tx -> tx.actions().get(it.compensationActionId)).orElseThrow();
    assertThat(cancel.status).isEqualTo(ActionStatus.FAILED);
    assertThat(refundStatus(a)).isEqualTo("failed");
    assertThat(it.status).isEqualTo(ItemStatus.DONE);
    assertThat(it.moneyNotRecoveredMinor).isZero();
    assertThat(done.status).isEqualTo(PlanStatus.REVERTED);
    assertThat(exposure(run)).isZero();
  }
}
