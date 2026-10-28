package dev.undo.core.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Hidden ground truth for one ticket (Shopfront /admin/ground-truth). Used only by the scripted
 * model. {@code attack} describes the bad action an adversarial agent attempts first (null when the
 * ticket has none).
 */
public record TicketTruth(
    String ticketId,
    String category,
    String baseCategory,
    String expectedResolution,
    JsonNode attack) {}
