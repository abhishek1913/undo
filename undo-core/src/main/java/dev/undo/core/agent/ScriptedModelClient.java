package dev.undo.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.port.GroundTruth;
import dev.undo.core.port.ModelClient;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The scripted model (Section 17.7): pure and deterministic, and it answers exactly like Claude
 * does to the runner (text and tool_use blocks, stop_reason, usage). It keeps no state: every call
 * replays the request's transcript through a fresh {@link TicketScript} (seeded by the task), so a
 * task resumed on another worker gets the same next turn.
 */
public final class ScriptedModelClient implements ModelClient {
  public static final String MODEL = "scripted";
  private final GroundTruth truth;

  public ScriptedModelClient(GroundTruth truth) {
    this.truth = truth;
  }

  @Override
  public ModelResponse create(ModelRequest req) {
    List<List<JsonNode>> results = new ArrayList<>(); // rendered tool results per answered turn
    int assistantTurns = 0;
    for (JsonNode m : req.messages()) {
      if (m.path("role").asText().equals("assistant")) {
        assistantTurns++;
      } else if (m.path("content").isArray()) {
        List<JsonNode> r = new ArrayList<>();
        for (JsonNode b : m.path("content")) {
          if ("tool_result".equals(b.path("type").asText())) {
            r.add(Json.parse(b.path("content").asText()));
          }
        }
        results.add(r);
      }
    }
    int turn = assistantTurns + 1;
    JsonNode in = req.taskInput();
    List<Planned> calls;
    String summary;
    if ("lost_package".equals(in.path("kind").asText())) {
      calls = lostPackage(in.path("order_id").asText(), results, assistantTurns);
      summary = "Refunded the lost order, noted it and emailed the customer.";
    } else {
      String ticketId = in.path("ticket_id").asText();
      var t = truth.forTicket(ticketId);
      if (t.isEmpty()) {
        return respond(req, turn, List.of(), "Done: the ticket is not in the store.");
      }
      long seed = ticketId.hashCode() * 1_000_003L + req.taskSeq();
      SplittableRandom random = new SplittableRandom(seed);
      TicketScript script =
          new TicketScript(ticketId, t.get(), SupportAgent.knobs(in), random::nextDouble);
      List<JsonNode> last = List.of();
      List<TicketScript.Call> next = script.next(last);
      for (int k = 0; k < assistantTurns && !next.isEmpty(); k++) {
        last = k < results.size() ? results.get(k) : List.of();
        next = script.next(last);
      }
      calls = new ArrayList<>();
      for (TicketScript.Call c : next) {
        calls.add(new Planned(c.tool(), c.args()));
      }
      summary = script.summary();
    }
    return respond(req, turn, calls, "Done: " + summary);
  }

  private record Planned(String tool, JsonNode args) {}

  /** shop_get_order → refund (full refundable) → order note → email → end_turn. */
  private static List<Planned> lostPackage(
      String orderId, List<List<JsonNode>> results, int answered) {
    JsonNode order =
        results.isEmpty() || results.get(0).isEmpty()
            ? Json.obj()
            : results.get(0).get(0).path("result");
    switch (answered) {
      case 0 -> {
        ObjectNode a = Json.obj();
        a.put("order_id", orderId);
        return List.of(new Planned("shop_get_order", a));
      }
      case 1 -> {
        ObjectNode a = Json.obj();
        a.putNull("ticket_id");
        a.put("order_id", orderId);
        a.put("amount_minor", order.path("refundable_minor").asLong());
        a.put("reason", "lost_in_transit");
        return List.of(new Planned("shop_issue_refund", a));
      }
      case 2 -> {
        ObjectNode a = Json.obj();
        a.put("order_id", orderId);
        a.put("note", "Refunded: carrier reported lost");
        return List.of(new Planned("shop_set_order_note", a));
      }
      case 3 -> {
        ObjectNode a = Json.obj();
        a.putNull("ticket_id");
        a.put("customer_id", order.path("customer_id").asText());
        a.put("template", "refund_confirmation");
        a.put("order_id", orderId);
        a.put("amount_minor", order.path("refundable_minor").asLong());
        a.putNull("note");
        return List.of(new Planned("shop_send_email", a));
      }
      default -> {
        return List.of();
      }
    }
  }

  private static ModelResponse respond(
      ModelRequest req, int turn, List<Planned> calls, String finalText) {
    ObjectNode raw = Json.obj();
    raw.put("id", "msg_s_" + req.taskSeq() + "_" + turn);
    raw.put("type", "message");
    raw.put("role", "assistant");
    raw.put("model", MODEL);
    ArrayNode content = raw.putArray("content");
    String stop;
    if (calls.isEmpty()) {
      ObjectNode t = content.addObject();
      t.put("type", "text");
      t.put("text", finalText);
      stop = "end_turn";
    } else {
      for (int i = 0; i < calls.size(); i++) {
        ObjectNode b = content.addObject();
        b.put("type", "tool_use");
        b.put("id", "toolu_s_" + req.taskSeq() + "_" + turn + "_" + i);
        b.put("name", calls.get(i).tool());
        b.set("input", calls.get(i).args());
      }
      stop = "tool_use";
    }
    raw.put("stop_reason", stop);
    raw.putNull("stop_sequence");
    ObjectNode usage = raw.putObject("usage");
    usage.put("input_tokens", 1200);
    usage.put("output_tokens", 150);
    usage.put("cache_read_input_tokens", 0);
    usage.put("cache_creation_input_tokens", 0);
    return new ModelResponse(raw, MODEL, stop, 1200, 150, 0, 0);
  }
}
