package dev.undo.sim.world;

import dev.shopfront.core.router.ShopResponse;
import dev.undo.connector.shopfront.ShopRequest;
import dev.undo.connector.shopfront.ShopTransportException;
import dev.undo.connector.shopfront.ShopfrontApi;
import dev.undo.core.port.CrashPoint;
import dev.undo.sim.SimulatedCrash;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * One worker's view of the network to Shopfront. A call is a blocking point: the task parks until
 * the response arrives or the client timeout fires. Faults: drops, slow deliveries, lost responses,
 * 5xx/429 before handling, and process stalls after a response arrived (zombies).
 */
final class SimNetwork implements ShopfrontApi {
  private static final Object TIMEOUT = new Object();
  private static final Object RESET = new Object();

  private final World w;
  private final SimWorker worker;
  private final SplittableRandom r;

  SimNetwork(World w, SimWorker worker, SplittableRandom r) {
    this.w = w;
    this.worker = worker;
    this.r = r;
  }

  @Override
  public dev.undo.connector.shopfront.ShopResponse send(ShopRequest req)
      throws ShopTransportException {
    CoopScheduler sched = w.sched;
    Map<String, String> headers = new HashMap<>(req.headers());
    String t = headers.remove(TIMEOUT_HEADER);
    long timeoutMicros = (t == null ? 10_000 : Long.parseLong(t)) * 1000L;
    boolean write = !req.method().equals("GET");
    dev.shopfront.core.router.ShopRequest shopReq =
        new dev.shopfront.core.router.ShopRequest(req.method(), req.path(), headers, req.body());
    WorldParams p = w.params;
    boolean faulty = w.chaos;
    long now = w.loop.nowMicros();
    CoopScheduler.Park park = sched.newPark();
    String label = req.method() + " " + req.path().split("\\?")[0];

    if (faulty && p.outage(now)) {
      // Provider outage: connection refused, or the request vanishes and the client times out.
      if (r.nextBoolean()) {
        throw new ShopTransportException(
            ShopTransportException.Kind.CONNECT_FAILED, "connection refused (outage)");
      }
      sched.resumeAt(park, now + timeoutMicros, "timeout (outage) " + label, TIMEOUT);
    } else if (faulty && write && r.nextDouble() < p.error5xx()) {
      sched.resumeAt(
          park,
          now + p.responseDelay(r),
          "503 " + label,
          response(503, "{\"error\":{\"code\":\"service_unavailable\"}}", Map.of()));
    } else if (faulty && write && r.nextDouble() < p.rate429()) {
      sched.resumeAt(
          park,
          now + p.responseDelay(r),
          "429 " + label,
          response(429, "{\"error\":{\"code\":\"rate_limited\"}}", Map.of("Retry-After", "1")));
    } else {
      boolean dropRequest = faulty && r.nextDouble() < p.requestDrop();
      boolean dropResponse = faulty && r.nextDouble() < p.responseDrop();
      boolean reset = r.nextBoolean();
      long d1 = faulty ? p.deliveryDelay(r) : WorldParams.ms(1) + r.nextInt(50_000);
      long d2 = faulty ? p.responseDelay(r) : WorldParams.ms(1) + r.nextInt(50_000);
      if (!dropRequest) {
        w.loop.at(
            now + d1,
            "deliver " + label + " from " + worker.node,
            () -> {
              ShopResponse resp = w.shop.handle(shopReq);
              w.coverage.providerRequest(label, resp.status());
              try {
                worker.crashPoints().hit(CrashPoint.PROVIDER_CALL_AFTER_SEND);
              } catch (SimulatedCrash c) {
                return; // the worker died after the provider applied the request
              }
              if (dropResponse) {
                if (reset) {
                  sched.resumeAt(park, w.loop.nowMicros() + d2, "reset " + label, RESET);
                }
              } else {
                sched.resumeAt(park, w.loop.nowMicros() + d2, "response " + label, resp);
              }
            });
      }
      sched.resumeAt(park, now + timeoutMicros, "timeout " + label, TIMEOUT);
    }
    Object v = sched.block(park);
    if (v == TIMEOUT) {
      throw new ShopTransportException(ShopTransportException.Kind.TIMEOUT, "timeout");
    }
    if (v == RESET) {
      throw new ShopTransportException(ShopTransportException.Kind.RESET, "reset");
    }
    ShopResponse resp = (ShopResponse) v;
    if (faulty && r.nextDouble() < p.stallRate()) {
      // The process stalls (GC pause, VM suspend) after the response arrived: a zombie in the
      // making.
      sched.sleep(
          WorldParams.s(25) + (long) (r.nextDouble() * WorldParams.s(65)), "stall " + worker.node);
    }
    return new dev.undo.connector.shopfront.ShopResponse(
        resp.status(), resp.headers(), resp.body());
  }

  private static ShopResponse response(int status, String body, Map<String, String> headers) {
    return new ShopResponse(status, headers, body.getBytes(StandardCharsets.UTF_8));
  }
}
