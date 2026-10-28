package dev.undo.server.store;

import static dev.undo.server.store.Rows.*;

import dev.undo.core.model.WebhookEvent;
import dev.undo.core.port.repo.WebhookRepo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PgWebhookRepo implements WebhookRepo {
  private final JdbcClient db;

  PgWebhookRepo(JdbcClient db) {
    this.db = db;
  }

  @Override
  public boolean insertIfAbsent(WebhookEvent e) {
    return db.sql(
                """
                insert into webhook_events(provider, event_id, type, payload, received_at, processed_at, process_attempts, last_error)
                values (?, ?, ?, ?::jsonb, ?, ?, ?, ?) on conflict (provider, event_id) do nothing
                """)
            .params(
                e.provider,
                e.eventId,
                e.type,
                json(e.payload),
                ts(e.receivedAt),
                ts(e.processedAt),
                e.processAttempts,
                e.lastError)
            .update()
        == 1;
  }

  @Override
  public Optional<WebhookEvent> get(String provider, String eventId) {
    return db.sql("select * from webhook_events where provider = ? and event_id = ?")
        .params(provider, eventId)
        .query(WEBHOOK)
        .optional();
  }

  @Override
  public WebhookEvent lock(String provider, String eventId) {
    return db.sql("select * from webhook_events where provider = ? and event_id = ? for update")
        .params(provider, eventId)
        .query(WEBHOOK)
        .optional()
        .orElseThrow(() -> new IllegalStateException("no webhook " + eventId));
  }

  @Override
  public void update(WebhookEvent e) {
    db.sql(
            "update webhook_events set processed_at = ?, process_attempts = ?, last_error = ? where provider = ? and event_id = ?")
        .params(ts(e.processedAt), e.processAttempts, e.lastError, e.provider, e.eventId)
        .update();
  }

  @Override
  public List<WebhookEvent> lockUnprocessed(int limit) {
    return db.sql(
            "select * from webhook_events where processed_at is null order by received_at limit ? for update skip locked")
        .param(limit)
        .query(WEBHOOK)
        .list();
  }

  @Override
  public List<WebhookEvent> unprocessedReceivedBefore(Instant cutoff) {
    return db.sql(
            "select * from webhook_events where processed_at is null and received_at < ? order by received_at")
        .param(ts(cutoff))
        .query(WEBHOOK)
        .list();
  }
}
