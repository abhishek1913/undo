package dev.undo.server.metrics;

import dev.undo.core.engine.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import org.slf4j.MDC;

/** Engine metrics on Micrometer; Prometheus adds the _total / _seconds suffixes itself. */
public final class MicrometerMetrics implements Metrics {
  private final MeterRegistry registry;
  private static final java.util.Set<String> MDC_KEYS =
      java.util.Set.of("run", "action", "task", "fence");
  private final Tracer tracer;

  public MicrometerMetrics(MeterRegistry registry) {
    this(registry, null);
  }

  /** {@code tracer} is null while tracing is off (UNDO_TRACING=false): spans are then no-ops. */
  public MicrometerMetrics(MeterRegistry registry, Tracer tracer) {
    this.registry = registry;
    this.tracer = tracer;
  }

  @Override
  public void count(String name, String... tags) {
    registry.counter(strip(name, "_total"), clean(tags)).increment();
  }

  @Override
  public void add(String name, double amount, String... tags) {
    registry.counter(strip(name, "_total"), clean(tags)).increment(amount);
  }

  @Override
  public void time(String name, long nanos, String... tags) {
    Timer.builder(strip(name, "_seconds"))
        .tags(clean(tags))
        .publishPercentileHistogram()
        .register(registry)
        .record(Duration.ofNanos(nanos));
  }

  @Override
  public Span span(String name, String... attrs) {
    // Logs carry run/action/task/fence (24.3) for as long as the span is open.
    java.util.Map<String, String> restore = new java.util.HashMap<>();
    for (int i = 0; i + 1 < attrs.length; i += 2) {
      if (MDC_KEYS.contains(attrs[i]) && attrs[i + 1] != null) {
        restore.put(attrs[i], MDC.get(attrs[i]));
        MDC.put(attrs[i], attrs[i + 1]);
      }
    }
    Span inner = tracer == null ? Span.NONE : traced(name, attrs);
    if (restore.isEmpty()) {
      return inner;
    }
    return new Span() {
      @Override
      public void attr(String key, String value) {
        inner.attr(key, value);
      }

      @Override
      public void error(Throwable t) {
        inner.error(t);
      }

      @Override
      public void close() {
        inner.close();
        restore.forEach(
            (k, v) -> {
              if (v == null) {
                MDC.remove(k);
              } else {
                MDC.put(k, v);
              }
            });
      }
    };
  }

  private Span traced(String name, String... attrs) {
    io.micrometer.tracing.Span s = tracer.nextSpan().name(name);
    for (int i = 0; i + 1 < attrs.length; i += 2) {
      if (attrs[i + 1] != null) {
        s.tag(attrs[i], attrs[i + 1]);
      }
    }
    s.start();
    io.micrometer.tracing.Tracer.SpanInScope scope = tracer.withSpan(s);
    return new Span() {
      @Override
      public void attr(String key, String value) {
        if (value != null) {
          s.tag(key, value);
        }
      }

      @Override
      public void error(Throwable t) {
        s.error(t);
      }

      @Override
      public void close() {
        scope.close();
        s.end();
      }
    };
  }

  private static String strip(String name, String suffix) {
    return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
  }

  private static String[] clean(String[] tags) {
    String[] out = tags.clone();
    for (int i = 0; i < out.length; i++) {
      if (out[i] == null) {
        out[i] = "none";
      }
    }
    return out;
  }
}
