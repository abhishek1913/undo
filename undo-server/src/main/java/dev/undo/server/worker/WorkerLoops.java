package dev.undo.server.worker;

import dev.undo.core.engine.ApprovalService;
import dev.undo.core.engine.ExecutionService;
import dev.undo.core.engine.RecoveryService;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.WebhookService;
import dev.undo.server.config.EngineBeans;
import dev.undo.server.config.UndoProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The worker role's loops (Section 7.6). Each loop runs, then sleeps its interval; when a round did
 * work it runs again at once, up to 10 times. Claimed actions run on their own virtual threads.
 */
@Component
public class WorkerLoops {
  private static final Logger log = LoggerFactory.getLogger(WorkerLoops.class);
  private final UndoProperties props;
  private final String node;
  private final ExecutionService execution;
  private final RecoveryService recovery;
  private final ApprovalService approvals;
  private final RunService runs;
  private final WebhookService webhooks;
  private final java.util.List<Extra> extras;
  private ScheduledExecutorService scheduler;

  /** Loops added by later features (revert executor, hosted runner, recon scheduler). */
  public interface Extra {
    String name();

    Duration interval();

    /** Returns how much work the round did. */
    int round();
  }

  public WorkerLoops(
      UndoProperties props,
      EngineBeans.NodeId node,
      ExecutionService execution,
      RecoveryService recovery,
      ApprovalService approvals,
      RunService runs,
      WebhookService webhooks,
      java.util.List<Extra> extras) {
    this.props = props;
    this.node = node.value();
    this.execution = execution;
    this.recovery = recovery;
    this.approvals = approvals;
    this.runs = runs;
    this.webhooks = webhooks;
    this.extras = extras;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!props.worker()) {
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("undo-loops").daemon().factory());
    UndoProperties.Loops l = props.loops();
    loop("executor", l.executor(), this::executorRound);
    loop("sweeper", l.sweeper(), () -> recovery.sweepExpiredLeases(node, 100));
    loop("resolver", l.resolver(), this::resolverRound);
    loop("approval-expiry", l.approvalExpiry(), () -> approvals.expireDue(100));
    loop("webhook-retry", l.webhookRetry(), () -> webhooks.retryUnprocessed(100));
    loop("idle-closer", l.idleCloser(), () -> runs.closeIdle(props.runs().idleTimeout(), 100));
    for (Extra e : extras) {
      loop(e.name(), e.interval(), e::round);
    }
    log.info("worker loops started on node {}", node);
  }

  private int executorRound() {
    var claimed = execution.claimBatch(node, 20);
    for (ExecutionService.Claimed c : claimed) {
      Thread.startVirtualThread(() -> safely("execute", () -> execution.executeClaimed(c)));
    }
    return claimed.size();
  }

  private int resolverRound() {
    var work = recovery.claimResolvable(node, 20);
    for (RecoveryService.Work w : work) {
      Thread.startVirtualThread(() -> safely("resolve", () -> recovery.perform(w)));
    }
    return work.size();
  }

  private void loop(String name, Duration interval, IntSupplier round) {
    Runnable task =
        new Runnable() {
          @Override
          public void run() {
            try {
              for (int i = 0; i < 10; i++) {
                if (round.getAsInt() == 0) {
                  break;
                }
              }
            } catch (RuntimeException e) {
              log.warn("loop {} failed: {}", name, e.toString());
            } finally {
              if (!scheduler.isShutdown()) {
                scheduler.schedule(this, interval.toMillis(), TimeUnit.MILLISECONDS);
              }
            }
          }
        };
    scheduler.schedule(task, interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private static void safely(String what, Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      log.warn("{} failed: {}", what, e.toString());
    }
  }

  @PreDestroy
  public void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }
}
