package dev.shopfront.core.router;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.shopfront.core.domain.Email;
import dev.shopfront.core.domain.Order;
import dev.shopfront.core.domain.Refund;
import dev.shopfront.core.domain.StoreCredit;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.store.ShopTx;
import java.util.Comparator;
import java.util.List;

/** Data for the provider page (Section 12.9), read from the store directly (not the export). */
public final class PageData {
  private PageData() {}

  public static ObjectNode compute(ShopTx tx) {
    ObjectNode out = Json.obj();
    out.set("invariants", Invariants.compute(tx));
    out.set("stats", Invariants.stats(tx));
    out.set(
        "refunds",
        latest(tx.refunds(), Comparator.comparing(Refund::createdAt), 50, Views::refund));
    out.set(
        "store_credits",
        latest(tx.credits(), Comparator.comparing(StoreCredit::createdAt), 50, Views::credit));
    out.set(
        "emails", latest(tx.emails(), Comparator.comparing(Email::createdAt), 50, Views::email));
    ArrayNode lost = Json.arr();
    for (Order o : tx.orders()) {
      if (o.carrierStatus().equals("LOST")) {
        lost.add(Views.order(o, tx.refundsByOrder(o.id())));
      }
    }
    out.set("lost_orders", lost);
    ArrayNode noted = Json.arr();
    for (Order o : tx.orders()) {
      if (o.note() != null) {
        noted.add(Views.order(o, tx.refundsByOrder(o.id())));
      }
    }
    out.set("orders_with_notes", noted);
    return out;
  }

  private static <T> ArrayNode latest(
      List<T> rows, Comparator<T> by, int n, java.util.function.Function<T, ObjectNode> view) {
    ArrayNode a = Json.arr();
    rows.stream().sorted(by.reversed()).limit(n).forEach(r -> a.add(view.apply(r)));
    return a;
  }
}
