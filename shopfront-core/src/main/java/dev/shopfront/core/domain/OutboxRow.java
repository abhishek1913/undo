package dev.shopfront.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record OutboxRow(
    String id,
    String type,
    JsonNode payload,
    int attempts,
    Instant nextAttemptAt,
    Instant deliveredAt,
    Instant createdAt) {

  public static final int MAX_ATTEMPTS = 10;

  public OutboxRow delivered(Instant at) {
    return new OutboxRow(id, type, payload, attempts + 1, nextAttemptAt, at, createdAt);
  }

  /** Backoff 1, 2, 4 ... 256 s; after MAX_ATTEMPTS the row is no longer due. */
  public OutboxRow failed(Instant now) {
    int n = attempts + 1;
    long delay = 1L << Math.min(n - 1, 8);
    return new OutboxRow(id, type, payload, n, now.plusSeconds(delay), null, createdAt);
  }
}
