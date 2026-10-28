package dev.undo.server.config;

import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.CrashPoints;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chaos profile: each crash point hit halts the JVM with the configured probability (FR-DEMO-2).
 */
public final class ChaosCrashPoints implements CrashPoints {
  private static final Logger log = LoggerFactory.getLogger(ChaosCrashPoints.class);
  private final double probability;

  public ChaosCrashPoints(double probability) {
    this.probability = probability;
  }

  @Override
  public void hit(CrashPoint point) {
    if (probability > 0 && ThreadLocalRandom.current().nextDouble() < probability) {
      log.warn("chaos: halting at crash point {}", point);
      System.out.flush();
      Runtime.getRuntime().halt(137);
    }
  }
}
