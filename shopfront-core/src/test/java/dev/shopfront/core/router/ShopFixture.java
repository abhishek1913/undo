package dev.shopfront.core.router;

import com.fasterxml.jackson.databind.JsonNode;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.port.ShopClock;
import dev.shopfront.core.port.ShopConfig;
import dev.shopfront.core.port.ShopRandom;
import dev.shopfront.core.store.InMemoryShopStore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/** A Shopfront router on an in-memory store with a controllable clock. */
final class ShopFixture {
  static final String API = "test_api_key";
  static final String ADMIN = "test_admin";
  static final String SECRET = "whsec_test";

  Instant now = Instant.parse("2026-10-20T10:00:00Z");
  final InMemoryShopStore store = new InMemoryShopStore();
  final SplittableRandom rnd = new SplittableRandom(7);
  final ShopClock clock = () -> now;
  final ShopRandom random =
      new ShopRandom() {
        public double nextDouble() {
          return rnd.nextDouble();
        }

        public int nextInt(int bound) {
          return rnd.nextInt(bound);
        }

        public long nextLong() {
          return rnd.nextLong();
        }
      };
  final ShopConfig config =
      new ShopConfig(
          API, ADMIN, SECRET, Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofHours(24));
  final ShopRouter router = new ShopRouter(store, clock, random, config);

  ShopResponse call(String method, String path, String body, String... headers) {
    Map<String, String> h = new HashMap<>();
    if (path.startsWith("/v1")) {
      h.put("Authorization", "Bearer " + API);
    } else {
      h.put("X-Admin-Token", ADMIN);
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      h.put(headers[i], headers[i + 1]);
    }
    return router.handle(ShopRequest.of(method, path, h, body));
  }

  JsonNode ok(String method, String path, String body, String... headers) {
    ShopResponse r = call(method, path, body, headers);
    if (r.status() >= 300) {
      throw new AssertionError(method + " " + path + " -> " + r.status() + " " + r.bodyText());
    }
    return Json.parse(r.body());
  }

  void seed(int customers, int orders, int tickets) {
    ok(
        "POST",
        "/admin/seed",
        "{\"seed\":42,\"customers\":"
            + customers
            + ",\"orders\":"
            + orders
            + ",\"tickets\":"
            + tickets
            + "}");
  }

  static String code(ShopResponse r) {
    return Json.parse(r.body()).path("error").path("code").asText();
  }
}
