package dev.undo.core.engine;

import dev.undo.core.port.Entropy;
import java.time.Duration;

/** Exponential backoff with full jitter (FR-EXEC-7): uniform(0, min(cap, base * 2^(n-1))). */
public final class Backoff {
  private Backoff() {}

  public static Duration of(int attempt, Duration base, Duration cap, Entropy entropy) {
    int n = Math.max(1, attempt);
    long ceiling = base.toMillis() << Math.min(n - 1, 20);
    ceiling = Math.min(ceiling, cap.toMillis());
    return Duration.ofMillis((long) (entropy.nextDouble() * ceiling));
  }
}
