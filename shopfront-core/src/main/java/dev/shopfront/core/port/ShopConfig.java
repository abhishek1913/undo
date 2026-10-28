package dev.shopfront.core.port;

import java.time.Duration;

/** Shopfront settings (Section 26, Shopfront env). */
public record ShopConfig(
    String apiKey,
    String adminToken,
    String webhookSecret,
    Duration settlementDelay,
    Duration exportLag,
    Duration idempotencyTtl) {

  public static ShopConfig defaults(String apiKey, String adminToken, String webhookSecret) {
    return new ShopConfig(
        apiKey,
        adminToken,
        webhookSecret,
        Duration.ofMinutes(10),
        Duration.ofMinutes(15),
        Duration.ofHours(24));
  }
}
