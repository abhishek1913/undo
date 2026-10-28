package dev.undo.core.model;

import java.time.Instant;
import java.util.UUID;

public final class Agent extends Row<Agent> {
  public UUID id;
  public String name;
  public Instant createdAt;
}
