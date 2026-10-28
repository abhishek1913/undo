package dev.undo.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undo.core.connector.Consistency;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.IdempotencyClass;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.port.Bug;
import dev.undo.core.port.BugFlags;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Section 9.5, row by row. */
class ResolutionTableTest {
  static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  static RecoveryService service(BugFlags bugs) {
    Ports p =
        new Ports(() -> NOW, null, null, null, null, null, bugs, () -> 0.5, null, Metrics.NONE);
    return new RecoveryService(
        p, new ExecutionService(p, EngineConfig.defaults()), EngineConfig.defaults());
  }

  static ToolSpec tool(IdempotencyClass cls) {
    return new ToolSpec(
        "t_tool",
        "c",
        "d",
        Json.obj(),
        Effect.WRITE,
        Visibility.AGENT,
        cls,
        Consistency.STRONG,
        Duration.ZERO,
        Optional.empty(),
        Optional.empty(),
        false,
        Duration.ofHours(24),
        Optional.empty(),
        true);
  }

  static Action action(IdempotencyClass cls, int attempts, Duration keyAge, Duration unknownAge) {
    Action a = new Action();
    a.idempotencyClass = cls;
    a.attemptCount = attempts;
    a.firstAttemptAt = NOW.minus(keyAge);
    a.unknownSince = NOW.minus(unknownAge);
    return a;
  }

  @ParameterizedTest(name = "{0} frozen={1} attempts={2} keyAge={3} unknownAge={4} -> {5}")
  @CsvSource({
    "NATURAL,false,1,PT1M,PT1M,REEXECUTE",
    "NATURAL,true,1,PT1M,PT1M,LOOKUP",
    "NATIVE_KEY,false,1,PT1M,PT1M,REEXECUTE",
    "NATIVE_KEY,false,1,PT22H59M,PT1M,REEXECUTE",
    "NATIVE_KEY,false,1,PT23H1M,PT1M,LOOKUP",
    "NATIVE_KEY,true,1,PT1M,PT1M,LOOKUP",
    "LOOKUP_BY_REFERENCE,false,1,PT1M,PT1M,LOOKUP",
    "LOOKUP_BY_REFERENCE,true,1,PT1M,PT1M,LOOKUP",
    "NONE,false,1,PT1M,PT1M,REVIEW",
    "NONE,true,1,PT1M,PT1M,REVIEW",
    "NATURAL,false,8,PT1M,PT1M,LOOKUP",
    "NATIVE_KEY,false,8,PT1M,PT1M,LOOKUP",
    "NATIVE_KEY,false,1,PT2H,PT61M,REVIEW",
    "NATURAL,true,1,PT2H,PT61M,REVIEW"
  })
  void decide(
      IdempotencyClass cls,
      boolean frozen,
      int attempts,
      Duration keyAge,
      Duration unknownAge,
      RecoveryService.Strategy expected) {
    RecoveryService r = service(BugFlags.NONE);
    assertThat(r.decide(action(cls, attempts, keyAge, unknownAge), tool(cls), frozen, NOW))
        .isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "RETRY_UNKNOWN_WITHOUT_LOOKUP,LOOKUP_BY_REFERENCE,REEXECUTE",
    "NONE_CLASS_RETRY,NONE,REEXECUTE"
  })
  void bugFlagsChangeTheDecision(Bug bug, IdempotencyClass cls, RecoveryService.Strategy expected) {
    RecoveryService r = service(b -> b == bug);
    assertThat(
            r.decide(
                action(cls, 1, Duration.ofMinutes(1), Duration.ofMinutes(1)),
                tool(cls),
                false,
                NOW))
        .isEqualTo(expected);
  }

  @org.junit.jupiter.api.Test
  void graceForPerClass() {
    ExecutionService e =
        new ExecutionService(
            new Ports(
                () -> NOW,
                null,
                null,
                null,
                null,
                null,
                BugFlags.NONE,
                () -> 0.999,
                null,
                Metrics.NONE),
            EngineConfig.defaults());
    assertThat(e.graceFor(IdempotencyClass.NATURAL, 1, "timeout")).isZero();
    assertThat(e.graceFor(IdempotencyClass.LOOKUP_BY_REFERENCE, 1, "timeout"))
        .isEqualTo(Duration.ofSeconds(60));
    assertThat(e.graceFor(IdempotencyClass.NATIVE_KEY, 3, "timeout"))
        .isBetween(Duration.ofMillis(1900), Duration.ofSeconds(2));
    assertThat(e.graceFor(IdempotencyClass.NATIVE_KEY, 3, "idempotency_request_in_progress"))
        .isEqualTo(Duration.ofSeconds(1));
  }
}
