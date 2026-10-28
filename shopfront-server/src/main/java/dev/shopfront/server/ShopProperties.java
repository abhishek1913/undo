package dev.shopfront.server;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shop")
public record ShopProperties(
    String apiKey,
    String adminToken,
    String webhookUrl,
    String webhookSecret,
    Duration settlementDelay,
    Duration exportLag,
    Duration idempotencyTtl,
    boolean jobsEnabled,
    boolean pageEnabled) {}
