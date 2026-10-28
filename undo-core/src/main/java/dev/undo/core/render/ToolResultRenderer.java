package dev.undo.core.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import java.util.List;

/** What the agent sees: exactly the shapes of Section 13.4. */
public final class ToolResultRenderer {
  public static final String PENDING_MESSAGE =
      "A human must approve this. It will run automatically if approved. Do not call it again. Continue with other work.";
  public static final String UNKNOWN_MESSAGE =
      "The outcome is being verified. Do not call it again.";

  private ToolResultRenderer() {}

  public static ObjectNode invalid(List<String> errors) {
    ObjectNode o = Json.obj();
    o.put("status", "invalid");
    ArrayNode a = o.putArray("errors");
    errors.forEach(a::add);
    return o;
  }

  /** Renders the action's current state; {@code original} is the action a duplicate points to. */
  public static ObjectNode render(Action a, Action original) {
    ObjectNode o = Json.obj();
    String status = agentStatus(a);
    o.put("status", status);
    o.put("action", a.display());
    switch (status) {
      case "succeeded" -> o.set("result", a.result == null ? Json.obj() : a.result);
      case "duplicate" -> {
        if (original != null) {
          o.put("original_action", original.display());
          o.put("original_status", agentStatus(original));
          o.set("original_result", original.result == null ? Json.nul() : original.result);
        } else {
          o.put("original_action", (String) null);
        }
      }
      case "denied" -> {
        o.put("reason", denyText(a));
        o.put("rule", ruleOf(a));
        o.put("retryable", "facts_unavailable".equals(a.statusReason));
      }
      case "pending_approval" -> o.put("message", PENDING_MESSAGE);
      case "unknown" -> o.put("message", UNKNOWN_MESSAGE);
      case "failed" -> {
        ObjectNode err = o.putObject("error");
        err.put(
            "code", a.error == null ? a.statusReason : a.error.path("code").asText(a.statusReason));
        err.put(
            "message",
            a.error == null
                ? failText(a.statusReason)
                : a.error.path("message").asText(failText(a.statusReason)));
        o.put("retryable", false);
      }
      default -> {}
    }
    return o;
  }

  /** For Claude tool results and MCP: is_error only for invalid and failed. */
  public static boolean isError(JsonNode rendered) {
    String s = rendered.path("status").asText();
    return s.equals("invalid") || s.equals("failed");
  }

  public static String agentStatus(Action a) {
    if (a.status == ActionStatus.DENIED && "duplicate".equals(a.statusReason)) {
      return "duplicate";
    }
    return switch (a.status) {
      case SUCCEEDED -> "succeeded";
      case DENIED, REJECTED, EXPIRED, CANCELLED -> "denied";
      case AWAITING_APPROVAL -> "pending_approval";
      case FAILED -> "failed";
      default -> "unknown";
    };
  }

  private static String ruleOf(Action a) {
    if (a.status != ActionStatus.DENIED) {
      return null;
    }
    if (a.error != null && a.error.hasNonNull("rule")) {
      return a.error.get("rule").asText();
    }
    for (JsonNode m : a.matchedRules == null ? Json.arr() : a.matchedRules) {
      if ("deny".equals(m.path("decision").asText())) {
        return m.path("id").asText();
      }
    }
    return null;
  }

  private static String denyText(Action a) {
    return switch (a.status) {
      case REJECTED -> "A human rejected this action.";
      case EXPIRED -> "Nobody approved this action in time.";
      case CANCELLED -> "The run was stopped before this action ran.";
      default -> {
        String r = a.statusReason == null ? "" : a.statusReason;
        yield switch (r) {
          case "facts_unavailable" ->
              "Undo could not read the facts it needs from the provider. Try again later.";
          case "run_not_active" -> "This run is no longer active.";
          case "internal_tool" -> "This tool is internal and cannot be called by agents.";
          case "tool_not_in_policy", "default" -> "This tool is not allowed by the policy.";
          case "budget_exceeded" ->
              a.error != null
                  ? a.error.path("message").asText("The run budget is used up.")
                  : "The run budget is used up.";
          default ->
              a.error != null && a.error.hasNonNull("message")
                  ? a.error.get("message").asText()
                  : "Denied by the policy.";
        };
      }
    };
  }

  private static String failText(String reason) {
    return switch (reason == null ? "" : reason) {
      case "max_attempts_not_applied" ->
          "The provider kept refusing the request; nothing was done.";
      case "not_applied_run_frozen" -> "The run was stopped; the action did not happen.";
      default -> "The provider refused the request.";
    };
  }
}
