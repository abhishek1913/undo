package dev.undo.core.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Run statuses and the legal transitions of Section 8.1. */
public enum RunStatus {
  ACTIVE,
  COMPLETED,
  FROZEN,
  FAILED,
  REVERTING,
  REVERTED,
  PARTIALLY_REVERTED;

  private static final Map<RunStatus, Set<RunStatus>> LEGAL =
      Map.of(
          ACTIVE, EnumSet.of(COMPLETED, FROZEN, FAILED),
          COMPLETED, EnumSet.of(REVERTING),
          FROZEN, EnumSet.of(REVERTING),
          FAILED, EnumSet.of(REVERTING),
          REVERTING, EnumSet.of(REVERTED, PARTIALLY_REVERTED),
          REVERTED, EnumSet.noneOf(RunStatus.class),
          PARTIALLY_REVERTED, EnumSet.noneOf(RunStatus.class));

  public boolean canMoveTo(RunStatus to) {
    return LEGAL.get(this).contains(to);
  }

  /** PRIMARY actions may execute only while the run is ACTIVE or COMPLETED. */
  public boolean allowsPrimaryExecution() {
    return this == ACTIVE || this == COMPLETED;
  }
}
