package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Section 24.1: the metrics a propose, a provider call and a model turn leave in
 * /actuator/prometheus.
 */
class ObservabilityIT extends StackTestSupport {
  @Autowired MeterRegistry registry;

  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  @Test
  void aProposeLeavesTheDocumentedMetrics() throws Exception {
    UUID run = newRun(null);
    Resp r =
        propose(
            run,
            "shop_set_order_note",
            "{\"order_id\":\"" + freshOrder() + "\",\"note\":\"observed\"}",
            null);
    assertThat(r.status()).isEqualTo(200);
    // Same registry /actuator/prometheus renders (the test context has no exporter).
    assertThat(registry.find("undo_actions_proposed").counter()).isNotNull();
    assertThat(registry.find("undo_action_outcomes").counter()).isNotNull();
    assertThat(registry.find("undo_action_execution").timer()).isNotNull();
    assertThat(registry.find("undo_action_overhead").timer()).isNotNull();
    assertThat(
            registry
                .find("undo_provider_requests")
                .tags("connector", "shopfront", "http_status", "200")
                .counter())
        .isNotNull();
  }
}
