package dev.undo.connector.shopfront;

import java.util.Locale;
import java.util.Map;

public record ShopResponse(int status, Map<String, String> headers, byte[] body) {
  public String header(String name) {
    for (Map.Entry<String, String> e : headers.entrySet()) {
      if (e.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
        return e.getValue();
      }
    }
    return null;
  }
}
