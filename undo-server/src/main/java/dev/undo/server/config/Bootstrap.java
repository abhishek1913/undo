package dev.undo.server.config;

import dev.undo.core.model.Agent;
import dev.undo.core.policy.BuiltinPolicies;
import dev.undo.core.policy.PolicyService;
import dev.undo.core.port.Clock;
import dev.undo.core.port.Ids;
import dev.undo.core.port.Store;
import dev.undo.server.security.ApiKeys;
import dev.undo.server.store.ApiKeyStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Startup: ship support-default, and in demo mode the demo agent with one API key. */
@Component
@Order(1)
public class Bootstrap implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
  private final PolicyService policies;
  private final Store store;
  private final ApiKeyStore keys;
  private final Ids ids;
  private final Clock clock;
  private final UndoProperties props;

  public Bootstrap(
      PolicyService policies,
      Store store,
      ApiKeyStore keys,
      Ids ids,
      Clock clock,
      UndoProperties props) {
    this.policies = policies;
    this.store = store;
    this.keys = keys;
    this.ids = ids;
    this.clock = clock;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      policies.bootstrap(BuiltinPolicies.supportDefaultYaml(), "bootstrap");
    } catch (RuntimeException e) {
      log.info("support-default already present ({})", e.getMessage()); // another node won the race
    }
    if (props.demo().enabled() && props.api()) {
      demoAgent();
    }
  }

  private void demoAgent() {
    Agent agent =
        store.tx(
            tx ->
                tx.agents()
                    .byName("demo-support")
                    .orElseGet(
                        () -> {
                          Agent a = new Agent();
                          a.id = ids.next();
                          a.name = "demo-support";
                          a.createdAt = clock.now();
                          tx.agents().insert(a);
                          return a;
                        }));
    String fixed = props.demo().agentKey();
    if (fixed != null && !fixed.isBlank()) {
      if (keys.byHash(ApiKeys.hash(fixed)).isEmpty()) {
        keys.insert(
            ids.next(),
            agent.id,
            ApiKeys.prefix(fixed),
            ApiKeys.hash(fixed),
            List.of("shop_*", "undo_run_status"),
            "support-default",
            clock.now());
      }
      return;
    }
    if (keys.countForAgent(agent.id) == 0) {
      String key = ApiKeys.generate();
      keys.insert(
          ids.next(),
          agent.id,
          ApiKeys.prefix(key),
          ApiKeys.hash(key),
          List.of("shop_*", "undo_run_status"),
          "support-default",
          clock.now());
      log.info("Demo agent demo-support API key (shown once): {}", key);
    }
  }
}
