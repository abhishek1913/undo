package dev.undo.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MoneyAndBackoffTest {
  @Test
  void indianMoneyFormat() {
    assertThat(Money.format(7_800_000)).isEqualTo("₹78,000");
    assertThat(Money.format(12_345_600)).isEqualTo("₹1,23,456");
    assertThat(Money.format(123_450)).isEqualTo("₹1,234.50");
    assertThat(Money.format(5)).isEqualTo("₹0.05");
  }

  @Test
  void backoffIsFullJitterBoundedByCap() {
    Duration base = Duration.ofMillis(500);
    Duration cap = Duration.ofSeconds(30);
    assertThat(Backoff.of(1, base, cap, () -> 0.999)).isLessThan(Duration.ofMillis(500));
    assertThat(Backoff.of(3, base, cap, () -> 0.999))
        .isBetween(Duration.ofMillis(1900), Duration.ofMillis(2000));
    assertThat(Backoff.of(20, base, cap, () -> 0.999)).isLessThanOrEqualTo(cap);
    assertThat(Backoff.of(5, base, cap, () -> 0.0)).isEqualTo(Duration.ZERO);
  }
}
