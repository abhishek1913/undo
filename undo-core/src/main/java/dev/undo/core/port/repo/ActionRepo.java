package dev.undo.core.port.repo;

import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ActionTransition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ActionRepo {
  /**
   * Inserts and assigns {@code number}. May throw ConstraintViolationException for
   * actions_agent_call, actions_client_key, actions_dedupe, actions_one_live_compensation.
   */
  Action insert(Action action);

  Optional<Action> get(UUID id);

  Optional<Action> byNumber(long number);

  /** SELECT ... FOR UPDATE. Throws if missing. */
  Action lock(UUID id);

  /** Matches either call key within the run (FR-PROP-2). */
  Optional<Action> byCallKey(UUID runId, String agentCallId, String clientIdempotencyKey);

  Optional<Action> activeByDedupeKey(String dedupeKey);

  /**
   * UPDATE actions SET (all mutable columns) WHERE id = ? AND status = expectedStatus AND
   * lease_fence = expectedFence. Returns false when no row matched.
   */
  boolean update(Action action, ActionStatus expectedStatus, long expectedFence);

  /**
   * Bug flag NO_FENCE_CHECK only: UPDATE ... WHERE id = ? (no condition). {@code writerFence} is
   * the fence the writer holds; the in-memory store uses it to detect stale writes (INV-10).
   */
  void updateUnconditional(Action action, long writerFence);

  /**
   * APPROVED or due RETRY_WAIT actions (next_attempt_at null or <= now) whose PRIMARY run is ACTIVE
   * or COMPLETED, ordered by number, FOR UPDATE OF a SKIP LOCKED.
   */
  List<Action> lockRunnable(Instant now, int limit);

  /** EXECUTING with lease_expires_at < now, FOR UPDATE SKIP LOCKED. */
  List<Action> lockExpiredExecuting(Instant now, int limit);

  /**
   * UNKNOWN with resolve_after <= now and (lease_expires_at null or < now), FOR UPDATE SKIP LOCKED.
   */
  List<Action> lockResolvable(Instant now, int limit);

  /** PRIMARY actions of the run in AWAITING_APPROVAL, APPROVED or RETRY_WAIT, FOR UPDATE. */
  List<Action> lockQueuedPrimary(UUID runId);

  /** All actions of the run ordered by number. */
  List<Action> byRun(UUID runId);

  Map<ActionStatus, Long> countByStatus(UUID runId);

  Optional<Action> liveCompensationOf(UUID targetActionId);

  Optional<Action> byProviderRef(String connector, String providerRef);

  /** Actions of the connector updated in [from, to), plus every UNKNOWN or NEEDS_REVIEW one. */
  List<Action> forRecon(String connector, Instant from, Instant to);

  /** Newest first, any run. */
  List<Action> byStatus(ActionStatus status, int limit);

  /** Counts of all actions by status (overview tiles). */
  Map<ActionStatus, Long> globalCounts();

  /** Money of SUCCEEDED PRIMARY actions completed at or after the time ("money out today"). */
  long moneyOutSince(Instant since);

  /** Number of actions in APPROVED, EXECUTING, RETRY_WAIT or UNKNOWN (runnable work). */
  long countInFlight();

  void addTransition(ActionTransition transition);

  List<ActionTransition> transitions(UUID actionId);
}
