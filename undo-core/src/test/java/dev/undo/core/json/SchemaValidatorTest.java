package dev.undo.core.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {
  static final JsonNode REFUND =
      Json.parse(
          """
          {"type":"object","additionalProperties":false,"required":["ticket_id","order_id","amount_minor","reason"],
           "properties":{"ticket_id":{"type":["string","null"],"pattern":"^TKT-[0-9]{4}$"},
                         "order_id":{"type":"string","pattern":"^ORD-[0-9]{4}$"},
                         "amount_minor":{"type":"integer","minimum":1,"maximum":100000000},
                         "reason":{"type":"string","enum":["damaged","not_received","wrong_item","lost_in_transit","other"]}}}
          """);
  final SchemaValidator v = new SchemaValidator();

  @Test
  void validArgsPass() {
    assertThat(
            v.validate(
                "t",
                REFUND,
                Json.parse(
                    "{\"ticket_id\":\"TKT-0001\",\"order_id\":\"ORD-0001\",\"amount_minor\":100,\"reason\":\"damaged\"}")))
        .isEmpty();
  }

  @Test
  void patternIsAppliedOnlyToStringsSoNullPasses() {
    assertThat(
            v.validate(
                "t",
                REFUND,
                Json.parse(
                    "{\"ticket_id\":null,\"order_id\":\"ORD-0001\",\"amount_minor\":100,\"reason\":\"damaged\"}")))
        .isEmpty();
    assertThat(
            v.validate(
                "t",
                REFUND,
                Json.parse(
                    "{\"ticket_id\":\"BAD\",\"order_id\":\"ORD-0001\",\"amount_minor\":100,\"reason\":\"damaged\"}")))
        .isNotEmpty();
  }

  @Test
  void floatsAreRejectedEvenWhenWhole() {
    assertThat(
            v.validate(
                "t",
                REFUND,
                Json.parse(
                    "{\"ticket_id\":null,\"order_id\":\"ORD-0001\",\"amount_minor\":100.0,\"reason\":\"damaged\"}")))
        .anyMatch(e -> e.contains("amount_minor"));
    assertThat(
            v.validate(
                "t",
                REFUND,
                Json.parse(
                    "{\"ticket_id\":null,\"order_id\":\"ORD-0001\",\"amount_minor\":1.5,\"reason\":\"damaged\"}")))
        .isNotEmpty();
  }

  @Test
  void unknownAndMissingPropertiesAreRejected() {
    assertThat(
            v.validate(
                "t",
                REFUND,
                Json.parse(
                    "{\"ticket_id\":null,\"order_id\":\"ORD-0001\",\"amount_minor\":1,\"reason\":\"damaged\",\"x\":1}")))
        .isNotEmpty();
    assertThat(
            v.validate(
                "t",
                REFUND,
                Json.parse(
                    "{\"order_id\":\"ORD-0001\",\"amount_minor\":1,\"reason\":\"damaged\"}")))
        .isNotEmpty();
    assertThat(v.validate("t", REFUND, Json.parse("[1]"))).containsExactly("$: must be an object");
  }
}
