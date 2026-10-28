package dev.shopfront.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record Refund(
    String id,
    String orderId,
    long amountMinor,
    String reason,
    String status,
    JsonNode metadata,
    Instant createdAt,
    Instant settlesAfter,
    Instant updatedAt) {

  public Refund withStatus(String newStatus, Instant at) {
    return new Refund(
        id, orderId, amountMinor, reason, newStatus, metadata, createdAt, settlesAfter, at);
  }

  /** pending and settled refunds count against the order's refundable amount. */
  public boolean counts() {
    return status.equals("pending") || status.equals("settled");
  }

  public String undoActionId() {
    JsonNode v = metadata == null ? null : metadata.get("undo_action_id");
    return v == null || v.isNull() ? null : v.asText();
  }

  public String undoRunId() {
    JsonNode v = metadata == null ? null : metadata.get("undo_run_id");
    return v == null || v.isNull() ? null : v.asText();
  }
}
