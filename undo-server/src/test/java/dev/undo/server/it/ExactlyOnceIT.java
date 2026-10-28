package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.shopfront.core.webhooks.WebhookOutbox;
import dev.undo.core.engine.ApprovalService;
import dev.undo.core.engine.Caller;
import dev.undo.core.engine.ExecutionService;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.AttemptKind;
import dev.undo.core.model.AttemptOutcome;
import dev.undo.core.model.BudgetState;
import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.Run;
import dev.undo.core.port.CrashPoint;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Section 22.2 tests on the real stack: Postgres, HTTP Shopfront, worker loops running. */
class ExactlyOnceIT extends StackTestSupport {
  @Autowired ProposalService proposals;
  @Autowired ExecutionService execution;
  @Autowired ApprovalService approvals;
  @Autowired RunService runs;

  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  Action actionOf(Resp r) {
    return action(r.body().at("/action_detail/id").asText());
  }

  Action awaitStatus(String id, ActionStatus s) throws Exception {
    await(id + " " + s, Duration.ofSeconds(20), () -> action(id).status == s);
    return action(id);
  }

  @Test
  void refundIsAppliedOnceWhenResponseIsLostAndWorkerDies() throws Exception {
    UUID run = newRun(null);
    shop("POST", "/admin/faults", "{\"drop_after_commit_rate\":1}");
    CRASH.arm(
        CrashPoint.PROVIDER_CALL_AFTER_RESPONSE,
        0); // the API thread dies before writing the outcome
    Resp r = propose(run, "shop_issue_refund", refund(freshOrder(), 1200), "lost-1");
    assertThat(r.status()).isEqualTo(500);
    shop("POST", "/admin/faults", "{\"preset\":\"calm\"}");
    Action a = actionsOf(run).get(0);
    assertThat(refundsFor(a.id.toString())).isEqualTo(1); // the provider applied it
    Action done = awaitStatus(a.id.toString(), ActionStatus.SUCCEEDED);
    assertThat(done.attemptCount).isGreaterThanOrEqualTo(2);
    assertThat(refundsFor(a.id.toString())).isEqualTo(1);
    assertThat(
            shop("GET", "/admin/invariants", null).get("orders_with_multiple_undo_refunds").asInt())
        .isZero();
    // the agent retries with the same key and gets the same action
    Resp again =
        propose(
            run, "shop_issue_refund", refund(done.args.get("order_id").asText(), 1200), "lost-1");
    assertThat(again.body().get("action").asText()).isEqualTo(done.display());
    assertThat(again.body().get("status").asText()).isEqualTo("succeeded");
  }

  @Test
  void sameAgentCallIdReturnsSameActionAfterCrash() throws Exception {
    UUID run = newRun(null);
    CRASH.arm(CrashPoint.PROPOSE_AFTER_COMMIT, 0);
    Resp crashed = propose(run, "shop_get_ticket", "{\"ticket_id\":\"TKT-0001\"}", "call-1");
    assertThat(crashed.status()).isEqualTo(500);
    Resp again = propose(run, "shop_get_ticket", "{\"ticket_id\":\"TKT-0001\"}", "call-1");
    assertThat(again.status()).isEqualTo(200);
    assertThat(actionsOf(run)).hasSize(1);
    // the inline claim was committed but never executed: the sweeper and resolver finish it
    awaitStatus(actionsOf(run).get(0).id.toString(), ActionStatus.SUCCEEDED);
  }

  @Test
  void staleFenceWriteIsRejected() throws Exception {
    UUID run = newRun(null);
    CRASH.arm(
        CrashPoint.PROVIDER_CALL_AFTER_RESPONSE, 3500); // the API thread stalls past its 2 s lease
    Resp r = propose(run, "shop_issue_refund", refund(freshOrder(), 900), null);
    Action a = actionOf(r);
    assertThat(a.status)
        .isEqualTo(ActionStatus.SUCCEEDED); // finished by the resolver while the zombie slept
    assertThat(a.leaseFence).isGreaterThan(1);
    await(
        "stale attempt recorded",
        Duration.ofSeconds(5),
        () ->
            store.tx(tx -> tx.attempts().byAction(a.id)).stream()
                .anyMatch(at -> at.outcome == AttemptOutcome.STALE_FENCE));
    assertThat(refundsFor(a.id.toString())).isEqualTo(1);
  }

  @Test
  void noneClassUnknownGoesToNeedsReview() throws Exception {
    UUID run = newRun(null);
    shop("POST", "/admin/faults", "{\"drop_after_commit_rate\":1}");
    Resp r =
        propose(
            run,
            "shop_send_email",
            "{\"ticket_id\":null,\"customer_id\":\"CUS-0001\",\"template\":\"need_more_info\",\"order_id\":null,\"amount_minor\":null,\"note\":null}",
            null);
    assertThat(r.body().get("status").asText()).isEqualTo("unknown");
    Action a = actionOf(r);
    assertThat(a.status).isEqualTo(ActionStatus.NEEDS_REVIEW);
    assertThat(a.statusReason).isEqualTo("outcome_unknown_no_idempotency");
    Thread.sleep(2500);
    assertThat(action(a.id.toString()).status).isEqualTo(ActionStatus.NEEDS_REVIEW);
    assertThat(action(a.id.toString()).attemptCount).isEqualTo(1);
  }

  @Test
  void lookupClassWaitsForGraceBeforeTrustingNotFound() throws Exception {
    UUID run = newRun(null);
    shop(
        "POST",
        "/admin/faults",
        "{\"error_5xx_rate\":1}"); // never applied, but the client cannot know
    Resp r =
        propose(
            run,
            "shop_grant_store_credit",
            "{\"ticket_id\":null,\"customer_id\":\"CUS-0002\",\"amount_minor\":5000,\"reason\":\"goodwill\"}",
            null,
            0);
    Action a = actionOf(r);
    await(
        "unknown",
        Duration.ofSeconds(5),
        () -> action(a.id.toString()).status == ActionStatus.UNKNOWN);
    shop("POST", "/admin/faults", "{\"preset\":\"calm\"}");
    Action done = awaitStatus(a.id.toString(), ActionStatus.SUCCEEDED);
    var attempts = store.tx(tx -> tx.attempts().byAction(a.id));
    var firstExec =
        attempts.stream()
            .filter(x -> x.kind == AttemptKind.EXECUTE && x.attemptNo == 1)
            .findFirst()
            .orElseThrow();
    var secondExec =
        attempts.stream()
            .filter(x -> x.kind == AttemptKind.EXECUTE && x.attemptNo == 2)
            .findFirst()
            .orElseThrow();
    // re-executed only after provider timeout (1 s) + lookup grace (1 s), and only after a lookup
    assertThat(Duration.between(firstExec.startedAt, secondExec.startedAt))
        .isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    assertThat(attempts)
        .anyMatch(x -> x.kind == AttemptKind.LOOKUP && x.outcome == AttemptOutcome.NOT_FOUND);
    assertThat(shop("GET", "/v1/store-credits?reference=undo_" + a.id, null).path("data").size())
        .isEqualTo(1);
    assertThat(done.attemptCount).isEqualTo(2);
  }

  @Test
  void frozenRunNeverReexecutesUnknown() throws Exception {
    UUID run = newRun(null);
    shop("POST", "/admin/faults", "{\"error_5xx_rate\":1}");
    Action a = actionOf(propose(run, "shop_issue_refund", refund(freshOrder(), 800), null, 0));
    await(
        "unknown",
        Duration.ofSeconds(5),
        () -> action(a.id.toString()).status == ActionStatus.UNKNOWN);
    runs.freeze(run, "user:it");
    shop("POST", "/admin/faults", "{\"preset\":\"calm\"}");
    Action done = awaitStatus(a.id.toString(), ActionStatus.FAILED);
    assertThat(done.statusReason).isEqualTo("not_applied_run_frozen");
    assertThat(done.attemptCount).isEqualTo(1);
    assertThat(done.budgetState).isEqualTo(BudgetState.RELEASED);
    assertThat(refundsFor(a.id.toString())).isZero();
  }

  @Test
  void webhookBeforeOutcomeCommitIsAppliedLater() throws Exception {
    UUID run = newRun(null);
    shop("POST", "/admin/faults", "{\"drop_after_commit_rate\":1}");
    CRASH.arm(CrashPoint.PROVIDER_CALL_AFTER_RESPONSE, 0);
    propose(run, "shop_issue_refund", refund(freshOrder(), 1300), null);
    shop("POST", "/admin/faults", "{\"preset\":\"calm\"}");
    Action a = actionsOf(run).get(0);
    // settle now: refund.settled reaches Undo while the action has no provider_ref yet
    shop("POST", "/admin/settlement/run?undo_run_id=" + run, null);
    Action done = awaitStatus(a.id.toString(), ActionStatus.SUCCEEDED);
    await(
        "webhook applied",
        Duration.ofSeconds(20),
        () -> "settled".equals(action(done.id.toString()).providerStatus));
    List<JournalEntry> entries = store.tx(tx -> tx.ledger().entriesByRun(run));
    assertThat(entries)
        .extracting(e -> e.kind)
        .containsExactlyInAnyOrder("REFUND_ISSUED", "REFUND_SETTLED");
  }

  @Test
  void duplicateWebhookPostsOnce() throws Exception {
    UUID run = newRun(null);
    Action a = actionOf(propose(run, "shop_issue_refund", refund(freshOrder(), 1400), null));
    shop("POST", "/admin/faults", "{\"webhook_duplicate_rate\":1}");
    shop("POST", "/admin/settlement/run?undo_run_id=" + run, null);
    await(
        "settled",
        Duration.ofSeconds(20),
        () -> "settled".equals(action(a.id.toString()).providerStatus));
    // and replay the same event by hand, twice
    JsonNode payload =
        Json.parse(
            "{\"id\":\"evt_manual_"
                + a.id
                + "\",\"type\":\"refund.settled\",\"created_at\":\"2026-10-24T00:00:00Z\",\"data\":{\"object\":{\"id\":\""
                + a.providerRef
                + "\",\"status\":\"settled\"}}}");
    for (int i = 0; i < 2; i++) {
      assertThat(postWebhook(payload, SECRET)).isEqualTo(200);
    }
    List<JournalEntry> entries = store.tx(tx -> tx.ledger().entriesByRun(run));
    assertThat(entries)
        .extracting(e -> e.kind)
        .containsExactlyInAnyOrder("REFUND_ISSUED", "REFUND_SETTLED");
    assertThat(postWebhook(payload, "wrong-secret")).isEqualTo(400);
  }

  int postWebhook(JsonNode payload, String secret) throws Exception {
    byte[] body = Json.bytes(payload);
    long t = System.currentTimeMillis() / 1000;
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + UNDO_PORT + "/webhooks/shopfront"))
            .header("Content-Type", "application/json")
            .header("Shopfront-Event-Id", payload.get("id").asText())
            .header("Shopfront-Signature", "t=" + t + ",v1=" + WebhookOutbox.sign(secret, t, body))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  @Test
  void concurrentDuplicateProposalsOneWins() throws Exception {
    String order = freshOrder();
    List<UUID> runIds = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      runIds.add(newRun(null));
    }
    ExecutorService pool = Executors.newFixedThreadPool(20);
    List<Future<Resp>> fs = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      UUID run = runIds.get(i);
      fs.add(pool.submit(() -> propose(run, "shop_issue_refund", refund(order, 1500), null)));
    }
    int succeeded = 0;
    int duplicates = 0;
    for (Future<Resp> f : fs) {
      String s = f.get().body().get("status").asText();
      if (s.equals("succeeded")) {
        succeeded++;
      } else if (s.equals("duplicate")) {
        duplicates++;
      }
    }
    pool.shutdown();
    assertThat(succeeded).isEqualTo(1);
    assertThat(duplicates).isEqualTo(19);
    assertThat(shop("GET", "/v1/refunds?order_id=" + order, null).path("data").size()).isEqualTo(1);
  }

  @Test
  void budgetNeverOvershootsUnderConcurrency() throws Exception {
    UUID run = newRun("\"budgets\":{\"money_out_minor\":10000,\"actions\":2000}");
    ExecutorService pool = Executors.newFixedThreadPool(50);
    List<Future<Resp>> fs = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      String order = freshOrder();
      fs.add(pool.submit(() -> propose(run, "shop_issue_refund", refund(order, 1000), null)));
    }
    int ok = 0;
    int budget = 0;
    for (Future<Resp> f : fs) {
      JsonNode b = f.get().body();
      if (b.get("status").asText().equals("succeeded")) {
        ok++;
      } else if (b.get("status").asText().equals("denied")
          && b.at("/action_detail/status_reason").asText().equals("budget_exceeded")) {
        budget++;
      }
    }
    pool.shutdown();
    assertThat(ok).isEqualTo(10); // the cap exactly hit
    assertThat(budget).isEqualTo(40);
    Run r = store.tx(tx -> tx.runs().get(run)).orElseThrow();
    assertThat(r.moneySpentMinor).isEqualTo(10000);
    assertThat(r.moneyReservedMinor).isZero();
  }

  @Test
  void approvalWithChangedArgsHashIsRejected() throws Exception {
    UUID run = newRun(null);
    Action a =
        actionOf(propose(run, "shop_issue_refund", refund(freshBigOrder(), 1_050_000), null));
    var ap = store.tx(tx -> tx.approvals().byAction(a.id)).orElseThrow();
    try {
      approvals.approve(ap.id, "0".repeat(64), null, "user:it", null);
      throw new AssertionError("expected mismatch");
    } catch (UndoException e) {
      assertThat(e.code()).isEqualTo(UndoException.Code.APPROVAL_HASH_MISMATCH);
    }
    approvals.approve(ap.id, a.argsHash, null, "user:it", null);
    awaitStatus(a.id.toString(), ActionStatus.SUCCEEDED); // the worker executes it
  }

  @Test
  void expiredApprovalReleasesBudget() throws Exception {
    String yaml =
        dev.undo.core.policy.BuiltinPolicies.supportDefaultYaml()
            .replace("name: support-default", "name: quick-expiry")
            .replace("PT24H", "PT1S");
    var policy = applicationPolicy(yaml);
    UUID run = newRun("\"policy\":\"" + policy + "\"");
    Action a =
        actionOf(propose(run, "shop_issue_refund", refund(freshBigOrder(), 1_050_000), null));
    assertThat(a.status).isEqualTo(ActionStatus.AWAITING_APPROVAL);
    Action expired = awaitStatus(a.id.toString(), ActionStatus.EXPIRED);
    assertThat(expired.budgetState).isEqualTo(BudgetState.RELEASED);
    assertThat(store.tx(tx -> tx.runs().get(run)).orElseThrow().moneyReservedMinor).isZero();
  }

  @Autowired dev.undo.core.policy.PolicyService policyService;

  String applicationPolicy(String yaml) {
    var v = policyService.bootstrap(yaml, "it");
    return v.name;
  }

  @Test
  void internalToolCannotBeCalledAsPrimary() {
    UUID run = UUID.randomUUID();
    Run r =
        runs.create(
            new RunService.CreateRun(
                agentId(),
                dev.undo.core.model.RunKind.EXTERNAL,
                "t",
                null,
                null,
                null,
                "support-default",
                null,
                null,
                null,
                null));
    var out =
        proposals.propose(
            ProposalService.Request.primary(
                Caller.api("it-node", "x", true),
                r.id,
                "shop_cancel_refund",
                Json.parse("{\"refund_id\":\"RF-AAAAAAAA\"}")));
    Action a = ((ProposalService.Outcome.Recorded) out).action();
    assertThat(a.status).isEqualTo(ActionStatus.DENIED);
    assertThat(a.statusReason).isEqualTo("internal_tool");
    assertThat(run).isNotNull();
  }

  UUID agentId() {
    return store.tx(tx -> tx.agents().byName("demo-support")).orElseThrow().id;
  }
}
