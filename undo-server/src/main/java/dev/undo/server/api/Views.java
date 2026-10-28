package dev.undo.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.Run;
import dev.undo.core.port.Store;
import java.time.Instant;
import java.util.Map;

/** JSON shapes of the REST APIs (snake_case, money in minor units). */
public final class Views {
  private Views() {}

  public static String ts(Instant i) {
    return i == null ? null : i.toString();
  }

  public static ObjectNode run(Run r, Store store) {
    ObjectNode o = Json.obj();
    o.put("id", r.id.toString());
    o.put("number", r.number);
    o.put("kind", r.kind.name());
    o.put("status", r.status.name());
    o.put("title", r.title);
    o.put("scenario", r.scenario);
    o.put("model_mode", r.modelMode);
    o.set("labels", r.labels);
    o.put("policy_version_id", r.policyVersionId.toString());
    o.put("currency", r.currency);
    ObjectNode b = o.putObject("budgets");
    b.put("money_cap_minor", r.moneyCapMinor);
    b.put("money_reserved_minor", r.moneyReservedMinor);
    b.put("money_spent_minor", r.moneySpentMinor);
    b.put("money_remaining_minor", r.moneyCapMinor - r.moneyReservedMinor - r.moneySpentMinor);
    b.put("action_cap", r.actionCap);
    b.put("actions_used", r.actionCount);
    b.put("llm_token_cap", r.llmTokenCap);
    b.put("llm_tokens_used", r.llmTokensUsed);
    if (store != null) {
      Map<ActionStatus, Long> counts = store.tx(tx -> tx.actions().countByStatus(r.id));
      ObjectNode c = o.putObject("counts_by_status");
      counts.forEach((k, v) -> c.put(k.name(), v));
      long pending =
          store.tx(tx -> tx.approvals().list(ApprovalStatus.PENDING, r.id, 10_000)).size();
      o.put("pending_approvals", pending);
    }
    o.put("failure_reason", r.failureReason);
    o.put("created_at", ts(r.createdAt));
    o.put("completed_at", ts(r.completedAt));
    o.put("frozen_at", ts(r.frozenAt));
    o.put("last_activity_at", ts(r.lastActivityAt));
    return o;
  }

  /** What an agent may see about its own action. */
  public static ObjectNode agentAction(Action a) {
    ObjectNode o = Json.obj();
    o.put("id", a.id.toString());
    o.put("number", a.number);
    o.put("display", a.display());
    o.put("run_id", a.runId.toString());
    o.put("kind", a.kind.name());
    o.put("tool", a.tool);
    o.set("args", a.args);
    o.put("args_hash", a.argsHash);
    o.put("status", a.status.name());
    o.put("status_reason", a.statusReason);
    o.put("decision", a.decision == null ? null : a.decision.name());
    o.set("matched_rules", a.matchedRules);
    o.put("idempotency_class", a.idempotencyClass.name());
    o.put("money_out_minor", a.moneyOutMinor);
    o.put("provider_ref", a.providerRef);
    o.put("provider_status", a.providerStatus);
    o.set("result", a.result);
    o.set("error", a.error);
    o.put("attempt_count", a.attemptCount);
    o.put("created_at", ts(a.createdAt));
    o.put("updated_at", ts(a.updatedAt));
    o.put("completed_at", ts(a.completedAt));
    return o;
  }

  public static ObjectNode tool(ToolSpec t) {
    ObjectNode o = Json.obj();
    o.put("name", t.name());
    o.put("description", t.description());
    o.set("input_schema", t.inputSchema());
    o.put("effect", t.effect().name());
    o.put("idempotency_class", t.idempotencyClass().name());
    o.put("visibility", t.visibility().name());
    return o;
  }

  public static JsonNode nullable(JsonNode n) {
    return n == null ? Json.nul() : n;
  }
}
