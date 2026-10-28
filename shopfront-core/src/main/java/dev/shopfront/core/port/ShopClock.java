package dev.shopfront.core.port;

import java.time.Instant;

public interface ShopClock {
  Instant now();
}
