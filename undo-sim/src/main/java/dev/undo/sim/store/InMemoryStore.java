package dev.undo.sim.store;

import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ActionTransition;
import dev.undo.core.model.Agent;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.Attempt;
import dev.undo.core.model.AttemptKind;
import dev.undo.core.model.Event;
import dev.undo.core.model.FindingStatus;
import dev.undo.core.model.FindingType;
import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.LlmTurn;
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
import dev.undo.core.model.Task;
import dev.undo.core.model.WebhookEvent;
import dev.undo.core.port.ConstraintViolationException;
import dev.undo.core.port.Store;
import dev.undo.core.port.TxContext;
import dev.undo.core.port.repo.ActionRepo;
import dev.undo.core.port.repo.AgentRepo;
import dev.undo.core.port.repo.ApprovalRepo;
import dev.undo.core.port.repo.AttemptRepo;
import dev.undo.core.port.repo.EventRepo;
import dev.undo.core.port.repo.LedgerRepo;
import dev.undo.core.port.repo.PolicyRepo;
import dev.undo.core.port.repo.ReconRepo;
import dev.undo.core.port.repo.RevertRepo;
import dev.undo.core.port.repo.RunRepo;
import dev.undo.core.port.repo.TaskRepo;
import dev.undo.core.port.repo.TurnRepo;
import dev.undo.core.port.repo.WebhookRepo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * In-memory Store with the same constraints as the Postgres DDL (Section 21.3): copy-on-write
 * transactions, unique and check constraints enforced at statement time, journal balance at commit,
 * identity values never reused (gaps when a TX is discarded), append-only tables. Records every
 * constraint violation (INV-13), every applied stale-fence write (INV-10) and every PRIMARY action
 * that entered EXECUTING while its run did not allow it (INV-7).
 */
public final class InMemoryStore implements Store {
  static final Set<ActionStatus> DEAD =
      EnumSet.of(
          ActionStatus.FAILED,
          ActionStatus.DENIED,
          ActionStatus.CANCELLED,
          ActionStatus.REJECTED,
          ActionStatus.EXPIRED);

  /** Composite index key without string building. */
  record K2(Object a, Object b) {}

  final Table<UUID, Run> runs =
      new Table<UUID, Run>("runs", r -> r.id)
          .unique("runs_mcp_session_id_key", r -> r.mcpSessionId)
          .index("runs_by_status", r -> r.status);
  final Table<UUID, Task> tasks =
      new Table<UUID, Task>("tasks", t -> t.id)
          .unique("tasks_run_id_seq_key", t -> t.runId + ":" + t.seq)
          .index("tasks_by_run", t -> t.runId);
  final Table<UUID, LlmTurn> turns =
      new Table<UUID, LlmTurn>("llm_turns", t -> t.id)
          .unique("llm_turns_task_id_turn_no_key", t -> t.taskId + ":" + t.turnNo)
          .index("turns_by_task", t -> t.taskId);
  final Table<UUID, Action> actions =
      new Table<UUID, Action>("actions", a -> a.id)
          .unique("actions_number_key", a -> a.number)
          .unique(
              "actions_agent_call",
              a -> a.agentCallId == null ? null : new K2(a.runId, a.agentCallId))
          .unique(
              "actions_client_key",
              a -> a.clientIdempotencyKey == null ? null : new K2(a.runId, a.clientIdempotencyKey))
          .unique("actions_dedupe", a -> a.dedupeActive ? a.dedupeKey : null)
          .unique(
              "actions_one_live_compensation",
              a ->
                  a.compensatesActionId != null && !DEAD.contains(a.status)
                      ? a.compensatesActionId
                      : null)
          .index("actions_by_status", a -> a.status)
          .index("actions_by_run", a -> a.runId)
          .index(
              "actions_by_ref",
              a -> a.providerRef == null ? null : new K2(a.connector, a.providerRef))
          .index("actions_by_target", a -> a.compensatesActionId);
  final Table<UUID, Attempt> attempts =
      new Table<UUID, Attempt>("attempts", a -> a.id)
          .unique(
              "attempts_action_id_kind_attempt_no_key",
              a -> new K2(a.actionId, new K2(a.kind, a.attemptNo)))
          .index("attempts_by_action", a -> a.actionId);
  final Table<UUID, Approval> approvals =
      new Table<UUID, Approval>("approvals", a -> a.id)
          .unique("approvals_action_id_key", a -> a.actionId)
          .index("approvals_by_status", a -> a.status);
  final Table<UUID, JournalEntry> entries =
      new Table<UUID, JournalEntry>("journal_entries", e -> e.id)
          .unique("journal_entries_action_id_kind_key", e -> new K2(e.actionId, e.kind))
          .index("entries_by_run", e -> e.runId)
          .index("entries_by_action", e -> e.actionId);
  final Table<Long, Posting> postings =
      new Table<Long, Posting>("postings", p -> p.id).index("postings_by_entry", p -> p.entryId);
  final Table<UUID, RevertPlan> plans =
      new Table<UUID, RevertPlan>("revert_plans", p -> p.id)
          .unique(
              "revert_one_open_per_run",
              p ->
                  p.status == PlanStatus.PREVIEW || p.status == PlanStatus.EXECUTING
                      ? p.runId
                      : null)
          .index("plans_by_status", p -> p.status)
          .index("plans_by_run", p -> p.runId);
  final Table<UUID, RevertItem> items =
      new Table<UUID, RevertItem>("revert_items", i -> i.id)
          .unique("revert_items_plan_id_order_no_key", i -> i.planId + ":" + i.orderNo)
          .unique(
              "revert_items_plan_id_target_action_id_key", i -> i.planId + ":" + i.targetActionId)
          .index("items_by_plan", i -> i.planId);
  final Table<String, WebhookEvent> webhooks =
      new Table<String, WebhookEvent>("webhook_events", w -> w.provider + "|" + w.eventId)
          .index("webhooks_unprocessed", w -> w.processedAt == null ? Boolean.TRUE : null);
  final Table<UUID, ReconRun> reconRuns = new Table<UUID, ReconRun>("recon_runs", r -> r.id);
  final Table<UUID, ReconFinding> findings =
      new Table<UUID, ReconFinding>("recon_findings", f -> f.id)
          .unique(
              "recon_open_fingerprint",
              f ->
                  f.status == FindingStatus.OPEN || f.status == FindingStatus.ACKNOWLEDGED
                      ? f.fingerprint
                      : null);
  final Table<UUID, PolicyVersion> policies =
      new Table<UUID, PolicyVersion>("policy_versions", p -> p.id)
          .unique("policy_versions_name_version_key", p -> p.name + ":" + p.version)
          .unique("policy_one_active_per_name", p -> p.active ? p.name : null);
  final Table<UUID, Agent> agents =
      new Table<UUID, Agent>("agents", a -> a.id).unique("agents_name_key", a -> a.name);
  final List<ActionTransition> transitions = new ArrayList<>();
  final List<Event> events = new ArrayList<>();
  final Map<UUID, List<ActionTransition>> transitionsByAction = new HashMap<>();

  private final List<Table<?, ?>> tables =
      List.of(
          runs, tasks, turns, actions, attempts, approvals, entries, postings, plans, items,
          webhooks, reconRuns, findings, policies, agents);

  // identity sequences (never reused)
  private long runNumber;
  private long actionNumber;
  private long planNumber;
  private long reconNumber;
  private long eventSeq;
  private long transitionId;
  private long postingId;

  /** Every constraint violation raised, by constraint name (INV-13). */
  public final List<String> violations = new ArrayList<>();

  /** Writes applied with a fence that was not the row's current fence (INV-10). */
  public final List<String> staleWritesApplied = new ArrayList<>();

  /** PRIMARY actions that entered EXECUTING in a TX where the run did not allow it (INV-7). */
  public final List<String> guardViolations = new ArrayList<>();

  private final List<Consumer<Set<UUID>>> commitListeners = new ArrayList<>();
  private final List<Consumer<CommitInfo>> commitObservers = new ArrayList<>();
  private Mem active;

  /** What one commit changed, for invariant checks that run after every event. */
  /**
   * What one commit changed. {@code actionChanges} holds (before, after) pairs; before is null for
   * inserts.
   */
  public record CommitInfo(
      List<ActionTransition> transitions,
      Set<UUID> actionIds,
      List<Action[]> actionChanges,
      Set<UUID> runIds) {}

  public void onActionsCommitted(Consumer<Set<UUID>> listener) {
    commitListeners.add(listener);
  }

  public void onCommit(Consumer<CommitInfo> observer) {
    commitObservers.add(observer);
  }

  @Override
  public synchronized <T> T tx(Function<TxContext, T> work) {
    if (active != null) {
      throw new IllegalStateException("nested transactions are not supported");
    }
    Mem m = new Mem();
    active = m;
    T result;
    try {
      result = work.apply(m);
      m.deferredChecks();
    } finally {
      active = null;
    }
    m.commit();
    return result;
  }

  /** Read-only access to committed state for invariant checks and tests (no TX). */
  public Snapshot snapshot() {
    return new Snapshot(this);
  }

  /** Deletes all data except agents and policy versions (demo seed reset). */
  public synchronized void resetData() {
    for (Table<?, ?> t :
        List.of(
            runs, tasks, turns, actions, attempts, approvals, entries, postings, plans, items,
            webhooks, reconRuns, findings)) {
      t.clear();
    }
    transitions.clear();
    transitionsByAction.clear();
    events.clear();
  }

  // ── cheap read-only peeks for idle worker ticks (committed state, outside any TX) ──

  private java.util.Collection<UUID> idsWithStatus(ActionStatus s) {
    return actions.indexData.get("actions_by_status").getOrDefault(s, java.util.Set.of());
  }

  /** Is there an APPROVED or due RETRY_WAIT action (ignoring the run guard)? */
  public synchronized boolean peekRunnable(Instant now) {
    if (!idsWithStatus(ActionStatus.APPROVED).isEmpty()) {
      return true;
    }
    for (UUID id : idsWithStatus(ActionStatus.RETRY_WAIT)) {
      Action a = actions.base.get(id);
      if (a.nextAttemptAt == null || !a.nextAttemptAt.isAfter(now)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A plan step is due: an EXECUTING plan with a free lease, or a PREVIEW older than the cutoff.
   */
  public synchronized boolean peekRevertWork(Instant now, Instant previewCutoff) {
    for (RevertPlan p : plans.base.values()) {
      if (p.status == PlanStatus.EXECUTING
          && (p.leaseExpiresAt == null || p.leaseExpiresAt.isBefore(now))) {
        return true;
      }
      if (p.status == PlanStatus.PREVIEW && p.createdAt.isBefore(previewCutoff)) {
        return true;
      }
    }
    return false;
  }

  public synchronized boolean peekExpiredLeases(Instant now) {
    for (UUID id : idsWithStatus(ActionStatus.EXECUTING)) {
      Action a = actions.base.get(id);
      if (a.leaseExpiresAt != null && a.leaseExpiresAt.isBefore(now)) {
        return true;
      }
    }
    return false;
  }

  public synchronized boolean peekResolvable(Instant now) {
    for (UUID id : idsWithStatus(ActionStatus.UNKNOWN)) {
      Action a = actions.base.get(id);
      if ((a.resolveAfter == null || !a.resolveAfter.isAfter(now))
          && (a.leaseExpiresAt == null || a.leaseExpiresAt.isBefore(now))) {
        return true;
      }
    }
    return false;
  }

  public synchronized boolean peekExpiredApprovals(Instant now) {
    for (UUID id :
        approvals
            .indexData
            .get("approvals_by_status")
            .getOrDefault(ApprovalStatus.PENDING, java.util.Set.of())) {
      if (!approvals.base.get(id).expiresAt.isAfter(now)) {
        return true;
      }
    }
    return false;
  }

  public synchronized boolean peekUnprocessedWebhooks() {
    return !webhooks
        .indexData
        .get("webhooks_unprocessed")
        .getOrDefault(Boolean.TRUE, java.util.Set.of())
        .isEmpty();
  }

  /** Actions in APPROVED, EXECUTING, RETRY_WAIT or UNKNOWN. */
  public synchronized long snapshotInFlight() {
    return idsWithStatus(ActionStatus.APPROVED).size()
        + idsWithStatus(ActionStatus.EXECUTING).size()
        + idsWithStatus(ActionStatus.RETRY_WAIT).size()
        + idsWithStatus(ActionStatus.UNKNOWN).size();
  }

  List<Action> committedActionsOfRun(UUID runId) {
    List<Action> out = new ArrayList<>();
    for (UUID id :
        actions.indexData.get("actions_by_run").getOrDefault(runId, java.util.Set.of())) {
      out.add(actions.base.get(id));
    }
    return out;
  }

  private void violation(ConstraintViolationException e) {
    violations.add(e.constraint());
  }

  // ─────────────────────────────────────────────────────────────────────

  private final class Mem implements TxContext {
    final Table<UUID, Run>.View vRuns = runs.view(InMemoryStore.this::violation);
    final Table<UUID, Task>.View vTasks = tasks.view(InMemoryStore.this::violation);
    final Table<UUID, LlmTurn>.View vTurns = turns.view(InMemoryStore.this::violation);
    final Table<UUID, Action>.View vActions = actions.view(InMemoryStore.this::violation);
    final Table<UUID, Attempt>.View vAttempts = attempts.view(InMemoryStore.this::violation);
    final Table<UUID, Approval>.View vApprovals = approvals.view(InMemoryStore.this::violation);
    final Table<UUID, JournalEntry>.View vEntries = entries.view(InMemoryStore.this::violation);
    final Table<Long, Posting>.View vPostings = postings.view(InMemoryStore.this::violation);
    final Table<UUID, RevertPlan>.View vPlans = plans.view(InMemoryStore.this::violation);
    final Table<UUID, RevertItem>.View vItems = items.view(InMemoryStore.this::violation);
    final Table<String, WebhookEvent>.View vWebhooks = webhooks.view(InMemoryStore.this::violation);
    final Table<UUID, ReconRun>.View vReconRuns = reconRuns.view(InMemoryStore.this::violation);
    final Table<UUID, ReconFinding>.View vFindings = findings.view(InMemoryStore.this::violation);
    final Table<UUID, PolicyVersion>.View vPolicies = policies.view(InMemoryStore.this::violation);
    final Table<UUID, Agent>.View vAgents = agents.view(InMemoryStore.this::violation);
    final List<ActionTransition> newTransitions = new ArrayList<>();
    final List<Event> newEvents = new ArrayList<>();
    final Set<UUID> touchedActions = new java.util.LinkedHashSet<>();
    final Set<UUID> touchedEntries = new java.util.LinkedHashSet<>();

    private ConstraintViolationException fail(String constraint, String msg) {
      ConstraintViolationException e = new ConstraintViolationException(constraint, msg);
      violation(e);
      return e;
    }

    void deferredChecks() {
      for (UUID entryId : touchedEntries) {
        long sum = 0;
        for (Posting p : vPostings.byIndex("postings_by_entry", entryId)) {
          sum += p.amountMinor;
        }
        if (sum != 0) {
          throw fail(
              "postings_balanced",
              "journal entry " + entryId + " is not balanced (sum=" + sum + ")");
        }
      }
      // INV-7 guard: a PRIMARY action enters EXECUTING only while its run is ACTIVE or COMPLETED.
      for (ActionTransition t : newTransitions) {
        if (t.toStatus == ActionStatus.EXECUTING) {
          Action a = vActions.get(t.actionId);
          if (a != null && a.kind == ActionKind.PRIMARY) {
            Run r = vRuns.get(a.runId);
            if (r != null && !r.status.allowsPrimaryExecution()) {
              guardViolations.add(a.display() + " entered EXECUTING while run was " + r.status);
            }
          }
        }
      }
    }

    void commit() {
      List<Action[]> changes = new ArrayList<>(vActions.writes.size());
      for (Map.Entry<UUID, Action> w : vActions.writes.entrySet()) {
        changes.add(new Action[] {actions.base.get(w.getKey()), w.getValue()});
      }
      Set<UUID> runIds = new java.util.LinkedHashSet<>(vRuns.writes.keySet());
      for (Table<?, ?>.View v :
          List.<Table<?, ?>.View>of(
              vRuns,
              vTasks,
              vTurns,
              vActions,
              vAttempts,
              vApprovals,
              vEntries,
              vPostings,
              vPlans,
              vItems,
              vWebhooks,
              vReconRuns,
              vFindings,
              vPolicies,
              vAgents)) {
        if (!v.writes.isEmpty()) {
          v.commit();
        }
      }
      for (ActionTransition t : newTransitions) {
        transitions.add(t);
        transitionsByAction.computeIfAbsent(t.actionId, k -> new ArrayList<>()).add(t);
      }
      events.addAll(newEvents);
      if (!touchedActions.isEmpty()) {
        // Insertion order, not Set.copyOf: immutable sets iterate in a per-JVM random order.
        Set<UUID> ids = new java.util.LinkedHashSet<>(touchedActions);
        for (Consumer<Set<UUID>> l : commitListeners) {
          l.accept(ids);
        }
      }
      if (newTransitions.isEmpty() && changes.isEmpty() && runIds.isEmpty()) {
        return;
      }
      CommitInfo info = new CommitInfo(newTransitions, touchedActions, changes, runIds);
      for (Consumer<CommitInfo> o : commitObservers) {
        o.accept(info);
      }
    }

    @Override
    public RunRepo runs() {
      return runRepo;
    }

    @Override
    public TaskRepo tasks() {
      return taskRepo;
    }

    @Override
    public ActionRepo actions() {
      return actionRepo;
    }

    @Override
    public AttemptRepo attempts() {
      return attemptRepo;
    }

    @Override
    public ApprovalRepo approvals() {
      return approvalRepo;
    }

    @Override
    public TurnRepo turns() {
      return turnRepo;
    }

    @Override
    public LedgerRepo ledger() {
      return ledgerRepo;
    }

    @Override
    public RevertRepo reverts() {
      return revertRepo;
    }

    @Override
    public WebhookRepo webhooks() {
      return webhookRepo;
    }

    @Override
    public ReconRepo recon() {
      return reconRepo;
    }

    @Override
    public EventRepo events() {
      return eventRepo;
    }

    @Override
    public PolicyRepo policies() {
      return policyRepo;
    }

    @Override
    public AgentRepo agents() {
      return agentRepo;
    }

    // ── runs ──
    void checkRun(Run r) {
      if (r.moneyReservedMinor < 0 || r.moneySpentMinor < 0) {
        throw fail("runs_check", "money_reserved_minor >= 0 and money_spent_minor >= 0");
      }
      if (r.moneyReservedMinor + r.moneySpentMinor > r.moneyCapMinor) {
        throw fail("runs_check1", "money_reserved_minor + money_spent_minor <= money_cap_minor");
      }
      if (r.actionCount < 0 || r.actionCount > r.actionCap) {
        throw fail("runs_check2", "action_count >= 0 and action_count <= action_cap");
      }
    }

    Run requireRun(UUID id) {
      Run r = vRuns.get(id);
      if (r == null) {
        throw new IllegalStateException("no run " + id);
      }
      return r;
    }

    final RunRepo runRepo =
        new RunRepo() {
          @Override
          public Run insert(Run run) {
            Run r = run.copy();
            r.number = ++runNumber;
            checkRun(r);
            vRuns.put(r);
            return r.copy();
          }

          @Override
          public Optional<Run> get(UUID id) {
            return Optional.ofNullable(vRuns.get(id));
          }

          @Override
          public Optional<Run> byNumber(long number) {
            return vRuns.all().stream().filter(r -> r.number == number).findFirst();
          }

          @Override
          public Optional<Run> byMcpSession(String sessionId) {
            return vRuns.all().stream().filter(r -> sessionId.equals(r.mcpSessionId)).findFirst();
          }

          @Override
          public Run lock(UUID id) {
            return requireRun(id);
          }

          @Override
          public void update(Run run) {
            Run cur = requireRun(run.id);
            Run next = run.copy();
            // budget columns are not written by update()
            next.moneyCapMinor = cur.moneyCapMinor;
            next.moneyReservedMinor = cur.moneyReservedMinor;
            next.moneySpentMinor = cur.moneySpentMinor;
            next.actionCap = cur.actionCap;
            next.actionCount = cur.actionCount;
            next.llmTokenCap = cur.llmTokenCap;
            next.llmTokensUsed = cur.llmTokensUsed;
            next.number = cur.number;
            // immutable after insert, exactly like the SQL update (PgRunRepo)
            next.agentId = cur.agentId;
            next.kind = cur.kind;
            next.policyVersionId = cur.policyVersionId;
            next.currency = cur.currency;
            next.createdAt = cur.createdAt;
            vRuns.put(next);
          }

          @Override
          public boolean reserve(UUID id, long money, int n) {
            Run r = requireRun(id);
            if (r.moneyReservedMinor + r.moneySpentMinor + money <= r.moneyCapMinor
                && r.actionCount + n <= r.actionCap) {
              r.moneyReservedMinor += money;
              r.actionCount += n;
              checkRun(r);
              vRuns.put(r);
              return true;
            }
            return false;
          }

          @Override
          public void release(UUID id, long money, int n) {
            Run r = requireRun(id);
            r.moneyReservedMinor -= money;
            r.actionCount -= n;
            checkRun(r);
            vRuns.put(r);
          }

          @Override
          public void settle(UUID id, long money) {
            Run r = requireRun(id);
            r.moneyReservedMinor -= money;
            r.moneySpentMinor += money;
            checkRun(r);
            vRuns.put(r);
          }

          @Override
          public void reserveUnchecked(UUID id, long money, int n) {
            Run r = requireRun(id);
            r.moneyReservedMinor += money;
            r.actionCount += n;
            checkRun(r);
            vRuns.put(r);
          }

          @Override
          public void addTokens(UUID id, long tokens) {
            Run r = requireRun(id);
            r.llmTokensUsed += tokens;
            vRuns.put(r);
          }

          @Override
          public Optional<Run> claimHosted(String owner, Instant now, Instant leaseUntil) {
            Optional<Run> r =
                vRuns.byIndex("runs_by_status", RunStatus.ACTIVE).stream()
                    .filter(
                        x ->
                            x.kind == RunKind.HOSTED
                                && (x.leaseExpiresAt == null || x.leaseExpiresAt.isBefore(now)))
                    .min(Comparator.comparingLong(x -> x.number));
            r.ifPresent(
                x -> {
                  x.leaseOwner = owner;
                  x.leaseExpiresAt = leaseUntil;
                  x.leaseFence += 1;
                  vRuns.put(x);
                });
            return r.map(Run::copy);
          }

          @Override
          public boolean heartbeat(UUID id, long fence, Instant leaseUntil) {
            Run r = vRuns.get(id);
            if (r == null || r.leaseFence != fence) {
              return false;
            }
            r.leaseExpiresAt = leaseUntil;
            vRuns.put(r);
            return true;
          }

          @Override
          public List<Run> lockIdleExternal(Instant idleBefore, int limit) {
            return vRuns.byIndex("runs_by_status", RunStatus.ACTIVE).stream()
                .filter(x -> x.kind == RunKind.EXTERNAL && x.lastActivityAt.isBefore(idleBefore))
                .sorted(Comparator.comparingLong(x -> x.number))
                .limit(limit)
                .toList();
          }

          @Override
          public List<Run> list(RunStatus status, RunKind kind, Long beforeNumber, int limit) {
            return vRuns.all().stream()
                .filter(x -> status == null || x.status == status)
                .filter(x -> kind == null || x.kind == kind)
                .filter(x -> beforeNumber == null || x.number < beforeNumber)
                .sorted(Comparator.comparingLong((Run x) -> x.number).reversed())
                .limit(limit)
                .toList();
          }
        };

    // ── tasks and turns ──
    final TaskRepo taskRepo =
        new TaskRepo() {
          @Override
          public void insert(Task task) {
            vTasks.put(task);
          }

          @Override
          public Optional<Task> get(UUID id) {
            return Optional.ofNullable(vTasks.get(id));
          }

          @Override
          public List<Task> byRun(UUID runId) {
            return vTasks.byIndex("tasks_by_run", runId).stream()
                .sorted(Comparator.comparingInt(t -> t.seq))
                .toList();
          }

          @Override
          public void update(Task task) {
            vTasks.put(task);
          }
        };

    final TurnRepo turnRepo =
        new TurnRepo() {
          @Override
          public void insert(LlmTurn turn) {
            vTurns.put(turn);
          }

          @Override
          public List<LlmTurn> byTask(UUID taskId) {
            return vTurns.byIndex("turns_by_task", taskId).stream()
                .sorted(Comparator.comparingInt(t -> t.turnNo))
                .toList();
          }

          @Override
          public void setToolResults(LlmTurn turn) {
            LlmTurn cur = vTurns.get(turn.id);
            cur.toolResults = turn.toolResults;
            vTurns.put(cur);
          }
        };

    // ── actions ──
    final ActionRepo actionRepo =
        new ActionRepo() {
          @Override
          public Action insert(Action action) {
            Action a = action.copy();
            a.number = ++actionNumber;
            vActions.put(a);
            touchedActions.add(a.id);
            return a.copy();
          }

          @Override
          public Optional<Action> get(UUID id) {
            return Optional.ofNullable(vActions.get(id));
          }

          @Override
          public Optional<Action> byNumber(long number) {
            List<Action> l = vActions.byIndex("actions_number_key", number);
            return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
          }

          @Override
          public Action lock(UUID id) {
            Action a = vActions.get(id);
            if (a == null) {
              throw new IllegalStateException("no action " + id);
            }
            return a;
          }

          @Override
          public Optional<Action> byCallKey(UUID runId, String agentCallId, String clientKey) {
            if (agentCallId != null) {
              List<Action> l = vActions.byIndex("actions_agent_call", new K2(runId, agentCallId));
              if (!l.isEmpty()) {
                return Optional.of(l.get(0));
              }
            }
            if (clientKey != null) {
              List<Action> l = vActions.byIndex("actions_client_key", new K2(runId, clientKey));
              if (!l.isEmpty()) {
                return Optional.of(l.get(0));
              }
            }
            return Optional.empty();
          }

          @Override
          public Optional<Action> activeByDedupeKey(String dedupeKey) {
            List<Action> l = vActions.byIndex("actions_dedupe", dedupeKey);
            return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
          }

          @Override
          public boolean update(Action action, ActionStatus expectedStatus, long expectedFence) {
            Action cur = vActions.get(action.id);
            if (cur == null || cur.status != expectedStatus || cur.leaseFence != expectedFence) {
              return false;
            }
            Action next = action.copy();
            next.number = cur.number;
            vActions.put(next);
            touchedActions.add(next.id);
            return true;
          }

          @Override
          public void updateUnconditional(Action action, long writerFence) {
            Action cur = vActions.get(action.id);
            if (cur != null && cur.leaseFence != writerFence) {
              staleWritesApplied.add(
                  action.display()
                      + " written with fence "
                      + writerFence
                      + " while current fence is "
                      + cur.leaseFence);
            }
            Action next = action.copy();
            next.number = cur == null ? action.number : cur.number;
            vActions.put(next);
            touchedActions.add(next.id);
          }

          private List<Action> byStatuses(Set<ActionStatus> statuses) {
            List<Action> out = new ArrayList<>();
            for (ActionStatus s : statuses) {
              out.addAll(vActions.byIndex("actions_by_status", s));
            }
            out.sort(Comparator.comparingLong(a -> a.number));
            return out;
          }

          @Override
          public List<Action> lockRunnable(Instant now, int limit) {
            List<Action> out = new ArrayList<>();
            for (Action a :
                byStatuses(EnumSet.of(ActionStatus.APPROVED, ActionStatus.RETRY_WAIT))) {
              if (a.nextAttemptAt != null && a.nextAttemptAt.isAfter(now)) {
                continue;
              }
              if (a.kind == ActionKind.PRIMARY
                  && !requireRun(a.runId).status.allowsPrimaryExecution()) {
                continue;
              }
              out.add(a);
              if (out.size() == limit) {
                break;
              }
            }
            return out;
          }

          @Override
          public List<Action> lockExpiredExecuting(Instant now, int limit) {
            return byStatuses(EnumSet.of(ActionStatus.EXECUTING)).stream()
                .filter(a -> a.leaseExpiresAt != null && a.leaseExpiresAt.isBefore(now))
                .limit(limit)
                .toList();
          }

          @Override
          public List<Action> lockResolvable(Instant now, int limit) {
            return byStatuses(EnumSet.of(ActionStatus.UNKNOWN)).stream()
                .filter(a -> a.resolveAfter == null || !a.resolveAfter.isAfter(now))
                .filter(a -> a.leaseExpiresAt == null || a.leaseExpiresAt.isBefore(now))
                .limit(limit)
                .toList();
          }

          @Override
          public List<Action> lockQueuedPrimary(UUID runId) {
            return vActions.byIndex("actions_by_run", runId).stream()
                .filter(a -> a.kind == ActionKind.PRIMARY)
                .filter(
                    a ->
                        a.status == ActionStatus.AWAITING_APPROVAL
                            || a.status == ActionStatus.APPROVED
                            || a.status == ActionStatus.RETRY_WAIT)
                .sorted(Comparator.comparingLong(a -> a.number))
                .toList();
          }

          @Override
          public List<Action> byRun(UUID runId) {
            return vActions.byIndex("actions_by_run", runId).stream()
                .sorted(Comparator.comparingLong(a -> a.number))
                .toList();
          }

          @Override
          public Map<ActionStatus, Long> countByStatus(UUID runId) {
            Map<ActionStatus, Long> m = new EnumMap<>(ActionStatus.class);
            for (Action a : vActions.byIndex("actions_by_run", runId)) {
              m.merge(a.status, 1L, Long::sum);
            }
            return m;
          }

          @Override
          public Optional<Action> liveCompensationOf(UUID target) {
            List<Action> l = vActions.byIndex("actions_one_live_compensation", target);
            return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
          }

          @Override
          public Optional<Action> byProviderRef(String connector, String providerRef) {
            return vActions.byIndex("actions_by_ref", new K2(connector, providerRef)).stream()
                .filter(a -> a.kind == ActionKind.PRIMARY)
                .min(Comparator.comparingLong(a -> a.number))
                .or(
                    () ->
                        vActions.byIndex("actions_by_ref", new K2(connector, providerRef)).stream()
                            .min(Comparator.comparingLong(a -> a.number)));
          }

          @Override
          public List<Action> forRecon(String connector, Instant from, Instant to) {
            return vActions.all().stream()
                .filter(a -> a.connector.equals(connector))
                .filter(
                    a ->
                        a.status == ActionStatus.UNKNOWN
                            || a.status == ActionStatus.NEEDS_REVIEW
                            || (!a.updatedAt.isBefore(from) && a.updatedAt.isBefore(to)))
                .sorted(Comparator.comparingLong(a -> a.number))
                .toList();
          }

          @Override
          public List<Action> byStatus(ActionStatus status, int limit) {
            return vActions.byIndex("actions_by_status", status).stream()
                .sorted(Comparator.comparingLong((Action a) -> a.number).reversed())
                .limit(limit)
                .toList();
          }

          @Override
          public Map<ActionStatus, Long> globalCounts() {
            Map<ActionStatus, Long> m = new EnumMap<>(ActionStatus.class);
            for (Action a : vActions.all()) {
              m.merge(a.status, 1L, Long::sum);
            }
            return m;
          }

          @Override
          public long moneyOutSince(Instant since) {
            long sum = 0;
            for (Action a : vActions.byIndex("actions_by_status", ActionStatus.SUCCEEDED)) {
              if (a.kind == ActionKind.PRIMARY
                  && a.completedAt != null
                  && !a.completedAt.isBefore(since)) {
                sum += a.moneyOutMinor;
              }
            }
            return sum;
          }

          @Override
          public long countInFlight() {
            return byStatuses(
                    EnumSet.of(
                        ActionStatus.APPROVED,
                        ActionStatus.EXECUTING,
                        ActionStatus.RETRY_WAIT,
                        ActionStatus.UNKNOWN))
                .size();
          }

          @Override
          public void addTransition(ActionTransition t) {
            ActionTransition c = t.copy();
            c.id = ++transitionId;
            newTransitions.add(c);
          }

          @Override
          public List<ActionTransition> transitions(UUID actionId) {
            List<ActionTransition> out =
                new ArrayList<>(transitionsByAction.getOrDefault(actionId, List.of()));
            for (ActionTransition t : newTransitions) {
              if (t.actionId.equals(actionId)) {
                out.add(t);
              }
            }
            return out.stream().map(ActionTransition::copy).toList();
          }
        };

    final AttemptRepo attemptRepo =
        new AttemptRepo() {
          @Override
          public void insert(Attempt attempt) {
            if (!vActions.exists(attempt.actionId)) {
              throw fail("attempts_action_id_fkey", "no action " + attempt.actionId);
            }
            vAttempts.put(attempt);
          }

          @Override
          public void update(Attempt attempt) {
            vAttempts.put(attempt);
          }

          @Override
          public Optional<Attempt> get(UUID actionId, AttemptKind kind, int attemptNo) {
            return vAttempts
                .byIndex(
                    "attempts_action_id_kind_attempt_no_key",
                    new K2(actionId, new K2(kind, attemptNo)))
                .stream()
                .findFirst();
          }

          @Override
          public Optional<Attempt> lastExecute(UUID actionId) {
            return vAttempts.byIndex("attempts_by_action", actionId).stream()
                .filter(a -> a.kind == AttemptKind.EXECUTE)
                .max(Comparator.comparingInt(a -> a.attemptNo));
          }

          @Override
          public List<Attempt> byAction(UUID actionId) {
            return vAttempts.byIndex("attempts_by_action", actionId).stream()
                .sorted(
                    Comparator.comparing((Attempt a) -> a.startedAt)
                        .thenComparing(a -> a.kind)
                        .thenComparingInt(a -> a.attemptNo))
                .toList();
          }
        };

    final ApprovalRepo approvalRepo =
        new ApprovalRepo() {
          @Override
          public void insert(Approval a) {
            vApprovals.put(a);
          }

          @Override
          public Optional<Approval> get(UUID id) {
            return Optional.ofNullable(vApprovals.get(id));
          }

          @Override
          public Optional<Approval> byAction(UUID actionId) {
            return vApprovals.byIndex("approvals_action_id_key", actionId).stream().findFirst();
          }

          @Override
          public Approval lock(UUID id) {
            Approval a = vApprovals.get(id);
            if (a == null) {
              throw new IllegalStateException("no approval " + id);
            }
            return a;
          }

          @Override
          public void update(Approval a) {
            vApprovals.put(a);
          }

          @Override
          public List<Approval> lockExpired(Instant now, int limit) {
            return vApprovals.byIndex("approvals_by_status", ApprovalStatus.PENDING).stream()
                .filter(a -> !a.expiresAt.isAfter(now))
                .sorted(Comparator.comparing((Approval a) -> a.expiresAt).thenComparing(a -> a.id))
                .limit(limit)
                .toList();
          }

          @Override
          public List<Approval> list(ApprovalStatus status, UUID runId, int limit) {
            List<Approval> src =
                status == null
                    ? vApprovals.all()
                    : vApprovals.byIndex("approvals_by_status", status);
            return src.stream()
                .filter(a -> runId == null || a.runId.equals(runId))
                .sorted(
                    Comparator.comparing((Approval a) -> a.requestedAt)
                        .thenComparing(a -> a.id)
                        .reversed())
                .limit(limit)
                .toList();
          }

          @Override
          public List<Approval> missingAdvisory(int limit) {
            return vApprovals.byIndex("approvals_by_status", ApprovalStatus.PENDING).stream()
                .filter(a -> a.advisoryNote == null)
                .sorted(
                    Comparator.comparing((Approval a) -> a.requestedAt).thenComparing(a -> a.id))
                .limit(limit)
                .toList();
          }

          @Override
          public void setAdvisory(Approval approval) {
            Approval cur = vApprovals.get(approval.id);
            cur.advisoryNote = approval.advisoryNote;
            vApprovals.put(cur);
          }
        };

    final LedgerRepo ledgerRepo =
        new LedgerRepo() {
          @Override
          public boolean entryExists(UUID actionId, String kind) {
            return !vEntries
                .byIndex("journal_entries_action_id_kind_key", new K2(actionId, kind))
                .isEmpty();
          }

          @Override
          public void insert(JournalEntry entry, List<Posting> list) {
            vEntries.put(entry);
            for (Posting p : list) {
              if (p.amountMinor == 0) {
                throw fail("postings_amount_minor_check", "amount_minor <> 0");
              }
              Posting c = p.copy();
              c.id = ++postingId;
              c.entryId = entry.id;
              vPostings.put(c);
            }
            touchedEntries.add(entry.id);
          }

          @Override
          public List<JournalEntry> entriesByRun(UUID runId) {
            return vEntries.byIndex("entries_by_run", runId).stream()
                .sorted(
                    Comparator.comparing((JournalEntry e) -> e.createdAt).thenComparing(e -> e.id))
                .toList();
          }

          @Override
          public List<Posting> postingsByRun(UUID runId) {
            List<Posting> out = new ArrayList<>();
            for (JournalEntry e : vEntries.byIndex("entries_by_run", runId)) {
              out.addAll(vPostings.byIndex("postings_by_entry", e.id));
            }
            out.sort(Comparator.comparingLong(p -> p.id));
            return out;
          }

          @Override
          public List<Posting> postingsByAction(UUID actionId) {
            List<Posting> out = new ArrayList<>();
            for (JournalEntry e : vEntries.byIndex("entries_by_action", actionId)) {
              out.addAll(vPostings.byIndex("postings_by_entry", e.id));
            }
            out.sort(Comparator.comparingLong(p -> p.id));
            return out;
          }
        };

    final RevertRepo revertRepo =
        new RevertRepo() {
          @Override
          public RevertPlan insertPlan(RevertPlan plan) {
            RevertPlan p = plan.copy();
            p.number = ++planNumber;
            vPlans.put(p);
            return p.copy();
          }

          @Override
          public Optional<RevertPlan> plan(UUID id) {
            return Optional.ofNullable(vPlans.get(id));
          }

          @Override
          public Optional<RevertPlan> planByNumber(long number) {
            return vPlans.all().stream().filter(p -> p.number == number).findFirst();
          }

          @Override
          public Optional<RevertPlan> openForRun(UUID runId) {
            return vPlans.byIndex("revert_one_open_per_run", runId).stream().findFirst();
          }

          @Override
          public List<RevertPlan> byRun(UUID runId) {
            return vPlans.byIndex("plans_by_run", runId).stream()
                .sorted(Comparator.comparingLong(p -> p.number))
                .toList();
          }

          @Override
          public RevertPlan lockPlan(UUID id) {
            RevertPlan p = vPlans.get(id);
            if (p == null) {
              throw new IllegalStateException("no plan " + id);
            }
            return p;
          }

          @Override
          public void updatePlan(RevertPlan plan) {
            RevertPlan cur = vPlans.get(plan.id);
            RevertPlan next = plan.copy();
            next.number = cur.number;
            vPlans.put(next);
          }

          @Override
          public List<RevertPlan> lockExecutable(Instant now, int limit) {
            return vPlans.byIndex("plans_by_status", PlanStatus.EXECUTING).stream()
                .filter(p -> p.leaseExpiresAt == null || p.leaseExpiresAt.isBefore(now))
                .sorted(Comparator.comparingLong(p -> p.number))
                .limit(limit)
                .toList();
          }

          @Override
          public List<RevertPlan> lockExpiredPreviews(Instant createdBefore, int limit) {
            return vPlans.byIndex("plans_by_status", PlanStatus.PREVIEW).stream()
                .filter(p -> p.createdAt.isBefore(createdBefore))
                .sorted(Comparator.comparingLong(p -> p.number))
                .limit(limit)
                .toList();
          }

          @Override
          public void insertItem(RevertItem item) {
            vItems.put(item);
          }

          @Override
          public void updateItem(RevertItem item) {
            vItems.put(item);
          }

          @Override
          public List<RevertItem> items(UUID planId) {
            return vItems.byIndex("items_by_plan", planId).stream()
                .sorted(Comparator.comparingInt(i -> i.orderNo))
                .toList();
          }
        };

    final WebhookRepo webhookRepo =
        new WebhookRepo() {
          @Override
          public boolean insertIfAbsent(WebhookEvent e) {
            if (vWebhooks.exists(e.provider + "|" + e.eventId)) {
              return false;
            }
            vWebhooks.put(e);
            return true;
          }

          @Override
          public Optional<WebhookEvent> get(String provider, String eventId) {
            return Optional.ofNullable(vWebhooks.get(provider + "|" + eventId));
          }

          @Override
          public WebhookEvent lock(String provider, String eventId) {
            WebhookEvent w = vWebhooks.get(provider + "|" + eventId);
            if (w == null) {
              throw new IllegalStateException("no webhook " + eventId);
            }
            return w;
          }

          @Override
          public void update(WebhookEvent e) {
            vWebhooks.put(e);
          }

          @Override
          public List<WebhookEvent> lockUnprocessed(int limit) {
            return vWebhooks.byIndex("webhooks_unprocessed", Boolean.TRUE).stream()
                .sorted(
                    Comparator.comparing((WebhookEvent w) -> w.receivedAt)
                        .thenComparing(w -> w.eventId))
                .limit(limit)
                .toList();
          }

          @Override
          public List<WebhookEvent> unprocessedReceivedBefore(Instant cutoff) {
            return vWebhooks.byIndex("webhooks_unprocessed", Boolean.TRUE).stream()
                .filter(w -> w.receivedAt.isBefore(cutoff))
                .sorted(
                    Comparator.comparing((WebhookEvent w) -> w.receivedAt)
                        .thenComparing(w -> w.eventId))
                .toList();
          }
        };

    final ReconRepo reconRepo =
        new ReconRepo() {
          @Override
          public ReconRun insertRun(ReconRun run) {
            ReconRun r = run.copy();
            r.number = ++reconNumber;
            vReconRuns.put(r);
            return r.copy();
          }

          @Override
          public void updateRun(ReconRun run) {
            ReconRun cur = vReconRuns.get(run.id);
            ReconRun next = run.copy();
            next.number = cur.number;
            vReconRuns.put(next);
          }

          @Override
          public Optional<ReconRun> lastCompleted(String connector) {
            return vReconRuns.all().stream()
                .filter(r -> r.connector.equals(connector) && r.status == ReconRunStatus.COMPLETED)
                .max(Comparator.comparingLong(r -> r.number));
          }

          @Override
          public List<ReconRun> runs(int limit) {
            return vReconRuns.all().stream()
                .sorted(Comparator.comparingLong((ReconRun r) -> r.number).reversed())
                .limit(limit)
                .toList();
          }

          @Override
          public Optional<ReconFinding> openByFingerprint(String fingerprint) {
            return vFindings.byIndex("recon_open_fingerprint", fingerprint).stream().findFirst();
          }

          @Override
          public void insertFinding(ReconFinding finding) {
            vFindings.put(finding);
          }

          @Override
          public void updateFinding(ReconFinding finding) {
            vFindings.put(finding);
          }

          @Override
          public Optional<ReconFinding> finding(UUID id) {
            return Optional.ofNullable(vFindings.get(id));
          }

          @Override
          public List<ReconFinding> findingsOfAction(UUID actionId) {
            return vFindings.all().stream()
                .filter(f -> actionId.equals(f.actionId))
                .sorted(
                    Comparator.comparing((ReconFinding f) -> f.createdAt)
                        .thenComparing(f -> f.id)
                        .reversed())
                .toList();
          }

          @Override
          public List<OpenCount> openCounts() {
            java.util.TreeMap<String, OpenCount> m = new java.util.TreeMap<>();
            for (ReconFinding f : vFindings.all()) {
              if (f.status == FindingStatus.OPEN || f.status == FindingStatus.ACKNOWLEDGED) {
                m.merge(
                    f.type.name() + "|" + f.severity.name(),
                    new OpenCount(f.type, f.severity, 1),
                    (a, b) -> new OpenCount(a.type(), a.severity(), a.count() + 1));
              }
            }
            return List.copyOf(m.values());
          }

          @Override
          public List<ReconFinding> findings(FindingStatus status, FindingType type, int limit) {
            return vFindings.all().stream()
                .filter(f -> status == null || f.status == status)
                .filter(f -> type == null || f.type == type)
                .sorted(
                    Comparator.comparing((ReconFinding f) -> f.createdAt)
                        .thenComparing(f -> f.id)
                        .reversed())
                .limit(limit)
                .toList();
          }
        };

    final EventRepo eventRepo =
        new EventRepo() {
          @Override
          public Event append(Event event) {
            Event e = event.copy();
            e.seq = ++eventSeq;
            newEvents.add(e);
            return e.copy();
          }

          @Override
          public long maxSeq() {
            return events.isEmpty() ? 0 : events.get(events.size() - 1).seq;
          }

          @Override
          public List<Event> after(long afterSeq, UUID runId, int limit) {
            List<Event> out = new ArrayList<>();
            for (Event e : events) {
              if (e.seq > afterSeq && (runId == null || runId.equals(e.runId))) {
                out.add(e.copy());
                if (out.size() == limit) {
                  break;
                }
              }
            }
            return out;
          }
        };

    final PolicyRepo policyRepo =
        new PolicyRepo() {
          @Override
          public void insert(PolicyVersion v) {
            vPolicies.put(v);
          }

          @Override
          public Optional<PolicyVersion> get(UUID id) {
            return Optional.ofNullable(vPolicies.get(id));
          }

          @Override
          public Optional<PolicyVersion> active(String name) {
            return vPolicies.byIndex("policy_one_active_per_name", name).stream().findFirst();
          }

          @Override
          public Optional<PolicyVersion> version(String name, int version) {
            return vPolicies
                .byIndex("policy_versions_name_version_key", name + ":" + version)
                .stream()
                .findFirst();
          }

          @Override
          public List<PolicyVersion> all() {
            return vPolicies.all().stream()
                .sorted(
                    Comparator.comparing((PolicyVersion p) -> p.name)
                        .thenComparingInt(p -> p.version))
                .toList();
          }

          @Override
          public int maxVersion(String name) {
            return vPolicies.all().stream()
                .filter(p -> p.name.equals(name))
                .mapToInt(p -> p.version)
                .max()
                .orElse(0);
          }

          @Override
          public void activate(UUID id) {
            PolicyVersion target = vPolicies.get(id);
            for (PolicyVersion p : vPolicies.all()) {
              if (p.name.equals(target.name) && p.active && !p.id.equals(id)) {
                p.active = false;
                vPolicies.put(p);
              }
            }
            target.active = true;
            vPolicies.put(target);
          }
        };

    final AgentRepo agentRepo =
        new AgentRepo() {
          @Override
          public void insert(Agent a) {
            vAgents.put(a);
          }

          @Override
          public Optional<Agent> get(UUID id) {
            return Optional.ofNullable(vAgents.get(id));
          }

          @Override
          public Optional<Agent> byName(String name) {
            return vAgents.byIndex("agents_name_key", name).stream().findFirst();
          }

          @Override
          public List<Agent> all() {
            return vAgents.all();
          }
        };
  }
}
