package dev.undo.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.Event;
import dev.undo.core.port.TxContext;
import java.time.Instant;
import java.util.UUID;

/** Transactional outbox: every state change writes an event in the same TX (FR-EVT-1). */
public final class EventLog {
  private EventLog() {}

  public static void append(
      TxContext tx, Instant at, UUID runId, UUID actionId, String type, JsonNode data) {
    Event e = new Event();
    e.runId = runId;
    e.actionId = actionId;
    e.type = type;
    e.data = data;
    e.at = at;
    tx.events().append(e);
  }

  /** Fields the console needs to render an action row without another query. */
  public static ObjectNode actionData(Action a) {
    ObjectNode d = Json.obj();
    d.put("action_number", a.number);
    d.put("action", a.display());
    d.put("tool", a.tool);
    d.put("kind", a.kind.name());
    d.put("status", a.status.name());
    if (a.statusReason != null) {
      d.put("reason", a.statusReason);
    }
    if (a.taskId != null) {
      d.put("task_id", a.taskId.toString());
    }
    d.put("summary", Summaries.of(a));
    if (a.moneyOutMinor > 0) {
      d.put("amount_minor", a.moneyOutMinor);
    }
    if (a.providerRef != null) {
      d.put("provider_ref", a.providerRef);
    }
    d.put("attempt", a.attemptCount);
    return d;
  }
}
