package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class LlmTurn extends Row<LlmTurn> {
  public UUID id;
  public UUID runId;
  public UUID taskId;
  public int turnNo;
  public String model;
  public JsonNode response;
  public String stopReason;
  public JsonNode toolResults;
  public int inputTokens;
  public int outputTokens;
  public int cacheReadTokens;
  public int cacheWriteTokens;
  public int latencyMs;
  public long runFence;
  public Instant createdAt;
}
