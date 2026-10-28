package dev.undo.core.port.repo;

import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepo {
  void insert(Approval approval);

  Optional<Approval> get(UUID id);

  Optional<Approval> byAction(UUID actionId);

  /** SELECT ... FOR UPDATE. Throws if missing. */
  Approval lock(UUID id);

  /** Caller holds the lock. */
  void update(Approval approval);

  /** PENDING with expires_at <= now, FOR UPDATE SKIP LOCKED. */
  List<Approval> lockExpired(Instant now, int limit);

  /** Newest first. Null filters are ignored. */
  List<Approval> list(ApprovalStatus status, UUID runId, int limit);

  /** PENDING approvals without an advisory note, oldest first. */
  List<Approval> missingAdvisory(int limit);

  /** Writes only advisory_note (the advisory job never touches decision columns). */
  void setAdvisory(Approval approval);
}
