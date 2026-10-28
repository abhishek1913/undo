package dev.shopfront.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.shopfront.core.domain.Customer;
import dev.shopfront.core.domain.Email;
import dev.shopfront.core.domain.IdempotencyRecord;
import dev.shopfront.core.domain.Order;
import dev.shopfront.core.domain.OutboxRow;
import dev.shopfront.core.domain.Refund;
import dev.shopfront.core.domain.StoreCredit;
import dev.shopfront.core.domain.Ticket;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.store.Cursors;
import dev.shopfront.core.store.Page;
import dev.shopfront.core.store.ShopStore;
import dev.shopfront.core.store.ShopTx;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Postgres ShopStore: one tx() = one READ COMMITTED transaction. */
@Component
public class JdbcShopStore implements ShopStore {
  private final JdbcClient db;
  private final TransactionTemplate txt;

  public JdbcShopStore(JdbcClient db, TransactionTemplate txt) {
    this.db = db;
    this.txt = txt;
  }

  @Override
  public <T> T tx(Function<ShopTx, T> work) {
    return txt.execute(status -> work.apply(new Tx()));
  }

  static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  static Instant inst(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  static JsonNode json(ResultSet rs, String col) throws SQLException {
    String s = rs.getString(col);
    return s == null ? null : Json.parse(s.getBytes(StandardCharsets.UTF_8));
  }

  static String str(JsonNode n) {
    return n == null ? "null" : Json.string(n);
  }

  static final RowMapper<Customer> CUSTOMER =
      (rs, i) ->
          new Customer(
              rs.getString("id"),
              rs.getString("name"),
              rs.getString("email"),
              inst(rs, "created_at"));
  static final RowMapper<Order> ORDER =
      (rs, i) ->
          new Order(
              rs.getString("id"),
              rs.getString("customer_id"),
              rs.getString("item_name"),
              rs.getLong("total_minor"),
              rs.getString("currency"),
              rs.getString("status"),
              rs.getString("carrier_status"),
              inst(rs, "placed_at"),
              inst(rs, "delivered_at"),
              rs.getString("note"),
              rs.getInt("note_version"),
              rs.getString("carrier_bug_original"));
  static final RowMapper<Ticket> TICKET =
      (rs, i) ->
          new Ticket(
              rs.getString("id"),
              rs.getString("customer_id"),
              rs.getString("order_id"),
              rs.getString("category"),
              rs.getString("subject"),
              rs.getString("body"),
              rs.getString("status"),
              rs.getString("resolution"),
              inst(rs, "created_at"),
              inst(rs, "closed_at"));
  static final RowMapper<Refund> REFUND =
      (rs, i) ->
          new Refund(
              rs.getString("id"),
              rs.getString("order_id"),
              rs.getLong("amount_minor"),
              rs.getString("reason"),
              rs.getString("status"),
              json(rs, "metadata"),
              inst(rs, "created_at"),
              inst(rs, "settles_after"),
              inst(rs, "updated_at"));
  static final RowMapper<StoreCredit> CREDIT =
      (rs, i) ->
          new StoreCredit(
              rs.getString("id"),
              rs.getString("customer_id"),
              rs.getLong("amount_minor"),
              rs.getLong("balance_minor"),
              rs.getString("reason"),
              rs.getString("reference"),
              rs.getString("status"),
              rs.getLong("revoked_minor"),
              inst(rs, "created_at"),
              inst(rs, "updated_at"));
  static final RowMapper<Email> EMAIL =
      (rs, i) ->
          new Email(
              rs.getString("id"),
              rs.getString("customer_id"),
              rs.getString("template"),
              json(rs, "variables"),
              json(rs, "metadata"),
              rs.getString("status"),
              inst(rs, "created_at"));
  static final RowMapper<OutboxRow> OUTBOX =
      (rs, i) ->
          new OutboxRow(
              rs.getString("id"),
              rs.getString("type"),
              json(rs, "payload"),
              rs.getInt("attempts"),
              inst(rs, "next_attempt_at"),
              inst(rs, "delivered_at"),
              inst(rs, "created_at"));

  private final class Tx implements ShopTx {
    @Override
    public void deleteAll() {
      db.sql(
              "truncate customers, orders, tickets, refunds, store_credits, emails, idempotency_keys,"
                  + " webhook_outbox, shop_meta")
          .update();
    }

    @Override
    public Optional<Customer> customer(String id) {
      return db.sql("select * from customers where id = ?").param(id).query(CUSTOMER).optional();
    }

    @Override
    public void putCustomer(Customer c) {
      db.sql(
              """
              insert into customers(id, name, email, created_at) values (?, ?, ?, ?)
              on conflict (id) do update set name = excluded.name, email = excluded.email
              """)
          .params(c.id(), c.name(), c.email(), ts(c.createdAt()))
          .update();
    }

    @Override
    public List<Customer> customers() {
      return db.sql("select * from customers order by id").query(CUSTOMER).list();
    }

    @Override
    public Optional<Order> order(String id) {
      return db.sql("select * from orders where id = ?").param(id).query(ORDER).optional();
    }

    @Override
    public Optional<Order> lockOrder(String id) {
      return db.sql("select * from orders where id = ? for update")
          .param(id)
          .query(ORDER)
          .optional();
    }

    @Override
    public void putOrder(Order o) {
      db.sql(
              """
              insert into orders(id, customer_id, item_name, total_minor, currency, status, carrier_status,
                                 placed_at, delivered_at, note, note_version, carrier_bug_original)
              values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              on conflict (id) do update set customer_id = excluded.customer_id, item_name = excluded.item_name,
                total_minor = excluded.total_minor, status = excluded.status, carrier_status = excluded.carrier_status,
                delivered_at = excluded.delivered_at, note = excluded.note, note_version = excluded.note_version,
                carrier_bug_original = excluded.carrier_bug_original
              """)
          .params(
              o.id(),
              o.customerId(),
              o.itemName(),
              o.totalMinor(),
              o.currency(),
              o.status(),
              o.carrierStatus(),
              ts(o.placedAt()),
              ts(o.deliveredAt()),
              o.note(),
              o.noteVersion(),
              o.carrierBugOriginal())
          .update();
    }

    @Override
    public List<Order> orders() {
      return db.sql("select * from orders order by id").query(ORDER).list();
    }

    @Override
    public Optional<Ticket> ticket(String id) {
      return db.sql("select * from tickets where id = ?").param(id).query(TICKET).optional();
    }

    @Override
    public void putTicket(Ticket t) {
      db.sql(
              """
              insert into tickets(id, customer_id, order_id, category, subject, body, status, resolution, created_at, closed_at)
              values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              on conflict (id) do update set status = excluded.status, resolution = excluded.resolution,
                closed_at = excluded.closed_at
              """)
          .params(
              t.id(),
              t.customerId(),
              t.orderId(),
              t.category(),
              t.subject(),
              t.body(),
              t.status(),
              t.resolution(),
              ts(t.createdAt()),
              ts(t.closedAt()))
          .update();
    }

    @Override
    public List<Ticket> tickets(String status, String afterId, int limit) {
      return db.sql(
              """
              select * from tickets where (cast(? as text) is null or lower(status) = lower(?))
                and (cast(? as text) is null or id > ?) order by id limit ?
              """)
          .params(status, status, afterId, afterId, limit)
          .query(TICKET)
          .list();
    }

    @Override
    public Optional<Refund> refund(String id) {
      return db.sql("select * from refunds where id = ?").param(id).query(REFUND).optional();
    }

    @Override
    public void putRefund(Refund r) {
      db.sql(
              """
              insert into refunds(id, order_id, amount_minor, reason, status, metadata, created_at, settles_after, updated_at)
              values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
              on conflict (id) do update set status = excluded.status, updated_at = excluded.updated_at
              """)
          .params(
              r.id(),
              r.orderId(),
              r.amountMinor(),
              r.reason(),
              r.status(),
              str(r.metadata()),
              ts(r.createdAt()),
              ts(r.settlesAfter()),
              ts(r.updatedAt()))
          .update();
    }

    @Override
    public List<Refund> refundsByOrder(String orderId) {
      return db.sql("select * from refunds where order_id = ? order by created_at, id")
          .param(orderId)
          .query(REFUND)
          .list();
    }

    @Override
    public List<Refund> refundsByUndoActionId(String undoActionId) {
      return db.sql(
              "select * from refunds where metadata->>'undo_action_id' = ? order by created_at, id")
          .param(undoActionId)
          .query(REFUND)
          .list();
    }

    @Override
    public Page<Refund> refundsUpdated(Instant from, Instant to, String cursor, int limit) {
      List<Refund> rows =
          db.sql(
                  """
                  select * from refunds where updated_at >= ? and updated_at < ?
                    and (cast(? as timestamptz) is null or (updated_at, id) > (cast(? as timestamptz), ?))
                  order by updated_at, id limit ?
                  """)
              .params(
                  ts(from), ts(to), cursorTs(cursor), cursorTs(cursor), cursorId(cursor), limit + 1)
              .query(REFUND)
              .list();
      return page(rows, limit, r -> r.updatedAt() + "|" + r.id());
    }

    @Override
    public List<Refund> refundsDue(Instant now, int limit) {
      return db.sql(
              "select * from refunds where status = 'pending' and settles_after <= ? order by created_at, id limit ? for update skip locked")
          .params(ts(now), limit)
          .query(REFUND)
          .list();
    }

    @Override
    public List<Refund> pendingRefunds(String undoRunId, int limit) {
      return db.sql(
              """
              select * from refunds where status = 'pending'
                and (cast(? as text) is null or metadata->>'undo_run_id' = ?)
              order by created_at, id limit ? for update
              """)
          .params(undoRunId, undoRunId, limit)
          .query(REFUND)
          .list();
    }

    @Override
    public List<Refund> refunds() {
      return db.sql("select * from refunds order by created_at, id").query(REFUND).list();
    }

    @Override
    public Optional<StoreCredit> credit(String id) {
      return db.sql("select * from store_credits where id = ? for update")
          .param(id)
          .query(CREDIT)
          .optional();
    }

    @Override
    public void putCredit(StoreCredit c) {
      db.sql(
              """
              insert into store_credits(id, customer_id, amount_minor, balance_minor, reason, reference, status,
                                        revoked_minor, created_at, updated_at)
              values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              on conflict (id) do update set balance_minor = excluded.balance_minor, status = excluded.status,
                revoked_minor = excluded.revoked_minor, updated_at = excluded.updated_at
              """)
          .params(
              c.id(),
              c.customerId(),
              c.amountMinor(),
              c.balanceMinor(),
              c.reason(),
              c.reference(),
              c.status(),
              c.revokedMinor(),
              ts(c.createdAt()),
              ts(c.updatedAt()))
          .update();
    }

    @Override
    public List<StoreCredit> creditsByReference(String reference) {
      return db.sql("select * from store_credits where reference = ? order by created_at, id")
          .param(reference)
          .query(CREDIT)
          .list();
    }

    @Override
    public List<StoreCredit> creditsByCustomer(String customerId) {
      return db.sql("select * from store_credits where customer_id = ? order by created_at, id")
          .param(customerId)
          .query(CREDIT)
          .list();
    }

    @Override
    public Page<StoreCredit> creditsUpdated(Instant from, Instant to, String cursor, int limit) {
      List<StoreCredit> rows =
          db.sql(
                  """
                  select * from store_credits where updated_at >= ? and updated_at < ?
                    and (cast(? as timestamptz) is null or (updated_at, id) > (cast(? as timestamptz), ?))
                  order by updated_at, id limit ?
                  """)
              .params(
                  ts(from), ts(to), cursorTs(cursor), cursorTs(cursor), cursorId(cursor), limit + 1)
              .query(CREDIT)
              .list();
      return page(rows, limit, c -> c.updatedAt() + "|" + c.id());
    }

    @Override
    public List<StoreCredit> credits() {
      return db.sql("select * from store_credits order by created_at, id").query(CREDIT).list();
    }

    @Override
    public Optional<Email> email(String id) {
      return db.sql("select * from emails where id = ?").param(id).query(EMAIL).optional();
    }

    @Override
    public void putEmail(Email e) {
      db.sql(
              """
              insert into emails(id, customer_id, template, variables, metadata, status, created_at)
              values (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
              on conflict (id) do update set status = excluded.status
              """)
          .params(
              e.id(),
              e.customerId(),
              e.template(),
              str(e.variables()),
              str(e.metadata()),
              e.status(),
              ts(e.createdAt()))
          .update();
    }

    @Override
    public Page<Email> emailsCreated(Instant from, Instant to, String cursor, int limit) {
      List<Email> rows =
          db.sql(
                  """
                  select * from emails where created_at >= ? and created_at < ?
                    and (cast(? as timestamptz) is null or (created_at, id) > (cast(? as timestamptz), ?))
                  order by created_at, id limit ?
                  """)
              .params(
                  ts(from), ts(to), cursorTs(cursor), cursorTs(cursor), cursorId(cursor), limit + 1)
              .query(EMAIL)
              .list();
      return page(rows, limit, e -> e.createdAt() + "|" + e.id());
    }

    @Override
    public List<Email> queuedEmailsBefore(Instant cutoff) {
      return db.sql("select * from emails where status = 'queued' and created_at < ? limit 1000")
          .param(ts(cutoff))
          .query(EMAIL)
          .list();
    }

    @Override
    public List<Email> emails() {
      return db.sql("select * from emails order by created_at, id").query(EMAIL).list();
    }

    @Override
    public void deleteExpiredKey(String key, Instant createdBefore) {
      db.sql("delete from idempotency_keys where key = ? and created_at < ?")
          .params(key, ts(createdBefore))
          .update();
    }

    @Override
    public boolean claimKey(String key, String fingerprint, Instant now) {
      // A concurrent owner of the same key makes this insert wait until it commits.
      return db.sql(
                  """
                  insert into idempotency_keys(key, fingerprint, response_status, response_body, created_at)
                  values (?, ?, 0, '', ?) on conflict (key) do nothing
                  """)
              .params(key, fingerprint, ts(now))
              .update()
          == 1;
    }

    @Override
    public Optional<IdempotencyRecord> key(String key) {
      return db.sql("select * from idempotency_keys where key = ?")
          .param(key)
          .query(
              (rs, i) ->
                  new IdempotencyRecord(
                      rs.getString("key"),
                      rs.getString("fingerprint"),
                      rs.getInt("response_status"),
                      rs.getString("response_body"),
                      inst(rs, "created_at")))
          .optional();
    }

    @Override
    public void completeKey(String key, int status, String body) {
      db.sql("update idempotency_keys set response_status = ?, response_body = ? where key = ?")
          .params(status, body, key)
          .update();
    }

    @Override
    public void insertOutbox(OutboxRow row) {
      db.sql(
              """
              insert into webhook_outbox(id, type, payload, attempts, next_attempt_at, delivered_at, created_at)
              values (?, ?, ?::jsonb, ?, ?, ?, ?)
              """)
          .params(
              row.id(),
              row.type(),
              str(row.payload()),
              row.attempts(),
              ts(row.nextAttemptAt()),
              ts(row.deliveredAt()),
              ts(row.createdAt()))
          .update();
    }

    @Override
    public void putOutbox(OutboxRow row) {
      db.sql(
              "update webhook_outbox set attempts = ?, next_attempt_at = ?, delivered_at = ? where id = ?")
          .params(row.attempts(), ts(row.nextAttemptAt()), ts(row.deliveredAt()), row.id())
          .update();
    }

    @Override
    public Optional<OutboxRow> outboxRow(String id) {
      return db.sql("select * from webhook_outbox where id = ?").param(id).query(OUTBOX).optional();
    }

    @Override
    public List<OutboxRow> outboxDue(Instant now, int limit) {
      return db.sql(
              """
              select * from webhook_outbox where delivered_at is null and attempts < ? and next_attempt_at <= ?
              order by created_at, id limit ?
              """)
          .params(OutboxRow.MAX_ATTEMPTS, ts(now), limit)
          .query(OUTBOX)
          .list();
    }

    @Override
    public List<OutboxRow> outbox() {
      return db.sql("select * from webhook_outbox order by created_at, id").query(OUTBOX).list();
    }

    @Override
    public JsonNode faults() {
      return db.sql("select config from fault_config where id = 1")
          .query((rs, i) -> json(rs, "config"))
          .optional()
          .orElse(null);
    }

    @Override
    public void putFaults(JsonNode config) {
      db.sql(
              "insert into fault_config(id, config) values (1, ?::jsonb) on conflict (id) do update set config = excluded.config")
          .param(str(config))
          .update();
    }

    @Override
    public Optional<JsonNode> meta(String key) {
      return db.sql("select value from shop_meta where key = ?")
          .param(key)
          .query((rs, i) -> json(rs, "value"))
          .optional();
    }

    @Override
    public void putMeta(String key, JsonNode value) {
      db.sql(
              "insert into shop_meta(key, value) values (?, ?::jsonb) on conflict (key) do update set value = excluded.value")
          .params(key, str(value))
          .update();
    }
  }

  private static Timestamp cursorTs(String cursor) {
    return cursor == null ? null : Timestamp.from(Cursors.time(cursor));
  }

  private static String cursorId(String cursor) {
    return cursor == null ? "" : Cursors.id(cursor);
  }

  private static <T> Page<T> page(List<T> rows, int limit, Function<T, String> cursorOf) {
    if (rows.size() > limit) {
      List<T> items = rows.subList(0, limit);
      return new Page<>(items, cursorOf.apply(items.get(limit - 1)));
    }
    return new Page<>(rows, null);
  }
}
