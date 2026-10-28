package dev.undo.core.port.repo;

import dev.undo.core.model.RevertItem;
import dev.undo.core.model.RevertPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RevertRepo {
  /** Assigns number. revert_one_open_per_run is enforced. */
  RevertPlan insertPlan(RevertPlan plan);

  Optional<RevertPlan> plan(UUID id);

  Optional<RevertPlan> planByNumber(long number);

  /** The PREVIEW or EXECUTING plan of the run, if any. */
  Optional<RevertPlan> openForRun(UUID runId);

  List<RevertPlan> byRun(UUID runId);

  /** SELECT ... FOR UPDATE. */
  RevertPlan lockPlan(UUID id);

  void updatePlan(RevertPlan plan);

  /** EXECUTING plans whose lease is free or expired, FOR UPDATE SKIP LOCKED. */
  List<RevertPlan> lockExecutable(Instant now, int limit);

  /** PREVIEW plans created before the cutoff, FOR UPDATE SKIP LOCKED. */
  List<RevertPlan> lockExpiredPreviews(Instant createdBefore, int limit);

  void insertItem(RevertItem item);

  void updateItem(RevertItem item);

  /** Ordered by order_no. */
  List<RevertItem> items(UUID planId);
}
