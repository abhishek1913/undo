package dev.undo.core.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The exact response body as JSON ({@code raw}) plus the fields the runner reads. {@code raw} is
 * stored in llm_turns.response and echoed back verbatim.
 */
public record ModelResponse(
    JsonNode raw,
    String model,
    String stopReason,
    int inputTokens,
    int outputTokens,
    int cacheReadTokens,
    int cacheWriteTokens) {

  public JsonNode content() {
    return raw.path("content");
  }
}
