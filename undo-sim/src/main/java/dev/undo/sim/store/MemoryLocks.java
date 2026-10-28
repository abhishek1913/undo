package dev.undo.sim.store;

import dev.undo.core.port.Clock;
import dev.undo.core.port.Locks;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Fenced job leases in memory, with the same semantics as JdbcLocks (table job_leases). */
public final class MemoryLocks implements Locks {
  private record Lease(String owner, Instant expiresAt, long fence) {}

  private final Clock clock;
  private final Map<String, Lease> leases = new HashMap<>();

  public MemoryLocks(Clock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized Optional<Long> tryAcquire(String name, String owner, Duration ttl) {
    Instant now = clock.now();
    Lease cur = leases.get(name);
    if (cur != null && cur.expiresAt() != null && !cur.expiresAt().isBefore(now)) {
      return Optional.empty();
    }
    long fence = (cur == null ? 0 : cur.fence()) + 1;
    leases.put(name, new Lease(owner, now.plus(ttl), fence));
    return Optional.of(fence);
  }

  @Override
  public synchronized boolean renew(String name, long fence, Duration ttl) {
    Lease cur = leases.get(name);
    if (cur == null || cur.fence() != fence) {
      return false;
    }
    leases.put(name, new Lease(cur.owner(), clock.now().plus(ttl), fence));
    return true;
  }

  @Override
  public synchronized void release(String name, long fence) {
    Lease cur = leases.get(name);
    if (cur != null && cur.fence() == fence) {
      leases.put(name, new Lease(null, null, fence));
    }
  }
}
