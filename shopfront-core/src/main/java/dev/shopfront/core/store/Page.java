package dev.shopfront.core.store;

import java.util.List;

public record Page<T>(List<T> items, String nextCursor) {}
