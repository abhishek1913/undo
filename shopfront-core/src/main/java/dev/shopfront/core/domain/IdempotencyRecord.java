package dev.shopfront.core.domain;

import java.time.Instant;

/**
 * The response body is stored as the exact text sent, so replays are byte-identical. responseStatus
 * 0 means the request that owns the key has not finished yet.
 */
public record IdempotencyRecord(
    String key, String fingerprint, int responseStatus, String responseBody, Instant createdAt) {}
