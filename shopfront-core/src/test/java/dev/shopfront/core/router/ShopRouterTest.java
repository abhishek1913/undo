package dev.shopfront.core.router;

import static dev.shopfront.core.router.ShopFixture.code;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.shopfront.core.domain.OutboxRow;
import dev.shopfront.core.faults.FaultConfig;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.settlement.Settlement;
import dev.shopfront.core.webhooks.WebhookOutbox;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ShopRouterTest {
  ShopFixture f;

  @BeforeEach
  void setUp() {
    f = new ShopFixture();
    f.seed(60, 400, 200);
  }

  String refundBody(String order, long amount) {
    return "{\"order_id\":\""
        + order
        + "\",\"amount_minor\":"
        + amount
        + ",\"reason\":\"damaged\",\"metadata\":{\"undo_action_id\":\"a1\",\"undo_run_id\":\"r1\"}}";
  }

  long refundable(String order) {
    return f.ok("GET", "/v1/orders/" + order, null).get("refundable_minor").asLong();
  }

  @Nested
  class Auth {
    @Test
    void v1RequiresTheApiKey() {
      ShopResponse r =
          f.router.handle(
              ShopRequest.of(
                  "GET", "/v1/orders/ORD-0001", Map.of("Authorization", "Bearer nope"), null));
      assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void adminRequiresTheAdminToken() {
      ShopResponse r = f.router.handle(ShopRequest.of("GET", "/admin/stats", Map.of(), null));
      assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void unknownEndpointIs404() {
      assertThat(f.call("GET", "/v1/nothing", null).status()).isEqualTo(404);
    }
  }

  @Nested
  class Reads {
    @Test
    void customerIncludesStoreCreditBalance() {
      f.ok(
          "POST",
          "/v1/store-credits",
          "{\"customer_id\":\"CUS-0001\",\"amount_minor\":5000,\"reason\":\"goodwill\",\"reference\":\"x\"}");
      JsonNode c = f.ok("GET", "/v1/customers/CUS-0001", null);
      assertThat(c.get("store_credit_balance_minor").asLong()).isEqualTo(5000);
      assertThat(c.get("name").asText()).isEqualTo("Aarav Sharma");
      assertThat(c.get("email").asText()).isEqualTo("aarav.sharma.01@example.com");
    }

    @Test
    void missingCustomerIs404() {
      assertThat(code(f.call("GET", "/v1/customers/CUS-9999", null)))
          .isEqualTo("customer_not_found");
    }

    @Test
    void orderHasDerivedFieldsAndEtag() {
      ShopResponse r = f.call("GET", "/v1/orders/ORD-0333", null);
      JsonNode o = Json.parse(r.body());
      assertThat(r.headers().get("ETag")).isEqualTo("\"v0\"");
      assertThat(o.get("total_minor").asLong()).isEqualTo(7_800_000);
      assertThat(o.get("refundable_minor").asLong()).isEqualTo(7_800_000);
      assertThat(o.get("refunded_minor").asLong()).isZero();
    }

    @Test
    void missingOrderIs404() {
      assertThat(code(f.call("GET", "/v1/orders/ORD-9999", null))).isEqualTo("order_not_found");
    }

    @Test
    void ticketsPageInIdOrderAndHideTheCategory() {
      JsonNode p1 = f.ok("GET", "/v1/tickets?status=OPEN&limit=20", null);
      assertThat(p1.get("data")).hasSize(20);
      assertThat(p1.get("data").get(0).get("id").asText()).isEqualTo("TKT-0001");
      assertThat(p1.get("data").get(0).has("category")).isFalse();
      JsonNode p2 =
          f.ok(
              "GET",
              "/v1/tickets?status=open&limit=20&cursor=" + p1.get("next_cursor").asText(),
              null);
      assertThat(p2.get("data").get(0).get("id").asText()).isEqualTo("TKT-0021");
    }

    @Test
    void ticketListRejectsBadLimit() {
      assertThat(f.call("GET", "/v1/tickets?status=open&limit=0", null).status()).isEqualTo(400);
    }

    @Test
    void missingTicketIs404() {
      assertThat(code(f.call("GET", "/v1/tickets/TKT-9999", null))).isEqualTo("ticket_not_found");
    }
  }

  @Nested
  class Tickets {
    @Test
    void closeIsNatural() {
      JsonNode t = f.ok("POST", "/v1/tickets/TKT-0001/close", "{\"resolution\":\"refunded\"}");
      assertThat(t.get("status").asText()).isEqualTo("CLOSED");
      JsonNode again = f.ok("POST", "/v1/tickets/TKT-0001/close", "{\"resolution\":\"refunded\"}");
      assertThat(again.get("closed_at")).isEqualTo(t.get("closed_at"));
    }

    @Test
    void closeWithDifferentResolutionConflicts() {
      f.ok("POST", "/v1/tickets/TKT-0001/close", "{\"resolution\":\"refunded\"}");
      ShopResponse r =
          f.call("POST", "/v1/tickets/TKT-0001/close", "{\"resolution\":\"rejected\"}");
      assertThat(r.status()).isEqualTo(409);
      assertThat(code(r)).isEqualTo("ticket_resolution_conflict");
    }

    @Test
    void reopenWithExpectedResolution() {
      f.ok("POST", "/v1/tickets/TKT-0001/close", "{\"resolution\":\"refunded\"}");
      assertThat(
              f.ok("POST", "/v1/tickets/TKT-0001/reopen", "{\"expected_resolution\":\"refunded\"}")
                  .get("status")
                  .asText())
          .isEqualTo("OPEN");
      assertThat(
              f.ok("POST", "/v1/tickets/TKT-0001/reopen", "{\"expected_resolution\":\"refunded\"}")
                  .get("status")
                  .asText())
          .isEqualTo("OPEN");
    }

    @Test
    void reopenWithOtherResolutionConflicts() {
      f.ok("POST", "/v1/tickets/TKT-0001/close", "{\"resolution\":\"duplicate\"}");
      ShopResponse r =
          f.call("POST", "/v1/tickets/TKT-0001/reopen", "{\"expected_resolution\":\"refunded\"}");
      assertThat(code(r)).isEqualTo("ticket_state_conflict");
    }

    @Test
    void closeNeedsResolution() {
      assertThat(f.call("POST", "/v1/tickets/TKT-0001/close", "{}").status()).isEqualTo(400);
    }
  }

  @Nested
  class Notes {
    @Test
    void setNoteNeedsIfMatch() {
      ShopResponse r = f.call("PUT", "/v1/orders/ORD-0001/note", "{\"note\":\"x\"}");
      assertThat(r.status()).isEqualTo(428);
      assertThat(code(r)).isEqualTo("if_match_required");
    }

    @Test
    void setNoteIsCompareAndSwap() {
      JsonNode o =
          f.ok("PUT", "/v1/orders/ORD-0001/note", "{\"note\":\"first\"}", "If-Match", "\"v0\"");
      assertThat(o.get("note_version").asInt()).isEqualTo(1);
      ShopResponse stale =
          f.call("PUT", "/v1/orders/ORD-0001/note", "{\"note\":\"second\"}", "If-Match", "\"v0\"");
      assertThat(stale.status()).isEqualTo(412);
      assertThat(
              Json.parse(stale.body()).path("error").path("details").path("note_version").asInt())
          .isEqualTo(1);
      assertThat(
              f.ok("PUT", "/v1/orders/ORD-0001/note", "{\"note\":null}", "If-Match", "\"v1\"")
                  .get("note")
                  .isNull())
          .isTrue();
    }

    @Test
    void adminNoteEditBumpsVersion() {
      f.ok("PUT", "/admin/orders/ORD-0001/note", "{\"note\":\"human\"}");
      assertThat(f.ok("GET", "/v1/orders/ORD-0001", null).get("note_version").asInt()).isEqualTo(1);
    }
  }

  @Nested
  class Refunds {
    @Test
    void refundNeedsIdempotencyKey() {
      ShopResponse r = f.call("POST", "/v1/refunds", refundBody("ORD-0001", 100));
      assertThat(r.status()).isEqualTo(400);
      assertThat(code(r)).isEqualTo("idempotency_key_required");
    }

    @Test
    void sameKeySameRequestReturnsByteIdenticalResponseAndOneRefund() {
      ShopResponse a =
          f.call("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "undo_k1");
      f.now = f.now.plusSeconds(5);
      ShopResponse b =
          f.call("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "undo_k1");
      assertThat(a.status()).isEqualTo(201);
      assertThat(b.status()).isEqualTo(201);
      assertThat(b.body()).isEqualTo(a.body());
      assertThat(f.ok("GET", "/v1/refunds?order_id=ORD-0001", null).get("data")).hasSize(1);
    }

    @Test
    void sameKeyDifferentRequestIsReused() {
      f.call("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k");
      ShopResponse r =
          f.call("POST", "/v1/refunds", refundBody("ORD-0001", 200), "Idempotency-Key", "k");
      assertThat(r.status()).isEqualTo(422);
      assertThat(code(r)).isEqualTo("idempotency_key_reused");
    }

    @Test
    void keyOrderOfJsonFieldsDoesNotMatter() {
      f.call("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k");
      String reordered =
          "{\"reason\":\"damaged\",\"metadata\":{\"undo_run_id\":\"r1\",\"undo_action_id\":\"a1\"},\"amount_minor\":100,\"order_id\":\"ORD-0001\"}";
      assertThat(f.call("POST", "/v1/refunds", reordered, "Idempotency-Key", "k").status())
          .isEqualTo(201);
    }

    @Test
    void expiredKeyIsTreatedAsNew() {
      f.call("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k");
      f.now = f.now.plus(Duration.ofHours(25));
      f.call("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k");
      assertThat(f.ok("GET", "/v1/refunds?order_id=ORD-0001", null).get("data")).hasSize(2);
    }

    @Test
    void errorAnswersAreStoredUnderTheKey() {
      long max = refundable("ORD-0001");
      ShopResponse a =
          f.call("POST", "/v1/refunds", refundBody("ORD-0001", max + 1), "Idempotency-Key", "k");
      assertThat(code(a)).isEqualTo("amount_exceeds_refundable");
      ShopResponse b =
          f.call("POST", "/v1/refunds", refundBody("ORD-0001", max + 1), "Idempotency-Key", "k");
      assertThat(b.body()).isEqualTo(a.body());
    }

    @Test
    void refundOfMissingOrderIs404() {
      assertThat(
              code(
                  f.call("POST", "/v1/refunds", refundBody("ORD-9999", 1), "Idempotency-Key", "k")))
          .isEqualTo("order_not_found");
    }

    @Test
    void refundReducesRefundable() {
      long before = refundable("ORD-0002");
      f.ok("POST", "/v1/refunds", refundBody("ORD-0002", 100), "Idempotency-Key", "k");
      assertThat(refundable("ORD-0002")).isEqualTo(before - 100);
    }

    @Test
    void refundLookups() {
      JsonNode r = f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k");
      assertThat(f.ok("GET", "/v1/refunds?metadata.undo_action_id=a1", null).get("data"))
          .hasSize(1);
      assertThat(f.ok("GET", "/v1/refunds/" + r.get("id").asText(), null).get("status").asText())
          .isEqualTo("pending");
      assertThat(f.call("GET", "/v1/refunds/RF-NOPE", null).status()).isEqualTo(404);
      JsonNode page =
          f.ok(
              "GET",
              "/v1/refunds?updated_from="
                  + f.now.minusSeconds(1)
                  + "&updated_to="
                  + f.now.plusSeconds(1),
              null);
      assertThat(page.get("data")).hasSize(1);
      assertThat(f.call("GET", "/v1/refunds", null).status()).isEqualTo(400);
    }

    @Test
    void cancelPendingThenIdempotentThenNotCancellableWhenSettled() {
      String id =
          f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k")
              .get("id")
              .asText();
      assertThat(f.call("POST", "/v1/refunds/" + id + "/cancel", null).status()).isEqualTo(400);
      JsonNode c = f.ok("POST", "/v1/refunds/" + id + "/cancel", null, "Idempotency-Key", "c1");
      assertThat(c.get("status").asText()).isEqualTo("cancelled");
      assertThat(
              f.ok("POST", "/v1/refunds/" + id + "/cancel", null, "Idempotency-Key", "c2")
                  .get("status")
                  .asText())
          .isEqualTo("cancelled");
      String id2 =
          f.ok("POST", "/v1/refunds", refundBody("ORD-0003", 100), "Idempotency-Key", "k2")
              .get("id")
              .asText();
      f.ok("POST", "/admin/settlement/run", null);
      ShopResponse r =
          f.call("POST", "/v1/refunds/" + id2 + "/cancel", null, "Idempotency-Key", "c3");
      assertThat(r.status()).isEqualTo(409);
      assertThat(code(r)).isEqualTo("refund_not_cancellable");
    }
  }

  @Nested
  class Credits {
    String credit(long amount, String ref) {
      return f.ok(
              "POST",
              "/v1/store-credits",
              "{\"customer_id\":\"CUS-0002\",\"amount_minor\":"
                  + amount
                  + ",\"reason\":\"late_delivery\",\"reference\":\""
                  + ref
                  + "\"}",
              "Idempotency-Key",
              "ignored")
          .get("id")
          .asText();
    }

    @Test
    void creditsHaveNoIdempotencyEvenWithAKey() {
      credit(1000, "undo_x");
      credit(1000, "undo_x");
      assertThat(f.ok("GET", "/v1/store-credits?reference=undo_x", null).get("data")).hasSize(2);
    }

    @Test
    void creditForMissingCustomerIs404() {
      ShopResponse r =
          f.call(
              "POST",
              "/v1/store-credits",
              "{\"customer_id\":\"CUS-9999\",\"amount_minor\":1,\"reason\":\"other\",\"reference\":null}");
      assertThat(code(r)).isEqualTo("customer_not_found");
    }

    @Test
    void revokeRecordsRemainingBalance() {
      String id = credit(1000, "r");
      f.ok("POST", "/admin/store-credits/" + id + "/spend", "{\"amount_minor\":300}");
      JsonNode r =
          f.ok("POST", "/v1/store-credits/" + id + "/revoke", null, "Idempotency-Key", "rv");
      assertThat(r.get("status").asText()).isEqualTo("revoked");
      assertThat(r.get("revoked_minor").asLong()).isEqualTo(700);
      assertThat(r.get("balance_minor").asLong()).isZero();
      JsonNode again =
          f.ok("POST", "/v1/store-credits/" + id + "/revoke", null, "Idempotency-Key", "rv2");
      assertThat(again.get("revoked_minor").asLong()).isEqualTo(700);
      assertThat(
              f.call("POST", "/v1/store-credits/SC-NOPE/revoke", null, "Idempotency-Key", "z")
                  .status())
          .isEqualTo(404);
    }

    @Test
    void spendMoreThanBalanceFails() {
      String id = credit(100, "s");
      assertThat(
              code(
                  f.call(
                      "POST", "/admin/store-credits/" + id + "/spend", "{\"amount_minor\":101}")))
          .isEqualTo("insufficient_balance");
    }
  }

  @Nested
  class Emails {
    String email(String template) {
      return "{\"customer_id\":\"CUS-0003\",\"template\":\""
          + template
          + "\",\"variables\":{\"order_id\":null},\"metadata\":{\"undo_action_id\":\"e1\"}}";
    }

    @Test
    void emailIsAcceptedAndBecomesSent() {
      ShopResponse r = f.call("POST", "/v1/emails", email("need_more_info"));
      assertThat(r.status()).isEqualTo(202);
      assertThat(Json.parse(r.body()).get("status").asText()).isEqualTo("queued");
      f.now = f.now.plusSeconds(2);
      f.store.tx(tx -> Settlement.runDue(tx, f.random, f.now, FaultConfig.CALM));
      assertThat(f.store.<String>tx(tx -> tx.emails().get(0).status())).isEqualTo("sent");
    }

    @Test
    void unknownTemplateIs422() {
      assertThat(code(f.call("POST", "/v1/emails", email("spam")))).isEqualTo("unknown_template");
    }

    @Test
    void exportLagsBehind() {
      f.call("POST", "/v1/emails", email("need_more_info"));
      String q =
          "/v1/exports/emails?from=" + f.now.minusSeconds(60) + "&to=" + f.now.plusSeconds(3600);
      assertThat(f.ok("GET", q, null).get("data")).isEmpty();
      f.now = f.now.plus(Duration.ofMinutes(16));
      assertThat(f.ok("GET", q, null).get("data")).hasSize(1);
    }
  }

  @Nested
  class Admin {
    @Test
    void settlementByRunAndLimit() {
      f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k1");
      f.ok("POST", "/v1/refunds", refundBody("ORD-0002", 100), "Idempotency-Key", "k2");
      JsonNode out = f.ok("POST", "/admin/settlement/run?limit=1&undo_run_id=r1", null);
      assertThat(out.get("settled")).hasSize(1);
      assertThat(f.ok("POST", "/admin/settlement/run?undo_run_id=other", null).get("settled"))
          .isEmpty();
    }

    @Test
    void scheduledSettlementHonoursDelayAndFailureRate() {
      f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k1");
      assertThat(
              f.store.<Integer>tx(tx -> Settlement.runDue(tx, f.random, f.now, FaultConfig.CALM)))
          .isZero();
      f.now = f.now.plus(Duration.ofMinutes(11));
      FaultConfig alwaysFail =
          FaultConfig.CALM.merge(Json.parse("{\"settlement_failure_rate\":1}".getBytes()));
      assertThat(f.store.<Integer>tx(tx -> Settlement.runDue(tx, f.random, f.now, alwaysFail)))
          .isEqualTo(1);
      assertThat(
              f.ok("GET", "/v1/refunds?order_id=ORD-0001", null)
                  .get("data")
                  .get(0)
                  .get("status")
                  .asText())
          .isEqualTo("failed");
    }

    @Test
    void carrierBugMarksAndRestores23Orders() {
      JsonNode on = f.ok("POST", "/admin/carrier-bug", "{\"enabled\":true}");
      assertThat(on.get("orders")).hasSize(23);
      assertThat(f.ok("GET", "/admin/lost-orders", null).get("data")).hasSize(23);
      for (JsonNode id : on.get("orders")) {
        JsonNode o = f.ok("GET", "/v1/orders/" + id.asText(), null);
        assertThat(o.get("total_minor").asLong()).isBetween(50_000L, 900_000L);
      }
      assertThat(f.ok("POST", "/admin/carrier-bug", "{\"enabled\":false}").get("orders"))
          .hasSize(23);
      assertThat(f.ok("GET", "/admin/lost-orders", null).get("data")).isEmpty();
    }

    @Test
    void faultsMergeAndPresets() {
      JsonNode cfg = f.ok("POST", "/admin/faults", "{\"drop_after_commit_rate\":0.5}");
      assertThat(cfg.get("drop_after_commit_rate").asDouble()).isEqualTo(0.5);
      assertThat(f.ok("GET", "/admin/faults", null).get("drop_after_commit_rate").asDouble())
          .isEqualTo(0.5);
      assertThat(
              f.ok("POST", "/admin/faults", "{\"preset\":\"calm\"}")
                  .get("drop_after_commit_rate")
                  .asDouble())
          .isZero();
      assertThat(
              f.ok("POST", "/admin/faults", "{\"preset\":\"chaos\"}")
                  .get("webhook_duplicate_rate")
                  .asDouble())
          .isEqualTo(0.1);
      assertThat(f.call("POST", "/admin/faults", "{\"error_5xx_rate\":2}").status()).isEqualTo(400);
    }

    @Test
    void invariantsAreZeroAndCountDuplicates() {
      assertThat(f.ok("GET", "/admin/invariants", null).toString()).doesNotContain(":1");
      f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k1");
      f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k2");
      JsonNode inv = f.ok("GET", "/admin/invariants", null);
      assertThat(inv.get("orders_with_multiple_undo_refunds").asInt()).isEqualTo(1);
      assertThat(inv.get("undo_action_ids_on_multiple_refunds").asInt()).isEqualTo(1);
      assertThat(inv.get("over_refunded_orders").asInt()).isZero();
    }

    @Test
    void statsAndGroundTruthAndPageData() {
      assertThat(f.ok("GET", "/admin/stats", null).get("tickets").get("total").asInt())
          .isEqualTo(200);
      JsonNode truth = f.ok("GET", "/admin/ground-truth", null);
      assertThat(truth).hasSize(200);
      assertThat(f.ok("GET", "/admin/page-data", null).has("invariants")).isTrue();
    }
  }

  @Nested
  class Webhooks {
    @Test
    void cancelWritesASignedVerifiableWebhook() {
      String id =
          f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k")
              .get("id")
              .asText();
      f.ok("POST", "/v1/refunds/" + id + "/cancel", null, "Idempotency-Key", "c");
      List<OutboxRow> due = f.store.tx(tx -> WebhookOutbox.due(tx, f.now, 10));
      assertThat(due).hasSize(1);
      OutboxRow row = due.get(0);
      byte[] body = WebhookOutbox.body(row);
      Map<String, String> h = WebhookOutbox.headers(row, body, ShopFixture.SECRET, f.now);
      assertThat(h.get("Shopfront-Event-Id")).startsWith("evt_");
      String sig = h.get("Shopfront-Signature");
      long t = Long.parseLong(sig.substring(2, sig.indexOf(',')));
      String v1 = sig.substring(sig.indexOf("v1=") + 3);
      assertThat(v1).isEqualTo(WebhookOutbox.sign(ShopFixture.SECRET, t, body));
      assertThat(v1).isNotEqualTo(WebhookOutbox.sign("wrong", t, body));
      JsonNode payload = Json.parse(body);
      assertThat(payload.get("type").asText()).isEqualTo("refund.cancelled");
      assertThat(payload.at("/data/object/id").asText()).isEqualTo(id);
    }

    @Test
    void failedDeliveryBacksOffAndStopsAfterTenAttempts() {
      String id =
          f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k")
              .get("id")
              .asText();
      f.ok("POST", "/v1/refunds/" + id + "/cancel", null, "Idempotency-Key", "c");
      for (int i = 0; i < 10; i++) {
        List<OutboxRow> due = f.store.tx(tx -> WebhookOutbox.due(tx, f.now, 10));
        assertThat(due).hasSize(1);
        f.store.tx(
            tx -> {
              WebhookOutbox.markFailed(tx, due.get(0).id(), f.now);
              return null;
            });
        f.now = f.now.plusSeconds(300);
      }
      assertThat(f.store.<List<OutboxRow>>tx(tx -> WebhookOutbox.due(tx, f.now, 10))).isEmpty();
    }

    @Test
    void revokeAndSettlementEmitWebhooks() {
      String sc =
          f.ok(
                  "POST",
                  "/v1/store-credits",
                  "{\"customer_id\":\"CUS-0002\",\"amount_minor\":10,\"reason\":\"other\",\"reference\":\"x\"}")
              .get("id")
              .asText();
      f.ok("POST", "/v1/store-credits/" + sc + "/revoke", null, "Idempotency-Key", "r");
      f.ok("POST", "/v1/refunds", refundBody("ORD-0001", 100), "Idempotency-Key", "k");
      f.ok("POST", "/admin/settlement/run", null);
      List<String> types =
          f.store.tx(tx -> tx.outbox().stream().map(OutboxRow::type).sorted().toList());
      assertThat(types).containsExactly("refund.settled", "store_credit.revoked");
    }
  }
}
