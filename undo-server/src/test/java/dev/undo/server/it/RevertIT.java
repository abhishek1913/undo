package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Revert end to end (Section 15) on Postgres with a real HTTP Shopfront and the console API. */
class RevertIT extends StackTestSupport {
  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  private long runNumber(UUID run) {
    return store.tx(tx -> tx.runs().get(run)).orElseThrow().number;
  }

  private JsonNode waitForPlanEnd(ConsoleClient c, long plan) throws Exception {
    JsonNode[] last = new JsonNode[1];
    await(
        "plan ends",
        Duration.ofSeconds(20),
        () -> {
          try {
            last[0] = c.get("/api/revert-plans/" + plan).body();
            return !last[0].get("status").asText().equals("EXECUTING");
          } catch (Exception e) {
            return false;
          }
        });
    return last[0];
  }

  private static Map<String, JsonNode> itemsByAction(JsonNode plan) {
    Map<String, JsonNode> m = new HashMap<>();
    plan.get("items").forEach(i -> m.put(i.get("action").asText(), i));
    return m;
  }

  @Test
  void revertOfAMixedRunCancelsWhatItCanAndReportsTheRest() throws Exception {
    UUID run = newRun(null);
    String settled =
        propose(run, "shop_issue_refund", refund(freshOrder(), 2500), null)
            .body()
            .get("action")
            .asText();
    String pending =
        propose(run, "shop_issue_refund", refund(freshOrder(), 3000), null)
            .body()
            .get("action")
            .asText();
    String noteOrder = freshOrder();
    String note =
        propose(
                run,
                "shop_set_order_note",
                "{\"order_id\":\"" + noteOrder + "\",\"note\":\"agent wrote\"}",
                null)
            .body()
            .get("action")
            .asText();
    String customer = shop("GET", "/v1/orders/" + freshOrder(), null).get("customer_id").asText();
    String email =
        propose(
                run,
                "shop_send_email",
                "{\"ticket_id\":null,\"customer_id\":\""
                    + customer
                    + "\",\"template\":\"need_more_info\",\"order_id\":null,\"amount_minor\":null,\"note\":null}",
                null)
            .body()
            .get("action")
            .asText();
    // The first refund settles at the provider; a person edits the note.
    shop("POST", "/admin/settlement/run?limit=1&undo_run_id=" + run, null);
    shop("PUT", "/admin/orders/" + noteOrder + "/note", "{\"note\":\"a person wrote this\"}");

    long number = runNumber(run);
    ConsoleClient viewer = ConsoleClient.as("viewer@undo.local", "undo-demo-viewer");
    assertThat(viewer.post("/api/runs/" + number + "/revert-plans", "{\"scope\":\"RUN\"}").status())
        .isEqualTo(403);
    ConsoleClient c = ConsoleClient.as("approver@undo.local", "undo-demo-approver");
    Resp preview = c.post("/api/runs/" + number + "/revert-plans", "{\"scope\":\"RUN\"}");
    assertThat(preview.status()).isEqualTo(201);
    JsonNode plan = preview.body();
    assertThat(plan.get("run_status").asText()).isEqualTo("FROZEN"); // ACTIVE run frozen first
    Map<String, JsonNode> items = itemsByAction(plan);
    assertThat(items.get(settled).get("kind").asText()).isEqualTo("IRREVERSIBLE");
    assertThat(items.get(pending).get("kind").asText()).isEqualTo("COMPENSATE");
    assertThat(items.get(note).get("kind").asText()).isEqualTo("CONFLICT");
    assertThat(items.get(email).get("kind").asText()).isEqualTo("IRREVERSIBLE");
    assertThat(items.get(email).at("/remediation/args/template").asText()).isEqualTo("correction");
    assertThat(plan.at("/summary/money_recoverable_minor").asLong()).isEqualTo(3000);
    assertThat(plan.at("/summary/money_not_recoverable_minor").asLong()).isEqualTo(2500);
    // Items are newest first.
    assertThat(plan.at("/items/0/action").asText()).isEqualTo(email);

    long planNumber = plan.get("number").asLong();
    Resp wrong =
        c.post(
            "/api/revert-plans/" + planNumber + "/execute",
            "{\"confirm_run_number\":" + (number + 1) + "}");
    assertThat(wrong.status()).isEqualTo(409);
    assertThat(wrong.body().get("code").asText()).isEqualTo("CONFIRMATION_MISMATCH");
    Resp ok =
        c.post(
            "/api/revert-plans/" + planNumber + "/execute",
            "{\"confirm_run_number\":" + number + "}");
    assertThat(ok.status()).isEqualTo(200);

    JsonNode done = waitForPlanEnd(c, planNumber);
    assertThat(done.get("status").asText()).isEqualTo("PARTIALLY_REVERTED");
    assertThat(done.get("run_status").asText()).isEqualTo("PARTIALLY_REVERTED");
    Map<String, JsonNode> after = itemsByAction(done);
    assertThat(after.get(pending).get("status").asText()).isEqualTo("DONE");
    assertThat(after.get(pending).get("money_recovered_minor").asLong()).isEqualTo(3000);
    assertThat(after.get(settled).get("status").asText()).isEqualTo("SKIPPED");
    assertThat(after.get(note).get("status").asText()).isEqualTo("SKIPPED");
    assertThat(after.get(email).get("remediation_status").asText()).isEqualTo("AWAITING_APPROVAL");

    String pendingRef =
        action(c.get("/api/actions/" + pending.substring(3)).body().get("id").asText()).providerRef;
    assertThat(shop("GET", "/v1/refunds/" + pendingRef, null).get("status").asText())
        .isEqualTo("cancelled");
    assertThat(shop("GET", "/v1/orders/" + noteOrder, null).get("note").asText())
        .isEqualTo("a person wrote this");
    // INV-6: net exposure = sum of money not recovered (the settled refund).
    JsonNode ledger = c.get("/api/runs/" + number + "/ledger").body();
    assertThat(ledger.get("balanced").asBoolean()).isTrue();
    assertThat(ledger.get("net_exposure_minor").asLong()).isEqualTo(2500);
    assertThat(done.at("/summary/net_exposure_after_minor").asLong()).isEqualTo(2500);
    assertThat(done.at("/summary/report_text").asText())
        .startsWith("Reverted 1 of 4 actions.")
        .contains("1 remediation is waiting for approval.");

    // The correction email can be approved after the run is reverted.
    JsonNode card = null;
    for (JsonNode x : c.get("/api/approvals?run=" + number).body().get("data")) {
      if (x.get("tool").asText().equals("shop_send_email")) {
        card = x;
      }
    }
    assertThat(card).isNotNull();
    assertThat(
            c.post(
                    "/api/approvals/" + card.get("id").asText() + "/approve",
                    "{\"args_hash\":\"" + card.get("args_hash").asText() + "\"}")
                .status())
        .isEqualTo(200);
    String remNumber = card.get("action_number").asText();
    await(
        "remediation sent",
        Duration.ofSeconds(5),
        () -> {
          try {
            return c.get("/api/actions/" + remNumber)
                .body()
                .get("status")
                .asText()
                .equals("SUCCEEDED");
          } catch (Exception e) {
            return false;
          }
        });
    JsonNode inv = shop("GET", "/admin/invariants", null);
    inv.properties().forEach(f -> assertThat(f.getValue().asLong()).as(f.getKey()).isZero());
  }

  @Test
  void fullRevertEndsRevertedWithZeroExposure() throws Exception {
    UUID run = newRun(null);
    propose(run, "shop_issue_refund", refund(freshOrder(), 1500), null);
    propose(run, "shop_issue_refund", refund(freshOrder(), 1700), null);
    undo("POST", "/v1/runs/" + run + "/complete", "{}");
    long number = runNumber(run);
    ConsoleClient c = ConsoleClient.as("admin@undo.local", "undo-demo-admin");
    JsonNode plan = c.post("/api/runs/" + number + "/revert-plans", "{\"scope\":\"RUN\"}").body();
    assertThat(plan.get("run_status").asText()).isEqualTo("COMPLETED");
    // Preview is idempotent while it is open.
    assertThat(
            c.post("/api/runs/" + number + "/revert-plans", "{\"scope\":\"RUN\"}")
                .body()
                .get("number")
                .asLong())
        .isEqualTo(plan.get("number").asLong());
    c.post(
        "/api/revert-plans/" + plan.get("number").asLong() + "/execute",
        "{\"confirm_run_number\":" + number + "}");
    JsonNode done = waitForPlanEnd(c, plan.get("number").asLong());
    assertThat(done.get("status").asText()).isEqualTo("REVERTED");
    assertThat(c.get("/api/runs/" + number + "/ledger").body().get("net_exposure_minor").asLong())
        .isZero();
    Resp again = c.post("/api/runs/" + number + "/revert-plans", "{\"scope\":\"RUN\"}");
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().get("code").asText()).isEqualTo("ALREADY_REVERTED");
  }

  @Test
  void agentKeysCannotRevert() throws Exception {
    UUID run = newRun(null);
    Resp r =
        undo(
            "POST",
            "/api/runs/" + runNumber(run) + "/revert-plans",
            "{\"scope\":\"RUN\"}",
            "Authorization",
            "Bearer " + AGENT_KEY);
    assertThat(r.status()).isEqualTo(401);
  }
}
