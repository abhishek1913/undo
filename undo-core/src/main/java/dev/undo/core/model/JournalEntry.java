package dev.undo.core.model;

import java.time.Instant;
import java.util.UUID;

public final class JournalEntry extends Row<JournalEntry> {
  public UUID id;
  public UUID runId;

  /** The PRIMARY action the money belongs to. */
  public UUID actionId;

  public String kind;
  public String source;
  public String sourceRef;
  public Instant createdAt;
}
