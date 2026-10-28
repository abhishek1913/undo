package dev.shopfront.core.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.shopfront.core.json.Json;

/** A 4xx answer with the error body format of Section 12.3. */
public final class HttpError extends RuntimeException {
  public final int status;
  public final String code;
  public final JsonNode details;

  public HttpError(int status, String code, String message) {
    this(status, code, message, null);
  }

  public HttpError(int status, String code, String message, JsonNode details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }

  public ObjectNode body() {
    ObjectNode err = Json.obj();
    err.put("code", code);
    err.put("message", getMessage());
    err.set("details", details == null ? Json.obj() : details);
    ObjectNode root = Json.obj();
    root.set("error", err);
    return root;
  }
}
