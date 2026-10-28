package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ProviderStatusChange(
    String eventId,
    String eventType,
    String providerRef,
    String newStatus,
    Instant occurredAt,
    JsonNode raw) {}
