package dev.undo.connector.shopfront;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undo.core.connector.ExecutionOutcome;
import dev.undo.core.connector.ToolCall;
import dev.undo.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Section 9.4, row by row, for shop_issue_refund. */
class ClassificationTableTest {
  static ShopfrontConnector connector(ShopfrontApi api) {
    return new ShopfrontConnector(
        api,
        new ShopfrontConnector.Config("k", "s", Duration.ofMinutes(15), Duration.ofSeconds(3)),
        () -> Instant.EPOCH);
  }

  static ToolCall refund() {
    return new ToolCall(
        "shop_issue_refund",
        Json.parse(
            "{\"ticket_id\":null,\"order_id\":\"ORD-0001\",\"amount_minor\":100,\"reason\":\"damaged\"}"),
        UUID.fromString("00000000-0000-7000-8000-000000000001"),
        "undo_x",
        1,
        UUID.fromString("00000000-0000-7000-8000-000000000002"),
        null);
  }

  static ShopfrontApi answering(int status, String body, Map<String, String> headers) {
    return r -> new ShopResponse(status, headers, body.getBytes(StandardCharsets.UTF_8));
  }

  static String err(String code) {
    return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"m\",\"details\":{}}}";
  }

  @Test
  void http2xxIsSucceeded() {
    String body =
        "{\"id\":\"RF-AAAAAAAA\",\"order_id\":\"ORD-0001\",\"amount_minor\":100,\"status\":\"pending\",\"settles_after\":\"2026-01-01T00:00:00Z\"}";
    ExecutionOutcome o = connector(answering(201, body, Map.of())).execute(refund());
    assertThat(o).isInstanceOf(ExecutionOutcome.Succeeded.class);
    ExecutionOutcome.Succeeded s = (ExecutionOutcome.Succeeded) o;
    assertThat(s.providerRef()).isEqualTo("RF-AAAAAAAA");
    assertThat(s.providerStatus()).isEqualTo("pending");
    assertThat(s.result().get("refund_id").asText()).isEqualTo("RF-AAAAAAAA");
  }

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource({
    "400,invalid_request,FailedDefinitive",
    "404,order_not_found,FailedDefinitive",
    "422,amount_exceeds_refundable,FailedDefinitive",
    "422,idempotency_key_reused,FailedDefinitive",
    "401,unauthorized,FailedDefinitive",
    "403,forbidden,FailedDefinitive",
    "409,idempotency_request_in_progress,Unknown",
    "409,refund_not_cancellable,FailedDefinitive",
    "409,ticket_resolution_conflict,FailedDefinitive",
    "429,rate_limited,RetryableNotApplied",
    "500,boom,Unknown",
    "502,,Unknown",
    "503,service_unavailable,Unknown"
  })
  void httpStatuses(int status, String code, String expected) {
    String body = code == null ? "" : err(code);
    ExecutionOutcome o = connector(answering(status, body, Map.of())).execute(refund());
    assertThat(o.getClass().getSimpleName()).isEqualTo(expected);
    if (o instanceof ExecutionOutcome.FailedDefinitive f) {
      assertThat(f.code()).isEqualTo(code);
      assertThat(f.httpStatus()).isEqualTo(status);
    }
  }

  @Test
  void rateLimitUsesRetryAfter() {
    ExecutionOutcome o =
        connector(answering(429, err("rate_limited"), Map.of("Retry-After", "7")))
            .execute(refund());
    assertThat(((ExecutionOutcome.RetryableNotApplied) o).retryAfter())
        .isEqualTo(Duration.ofSeconds(7));
    ExecutionOutcome d = connector(answering(429, err("rate_limited"), Map.of())).execute(refund());
    assertThat(((ExecutionOutcome.RetryableNotApplied) d).retryAfter())
        .isEqualTo(Duration.ofSeconds(1));
  }

  @ParameterizedTest
  @CsvSource({"CONNECT_FAILED,RetryableNotApplied", "TIMEOUT,Unknown", "RESET,Unknown"})
  void transportFailures(ShopTransportException.Kind kind, String expected) {
    ShopfrontApi api =
        r -> {
          throw new ShopTransportException(kind, "x");
        };
    assertThat(connector(api).execute(refund()).getClass().getSimpleName()).isEqualTo(expected);
  }

  @Test
  void unparseable2xxBodyIsUnknownNotFailed() {
    assertThat(connector(answering(201, "not json", Map.of())).execute(refund()))
        .isInstanceOf(ExecutionOutcome.Unknown.class);
  }

  @Test
  void casConflictReadsCurrentState() {
    // 412 then GET shows our text → Succeeded; GET shows other text → conflict.
    ToolCall note =
        new ToolCall(
            "shop_set_order_note",
            Json.parse("{\"order_id\":\"ORD-0001\",\"note\":\"ours\"}"),
            UUID.randomUUID(),
            "undo_n",
            2,
            UUID.randomUUID(),
            Json.parse("{\"note\":null,\"note_version\":0}"));
    ShopfrontApi ours =
        r ->
            r.method().equals("PUT")
                ? new ShopResponse(
                    412, Map.of(), err("precondition_failed").getBytes(StandardCharsets.UTF_8))
                : new ShopResponse(
                    200,
                    Map.of(),
                    "{\"id\":\"ORD-0001\",\"note\":\"ours\",\"note_version\":1}"
                        .getBytes(StandardCharsets.UTF_8));
    assertThat(connector(ours).execute(note)).isInstanceOf(ExecutionOutcome.Succeeded.class);
    ShopfrontApi theirs =
        r ->
            r.method().equals("PUT")
                ? new ShopResponse(
                    412, Map.of(), err("precondition_failed").getBytes(StandardCharsets.UTF_8))
                : new ShopResponse(
                    200,
                    Map.of(),
                    "{\"id\":\"ORD-0001\",\"note\":\"human\",\"note_version\":2}"
                        .getBytes(StandardCharsets.UTF_8));
    ExecutionOutcome o = connector(theirs).execute(note);
    assertThat(o).isInstanceOf(ExecutionOutcome.FailedDefinitive.class);
    assertThat(((ExecutionOutcome.FailedDefinitive) o).code()).isEqualTo("conflict");
  }
}
