package dev.shopfront.core.router;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.shopfront.core.domain.Email;
import dev.shopfront.core.domain.Order;
import dev.shopfront.core.domain.Refund;
import dev.shopfront.core.domain.StoreCredit;
import dev.shopfront.core.domain.Ticket;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.store.ShopTx;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The provider's own view of "did anything happen twice" (Section 12.6). All must be 0. */
public final class Invariants {
  private Invariants() {}

  public static ObjectNode compute(ShopTx tx) {
    List<Refund> refunds = tx.refunds();
    Map<String, Integer> undoRefundsPerOrder = new HashMap<>();
    Map<String, Integer> refundsPerAction = new HashMap<>();
    Map<String, Long> refundedPerOrder = new HashMap<>();
    for (Refund r : refunds) {
      if (r.undoActionId() != null) {
        undoRefundsPerOrder.merge(r.orderId(), 1, Integer::sum);
        refundsPerAction.merge(r.undoActionId(), 1, Integer::sum);
      }
      if (r.counts()) {
        refundedPerOrder.merge(r.orderId(), r.amountMinor(), Long::sum);
      }
    }
    Map<String, Integer> creditRefs = new HashMap<>();
    for (StoreCredit c : tx.credits()) {
      if (c.reference() != null) {
        creditRefs.merge(c.reference(), 1, Integer::sum);
      }
    }
    Map<String, Integer> emailsPerAction = new HashMap<>();
    for (Email e : tx.emails()) {
      if (e.undoActionId() != null) {
        emailsPerAction.merge(e.undoActionId(), 1, Integer::sum);
      }
    }
    long overRefunded = 0;
    for (Order o : tx.orders()) {
      if (refundedPerOrder.getOrDefault(o.id(), 0L) > o.totalMinor()) {
        overRefunded++;
      }
    }
    ObjectNode out = Json.obj();
    out.put("orders_with_multiple_undo_refunds", countAbove1(undoRefundsPerOrder));
    out.put("undo_action_ids_on_multiple_refunds", countAbove1(refundsPerAction));
    out.put("store_credit_references_used_twice", countAbove1(creditRefs));
    out.put("undo_action_ids_on_multiple_emails", countAbove1(emailsPerAction));
    out.put("over_refunded_orders", overRefunded);
    return out;
  }

  private static long countAbove1(Map<String, Integer> m) {
    return m.values().stream().filter(v -> v > 1).count();
  }

  public static ObjectNode stats(ShopTx tx) {
    ObjectNode out = Json.obj();
    out.put("customers", tx.customers().size());
    out.set("orders", byStatus(tx.orders().stream().map(Order::status).toList()));
    out.set(
        "orders_by_carrier_status",
        byStatus(tx.orders().stream().map(Order::carrierStatus).toList()));
    out.set(
        "tickets",
        byStatus(tx.tickets(null, null, Integer.MAX_VALUE).stream().map(Ticket::status).toList()));
    out.set("refunds", byStatus(tx.refunds().stream().map(Refund::status).toList()));
    out.set("store_credits", byStatus(tx.credits().stream().map(StoreCredit::status).toList()));
    out.set("emails", byStatus(tx.emails().stream().map(Email::status).toList()));
    out.put("webhooks_pending", tx.outbox().stream().filter(r -> r.deliveredAt() == null).count());
    return out;
  }

  private static ObjectNode byStatus(List<String> statuses) {
    TreeMap<String, Integer> counts = new TreeMap<>();
    statuses.forEach(s -> counts.merge(s, 1, Integer::sum));
    ObjectNode o = Json.obj();
    o.put("total", statuses.size());
    counts.forEach(o::put);
    return o;
  }
}
