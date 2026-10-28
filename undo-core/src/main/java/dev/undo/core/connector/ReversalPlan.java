package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

public sealed interface ReversalPlan {
  /** Run this INTERNAL tool to undo the effect. */
  record Compensate(String tool, JsonNode args, long moneyRecoveredMinor, String description)
      implements ReversalPlan {}

  /** Cannot be undone now. */
  record Irreversible(String reason, Optional<Remediation> remediation, long moneyNotRecoveredMinor)
      implements ReversalPlan {}

  /** Nothing to undo (reads; effects already gone, e.g. refund failed at provider). */
  record NoEffect(String reason) implements ReversalPlan {}

  /** Someone else changed the object after the agent did. Never overwrite; a human decides. */
  record Conflict(String reason, JsonNode currentState) implements ReversalPlan {}
}
