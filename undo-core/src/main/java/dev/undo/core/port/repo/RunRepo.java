package dev.undo.core.port.repo;

import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.model.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepo {
  /** Inserts the run and assigns {@code number} (identity). */
  Run insert(Run run);

  Optional<Run> get(UUID id);

  Optional<Run> byNumber(long number);

  Optional<Run> byMcpSession(String sessionId);

  /** SELECT ... FOR UPDATE. Throws if the run does not exist. */
  Run lock(UUID id);

  /**
   * Writes every mutable column except the budget columns. The caller holds the row lock. Budget
   * columns change only through {@link #reserve}, {@link #release}, {@link #settle}, {@link
   * #addTokens}.
   */
  void update(Run run);

  /**
   * UPDATE runs SET money_reserved += money, action_count += actions WHERE id = ? AND
   * money_reserved + money_spent + money <= money_cap AND action_count + actions <= action_cap.
   */
  boolean reserve(UUID id, long money, int actions);

  /** UPDATE runs SET money_reserved -= money, action_count -= actions. */
  void release(UUID id, long money, int actions);

  /** UPDATE runs SET money_reserved -= money, money_spent += money. */
  void settle(UUID id, long money);

  /** Bug flag BUDGET_CHECK_THEN_ACT only: unconditional reservation. */
  void reserveUnchecked(UUID id, long money, int actions);

  void addTokens(UUID id, long tokens);

  /**
   * Claims one ACTIVE hosted run whose lease is free or expired (FOR UPDATE SKIP LOCKED, ordered by
   * number): sets lease_owner, lease_expires_at and lease_fence + 1.
   */
  Optional<Run> claimHosted(String owner, Instant now, Instant leaseUntil);

  /** UPDATE runs SET lease_expires_at = ? WHERE id = ? AND lease_fence = ?. */
  boolean heartbeat(UUID id, long fence, Instant leaseUntil);

  /** ACTIVE EXTERNAL runs with last_activity_at before the cutoff, FOR UPDATE SKIP LOCKED. */
  List<Run> lockIdleExternal(Instant idleBefore, int limit);

  /** Newest first. Null filters are ignored; beforeNumber is a cursor (exclusive). */
  List<Run> list(RunStatus status, RunKind kind, Long beforeNumber, int limit);
}
