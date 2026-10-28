package dev.undo.core.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.Run;
import dev.undo.core.port.Bug;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.TxContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Human decisions on one action, bound to the SHA-256 of its exact arguments (FR-APR-1..8).
 * Decision code here never reads the advisory note (ArchUnit rule 5).
 *
 * <p>Transactions: approve/reject = one TX each (crash point APPROVAL_AFTER_COMMIT after approve);
 * expireDue = one TX; bulk = one TX per item.
 */
public final class ApprovalService {
  public record Decided(UUID approvalId, UUID actionId, ApprovalStatus status) {}

  public record BulkItem(UUID approvalId, String argsHash) {}

  public record BulkResult(UUID approvalId, boolean ok, String code, String message) {}

  private final Ports p;

  public ApprovalService(Ports p) {
    this.p = p;
  }

  /** The approver sends the args hash they saw; any change → APPROVAL_HASH_MISMATCH. */
  public Decided approve(
      UUID approvalId, String submittedHash, UUID userId, String actor, String note) {
    Decided d = p.store().tx(tx -> approve(tx, approvalId, submittedHash, userId, actor, note));
    p.crash().hit(CrashPoint.APPROVAL_AFTER_COMMIT);
    p.notifier().actionChanged(d.actionId());
    return d;
  }

  private Decided approve(
      TxContext tx, UUID approvalId, String submittedHash, UUID userId, String actor, String note) {
    Instant now = p.clock().now();
    Approval ap = tx.approvals().get(approvalId).orElse(null);
    if (ap == null) {
      throw new UndoException(UndoException.Code.APPROVAL_NOT_FOUND, "No approval " + approvalId);
    }
    ap = tx.approvals().lock(approvalId);
    if (ap.status != ApprovalStatus.PENDING) {
      throw new UndoException(
          UndoException.Code.APPROVAL_NOT_PENDING, "This approval is already " + ap.status + ".");
    }
    if (!now.isBefore(ap.expiresAt)) {
      throw new UndoException(UndoException.Code.APPROVAL_EXPIRED, "This approval expired.");
    }
    if (!p.bugs().on(Bug.APPROVAL_NOT_HASH_BOUND)
        && (submittedHash == null || !submittedHash.equals(ap.argsHash))) {
      throw new UndoException(
          UndoException.Code.APPROVAL_HASH_MISMATCH,
          "The action arguments changed since you viewed them.");
    }
    Action a = tx.actions().lock(ap.actionId);
    if (a.kind == ActionKind.PRIMARY) {
      Run run = tx.runs().get(a.runId).orElseThrow();
      if (!run.status.allowsPrimaryExecution()) {
        throw new UndoException(
            UndoException.Code.RUN_NOT_ACTIVE,
            "The run is " + run.status + "; its actions cannot be approved.");
      }
    }
    ActionStatus prev = a.status;
    long fence = a.leaseFence;
    ap.status = ApprovalStatus.APPROVED;
    ap.approvedArgsHash = submittedHash;
    ap.decidedBy = userId;
    ap.decidedAt = now;
    ap.decisionNote = note;
    tx.approvals().update(ap);
    Transitions.move(tx, now, a, ActionStatus.APPROVED, actor, "approved");
    if (!tx.actions().update(a, prev, fence)) {
      throw new IllegalStateException("locked action changed during approve");
    }
    ObjectNode data = EventLog.actionData(a);
    data.put("approval_id", ap.id.toString());
    data.put("actor", actor);
    EventLog.append(tx, now, a.runId, a.id, "approval.approved", data);
    p.metrics()
        .time(
            "undo_approval_wait_seconds",
            Duration.between(ap.requestedAt, now).toNanos(),
            "decision",
            "approved");
    return new Decided(ap.id, a.id, ap.status);
  }

  public Decided reject(UUID approvalId, UUID userId, String actor, String note) {
    if (note == null || note.isBlank()) {
      throw new UndoException(UndoException.Code.INVALID_REQUEST, "A note is required to reject.");
    }
    Decided d =
        p.store()
            .tx(
                tx -> {
                  Approval ap = tx.approvals().get(approvalId).orElse(null);
                  if (ap == null) {
                    throw new UndoException(
                        UndoException.Code.APPROVAL_NOT_FOUND, "No approval " + approvalId);
                  }
                  ap = tx.approvals().lock(approvalId);
                  if (ap.status != ApprovalStatus.PENDING) {
                    throw new UndoException(
                        UndoException.Code.APPROVAL_NOT_PENDING,
                        "This approval is already " + ap.status + ".");
                  }
                  return close(
                      tx,
                      ap,
                      ApprovalStatus.REJECTED,
                      ActionStatus.REJECTED,
                      userId,
                      actor,
                      note,
                      "approval.rejected");
                });
    p.notifier().actionChanged(d.actionId());
    return d;
  }

  /** PENDING approvals past expires_at → EXPIRED, action EXPIRED, budget released (FR-APR-5). */
  public int expireDue(int limit) {
    List<UUID> changed = new ArrayList<>();
    int n =
        p.store()
            .tx(
                tx -> {
                  List<Approval> due = tx.approvals().lockExpired(p.clock().now(), limit);
                  for (Approval ap : due) {
                    close(
                        tx,
                        ap,
                        ApprovalStatus.EXPIRED,
                        ActionStatus.EXPIRED,
                        null,
                        "system:approval-expiry",
                        "expired",
                        "approval.expired");
                    changed.add(ap.actionId);
                  }
                  return due.size();
                });
    changed.forEach(p.notifier()::actionChanged);
    return n;
  }

  private Decided close(
      TxContext tx,
      Approval ap,
      ApprovalStatus apStatus,
      ActionStatus actionStatus,
      UUID userId,
      String actor,
      String note,
      String event) {
    Instant now = p.clock().now();
    Action a = tx.actions().lock(ap.actionId);
    ActionStatus prev = a.status;
    long fence = a.leaseFence;
    ap.status = apStatus;
    ap.decidedBy = userId;
    ap.decidedAt = now;
    ap.decisionNote = note;
    tx.approvals().update(ap);
    if (prev == ActionStatus.AWAITING_APPROVAL) {
      Transitions.move(tx, now, a, actionStatus, actor, apStatus.name().toLowerCase());
      a.completedAt = now;
      a.dedupeActive = false;
      Budget.releaseIfReserved(tx, a);
      if (!tx.actions().update(a, prev, fence)) {
        throw new IllegalStateException("locked action changed during approval close");
      }
    }
    ObjectNode data = EventLog.actionData(a);
    data.put("approval_id", ap.id.toString());
    data.put("actor", actor);
    if (note != null) {
      data.put("note", note);
    }
    EventLog.append(tx, now, a.runId, a.id, event, data);
    if (ap.requestedAt != null) {
      p.metrics()
          .time(
              "undo_approval_wait_seconds",
              Duration.between(ap.requestedAt, now).toNanos(),
              "decision",
              apStatus.name().toLowerCase());
    }
    return new Decided(ap.id, a.id, ap.status);
  }

  /** FR-APR-8: each item is checked and applied independently in its own TX. */
  public List<BulkResult> bulk(
      boolean approve, List<BulkItem> items, UUID userId, String actor, String note) {
    List<BulkResult> out = new ArrayList<>();
    for (BulkItem it : items) {
      try {
        if (approve) {
          approve(it.approvalId(), it.argsHash(), userId, actor, note);
        } else {
          Approval ap = p.store().tx(tx -> tx.approvals().get(it.approvalId())).orElse(null);
          if (ap != null && it.argsHash() != null && !it.argsHash().equals(ap.argsHash)) {
            throw new UndoException(
                UndoException.Code.APPROVAL_HASH_MISMATCH,
                "The action arguments changed since you viewed them.");
          }
          reject(
              it.approvalId(),
              userId,
              actor,
              note == null || note.isBlank() ? "rejected in bulk" : note);
        }
        out.add(new BulkResult(it.approvalId(), true, null, null));
      } catch (UndoException e) {
        out.add(new BulkResult(it.approvalId(), false, e.code().name(), e.getMessage()));
      }
    }
    return out;
  }
}
