package dev.undo.core.port.repo;

import dev.undo.core.model.Attempt;
import dev.undo.core.model.AttemptKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttemptRepo {
  void insert(Attempt attempt);

  /** Writes finished_at, outcome, http_status, latency_ms, detail. */
  void update(Attempt attempt);

  Optional<Attempt> get(UUID actionId, AttemptKind kind, int attemptNo);

  /** The EXECUTE attempt with the highest attempt_no. */
  Optional<Attempt> lastExecute(UUID actionId);

  List<Attempt> byAction(UUID actionId);
}
