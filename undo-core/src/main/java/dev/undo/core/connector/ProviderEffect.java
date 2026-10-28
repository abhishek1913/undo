package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ProviderEffect(
    String providerRef,
    String objectType,
    Optional<UUID> undoActionId,
    String status,
    long amountMinor,
    Instant createdAt,
    JsonNode raw) {}
