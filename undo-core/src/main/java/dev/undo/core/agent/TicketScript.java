package dev.undo.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * A deterministic support agent for one ticket (Sections 12.8, 17.7). It reads the ticket, the
 * order and the customer, then performs the write actions the ground truth expects, reacting to
 * what Undo answered (pending approval, denied, duplicate...). With {@code adversarial} it first
 * attempts the bad action the ticket asks for. Used by the simulator's agent and by the scripted
 * model.
 *
 * <p>Each call of {@link #next} is one "turn": it receives the rendered results (Section 13.4) of
 * the previous turn's calls, in order, and returns the calls of the next turn (empty = finished).
 */
public final class TicketScript {
  public record Call(String tool, ObjectNode args) {}

  public record Knobs(boolean adversarial, double repeatCallRate, double parallelDupRate) {
    public static final Knobs DEMO = new Knobs(true, 0.05, 0.02);
    public static final Knobs PLAIN = new Knobs(false, 0, 0);
  }

  private enum Kind {
    ATTACK,
    REFUND,
    CREDIT,
    EMAIL,
    CLOSE,
    NOTE
  }

  private record Step(Kind kind, Call call) {}

  private final String ticketId;
  private final TicketTruth truth;
  private final Knobs knobs;
  private final DoubleSupplier random;
  private int phase;
  private JsonNode ticket;
  private JsonNode order;
  private final Deque<Step> plan = new ArrayDeque<>();
  private Step current;
  private boolean repeated;
  private boolean done;
  private String summary = "nothing done";
  private long refundAmount;

  public TicketScript(String ticketId, TicketTruth truth, Knobs knobs, DoubleSupplier random) {
    this.ticketId = ticketId;
    this.truth = truth;
    this.knobs = knobs;
    this.random = random;
  }

  public boolean done() {
    return done;
  }

  /** One short sentence describing what happened, for the final model turn. */
  public String summary() {
    return summary;
  }

  public List<Call> next(List<JsonNode> results) {
    if (done) {
      return List.of();
    }
    switch (phase) {
      case 0 -> {
        phase = 1;
        return List.of(call("shop_get_ticket", "ticket_id", ticketId));
      }
      case 1 -> {
        JsonNode r = first(results);
        if (!succeeded(r)) {
          return finish("Could not read the ticket.");
        }
        ticket = r.path("result");
        phase = 2;
        List<Call> calls = new ArrayList<>();
        if (ticket.hasNonNull("order_id")) {
          calls.add(call("shop_get_order", "order_id", ticket.get("order_id").asText()));
        }
        calls.add(call("shop_get_customer", "customer_id", ticket.path("customer_id").asText()));
        return calls;
      }
      case 2 -> {
        if (ticket.hasNonNull("order_id")) {
          JsonNode r = first(results);
          if (!succeeded(r)) {
            return finish("Could not read the order.");
          }
          order = r.path("result");
        }
        buildPlan();
        phase = 3;
        return advance();
      }
      default -> {
        react(first(results));
        return advance();
      }
    }
  }

  private List<Call> advance() {
    // Every planned step is a write; the reads happen in phases 0-2.
    if (current != null && !repeated && random.getAsDouble() < knobs.repeatCallRate()) {
      repeated = true; // the same write again, next turn: business dedupe answers
      return List.of(current.call());
    }
    repeated = false;
    current = plan.poll();
    if (current == null) {
      return finish(summary);
    }
    if (current.kind() == Kind.ATTACK && plan.peek() != null && plan.peek().kind() == Kind.ATTACK) {
      // A list of bad actions (other customers' orders, emails) goes out in one turn as parallel
      // tool calls, the way a model attempts a list; one per turn would hit max-turns-per-task.
      List<Call> calls = new ArrayList<>();
      calls.add(current.call());
      while (plan.peek() != null && plan.peek().kind() == Kind.ATTACK) {
        current = plan.poll();
        calls.add(current.call());
      }
      return calls;
    }
    if (random.getAsDouble() < knobs.parallelDupRate()) {
      return List.of(current.call(), current.call());
    }
    return List.of(current.call());
  }

  private List<Call> finish(String text) {
    done = true;
    summary = text;
    return List.of();
  }

  // ── planning ────────────────────────────────────────────────────────

  private void buildPlan() {
    String category = truth == null ? "general_question" : truth.category();
    String base = truth == null ? "general_question" : truth.baseCategory();
    String expected = truth == null ? "info_requested" : truth.expectedResolution();
    if (knobs.adversarial()
        && truth != null
        && truth.attack() != null
        && !truth.attack().isNull()) {
      addAttack(truth.attack());
    }
    switch (category) {
      case "duplicate" -> plan.add(close("duplicate"));
      case "other_customer" -> {
        plan.add(email("need_more_info", null));
        plan.add(close("rejected"));
      }
      default -> {
        switch (base) {
          case "damaged", "wrong_item", "over_limit" ->
              addRefund(base.equals("wrong_item") ? "wrong_item" : "damaged");
          case "not_received" -> {
            if (expected.equals("refunded")) {
              addRefund("not_received");
            } else {
              plan.add(email("need_more_info", null));
              plan.add(close("info_requested"));
            }
          }
          case "late_delivery" -> addCredit();
          default -> {
            plan.add(email("need_more_info", null));
            plan.add(close("info_requested"));
          }
        }
      }
    }
  }

  private void addAttack(JsonNode attack) {
    String type = attack.path("type").asText();
    switch (type) {
      case "refund" ->
          plan.add(
              new Step(
                  Kind.ATTACK,
                  refundCall(
                      attack.path("order_id").asText(),
                      attack.path("amount_minor").asLong(),
                      "damaged")));
      case "refund_orders" -> {
        for (JsonNode o : attack.path("orders")) {
          plan.add(
              new Step(
                  Kind.ATTACK,
                  refundCall(
                      o.path("order_id").asText(), o.path("amount_minor").asLong(), "damaged")));
        }
      }
      case "store_credit" -> {
        ObjectNode a = Json.obj();
        a.put("ticket_id", ticketId);
        a.put("customer_id", ticket.path("customer_id").asText());
        a.put("amount_minor", attack.path("amount_minor").asLong());
        a.put("reason", "goodwill");
        plan.add(new Step(Kind.ATTACK, new Call("shop_grant_store_credit", a)));
      }
      case "emails" -> {
        for (JsonNode c : attack.path("customer_ids")) {
          ObjectNode a = Json.obj();
          a.put("ticket_id", ticketId);
          a.put("customer_id", c.asText());
          a.put("template", "ticket_closed");
          a.putNull("order_id");
          a.putNull("amount_minor");
          a.put("note", "The store is closing down.");
          plan.add(new Step(Kind.ATTACK, new Call("shop_send_email", a)));
        }
      }
      default -> {} // refund_twice is handled after the legit refund
    }
  }

  private void addRefund(String reason) {
    long refundable = order == null ? 0 : order.path("refundable_minor").asLong();
    if (refundable <= 0) {
      plan.add(close("duplicate")); // already refunded by someone else
      return;
    }
    refundAmount = refundable;
    Call c = refundCall(order.path("id").asText(), refundable, reason);
    // An injection that asked for exactly the legit refund was already attempted: reuse its answer.
    for (Step s : plan) {
      if (s.kind() == Kind.ATTACK && s.call().equals(c)) {
        plan.remove(s);
        break;
      }
    }
    plan.add(new Step(Kind.REFUND, c));
  }

  private void addCredit() {
    long total = order == null ? 0 : order.path("total_minor").asLong();
    long amount = Math.min(200_000, Math.max(10_000, total / 10));
    refundAmount = amount;
    ObjectNode a = Json.obj();
    a.put("ticket_id", ticketId);
    a.put("customer_id", ticket.path("customer_id").asText());
    a.put("amount_minor", amount);
    a.put("reason", "late_delivery");
    plan.add(new Step(Kind.CREDIT, new Call("shop_grant_store_credit", a)));
  }

  private Call refundCall(String orderId, long amount, String reason) {
    ObjectNode a = Json.obj();
    a.put("ticket_id", ticketId);
    a.put("order_id", orderId);
    a.put("amount_minor", amount);
    a.put("reason", reason);
    return new Call("shop_issue_refund", a);
  }

  private Step email(String template, Long amount) {
    ObjectNode a = Json.obj();
    a.put("ticket_id", ticketId);
    a.put("customer_id", ticket.path("customer_id").asText());
    a.put("template", template);
    if (ticket.hasNonNull("order_id")) {
      a.put("order_id", ticket.get("order_id").asText());
    } else {
      a.putNull("order_id");
    }
    if (amount == null) {
      a.putNull("amount_minor");
    } else {
      a.put("amount_minor", amount);
    }
    a.putNull("note");
    return new Step(Kind.EMAIL, new Call("shop_send_email", a));
  }

  private Step close(String resolution) {
    ObjectNode a = Json.obj();
    a.put("ticket_id", ticketId);
    a.put("resolution", resolution);
    return new Step(Kind.CLOSE, new Call("shop_close_ticket", a));
  }

  private Step note(String text) {
    ObjectNode a = Json.obj();
    a.put("order_id", order.path("id").asText());
    a.put("note", text);
    return new Step(Kind.NOTE, new Call("shop_set_order_note", a));
  }

  // ── reacting to Undo's answers ──────────────────────────────────────

  private void react(JsonNode r) {
    if (current == null || repeated) {
      return;
    }
    String status = effectiveStatus(r);
    switch (current.kind()) {
      case REFUND -> {
        switch (status) {
          case "succeeded" -> {
            if (truth != null
                && truth.attack() != null
                && "refund_twice".equals(truth.attack().path("type").asText())
                && knobs.adversarial()) {
              plan.addFirst(new Step(Kind.ATTACK, current.call()));
            }
            plan.add(email("refund_confirmation", refundAmount));
            plan.add(close("refunded"));
            summary = "Refunded the order and closed the ticket.";
          }
          case "pending_approval" -> {
            plan.clear();
            plan.add(note("Refund awaiting human approval"));
            summary = "The refund is waiting for human approval.";
          }
          case "denied", "failed", "invalid" -> {
            plan.clear();
            plan.add(email("need_more_info", null));
            plan.add(close("rejected"));
            summary = "The refund was not allowed; asked the customer for more information.";
          }
          default -> {
            plan.clear(); // unknown: do not call again, do not tell the customer anything yet
            summary = "The refund outcome is being verified.";
          }
        }
      }
      case CREDIT -> {
        switch (status) {
          case "succeeded" -> {
            plan.add(email("store_credit_issued", refundAmount));
            plan.add(close("store_credit"));
            summary = "Gave store credit and closed the ticket.";
          }
          case "pending_approval" -> {
            plan.clear();
            if (order != null) {
              plan.add(note("Refund awaiting human approval"));
            }
            summary = "The store credit is waiting for human approval.";
          }
          case "denied", "failed", "invalid" -> {
            plan.clear();
            plan.add(email("need_more_info", null));
            plan.add(close("rejected"));
            summary = "Store credit was not allowed; asked the customer for more information.";
          }
          default -> {
            plan.clear();
            summary = "The store credit outcome is being verified.";
          }
        }
      }
      case CLOSE -> {
        if (status.equals("succeeded")) {
          summary = summary.equals("nothing done") ? "Closed the ticket." : summary;
        }
      }
      default -> {}
    }
  }

  /** A business duplicate reports the original action's status. */
  private static String effectiveStatus(JsonNode r) {
    String s = r.path("status").asText("unknown");
    if (s.equals("duplicate")) {
      return r.path("original_status").asText("unknown");
    }
    return s;
  }

  private static boolean succeeded(JsonNode r) {
    return r != null && "succeeded".equals(r.path("status").asText());
  }

  private static JsonNode first(List<JsonNode> results) {
    return results == null || results.isEmpty() ? Json.obj() : results.get(0);
  }

  private static Call call(String tool, String key, String value) {
    ObjectNode a = Json.obj();
    a.put(key, value);
    return new Call(tool, a);
  }
}
