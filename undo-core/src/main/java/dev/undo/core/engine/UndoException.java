package dev.undo.core.engine;

/** A request the engine refuses, with the API error code of Section 19.4. */
public final class UndoException extends RuntimeException {
  public enum Code {
    UNKNOWN_TOOL(404),
    INVALID_ARGS(422),
    IDEMPOTENCY_KEY_REUSED(409),
    RUN_NOT_FOUND(404),
    ACTION_NOT_FOUND(404),
    APPROVAL_NOT_FOUND(404),
    PLAN_NOT_FOUND(404),
    FINDING_NOT_FOUND(404),
    POLICY_NOT_FOUND(404),
    RUN_NOT_ACTIVE(409),
    APPROVAL_NOT_PENDING(409),
    APPROVAL_EXPIRED(409),
    APPROVAL_HASH_MISMATCH(409),
    REVERT_IN_PROGRESS(409),
    RECON_RUNNING(409),
    FINDING_NOT_OPEN(409),
    ALREADY_REVERTED(409),
    REVERT_BLOCKED(409),
    REVERT_PREVIEW_EXPIRED(409),
    CONFIRMATION_MISMATCH(409),
    NOT_REVIEWABLE(409),
    INVALID_REQUEST(422),
    POLICY_INVALID(422),
    STALE_FENCE(409),
    FORBIDDEN(403),
    UNAUTHENTICATED(401),
    RATE_LIMITED(429);

    public final int http;

    Code(int http) {
      this.http = http;
    }
  }

  private final Code code;

  public UndoException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
