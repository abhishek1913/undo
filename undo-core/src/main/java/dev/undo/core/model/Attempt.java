package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class Attempt extends Row<Attempt> {
  public UUID id;
  public UUID actionId;
  public AttemptKind kind;
  public int attemptNo;
  public String node;
  public long fence;
  public Instant startedAt;
  public Instant finishedAt;
  public AttemptOutcome outcome;
  public Integer httpStatus;
  public Integer latencyMs;
  public JsonNode detail;
}
