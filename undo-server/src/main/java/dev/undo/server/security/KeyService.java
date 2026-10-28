package dev.undo.server.security;

import dev.undo.core.engine.UndoException;
import dev.undo.core.model.Agent;
import dev.undo.core.port.Clock;
import dev.undo.core.port.Ids;
import dev.undo.core.port.Store;
import dev.undo.server.store.ApiKeyStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Agents and their API keys for the console (Settings → keys). */
@Service
public class KeyService {
  public record Created(UUID keyId, String key, String prefix) {}

  /** A key as the console shows it (never the key itself). */
  public record KeyView(
      UUID id,
      String prefix,
      List<String> allowedTools,
      String defaultPolicy,
      java.time.Instant createdAt,
      java.time.Instant revokedAt) {}

  private final ApiKeyStore keys;
  private final Store store;
  private final Ids ids;
  private final Clock clock;

  public KeyService(ApiKeyStore keys, Store store, Ids ids, Clock clock) {
    this.keys = keys;
    this.store = store;
    this.ids = ids;
    this.clock = clock;
  }

  public Agent createAgent(String name) {
    if (name == null || !name.matches("[a-z0-9][a-z0-9-]{1,62}")) {
      throw new UndoException(
          UndoException.Code.INVALID_REQUEST, "Agent names use a-z, 0-9 and -.");
    }
    return store.tx(
        tx -> {
          if (tx.agents().byName(name).isPresent()) {
            throw new UndoException(
                UndoException.Code.INVALID_REQUEST, "An agent named " + name + " exists.");
          }
          Agent a = new Agent();
          a.id = ids.next();
          a.name = name;
          a.createdAt = clock.now();
          tx.agents().insert(a);
          return a;
        });
  }

  public Created createKey(UUID agentId, List<String> allowedTools, String defaultPolicy) {
    store
        .tx(tx -> tx.agents().get(agentId))
        .orElseThrow(
            () -> new UndoException(UndoException.Code.INVALID_REQUEST, "No agent " + agentId));
    if (allowedTools == null || allowedTools.isEmpty()) {
      throw new UndoException(
          UndoException.Code.INVALID_REQUEST, "Give at least one allowed tool pattern.");
    }
    String key = ApiKeys.generate();
    UUID id = ids.next();
    keys.insert(
        id,
        agentId,
        ApiKeys.prefix(key),
        ApiKeys.hash(key),
        allowedTools,
        defaultPolicy == null ? "support-default" : defaultPolicy,
        clock.now());
    return new Created(id, key, ApiKeys.prefix(key));
  }

  public List<KeyView> keysOf(UUID agentId) {
    return keys.byAgent(agentId).stream()
        .map(
            k ->
                new KeyView(
                    k.id(),
                    k.prefix(),
                    k.allowedTools(),
                    k.defaultPolicy(),
                    k.createdAt(),
                    k.revokedAt()))
        .toList();
  }

  public boolean revoke(UUID keyId) {
    return keys.revoke(keyId, clock.now());
  }
}
