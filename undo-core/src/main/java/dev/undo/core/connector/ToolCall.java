package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * One provider call. {@code runId} and {@code beforeSnapshot} are additions to the PRD record:
 * refunds carry {@code metadata.undo_run_id} and the note write needs the captured version for
 * If-Match (Section 13.3). See ADR-014.
 */
public record ToolCall(
    String tool,
    JsonNode args,
    UUID actionId,
    String providerIdempotencyKey,
    int attemptNo,
    UUID runId,
    JsonNode beforeSnapshot) {}
