package dev.undo.core.model;

public enum AttemptOutcome {
  SUCCEEDED,
  FAILED_DEFINITIVE,
  RETRYABLE_NOT_APPLIED,
  UNKNOWN,
  FOUND,
  NOT_FOUND,
  LOOKUP_UNAVAILABLE,
  STALE_FENCE
}
