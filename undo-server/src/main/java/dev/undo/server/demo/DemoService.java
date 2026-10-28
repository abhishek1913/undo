package dev.undo.server.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.store.DemoReset;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Demo operations (Section 19.3): talks to Shopfront's admin API and resets Undo's data. */
@Service
public class DemoService {
  private final UndoProperties props;
  private final DemoReset reset;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public DemoService(UndoProperties props, DemoReset reset) {
    this.props = props;
    this.reset = reset;
  }

  public boolean enabled() {
    return props.demo().enabled();
  }

  /** Shopfront /admin/seed with this seed, then empties Undo's run data. */
  public ObjectNode seed(long seed) throws IOException, InterruptedException {
    ObjectNode req = Json.obj();
    req.put("seed", seed);
    JsonNode shop = admin("POST", "/admin/seed", req.toString());
    reset.resetUndoData();
    ObjectNode out = Json.obj();
    out.put("seed", seed);
    out.set("shopfront", shop);
    out.put("undo_reset", true);
    return out;
  }

  public JsonNode faults() throws IOException, InterruptedException {
    return admin("GET", "/admin/faults", null);
  }

  public JsonNode setFaults(JsonNode body) throws IOException, InterruptedException {
    return admin("POST", "/admin/faults", body.toString());
  }

  /** Settles up to {@code limit} pending refunds, only of this run when given. */
  public JsonNode settle(int limit, java.util.UUID runId) throws IOException, InterruptedException {
    return admin(
        "POST",
        "/admin/settlement/run?limit=" + limit + (runId == null ? "" : "&undo_run_id=" + runId),
        null);
  }

  public JsonNode carrierBug(boolean enabled) throws IOException, InterruptedException {
    return admin("POST", "/admin/carrier-bug", "{\"enabled\":" + enabled + "}");
  }

  /** POST /internal/halt on a worker: it calls Runtime.halt(137) and compose restarts it. */
  public ObjectNode killWorker() throws IOException, InterruptedException {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(props.demo().workerHaltUrl()))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    ObjectNode out = Json.obj();
    out.put("worker", props.demo().workerHaltUrl());
    out.put("status", r.statusCode());
    out.put("halting", r.statusCode() == 202);
    return out;
  }

  JsonNode admin(String method, String path, String body) throws IOException, InterruptedException {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(props.connectors().shopfront().baseUrl() + path))
                .header("X-Admin-Token", props.connectors().shopfront().adminToken())
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
  }
}
