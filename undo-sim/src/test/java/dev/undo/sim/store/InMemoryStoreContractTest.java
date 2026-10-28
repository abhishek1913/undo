package dev.undo.sim.store;

import dev.undo.core.port.Store;
import dev.undo.core.store.StoreContractTest;
import java.util.UUID;

class InMemoryStoreContractTest extends StoreContractTest {
  @Override
  protected Store createStore(UUID userId) {
    return new InMemoryStore();
  }
}
