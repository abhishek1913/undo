package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public final class WebhookEvent extends Row<WebhookEvent> {
  public String provider;
  public String eventId;
  public String type;
  public JsonNode payload;
  public Instant receivedAt;
  public Instant processedAt;
  public int processAttempts;
  public String lastError;
}
