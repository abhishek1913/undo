package dev.undo.server.sse;

import dev.undo.core.model.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The outbox gap rule (Section 7.7, ADR-007). Sequence numbers come from an identity column, and
 * transactions can commit out of order: seq 101 may be visible before seq 100. Deliver everything
 * up to the first gap, then wait; if the gap is still there after the timeout, treat the missing
 * seq as rolled back and move on.
 */
public final class GapTracker {
  private final Duration timeout;
  private long cursor;
  private long gapAt = -1;
  private Instant gapSince;

  public GapTracker(long cursor, Duration timeout) {
    this.cursor = cursor;
    this.timeout = timeout;
  }

  public long cursor() {
    return cursor;
  }

  /**
   * @param batch events with seq > cursor of all runs, ordered by seq (gaps are judged on the
   *     global seq; a per-run stream filters what this returns)
   * @return the events to deliver now, in order
   */
  public List<Event> accept(List<Event> batch, Instant now) {
    List<Event> out = new ArrayList<>();
    for (Event e : batch) {
      if (e.seq <= cursor) {
        continue;
      }
      if (e.seq == cursor + 1) {
        out.add(e);
        cursor = e.seq;
        gapAt = -1;
        continue;
      }
      // a gap before e.seq
      if (gapAt != cursor + 1) {
        gapAt = cursor + 1;
        gapSince = now;
        break;
      }
      if (Duration.between(gapSince, now).compareTo(timeout) < 0) {
        break; // still waiting for the slow transaction
      }
      // the missing seq never committed: skip over it
      out.add(e);
      cursor = e.seq;
      gapAt = -1;
    }
    return out;
  }
}
