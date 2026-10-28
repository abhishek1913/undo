package dev.undo.server.store;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.IdempotencyClass;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ActionTransition;
import dev.undo.core.model.Agent;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.Attempt;
import dev.undo.core.model.AttemptKind;
import dev.undo.core.model.AttemptOutcome;
import dev.undo.core.model.BudgetState;
import dev.undo.core.model.Decision;
import dev.undo.core.model.Event;
import dev.undo.core.model.FindingStatus;
import dev.undo.core.model.FindingType;
import dev.undo.core.model.ItemKind;
import dev.undo.core.model.ItemStatus;
import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.LlmTurn;
import dev.undo.core.model.PlanScope;
import dev.undo.core.model.PlanStatus;
import dev.undo.core.model.PolicyVersion;
import dev.undo.core.model.Posting;
import dev.undo.core.model.ReconFinding;
import dev.undo.core.model.ReconRun;
import dev.undo.core.model.ReconRunStatus;
import dev.undo.core.model.RevertItem;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.model.RunStatus;
import dev.undo.core.model.Severity;
import dev.undo.core.model.Task;
import dev.undo.core.model.TaskStatus;
import dev.undo.core.model.WebhookEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Row mappers and column helpers for the undo schema (Section 10.1). */
final class Rows {
  private Rows() {}

  static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  static Instant inst(ResultSet rs, String c) throws SQLException {
    Timestamp t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  static UUID uuid(ResultSet rs, String c) throws SQLException {
    return rs.getObject(c, UUID.class);
  }

  static JsonNode json(ResultSet rs, String c) throws SQLException {
    String s = rs.getString(c);
    return s == null ? null : Json.parse(s);
  }

  static String json(JsonNode n) {
    return n == null ? null : Json.write(n);
  }

  static String name(Enum<?> e) {
    return e == null ? null : e.name();
  }

  static <E extends Enum<E>> E en(ResultSet rs, String c, Class<E> t) throws SQLException {
    String s = rs.getString(c);
    return s == null ? null : Enum.valueOf(t, s);
  }

  static Integer intOrNull(ResultSet rs, String c) throws SQLException {
    int v = rs.getInt(c);
    return rs.wasNull() ? null : v;
  }

  static final RowMapper<Agent> AGENT =
      (rs, i) -> {
        Agent a = new Agent();
        a.id = uuid(rs, "id");
        a.name = rs.getString("name");
        a.createdAt = inst(rs, "created_at");
        return a;
      };

  static final RowMapper<PolicyVersion> POLICY =
      (rs, i) -> {
        PolicyVersion p = new PolicyVersion();
        p.id = uuid(rs, "id");
        p.name = rs.getString("name");
        p.version = rs.getInt("version");
        p.sourceYaml = rs.getString("source_yaml");
        p.compiled = json(rs, "compiled");
        p.sha256 = rs.getString("sha256");
        p.active = rs.getBoolean("active");
        p.createdBy = rs.getString("created_by");
        p.createdAt = inst(rs, "created_at");
        return p;
      };

  static final RowMapper<Run> RUN =
      (rs, i) -> {
        Run r = new Run();
        r.id = uuid(rs, "id");
        r.number = rs.getLong("number");
        r.agentId = uuid(rs, "agent_id");
        r.kind = en(rs, "kind", RunKind.class);
        r.status = en(rs, "status", RunStatus.class);
        r.title = rs.getString("title");
        r.scenario = rs.getString("scenario");
        r.modelMode = rs.getString("model_mode");
        r.labels = json(rs, "labels");
        r.policyVersionId = uuid(rs, "policy_version_id");
        r.currency = rs.getString("currency");
        r.moneyCapMinor = rs.getLong("money_cap_minor");
        r.moneyReservedMinor = rs.getLong("money_reserved_minor");
        r.moneySpentMinor = rs.getLong("money_spent_minor");
        r.actionCap = rs.getInt("action_cap");
        r.actionCount = rs.getInt("action_count");
        r.llmTokenCap = rs.getLong("llm_token_cap");
        r.llmTokensUsed = rs.getLong("llm_tokens_used");
        r.mcpSessionId = rs.getString("mcp_session_id");
        r.leaseOwner = rs.getString("lease_owner");
        r.leaseExpiresAt = inst(rs, "lease_expires_at");
        r.leaseFence = rs.getLong("lease_fence");
        r.auditHeadHash = rs.getString("audit_head_hash");
        r.failureReason = rs.getString("failure_reason");
        r.lastActivityAt = inst(rs, "last_activity_at");
        r.createdAt = inst(rs, "created_at");
        r.completedAt = inst(rs, "completed_at");
        r.frozenAt = inst(rs, "frozen_at");
        return r;
      };

  static final RowMapper<Task> TASK =
      (rs, i) -> {
        Task t = new Task();
        t.id = uuid(rs, "id");
        t.runId = uuid(rs, "run_id");
        t.seq = rs.getInt("seq");
        t.input = json(rs, "input");
        t.status = en(rs, "status", TaskStatus.class);
        t.turns = rs.getInt("turns");
        t.failureReason = rs.getString("failure_reason");
        t.createdAt = inst(rs, "created_at");
        t.startedAt = inst(rs, "started_at");
        t.completedAt = inst(rs, "completed_at");
        return t;
      };

  static final RowMapper<LlmTurn> TURN =
      (rs, i) -> {
        LlmTurn t = new LlmTurn();
        t.id = uuid(rs, "id");
        t.runId = uuid(rs, "run_id");
        t.taskId = uuid(rs, "task_id");
        t.turnNo = rs.getInt("turn_no");
        t.model = rs.getString("model");
        t.response = json(rs, "response");
        t.stopReason = rs.getString("stop_reason");
        t.toolResults = json(rs, "tool_results");
        t.inputTokens = rs.getInt("input_tokens");
        t.outputTokens = rs.getInt("output_tokens");
        t.cacheReadTokens = rs.getInt("cache_read_tokens");
        t.cacheWriteTokens = rs.getInt("cache_write_tokens");
        t.latencyMs = rs.getInt("latency_ms");
        t.runFence = rs.getLong("run_fence");
        t.createdAt = inst(rs, "created_at");
        return t;
      };

  static final RowMapper<Action> ACTION =
      (rs, i) -> {
        Action a = new Action();
        a.id = uuid(rs, "id");
        a.number = rs.getLong("number");
        a.runId = uuid(rs, "run_id");
        a.taskId = uuid(rs, "task_id");
        a.kind = en(rs, "kind", ActionKind.class);
        a.compensatesActionId = uuid(rs, "compensates_action_id");
        a.revertPlanId = uuid(rs, "revert_plan_id");
        a.connector = rs.getString("connector");
        a.tool = rs.getString("tool");
        a.effect = en(rs, "effect", Effect.class);
        a.idempotencyClass = en(rs, "idempotency_class", IdempotencyClass.class);
        a.args = json(rs, "args");
        a.argsHash = rs.getString("args_hash");
        a.agentCallId = rs.getString("agent_call_id");
        a.clientIdempotencyKey = rs.getString("client_idempotency_key");
        a.dedupeKey = rs.getString("dedupe_key");
        a.dedupeActive = rs.getBoolean("dedupe_active");
        a.duplicateOfActionId = uuid(rs, "duplicate_of_action_id");
        a.status = en(rs, "status", ActionStatus.class);
        a.statusReason = rs.getString("status_reason");
        a.policyVersionId = uuid(rs, "policy_version_id");
        a.decision = en(rs, "decision", Decision.class);
        a.matchedRules = json(rs, "matched_rules");
        a.facts = json(rs, "facts");
        a.beforeSnapshot = json(rs, "before_snapshot");
        a.moneyOutMinor = rs.getLong("money_out_minor");
        a.budgetState = en(rs, "budget_state", BudgetState.class);
        a.providerIdempotencyKey = rs.getString("provider_idempotency_key");
        a.providerRef = rs.getString("provider_ref");
        a.providerStatus = rs.getString("provider_status");
        a.result = json(rs, "result");
        a.error = json(rs, "error");
        a.compensatedByActionId = uuid(rs, "compensated_by_action_id");
        a.attemptCount = rs.getInt("attempt_count");
        a.lookupCount = rs.getInt("lookup_count");
        a.firstAttemptAt = inst(rs, "first_attempt_at");
        a.nextAttemptAt = inst(rs, "next_attempt_at");
        a.unknownSince = inst(rs, "unknown_since");
        a.resolveAfter = inst(rs, "resolve_after");
        a.leaseOwner = rs.getString("lease_owner");
        a.leaseExpiresAt = inst(rs, "lease_expires_at");
        a.leaseFence = rs.getLong("lease_fence");
        a.createdAt = inst(rs, "created_at");
        a.updatedAt = inst(rs, "updated_at");
        a.completedAt = inst(rs, "completed_at");
        return a;
      };

  static final RowMapper<ActionTransition> TRANSITION =
      (rs, i) -> {
        ActionTransition t = new ActionTransition();
        t.id = rs.getLong("id");
        t.actionId = uuid(rs, "action_id");
        t.fromStatus = en(rs, "from_status", ActionStatus.class);
        t.toStatus = en(rs, "to_status", ActionStatus.class);
        t.fence = rs.getLong("fence");
        t.actor = rs.getString("actor");
        t.reason = rs.getString("reason");
        t.at = inst(rs, "at");
        return t;
      };

  static final RowMapper<Attempt> ATTEMPT =
      (rs, i) -> {
        Attempt a = new Attempt();
        a.id = uuid(rs, "id");
        a.actionId = uuid(rs, "action_id");
        a.kind = en(rs, "kind", AttemptKind.class);
        a.attemptNo = rs.getInt("attempt_no");
        a.node = rs.getString("node");
        a.fence = rs.getLong("fence");
        a.startedAt = inst(rs, "started_at");
        a.finishedAt = inst(rs, "finished_at");
        a.outcome = en(rs, "outcome", AttemptOutcome.class);
        a.httpStatus = intOrNull(rs, "http_status");
        a.latencyMs = intOrNull(rs, "latency_ms");
        a.detail = json(rs, "detail");
        return a;
      };

  static final RowMapper<Approval> APPROVAL =
      (rs, i) -> {
        Approval a = new Approval();
        a.id = uuid(rs, "id");
        a.actionId = uuid(rs, "action_id");
        a.runId = uuid(rs, "run_id");
        a.argsHash = rs.getString("args_hash");
        a.approvedArgsHash = rs.getString("approved_args_hash");
        a.policyVersionId = uuid(rs, "policy_version_id");
        a.reasons = json(rs, "reasons");
        a.advisoryNote = json(rs, "advisory_note");
        a.status = en(rs, "status", ApprovalStatus.class);
        a.requestedAt = inst(rs, "requested_at");
        a.expiresAt = inst(rs, "expires_at");
        a.decidedBy = uuid(rs, "decided_by");
        a.decidedAt = inst(rs, "decided_at");
        a.decisionNote = rs.getString("decision_note");
        return a;
      };

  static final RowMapper<JournalEntry> ENTRY =
      (rs, i) -> {
        JournalEntry e = new JournalEntry();
        e.id = uuid(rs, "id");
        e.runId = uuid(rs, "run_id");
        e.actionId = uuid(rs, "action_id");
        e.kind = rs.getString("kind");
        e.source = rs.getString("source");
        e.sourceRef = rs.getString("source_ref");
        e.createdAt = inst(rs, "created_at");
        return e;
      };

  static final RowMapper<Posting> POSTING =
      (rs, i) -> {
        Posting p = new Posting();
        p.id = rs.getLong("id");
        p.entryId = uuid(rs, "entry_id");
        p.account = rs.getString("account");
        p.amountMinor = rs.getLong("amount_minor");
        p.currency = rs.getString("currency");
        return p;
      };

  static final RowMapper<RevertPlan> PLAN =
      (rs, i) -> {
        RevertPlan p = new RevertPlan();
        p.id = uuid(rs, "id");
        p.number = rs.getLong("number");
        p.runId = uuid(rs, "run_id");
        p.scope = en(rs, "scope", PlanScope.class);
        p.requestedBy = uuid(rs, "requested_by");
        p.status = en(rs, "status", PlanStatus.class);
        p.summary = json(rs, "summary");
        p.leaseOwner = rs.getString("lease_owner");
        p.leaseExpiresAt = inst(rs, "lease_expires_at");
        p.leaseFence = rs.getLong("lease_fence");
        p.createdAt = inst(rs, "created_at");
        p.startedAt = inst(rs, "started_at");
        p.completedAt = inst(rs, "completed_at");
        return p;
      };

  static final RowMapper<RevertItem> ITEM =
      (rs, i) -> {
        RevertItem it = new RevertItem();
        it.id = uuid(rs, "id");
        it.planId = uuid(rs, "plan_id");
        it.orderNo = rs.getInt("order_no");
        it.targetActionId = uuid(rs, "target_action_id");
        it.kind = en(rs, "kind", ItemKind.class);
        it.status = en(rs, "status", ItemStatus.class);
        it.compensationActionId = uuid(rs, "compensation_action_id");
        it.remediationActionId = uuid(rs, "remediation_action_id");
        it.moneyRecoveredMinor = rs.getLong("money_recovered_minor");
        it.moneyNotRecoveredMinor = rs.getLong("money_not_recovered_minor");
        it.note = rs.getString("note");
        return it;
      };

  static final RowMapper<WebhookEvent> WEBHOOK =
      (rs, i) -> {
        WebhookEvent w = new WebhookEvent();
        w.provider = rs.getString("provider");
        w.eventId = rs.getString("event_id");
        w.type = rs.getString("type");
        w.payload = json(rs, "payload");
        w.receivedAt = inst(rs, "received_at");
        w.processedAt = inst(rs, "processed_at");
        w.processAttempts = rs.getInt("process_attempts");
        w.lastError = rs.getString("last_error");
        return w;
      };

  static final RowMapper<ReconRun> RECON_RUN =
      (rs, i) -> {
        ReconRun r = new ReconRun();
        r.id = uuid(rs, "id");
        r.number = rs.getLong("number");
        r.connector = rs.getString("connector");
        r.windowStart = inst(rs, "window_start");
        r.windowEnd = inst(rs, "window_end");
        r.status = en(rs, "status", ReconRunStatus.class);
        r.triggeredBy = rs.getString("triggered_by");
        r.stats = json(rs, "stats");
        r.startedAt = inst(rs, "started_at");
        r.finishedAt = inst(rs, "finished_at");
        return r;
      };

  static final RowMapper<ReconFinding> FINDING =
      (rs, i) -> {
        ReconFinding f = new ReconFinding();
        f.id = uuid(rs, "id");
        f.reconRunId = uuid(rs, "recon_run_id");
        f.type = en(rs, "type", FindingType.class);
        f.severity = en(rs, "severity", Severity.class);
        f.actionId = uuid(rs, "action_id");
        f.providerObjectRef = rs.getString("provider_object_ref");
        f.expected = json(rs, "expected");
        f.actual = json(rs, "actual");
        f.status = en(rs, "status", FindingStatus.class);
        f.fingerprint = rs.getString("fingerprint");
        f.resolutionNote = rs.getString("resolution_note");
        f.resolvedBy = uuid(rs, "resolved_by");
        f.resolvedAt = inst(rs, "resolved_at");
        f.createdAt = inst(rs, "created_at");
        return f;
      };

  static final RowMapper<Event> EVENT =
      (rs, i) -> {
        Event e = new Event();
        e.seq = rs.getLong("seq");
        e.runId = uuid(rs, "run_id");
        e.actionId = uuid(rs, "action_id");
        e.type = rs.getString("type");
        e.data = json(rs, "data");
        e.at = inst(rs, "at");
        e.prevHash = rs.getString("prev_hash");
        e.hash = rs.getString("hash");
        return e;
      };
}
