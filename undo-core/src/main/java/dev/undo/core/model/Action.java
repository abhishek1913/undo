package dev.undo.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.IdempotencyClass;
import java.time.Instant;
import java.util.UUID;

/** One recorded tool call. The central record (table {@code actions}). */
public final class Action extends Row<Action> {
  public UUID id;
  public long number;
  public UUID runId;
  public UUID taskId;
  public ActionKind kind;
  public UUID compensatesActionId;
  public UUID revertPlanId;
  public String connector;
  public String tool;
  public Effect effect;
  public IdempotencyClass idempotencyClass;
  public JsonNode args;
  public String argsHash;
  public String agentCallId;
  public String clientIdempotencyKey;
  public String dedupeKey;
  public boolean dedupeActive;
  public UUID duplicateOfActionId;
  public ActionStatus status;
  public String statusReason;
  public UUID policyVersionId;
  public Decision decision;
  public JsonNode matchedRules;
  public JsonNode facts;
  public JsonNode beforeSnapshot;
  public long moneyOutMinor;
  public BudgetState budgetState = BudgetState.NONE;
  public String providerIdempotencyKey;
  public String providerRef;
  public String providerStatus;
  public JsonNode result;
  public JsonNode error;
  public UUID compensatedByActionId;
  public int attemptCount;
  public int lookupCount;
  public Instant firstAttemptAt;
  public Instant nextAttemptAt;
  public Instant unknownSince;
  public Instant resolveAfter;
  public String leaseOwner;
  public Instant leaseExpiresAt;
  public long leaseFence;
  public Instant createdAt;
  public Instant updatedAt;
  public Instant completedAt;

  public String display() {
    return display(number);
  }

  public static String display(long number) {
    return String.format("#A-%04d", number);
  }
}
