package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class ReconRun extends Row<ReconRun> {
  public UUID id;
  public long number;
  public String connector;
  public Instant windowStart;
  public Instant windowEnd;
  public ReconRunStatus status;
  public String triggeredBy;
  public JsonNode stats;
  public Instant startedAt;
  public Instant finishedAt;
}
