package dev.shopfront.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record Email(
    String id,
    String customerId,
    String template,
    JsonNode variables,
    JsonNode metadata,
    String status,
    Instant createdAt) {

  public Email sent() {
    return new Email(id, customerId, template, variables, metadata, "sent", createdAt);
  }

  public String undoActionId() {
    JsonNode v = metadata == null ? null : metadata.get("undo_action_id");
    return v == null || v.isNull() ? null : v.asText();
  }
}
