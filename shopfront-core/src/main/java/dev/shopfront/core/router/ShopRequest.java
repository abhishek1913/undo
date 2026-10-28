package dev.shopfront.core.router;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** HTTP-shaped request. {@code path} may include a query string. Header names are lowercase. */
public record ShopRequest(String method, String path, Map<String, String> headers, byte[] body) {
  public ShopRequest {
    TreeMap<String, String> lower = new TreeMap<>();
    if (headers != null) {
      headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
    }
    headers = lower;
    body = body == null ? new byte[0] : body;
  }

  public static ShopRequest of(
      String method, String path, Map<String, String> headers, String body) {
    return new ShopRequest(
        method, path, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
  }

  public String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }
}
