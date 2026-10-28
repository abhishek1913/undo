package dev.undo.core.port.repo;

import dev.undo.core.model.WebhookEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebhookRepo {
  /** INSERT ... ON CONFLICT (provider, event_id) DO NOTHING. True when inserted. */
  boolean insertIfAbsent(WebhookEvent event);

  Optional<WebhookEvent> get(String provider, String eventId);

  /** SELECT ... FOR UPDATE. */
  WebhookEvent lock(String provider, String eventId);

  void update(WebhookEvent event);

  /** Unprocessed, oldest first, FOR UPDATE SKIP LOCKED. */
  List<WebhookEvent> lockUnprocessed(int limit);

  List<WebhookEvent> unprocessedReceivedBefore(Instant cutoff);
}
