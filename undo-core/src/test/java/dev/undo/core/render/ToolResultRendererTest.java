package dev.undo.core.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolResultRendererTest {
  static Action action(long n, ActionStatus s, String reason) {
    Action a = new Action();
    a.number = n;
    a.status = s;
    a.statusReason = reason;
    a.matchedRules = Json.arr();
    return a;
  }

  @Test
  void succeeded() {
    Action a = action(1042, ActionStatus.SUCCEEDED, null);
    a.result = Json.parse("{\"refund_id\":\"RF-X\"}");
    JsonNode r = ToolResultRenderer.render(a, null);
    assertThat(r.toString())
        .isEqualTo(
            "{\"status\":\"succeeded\",\"action\":\"#A-1042\",\"result\":{\"refund_id\":\"RF-X\"}}");
    assertThat(ToolResultRenderer.isError(r)).isFalse();
  }

  @Test
  void deniedByRule() {
    Action a = action(1043, ActionStatus.DENIED, "refund.hard-cap");
    a.error =
        Json.parse(
            "{\"code\":\"policy_denied\",\"message\":\"Refunds above ₹1,00,000 are never allowed for agents\",\"rule\":\"refund.hard-cap\"}");
    assertThat(ToolResultRenderer.render(a, null).toString())
        .isEqualTo(
            "{\"status\":\"denied\",\"action\":\"#A-1043\",\"reason\":\"Refunds above ₹1,00,000 are never allowed for agents\",\"rule\":\"refund.hard-cap\",\"retryable\":false}");
  }

  @Test
  void deniedFactsUnavailableIsRetryable() {
    assertThat(
            ToolResultRenderer.render(action(5, ActionStatus.DENIED, "facts_unavailable"), null)
                .get("retryable")
                .asBoolean())
        .isTrue();
  }

  @Test
  void pendingApproval() {
    assertThat(
            ToolResultRenderer.render(action(1044, ActionStatus.AWAITING_APPROVAL, null), null)
                .toString())
        .isEqualTo(
            "{\"status\":\"pending_approval\",\"action\":\"#A-1044\",\"message\":\"A human must approve this. It will run automatically if approved. Do not call it again. Continue with other work.\"}");
  }

  @Test
  void duplicateCarriesTheOriginal() {
    Action orig = action(990, ActionStatus.SUCCEEDED, null);
    orig.result = Json.parse("{\"refund_id\":\"RF-1\"}");
    JsonNode r = ToolResultRenderer.render(action(1045, ActionStatus.DENIED, "duplicate"), orig);
    assertThat(r.toString())
        .isEqualTo(
            "{\"status\":\"duplicate\",\"action\":\"#A-1045\",\"original_action\":\"#A-0990\",\"original_status\":\"succeeded\",\"original_result\":{\"refund_id\":\"RF-1\"}}");
  }

  @Test
  void unknownForEveryInFlightStatus() {
    for (ActionStatus s :
        List.of(
            ActionStatus.UNKNOWN,
            ActionStatus.EXECUTING,
            ActionStatus.APPROVED,
            ActionStatus.RETRY_WAIT,
            ActionStatus.NEEDS_REVIEW)) {
      assertThat(ToolResultRenderer.render(action(1046, s, null), null).toString())
          .isEqualTo(
              "{\"status\":\"unknown\",\"action\":\"#A-1046\",\"message\":\"The outcome is being verified. Do not call it again.\"}");
    }
  }

  @Test
  void failedIsAnError() {
    Action a = action(1047, ActionStatus.FAILED, "amount_exceeds_refundable");
    a.error =
        Json.parse(
            "{\"code\":\"amount_exceeds_refundable\",\"message\":\"too much\",\"http_status\":422}");
    JsonNode r = ToolResultRenderer.render(a, null);
    assertThat(r.toString())
        .isEqualTo(
            "{\"status\":\"failed\",\"action\":\"#A-1047\",\"error\":{\"code\":\"amount_exceeds_refundable\",\"message\":\"too much\"},\"retryable\":false}");
    assertThat(ToolResultRenderer.isError(r)).isTrue();
  }

  @Test
  void invalidIsAnError() {
    JsonNode r = ToolResultRenderer.invalid(List.of("$.amount_minor: must be an integer"));
    assertThat(r.toString())
        .isEqualTo("{\"status\":\"invalid\",\"errors\":[\"$.amount_minor: must be an integer\"]}");
    assertThat(ToolResultRenderer.isError(r)).isTrue();
  }
}
