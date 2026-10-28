package dev.undo.core.port;

import java.util.function.Function;

/** One call = one ACID transaction (READ COMMITTED in Postgres). */
public interface Store {
  <T> T tx(Function<TxContext, T> work);
}
