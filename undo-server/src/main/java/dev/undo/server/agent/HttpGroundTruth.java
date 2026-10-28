package dev.undo.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.agent.TicketTruth;
import dev.undo.core.port.GroundTruth;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Ground truth for the scripted model only (Section 17.7): Shopfront's /admin/ground-truth, cached,
 * and reloaded once when a ticket is missing (the store was reseeded).
 */
@Component
public class HttpGroundTruth implements GroundTruth {
  private final ShopfrontHttp shop;
  private volatile Map<String, TicketTruth> cache = Map.of();

  public HttpGroundTruth(ShopfrontHttp shop) {
    this.shop = shop;
  }

  @Override
  public Optional<TicketTruth> forTicket(String ticketId) {
    TicketTruth t = cache.get(ticketId);
    if (t == null) {
      reload();
      t = cache.get(ticketId);
    }
    return Optional.ofNullable(t);
  }

  public synchronized void reload() {
    Map<String, TicketTruth> m = new ConcurrentHashMap<>();
    for (JsonNode n : shop.admin("GET", "/admin/ground-truth", null)) {
      m.put(
          n.get("ticket_id").asText(),
          new TicketTruth(
              n.get("ticket_id").asText(),
              n.get("category").asText(),
              n.get("base_category").asText(),
              n.get("expected_resolution").asText(),
              n.get("attack").isNull() ? null : n.get("attack")));
    }
    cache = m;
  }
}
