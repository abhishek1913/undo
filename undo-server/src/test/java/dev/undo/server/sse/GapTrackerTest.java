package dev.undo.server.sse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undo.core.model.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Section 7.7: deliver up to a gap, wait, and skip it after 2 s (ADR-007). */
class GapTrackerTest {
  static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");

  static Event e(long seq) {
    Event ev = new Event();
    ev.seq = seq;
    return ev;
  }

  @Test
  void deliversInOrderAndStopsAtAGap() {
    GapTracker g = new GapTracker(99, Duration.ofSeconds(2));
    assertThat(g.accept(List.of(e(100), e(101), e(103)), T0))
        .extracting(x -> x.seq)
        .containsExactly(100L, 101L);
    assertThat(g.cursor()).isEqualTo(101);
  }

  @Test
  void aLateCommitFillsTheGap() {
    GapTracker g = new GapTracker(99, Duration.ofSeconds(2));
    g.accept(List.of(e(101)), T0);
    assertThat(g.cursor()).isEqualTo(99);
    assertThat(g.accept(List.of(e(100), e(101)), T0.plusMillis(500)))
        .extracting(x -> x.seq)
        .containsExactly(100L, 101L);
  }

  @Test
  void aGapOlderThanTheTimeoutIsSkipped() {
    GapTracker g = new GapTracker(99, Duration.ofSeconds(2));
    assertThat(g.accept(List.of(e(101), e(102)), T0)).isEmpty();
    assertThat(g.accept(List.of(e(101), e(102)), T0.plusMillis(1999))).isEmpty();
    assertThat(g.accept(List.of(e(101), e(102)), T0.plusSeconds(2)))
        .extracting(x -> x.seq)
        .containsExactly(101L, 102L);
  }
}
