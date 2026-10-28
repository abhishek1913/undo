package dev.undo.core.port;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** The ONLY way core code waits (no Thread.sleep in core). */
public interface Waiter {
  /** Server: Thread.sleep. Simulator: park until the virtual clock reaches now + d. */
  void sleep(Duration d);

  /**
   * Returns when the action may have changed or the deadline passed. Callers re-read the action.
   * Server: Notifier wake-up plus 100 ms polling. Simulator: park.
   */
  void awaitActionChange(UUID actionId, Instant deadline);
}
