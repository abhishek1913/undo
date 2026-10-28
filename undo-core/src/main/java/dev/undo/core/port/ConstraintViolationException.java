package dev.undo.core.port;

/**
 * A database constraint stopped a write. Both stores throw this with the constraint name from the
 * DDL (for example {@code actions_dedupe}), so the engine can handle the expected ones.
 */
public final class ConstraintViolationException extends RuntimeException {
  private final String constraint;

  public ConstraintViolationException(String constraint, String message) {
    super(constraint + ": " + message);
    this.constraint = constraint;
  }

  public String constraint() {
    return constraint;
  }
}
