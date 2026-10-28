package dev.undo.server.worker;

import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.port.Store;
import dev.undo.core.port.repo.ReconRepo;
import dev.undo.core.recon.ReconService;
import dev.undo.server.config.EngineBeans;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recon scheduler (Section 7.6, FR-RECON-1): every 30 s checks whether the connector is due (5 min
 * after the last completed run); the job lease {@code recon:shopfront} keeps it to one node. Also
 * refreshes the gauge {@code undo_recon_findings_open{type,severity}}.
 */
@Component
public class ReconLoop implements WorkerLoops.Extra {
  private static final Logger log = LoggerFactory.getLogger(ReconLoop.class);
  private final ReconService recon;
  private final boolean enabled;
  private final Store store;
  private final String node;
  private final MultiGauge open;
  private final AtomicBoolean busy = new AtomicBoolean();

  public ReconLoop(
      ReconService recon,
      Store store,
      EngineBeans.NodeId node,
      MeterRegistry meters,
      dev.undo.server.config.UndoProperties props) {
    this.recon = recon;
    this.enabled = props.recon().schedulerEnabled();
    this.store = store;
    this.node = node.value();
    this.open =
        MultiGauge.builder("undo_recon_findings_open")
            .description("Open and acknowledged recon findings")
            .register(meters);
  }

  @Override
  public String name() {
    return "recon";
  }

  @Override
  public Duration interval() {
    return Duration.ofSeconds(30);
  }

  @Override
  public int round() {
    if (!busy.compareAndSet(false, true)) {
      return 0;
    }
    Thread.startVirtualThread(
        () -> {
          try {
            if (enabled) {
              recon
                  .runScheduledIfDue(ShopfrontConnector.ID, node)
                  .ifPresent(r -> log.info("recon run {} {} {}", r.number, r.status, r.stats));
            }
            refreshGauge();
          } catch (RuntimeException e) {
            log.warn("recon round failed: {}", e.toString());
          } finally {
            busy.set(false);
          }
        });
    return 0;
  }

  void refreshGauge() {
    List<ReconRepo.OpenCount> counts = store.tx(tx -> tx.recon().openCounts());
    open.register(
        counts.stream()
            .map(
                c ->
                    MultiGauge.Row.of(
                        Tags.of("type", c.type().name(), "severity", c.severity().name()),
                        c.count()))
            .toList(),
        true);
  }
}
