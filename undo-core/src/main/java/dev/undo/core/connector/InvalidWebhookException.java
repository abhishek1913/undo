package dev.undo.core.connector;

public class InvalidWebhookException extends Exception {
  public InvalidWebhookException(String message) {
    super(message);
  }
}
