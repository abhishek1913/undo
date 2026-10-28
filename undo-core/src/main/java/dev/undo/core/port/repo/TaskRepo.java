package dev.undo.core.port.repo;

import dev.undo.core.model.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepo {
  void insert(Task task);

  Optional<Task> get(UUID id);

  /** Ordered by seq. */
  List<Task> byRun(UUID runId);

  void update(Task task);
}
