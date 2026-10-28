package dev.shopfront.core.domain;

import java.time.Instant;

/** {@code category} is hidden ground truth and is never returned by /v1. */
public record Ticket(
    String id,
    String customerId,
    String orderId,
    String category,
    String subject,
    String body,
    String status,
    String resolution,
    Instant createdAt,
    Instant closedAt) {

  public Ticket closed(String newResolution, Instant at) {
    return new Ticket(
        id, customerId, orderId, category, subject, body, "CLOSED", newResolution, createdAt, at);
  }

  public Ticket reopened() {
    return new Ticket(
        id, customerId, orderId, category, subject, body, "OPEN", null, createdAt, null);
  }
}
