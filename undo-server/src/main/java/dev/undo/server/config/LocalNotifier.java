package dev.undo.server.config;

import dev.undo.core.port.Clock;
import dev.undo.core.port.Notifier;
import dev.undo.core.port.Waiter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wakes proposal calls waiting on an action changed by this node. Changes made by other nodes are
 * seen through 100 ms polling (the caller re-reads the action after every wake-up).
 */
public final class LocalNotifier implements Notifier, Waiter {
  private final ConcurrentHashMap<UUID, CompletableFuture<Void>> waiting =
      new ConcurrentHashMap<>();
  private final Clock clock;

  public LocalNotifier(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void actionChanged(UUID actionId) {
    CompletableFuture<Void> f = waiting.remove(actionId);
    if (f != null) {
      f.complete(null);
    }
  }

  @Override
  public void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void awaitActionChange(UUID actionId, Instant deadline) {
    long ms = Math.min(100, Math.max(1, Duration.between(clock.now(), deadline).toMillis()));
    CompletableFuture<Void> f = waiting.computeIfAbsent(actionId, k -> new CompletableFuture<>());
    try {
      f.get(ms, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      // poll again
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
