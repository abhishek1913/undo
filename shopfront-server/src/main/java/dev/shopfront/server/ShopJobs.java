package dev.shopfront.server;

import dev.shopfront.core.domain.OutboxRow;
import dev.shopfront.core.faults.FaultConfig;
import dev.shopfront.core.port.ShopClock;
import dev.shopfront.core.port.ShopRandom;
import dev.shopfront.core.settlement.Settlement;
import dev.shopfront.core.store.ShopTx;
import dev.shopfront.core.webhooks.WebhookOutbox;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Settlement every 5 s and webhook delivery every 1 s (Section 12.1). */
@Component
public class ShopJobs {
  private static final Logger log = LoggerFactory.getLogger(ShopJobs.class);
  private final JdbcShopStore store;
  private final ShopClock clock;
  private final ShopRandom random;
  private final ShopProperties props;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  public ShopJobs(JdbcShopStore store, ShopClock clock, ShopRandom random, ShopProperties props) {
    this.store = store;
    this.clock = clock;
    this.random = random;
    this.props = props;
  }

  @Scheduled(fixedDelay = 5000)
  public void settle() {
    if (!props.jobsEnabled()) {
      return;
    }
    try {
      store.tx(tx -> Settlement.runDue(tx, random, clock.now(), FaultConfig.fromJson(tx.faults())));
    } catch (RuntimeException e) {
      log.warn("settlement failed: {}", e.toString());
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void deliverWebhooks() {
    if (!props.jobsEnabled()) {
      return;
    }
    List<OutboxRow> due;
    FaultConfig faults;
    try {
      store.tx(
          tx -> {
            Settlement.sendEmails(tx, clock.now());
            return null;
          });
      due = store.tx(tx -> WebhookOutbox.due(tx, clock.now(), 100));
      faults = FaultConfig.fromJson(store.tx(ShopTx::faults));
    } catch (RuntimeException e) {
      log.warn("webhook poll failed: {}", e.toString());
      return;
    }
    for (OutboxRow row : due) {
      if (!inFlight.add(row.id())) {
        continue;
      }
      WebhookOutbox.Delivery plan = WebhookOutbox.plan(faults, random);
      Thread.startVirtualThread(() -> deliver(row, plan));
    }
  }

  private void deliver(OutboxRow row, WebhookOutbox.Delivery plan) {
    try {
      if (plan.drop()) {
        store.tx(
            tx -> {
              WebhookOutbox.markDelivered(tx, row.id(), clock.now());
              return null;
            });
        return;
      }
      if (plan.delayMs() > 0) {
        Thread.sleep(plan.delayMs());
      }
      boolean ok = false;
      for (int i = 0; i < plan.copies(); i++) {
        ok |= send(row);
      }
      boolean delivered = ok;
      store.tx(
          tx -> {
            if (delivered) {
              WebhookOutbox.markDelivered(tx, row.id(), clock.now());
            } else {
              WebhookOutbox.markFailed(tx, row.id(), clock.now());
            }
            return null;
          });
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      log.warn("webhook {} failed: {}", row.id(), e.toString());
    } finally {
      inFlight.remove(row.id());
    }
  }

  private boolean send(OutboxRow row) {
    byte[] body = WebhookOutbox.body(row);
    Map<String, String> headers =
        WebhookOutbox.headers(row, body, props.webhookSecret(), clock.now());
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(props.webhookUrl()))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(b::header);
    try {
      HttpResponse<Void> r = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
      return r.statusCode() / 100 == 2;
    } catch (Exception e) {
      return false;
    }
  }
}
