package dev.undo.core.port;

import dev.undo.core.agent.TicketTruth;
import java.util.Optional;

/** Scripted model only. Server: Shopfront /admin/ground-truth. Simulator: SimShopfront. */
public interface GroundTruth {
  Optional<TicketTruth> forTicket(String ticketId);
}
