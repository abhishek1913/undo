package dev.undo.connector.shopfront;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.connector.ActionView;
import dev.undo.core.connector.JournalSpec;
import dev.undo.core.connector.PostingLine;
import dev.undo.core.connector.ReversalPlan;
import dev.undo.core.json.Json;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Table-driven tests for reversal and journal methods (Section 13.3). */
class PureMethodsTest {
  static final ShopfrontConnector C =
      new ShopfrontConnector(
          r -> null,
          new ShopfrontConnector.Config("k", "s", Duration.ofMinutes(15), Duration.ofSeconds(3)),
          () -> Instant.EPOCH);

  static ActionView view(
      String tool, String args, long money, String ref, JsonNode before, JsonNode result) {
    return new ActionView(
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        tool,
        Json.parse(args),
        "h",
        ActionKind.PRIMARY,
        ActionStatus.SUCCEEDED,
        "undo_x",
        ref,
        "pending",
        result,
        Json.obj(),
        before,
        money,
        1);
  }

  static final String REFUND_ARGS =
      "{\"ticket_id\":null,\"order_id\":\"ORD-0001\",\"amount_minor\":5000,\"reason\":\"damaged\"}";
  static final String CREDIT_ARGS =
      "{\"ticket_id\":\"TKT-0001\",\"customer_id\":\"CUS-0001\",\"amount_minor\":1000,\"reason\":\"late_delivery\"}";

  @ParameterizedTest(name = "refund {0} -> {1}")
  @CsvSource({
    "pending,Compensate,5000",
    "settled,Irreversible,5000",
    "failed,NoEffect,0",
    "cancelled,NoEffect,0"
  })
  void refundReversal(String status, String kind, long money) {
    ReversalPlan p =
        C.reversal(
            view("shop_issue_refund", REFUND_ARGS, 5000, "RF-AAAAAAAA", null, null),
            Json.parse(
                "{\"id\":\"RF-AAAAAAAA\",\"status\":\"" + status + "\",\"amount_minor\":5000}"),
            Instant.EPOCH);
    assertThat(p.getClass().getSimpleName()).isEqualTo(kind);
    if (p instanceof ReversalPlan.Compensate c) {
      assertThat(c.tool()).isEqualTo("shop_cancel_refund");
      assertThat(c.args().get("refund_id").asText()).isEqualTo("RF-AAAAAAAA");
      assertThat(c.moneyRecoveredMinor()).isEqualTo(money);
    }
    if (p instanceof ReversalPlan.Irreversible i) {
      assertThat(i.reason()).isEqualTo("Refund already settled to the customer's bank");
      assertThat(i.remediation()).isEmpty();
      assertThat(i.moneyNotRecoveredMinor()).isEqualTo(money);
    }
  }

  @ParameterizedTest(name = "credit {0} balance {1} -> {2}")
  @CsvSource({
    "active,1000,Compensate,1000",
    "active,400,Compensate,400",
    "active,0,Irreversible,0",
    "revoked,0,NoEffect,0"
  })
  void creditReversal(String status, long balance, String kind, long recovered) {
    ReversalPlan p =
        C.reversal(
            view("shop_grant_store_credit", CREDIT_ARGS, 1000, "SC-AAAAAAAA", null, null),
            Json.parse(
                "{\"id\":\"SC-AAAAAAAA\",\"status\":\""
                    + status
                    + "\",\"amount_minor\":1000,\"balance_minor\":"
                    + balance
                    + "}"),
            Instant.EPOCH);
    assertThat(p.getClass().getSimpleName()).isEqualTo(kind);
    if (p instanceof ReversalPlan.Compensate c) {
      assertThat(c.tool()).isEqualTo("shop_revoke_store_credit");
      assertThat(c.moneyRecoveredMinor()).isEqualTo(recovered);
    }
    if (p instanceof ReversalPlan.Irreversible i) {
      assertThat(i.reason()).isEqualTo("Store credit already fully spent");
      assertThat(i.moneyNotRecoveredMinor()).isEqualTo(1000);
    }
  }

  @org.junit.jupiter.api.Test
  void emailIsIrreversibleWithACorrection() {
    String args =
        "{\"ticket_id\":\"TKT-0001\",\"customer_id\":\"CUS-0001\",\"template\":\"refund_confirmation\",\"order_id\":\"ORD-0042\",\"amount_minor\":100,\"note\":null}";
    ReversalPlan p =
        C.reversal(
            view("shop_send_email", args, 0, "EM-1", null, Json.obj()), Json.obj(), Instant.EPOCH);
    ReversalPlan.Irreversible i = (ReversalPlan.Irreversible) p;
    assertThat(i.reason()).isEqualTo("Emails cannot be unsent");
    JsonNode r = i.remediation().orElseThrow().args();
    assertThat(r.get("template").asText()).isEqualTo("correction");
    assertThat(r.get("amount_minor").isNull()).isTrue();
    assertThat(r.get("note").asText())
        .isEqualTo(
            "Please ignore our earlier email about order ORD-0042. It was sent by mistake. No action is needed from you.");
  }

  @ParameterizedTest(name = "ticket {0}/{1} -> {2}")
  @CsvSource({"CLOSED,refunded,Compensate", "OPEN,,NoEffect", "CLOSED,rejected,Conflict"})
  void closeTicketReversal(String status, String resolution, String kind) {
    ReversalPlan p =
        C.reversal(
            view(
                "shop_close_ticket",
                "{\"ticket_id\":\"TKT-0001\",\"resolution\":\"refunded\"}",
                0,
                "TKT-0001",
                null,
                null),
            Json.parse(
                "{\"id\":\"TKT-0001\",\"status\":\""
                    + status
                    + "\",\"resolution\":"
                    + (resolution == null ? "null" : "\"" + resolution + "\"")
                    + "}"),
            Instant.EPOCH);
    assertThat(p.getClass().getSimpleName()).isEqualTo(kind);
    if (p instanceof ReversalPlan.Compensate c) {
      assertThat(c.tool()).isEqualTo("shop_reopen_ticket");
      assertThat(c.args().get("expected_resolution").asText()).isEqualTo("refunded");
    }
  }

  @ParameterizedTest(name = "note current={0} -> {1}")
  @CsvSource({"ours,Compensate", "human edit,Conflict"})
  void noteReversalComparesText(String current, String kind) {
    ReversalPlan p =
        C.reversal(
            view(
                "shop_set_order_note",
                "{\"order_id\":\"ORD-0001\",\"note\":\"ours\"}",
                0,
                "ORD-0001",
                Json.parse("{\"note\":\"before\",\"note_version\":3}"),
                null),
            Json.parse("{\"id\":\"ORD-0001\",\"note\":\"" + current + "\",\"note_version\":4}"),
            Instant.EPOCH);
    assertThat(p.getClass().getSimpleName()).isEqualTo(kind);
    if (p instanceof ReversalPlan.Compensate c) {
      assertThat(c.tool()).isEqualTo("shop_restore_order_note");
      assertThat(c.args().get("note").asText()).isEqualTo("before");
      assertThat(c.args().get("expected_version").asInt()).isEqualTo(4);
    }
    if (p instanceof ReversalPlan.Conflict c) {
      assertThat(c.reason()).isEqualTo("The note was changed after the agent wrote it");
    }
  }

  static long sum(List<JournalSpec> specs) {
    return specs.stream()
        .flatMap(s -> s.lines().stream())
        .mapToLong(PostingLine::amountMinor)
        .sum();
  }

  @ParameterizedTest(name = "refund status {0} -> {1}")
  @CsvSource({"settled,REFUND_SETTLED", "failed,REFUND_FAILED", "cancelled,REFUND_CANCELLED"})
  void refundJournals(String status, String kind) {
    ActionView a = view("shop_issue_refund", REFUND_ARGS, 5000, "RF-1", null, null);
    List<JournalSpec> issued = C.journalOnSuccess(a);
    assertThat(issued)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.kind()).isEqualTo("REFUND_ISSUED");
              assertThat(s.lines())
                  .containsExactly(
                      new PostingLine("expense:refunds", 5000),
                      new PostingLine("liability:refunds_in_flight:shopfront", -5000));
            });
    List<JournalSpec> st = C.journalOnProviderStatus(a, status);
    assertThat(st).singleElement().satisfies(s -> assertThat(s.kind()).isEqualTo(kind));
    assertThat(sum(st)).isZero();
    assertThat(C.journalOnProviderStatus(a, "pending")).isEmpty();
  }

  @org.junit.jupiter.api.Test
  void compensationJournals() {
    ActionView refund = view("shop_issue_refund", REFUND_ARGS, 5000, "RF-1", null, null);
    assertThat(C.journalOnCompensated(refund, refund))
        .singleElement()
        .satisfies(s -> assertThat(s.kind()).isEqualTo("REFUND_CANCELLED"));
    ActionView credit = view("shop_grant_store_credit", CREDIT_ARGS, 1000, "SC-1", null, null);
    assertThat(C.journalOnSuccess(credit))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.kind()).isEqualTo("CREDIT_GRANTED");
              assertThat(s.lines())
                  .containsExactly(
                      new PostingLine("expense:store_credit", 1000),
                      new PostingLine("liability:store_credit:CUS-0001", -1000));
            });
    ActionView revoke =
        view(
            "shop_revoke_store_credit",
            "{\"store_credit_id\":\"SC-1\"}",
            0,
            "SC-1",
            null,
            Json.parse("{\"revoked_minor\":400}"));
    assertThat(C.journalOnCompensated(credit, revoke))
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.kind()).isEqualTo("CREDIT_REVOKED");
              assertThat(s.lines())
                  .containsExactly(
                      new PostingLine("liability:store_credit:CUS-0001", 400),
                      new PostingLine("expense:store_credit", -400));
            });
    ActionView zero =
        view(
            "shop_revoke_store_credit",
            "{\"store_credit_id\":\"SC-1\"}",
            0,
            "SC-1",
            null,
            Json.parse("{\"revoked_minor\":0}"));
    assertThat(C.journalOnCompensated(credit, zero)).isEmpty();
    assertThat(
            C.journalOnSuccess(
                view("shop_send_email", "{\"customer_id\":\"CUS-0001\"}", 0, "EM-1", null, null)))
        .isEmpty();
  }
}
