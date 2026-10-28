package dev.shopfront.core.domain;

import java.time.Instant;

public record Order(
    String id,
    String customerId,
    String itemName,
    long totalMinor,
    String currency,
    String status,
    String carrierStatus,
    Instant placedAt,
    Instant deliveredAt,
    String note,
    int noteVersion,
    String carrierBugOriginal) {

  public Order withNote(String newNote) {
    return new Order(
        id,
        customerId,
        itemName,
        totalMinor,
        currency,
        status,
        carrierStatus,
        placedAt,
        deliveredAt,
        newNote,
        noteVersion + 1,
        carrierBugOriginal);
  }

  public Order withCarrier(String newCarrierStatus, String original) {
    return new Order(
        id,
        customerId,
        itemName,
        totalMinor,
        currency,
        status,
        newCarrierStatus,
        placedAt,
        deliveredAt,
        note,
        noteVersion,
        original);
  }

  public Order withCustomer(String newCustomerId) {
    return new Order(
        id,
        newCustomerId,
        itemName,
        totalMinor,
        currency,
        status,
        carrierStatus,
        placedAt,
        deliveredAt,
        note,
        noteVersion,
        carrierBugOriginal);
  }
}
