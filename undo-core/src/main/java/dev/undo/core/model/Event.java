package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Transactional outbox row (table {@code events}). */
public final class Event extends Row<Event> {
  public long seq;
  public UUID runId;
  public UUID actionId;
  public String type;
  public JsonNode data;
  public Instant at;
  public String prevHash;
  public String hash;
}
