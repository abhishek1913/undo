package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;

public record ToolSpec(
    String name,
    String connectorId,
    String description,
    JsonNode inputSchema,
    Effect effect,
    Visibility visibility,
    IdempotencyClass idempotencyClass,
    Consistency lookupConsistency,
    Duration lookupLag, // EVENTUAL only, else Duration.ZERO
    Optional<String> dedupeKeyTemplate, // e.g. "refund:order:{order_id}"; top-level arg names
    Optional<String> moneyOutPointer, // JSON pointer into args, e.g. "/amount_minor"
    boolean capturesBefore,
    Duration nativeKeyTtl, // NATIVE_KEY only
    Optional<String>
        providerObjectType, // "refund" | "store_credit" | "email"; empty = not reconciled
    boolean reversalNeedsState // false: currentState returns the stored result, no provider call
    ) {

  public static final String NAME_PATTERN = "^[a-z][a-z0-9_]{1,63}$";

  public ToolSpec {
    if (!name.matches(NAME_PATTERN)) {
      throw new IllegalArgumentException("Invalid tool name: " + name);
    }
  }
}
