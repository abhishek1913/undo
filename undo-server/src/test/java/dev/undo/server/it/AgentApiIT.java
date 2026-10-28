package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The REST agent API (Section 19.1) end to end on Postgres with a real Shopfront. */
class AgentApiIT extends StackTestSupport {
  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  @Test
  void inlineAllowedRefundSucceedsWithServerTiming() throws Exception {
    UUID run = newRun(null);
    Resp r = propose(run, "shop_issue_refund", refund(freshOrder(), 1000), null);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().get("status").asText()).isEqualTo("succeeded");
    assertThat(r.body().get("action").asText()).startsWith("#A-");
    assertThat(r.body().at("/result/refund_id").asText()).startsWith("RF-");
    assertThat(r.body().at("/action_detail/status").asText()).isEqualTo("SUCCEEDED");
    assertThat(r.headers().firstValue("Server-Timing").orElse(""))
        .matches("undo;dur=[0-9.]+, provider;dur=[0-9.]+");
    JsonNode runView = undo("GET", "/v1/runs/" + run, null).body();
    assertThat(runView.at("/budgets/money_spent_minor").asLong()).isEqualTo(1000);
    assertThat(refundsFor(r.body().at("/action_detail/id").asText())).isEqualTo(1);
  }

  @Test
  void requestsWithoutAValidKeyAre401() throws Exception {
    assertThat(undo("POST", "/v1/runs", "{}", "Authorization", "Bearer nope").status())
        .isEqualTo(401);
    Resp r =
        undo(
            "POST",
            "/v1/runs",
            "{}",
            "Authorization",
            "Bearer undo_sk_doesnotexist000000000000000000000000000000");
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.body().get("code").asText()).isEqualTo("UNAUTHENTICATED");
    assertThat(r.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
  }

  @Test
  void consoleApiIsClosedToAgentKeys() throws Exception {
    assertThat(undo("GET", "/api/runs", null, "Authorization", "Bearer " + AGENT_KEY).status())
        .isEqualTo(401);
  }

  @Test
  void invalidArgsAre422AndCreateNoRow() throws Exception {
    UUID run = newRun(null);
    Resp r =
        propose(
            run,
            "shop_issue_refund",
            "{\"ticket_id\":null,\"order_id\":\"ORD-0001\",\"amount_minor\":1.5,\"reason\":\"other\"}",
            null);
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.body().get("code").asText()).isEqualTo("INVALID_ARGS");
    assertThat(r.body().get("errors").size()).isPositive();
    assertThat(actionsOf(run)).isEmpty();
  }

  @Test
  void unknownAndInternalToolsAre404() throws Exception {
    UUID run = newRun(null);
    assertThat(propose(run, "shop_teleport", "{}", null).body().get("code").asText())
        .isEqualTo("UNKNOWN_TOOL");
    Resp internal = propose(run, "shop_cancel_refund", "{\"refund_id\":\"RF-AAAAAAAA\"}", null);
    assertThat(internal.status()).isEqualTo(404);
    assertThat(actionsOf(run)).isEmpty();
  }

  @Test
  void idempotencyKeyReplaysTheSameActionAndRejectsOtherArgs() throws Exception {
    UUID run = newRun(null);
    String order = freshOrder();
    Resp a = propose(run, "shop_issue_refund", refund(order, 500), "k-1");
    Resp b = propose(run, "shop_issue_refund", refund(order, 500), "k-1");
    assertThat(b.body().get("action").asText()).isEqualTo(a.body().get("action").asText());
    assertThat(actionsOf(run)).hasSize(1);
    Resp reused = propose(run, "shop_issue_refund", refund(order, 600), "k-1");
    assertThat(reused.status()).isEqualTo(409);
    assertThat(reused.body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void businessDedupeBlocksSecondRefundForSameOrderAcrossRuns() throws Exception {
    String order = freshOrder();
    Resp first = propose(newRun(null), "shop_issue_refund", refund(order, 700), null);
    assertThat(first.body().get("status").asText()).isEqualTo("succeeded");
    Resp second = propose(newRun(null), "shop_issue_refund", refund(order, 700), null);
    assertThat(second.body().get("status").asText()).isEqualTo("duplicate");
    assertThat(second.body().get("original_action").asText())
        .isEqualTo(first.body().get("action").asText());
    assertThat(second.body().get("original_status").asText()).isEqualTo("succeeded");
    assertThat(shop("GET", "/v1/refunds?order_id=" + order, null).path("data").size()).isEqualTo(1);
  }

  @Test
  void policyMissingFieldInOrderingOperatorFailsClosed() throws Exception {
    Resp r = propose(newRun(null), "shop_issue_refund", refund("ORD-9999", 100), null);
    assertThat(r.body().get("status").asText()).isEqualTo("denied");
    assertThat(r.body().at("/action_detail/status_reason").asText()).isEqualTo("policy_error");
  }

  @Test
  void largeRefundWaitsForApproval() throws Exception {
    Resp r = propose(newRun(null), "shop_issue_refund", refund("ORD-0333", 7_800_000), null);
    assertThat(r.body().get("status").asText()).isEqualTo("pending_approval");
    assertThat(r.body().at("/action_detail/matched_rules/0/id").asText())
        .isEqualTo("refund.large-needs-human");
  }

  @Test
  void failedReadDoesNotTouchBudget() throws Exception {
    UUID run = newRun(null);
    Resp r = propose(run, "shop_get_order", "{\"order_id\":\"ORD-9999\"}", null);
    assertThat(r.body().get("status").asText()).isEqualTo("failed");
    JsonNode v = undo("GET", "/v1/runs/" + run, null).body();
    assertThat(v.at("/budgets/actions_used").asInt()).isZero();
    assertThat(
            v.at("/budgets/money_reserved_minor").asLong()
                + v.at("/budgets/money_spent_minor").asLong())
        .isZero();
  }

  @Test
  void budgetsCanOnlyBeLowered() throws Exception {
    UUID run = newRun("\"budgets\":{\"money_out_minor\":999999999999,\"actions\":3}");
    JsonNode v = undo("GET", "/v1/runs/" + run, null).body();
    assertThat(v.at("/budgets/money_cap_minor").asLong()).isEqualTo(100_000_000);
    assertThat(v.at("/budgets/action_cap").asInt()).isEqualTo(3);
  }

  @Test
  void completeRunThenNewProposalsAreDenied() throws Exception {
    UUID run = newRun(null);
    assertThat(undo("POST", "/v1/runs/" + run + "/complete", null).body().get("status").asText())
        .isEqualTo("COMPLETED");
    Resp again = undo("POST", "/v1/runs/" + run + "/complete", null);
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().get("code").asText()).isEqualTo("RUN_NOT_ACTIVE");
    Resp late = propose(run, "shop_get_ticket", "{\"ticket_id\":\"TKT-0001\"}", null);
    assertThat(late.status()).isEqualTo(200);
    assertThat(late.body().get("status").asText()).isEqualTo("denied");
    assertThat(late.body().get("reason").asText()).isEqualTo("This run is no longer active.");
  }

  @Test
  void toolsListAndActionLookup() throws Exception {
    JsonNode tools = undo("GET", "/v1/tools", null).body().get("tools");
    assertThat(tools.size()).isEqualTo(10);
    assertThat(tools.toString()).doesNotContain("shop_cancel_refund");
    Resp r = propose(newRun(null), "undo_run_status", "{}", null);
    String id = r.body().at("/action_detail/id").asText();
    assertThat(undo("GET", "/v1/actions/" + id, null).body().get("status").asText())
        .isEqualTo("SUCCEEDED");
    assertThat(undo("GET", "/v1/actions/" + UUID.randomUUID(), null).status()).isEqualTo(404);
  }
}
