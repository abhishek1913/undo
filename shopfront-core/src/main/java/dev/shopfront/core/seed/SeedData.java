package dev.shopfront.core.seed;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.shopfront.core.domain.Customer;
import dev.shopfront.core.domain.Order;
import dev.shopfront.core.domain.Ticket;
import dev.shopfront.core.json.Json;
import java.util.List;

public record SeedData(
    List<Customer> customers,
    List<Order> orders,
    List<Ticket> tickets,
    ArrayNode groundTruth,
    List<String> carrierBugSelection,
    ObjectNode seedInfo) {

  public ArrayNode carrierBugSelectionJson() {
    ArrayNode a = Json.arr();
    carrierBugSelection.forEach(a::add);
    return a;
  }

  /** SHA-256 of a canonical dump of everything generated. */
  public String sha256() {
    ObjectNode dump = Json.obj();
    ArrayNode cs = dump.putArray("customers");
    for (Customer c : customers) {
      cs.add(
          Json.MAPPER.valueToTree(List.of(c.id(), c.name(), c.email(), c.createdAt().toString())));
    }
    ArrayNode os = dump.putArray("orders");
    for (Order o : orders) {
      os.add(
          Json.MAPPER.valueToTree(
              List.of(
                  o.id(),
                  o.customerId(),
                  o.itemName(),
                  o.totalMinor(),
                  o.status(),
                  o.carrierStatus(),
                  o.placedAt().toString(),
                  String.valueOf(o.deliveredAt()))));
    }
    ArrayNode ts = dump.putArray("tickets");
    for (Ticket t : tickets) {
      ts.add(
          Json.MAPPER.valueToTree(
              java.util.Arrays.asList(
                  t.id(),
                  t.customerId(),
                  t.orderId(),
                  t.category(),
                  t.subject(),
                  t.body(),
                  t.createdAt().toString())));
    }
    dump.set("ground_truth", groundTruth);
    dump.set("carrier_bug_selection", carrierBugSelectionJson());
    return Json.sha256Hex(Json.canonical(dump));
  }
}
