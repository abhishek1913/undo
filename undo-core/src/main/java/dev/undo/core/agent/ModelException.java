package dev.undo.core.agent;

/** A model call failed after the client's own retries. */
public class ModelException extends Exception {
  public enum Kind {
    RATE_LIMITED,
    SERVER_ERROR,
    CONNECTION,
    BAD_REQUEST,
    OTHER
  }

  private final Kind kind;

  public ModelException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /** Worth retrying with backoff. */
  public boolean transientFailure() {
    return kind == Kind.RATE_LIMITED || kind == Kind.SERVER_ERROR || kind == Kind.CONNECTION;
  }
}
