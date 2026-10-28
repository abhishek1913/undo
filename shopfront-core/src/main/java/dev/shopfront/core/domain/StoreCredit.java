package dev.shopfront.core.domain;

import java.time.Instant;

public record StoreCredit(
    String id,
    String customerId,
    long amountMinor,
    long balanceMinor,
    String reason,
    String reference,
    String status,
    long revokedMinor,
    Instant createdAt,
    Instant updatedAt) {

  public StoreCredit revoked(Instant at) {
    return new StoreCredit(
        id, customerId, amountMinor, 0, reason, reference, "revoked", balanceMinor, createdAt, at);
  }

  public StoreCredit spent(long amount, Instant at) {
    return new StoreCredit(
        id,
        customerId,
        amountMinor,
        balanceMinor - amount,
        reason,
        reference,
        status,
        revokedMinor,
        createdAt,
        at);
  }
}
