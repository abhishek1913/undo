package dev.undo.core.engine;

/** Metrics and trace spans (Section 24); the server adapts them to Micrometer. */
public interface Metrics {
  void count(String name, String... tags);

  void time(String name, long nanos, String... tags);

  /** Adds {@code amount} to a counter (token counts). */
  default void add(String name, double amount, String... tags) {}

  /** Starts a span (24.2); close it when the work ends. Attributes are key/value pairs. */
  default Span span(String name, String... attrs) {
    return Span.NONE;
  }

  /** An open trace span. */
  interface Span extends AutoCloseable {
    Span NONE =
        new Span() {
          @Override
          public void attr(String key, String value) {}

          @Override
          public void error(Throwable t) {}

          @Override
          public void close() {}
        };

    void attr(String key, String value);

    void error(Throwable t);

    @Override
    void close();
  }

  Metrics NONE =
      new Metrics() {
        @Override
        public void count(String name, String... tags) {}

        @Override
        public void time(String name, long nanos, String... tags) {}
      };
}
