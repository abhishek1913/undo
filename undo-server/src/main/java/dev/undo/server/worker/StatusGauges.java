package dev.undo.server.worker;

import dev.undo.core.model.ActionStatus;
import dev.undo.core.port.Store;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Refreshes {@code undo_actions_by_status{status}} and {@code undo_approvals_pending} every 10 s
 * (24.1). An action in AWAITING_APPROVAL is exactly one pending approval.
 */
@Component
public class StatusGauges implements WorkerLoops.Extra {
  private static final Logger log = LoggerFactory.getLogger(StatusGauges.class);
  private final Store store;
  private final MultiGauge byStatus;
  private final AtomicLong pending = new AtomicLong();

  public StatusGauges(Store store, MeterRegistry meters) {
    this.store = store;
    this.byStatus =
        MultiGauge.builder("undo_actions_by_status")
            .description("Actions per status (all runs)")
            .register(meters);
    meters.gauge("undo_approvals_pending", pending);
  }

  @Override
  public String name() {
    return "gauges";
  }

  @Override
  public Duration interval() {
    return Duration.ofSeconds(10);
  }

  @Override
  public int round() {
    try {
      Map<ActionStatus, Long> counts = store.tx(tx -> tx.actions().globalCounts());
      byStatus.register(
          java.util.Arrays.stream(ActionStatus.values())
              .map(
                  s ->
                      MultiGauge.Row.of(
                          Tags.of("status", s.name()), counts.getOrDefault(s, 0L).doubleValue()))
              .toList(),
          true);
      pending.set(counts.getOrDefault(ActionStatus.AWAITING_APPROVAL, 0L));
    } catch (RuntimeException e) {
      log.warn("gauge refresh failed: {}", e.toString());
    }
    return 0;
  }
}
