package dev.undo.core.port.repo;

import dev.undo.core.model.LlmTurn;
import java.util.List;
import java.util.UUID;

public interface TurnRepo {
  /** Append-only by (task_id, turn_no); only tool_results is ever set later. */
  void insert(LlmTurn turn);

  /** Ordered by turn_no. */
  List<LlmTurn> byTask(UUID taskId);

  /** Sets tool_results (null until answered). */
  void setToolResults(LlmTurn turn);
}
