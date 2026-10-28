package dev.undo.sim.world;

import com.fasterxml.jackson.databind.JsonNode;
import dev.shopfront.core.faults.FaultConfig;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.port.ShopConfig;
import dev.shopfront.core.port.ShopRandom;
import dev.shopfront.core.router.ShopRequest;
import dev.shopfront.core.router.ShopResponse;
import dev.shopfront.core.router.ShopRouter;
import dev.shopfront.core.settlement.Settlement;
import dev.shopfront.core.store.InMemoryShopStore;
import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;

/** The real ShopRouter on an in-memory store, driven by virtual time (Section 21.2). */
public final class SimShopfront {
  static final String API_KEY = "sim_api_key";
  static final String ADMIN = "sim_admin";
  static final String SECRET = "whsec_sim";

  final InMemoryShopStore store = new InMemoryShopStore();
  final ShopRouter router;
  final ShopRandom random;
  private final EventLoop loop;
  FaultConfig faults = FaultConfig.CALM;

  SimShopfront(EventLoop loop, SplittableRandom r, WorldParams p, long seed) {
    this.loop = loop;
    SplittableRandom rr = r;
    this.random =
        new ShopRandom() {
          public double nextDouble() {
            return rr.nextDouble();
          }

          public int nextInt(int bound) {
            return rr.nextInt(bound);
          }

          public long nextLong() {
            return rr.nextLong();
          }
        };
    ShopConfig cfg =
        new ShopConfig(
            API_KEY,
            ADMIN,
            SECRET,
            Duration.ofNanos(p.settlementDelayMicros() * 1000),
            Duration.ofMinutes(15),
            Duration.ofHours(24));
    router = new ShopRouter(store, loop::now, random, cfg);
    admin(
        "POST",
        "/admin/seed",
        "{\"seed\":"
            + seed
            + ",\"customers\":"
            + WorldParams.CUSTOMERS
            + ",\"orders\":"
            + WorldParams.ORDERS
            + ",\"tickets\":"
            + WorldParams.TICKETS
            + "}");
    faults =
        FaultConfig.CALM.merge(
            Json.parse(
                ("{\"settlement_failure_rate\":"
                        + p.settlementFailure()
                        + ",\"webhook_drop_rate\":"
                        + p.webhookDrop()
                        + ",\"webhook_duplicate_rate\":"
                        + p.webhookDup()
                        + ",\"webhook_delay_ms_max\":"
                        + (p.webhookDelayMaxMicros() / 1000)
                        + "}")
                    .getBytes()));
  }

  /** Quiescence: webhook faults off (settlement failures are provider behavior and stay). */
  void calmWebhooks() {
    faults =
        faults.merge(
            Json.parse(
                "{\"webhook_drop_rate\":0,\"webhook_duplicate_rate\":0,\"webhook_delay_ms_max\":0}"
                    .getBytes()));
  }

  ShopResponse handle(ShopRequest r) {
    return router.handle(r);
  }

  JsonNode admin(String method, String path, String body) {
    return Json.parse(
        router.handle(ShopRequest.of(method, path, Map.of("X-Admin-Token", ADMIN), body)).body());
  }

  JsonNode get(String path) {
    return Json.parse(
        router
            .handle(ShopRequest.of("GET", path, Map.of("Authorization", "Bearer " + API_KEY), null))
            .body());
  }

  /** Settlement job: every 5 s of virtual time. */
  void settlementTick() {
    store.tx(tx -> Settlement.runDue(tx, random, loop.now(), faults));
  }
}
