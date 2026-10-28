package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

public sealed interface ExecutionOutcome {
  record Succeeded(String providerRef, String providerStatus, JsonNode result)
      implements ExecutionOutcome {}

  record FailedDefinitive(String code, String message, int httpStatus)
      implements ExecutionOutcome {}

  record RetryableNotApplied(String code, Duration retryAfter) implements ExecutionOutcome {}

  record Unknown(String cause) implements ExecutionOutcome {}
}
