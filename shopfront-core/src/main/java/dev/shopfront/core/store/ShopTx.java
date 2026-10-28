package dev.shopfront.core.store;

import com.fasterxml.jackson.databind.JsonNode;
import dev.shopfront.core.domain.Customer;
import dev.shopfront.core.domain.Email;
import dev.shopfront.core.domain.IdempotencyRecord;
import dev.shopfront.core.domain.Order;
import dev.shopfront.core.domain.OutboxRow;
import dev.shopfront.core.domain.Refund;
import dev.shopfront.core.domain.StoreCredit;
import dev.shopfront.core.domain.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Typed access to the Shopfront tables inside one transaction. */
public interface ShopTx {
  void deleteAll();

  Optional<Customer> customer(String id);

  void putCustomer(Customer c);

  List<Customer> customers();

  Optional<Order> order(String id);

  /** SELECT ... FOR UPDATE (serializes changes to one order, e.g. refunds and notes). */
  Optional<Order> lockOrder(String id);

  void putOrder(Order o);

  List<Order> orders();

  Optional<Ticket> ticket(String id);

  void putTicket(Ticket t);

  /** Ordered by id; status null = any; afterId exclusive cursor. */
  List<Ticket> tickets(String status, String afterId, int limit);

  Optional<Refund> refund(String id);

  void putRefund(Refund r);

  List<Refund> refundsByOrder(String orderId);

  List<Refund> refundsByUndoActionId(String undoActionId);

  /** updated_at in [from, to), ordered by (updated_at, id), after the cursor (exclusive). */
  Page<Refund> refundsUpdated(Instant from, Instant to, String cursor, int limit);

  /** pending refunds with settles_after <= now, oldest first. */
  List<Refund> refundsDue(Instant now, int limit);

  /** pending refunds oldest first; undoRunId null = any. */
  List<Refund> pendingRefunds(String undoRunId, int limit);

  List<Refund> refunds();

  Optional<StoreCredit> credit(String id);

  void putCredit(StoreCredit c);

  List<StoreCredit> creditsByReference(String reference);

  List<StoreCredit> creditsByCustomer(String customerId);

  Page<StoreCredit> creditsUpdated(Instant from, Instant to, String cursor, int limit);

  List<StoreCredit> credits();

  Optional<Email> email(String id);

  void putEmail(Email e);

  /** created_at in [from, to), ordered by (created_at, id). */
  Page<Email> emailsCreated(Instant from, Instant to, String cursor, int limit);

  List<Email> queuedEmailsBefore(Instant cutoff);

  List<Email> emails();

  /** Deletes the key if it was created before the cutoff (expired keys are treated as new). */
  void deleteExpiredKey(String key, Instant createdBefore);

  /**
   * INSERT ... ON CONFLICT DO NOTHING. True when this request now owns the key. A concurrent owner
   * makes this call wait until it commits.
   */
  boolean claimKey(String key, String fingerprint, Instant now);

  Optional<IdempotencyRecord> key(String key);

  void completeKey(String key, int status, String body);

  void insertOutbox(OutboxRow row);

  void putOutbox(OutboxRow row);

  Optional<OutboxRow> outboxRow(String id);

  /** Undelivered rows with attempts < MAX and next_attempt_at <= now, oldest first. */
  List<OutboxRow> outboxDue(Instant now, int limit);

  List<OutboxRow> outbox();

  JsonNode faults();

  void putFaults(JsonNode config);

  /** Small key/value table for seed metadata (carrier-bug selection). */
  Optional<JsonNode> meta(String key);

  void putMeta(String key, JsonNode value);
}
