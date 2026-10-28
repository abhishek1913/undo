package dev.undo.core.port;

/** Backoff jitter. Seeded in the simulator. */
public interface Entropy {
  /** A value in [0, 1). */
  double nextDouble();
}
