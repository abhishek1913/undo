package dev.undo.server.it;

import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.CrashPoints;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test crash points: armed once per point, they either throw (the thread "dies" after this point,
 * like a crashed API process) or stall the thread (a zombie that wakes up after its lease ended).
 */
public final class TestCrashPoints implements CrashPoints {
  /** Never caught by engine code, like SimulatedCrash. */
  public static final class TestCrash extends Error {
    TestCrash(String m) {
      super(m);
    }
  }

  private final Map<CrashPoint, Long> armed = new ConcurrentHashMap<>();

  /** stallMs = 0: throw; > 0: sleep that long, then continue. */
  public void arm(CrashPoint p, long stallMs) {
    armed.put(p, stallMs);
  }

  public void reset() {
    armed.clear();
  }

  @Override
  public void hit(CrashPoint point) {
    Long ms = armed.remove(point);
    if (ms == null) {
      return;
    }
    if (ms == 0) {
      throw new TestCrash("crash at " + point);
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
