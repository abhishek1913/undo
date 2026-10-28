package dev.undo.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.json.Json;
import dev.undo.server.config.UndoProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Plain HTTP to Shopfront for launchers and demo controls (not the connector's tool calls). */
@Component
public class ShopfrontHttp {
  private final UndoProperties props;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public ShopfrontHttp(UndoProperties props) {
    this.props = props;
  }

  public JsonNode admin(String method, String path, String body) {
    return send(method, path, body, "X-Admin-Token", props.connectors().shopfront().adminToken());
  }

  public JsonNode api(String method, String path, String body) {
    return send(
        method, path, body, "Authorization", "Bearer " + props.connectors().shopfront().apiKey());
  }

  private JsonNode send(String method, String path, String body, String header, String value) {
    try {
      HttpResponse<String> r =
          http.send(
              HttpRequest.newBuilder(URI.create(props.connectors().shopfront().baseUrl() + path))
                  .header(header, value)
                  .header("Content-Type", "application/json")
                  .timeout(Duration.ofSeconds(30))
                  .method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() / 100 != 2) {
        throw new IllegalStateException("Shopfront " + path + " failed: HTTP " + r.statusCode());
      }
      return r.body().isEmpty() ? Json.obj() : Json.parse(r.body());
    } catch (IOException e) {
      throw new IllegalStateException("Shopfront " + path + " unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }
}
