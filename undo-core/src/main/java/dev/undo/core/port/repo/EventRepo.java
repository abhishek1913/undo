package dev.undo.core.port.repo;

import dev.undo.core.model.Event;
import java.util.List;
import java.util.UUID;

public interface EventRepo {
  /** Assigns seq (identity) and, for events of a run, the audit chain hashes. */
  Event append(Event event);

  /** The highest committed seq, 0 when empty. */
  long maxSeq();

  /** Events with seq > afterSeq ordered by seq; runId null = all runs. */
  List<Event> after(long afterSeq, UUID runId, int limit);
}
