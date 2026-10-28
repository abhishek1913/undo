package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.engine.Money;
import dev.undo.core.json.Json;
import dev.undo.core.ledger.Ledger;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionTransition;
import dev.undo.core.model.Approval;
import dev.undo.core.model.Attempt;
import dev.undo.core.model.Event;
import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.Posting;
import dev.undo.core.model.RevertItem;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.port.TxContext;
import dev.undo.core.render.ToolResultRenderer;
import dev.undo.server.api.Views;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** JSON shapes for the console API. All reads happen inside the caller's TX. */
final class ConsoleViews {
  private ConsoleViews() {}

  static ObjectNode runSummary(TxContext tx, Run r) {
    ObjectNode o = Views.run(r, null);
    var counts = tx.actions().countByStatus(r.id);
    ObjectNode c = o.putObject("counts_by_status");
    counts.forEach((k, v) -> c.put(k.name(), v));
    long writes = 0;
    long total = 0;
    for (Action a : tx.actions().byRun(r.id)) {
      total++;
      if (a.effect == dev.undo.core.connector.Effect.WRITE
          && a.kind == dev.undo.core.model.ActionKind.PRIMARY) {
        writes++;
      }
    }
    o.put("actions_total", total);
    o.put("actions_write", writes);
    o.put(
        "pending_approvals",
        tx.approvals().list(dev.undo.core.model.ApprovalStatus.PENDING, r.id, 10_000).size());
    tx.agents().get(r.agentId).ifPresent(a -> o.put("agent", a.name));
    tx.policies().get(r.policyVersionId).ifPresent(p -> o.put("policy", p.name + " v" + p.version));
    return o;
  }

  static ObjectNode ledger(TxContext tx, Run r) {
    ObjectNode o = Json.obj();
    List<JournalEntry> entries = tx.ledger().entriesByRun(r.id);
    List<Posting> postings = tx.ledger().postingsByRun(r.id);
    Map<UUID, ArrayNode> byEntry = new TreeMap<>();
    ArrayNode es = o.putArray("entries");
    for (JournalEntry e : entries) {
      ObjectNode eo = es.addObject();
      eo.put("id", e.id.toString());
      eo.put("kind", e.kind);
      eo.put("source", e.source);
      eo.put("source_ref", e.sourceRef);
      eo.put("created_at", Views.ts(e.createdAt));
      tx.actions()
          .get(e.actionId)
          .ifPresent(
              a -> {
                eo.put("action_number", a.number);
                eo.put("action", a.display());
              });
      byEntry.put(e.id, eo.putArray("postings"));
    }
    for (Posting p : postings) {
      ArrayNode arr = byEntry.get(p.entryId);
      if (arr != null) {
        ObjectNode po = arr.addObject();
        po.put("account", p.account);
        po.put("amount_minor", p.amountMinor);
      }
    }
    ObjectNode net = o.putObject("net_by_account");
    long sum = 0;
    for (var e : Ledger.net(postings).entrySet()) {
      net.put(e.getKey(), e.getValue());
      sum += e.getValue();
    }
    o.put("balanced", sum == 0);
    o.put("net_exposure_minor", Ledger.expenseExposure(postings));
    return o;
  }

  static ObjectNode actionRow(Action a) {
    ObjectNode o = Views.agentAction(a);
    o.put("summary", dev.undo.core.engine.Summaries.of(a));
    o.put("task_id", a.taskId == null ? null : a.taskId.toString());
    o.put(
        "compensates_action_id",
        a.compensatesActionId == null ? null : a.compensatesActionId.toString());
    o.put(
        "compensated_by_action_id",
        a.compensatedByActionId == null ? null : a.compensatedByActionId.toString());
    o.put("agent_status", ToolResultRenderer.agentStatus(a));
    return o;
  }

  static ObjectNode fullAction(TxContext tx, Action a) {
    ObjectNode o = actionRow(a);
    tx.runs()
        .get(a.runId)
        .ifPresent(
            r -> {
              o.put("run_number", r.number);
              o.put("run_status", r.status.name());
            });
    o.set("facts", Views.nullable(a.facts));
    o.set("before_snapshot", Views.nullable(a.beforeSnapshot));
    o.put("dedupe_key", a.dedupeKey);
    o.put("dedupe_active", a.dedupeActive);
    o.put("budget_state", a.budgetState.name());
    o.put("provider_idempotency_key", a.providerIdempotencyKey);
    o.put("lease_fence", a.leaseFence);
    o.put("lookup_count", a.lookupCount);
    o.put("unknown_since", Views.ts(a.unknownSince));
    o.set("rendered", ToolResultRenderer.render(a, null));
    tx.policies().get(a.policyVersionId).ifPresent(p -> o.put("policy", p.name + " v" + p.version));
    if (a.duplicateOfActionId != null) {
      tx.actions()
          .get(a.duplicateOfActionId)
          .ifPresent(d -> o.put("duplicate_of", d.display()).put("duplicate_of_number", d.number));
    }
    ArrayNode tr = o.putArray("transitions");
    for (ActionTransition t : tx.actions().transitions(a.id)) {
      ObjectNode x = tr.addObject();
      x.put("from", t.fromStatus == null ? null : t.fromStatus.name());
      x.put("to", t.toStatus.name());
      x.put("fence", t.fence);
      x.put("actor", t.actor);
      x.put("reason", t.reason);
      x.put("at", Views.ts(t.at));
    }
    ArrayNode at = o.putArray("attempts");
    for (Attempt x : tx.attempts().byAction(a.id)) {
      ObjectNode y = at.addObject();
      y.put("kind", x.kind.name());
      y.put("attempt_no", x.attemptNo);
      y.put("node", x.node);
      y.put("fence", x.fence);
      y.put("started_at", Views.ts(x.startedAt));
      y.put("finished_at", Views.ts(x.finishedAt));
      y.put("outcome", x.outcome == null ? null : x.outcome.name());
      if (x.httpStatus != null) {
        y.put("http_status", x.httpStatus);
      }
      if (x.latencyMs != null) {
        y.put("latency_ms", x.latencyMs);
      }
      y.set("detail", Views.nullable(x.detail));
    }
    tx.approvals().byAction(a.id).ifPresent(ap -> o.set("approval", approval(ap)));
    ArrayNode ps = o.putArray("postings");
    for (Posting p : tx.ledger().postingsByAction(a.id)) {
      ObjectNode y = ps.addObject();
      y.put("account", p.account);
      y.put("amount_minor", p.amountMinor);
    }
    if (a.compensatedByActionId != null) {
      tx.actions()
          .get(a.compensatedByActionId)
          .ifPresent(
              c -> o.put("compensated_by", c.display()).put("compensated_by_number", c.number));
    }
    if (a.compensatesActionId != null) {
      tx.actions()
          .get(a.compensatesActionId)
          .ifPresent(c -> o.put("compensates", c.display()).put("compensates_number", c.number));
    }
    return o;
  }

  static ObjectNode approval(Approval ap) {
    ObjectNode o = Json.obj();
    o.put("id", ap.id.toString());
    o.put("status", ap.status.name());
    o.put("args_hash", ap.argsHash);
    o.put("approved_args_hash", ap.approvedArgsHash);
    o.set("reasons", ap.reasons);
    o.set("advisory_note", Views.nullable(ap.advisoryNote));
    o.put("requested_at", Views.ts(ap.requestedAt));
    o.put("expires_at", Views.ts(ap.expiresAt));
    o.put("decided_at", Views.ts(ap.decidedAt));
    o.put("decision_note", ap.decisionNote);
    return o;
  }

  /** One approval card (Section 20.4): tool, args, facts, rules, advisory, expiry. */
  static ObjectNode card(TxContext tx, Approval ap) {
    ObjectNode o = approval(ap);
    tx.actions()
        .get(ap.actionId)
        .ifPresent(
            a -> {
              o.put("action_number", a.number);
              o.put("action", a.display());
              o.put("action_id", a.id.toString());
              o.put("action_status", a.status.name());
              o.put("kind", a.kind.name());
              o.put("tool", a.tool);
              o.set("args", a.args);
              o.set("facts", Views.nullable(a.facts));
              o.put(
                  "amount_minor",
                  a.moneyOutMinor > 0 ? a.moneyOutMinor : a.args.path("amount_minor").asLong(0));
              o.put(
                  "amount_text",
                  Money.format(
                      a.moneyOutMinor > 0
                          ? a.moneyOutMinor
                          : a.args.path("amount_minor").asLong(0)));
              JsonNode ticket = a.facts == null ? null : a.facts.get("ticket");
              if (ticket != null && ticket.isObject()) {
                o.put("ticket_id", ticket.path("id").asText());
                o.put("ticket_subject", ticket.path("subject").asText());
              }
            });
    tx.runs()
        .get(ap.runId)
        .ifPresent(
            r -> {
              o.put("run_number", r.number);
              o.put("run_title", r.title);
            });
    return o;
  }

  static ObjectNode event(Event e) {
    ObjectNode o = Json.obj();
    o.put("seq", e.seq);
    o.put("type", e.type);
    o.put("run_id", e.runId == null ? null : e.runId.toString());
    o.put("action_id", e.actionId == null ? null : e.actionId.toString());
    o.put("at", Views.ts(e.at));
    o.set("data", e.data);
    return o;
  }

  /** A revert plan with its items (Section 20.4 revert page). */
  static ObjectNode plan(TxContext tx, RevertPlan pl) {
    ObjectNode o = Json.obj();
    o.put("id", pl.id.toString());
    o.put("number", pl.number);
    o.put("scope", pl.scope.name());
    o.put("status", pl.status.name());
    o.set("summary", pl.summary);
    o.put("created_at", Views.ts(pl.createdAt));
    o.put("started_at", Views.ts(pl.startedAt));
    o.put("completed_at", Views.ts(pl.completedAt));
    o.put("lease_owner", pl.leaseOwner);
    tx.runs()
        .get(pl.runId)
        .ifPresent(
            r -> {
              o.put("run_number", r.number);
              o.put("run_title", r.title);
              o.put("run_status", r.status.name());
            });
    ArrayNode items = o.putArray("items");
    for (RevertItem it : tx.reverts().items(pl.id)) {
      ObjectNode x = items.addObject();
      x.put("order_no", it.orderNo);
      x.put("kind", it.kind.name());
      x.put("status", it.status.name());
      x.put("money_recovered_minor", it.moneyRecoveredMinor);
      x.put("money_not_recovered_minor", it.moneyNotRecoveredMinor);
      JsonNode note = it.note == null ? Json.obj() : Json.parse(it.note);
      x.put("note", note.path("text").asText(null));
      x.set("remediation", Views.nullable(note.get("remediation")));
      tx.actions()
          .get(it.targetActionId)
          .ifPresent(
              a -> {
                x.put("action_number", a.number);
                x.put("action", a.display());
                x.put("tool", a.tool);
                x.put("summary", dev.undo.core.engine.Summaries.of(a));
                x.put("money_out_minor", a.moneyOutMinor);
              });
      if (it.compensationActionId != null) {
        tx.actions()
            .get(it.compensationActionId)
            .ifPresent(
                c -> {
                  x.put("compensation_number", c.number);
                  x.put("compensation", c.display());
                  x.put("compensation_status", c.status.name());
                });
      }
      if (it.remediationActionId != null) {
        tx.actions()
            .get(it.remediationActionId)
            .ifPresent(
                c -> {
                  x.put("remediation_number", c.number);
                  x.put("remediation_action", c.display());
                  x.put("remediation_status", c.status.name());
                  tx.approvals()
                      .byAction(c.id)
                      .ifPresent(ap -> x.put("remediation_approval_status", ap.status.name()));
                });
      }
    }
    return o;
  }
}
