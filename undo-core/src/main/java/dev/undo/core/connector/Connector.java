package dev.undo.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Connector {
  String id();

  List<ToolSpec> tools();

  /** Read-only facts for policy. Throws ConnectorException on any failure. */
  JsonNode gatherFacts(String tool, JsonNode args) throws ConnectorException;

  /** Before-state for compare-and-swap compensation. Only called when capturesBefore. */
  JsonNode captureBefore(String tool, JsonNode args) throws ConnectorException;

  /** Performs the effect. Never throws; classifies per Section 9.4. */
  ExecutionOutcome execute(ToolCall call);

  /** Finds the effect of a previous attempt by key/reference/state. Never throws. */
  LookupOutcome lookup(ToolCall call);

  /**
   * Provider's current view of the object this action changed (refund, credit, ticket, order note).
   * Used by revert preview/execute and reconciliation. Throws ConnectorException on failure. For
   * tools with reversalNeedsState = false (emails) it returns action.result without any provider
   * call.
   */
  JsonNode currentState(ActionView action) throws ConnectorException;

  /** Pure: how to undo this SUCCEEDED action, given the provider's current state. */
  ReversalPlan reversal(ActionView action, JsonNode currentState, Instant now);

  /** Pure: journal entries to write when an action succeeds (may be empty). */
  List<JournalSpec> journalOnSuccess(ActionView action);

  /** Pure: journal entries when the provider changes status (webhook or recon heal). */
  List<JournalSpec> journalOnProviderStatus(ActionView action, String newStatus);

  /** Pure: journal entries on the ORIGINAL action when its compensation succeeds. */
  List<JournalSpec> journalOnCompensated(ActionView original, ActionView compensation);

  /** Verifies signature and parses. Throws InvalidWebhookException if invalid. */
  ProviderStatusChange parseWebhook(Map<String, String> headers, byte[] body, Instant now)
      throws InvalidWebhookException;

  /** Lists provider objects created or changed in [from, to) for reconciliation. */
  EffectPage listEffects(Instant from, Instant to, Optional<String> cursor)
      throws ConnectorException;
}
