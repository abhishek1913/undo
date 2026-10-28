package dev.shopfront.core.domain;

import java.time.Instant;

public record Customer(String id, String name, String email, Instant createdAt) {}
