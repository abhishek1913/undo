package dev.undo.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpShopfrontApiTest {
  @Test
  void resourceKeepsTheMetricLabelLowCardinality() {
    assertThat(HttpShopfrontApi.resource("/v1/orders/ORD-0001/refunds?x=1")).isEqualTo("orders");
    assertThat(HttpShopfrontApi.resource("/v1/refunds")).isEqualTo("refunds");
    assertThat(HttpShopfrontApi.resource("/admin")).isEqualTo("admin");
  }
}
