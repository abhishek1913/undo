package dev.shopfront.core.router;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public record ShopResponse(int status, Map<String, String> headers, byte[] body) {
  public String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }
}
