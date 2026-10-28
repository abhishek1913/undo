package dev.undo.core.engine;

import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ActionTransition;
import dev.undo.core.port.TxContext;
import java.time.Instant;

/**
 * Moves an action to a new status in memory and records the transition row and event (Section 8.2).
 * The caller writes the action itself with a conditional update in the same TX.
 */
public final class Transitions {
  private Transitions() {}

  public static void move(
      TxContext tx, Instant now, Action a, ActionStatus to, String actor, String reason) {
    ActionStatus from = a.status;
    if (from != null && !from.canMoveTo(to)) {
      throw new IllegalStateException(
          "illegal action transition " + from + " -> " + to + " for " + a.display());
    }
    a.status = to;
    if (reason != null) {
      a.statusReason = reason;
    }
    a.updatedAt = now;
    record(tx, now, a, from, to, actor, reason);
  }

  /**
   * Writes the transition row and event for a change already applied to {@code a} (used when the
   * row is inserted with its final status, as on the proposal path).
   */
  public static void record(
      TxContext tx,
      Instant now,
      Action a,
      ActionStatus from,
      ActionStatus to,
      String actor,
      String reason) {
    if (from != null && !from.canMoveTo(to)) {
      throw new IllegalStateException(
          "illegal action transition " + from + " -> " + to + " for " + a.display());
    }
    ActionTransition t = new ActionTransition();
    t.actionId = a.id;
    t.fromStatus = from;
    t.toStatus = to;
    t.fence = a.leaseFence;
    t.actor = actor;
    t.reason = reason;
    t.at = now;
    tx.actions().addTransition(t);
    String type = eventType(from, to);
    if (type != null) {
      com.fasterxml.jackson.databind.node.ObjectNode d = EventLog.actionData(a);
      d.put("status", to.name());
      if (from != null) {
        d.put("from", from.name());
      }
      EventLog.append(tx, now, a.runId, a.id, type, d);
    }
  }

  static String eventType(ActionStatus from, ActionStatus to) {
    if (from == ActionStatus.PROPOSED) {
      return "action.decided";
    }
    return switch (to) {
      case PROPOSED -> "action.proposed";
      case EXECUTING -> "action.executing";
      case RETRY_WAIT -> "action.retry_wait";
      case UNKNOWN -> "action.unknown";
      case SUCCEEDED -> "action.succeeded";
      case FAILED -> "action.failed";
      case NEEDS_REVIEW -> "action.needs_review";
      case CANCELLED -> "action.cancelled";
      case APPROVED ->
          from == ActionStatus.UNKNOWN ? "action.retry_wait" : null; // approvals emit their own
      default -> null; // REJECTED, EXPIRED: approval.* events carry them
    };
  }
}
