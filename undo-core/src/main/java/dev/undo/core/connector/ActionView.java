package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import java.util.UUID;

/** Read-only view of a stored action given to connectors. */
public record ActionView(
    UUID id,
    long number,
    UUID runId,
    String tool,
    JsonNode args,
    String argsHash,
    ActionKind kind,
    ActionStatus status,
    String providerIdempotencyKey,
    String providerRef,
    String providerStatus,
    JsonNode result,
    JsonNode facts,
    JsonNode beforeSnapshot,
    long moneyOutMinor,
    int attemptCount) {

  public static ActionView of(Action a) {
    return new ActionView(
        a.id,
        a.number,
        a.runId,
        a.tool,
        a.args,
        a.argsHash,
        a.kind,
        a.status,
        a.providerIdempotencyKey,
        a.providerRef,
        a.providerStatus,
        a.result,
        a.facts,
        a.beforeSnapshot,
        a.moneyOutMinor,
        a.attemptCount);
  }
}
