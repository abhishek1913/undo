package dev.undo.connector.shopfront;

import dev.shopfront.core.router.ShopRouter;
import java.util.HashMap;
import java.util.Map;

/** ShopfrontApi straight onto an in-memory ShopRouter (no network). */
final class RouterApi implements ShopfrontApi {
  final ShopRouter router;

  RouterApi(ShopRouter router) {
    this.router = router;
  }

  @Override
  public ShopResponse send(ShopRequest r) {
    Map<String, String> h = new HashMap<>(r.headers());
    h.remove(TIMEOUT_HEADER);
    dev.shopfront.core.router.ShopResponse s =
        router.handle(new dev.shopfront.core.router.ShopRequest(r.method(), r.path(), h, r.body()));
    return new ShopResponse(s.status(), s.headers(), s.body());
  }
}
