package dev.shopfront.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.webhooks.WebhookOutbox;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** M1 acceptance against the real server and Postgres 17. */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.name=shopfront")
class ShopfrontServerIT {
  @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17");

  static final List<Map<String, Object>> RECEIVED = new CopyOnWriteArrayList<>();
  static HttpServer receiver;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext(
        "/hook",
        ex -> {
          byte[] body = ex.getRequestBody().readAllBytes();
          RECEIVED.add(
              Map.of(
                  "sig", ex.getRequestHeaders().getFirst("Shopfront-Signature"),
                  "id", ex.getRequestHeaders().getFirst("Shopfront-Event-Id"),
                  "body", body));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    receiver.start();
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("shop.api-key", () -> "k");
    r.add("shop.admin-token", () -> "adm");
    r.add("shop.webhook-secret", () -> "whsec_it");
    r.add(
        "shop.webhook-url", () -> "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook");
  }

  @AfterAll
  static void stop() {
    receiver.stop(0);
  }

  @LocalServerPort int port;
  @Autowired JdbcClient db;
  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<byte[]> call(String method, String path, String body, String... headers)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
    if (path.startsWith("/v1")) {
      b.header("Authorization", "Bearer k");
    } else if (path.startsWith("/admin")) {
      b.header("X-Admin-Token", "adm");
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  JsonNode json(HttpResponse<byte[]> r) {
    return Json.parse(r.body());
  }

  @BeforeEach
  void seed() throws Exception {
    call("POST", "/admin/faults", "{\"preset\":\"calm\"}");
    assertThat(call("POST", "/admin/seed", "{\"seed\":42}").statusCode()).isEqualTo(200);
  }

  String dumpHash() {
    StringBuilder sb = new StringBuilder();
    for (String t : List.of("customers", "orders", "tickets")) {
      sb.append(
          db.sql(
                  "select coalesce(md5(string_agg(row_to_json(t)::text, ',' order by id)), '') from "
                      + t
                      + " t")
              .query(String.class)
              .single());
    }
    sb.append(
        db.sql("select value::text from shop_meta where key = 'ground_truth'")
            .query(String.class)
            .single());
    return Json.sha256Hex(sb.toString());
  }

  @Test
  void seed42ProducesIdenticalDataTwice() throws Exception {
    String first = dumpHash();
    String apiHash1 =
        json(call("POST", "/admin/seed", "{\"seed\":42}")).get("data_sha256").asText();
    String second = dumpHash();
    String apiHash2 =
        json(call("POST", "/admin/seed", "{\"seed\":42}")).get("data_sha256").asText();
    assertThat(second).isEqualTo(first);
    assertThat(apiHash2).isEqualTo(apiHash1);
    assertThat(db.sql("select count(*) from tickets").query(Long.class).single()).isEqualTo(200);
  }

  String refundBody(String order) {
    return "{\"order_id\":\""
        + order
        + "\",\"amount_minor\":1000,\"reason\":\"damaged\",\"metadata\":{\"undo_action_id\":\"act-1\"}}";
  }

  @Test
  void sameKeyReturnsByteIdenticalResponseAndCreatesOneRefund() throws Exception {
    HttpResponse<byte[]> a =
        call("POST", "/v1/refunds", refundBody("ORD-0001"), "Idempotency-Key", "undo_same");
    HttpResponse<byte[]> b =
        call("POST", "/v1/refunds", refundBody("ORD-0001"), "Idempotency-Key", "undo_same");
    assertThat(a.statusCode()).isEqualTo(201);
    assertThat(b.statusCode()).isEqualTo(201);
    assertThat(b.body()).isEqualTo(a.body());
    assertThat(
            db.sql("select count(*) from refunds where order_id = 'ORD-0001'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void concurrentDuplicatesWithTheSameKeyCreateOneRefund() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(20);
    List<Future<HttpResponse<byte[]>>> fs = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      fs.add(
          pool.submit(
              () ->
                  call(
                      "POST",
                      "/v1/refunds",
                      refundBody("ORD-0002"),
                      "Idempotency-Key",
                      "undo_conc")));
    }
    List<String> bodies = new ArrayList<>();
    for (Future<HttpResponse<byte[]>> f : fs) {
      HttpResponse<byte[]> r = f.get();
      assertThat(r.statusCode()).isEqualTo(201);
      bodies.add(new String(r.body()));
    }
    pool.shutdown();
    assertThat(bodies).allMatch(s -> s.equals(bodies.get(0)));
    assertThat(
            db.sql("select count(*) from refunds where order_id = 'ORD-0002'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void dropAfterCommitReturns502ButTheRefundExists() throws Exception {
    call("POST", "/admin/faults", "{\"drop_after_commit_rate\":1}");
    HttpResponse<byte[]> r =
        call("POST", "/v1/refunds", refundBody("ORD-0003"), "Idempotency-Key", "undo_drop");
    assertThat(r.statusCode()).isEqualTo(502);
    assertThat(r.body()).isEmpty();
    call("POST", "/admin/faults", "{\"preset\":\"calm\"}");
    JsonNode found = json(call("GET", "/v1/refunds?metadata.undo_action_id=act-1", null));
    assertThat(found.get("data")).hasSize(1);
    // Retrying with the same key returns the stored response, not a second refund.
    HttpResponse<byte[]> again =
        call("POST", "/v1/refunds", refundBody("ORD-0003"), "Idempotency-Key", "undo_drop");
    assertThat(again.statusCode()).isEqualTo(201);
    assertThat(
            db.sql("select count(*) from refunds where order_id = 'ORD-0003'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void injectedFaultsBeforeHandlingApplyNoEffect() throws Exception {
    call("POST", "/admin/faults", "{\"error_5xx_rate\":1}");
    assertThat(
            call("POST", "/v1/refunds", refundBody("ORD-0004"), "Idempotency-Key", "x5")
                .statusCode())
        .isEqualTo(503);
    assertThat(call("GET", "/v1/orders/ORD-0004", null).statusCode()).isEqualTo(200);
    call("POST", "/admin/faults", "{\"error_5xx_rate\":0,\"rate_limit_rate\":1}");
    HttpResponse<byte[]> r =
        call("POST", "/v1/refunds", refundBody("ORD-0004"), "Idempotency-Key", "x6");
    assertThat(r.statusCode()).isEqualTo(429);
    assertThat(r.headers().firstValue("Retry-After")).contains("1");
    assertThat(
            db.sql("select count(*) from refunds where order_id = 'ORD-0004'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void webhooksAreSignedAndVerifiable() throws Exception {
    RECEIVED.clear();
    String id =
        json(call("POST", "/v1/refunds", refundBody("ORD-0005"), "Idempotency-Key", "w1"))
            .get("id")
            .asText();
    call("POST", "/v1/refunds/" + id + "/cancel", null, "Idempotency-Key", "w2");
    for (int i = 0; i < 100 && RECEIVED.isEmpty(); i++) {
      Thread.sleep(100);
    }
    assertThat(RECEIVED).isNotEmpty();
    Map<String, Object> hook = RECEIVED.get(0);
    String sig = (String) hook.get("sig");
    byte[] body = (byte[]) hook.get("body");
    long t = Long.parseLong(sig.substring(2, sig.indexOf(',')));
    assertThat(sig.substring(sig.indexOf("v1=") + 3))
        .isEqualTo(WebhookOutbox.sign("whsec_it", t, body));
    assertThat(Json.parse(body).get("type").asText()).isEqualTo("refund.cancelled");
    assertThat((String) hook.get("id")).startsWith("evt_");
  }

  @Test
  void providerPageShowsInvariants() throws Exception {
    HttpResponse<byte[]> page = call("GET", "/", null);
    assertThat(new String(page.body()))
        .contains("Shopfront — provider view")
        .contains("Invariant check");
    JsonNode data = json(call("GET", "/page/data", null));
    assertThat(data.get("invariants").get("orders_with_multiple_undo_refunds").asInt()).isZero();
    assertThat(
            call("PUT", "/page/orders/ORD-0001/note", "{\"note\":\"edited by a person\"}")
                .statusCode())
        .isEqualTo(200);
    assertThat(json(call("GET", "/v1/orders/ORD-0001", null)).get("note_version").asInt())
        .isEqualTo(1);
  }
}
