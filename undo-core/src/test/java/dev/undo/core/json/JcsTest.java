package dev.undo.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class JcsTest {
  static String c(String json) {
    return Jcs.canonical(Json.parse(json));
  }

  @Test
  void sortsKeysAndRemovesWhitespace() {
    assertThat(c("{ \"b\" : 2, \"a\" : [ 1 , true , null ] }"))
        .isEqualTo("{\"a\":[1,true,null],\"b\":2}");
  }

  @Test
  void rfc8785StringEscapes() {
    // RFC 8785 section 3.2.2.2: only ", \ and control characters are escaped; short forms where
    // defined.
    assertThat(c("\"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/\""))
        .isEqualTo("\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"");
    assertThat(c("\"\\b\\t\\n\\f\\r\\u0001\\u001f\""))
        .isEqualTo("\"\\b\\t\\n\\f\\r\\u0001\\u001f\"");
  }

  @Test
  void rfc8785KeyOrderingByUtf16CodeUnits() {
    // RFC 8785 section 3.2.3 example (integer values instead of the RFC's strings).
    String in =
        "{\"\\u20ac\":1,\"\\r\":2,\"\\ufb33\":3,\"1\":4,\"\\ud83d\\ude00\":5,\"\\u0080\":6,\"\\u00f6\":7}";
    assertThat(c(in))
        .isEqualTo(
            "{\"\\r\":2,\"1\":4,\"\u0080\":6,\"\u00f6\":7,\"\u20ac\":1,\"\ud83d\ude00\":5,\"\ufb33\":3}");
  }

  @Test
  void integersInShortestForm() {
    assertThat(c("{\"n\":-0,\"m\":100000000,\"big\":123456789012345678901234567890}"))
        .isEqualTo("{\"big\":123456789012345678901234567890,\"m\":100000000,\"n\":0}");
  }

  @Test
  void nestedObjectsAreCanonicalToo() {
    assertThat(c("{\"z\":{\"y\":1,\"x\":{\"b\":1,\"a\":2}},\"a\":[]}"))
        .isEqualTo("{\"a\":[],\"z\":{\"x\":{\"a\":2,\"b\":1},\"y\":1}}");
  }

  @Test
  void floatsAreRejected() {
    assertThatThrownBy(() -> c("{\"x\":1.5}")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void argsHashIsStableAcrossKeyOrder() {
    JsonNode a = Json.parse("{\"order_id\":\"ORD-0001\",\"amount_minor\":100}");
    JsonNode b = Json.parse("{\"amount_minor\":100,\"order_id\":\"ORD-0001\"}");
    assertThat(Hashing.argsHash("shop_issue_refund", a))
        .isEqualTo(Hashing.argsHash("shop_issue_refund", b))
        .hasSize(64);
    assertThat(Hashing.argsHash("shop_issue_refund", a))
        .isNotEqualTo(Hashing.argsHash("shop_get_order", a));
  }
}
