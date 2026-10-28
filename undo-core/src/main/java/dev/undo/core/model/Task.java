package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class Task extends Row<Task> {
  public UUID id;
  public UUID runId;
  public int seq;
  public JsonNode input;
  public TaskStatus status;
  public int turns;
  public String failureReason;
  public Instant createdAt;
  public Instant startedAt;
  public Instant completedAt;
}
