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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * In-memory Shopfront store for the simulator and tests. Transactions are serialized and rolled
 * back with an undo log when the work throws.
 */
public final class InMemoryShopStore implements ShopStore {
  private final TreeMap<String, Customer> customers = new TreeMap<>();
  private final TreeMap<String, Order> orders = new TreeMap<>();
  private final TreeMap<String, Ticket> tickets = new TreeMap<>();
  private final TreeMap<String, Refund> refunds = new TreeMap<>();
  private final TreeMap<String, StoreCredit> credits = new TreeMap<>();
  private final TreeMap<String, Email> emails = new TreeMap<>();
  private final TreeMap<String, IdempotencyRecord> keys = new TreeMap<>();
  private final TreeMap<String, OutboxRow> outbox = new TreeMap<>();

  /** Undelivered rows that may still be retried: the only ones the delivery job looks at. */
  private final TreeMap<String, OutboxRow> pendingOutbox = new TreeMap<>();

  private final TreeMap<String, JsonNode> meta = new TreeMap<>();
  private JsonNode faults;

  private final Deque<Runnable> undo = new ArrayDeque<>();

  @Override
  public synchronized <T> T tx(Function<ShopTx, T> work) {
    undo.clear();
    try {
      return work.apply(new Tx());
    } catch (RuntimeException | Error e) {
      while (!undo.isEmpty()) {
        undo.pop().run();
      }
      throw e;
    } finally {
      undo.clear();
    }
  }

  private <V> void put(TreeMap<String, V> map, String key, V value) {
    V old = map.put(key, value);
    undo.push(
        () -> {
          if (old == null) {
            map.remove(key);
          } else {
            map.put(key, old);
          }
        });
  }

  private <V> void remove(TreeMap<String, V> map, String key) {
    V old = map.remove(key);
    if (old != null) {
      undo.push(() -> map.put(key, old));
    }
  }

  private static <V> List<V> filter(Map<String, V> map, Predicate<V> p) {
    List<V> out = new ArrayList<>();
    for (V v : map.values()) {
      if (p.test(v)) {
        out.add(v);
      }
    }
    return out;
  }

  private static <V> Page<V> page(
      List<V> rows,
      Function<V, Instant> time,
      Function<V, String> id,
      Instant from,
      Instant to,
      String cursor,
      int limit) {
    List<V> sorted = new ArrayList<>();
    for (V v : rows) {
      Instant t = time.apply(v);
      if (!t.isBefore(from) && t.isBefore(to)) {
        sorted.add(v);
      }
    }
    Comparator<V> order = Comparator.comparing(time).thenComparing(id);
    sorted.sort(order);
    List<V> out = new ArrayList<>();
    for (V v : sorted) {
      String c = time.apply(v) + "|" + id.apply(v);
      if (cursor != null && Cursors.compare(c, cursor) <= 0) {
        continue;
      }
      if (out.size() == limit) {
        V last = out.get(out.size() - 1);
        return new Page<>(out, time.apply(last) + "|" + id.apply(last));
      }
      out.add(v);
    }
    return new Page<>(out, null);
  }

  private final class Tx implements ShopTx {
    @Override
    public void deleteAll() {
      for (TreeMap<String, ?> m :
          List.of(
              customers,
              orders,
              tickets,
              refunds,
              credits,
              emails,
              keys,
              outbox,
              pendingOutbox,
              meta)) {
        for (String k : new ArrayList<>(m.keySet())) {
          remove(m, k);
        }
      }
    }

    @Override
    public Optional<Customer> customer(String id) {
      return Optional.ofNullable(customers.get(id));
    }

    @Override
    public void putCustomer(Customer c) {
      put(customers, c.id(), c);
    }

    @Override
    public List<Customer> customers() {
      return new ArrayList<>(customers.values());
    }

    @Override
    public Optional<Order> order(String id) {
      return Optional.ofNullable(orders.get(id));
    }

    @Override
    public Optional<Order> lockOrder(String id) {
      return order(id);
    }

    @Override
    public void putOrder(Order o) {
      put(orders, o.id(), o);
    }

    @Override
    public List<Order> orders() {
      return new ArrayList<>(orders.values());
    }

    @Override
    public Optional<Ticket> ticket(String id) {
      return Optional.ofNullable(tickets.get(id));
    }

    @Override
    public void putTicket(Ticket t) {
      put(tickets, t.id(), t);
    }

    @Override
    public List<Ticket> tickets(String status, String afterId, int limit) {
      List<Ticket> out = new ArrayList<>();
      Map<String, Ticket> tail = afterId == null ? tickets : tickets.tailMap(afterId, false);
      for (Ticket t : tail.values()) {
        if (status == null || t.status().equalsIgnoreCase(status)) {
          out.add(t);
          if (out.size() == limit) {
            break;
          }
        }
      }
      return out;
    }

    @Override
    public Optional<Refund> refund(String id) {
      return Optional.ofNullable(refunds.get(id));
    }

    @Override
    public void putRefund(Refund r) {
      put(refunds, r.id(), r);
    }

    @Override
    public List<Refund> refundsByOrder(String orderId) {
      return filter(refunds, r -> r.orderId().equals(orderId));
    }

    @Override
    public List<Refund> refundsByUndoActionId(String undoActionId) {
      return filter(refunds, r -> undoActionId.equals(r.undoActionId()));
    }

    @Override
    public Page<Refund> refundsUpdated(Instant from, Instant to, String cursor, int limit) {
      return page(
          new ArrayList<>(refunds.values()),
          Refund::updatedAt,
          Refund::id,
          from,
          to,
          cursor,
          limit);
    }

    @Override
    public List<Refund> refundsDue(Instant now, int limit) {
      List<Refund> due =
          filter(refunds, r -> r.status().equals("pending") && !r.settlesAfter().isAfter(now));
      due.sort(Comparator.comparing(Refund::createdAt).thenComparing(Refund::id));
      return due.size() > limit ? due.subList(0, limit) : due;
    }

    @Override
    public List<Refund> pendingRefunds(String undoRunId, int limit) {
      List<Refund> due =
          filter(
              refunds,
              r ->
                  r.status().equals("pending")
                      && (undoRunId == null || undoRunId.equals(r.undoRunId())));
      due.sort(Comparator.comparing(Refund::createdAt).thenComparing(Refund::id));
      return due.size() > limit ? due.subList(0, limit) : due;
    }

    @Override
    public List<Refund> refunds() {
      return new ArrayList<>(refunds.values());
    }

    @Override
    public Optional<StoreCredit> credit(String id) {
      return Optional.ofNullable(credits.get(id));
    }

    @Override
    public void putCredit(StoreCredit c) {
      put(credits, c.id(), c);
    }

    @Override
    public List<StoreCredit> creditsByReference(String reference) {
      return filter(credits, c -> reference.equals(c.reference()));
    }

    @Override
    public List<StoreCredit> creditsByCustomer(String customerId) {
      return filter(credits, c -> c.customerId().equals(customerId));
    }

    @Override
    public Page<StoreCredit> creditsUpdated(Instant from, Instant to, String cursor, int limit) {
      return page(
          new ArrayList<>(credits.values()),
          StoreCredit::updatedAt,
          StoreCredit::id,
          from,
          to,
          cursor,
          limit);
    }

    @Override
    public List<StoreCredit> credits() {
      return new ArrayList<>(credits.values());
    }

    @Override
    public Optional<Email> email(String id) {
      return Optional.ofNullable(emails.get(id));
    }

    @Override
    public void putEmail(Email e) {
      put(emails, e.id(), e);
    }

    @Override
    public Page<Email> emailsCreated(Instant from, Instant to, String cursor, int limit) {
      return page(
          new ArrayList<>(emails.values()), Email::createdAt, Email::id, from, to, cursor, limit);
    }

    @Override
    public List<Email> queuedEmailsBefore(Instant cutoff) {
      return filter(emails, e -> e.status().equals("queued") && e.createdAt().isBefore(cutoff));
    }

    @Override
    public List<Email> emails() {
      return new ArrayList<>(emails.values());
    }

    @Override
    public void deleteExpiredKey(String key, Instant createdBefore) {
      IdempotencyRecord r = keys.get(key);
      if (r != null && r.createdAt().isBefore(createdBefore)) {
        remove(keys, key);
      }
    }

    @Override
    public boolean claimKey(String key, String fingerprint, Instant now) {
      if (keys.containsKey(key)) {
        return false;
      }
      put(keys, key, new IdempotencyRecord(key, fingerprint, 0, null, now));
      return true;
    }

    @Override
    public Optional<IdempotencyRecord> key(String key) {
      return Optional.ofNullable(keys.get(key));
    }

    @Override
    public void completeKey(String key, int status, String body) {
      IdempotencyRecord r = keys.get(key);
      put(keys, key, new IdempotencyRecord(key, r.fingerprint(), status, body, r.createdAt()));
    }

    @Override
    public void insertOutbox(OutboxRow row) {
      putOutbox(row);
    }

    @Override
    public void putOutbox(OutboxRow row) {
      put(outbox, row.id(), row);
      if (row.deliveredAt() == null && row.attempts() < OutboxRow.MAX_ATTEMPTS) {
        put(pendingOutbox, row.id(), row);
      } else {
        remove(pendingOutbox, row.id());
      }
    }

    @Override
    public Optional<OutboxRow> outboxRow(String id) {
      return Optional.ofNullable(outbox.get(id));
    }

    @Override
    public List<OutboxRow> outboxDue(Instant now, int limit) {
      List<OutboxRow> due = filter(pendingOutbox, r -> !r.nextAttemptAt().isAfter(now));
      due.sort(Comparator.comparing(OutboxRow::createdAt).thenComparing(OutboxRow::id));
      return due.size() > limit ? due.subList(0, limit) : due;
    }

    @Override
    public List<OutboxRow> outbox() {
      return new ArrayList<>(outbox.values());
    }

    @Override
    public JsonNode faults() {
      return faults;
    }

    @Override
    public void putFaults(JsonNode config) {
      JsonNode old = faults;
      faults = config;
      undo.push(() -> faults = old);
    }

    @Override
    public Optional<JsonNode> meta(String key) {
      return Optional.ofNullable(meta.get(key));
    }

    @Override
    public void putMeta(String key, JsonNode value) {
      put(meta, key, value);
    }
  }
}
