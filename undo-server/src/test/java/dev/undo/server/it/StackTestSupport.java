package dev.undo.server.it;

import com.fasterxml.jackson.databind.JsonNode;
import dev.shopfront.server.ShopfrontServerApplication;
import dev.undo.core.json.Json;
import dev.undo.core.port.CrashPoints;
import dev.undo.core.port.Store;
import dev.undo.server.UndoServerApplication;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The real stack in one JVM: Postgres 17 (two databases), Shopfront on a random HTTP port, and Undo
 * (roles api + worker) on a fixed free port so Shopfront's webhooks reach it. Timings are short
 * (lease 2 s, provider timeout 1 s, lookup grace 1 s) so recovery paths finish quickly.
 */
@SpringBootTest(
    classes = UndoServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(StackTestSupport.TestBeans.class)
public abstract class StackTestSupport {
  public static final String SHOP_KEY = "it_shop_key";
  public static final String SHOP_ADMIN = "it_shop_admin";
  public static final String SECRET = "whsec_it_secret";
  public static final String AGENT_KEY = "undo_sk_ItTestKey0000000000000000000000000000000";

  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17").withCommand("postgres", "-c", "max_connections=200");
  static final int UNDO_PORT;
  static final int SHOP_PORT;
  static final ConfigurableApplicationContext SHOP;
  static final TestCrashPoints CRASH = new TestCrashPoints();

  static {
    PG.start();
    try (Connection c =
        DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
      c.createStatement().execute("create database shopfront");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    UNDO_PORT = freePort();
    SHOP =
        new SpringApplicationBuilder(ShopfrontServerApplication.class)
            .properties(ShopfrontServerApplication.CONFIG_NAME)
            .run(
                "--server.port=0",
                "--spring.datasource.url="
                    + PG.getJdbcUrl()
                        .replace("/test?", "/shopfront?")
                        .replaceAll("/test$", "/shopfront"),
                "--spring.datasource.username=" + PG.getUsername(),
                "--spring.datasource.password=" + PG.getPassword(),
                "--shop.api-key=" + SHOP_KEY,
                "--shop.admin-token=" + SHOP_ADMIN,
                "--shop.webhook-secret=" + SECRET,
                "--shop.webhook-url=http://localhost:" + UNDO_PORT + "/webhooks/shopfront",
                "--shop.settlement-delay=PT1H",
                "--shop.export-lag=PT1M",
                "--spring.jmx.enabled=false");
    SHOP_PORT = Integer.parseInt(SHOP.getEnvironment().getProperty("local.server.port"));
  }

  static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("server.port", () -> UNDO_PORT);
    r.add("undo.node-id", () -> "it-node");
    r.add("undo.connectors.shopfront.base-url", () -> "http://localhost:" + SHOP_PORT);
    r.add("undo.connectors.shopfront.api-key", () -> SHOP_KEY);
    r.add("undo.connectors.shopfront.admin-token", () -> SHOP_ADMIN);
    r.add("undo.connectors.shopfront.export-lag", () -> "PT1M");
    r.add("undo.webhooks.shopfront.secret", () -> SECRET);
    r.add("undo.demo.enabled", () -> "true");
    r.add("undo.demo.agent-key", () -> AGENT_KEY);
    r.add("undo.execution.lease", () -> "PT2S");
    r.add("undo.execution.provider-timeout", () -> "PT1S");
    r.add("undo.execution.backoff-base", () -> "PT0.05S");
    r.add("undo.execution.backoff-cap", () -> "PT0.5S");
    r.add("undo.resolve.lookup-grace", () -> "PT1S");
    r.add("undo.loops.executor", () -> "PT0.05S");
    r.add("undo.loops.sweeper", () -> "PT0.2S");
    r.add("undo.loops.resolver", () -> "PT0.1S");
    r.add("undo.loops.approval-expiry", () -> "PT0.3S");
    r.add("undo.loops.webhook-retry", () -> "PT0.3S");
    r.add("undo.loops.revert", () -> "PT0.1S");
    r.add("undo.recon.scheduler-enabled", () -> "false"); // ReconIT triggers runs itself
    r.add("undo.ratelimit.per-key-rps", () -> "1000");
    r.add("undo.ratelimit.burst", () -> "2000");
  }

  @TestConfiguration
  static class TestBeans {
    @Bean
    @Primary
    CrashPoints testCrashPoints() {
      return CRASH;
    }
  }

  @Autowired protected Store store;
  protected final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeEach
  void calmAndSeed() throws Exception {
    CRASH.reset();
    shop("POST", "/admin/faults", "{\"preset\":\"calm\"}");
  }

  @AfterEach
  void calmAfter() throws Exception {
    CRASH.reset();
    shop("POST", "/admin/faults", "{\"preset\":\"calm\"}");
  }

  /** Seeds Shopfront once per test class (orders are not reused across tests: dedupe is global). */
  protected static synchronized void seedOnce() throws Exception {
    if (!seeded) {
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + SHOP_PORT + "/admin/seed"))
                  .header("X-Admin-Token", SHOP_ADMIN)
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"seed\":42,\"customers\":60,\"orders\":3000,\"tickets\":200}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      seeded = true;
    }
  }

  private static boolean seeded;
  private static int nextOrder = 400;

  /** A fresh order (not used by tickets or earlier tests), for refunds that must not collide. */
  protected static synchronized String freshOrder() {
    return String.format("ORD-%04d", ++nextOrder);
  }

  /** A fresh order whose total is above ₹11,000, so a ₹10,500 refund needs approval. */
  protected String freshBigOrder() throws Exception {
    while (true) {
      String o = freshOrder();
      if (shop("GET", "/v1/orders/" + o, null).path("total_minor").asLong() > 1_100_000) {
        return o;
      }
    }
  }

  public record Resp(int status, JsonNode body, java.net.http.HttpHeaders headers) {}

  protected Resp undo(String method, String path, String body, String... headers) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + UNDO_PORT + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60));
    boolean auth = true;
    for (int i = 0; i + 1 < headers.length; i += 2) {
      if (headers[i].equals("Authorization")) {
        auth = false;
      }
      b.header(headers[i], headers[i + 1]);
    }
    if (auth && (path.startsWith("/v1") || path.startsWith("/mcp"))) {
      b.header("Authorization", "Bearer " + AGENT_KEY);
    }
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Resp(
        r.statusCode(), r.body().isEmpty() ? Json.obj() : Json.parse(r.body()), r.headers());
  }

  protected JsonNode shop(String method, String path, String body) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + SHOP_PORT + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
    if (path.startsWith("/admin")) {
      b.header("X-Admin-Token", SHOP_ADMIN);
    } else {
      b.header("Authorization", "Bearer " + SHOP_KEY);
    }
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return r.body().isEmpty() ? Json.obj() : Json.parse(r.body());
  }

  protected UUID newRun(String extra) throws Exception {
    Resp r =
        undo("POST", "/v1/runs", "{\"title\":\"it\"" + (extra == null ? "" : "," + extra) + "}");
    if (r.status() != 201) {
      throw new AssertionError("create run failed: " + r.status() + " " + r.body());
    }
    return UUID.fromString(r.body().get("id").asText());
  }

  protected Resp propose(UUID run, String tool, String args, String key) throws Exception {
    return propose(run, tool, args, key, 5000);
  }

  protected Resp propose(UUID run, String tool, String args, String key, long waitMs)
      throws Exception {
    String body = "{\"tool\":\"" + tool + "\",\"args\":" + args + ",\"wait_ms\":" + waitMs + "}";
    return key == null
        ? undo("POST", "/v1/runs/" + run + "/actions", body)
        : undo("POST", "/v1/runs/" + run + "/actions", body, "Idempotency-Key", key);
  }

  protected static String refund(String order, long amount) {
    return "{\"ticket_id\":null,\"order_id\":\""
        + order
        + "\",\"amount_minor\":"
        + amount
        + ",\"reason\":\"other\"}";
  }

  protected dev.undo.core.model.Action action(String id) {
    return store.tx(tx -> tx.actions().get(UUID.fromString(id))).orElseThrow();
  }

  protected static void await(String what, Duration max, BooleanSupplier ok)
      throws InterruptedException {
    long end = System.currentTimeMillis() + max.toMillis();
    while (System.currentTimeMillis() < end) {
      if (ok.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("timed out waiting for " + what);
  }

  protected java.util.List<dev.undo.core.model.Action> actionsOf(UUID run) {
    return store.tx(tx -> tx.actions().byRun(run));
  }

  protected long refundsFor(String actionId) throws Exception {
    return shop("GET", "/v1/refunds?metadata.undo_action_id=" + actionId, null).path("data").size();
  }
}
