package dev.undo.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ActionView;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.Consistency;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.EffectPage;
import dev.undo.core.connector.ExecutionOutcome;
import dev.undo.core.connector.IdempotencyClass;
import dev.undo.core.connector.InvalidWebhookException;
import dev.undo.core.connector.JournalSpec;
import dev.undo.core.connector.LookupOutcome;
import dev.undo.core.connector.ReversalPlan;
import dev.undo.core.connector.ToolCall;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.Run;
import dev.undo.core.port.Store;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Built-in connector "undo": {@code undo_run_status}, executed inside Undo (Section 13.3). */
public final class UndoConnector implements Connector {
  public static final String ID = "undo";
  public static final String RUN_STATUS = "undo_run_status";

  private final Store store;

  public UndoConnector(Store store) {
    this.store = store;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<ToolSpec> tools() {
    return List.of(
        new ToolSpec(
            RUN_STATUS,
            ID,
            "Show this run's remaining budget, pending approvals and counts of actions by status.",
            Json.parse(
                "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],\"properties\":{}}"),
            Effect.READ,
            Visibility.AGENT,
            IdempotencyClass.NATURAL,
            Consistency.STRONG,
            Duration.ZERO,
            Optional.empty(),
            Optional.empty(),
            false,
            Duration.ZERO,
            Optional.empty(),
            false));
  }

  @Override
  public JsonNode gatherFacts(String tool, JsonNode args) {
    return Json.obj();
  }

  @Override
  public JsonNode captureBefore(String tool, JsonNode args) {
    return Json.obj();
  }

  @Override
  public ExecutionOutcome execute(ToolCall call) {
    try {
      ObjectNode result = store.tx(tx -> status(tx.runs().get(call.runId()).orElseThrow(), tx));
      return new ExecutionOutcome.Succeeded(null, "ok", result);
    } catch (RuntimeException e) {
      return new ExecutionOutcome.Unknown("run status unavailable: " + e.getMessage());
    }
  }

  private static ObjectNode status(Run run, dev.undo.core.port.TxContext tx) {
    ObjectNode o = Json.obj();
    o.put("run_number", run.number);
    o.put("status", run.status.name());
    o.put("money_cap_minor", run.moneyCapMinor);
    o.put("money_spent_minor", run.moneySpentMinor);
    o.put("money_reserved_minor", run.moneyReservedMinor);
    o.put("actions_used", run.actionCount);
    o.put("action_cap", run.actionCap);
    ArrayNode pending = o.putArray("pending_approvals");
    for (Approval ap : tx.approvals().list(ApprovalStatus.PENDING, run.id, 100)) {
      tx.actions()
          .get(ap.actionId)
          .ifPresent(
              (Action a) -> {
                ObjectNode p = pending.addObject();
                p.put("action_number", a.number);
                p.put("tool", a.tool);
                p.put("amount_minor", a.moneyOutMinor);
              });
    }
    ObjectNode counts = o.putObject("counts_by_status");
    Map<ActionStatus, Long> c = tx.actions().countByStatus(run.id);
    for (ActionStatus s : ActionStatus.values()) {
      if (c.getOrDefault(s, 0L) > 0) {
        counts.put(s.name(), c.get(s));
      }
    }
    return o;
  }

  @Override
  public LookupOutcome lookup(ToolCall call) {
    ExecutionOutcome o = execute(call);
    if (o instanceof ExecutionOutcome.Succeeded s) {
      return new LookupOutcome.Found(s.providerRef(), s.providerStatus(), s.result());
    }
    return new LookupOutcome.Unavailable("run status unavailable");
  }

  @Override
  public JsonNode currentState(ActionView action) {
    return action.result();
  }

  @Override
  public ReversalPlan reversal(ActionView action, JsonNode currentState, Instant now) {
    return new ReversalPlan.NoEffect("Reads have no effect.");
  }

  @Override
  public List<JournalSpec> journalOnSuccess(ActionView action) {
    return List.of();
  }

  @Override
  public List<JournalSpec> journalOnProviderStatus(ActionView action, String newStatus) {
    return List.of();
  }

  @Override
  public List<JournalSpec> journalOnCompensated(ActionView original, ActionView compensation) {
    return List.of();
  }

  @Override
  public dev.undo.core.connector.ProviderStatusChange parseWebhook(
      Map<String, String> headers, byte[] body, Instant now) throws InvalidWebhookException {
    throw new InvalidWebhookException("the undo connector has no webhooks");
  }

  @Override
  public EffectPage listEffects(Instant from, Instant to, Optional<String> cursor) {
    return new EffectPage(List.of(), Optional.empty());
  }
}
