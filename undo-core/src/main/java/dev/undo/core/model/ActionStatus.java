package dev.undo.core.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Action statuses and the legal transitions of Section 8.2. */
public enum ActionStatus {
  PROPOSED,
  DENIED,
  AWAITING_APPROVAL,
  REJECTED,
  EXPIRED,
  APPROVED,
  EXECUTING,
  RETRY_WAIT,
  UNKNOWN,
  SUCCEEDED,
  FAILED,
  NEEDS_REVIEW,
  CANCELLED;

  private static final Map<ActionStatus, Set<ActionStatus>> LEGAL =
      Map.ofEntries(
          Map.entry(PROPOSED, EnumSet.of(DENIED, AWAITING_APPROVAL, APPROVED)),
          Map.entry(AWAITING_APPROVAL, EnumSet.of(APPROVED, REJECTED, EXPIRED, CANCELLED)),
          Map.entry(APPROVED, EnumSet.of(EXECUTING, CANCELLED)),
          Map.entry(EXECUTING, EnumSet.of(SUCCEEDED, FAILED, NEEDS_REVIEW, RETRY_WAIT, UNKNOWN)),
          Map.entry(RETRY_WAIT, EnumSet.of(EXECUTING, CANCELLED)),
          Map.entry(UNKNOWN, EnumSet.of(EXECUTING, APPROVED, SUCCEEDED, FAILED, NEEDS_REVIEW)),
          Map.entry(NEEDS_REVIEW, EnumSet.of(SUCCEEDED, FAILED)),
          Map.entry(DENIED, EnumSet.noneOf(ActionStatus.class)),
          Map.entry(REJECTED, EnumSet.noneOf(ActionStatus.class)),
          Map.entry(EXPIRED, EnumSet.noneOf(ActionStatus.class)),
          Map.entry(SUCCEEDED, EnumSet.noneOf(ActionStatus.class)),
          Map.entry(FAILED, EnumSet.noneOf(ActionStatus.class)),
          Map.entry(CANCELLED, EnumSet.noneOf(ActionStatus.class)));

  public boolean canMoveTo(ActionStatus to) {
    return LEGAL.get(this).contains(to);
  }

  public static Set<ActionStatus> legalTargets(ActionStatus from) {
    return LEGAL.get(from);
  }

  /** Terminal for the proposal wait (P10) and for the agent: nothing more will happen by itself. */
  public boolean isTerminal() {
    return this == DENIED
        || this == REJECTED
        || this == EXPIRED
        || this == SUCCEEDED
        || this == FAILED
        || this == CANCELLED;
  }

  /** Ended without any effect: the business dedupe key is not active (FR-PROP-4). */
  public boolean endedWithoutEffect() {
    return this == DENIED
        || this == REJECTED
        || this == EXPIRED
        || this == FAILED
        || this == CANCELLED;
  }

  /** Statuses in which the effect may still be happening (revert preview: BLOCKED_IN_FLIGHT). */
  public boolean inFlight() {
    return this == PROPOSED
        || this == AWAITING_APPROVAL
        || this == APPROVED
        || this == EXECUTING
        || this == UNKNOWN
        || this == RETRY_WAIT;
  }
}
