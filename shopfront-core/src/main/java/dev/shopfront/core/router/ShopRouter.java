package dev.shopfront.core.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.shopfront.core.domain.Customer;
import dev.shopfront.core.domain.Email;
import dev.shopfront.core.domain.IdempotencyRecord;
import dev.shopfront.core.domain.Order;
import dev.shopfront.core.domain.Refund;
import dev.shopfront.core.domain.StoreCredit;
import dev.shopfront.core.domain.Ticket;
import dev.shopfront.core.faults.FaultConfig;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.port.ShopClock;
import dev.shopfront.core.port.ShopConfig;
import dev.shopfront.core.port.ShopRandom;
import dev.shopfront.core.seed.SeedData;
import dev.shopfront.core.seed.SeedGenerator;
import dev.shopfront.core.settlement.Settlement;
import dev.shopfront.core.store.Page;
import dev.shopfront.core.store.ShopStore;
import dev.shopfront.core.store.ShopTx;
import dev.shopfront.core.webhooks.WebhookOutbox;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Implements every Shopfront endpoint (Sections 12.3 and 12.6). The same router runs in
 * shopfront-server and in the simulator, so provider behavior is identical in both worlds.
 */
public final class ShopRouter {
  private static final Set<String> TEMPLATES =
      Set.of(
          "refund_confirmation",
          "store_credit_issued",
          "need_more_info",
          "ticket_closed",
          "correction");
  private static final Set<String> REFUND_REASONS =
      Set.of("damaged", "not_received", "wrong_item", "lost_in_transit", "other");

  private final ShopStore store;
  private final ShopClock clock;
  private final ShopRandom random;
  private final ShopConfig config;

  public ShopRouter(ShopStore store, ShopClock clock, ShopRandom random, ShopConfig config) {
    this.store = store;
    this.clock = clock;
    this.random = random;
    this.config = config;
  }

  public ShopConfig config() {
    return config;
  }

  public ShopStore store() {
    return store;
  }

  public ShopResponse handle(ShopRequest req) {
    try {
      String raw = req.path();
      int q = raw.indexOf('?');
      String path = q < 0 ? raw : raw.substring(0, q);
      Map<String, String> query = q < 0 ? Map.of() : parseQuery(raw.substring(q + 1));
      String[] seg = path.replaceAll("^/+|/+$", "").split("/");
      if (seg.length >= 1 && seg[0].equals("v1")) {
        requireBearer(req);
        return v1(req, seg, query, path);
      }
      if (seg.length >= 1 && seg[0].equals("admin")) {
        if (!config.adminToken().equals(req.header("x-admin-token"))) {
          throw new HttpError(401, "unauthorized", "Missing or wrong X-Admin-Token.");
        }
        return admin(req, seg, query);
      }
      throw new HttpError(404, "not_found", "No such endpoint.");
    } catch (HttpError e) {
      return json(e.status, e.body());
    } catch (IllegalArgumentException e) {
      return json(400, new HttpError(400, "invalid_request", e.getMessage()).body());
    }
  }

  // ── /v1 ──────────────────────────────────────────────────────────────

  private void requireBearer(ShopRequest req) {
    String auth = req.header("authorization");
    if (auth == null || !auth.equals("Bearer " + config.apiKey())) {
      throw new HttpError(401, "unauthorized", "Missing or wrong API key.");
    }
  }

  private ShopResponse v1(ShopRequest req, String[] s, Map<String, String> q, String path) {
    String m = req.method();
    String r = s.length > 1 ? s[1] : "";
    int n = s.length;
    switch (r) {
      case "customers" -> {
        if (m.equals("GET") && n == 3) {
          return tx(tx -> json(200, customerView(tx, s[2])));
        }
      }
      case "orders" -> {
        if (m.equals("GET") && n == 3) {
          return tx(
              tx -> {
                Order o = order(tx, s[2]);
                return json(200, Views.order(o, tx.refundsByOrder(o.id())), etag(o));
              });
        }
        if (m.equals("PUT") && n == 4 && s[3].equals("note")) {
          return tx(tx -> setNote(tx, s[2], req));
        }
      }
      case "tickets" -> {
        if (m.equals("GET") && n == 2) {
          return tx(tx -> listTickets(tx, q));
        }
        if (m.equals("GET") && n == 3) {
          return tx(tx -> json(200, Views.ticket(ticket(tx, s[2]))));
        }
        if (m.equals("POST") && n == 4 && s[3].equals("close")) {
          return tx(tx -> closeTicket(tx, s[2], body(req)));
        }
        if (m.equals("POST") && n == 4 && s[3].equals("reopen")) {
          return tx(tx -> reopenTicket(tx, s[2], body(req)));
        }
      }
      case "refunds" -> {
        if (m.equals("POST") && n == 2) {
          return idempotent(req, path, tx -> createRefund(tx, body(req)));
        }
        if (m.equals("GET") && n == 2) {
          return tx(tx -> listRefunds(tx, q));
        }
        if (m.equals("GET") && n == 3) {
          return tx(tx -> json(200, Views.refund(refund(tx, s[2]))));
        }
        if (m.equals("POST") && n == 4 && s[3].equals("cancel")) {
          return idempotent(req, path, tx -> cancelRefund(tx, s[2]));
        }
      }
      case "store-credits" -> {
        if (m.equals("POST") && n == 2) {
          // No idempotency: an Idempotency-Key header is ignored on purpose (Section 12.3).
          return tx(tx -> createCredit(tx, body(req)));
        }
        if (m.equals("GET") && n == 2) {
          return tx(tx -> listCredits(tx, q));
        }
        if (m.equals("GET") && n == 3) {
          return tx(tx -> json(200, Views.credit(credit(tx, s[2]))));
        }
        if (m.equals("POST") && n == 4 && s[3].equals("revoke")) {
          return idempotent(req, path, tx -> revokeCredit(tx, s[2]));
        }
      }
      case "emails" -> {
        if (m.equals("POST") && n == 2) {
          return tx(tx -> createEmail(tx, body(req)));
        }
      }
      case "exports" -> {
        if (m.equals("GET") && n == 3 && s[2].equals("emails")) {
          return tx(tx -> exportEmails(tx, q));
        }
      }
      default -> {}
    }
    throw new HttpError(404, "not_found", "No such endpoint.");
  }

  private ObjectNode customerView(ShopTx tx, String id) {
    Customer c =
        tx.customer(id)
            .orElseThrow(() -> new HttpError(404, "customer_not_found", "No customer " + id));
    return Views.customer(c, tx.creditsByCustomer(id));
  }

  private static Map<String, String> etag(Order o) {
    return Map.of("ETag", "\"v" + o.noteVersion() + "\"");
  }

  private ShopResponse setNote(ShopTx tx, String id, ShopRequest req) {
    String ifMatch = req.header("if-match");
    if (ifMatch == null) {
      throw new HttpError(428, "if_match_required", "Send If-Match: \"v<note_version>\".");
    }
    JsonNode b = body(req);
    if (!b.has("note") || !(b.get("note").isTextual() || b.get("note").isNull())) {
      throw new IllegalArgumentException("note must be a string or null");
    }
    Order o = tx.lockOrder(id).orElseThrow(() -> orderNotFound(id));
    String expected = "\"v" + o.noteVersion() + "\"";
    if (!ifMatch.trim().equals(expected)) {
      ObjectNode d = Json.obj();
      d.put("note_version", o.noteVersion());
      throw new HttpError(412, "precondition_failed", "The note changed since you read it.", d);
    }
    Order updated = o.withNote(b.get("note").isNull() ? null : b.get("note").asText());
    tx.putOrder(updated);
    return json(200, Views.order(updated, tx.refundsByOrder(id)), etag(updated));
  }

  private ShopResponse listTickets(ShopTx tx, Map<String, String> q) {
    int limit = intParam(q, "limit", 20, 1, 100);
    String status = q.get("status");
    List<Ticket> rows = tx.tickets(status, q.get("cursor"), limit + 1);
    ArrayNode data = Json.arr();
    for (int i = 0; i < Math.min(limit, rows.size()); i++) {
      data.add(Views.ticket(rows.get(i)));
    }
    ObjectNode out = Json.obj();
    out.set("data", data);
    out.put("next_cursor", rows.size() > limit ? rows.get(limit - 1).id() : null);
    return json(200, out);
  }

  private ShopResponse closeTicket(ShopTx tx, String id, JsonNode b) {
    String resolution = requiredText(b, "resolution");
    Ticket t = ticket(tx, id);
    if (t.status().equals("CLOSED")) {
      if (resolution.equals(t.resolution())) {
        return json(200, Views.ticket(t));
      }
      throw new HttpError(
          409, "ticket_resolution_conflict", "Ticket is already closed as " + t.resolution());
    }
    Ticket closed = t.closed(resolution, clock.now());
    tx.putTicket(closed);
    return json(200, Views.ticket(closed));
  }

  private ShopResponse reopenTicket(ShopTx tx, String id, JsonNode b) {
    String expected = requiredText(b, "expected_resolution");
    Ticket t = ticket(tx, id);
    if (t.status().equals("OPEN")) {
      return json(200, Views.ticket(t));
    }
    if (!expected.equals(t.resolution())) {
      throw new HttpError(
          409,
          "ticket_state_conflict",
          "Ticket was closed as " + t.resolution() + ", not " + expected);
    }
    Ticket open = t.reopened();
    tx.putTicket(open);
    return json(200, Views.ticket(open));
  }

  private ShopResponse createRefund(ShopTx tx, JsonNode b) {
    String orderId = requiredText(b, "order_id");
    long amount = requiredPositiveLong(b, "amount_minor");
    String reason = requiredText(b, "reason");
    if (!REFUND_REASONS.contains(reason)) {
      throw new IllegalArgumentException("unknown reason " + reason);
    }
    JsonNode metadata = b.path("metadata");
    if (!metadata.isObject()) {
      metadata = Json.obj();
    }
    Order o = tx.lockOrder(orderId).orElseThrow(() -> orderNotFound(orderId));
    long refundable = o.totalMinor() - Views.refunded(tx.refundsByOrder(orderId));
    if (amount > refundable) {
      ObjectNode d = Json.obj();
      d.put("refundable_minor", refundable);
      throw new HttpError(
          422, "amount_exceeds_refundable", "Amount is more than the refundable amount.", d);
    }
    Instant now = clock.now();
    Refund r =
        new Refund(
            "RF-" + random.base32(8),
            orderId,
            amount,
            reason,
            "pending",
            metadata,
            now,
            now.plus(config.settlementDelay()),
            now);
    tx.putRefund(r);
    return json(201, Views.refund(r));
  }

  private ShopResponse listRefunds(ShopTx tx, Map<String, String> q) {
    List<Refund> rows;
    String next = null;
    if (q.containsKey("metadata.undo_action_id")) {
      rows = tx.refundsByUndoActionId(q.get("metadata.undo_action_id"));
    } else if (q.containsKey("order_id")) {
      rows = tx.refundsByOrder(q.get("order_id"));
    } else if (q.containsKey("updated_from") && q.containsKey("updated_to")) {
      Page<Refund> p =
          tx.refundsUpdated(
              Instant.parse(q.get("updated_from")),
              Instant.parse(q.get("updated_to")),
              q.get("cursor"),
              intParam(q, "limit", 100, 1, 500));
      rows = p.items();
      next = p.nextCursor();
    } else {
      throw new IllegalArgumentException(
          "filter by metadata.undo_action_id, order_id or updated_from/updated_to");
    }
    ArrayNode data = Json.arr();
    rows.forEach(r -> data.add(Views.refund(r)));
    return list(data, next);
  }

  private ShopResponse cancelRefund(ShopTx tx, String id) {
    Refund r = refund(tx, id);
    tx.lockOrder(r.orderId());
    r = refund(tx, id);
    switch (r.status()) {
      case "cancelled" -> {
        return json(200, Views.refund(r));
      }
      case "pending" -> {
        Refund c = r.withStatus("cancelled", clock.now());
        tx.putRefund(c);
        WebhookOutbox.enqueue(tx, random, clock.now(), "refund.cancelled", Views.refund(c));
        return json(200, Views.refund(c));
      }
      default ->
          throw new HttpError(
              409,
              "refund_not_cancellable",
              "Refund is " + r.status() + " and cannot be cancelled.");
    }
  }

  private ShopResponse createCredit(ShopTx tx, JsonNode b) {
    String customerId = requiredText(b, "customer_id");
    long amount = requiredPositiveLong(b, "amount_minor");
    String reason = requiredText(b, "reason");
    String reference = b.path("reference").isTextual() ? b.get("reference").asText() : null;
    tx.customer(customerId)
        .orElseThrow(() -> new HttpError(404, "customer_not_found", "No customer " + customerId));
    Instant now = clock.now();
    StoreCredit c =
        new StoreCredit(
            "SC-" + random.base32(8),
            customerId,
            amount,
            amount,
            reason,
            reference,
            "active",
            0,
            now,
            now);
    tx.putCredit(c);
    return json(201, Views.credit(c));
  }

  private ShopResponse listCredits(ShopTx tx, Map<String, String> q) {
    List<StoreCredit> rows;
    String next = null;
    if (q.containsKey("reference")) {
      rows = tx.creditsByReference(q.get("reference"));
    } else if (q.containsKey("updated_from") && q.containsKey("updated_to")) {
      Page<StoreCredit> p =
          tx.creditsUpdated(
              Instant.parse(q.get("updated_from")),
              Instant.parse(q.get("updated_to")),
              q.get("cursor"),
              intParam(q, "limit", 100, 1, 500));
      rows = p.items();
      next = p.nextCursor();
    } else {
      throw new IllegalArgumentException("filter by reference or updated_from/updated_to");
    }
    ArrayNode data = Json.arr();
    rows.forEach(c -> data.add(Views.credit(c)));
    return list(data, next);
  }

  private ShopResponse revokeCredit(ShopTx tx, String id) {
    StoreCredit c = credit(tx, id);
    if (c.status().equals("revoked")) {
      return json(200, Views.credit(c));
    }
    StoreCredit r = c.revoked(clock.now());
    tx.putCredit(r);
    WebhookOutbox.enqueue(tx, random, clock.now(), "store_credit.revoked", Views.credit(r));
    return json(200, Views.credit(r));
  }

  private ShopResponse createEmail(ShopTx tx, JsonNode b) {
    String customerId = requiredText(b, "customer_id");
    String template = requiredText(b, "template");
    tx.customer(customerId)
        .orElseThrow(() -> new HttpError(404, "customer_not_found", "No customer " + customerId));
    if (!TEMPLATES.contains(template)) {
      throw new HttpError(422, "unknown_template", "No template " + template);
    }
    JsonNode vars = b.path("variables").isObject() ? b.get("variables") : Json.obj();
    JsonNode meta = b.path("metadata").isObject() ? b.get("metadata") : Json.obj();
    Email e =
        new Email(
            "EM-" + random.base32(8), customerId, template, vars, meta, "queued", clock.now());
    tx.putEmail(e);
    ObjectNode out = Json.obj();
    out.put("id", e.id());
    out.put("status", e.status());
    out.put("customer_id", customerId);
    out.put("template", template);
    out.put("created_at", e.createdAt().toString());
    return json(202, out);
  }

  private ShopResponse exportEmails(ShopTx tx, Map<String, String> q) {
    Instant from = Instant.parse(require(q, "from"));
    Instant to = Instant.parse(require(q, "to"));
    Instant visibleUntil = clock.now().minus(config.exportLag()).plusNanos(1);
    if (visibleUntil.isBefore(to)) {
      to = visibleUntil;
    }
    ArrayNode data = Json.arr();
    String next = null;
    if (from.isBefore(to)) {
      Page<Email> p =
          tx.emailsCreated(from, to, q.get("cursor"), intParam(q, "limit", 100, 1, 500));
      p.items().forEach(e -> data.add(Views.email(e)));
      next = p.nextCursor();
    }
    return list(data, next);
  }

  // ── Idempotency-Key (Section 12.3) ───────────────────────────────────

  private ShopResponse idempotent(
      ShopRequest req, String path, Function<ShopTx, ShopResponse> effect) {
    String key = req.header("idempotency-key");
    if (key == null || key.isBlank()) {
      return json(
          400,
          new HttpError(400, "idempotency_key_required", "Send an Idempotency-Key header.").body());
    }
    JsonNode parsed = Json.parse(req.body());
    String fingerprint = Json.sha256Hex(req.method() + " " + path + " " + Json.canonical(parsed));
    return store.tx(
        tx -> {
          Instant now = clock.now();
          tx.deleteExpiredKey(key, now.minus(config.idempotencyTtl()));
          if (!tx.claimKey(key, fingerprint, now)) {
            IdempotencyRecord rec = tx.key(key).orElseThrow();
            if (!rec.fingerprint().equals(fingerprint)) {
              return json(
                  422,
                  new HttpError(
                          422,
                          "idempotency_key_reused",
                          "This Idempotency-Key was used with a different request.")
                      .body());
            }
            if (rec.responseStatus() == 0) {
              return json(
                  409,
                  new HttpError(
                          409,
                          "idempotency_request_in_progress",
                          "A request with this key is still in progress.")
                      .body());
            }
            return new ShopResponse(
                rec.responseStatus(),
                Map.of("Idempotent-Replayed", "true"),
                rec.responseBody().getBytes(StandardCharsets.UTF_8));
          }
          ShopResponse resp;
          try {
            resp = effect.apply(tx);
          } catch (HttpError e) {
            // 4xx answers are stored too, so a late duplicate gets the same answer.
            resp = json(e.status, e.body());
          }
          tx.completeKey(key, resp.status(), resp.bodyText());
          return resp;
        });
  }

  // ── /admin (Section 12.6) ────────────────────────────────────────────

  private ShopResponse admin(ShopRequest req, String[] s, Map<String, String> q) {
    String m = req.method();
    String r = s.length > 1 ? s[1] : "";
    int n = s.length;
    switch (r) {
      case "seed" -> {
        if (m.equals("POST")) {
          return seed(body(req));
        }
      }
      case "settlement" -> {
        if (m.equals("POST") && n == 3 && s[2].equals("run")) {
          int limit = q.containsKey("limit") ? Integer.parseInt(q.get("limit")) : Integer.MAX_VALUE;
          return tx(
              tx -> {
                List<String> ids =
                    Settlement.settleNow(tx, random, clock.now(), limit, q.get("undo_run_id"));
                ObjectNode out = Json.obj();
                ArrayNode a = out.putArray("settled");
                ids.forEach(a::add);
                return json(200, out);
              });
        }
      }
      case "carrier-bug" -> {
        if (m.equals("POST")) {
          return tx(tx -> carrierBug(tx, body(req).path("enabled").asBoolean(false)));
        }
      }
      case "orders" -> {
        if (m.equals("PUT") && n == 4 && s[3].equals("note")) {
          return tx(
              tx -> {
                Order o = tx.lockOrder(s[2]).orElseThrow(() -> orderNotFound(s[2]));
                JsonNode note = body(req).path("note");
                Order u = o.withNote(note.isTextual() ? note.asText() : null);
                tx.putOrder(u);
                return json(200, Views.order(u, tx.refundsByOrder(u.id())), etag(u));
              });
        }
      }
      case "store-credits" -> {
        if (m.equals("POST") && n == 4 && s[3].equals("spend")) {
          return tx(tx -> spend(tx, s[2], requiredPositiveLong(body(req), "amount_minor")));
        }
      }
      case "invariants" -> {
        if (m.equals("GET")) {
          return tx(tx -> json(200, Invariants.compute(tx)));
        }
      }
      case "stats" -> {
        if (m.equals("GET")) {
          return tx(tx -> json(200, Invariants.stats(tx)));
        }
      }
      case "ground-truth" -> {
        if (m.equals("GET")) {
          return tx(tx -> json(200, tx.meta("ground_truth").orElse(Json.arr())));
        }
      }
      case "lost-orders" -> {
        if (m.equals("GET")) {
          return tx(
              tx -> {
                ArrayNode a = Json.arr();
                tx.orders().stream()
                    .filter(o -> o.carrierStatus().equals("LOST"))
                    .forEach(o -> a.add(o.id()));
                ObjectNode out = Json.obj();
                out.set("data", a);
                return json(200, out);
              });
        }
      }
      case "faults" -> {
        if (m.equals("GET")) {
          return tx(tx -> json(200, FaultConfig.fromJson(tx.faults()).toJson()));
        }
        if (m.equals("POST")) {
          JsonNode patch = body(req);
          return tx(
              tx -> {
                JsonNode preset = patch.path("preset");
                FaultConfig base = FaultConfig.fromJson(tx.faults());
                if (preset.isTextual()) {
                  base =
                      switch (preset.asText()) {
                        case "calm" -> FaultConfig.CALM;
                        case "flaky" -> FaultConfig.flakyNetwork();
                        case "chaos" -> FaultConfig.chaos();
                        default -> throw new IllegalArgumentException("unknown preset");
                      };
                }
                FaultConfig next = base.merge(patch);
                tx.putFaults(next.toJson());
                return json(200, next.toJson());
              });
        }
      }
      case "page-data" -> {
        if (m.equals("GET")) {
          return tx(tx -> json(200, PageData.compute(tx)));
        }
      }
      default -> {}
    }
    throw new HttpError(404, "not_found", "No such endpoint.");
  }

  private ShopResponse seed(JsonNode b) {
    long seed = b.path("seed").asLong(42);
    int customers = b.path("customers").asInt(60);
    int orders = b.path("orders").asInt(400);
    int tickets = b.path("tickets").asInt(200);
    SeedData data = SeedGenerator.generate(seed, customers, orders, tickets, clock.now());
    return tx(
        tx -> {
          tx.deleteAll();
          data.customers().forEach(tx::putCustomer);
          data.orders().forEach(tx::putOrder);
          data.tickets().forEach(tx::putTicket);
          tx.putMeta("ground_truth", data.groundTruth());
          tx.putMeta("carrier_bug_selection", data.carrierBugSelectionJson());
          tx.putMeta("seed", data.seedInfo());
          ObjectNode out = Json.obj();
          out.put("seed", seed);
          out.put("customers", data.customers().size());
          out.put("orders", data.orders().size());
          out.put("tickets", data.tickets().size());
          out.put("data_sha256", data.sha256());
          return json(200, out);
        });
  }

  private ShopResponse carrierBug(ShopTx tx, boolean enabled) {
    ArrayNode ids = Json.arr();
    if (enabled) {
      JsonNode sel = tx.meta("carrier_bug_selection").orElse(Json.arr());
      for (JsonNode id : sel) {
        Order o = tx.order(id.asText()).orElse(null);
        if (o != null && !o.carrierStatus().equals("LOST")) {
          tx.putOrder(o.withCarrier("LOST", o.carrierStatus()));
          ids.add(o.id());
        }
      }
    } else {
      for (Order o : tx.orders()) {
        if (o.carrierBugOriginal() != null) {
          tx.putOrder(o.withCarrier(o.carrierBugOriginal(), null));
          ids.add(o.id());
        }
      }
    }
    ObjectNode out = Json.obj();
    out.put("enabled", enabled);
    out.set("orders", ids);
    return json(200, out);
  }

  private ShopResponse spend(ShopTx tx, String id, long amount) {
    StoreCredit c = credit(tx, id);
    if (!c.status().equals("active")) {
      throw new HttpError(409, "credit_not_active", "Store credit is " + c.status());
    }
    if (amount > c.balanceMinor()) {
      throw new HttpError(422, "insufficient_balance", "Amount is more than the balance.");
    }
    StoreCredit s = c.spent(amount, clock.now());
    tx.putCredit(s);
    return json(200, Views.credit(s));
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private ShopResponse tx(Function<ShopTx, ShopResponse> work) {
    return store.tx(work);
  }

  private static Order order(ShopTx tx, String id) {
    return tx.order(id).orElseThrow(() -> orderNotFound(id));
  }

  private static HttpError orderNotFound(String id) {
    return new HttpError(404, "order_not_found", "No order " + id);
  }

  private static Ticket ticket(ShopTx tx, String id) {
    return tx.ticket(id)
        .orElseThrow(() -> new HttpError(404, "ticket_not_found", "No ticket " + id));
  }

  private static Refund refund(ShopTx tx, String id) {
    return tx.refund(id)
        .orElseThrow(() -> new HttpError(404, "refund_not_found", "No refund " + id));
  }

  private static StoreCredit credit(ShopTx tx, String id) {
    return tx.credit(id)
        .orElseThrow(() -> new HttpError(404, "store_credit_not_found", "No store credit " + id));
  }

  private static JsonNode body(ShopRequest req) {
    JsonNode n = Json.parse(req.body());
    if (n.isNull()) {
      return Json.obj();
    }
    if (!n.isObject()) {
      throw new IllegalArgumentException("body must be a JSON object");
    }
    return n;
  }

  private static String requiredText(JsonNode b, String field) {
    JsonNode v = b.get(field);
    if (v == null || !v.isTextual() || v.asText().isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return v.asText();
  }

  private static long requiredPositiveLong(JsonNode b, String field) {
    JsonNode v = b.get(field);
    if (v == null || !v.isIntegralNumber() || v.asLong() <= 0) {
      throw new IllegalArgumentException(field + " must be a positive integer");
    }
    return v.asLong();
  }

  private static String require(Map<String, String> q, String name) {
    String v = q.get(name);
    if (v == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return v;
  }

  private static int intParam(Map<String, String> q, String name, int def, int min, int max) {
    String v = q.get(name);
    if (v == null) {
      return def;
    }
    int i = Integer.parseInt(v);
    if (i < min || i > max) {
      throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
    }
    return i;
  }

  private static Map<String, String> parseQuery(String qs) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String part : qs.split("&")) {
      if (part.isEmpty()) {
        continue;
      }
      int eq = part.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
      if (!v.isEmpty()) {
        out.put(k, v);
      }
    }
    return out;
  }

  private static ShopResponse list(ArrayNode data, String next) {
    ObjectNode out = Json.obj();
    out.set("data", data);
    out.put("next_cursor", next);
    return json(200, out);
  }

  static ShopResponse json(int status, JsonNode body) {
    return json(status, body, Map.of());
  }

  static ShopResponse json(int status, JsonNode body, Map<String, String> extra) {
    Map<String, String> h = new HashMap<>(extra);
    h.put("Content-Type", "application/json");
    return new ShopResponse(status, h, Json.bytes(body));
  }

  /** Visible for tests: every endpoint path pattern this router serves. */
  public static List<String> endpoints() {
    return new ArrayList<>(
        List.of(
            "GET /v1/customers/{id}",
            "GET /v1/orders/{id}",
            "PUT /v1/orders/{id}/note",
            "GET /v1/tickets",
            "GET /v1/tickets/{id}",
            "POST /v1/tickets/{id}/close",
            "POST /v1/tickets/{id}/reopen",
            "POST /v1/refunds",
            "GET /v1/refunds",
            "GET /v1/refunds/{id}",
            "POST /v1/refunds/{id}/cancel",
            "POST /v1/store-credits",
            "GET /v1/store-credits",
            "GET /v1/store-credits/{id}",
            "POST /v1/store-credits/{id}/revoke",
            "POST /v1/emails",
            "GET /v1/exports/emails"));
  }
}
