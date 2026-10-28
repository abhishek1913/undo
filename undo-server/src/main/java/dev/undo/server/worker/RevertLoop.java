package dev.undo.server.worker;

import dev.undo.core.revert.RevertService;
import dev.undo.server.config.EngineBeans;
import dev.undo.server.config.UndoProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Revert executor (Section 7.6): one step per open plan every 300 ms, and expiry of old previews. A
 * step makes provider calls, so it runs on a virtual thread, never two rounds at once per node.
 */
@Component
public class RevertLoop implements WorkerLoops.Extra {
  private static final Logger log = LoggerFactory.getLogger(RevertLoop.class);
  private final RevertService reverts;
  private final UndoProperties props;
  private final String node;
  private final AtomicBoolean busy = new AtomicBoolean();

  public RevertLoop(RevertService reverts, UndoProperties props, EngineBeans.NodeId node) {
    this.reverts = reverts;
    this.props = props;
    this.node = node.value();
  }

  @Override
  public String name() {
    return "revert";
  }

  @Override
  public Duration interval() {
    return props.loops().revert();
  }

  @Override
  public int round() {
    if (!busy.compareAndSet(false, true)) {
      return 0;
    }
    Thread.startVirtualThread(
        () -> {
          try {
            reverts.expirePreviews(20);
            reverts.stepAll(node, 10);
          } catch (RuntimeException e) {
            log.warn("revert round failed: {}", e.toString());
          } finally {
            busy.set(false);
          }
        });
    return 0;
  }
}
