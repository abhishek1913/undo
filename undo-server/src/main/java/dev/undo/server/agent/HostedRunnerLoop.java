package dev.undo.server.agent;

import dev.undo.core.agent.AgentRunner;
import dev.undo.server.config.EngineBeans;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.worker.WorkerLoops;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hosted runner loop (Section 7.6): every second claims ACTIVE hosted runs (up to 4 at a time per
 * worker) and works each on its own virtual thread.
 */
@Component
public class HostedRunnerLoop implements WorkerLoops.Extra {
  private static final Logger log = LoggerFactory.getLogger(HostedRunnerLoop.class);
  private static final int MAX_RUNS = 4;
  private final AgentRunner runner;
  private final UndoProperties props;
  private final String node;
  private final AtomicInteger active = new AtomicInteger();

  public HostedRunnerLoop(AgentRunner runner, UndoProperties props, EngineBeans.NodeId node) {
    this.runner = runner;
    this.props = props;
    this.node = node.value();
  }

  @Override
  public String name() {
    return "hosted-runner";
  }

  @Override
  public Duration interval() {
    return props.loops().hostedRunner();
  }

  @Override
  public int round() {
    if (active.get() >= MAX_RUNS) {
      return 0;
    }
    var claimed = runner.claim(node);
    if (claimed.isEmpty()) {
      return 0;
    }
    active.incrementAndGet();
    Thread.startVirtualThread(
        () -> {
          try {
            log.info(
                "hosted run {} claimed (fence {})",
                claimed.get().run().number,
                claimed.get().fence());
            runner.process(claimed.get(), node);
          } catch (RuntimeException e) {
            log.warn("hosted run {} stopped: {}", claimed.get().run().number, e.toString());
          } finally {
            active.decrementAndGet();
          }
        });
    return 1;
  }
}
