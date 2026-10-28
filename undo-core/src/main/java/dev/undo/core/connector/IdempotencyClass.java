package dev.undo.core.connector;

public enum IdempotencyClass {
  NATURAL,
  NATIVE_KEY,
  LOOKUP_BY_REFERENCE,
  NONE
}
