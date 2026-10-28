package dev.shopfront.core.router;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.shopfront.core.domain.Customer;
import dev.shopfront.core.domain.Email;
import dev.shopfront.core.domain.Order;
import dev.shopfront.core.domain.Refund;
import dev.shopfront.core.domain.StoreCredit;
import dev.shopfront.core.domain.Ticket;
import dev.shopfront.core.json.Json;
import java.time.Instant;
import java.util.List;

/** JSON shapes returned by /v1 (snake_case, money in minor units). */
public final class Views {
  private Views() {}

  static String ts(Instant t) {
    return t == null ? null : t.toString();
  }

  public static ObjectNode customer(Customer c, List<StoreCredit> credits) {
    long balance = 0;
    for (StoreCredit sc : credits) {
      if (sc.status().equals("active")) {
        balance += sc.balanceMinor();
      }
    }
    ObjectNode o = Json.obj();
    o.put("id", c.id());
    o.put("name", c.name());
    o.put("email", c.email());
    o.put("created_at", ts(c.createdAt()));
    o.put("store_credit_balance_minor", balance);
    return o;
  }

  public static long refunded(List<Refund> refunds) {
    long sum = 0;
    for (Refund r : refunds) {
      if (r.counts()) {
        sum += r.amountMinor();
      }
    }
    return sum;
  }

  public static ObjectNode order(Order o, List<Refund> refunds) {
    long refunded = refunded(refunds);
    ObjectNode n = Json.obj();
    n.put("id", o.id());
    n.put("customer_id", o.customerId());
    n.put("item_name", o.itemName());
    n.put("total_minor", o.totalMinor());
    n.put("currency", o.currency());
    n.put("status", o.status());
    n.put("carrier_status", o.carrierStatus());
    n.put("placed_at", ts(o.placedAt()));
    n.put("delivered_at", ts(o.deliveredAt()));
    n.put("note", o.note());
    n.put("note_version", o.noteVersion());
    n.put("refunded_minor", refunded);
    n.put("refundable_minor", o.totalMinor() - refunded);
    return n;
  }

  public static ObjectNode ticket(Ticket t) {
    ObjectNode n = Json.obj();
    n.put("id", t.id());
    n.put("customer_id", t.customerId());
    n.put("order_id", t.orderId());
    n.put("subject", t.subject());
    n.put("body", t.body());
    n.put("status", t.status());
    n.put("resolution", t.resolution());
    n.put("created_at", ts(t.createdAt()));
    n.put("closed_at", ts(t.closedAt()));
    return n;
  }

  public static ObjectNode refund(Refund r) {
    ObjectNode n = Json.obj();
    n.put("id", r.id());
    n.put("order_id", r.orderId());
    n.put("amount_minor", r.amountMinor());
    n.put("reason", r.reason());
    n.put("status", r.status());
    n.set("metadata", r.metadata() == null ? Json.obj() : r.metadata());
    n.put("created_at", ts(r.createdAt()));
    n.put("settles_after", ts(r.settlesAfter()));
    n.put("updated_at", ts(r.updatedAt()));
    return n;
  }

  public static ObjectNode credit(StoreCredit c) {
    ObjectNode n = Json.obj();
    n.put("id", c.id());
    n.put("customer_id", c.customerId());
    n.put("amount_minor", c.amountMinor());
    n.put("balance_minor", c.balanceMinor());
    n.put("reason", c.reason());
    n.put("reference", c.reference());
    n.put("status", c.status());
    n.put("revoked_minor", c.revokedMinor());
    n.put("created_at", ts(c.createdAt()));
    n.put("updated_at", ts(c.updatedAt()));
    return n;
  }

  public static ObjectNode email(Email e) {
    ObjectNode n = Json.obj();
    n.put("id", e.id());
    n.put("customer_id", e.customerId());
    n.put("template", e.template());
    n.set("variables", e.variables() == null ? Json.obj() : e.variables());
    n.set("metadata", e.metadata() == null ? Json.obj() : e.metadata());
    n.put("status", e.status());
    n.put("created_at", ts(e.createdAt()));
    return n;
  }
}
