package dev.shopfront.core.store;

import java.util.function.Function;

/** One call = one transaction. */
public interface ShopStore {
  <T> T tx(Function<ShopTx, T> work);
}
