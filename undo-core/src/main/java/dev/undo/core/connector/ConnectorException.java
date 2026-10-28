package dev.undo.core.connector;

/** A read-only provider call (facts, current state, listings) failed. */
public class ConnectorException extends Exception {
  public ConnectorException(String message) {
    super(message);
  }

  public ConnectorException(String message, Throwable cause) {
    super(message, cause);
  }
}
