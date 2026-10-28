package dev.shopfront.core.settlement;

import dev.shopfront.core.domain.Email;
import dev.shopfront.core.domain.Refund;
import dev.shopfront.core.faults.FaultConfig;
import dev.shopfront.core.port.ShopRandom;
import dev.shopfront.core.router.Views;
import dev.shopfront.core.store.ShopTx;
import dev.shopfront.core.webhooks.WebhookOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Async settlement of refunds and sending of queued emails. */
public final class Settlement {
  private Settlement() {}

  /** Scheduled job: settles due refunds (some fail under the fault rate) and sends emails. */
  public static int runDue(ShopTx tx, ShopRandom random, Instant now, FaultConfig faults) {
    int n = 0;
    for (Refund r : tx.refundsDue(now, 1000)) {
      boolean fail = random.nextDouble() < faults.settlementFailureRate();
      finish(tx, random, now, r, fail ? "failed" : "settled");
      n++;
    }
    sendEmails(tx, now);
    return n;
  }

  /** Queued emails become sent one second after they were accepted. */
  public static void sendEmails(ShopTx tx, Instant now) {
    for (Email e : tx.queuedEmailsBefore(now.minus(Duration.ofSeconds(1)))) {
      tx.putEmail(e.sent());
    }
  }

  /** Admin: settle the N oldest pending refunds now (optionally only one Undo run's). */
  public static List<String> settleNow(
      ShopTx tx, ShopRandom random, Instant now, int limit, String undoRunId) {
    List<String> ids = new ArrayList<>();
    for (Refund r : tx.pendingRefunds(undoRunId, limit)) {
      finish(tx, random, now, r, "settled");
      ids.add(r.id());
    }
    return ids;
  }

  private static void finish(ShopTx tx, ShopRandom random, Instant now, Refund r, String status) {
    Refund done = r.withStatus(status, now);
    tx.putRefund(done);
    WebhookOutbox.enqueue(tx, random, now, "refund." + status, Views.refund(done));
  }
}
