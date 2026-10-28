package dev.undo.core.engine;

import java.time.Duration;

/** Engine timing and limits (Section 26). */
public record EngineConfig(
    Duration lease,
    Duration providerTimeout,
    int maxAttempts,
    Duration backoffBase,
    Duration backoffCap,
    Duration lookupGrace,
    Duration maxUnknownAge,
    Duration nativeKeySafetyMargin,
    Duration factsTimeout,
    Duration defaultApprovalExpiry,
    Duration previewTtl,
    Duration runLease,
    Duration idleTimeout,
    boolean inline,
    long defaultWaitMs,
    long maxWaitMs) {

  public static EngineConfig defaults() {
    return new EngineConfig(
        Duration.ofSeconds(30),
        Duration.ofSeconds(10),
        8,
        Duration.ofMillis(500),
        Duration.ofSeconds(30),
        Duration.ofSeconds(60),
        Duration.ofHours(1),
        Duration.ofHours(1),
        Duration.ofSeconds(3),
        Duration.ofHours(24),
        Duration.ofMinutes(15),
        Duration.ofSeconds(60),
        Duration.ofMinutes(30),
        true,
        10_000,
        30_000);
  }

  public EngineConfig withInline(boolean value) {
    return new EngineConfig(
        lease,
        providerTimeout,
        maxAttempts,
        backoffBase,
        backoffCap,
        lookupGrace,
        maxUnknownAge,
        nativeKeySafetyMargin,
        factsTimeout,
        defaultApprovalExpiry,
        previewTtl,
        runLease,
        idleTimeout,
        value,
        defaultWaitMs,
        maxWaitMs);
  }

  public EngineConfig withMaxUnknownAge(Duration value) {
    return new EngineConfig(
        lease,
        providerTimeout,
        maxAttempts,
        backoffBase,
        backoffCap,
        lookupGrace,
        value,
        nativeKeySafetyMargin,
        factsTimeout,
        defaultApprovalExpiry,
        previewTtl,
        runLease,
        idleTimeout,
        inline,
        defaultWaitMs,
        maxWaitMs);
  }
}
