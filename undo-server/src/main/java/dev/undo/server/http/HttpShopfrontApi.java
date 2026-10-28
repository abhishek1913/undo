package dev.undo.server.http;

import dev.undo.connector.shopfront.ShopRequest;
import dev.undo.connector.shopfront.ShopResponse;
import dev.undo.connector.shopfront.ShopTransportException;
import dev.undo.connector.shopfront.ShopfrontApi;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ShopfrontApi over java.net.http (connect timeout 2 s, request timeout 10 s or the request's
 * X-Undo-Timeout-Ms). ConnectException → CONNECT_FAILED (never sent), HttpTimeoutException →
 * TIMEOUT, any other IOException → RESET.
 */
public final class HttpShopfrontApi implements ShopfrontApi {
  private static final java.util.Set<String> RESTRICTED =
      java.util.Set.of("connection", "content-length", "host", "expect", "upgrade");
  private final HttpClient http;
  private final String baseUrl;
  private final Duration timeout;
  private final dev.undo.core.engine.Metrics metrics;

  public HttpShopfrontApi(String baseUrl, Duration timeout) {
    this(baseUrl, timeout, dev.undo.core.engine.Metrics.NONE);
  }

  public HttpShopfrontApi(String baseUrl, Duration timeout, dev.undo.core.engine.Metrics metrics) {
    this.metrics = metrics;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.timeout = timeout;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  @Override
  public ShopResponse send(ShopRequest r) throws ShopTransportException {
    String op = r.method() + " " + resource(r.path());
    try {
      ShopResponse resp = send0(r);
      metrics.count(
          "undo_provider_requests_total",
          "connector",
          "shopfront",
          "op",
          op,
          "http_status",
          Integer.toString(resp.status()));
      return resp;
    } catch (ShopTransportException e) {
      metrics.count(
          "undo_provider_requests_total",
          "connector",
          "shopfront",
          "op",
          op,
          "http_status",
          e.kind().name().toLowerCase());
      throw e;
    }
  }

  /**
   * {@code /v1/orders/ORD-1/refunds?x=1} becomes {@code orders}: the label stays low-cardinality.
   */
  static String resource(String path) {
    String[] seg = path.split("\\?")[0].replaceAll("^/+", "").split("/");
    return seg.length > 1 ? seg[1] : seg[0];
  }

  private ShopResponse send0(ShopRequest r) throws ShopTransportException {
    Duration t = timeout;
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + r.path()));
    for (Map.Entry<String, String> h : r.headers().entrySet()) {
      if (h.getKey().equalsIgnoreCase(TIMEOUT_HEADER)) {
        t = Duration.ofMillis(Long.parseLong(h.getValue()));
      } else if (!RESTRICTED.contains(h.getKey().toLowerCase())) {
        b.header(h.getKey(), h.getValue());
      }
    }
    b.timeout(t);
    byte[] body = r.body() == null ? new byte[0] : r.body();
    b.method(
        r.method(),
        body.length == 0 && r.method().equals("GET")
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
    try {
      HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
      Map<String, String> headers = new LinkedHashMap<>();
      resp.headers()
          .map()
          .forEach((k, v) -> headers.put(k, v.isEmpty() ? "" : String.join(",", (List<String>) v)));
      return new ShopResponse(resp.statusCode(), headers, resp.body());
    } catch (HttpConnectTimeoutException e) {
      throw new ShopTransportException(
          ShopTransportException.Kind.CONNECT_FAILED, "connect timeout");
    } catch (HttpTimeoutException e) {
      throw new ShopTransportException(ShopTransportException.Kind.TIMEOUT, "timeout");
    } catch (ConnectException | UnresolvedAddressException e) {
      throw new ShopTransportException(
          ShopTransportException.Kind.CONNECT_FAILED, String.valueOf(e.getMessage()));
    } catch (IOException e) {
      if (e.getCause() instanceof ConnectException) {
        throw new ShopTransportException(ShopTransportException.Kind.CONNECT_FAILED, e.toString());
      }
      throw new ShopTransportException(ShopTransportException.Kind.RESET, e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ShopTransportException(ShopTransportException.Kind.RESET, "interrupted");
    }
  }
}
