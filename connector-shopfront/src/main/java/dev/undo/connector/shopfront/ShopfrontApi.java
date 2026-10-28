package dev.undo.connector.shopfront;

/** HTTP-shaped transport so classification logic is identical in server and simulator. */
public interface ShopfrontApi {
  /** Request header with a per-request timeout hint in ms; transports honor and strip it. */
  String TIMEOUT_HEADER = "X-Undo-Timeout-Ms";

  ShopResponse send(ShopRequest request) throws ShopTransportException;
}
