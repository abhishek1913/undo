package dev.undo.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.LlmTurn;
import dev.undo.core.model.Task;
import java.util.ArrayList;
import java.util.List;

/**
 * The conversation of one task (Section 17.3): the task message, then for each persisted turn the
 * assistant content exactly as the model returned it and, once answered, one user message with
 * every tool_result in tool_use order. Turns are append-only; nothing here edits them.
 */
public final class Transcript {
  private Transcript() {}

  public static List<JsonNode> rebuild(Task task, List<LlmTurn> turns) {
    List<JsonNode> messages = new ArrayList<>();
    ObjectNode first = Json.obj();
    first.put("role", "user");
    first.put("content", task.input.path("message").asText());
    messages.add(first);
    for (LlmTurn t : turns) {
      ObjectNode a = Json.obj();
      a.put("role", "assistant");
      a.set("content", t.response.path("content"));
      messages.add(a);
      if (t.toolResults != null && !t.toolResults.isEmpty()) {
        ObjectNode u = Json.obj();
        u.put("role", "user");
        u.set("content", t.toolResults);
        messages.add(u);
      }
    }
    return messages;
  }

  /** The tool_use blocks of a response, in order. */
  public static List<JsonNode> toolUses(JsonNode response) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode b : response.path("content")) {
      if ("tool_use".equals(b.path("type").asText())) {
        out.add(b);
      }
    }
    return out;
  }

  public static ObjectNode toolResult(String toolUseId, JsonNode rendered, boolean isError) {
    ObjectNode r = Json.obj();
    r.put("type", "tool_result");
    r.put("tool_use_id", toolUseId);
    r.put("content", Json.write(rendered));
    if (isError) {
      r.put("is_error", true);
    }
    return r;
  }

  /** The final text of a finished task (the last text block), or empty. */
  public static String finalText(JsonNode response) {
    String text = "";
    for (JsonNode b : response.path("content")) {
      if ("text".equals(b.path("type").asText())) {
        text = b.path("text").asText();
      }
    }
    return text;
  }

  static ArrayNode arr(List<? extends JsonNode> nodes) {
    ArrayNode a = Json.arr();
    nodes.forEach(a::add);
    return a;
  }
}
