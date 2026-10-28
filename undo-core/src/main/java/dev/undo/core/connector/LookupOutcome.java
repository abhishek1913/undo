package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface LookupOutcome {
  record Found(String providerRef, String providerStatus, JsonNode result)
      implements LookupOutcome {}

  record NotFound() implements LookupOutcome {}

  record Unavailable(String cause) implements LookupOutcome {}
}
