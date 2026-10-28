package dev.undo.core.port;

import java.time.Duration;
import java.util.Optional;

/** Fenced job leases stored in table job_leases. */
public interface Locks {
  /** Returns the fence if acquired. */
  Optional<Long> tryAcquire(String name, String owner, Duration ttl);

  boolean renew(String name, long fence, Duration ttl);

  void release(String name, long fence);
}
