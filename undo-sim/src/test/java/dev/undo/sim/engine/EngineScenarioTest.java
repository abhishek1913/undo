package dev.undo.sim.engine;

import static dev.undo.sim.engine.Harness.action;
import static dev.undo.sim.engine.Harness.refundArgs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.engine.Caller;
import dev.undo.core.engine.ExecutionService;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.ledger.Ledger;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.BudgetState;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.render.ToolResultRenderer;
import dev.undo.sim.SimulatedCrash;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineScenarioTest {
  Harness h;

  @BeforeEach
  void setUp() {
    h = new Harness();
  }

  Approval approvalOf(Action a) {
    return h.store.tx(tx -> tx.approvals().byAction(a.id)).orElseThrow();
  }

  @Test
  void inlineAllowedRefundSucceedsPostsJournalAndSpendsBudget() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    Action a =
        action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], Long.parseLong(o[1]))));
    assertThat(a.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(a.providerRef).startsWith("RF-");
    assertThat(a.providerStatus).isEqualTo("pending");
    assertThat(a.budgetState).isEqualTo(BudgetState.SPENT);
    Run r = h.reload(run);
    assertThat(r.moneySpentMinor).isEqualTo(Long.parseLong(o[1]));
    assertThat(r.moneyReservedMinor).isZero();
    assertThat(Ledger.expenseExposure(h.store.tx(tx -> tx.ledger().postingsByRun(run.id))))
        .isEqualTo(Long.parseLong(o[1]));
    assertThat(h.refundsFor(a.id)).isEqualTo(1);
    List<ActionStatus> path =
        h.store.tx(tx -> tx.actions().transitions(a.id)).stream().map(x -> x.toStatus).toList();
    assertThat(path)
        .containsExactly(
            ActionStatus.PROPOSED,
            ActionStatus.APPROVED,
            ActionStatus.EXECUTING,
            ActionStatus.SUCCEEDED);
  }

  @Test
  void refundIsAppliedOnceWhenResponseIsLostAndWorkerDies() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    h.faults.add(Harness.Fault.DROP_RESPONSE);
    h.armed.add(
        CrashPoint.PROVIDER_CALL_AFTER_RESPONSE); // the worker dies before writing the outcome
    assertThatThrownBy(
            () -> h.propose(run, "shop_issue_refund", refundArgs(t, o[0], Long.parseLong(o[1]))))
        .isInstanceOf(SimulatedCrash.class);
    Action a = h.store.tx(tx -> tx.actions().byRun(run.id)).get(0);
    assertThat(a.status).isEqualTo(ActionStatus.EXECUTING);
    assertThat(h.refundsFor(a.id)).isEqualTo(1);
    h.advance(Duration.ofSeconds(31));
    h.settle("worker-2");
    Action done = h.reload(a);
    assertThat(done.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(done.attemptCount).isEqualTo(2);
    assertThat(h.refundsFor(a.id)).isEqualTo(1);
    // The re-execution that settled the UNKNOWN action is counted as a resolution (24.1).
    assertThat(h.metered)
        .contains(
            "undo_unknown_resolutions_total [class, NATIVE_KEY, result, reexecute_succeeded]",
            "undo_unknown_resolution_seconds [class, NATIVE_KEY]");
    assertThat(
            h.shopAdmin("GET", "/admin/invariants", null)
                .get("orders_with_multiple_undo_refunds")
                .asInt())
        .isZero();
  }

  @Test
  void sameAgentCallIdReturnsSameActionAfterCrash() {
    Run run = h.run();
    String args = "{\"ticket_id\":\"TKT-0001\"}";
    h.armed.add(CrashPoint.PROPOSE_AFTER_COMMIT);
    ProposalService.Request req =
        ProposalService.Request.primary(Harness.API, run.id, "shop_get_ticket", Json.parse(args))
            .withCallId("toolu_1");
    assertThatThrownBy(() -> h.propose(req)).isInstanceOf(SimulatedCrash.class);
    ProposalService.Outcome again = h.propose(req);
    ProposalService.Outcome.Recorded r = (ProposalService.Outcome.Recorded) again;
    assertThat(r.replayed()).isTrue();
    assertThat(
            h.store.<java.util.List<dev.undo.core.model.Action>>tx(
                tx -> tx.actions().byRun(run.id)))
        .hasSize(1);
    ProposalService.Outcome reused =
        h.propose(
            ProposalService.Request.primary(
                    Harness.API,
                    run.id,
                    "shop_get_ticket",
                    Json.parse("{\"ticket_id\":\"TKT-0002\"}"))
                .withCallId("toolu_1"));
    assertThat(reused).isInstanceOf(ProposalService.Outcome.KeyReused.class);
  }

  @Test
  void businessDedupeBlocksSecondRefundForSameOrderAcrossRuns() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Action first = action(h.propose(h.run(), "shop_issue_refund", refundArgs(t, o[0], 100)));
    assertThat(first.status).isEqualTo(ActionStatus.SUCCEEDED);
    ProposalService.Outcome.Recorded second =
        (ProposalService.Outcome.Recorded)
            h.propose(h.run(), "shop_issue_refund", refundArgs(t, o[0], 100));
    assertThat(second.action().status).isEqualTo(ActionStatus.DENIED);
    assertThat(second.action().statusReason).isEqualTo("duplicate");
    assertThat(second.action().duplicateOfActionId).isEqualTo(first.id);
    JsonNode rendered = ToolResultRenderer.render(second.action(), second.original());
    assertThat(rendered.get("status").asText()).isEqualTo("duplicate");
    assertThat(rendered.get("original_action").asText()).isEqualTo(first.display());
    assertThat(rendered.get("original_status").asText()).isEqualTo("succeeded");
  }

  @Test
  void trapTkt0023IsADuplicateOfTkt0022() {
    String[] o = h.ownOrder("TKT-0022");
    Run run = h.run();
    Action first =
        action(
            h.propose(
                run, "shop_issue_refund", refundArgs("TKT-0022", o[0], Long.parseLong(o[1]))));
    assertThat(first.status).isIn(ActionStatus.SUCCEEDED, ActionStatus.AWAITING_APPROVAL);
    Action dup =
        action(
            h.propose(
                run, "shop_issue_refund", refundArgs("TKT-0023", o[0], Long.parseLong(o[1]))));
    assertThat(dup.status).isEqualTo(ActionStatus.DENIED);
    assertThat(dup.statusReason).isEqualTo("duplicate");
  }

  @Test
  void failedDedupeOriginalDoesNotBlockARetry() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    Action tooMuch =
        action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], Long.parseLong(o[1]) + 1)));
    assertThat(tooMuch.status).isEqualTo(ActionStatus.DENIED);
    assertThat(tooMuch.dedupeActive).isFalse();
    assertThat(action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], 100))).status)
        .isEqualTo(ActionStatus.SUCCEEDED);
  }

  @Test
  void staleFenceWriteIsRejected() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    Action a =
        action(
            h.proposals.propose(
                ProposalService.Request.primary(
                    Harness.QUEUED,
                    run.id,
                    "shop_issue_refund",
                    Json.parse(refundArgs(t, o[0], 100)))));
    assertThat(a.status).isEqualTo(ActionStatus.APPROVED);
    ExecutionService.Claimed zombie = h.execution.claimBatch("worker-A", 10).get(0);
    h.advance(Duration.ofSeconds(31)); // worker A stalls past its lease
    h.settle("worker-B"); // sweep → UNKNOWN → re-execute with fence 3 → SUCCEEDED
    Action afterB = h.reload(a);
    assertThat(afterB.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(afterB.leaseFence).isGreaterThan(zombie.fence());
    h.execution.executeClaimed(zombie); // the zombie finally gets its (replayed) answer
    Action afterZombie = h.reload(a);
    assertThat(afterZombie.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(afterZombie.updatedAt).isEqualTo(afterB.updatedAt);
    assertThat(
            h.store.<java.util.List<dev.undo.core.model.Attempt>>tx(
                tx -> tx.attempts().byAction(a.id)))
        .anyMatch(x -> x.outcome == dev.undo.core.model.AttemptOutcome.STALE_FENCE);
    assertThat(h.refundsFor(a.id)).isEqualTo(1);
    assertThat(h.store.staleWritesApplied).isEmpty();
  }

  @Test
  void approvalWithChangedArgsHashIsRejected() {
    Run run = h.run();
    Action a =
        action(h.propose(run, "shop_issue_refund", refundArgs("TKT-0117", "ORD-0333", 7_800_000)));
    assertThat(a.status).isEqualTo(ActionStatus.AWAITING_APPROVAL);
    Approval ap = approvalOf(a);
    assertThat(ap.argsHash).isEqualTo(a.argsHash);
    assertThatThrownBy(() -> h.approvals.approve(ap.id, "0".repeat(64), null, "user:x", null))
        .isInstanceOf(UndoException.class)
        .satisfies(
            e ->
                assertThat(((UndoException) e).code())
                    .isEqualTo(UndoException.Code.APPROVAL_HASH_MISMATCH));
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.AWAITING_APPROVAL);
    h.approvals.approve(ap.id, a.argsHash, null, "user:x", "ok");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.APPROVED);
    assertThat(approvalOf(a).approvedArgsHash).isEqualTo(a.argsHash);
    h.work("worker-1");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThatThrownBy(() -> h.approvals.approve(ap.id, a.argsHash, null, "user:x", null))
        .satisfies(
            e ->
                assertThat(((UndoException) e).code())
                    .isEqualTo(UndoException.Code.APPROVAL_NOT_PENDING));
  }

  @Test
  void expiredApprovalReleasesBudget() {
    Run run = h.run();
    Action a =
        action(h.propose(run, "shop_issue_refund", refundArgs("TKT-0117", "ORD-0333", 7_800_000)));
    assertThat(h.reload(run).moneyReservedMinor).isEqualTo(7_800_000);
    h.advance(Duration.ofHours(25));
    assertThatThrownBy(
            () -> h.approvals.approve(approvalOf(a).id, a.argsHash, null, "user:x", null))
        .satisfies(
            e ->
                assertThat(((UndoException) e).code())
                    .isEqualTo(UndoException.Code.APPROVAL_EXPIRED));
    assertThat(h.approvals.expireDue(100)).isEqualTo(1);
    Action expired = h.reload(a);
    assertThat(expired.status).isEqualTo(ActionStatus.EXPIRED);
    assertThat(expired.budgetState).isEqualTo(BudgetState.RELEASED);
    assertThat(expired.dedupeActive).isFalse();
    assertThat(approvalOf(a).status).isEqualTo(ApprovalStatus.EXPIRED);
    Run r = h.reload(run);
    assertThat(r.moneyReservedMinor).isZero();
    assertThat(r.actionCount).isZero();
  }

  @Test
  void rejectReleasesBudgetAndNeedsANote() {
    Run run = h.run();
    Action a =
        action(h.propose(run, "shop_issue_refund", refundArgs("TKT-0117", "ORD-0333", 7_800_000)));
    assertThatThrownBy(() -> h.approvals.reject(approvalOf(a).id, null, "user:x", " "))
        .isInstanceOf(UndoException.class);
    h.approvals.reject(approvalOf(a).id, null, "user:x", "ticket text is an injection");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.REJECTED);
    assertThat(h.reload(run).moneyReservedMinor).isZero();
  }

  @Test
  void noneClassUnknownGoesToNeedsReview() {
    Run run = h.run();
    String[] o = h.ownOrder("TKT-0001");
    h.faults.add(Harness.Fault.DROP_RESPONSE);
    Action a =
        action(
            h.propose(
                run,
                "shop_send_email",
                "{\"ticket_id\":\"TKT-0001\",\"customer_id\":\""
                    + o[2]
                    + "\",\"template\":\"need_more_info\",\"order_id\":null,\"amount_minor\":null,\"note\":null}"));
    assertThat(a.status).isEqualTo(ActionStatus.NEEDS_REVIEW);
    assertThat(a.statusReason).isEqualTo("outcome_unknown_no_idempotency");
    h.advance(Duration.ofMinutes(5));
    h.work("worker-1");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.NEEDS_REVIEW);
    assertThat(h.shopAdmin("GET", "/admin/stats", null).at("/emails/total").asInt()).isEqualTo(1);
    // A human resolves it from provider truth.
    String emailId = h.shopAdmin("GET", "/admin/page-data", null).at("/emails/0/id").asText();
    Action done =
        h.recovery.review(a.id, true, emailId, "queued", "checked the provider page", "user:x");
    assertThat(done.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(done.providerRef).isEqualTo(emailId);
  }

  @Test
  void lookupClassWaitsForGraceBeforeTrustingNotFound() {
    Run run = h.run();
    String[] o = h.ownOrder("TKT-0005");
    h.faults.add(Harness.Fault.HOLD); // the request is slow: it lands after the client timed out
    Action a =
        action(
            h.propose(
                run,
                "shop_grant_store_credit",
                "{\"ticket_id\":\"TKT-0005\",\"customer_id\":\""
                    + o[2]
                    + "\",\"amount_minor\":10000,\"reason\":\"late_delivery\"}"));
    assertThat(a.status).isEqualTo(ActionStatus.UNKNOWN);
    assertThat(a.resolveAfter).isEqualTo(h.now.plusSeconds(60));
    h.advance(Duration.ofSeconds(60));
    h.work("worker-1"); // lookup: NotFound, but not trusted until started_at + 10 s + 60 s
    Action waiting = h.reload(a);
    assertThat(waiting.status).isEqualTo(ActionStatus.UNKNOWN);
    assertThat(waiting.lookupCount).isEqualTo(1);
    h.deliverHeld(); // the late request lands within A1
    h.advance(Duration.ofSeconds(10));
    h.work("worker-1");
    Action done = h.reload(a);
    assertThat(done.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(done.attemptCount).isEqualTo(1);
    assertThat(h.shopGet("/v1/store-credits?reference=undo_" + a.id).path("data").size())
        .isEqualTo(1);
  }

  @Test
  void lookupClassReexecutesAfterTrustedNotFound() {
    Run run = h.run();
    String[] o = h.ownOrder("TKT-0005");
    h.faults.add(Harness.Fault.TIMEOUT_NOT_SENT);
    Action a =
        action(
            h.propose(
                run,
                "shop_grant_store_credit",
                "{\"ticket_id\":\"TKT-0005\",\"customer_id\":\""
                    + o[2]
                    + "\",\"amount_minor\":10000,\"reason\":\"late_delivery\"}"));
    h.advance(Duration.ofSeconds(70));
    h.work("worker-1");
    Action done = h.reload(a);
    assertThat(done.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(done.attemptCount).isEqualTo(2);
    assertThat(h.shopGet("/v1/store-credits?reference=undo_" + a.id).path("data").size())
        .isEqualTo(1);
  }

  @Test
  void frozenRunNeverReexecutesUnknown() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    h.faults.add(Harness.Fault.TIMEOUT_NOT_SENT);
    Action a = action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], 100)));
    assertThat(a.status).isEqualTo(ActionStatus.UNKNOWN);
    h.runs.freeze(run.id, "user:x");
    h.advance(Duration.ofSeconds(5));
    h.work("worker-1"); // lookup NotFound, not yet trusted
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.UNKNOWN);
    h.advance(Duration.ofSeconds(70));
    h.work("worker-1");
    Action done = h.reload(a);
    assertThat(done.status).isEqualTo(ActionStatus.FAILED);
    assertThat(done.statusReason).isEqualTo("not_applied_run_frozen");
    assertThat(done.attemptCount).isEqualTo(1);
    assertThat(done.budgetState).isEqualTo(BudgetState.RELEASED);
    assertThat(h.refundsFor(a.id)).isZero();
    assertThat(
            h.store.tx(tx -> tx.actions().transitions(a.id)).stream()
                .filter(x -> x.toStatus == ActionStatus.EXECUTING))
        .hasSize(1);
  }

  @Test
  void frozenRunLookupFindsAnAppliedEffect() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    h.faults.add(Harness.Fault.DROP_RESPONSE);
    Action a = action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], 100)));
    h.runs.freeze(run.id, "user:x");
    h.advance(Duration.ofSeconds(5));
    h.work("worker-1");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(h.reload(run).moneySpentMinor).isEqualTo(100);
  }

  @Test
  void retryableAfterUnknownStaysUnknownSoFreezeCannotCancelIt() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    h.faults.add(Harness.Fault.DROP_RESPONSE); // attempt 1 applied, response lost
    h.faults.add(Harness.Fault.RATE_LIMIT); // attempt 2 rejected before the key was checked
    Action a = action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], 100)));
    assertThat(a.status).isEqualTo(ActionStatus.UNKNOWN);
    h.advance(Duration.ofSeconds(1));
    h.recovery.resolveUnknownBatch("worker-1", 10);
    Action after = h.reload(a);
    assertThat(after.status).isEqualTo(ActionStatus.UNKNOWN); // not RETRY_WAIT (ADR-014)
    h.runs.freeze(run.id, "user:x");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.UNKNOWN);
    h.advance(Duration.ofSeconds(5));
    h.work("worker-1");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(h.refundsFor(a.id)).isEqualTo(1);
  }

  @Test
  void notAppliedAnswerAfterFreezeEndsTheAction() {
    // Found by the simulator (seed 2183): freeze while EXECUTING, then a 429 arrives.
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    Action a =
        action(
            h.proposals.propose(
                ProposalService.Request.primary(
                    Harness.QUEUED,
                    run.id,
                    "shop_issue_refund",
                    Json.parse(refundArgs(t, o[0], 100)))));
    ExecutionService.Claimed c = h.execution.claimBatch("worker-1", 10).get(0);
    h.runs.freeze(run.id, "user:x");
    h.faults.add(Harness.Fault.RATE_LIMIT);
    h.execution.executeClaimed(c);
    Action done = h.reload(a);
    assertThat(done.status).isEqualTo(ActionStatus.FAILED);
    assertThat(done.statusReason).isEqualTo("not_applied_run_frozen");
    assertThat(done.budgetState).isEqualTo(BudgetState.RELEASED);
    assertThat(h.reload(run).moneyReservedMinor).isZero();
  }

  @Test
  void retryableWithoutHistoryWaitsThenRetries() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    h.faults.add(Harness.Fault.RATE_LIMIT);
    Action a = action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], 100)));
    assertThat(a.status).isEqualTo(ActionStatus.RETRY_WAIT);
    assertThat(a.nextAttemptAt).isAfterOrEqualTo(h.now.plusSeconds(1));
    h.advance(Duration.ofSeconds(2));
    h.work("worker-1");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.SUCCEEDED);
  }

  @Test
  void eightNotAppliedAttemptsFail() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    for (int i = 0; i < 8; i++) {
      h.faults.add(Harness.Fault.RATE_LIMIT);
    }
    Action a = action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], 100)));
    for (int i = 0; i < 10; i++) {
      h.advance(Duration.ofSeconds(31));
      h.work("worker-1");
    }
    Action done = h.reload(a);
    assertThat(done.status).isEqualTo(ActionStatus.FAILED);
    assertThat(done.statusReason).isEqualTo("max_attempts_not_applied");
    assertThat(done.attemptCount).isEqualTo(8);
    assertThat(done.dedupeActive).isFalse();
    assertThat(h.reload(run).moneyReservedMinor).isZero();
  }

  @Test
  void failedReadDoesNotTouchBudget() {
    Run run = h.run();
    Action a = action(h.propose(run, "shop_get_order", "{\"order_id\":\"ORD-9999\"}"));
    assertThat(a.status).isEqualTo(ActionStatus.FAILED);
    assertThat(a.budgetState).isEqualTo(BudgetState.NONE);
    Run r = h.reload(run);
    assertThat(r.actionCount).isZero();
    assertThat(r.moneyReservedMinor + r.moneySpentMinor).isZero();
  }

  @Test
  void internalToolCannotBeCalledAsPrimary() {
    Action a = action(h.propose(h.run(), "shop_cancel_refund", "{\"refund_id\":\"RF-AAAAAAAA\"}"));
    assertThat(a.status).isEqualTo(ActionStatus.DENIED);
    assertThat(a.statusReason).isEqualTo("internal_tool");
  }

  @Test
  void invalidArgsAndUnknownToolsCreateNoRow() {
    Run run = h.run();
    assertThat(
            h.propose(run, "shop_issue_refund", "{\"order_id\":\"ORD-0001\",\"amount_minor\":1.5}"))
        .isInstanceOf(ProposalService.Outcome.Invalid.class);
    assertThat(h.propose(run, "shop_teleport", "{}"))
        .isInstanceOf(ProposalService.Outcome.UnknownTool.class);
    assertThat(
            h.store.<java.util.List<dev.undo.core.model.Action>>tx(
                tx -> tx.actions().byRun(run.id)))
        .isEmpty();
  }

  @Test
  void policyMissingFieldInOrderingOperatorFailsClosed() {
    // The order does not exist, so facts.order.refundable_minor is missing for a gt/ref rule.
    Action a = action(h.propose(h.run(), "shop_issue_refund", refundArgs(null, "ORD-9999", 100)));
    assertThat(a.status).isEqualTo(ActionStatus.DENIED);
    assertThat(a.statusReason).isEqualTo("policy_error");
    assertThat(a.error.path("message").asText()).contains("missing");
  }

  @Test
  void budgetIsNeverExceededAndDenialsSayWhich() {
    String t1 = h.smallDamagedTicket(0);
    String t2 = h.smallDamagedTicket(1);
    Run run = h.run(150L, null);
    Action ok = action(h.propose(run, "shop_issue_refund", refundArgs(t1, h.ownOrder(t1)[0], 100)));
    assertThat(ok.status).isEqualTo(ActionStatus.SUCCEEDED);
    Action denied =
        action(h.propose(run, "shop_issue_refund", refundArgs(t2, h.ownOrder(t2)[0], 100)));
    assertThat(denied.status).isEqualTo(ActionStatus.DENIED);
    assertThat(denied.statusReason).isEqualTo("budget_exceeded");
    assertThat(denied.error.get("budget").asText()).isEqualTo("money");
    assertThat(denied.dedupeActive).isFalse();
    Run caps = h.run(null, 1);
    assertThat(caps.actionCap).isEqualTo(1);
    assertThat(h.run(999_999_999_999L, null).moneyCapMinor)
        .isEqualTo(100_000_000); // never above policy
  }

  @Test
  void factsUnavailableDeniesAndIsRetryable() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    h.readsDown = true;
    Action a = action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], 100)));
    assertThat(a.status).isEqualTo(ActionStatus.DENIED);
    assertThat(a.statusReason).isEqualTo("facts_unavailable");
    assertThat(a.dedupeActive).isFalse();
    assertThat(ToolResultRenderer.render(a, null).get("retryable").asBoolean()).isTrue();
    h.readsDown = false;
    assertThat(action(h.propose(run, "shop_issue_refund", refundArgs(t, o[0], 100))).status)
        .isEqualTo(ActionStatus.SUCCEEDED);
  }

  @Test
  void freezeCancelsQueuedAndPendingPrimaryActionsAndReleasesBudget() {
    String t = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(t);
    Run run = h.run();
    Action pending =
        action(h.propose(run, "shop_issue_refund", refundArgs("TKT-0117", "ORD-0333", 7_800_000)));
    Action queued =
        action(
            h.proposals.propose(
                ProposalService.Request.primary(
                    Harness.QUEUED,
                    run.id,
                    "shop_issue_refund",
                    Json.parse(refundArgs(t, o[0], 100)))));
    assertThat(queued.status).isEqualTo(ActionStatus.APPROVED);
    h.runs.freeze(run.id, "user:x");
    assertThat(h.reload(pending).status).isEqualTo(ActionStatus.CANCELLED);
    assertThat(approvalOf(pending).status).isEqualTo(ApprovalStatus.CANCELLED);
    assertThat(h.reload(queued).status).isEqualTo(ActionStatus.CANCELLED);
    Run r = h.reload(run);
    assertThat(r.status).isEqualTo(RunStatus.FROZEN);
    assertThat(r.moneyReservedMinor).isZero();
    assertThat(r.actionCount).isZero();
    h.work("worker-1");
    assertThat(h.refundsFor(queued.id)).isZero();
    Action late = action(h.propose(run, "shop_get_ticket", "{\"ticket_id\":\"TKT-0001\"}"));
    assertThat(late.status).isEqualTo(ActionStatus.DENIED);
    assertThat(late.statusReason).isEqualTo("run_not_active");
  }

  @Test
  void primaryActionsOfAFrozenRunCannotBeApproved() {
    Run run = h.run();
    Action a =
        action(h.propose(run, "shop_issue_refund", refundArgs("TKT-0117", "ORD-0333", 7_800_000)));
    Approval ap = approvalOf(a);
    h.runs.freeze(run.id, "user:x");
    assertThatThrownBy(() -> h.approvals.approve(ap.id, a.argsHash, null, "user:x", null))
        .satisfies(
            e ->
                assertThat(((UndoException) e).code())
                    .isEqualTo(UndoException.Code.APPROVAL_NOT_PENDING));
  }

  @Test
  void bulkApprovalChecksEachItemIndependently() {
    Run run = h.run();
    Action a =
        action(h.propose(run, "shop_issue_refund", refundArgs("TKT-0117", "ORD-0333", 7_800_000)));
    var results =
        h.approvals.bulk(
            true,
            List.of(
                new dev.undo.core.engine.ApprovalService.BulkItem(approvalOf(a).id, "bad"),
                new dev.undo.core.engine.ApprovalService.BulkItem(
                    java.util.UUID.randomUUID(), "x")),
            null,
            "user:x",
            null);
    assertThat(results)
        .extracting(dev.undo.core.engine.ApprovalService.BulkResult::code)
        .containsExactly("APPROVAL_HASH_MISMATCH", "APPROVAL_NOT_FOUND");
    assertThat(
            h.approvals.bulk(
                true,
                List.of(
                    new dev.undo.core.engine.ApprovalService.BulkItem(
                        approvalOf(a).id, a.argsHash)),
                null,
                "user:x",
                null))
        .allMatch(dev.undo.core.engine.ApprovalService.BulkResult::ok);
  }

  @Test
  void runStatusToolReportsBudgetAndPendingApprovals() {
    Run run = h.run();
    h.propose(run, "shop_issue_refund", refundArgs("TKT-0117", "ORD-0333", 7_800_000));
    Action s = action(h.propose(run, "undo_run_status", "{}"));
    assertThat(s.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(s.result.get("money_reserved_minor").asLong()).isEqualTo(7_800_000);
    assertThat(s.result.get("pending_approvals")).hasSize(1);
  }

  @Test
  void noteWriteUsesTheCapturedVersion() {
    Run run = h.run();
    Action a =
        action(
            h.propose(
                run, "shop_set_order_note", "{\"order_id\":\"ORD-0001\",\"note\":\"first\"}"));
    assertThat(a.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(a.beforeSnapshot.get("note_version").asInt()).isZero();
    assertThat(h.shopGet("/v1/orders/ORD-0001").get("note").asText()).isEqualTo("first");
  }

  @Test
  void remediationAlwaysNeedsApproval() {
    Run run = h.run();
    String[] o = h.ownOrder("TKT-0001");
    Action r =
        action(
            h.propose(
                new ProposalService.Request(
                    Caller.system("worker-1", "revert"),
                    run.id,
                    null,
                    ActionKind.REMEDIATION,
                    null,
                    null,
                    "shop_send_email",
                    Json.parse(
                        "{\"ticket_id\":\"TKT-0001\",\"customer_id\":\""
                            + o[2]
                            + "\",\"template\":\"refund_confirmation\",\"order_id\":null,\"amount_minor\":null,\"note\":null}"),
                    "remediate:x:1",
                    null,
                    0)));
    assertThat(r.status).isEqualTo(ActionStatus.AWAITING_APPROVAL);
    assertThat(r.budgetState).isEqualTo(BudgetState.NONE);
  }

  @Test
  void successFoundByLookupAfterTheRefundFailedPostsTheFailedEntry() {
    // Simulator seed 17594: the effect is only found after the provider already failed it, so
    // the ledger needs REFUND_ISSUED and REFUND_FAILED (net 0), not REFUND_ISSUED alone.
    Run run = h.run();
    String ticket = h.smallDamagedTicket(0);
    String[] o = h.ownOrder(ticket);
    h.faults.add(Harness.Fault.DROP_RESPONSE);
    Action a =
        action(
            h.propose(
                run, "shop_issue_refund", Harness.refundArgs(ticket, o[0], Long.parseLong(o[1]))));
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.UNKNOWN);
    h.runs.freeze(run.id, "user:operator"); // frozen: the resolver only looks the action up
    h.failPendingRefunds();
    h.advance(java.time.Duration.ofMinutes(2));
    h.settle("w1");
    Action done = h.reload(a);
    assertThat(done.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(done.providerStatus).isEqualTo("failed");
    var postings = h.store.tx(tx -> tx.ledger().postingsByAction(a.id));
    assertThat(dev.undo.core.ledger.Ledger.expenseExposure(postings)).isZero();
  }
}
