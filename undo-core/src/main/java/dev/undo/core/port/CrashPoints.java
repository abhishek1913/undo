package dev.undo.core.port;

/**
 * No-op in production. Simulator: may throw SimulatedCrash. Chaos profile: may Runtime.halt(137).
 */
public interface CrashPoints {
  void hit(CrashPoint point);

  CrashPoints NONE = point -> {};
}
