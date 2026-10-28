package dev.undo.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.json.Hashing;
import dev.undo.core.model.PolicyVersion;
import dev.undo.core.port.Clock;
import dev.undo.core.port.ConnectorRegistry;
import dev.undo.core.port.Ids;
import dev.undo.core.port.Store;
import dev.undo.core.port.TxContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Load, validate, compile, store and evaluate policies (FR-POL-1..7). */
public final class PolicyService {
  private final Store store;
  private final Ids ids;
  private final Clock clock;
  private final PolicyCompiler compiler;
  private final Map<UUID, JsonNode> compiledById = new ConcurrentHashMap<>();

  public PolicyService(Store store, Ids ids, Clock clock, ConnectorRegistry registry) {
    this.store = store;
    this.ids = ids;
    this.clock = clock;
    Map<String, Effect> tools = new HashMap<>();
    for (ToolSpec t : registry.all()) {
      tools.put(t.name(), t.effect());
    }
    this.compiler = new PolicyCompiler(tools);
  }

  public PolicyCompiler.Result validate(String yaml) {
    return compiler.compile(yaml);
  }

  /** Stores the next version of the policy (inactive). TX: one. */
  public PolicyVersion create(String yaml, String createdBy) {
    PolicyCompiler.Result r = compiler.compile(yaml);
    if (!r.ok()) {
      throw new PolicyInvalidException(r.errors());
    }
    return store.tx(tx -> insert(tx, r, yaml, createdBy));
  }

  private PolicyVersion insert(
      TxContext tx, PolicyCompiler.Result r, String yaml, String createdBy) {
    PolicyVersion v = new PolicyVersion();
    v.id = ids.next();
    v.name = r.name();
    v.version = tx.policies().maxVersion(r.name()) + 1;
    v.sourceYaml = yaml;
    v.compiled = r.compiled();
    v.sha256 = Hashing.sha256Hex(yaml);
    v.active = false;
    v.createdBy = createdBy;
    v.createdAt = clock.now();
    tx.policies().insert(v);
    return v;
  }

  /** Activates one version; new runs use it, existing runs keep their pinned version. TX: one. */
  public void activate(UUID versionId) {
    store.tx(
        tx -> {
          tx.policies().activate(versionId);
          return null;
        });
  }

  /** Creates and activates the policy if no version of that name exists yet. */
  public PolicyVersion bootstrap(String yaml, String createdBy) {
    PolicyCompiler.Result r = compiler.compile(yaml);
    if (!r.ok()) {
      throw new PolicyInvalidException(r.errors());
    }
    return store.tx(
        tx -> {
          Optional<PolicyVersion> active = tx.policies().active(r.name());
          if (active.isPresent()) {
            return active.get();
          }
          PolicyVersion v = insert(tx, r, yaml, createdBy);
          tx.policies().activate(v.id);
          v.active = true;
          return v;
        });
  }

  /** Versions are immutable, so the compiled form is cached forever. */
  public JsonNode compiled(TxContext tx, UUID versionId) {
    JsonNode c = compiledById.get(versionId);
    if (c == null) {
      c =
          tx.policies()
              .get(versionId)
              .orElseThrow(() -> new IllegalStateException("no policy version " + versionId))
              .compiled;
      compiledById.put(versionId, c);
    }
    return c;
  }

  public PolicyEvaluator.Result evaluate(
      TxContext tx, UUID versionId, String tool, PolicyEvaluator.Context ctx) {
    return PolicyEvaluator.evaluate(compiled(tx, versionId), tool, ctx);
  }
}
