package dev.undo.connector.shopfront;

public final class ShopTransportException extends Exception {
  /** CONNECT_FAILED = the request was never sent. */
  public enum Kind {
    CONNECT_FAILED,
    TIMEOUT,
    RESET
  }

  private final Kind kind;

  public ShopTransportException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
