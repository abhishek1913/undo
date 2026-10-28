package dev.undo.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ActionView;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.InvalidWebhookException;
import dev.undo.core.connector.ProviderStatusChange;
import dev.undo.core.ledger.Ledger;
import dev.undo.core.model.Action;
import dev.undo.core.model.WebhookEvent;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.TxContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Incoming provider webhooks (FR-WH-1..3). Ingest stores the event idempotently by (provider,
 * event_id) and the caller answers 200 after that durable insert; processing applies the status to
 * the action and writes postings. Duplicate events change nothing. Events that refer to an object
 * not yet linked to an action stay unprocessed and are retried.
 *
 * <p>Transactions: ingest = one insert TX, then process = one TX (crash point WEBHOOK_AFTER_INSERT
 * in between). {@link #applyStatusChange} runs inside the caller's TX and is shared with recon.
 */
public final class WebhookService {
  public enum Ingest {
    ACCEPTED,
    DUPLICATE
  }

  private final Ports p;

  public WebhookService(Ports p) {
    this.p = p;
  }

  public Ingest ingest(String connectorId, Map<String, String> headers, byte[] body)
      throws InvalidWebhookException {
    Connector c = p.registry().connector(connectorId);
    Instant now = p.clock().now();
    ProviderStatusChange change = c.parseWebhook(headers, body, now);
    WebhookEvent e = new WebhookEvent();
    e.provider = connectorId;
    e.eventId = change.eventId();
    e.type = change.eventType();
    e.payload = change.raw();
    e.receivedAt = now;
    boolean inserted = p.store().tx(tx -> tx.webhooks().insertIfAbsent(e));
    p.metrics()
        .count(
            "undo_webhooks_total", "type", e.type, "result", inserted ? "accepted" : "duplicate");
    if (!inserted) {
      return Ingest.DUPLICATE;
    }
    p.crash().hit(CrashPoint.WEBHOOK_AFTER_INSERT);
    try {
      process(connectorId, e.eventId);
    } catch (RuntimeException ex) {
      // The event is stored; the retry loop processes it later.
    }
    return Ingest.ACCEPTED;
  }

  /** Returns true when the event is (now or already) processed. TX: one. */
  public boolean process(String provider, String eventId) {
    return p.store()
        .tx(
            tx -> {
              Instant now = p.clock().now();
              WebhookEvent e = tx.webhooks().lock(provider, eventId);
              if (e.processedAt != null) {
                return true;
              }
              JsonNode obj = e.payload.path("data").path("object");
              String ref = obj.path("id").asText(null);
              String status = obj.path("status").asText(null);
              Action a =
                  ref == null ? null : tx.actions().byProviderRef(provider, ref).orElse(null);
              if (a == null) {
                e.processAttempts++;
                e.lastError = "no action is linked to " + ref + " yet";
                tx.webhooks().update(e);
                return false;
              }
              Action locked = tx.actions().lock(a.id);
              applyStatusChange(tx, locked, status, "webhook", eventId);
              e.processAttempts++;
              e.processedAt = now;
              e.lastError = null;
              tx.webhooks().update(e);
              return true;
            });
  }

  /** The webhook retry loop: every unprocessed event gets another try. */
  public int retryUnprocessed(int limit) {
    List<WebhookEvent> pending = p.store().tx(tx -> tx.webhooks().lockUnprocessed(limit));
    int done = 0;
    for (WebhookEvent e : pending) {
      try {
        if (process(e.provider, e.eventId)) {
          done++;
        }
      } catch (RuntimeException ex) {
        // try again next round
      }
    }
    return done;
  }

  /**
   * Applies a provider status to an action the caller has locked: provider_status, journal entries
   * (each (action, kind) at most once), event. Returns false when nothing changed.
   */
  public boolean applyStatusChange(
      TxContext tx, Action a, String newStatus, String source, String sourceRef) {
    if (newStatus == null || Objects.equals(a.providerStatus, newStatus)) {
      return false;
    }
    Instant now = p.clock().now();
    String old = a.providerStatus;
    a.providerStatus = newStatus;
    a.updatedAt = now;
    if (!tx.actions().update(a, a.status, a.leaseFence)) {
      throw new IllegalStateException("locked action changed during status change");
    }
    Connector c = p.registry().connector(a.connector);
    Ledger.write(
        tx,
        p.ids(),
        p.bugs(),
        now,
        a.runId,
        a.id,
        c.journalOnProviderStatus(ActionView.of(a), newStatus),
        source,
        sourceRef);
    ObjectNode d = EventLog.actionData(a);
    d.put("provider_status", newStatus);
    d.put("previous_provider_status", old);
    d.put("source", source);
    EventLog.append(tx, now, a.runId, a.id, "provider.status_changed", d);
    return true;
  }
}
