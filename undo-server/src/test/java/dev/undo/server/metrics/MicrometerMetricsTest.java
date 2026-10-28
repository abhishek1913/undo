package dev.undo.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.undo.core.engine.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MicrometerMetricsTest {
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void addCountsTokensUnderThePrometheusName() {
    MicrometerMetrics m = new MicrometerMetrics(registry);
    m.add("undo_llm_tokens_total", 1200, "model", "scripted", "kind", "input");
    m.add("undo_llm_tokens_total", 300, "model", "scripted", "kind", "input");
    assertThat(
            registry
                .get("undo_llm_tokens")
                .tags("model", "scripted", "kind", "input")
                .counter()
                .count())
        .isEqualTo(1500);
  }

  @Test
  void timeRecordsNanosUnderTheSecondsName() {
    MicrometerMetrics m = new MicrometerMetrics(registry);
    m.time("undo_llm_turn_seconds", 2_000_000_000L, "model", "scripted", "stop_reason", null);
    assertThat(
            registry
                .get("undo_llm_turn")
                .tags("stop_reason", "none")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(2.0);
  }

  @Test
  void spanPutsRunActionAndFenceInTheLogContextUntilItCloses() {
    MicrometerMetrics m = new MicrometerMetrics(registry);
    MDC.put("action", "outer");
    try (Metrics.Span sp = m.span("undo.execute", "action", "A-1", "fence", "7", "tool", "x")) {
      assertThat(MDC.get("action")).isEqualTo("A-1");
      assertThat(MDC.get("fence")).isEqualTo("7");
      assertThat(MDC.get("tool")).isNull(); // only the keys of 24.3
    }
    assertThat(MDC.get("action")).isEqualTo("outer");
    assertThat(MDC.get("fence")).isNull();
    MDC.clear();
  }

  @Test
  void spanIsRecordedWithItsAttributesWhenATracerIsPresent() {
    SimpleTracer tracer = new SimpleTracer();
    MicrometerMetrics m = new MicrometerMetrics(registry, (Tracer) tracer);
    try (Metrics.Span sp = m.span("undo.propose", "tool", "shop_set_order_note")) {
      sp.attr("outcome", "Recorded");
    }
    var span = tracer.onlySpan();
    assertThat(span.getName()).isEqualTo("undo.propose");
    assertThat(span.getTags())
        .containsEntry("tool", "shop_set_order_note")
        .containsEntry("outcome", "Recorded");
  }
}
