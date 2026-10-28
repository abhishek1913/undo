package dev.undo.connector.shopfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ActionView;
import dev.undo.core.connector.Connector;
import dev.undo.core.connector.ConnectorException;
import dev.undo.core.connector.Consistency;
import dev.undo.core.connector.Effect;
import dev.undo.core.connector.EffectPage;
import dev.undo.core.connector.ExecutionOutcome;
import dev.undo.core.connector.IdempotencyClass;
import dev.undo.core.connector.InvalidWebhookException;
import dev.undo.core.connector.JournalSpec;
import dev.undo.core.connector.LookupOutcome;
import dev.undo.core.connector.PostingLine;
import dev.undo.core.connector.ProviderEffect;
import dev.undo.core.connector.ProviderStatusChange;
import dev.undo.core.connector.Remediation;
import dev.undo.core.connector.ReversalPlan;
import dev.undo.core.connector.ToolCall;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.json.Json;
import dev.undo.core.port.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connector for the Shopfront mock provider. Talks to Shopfront only through {@link ShopfrontApi},
 * so the server (HTTP) and the simulator (in-memory) share every line of classification logic.
 * Holds no state between calls except configuration.
 */
public final class ShopfrontConnector implements Connector {
  public static final String ID = "shopfront";
  private static final Logger log = LoggerFactory.getLogger(ShopfrontConnector.class);

  static final String REFUND = "shop_issue_refund";
  static final String CREDIT = "shop_grant_store_credit";
  static final String EMAIL = "shop_send_email";
  static final String CLOSE = "shop_close_ticket";
  static final String NOTE = "shop_set_order_note";
  static final String CANCEL = "shop_cancel_refund";
  static final String REVOKE = "shop_revoke_store_credit";
  static final String REOPEN = "shop_reopen_ticket";
  static final String RESTORE = "shop_restore_order_note";

  static final String EXPENSE_REFUNDS = "expense:refunds";
  static final String IN_FLIGHT = "liability:refunds_in_flight:shopfront";
  static final String CASH = "asset:cash:shopfront";
  static final String EXPENSE_CREDIT = "expense:store_credit";

  public record Config(
      String apiKey, String webhookSecret, Duration exportLag, Duration factsTimeout) {}

  private final ShopfrontApi api;
  private final Config cfg;
  private final Clock clock;
  private final List<ToolSpec> tools;

  public ShopfrontConnector(ShopfrontApi api, Config cfg, Clock clock) {
    this.api = api;
    this.cfg = cfg;
    this.clock = clock;
    this.tools = buildTools(cfg.exportLag());
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<ToolSpec> tools() {
    return tools;
  }

  // ── tool specs (Section 13.1, 13.2) ─────────────────────────────────

  static List<ToolSpec> buildTools(Duration exportLag) {
    JsonNode doc;
    try (InputStream in = ShopfrontConnector.class.getResourceAsStream("tools.json")) {
      doc = Json.MAPPER.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read tools.json", e);
    }
    Map<String, JsonNode> defs = new LinkedHashMap<>();
    doc.path("agent").forEach(t -> defs.put(t.get("name").asText(), t));
    doc.path("internal").forEach(t -> defs.put(t.get("name").asText(), t));
    List<ToolSpec> out = new ArrayList<>();
    Duration day = Duration.ofHours(24);
    for (JsonNode t : defs.values()) {
      String n = t.get("name").asText();
      JsonNode schema = t.get("input_schema");
      String desc = t.get("description").asText();
      boolean internal =
          n.equals(CANCEL) || n.equals(REVOKE) || n.equals(REOPEN) || n.equals(RESTORE);
      Visibility vis = internal ? Visibility.INTERNAL : Visibility.AGENT;
      ToolSpec spec =
          switch (n) {
            case REFUND ->
                spec(
                    n,
                    desc,
                    schema,
                    Effect.WRITE,
                    vis,
                    IdempotencyClass.NATIVE_KEY,
                    Consistency.STRONG,
                    Duration.ZERO,
                    "refund:order:{order_id}",
                    "/amount_minor",
                    false,
                    day,
                    "refund",
                    true);
            case CREDIT ->
                spec(
                    n,
                    desc,
                    schema,
                    Effect.WRITE,
                    vis,
                    IdempotencyClass.LOOKUP_BY_REFERENCE,
                    Consistency.STRONG,
                    Duration.ZERO,
                    "credit:ticket:{ticket_id}",
                    "/amount_minor",
                    false,
                    Duration.ZERO,
                    "store_credit",
                    true);
            case EMAIL ->
                spec(
                    n,
                    desc,
                    schema,
                    Effect.WRITE,
                    vis,
                    IdempotencyClass.NONE,
                    Consistency.EVENTUAL,
                    exportLag,
                    "email:{customer_id}:{template}:{order_id}",
                    null,
                    false,
                    Duration.ZERO,
                    "email",
                    false);
            case CLOSE ->
                spec(
                    n,
                    desc,
                    schema,
                    Effect.WRITE,
                    vis,
                    IdempotencyClass.NATURAL,
                    Consistency.STRONG,
                    Duration.ZERO,
                    null,
                    null,
                    false,
                    Duration.ZERO,
                    null,
                    true);
            case NOTE ->
                spec(
                    n,
                    desc,
                    schema,
                    Effect.WRITE,
                    vis,
                    IdempotencyClass.NATURAL,
                    Consistency.STRONG,
                    Duration.ZERO,
                    null,
                    null,
                    true,
                    Duration.ZERO,
                    null,
                    true);
            case CANCEL, REVOKE ->
                spec(
                    n,
                    desc,
                    schema,
                    Effect.WRITE,
                    vis,
                    IdempotencyClass.NATIVE_KEY,
                    Consistency.STRONG,
                    Duration.ZERO,
                    null,
                    null,
                    false,
                    day,
                    null,
                    true);
            case REOPEN, RESTORE ->
                spec(
                    n,
                    desc,
                    schema,
                    Effect.WRITE,
                    vis,
                    IdempotencyClass.NATURAL,
                    Consistency.STRONG,
                    Duration.ZERO,
                    null,
                    null,
                    false,
                    Duration.ZERO,
                    null,
                    true);
            default ->
                spec(
                    n,
                    desc,
                    schema,
                    Effect.READ,
                    vis,
                    IdempotencyClass.NATURAL,
                    Consistency.STRONG,
                    Duration.ZERO,
                    null,
                    null,
                    false,
                    Duration.ZERO,
                    null,
                    false);
          };
      out.add(spec);
    }
    return List.copyOf(out);
  }

  private static ToolSpec spec(
      String name,
      String desc,
      JsonNode schema,
      Effect effect,
      Visibility vis,
      IdempotencyClass cls,
      Consistency cons,
      Duration lag,
      String dedupe,
      String money,
      boolean before,
      Duration ttl,
      String objectType,
      boolean needsState) {
    return new ToolSpec(
        name,
        ID,
        desc,
        schema,
        effect,
        vis,
        cls,
        cons,
        lag,
        Optional.ofNullable(dedupe),
        Optional.ofNullable(money),
        before,
        ttl,
        Optional.ofNullable(objectType),
        needsState);
  }

  // ── transport helpers ───────────────────────────────────────────────

  private Map<String, String> headers(String idempotencyKey, Long timeoutMs) {
    Map<String, String> h = new HashMap<>();
    h.put("Authorization", "Bearer " + cfg.apiKey());
    h.put("Content-Type", "application/json");
    if (idempotencyKey != null) {
      h.put("Idempotency-Key", idempotencyKey);
    }
    if (timeoutMs != null) {
      h.put(ShopfrontApi.TIMEOUT_HEADER, Long.toString(timeoutMs));
    }
    return h;
  }

  private ShopResponse send(String method, String path, JsonNode body, Map<String, String> headers)
      throws ShopTransportException {
    byte[] b = body == null ? new byte[0] : Json.bytes(body);
    return api.send(new ShopRequest(method, path, headers, b));
  }

  /** A read that must succeed: 404 returns null; anything else throws ConnectorException. */
  private JsonNode read(String path, Long timeoutMs) throws ConnectorException {
    ShopResponse r;
    try {
      r = send("GET", path, null, headers(null, timeoutMs));
    } catch (ShopTransportException e) {
      throw new ConnectorException("shopfront " + path + ": " + e.kind(), e);
    }
    if (r.status() == 404) {
      return null;
    }
    if (r.status() / 100 != 2) {
      throw new ConnectorException("shopfront " + path + " returned " + r.status());
    }
    try {
      return Json.parse(r.body());
    } catch (IllegalArgumentException e) {
      throw new ConnectorException("shopfront " + path + ": bad body", e);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  // ── facts (Section 13.3) ────────────────────────────────────────────

  @Override
  public JsonNode gatherFacts(String tool, JsonNode args) throws ConnectorException {
    ObjectNode f = Json.obj();
    long t = cfg.factsTimeout().toMillis();
    switch (tool) {
      case REFUND -> {
        JsonNode order = read("/v1/orders/" + enc(args.get("order_id").asText()), t);
        f.set("order", order == null ? Json.nul() : order);
        f.set("ticket", ticketFact(args, t));
        if (order != null && order.hasNonNull("placed_at")) {
          long days =
              Duration.between(Instant.parse(order.get("placed_at").asText()), clock.now())
                  .toDays();
          f.put("order_age_days", days);
        }
      }
      case CREDIT, EMAIL -> {
        JsonNode c = read("/v1/customers/" + enc(args.get("customer_id").asText()), t);
        f.set("customer", c == null ? Json.nul() : c);
        f.set("ticket", ticketFact(args, t));
      }
      case CLOSE -> {
        JsonNode tk = read("/v1/tickets/" + enc(args.get("ticket_id").asText()), t);
        f.set("ticket", tk == null ? Json.nul() : tk);
      }
      case NOTE -> {
        JsonNode o = read("/v1/orders/" + enc(args.get("order_id").asText()), t);
        f.set("order", o == null ? Json.nul() : o);
      }
      default -> {}
    }
    return f;
  }

  private JsonNode ticketFact(JsonNode args, long timeoutMs) throws ConnectorException {
    JsonNode id = args.get("ticket_id");
    if (id == null || id.isNull()) {
      return Json.nul();
    }
    JsonNode t = read("/v1/tickets/" + enc(id.asText()), timeoutMs);
    return t == null ? Json.nul() : t;
  }

  @Override
  public JsonNode captureBefore(String tool, JsonNode args) throws ConnectorException {
    if (!tool.equals(NOTE)) {
      return Json.obj();
    }
    JsonNode o =
        read("/v1/orders/" + enc(args.get("order_id").asText()), cfg.factsTimeout().toMillis());
    if (o == null) {
      throw new ConnectorException("order not found");
    }
    ObjectNode b = Json.obj();
    b.set("note", o.get("note"));
    b.put("note_version", o.get("note_version").asInt());
    return b;
  }

  // ── execute (Section 9.4 classification) ────────────────────────────

  @Override
  public ExecutionOutcome execute(ToolCall call) {
    JsonNode a = call.args();
    String key = call.providerIdempotencyKey();
    try {
      return switch (call.tool()) {
        case "shop_list_open_tickets" -> {
          String path =
              "/v1/tickets?status=open&limit="
                  + a.get("limit").asInt()
                  + (a.hasNonNull("cursor") ? "&cursor=" + enc(a.get("cursor").asText()) : "");
          ShopResponse r = send("GET", path, null, headers(null, null));
          yield classify(
              r,
              body -> {
                ObjectNode out = Json.obj();
                var data = out.putArray("tickets");
                for (JsonNode t : body.path("data")) {
                  ObjectNode o = data.addObject();
                  o.put("id", t.path("id").asText());
                  o.put("subject", t.path("subject").asText());
                  o.put("customer_id", t.path("customer_id").asText());
                }
                out.set("next_cursor", body.path("next_cursor"));
                return new ExecutionOutcome.Succeeded(null, "ok", out);
              });
        }
        case "shop_get_ticket" -> readTool("/v1/tickets/" + enc(a.get("ticket_id").asText()));
        case "shop_get_order" -> readTool("/v1/orders/" + enc(a.get("order_id").asText()));
        case "shop_get_customer" -> readTool("/v1/customers/" + enc(a.get("customer_id").asText()));
        case REFUND -> {
          ObjectNode body = Json.obj();
          body.put("order_id", a.get("order_id").asText());
          body.put("amount_minor", a.get("amount_minor").asLong());
          body.put("reason", a.get("reason").asText());
          ObjectNode md = body.putObject("metadata");
          md.put("undo_action_id", call.actionId().toString());
          md.put("undo_run_id", call.runId() == null ? null : call.runId().toString());
          ShopResponse r = send("POST", "/v1/refunds", body, headers(key, null));
          yield classify(
              r,
              rb ->
                  new ExecutionOutcome.Succeeded(
                      rb.get("id").asText(), rb.get("status").asText(), refundResult(rb)));
        }
        case CREDIT -> {
          ObjectNode body = Json.obj();
          body.put("customer_id", a.get("customer_id").asText());
          body.put("amount_minor", a.get("amount_minor").asLong());
          body.put("reason", a.get("reason").asText());
          body.put("reference", "undo_" + call.actionId());
          ShopResponse r = send("POST", "/v1/store-credits", body, headers(null, null));
          yield classify(
              r,
              rb ->
                  new ExecutionOutcome.Succeeded(
                      rb.get("id").asText(), rb.get("status").asText(), creditResult(rb)));
        }
        case EMAIL -> {
          ObjectNode body = Json.obj();
          body.put("customer_id", a.get("customer_id").asText());
          body.put("template", a.get("template").asText());
          ObjectNode vars = body.putObject("variables");
          vars.set("order_id", a.get("order_id"));
          vars.set("amount_minor", a.get("amount_minor"));
          vars.set("note", a.get("note"));
          body.putObject("metadata").put("undo_action_id", call.actionId().toString());
          ShopResponse r = send("POST", "/v1/emails", body, headers(null, null));
          yield classify(
              r,
              rb -> {
                ObjectNode res = Json.obj();
                res.put("email_id", rb.get("id").asText());
                res.put("status", rb.get("status").asText());
                res.put("customer_id", a.get("customer_id").asText());
                res.put("template", a.get("template").asText());
                res.set("order_id", a.get("order_id"));
                return new ExecutionOutcome.Succeeded(
                    rb.get("id").asText(), rb.get("status").asText(), res);
              });
        }
        case CLOSE -> {
          ObjectNode body = Json.obj();
          body.put("resolution", a.get("resolution").asText());
          ShopResponse r =
              send(
                  "POST",
                  "/v1/tickets/" + enc(a.get("ticket_id").asText()) + "/close",
                  body,
                  headers(key, null));
          yield classify(
              r,
              rb ->
                  new ExecutionOutcome.Succeeded(
                      rb.get("id").asText(), rb.get("status").asText(), ticketResult(rb)));
        }
        case NOTE -> {
          int version =
              call.beforeSnapshot() == null
                  ? 0
                  : call.beforeSnapshot().path("note_version").asInt();
          yield casNote(a.get("order_id").asText(), a.get("note"), version);
        }
        case RESTORE ->
            casNote(a.get("order_id").asText(), a.get("note"), a.get("expected_version").asInt());
        case CANCEL -> {
          ShopResponse r =
              send(
                  "POST",
                  "/v1/refunds/" + enc(a.get("refund_id").asText()) + "/cancel",
                  Json.obj(),
                  headers(key, null));
          yield classify(
              r,
              rb ->
                  new ExecutionOutcome.Succeeded(
                      rb.get("id").asText(), rb.get("status").asText(), refundResult(rb)));
        }
        case REVOKE -> {
          ShopResponse r =
              send(
                  "POST",
                  "/v1/store-credits/" + enc(a.get("store_credit_id").asText()) + "/revoke",
                  Json.obj(),
                  headers(key, null));
          yield classify(
              r,
              rb ->
                  new ExecutionOutcome.Succeeded(
                      rb.get("id").asText(), rb.get("status").asText(), creditResult(rb)));
        }
        case REOPEN -> {
          ObjectNode body = Json.obj();
          body.put("expected_resolution", a.get("expected_resolution").asText());
          ShopResponse r =
              send(
                  "POST",
                  "/v1/tickets/" + enc(a.get("ticket_id").asText()) + "/reopen",
                  body,
                  headers(key, null));
          yield classify(
              r,
              rb ->
                  new ExecutionOutcome.Succeeded(
                      rb.get("id").asText(), rb.get("status").asText(), ticketResult(rb)));
        }
        default ->
            new ExecutionOutcome.FailedDefinitive("unknown_tool", "No such tool " + call.tool(), 0);
      };
    } catch (ShopTransportException e) {
      return switch (e.kind()) {
        case CONNECT_FAILED ->
            new ExecutionOutcome.RetryableNotApplied("connect_failed", Duration.ZERO);
        case TIMEOUT -> new ExecutionOutcome.Unknown("timeout");
        case RESET -> new ExecutionOutcome.Unknown("connection_reset");
      };
    } catch (RuntimeException e) {
      // Unparseable 2xx body or similar: the request may have been applied.
      return new ExecutionOutcome.Unknown("unexpected: " + e);
    }
  }

  private ExecutionOutcome readTool(String path) throws ShopTransportException {
    ShopResponse r = send("GET", path, null, headers(null, null));
    return classify(r, body -> new ExecutionOutcome.Succeeded(null, "ok", body));
  }

  /** PUT note with If-Match; on 412 read the order: already our text → Succeeded, else conflict. */
  private ExecutionOutcome casNote(String orderId, JsonNode note, int expectedVersion)
      throws ShopTransportException {
    ObjectNode body = Json.obj();
    body.set("note", note == null ? Json.nul() : note);
    Map<String, String> h = headers(null, null);
    h.put("If-Match", "\"v" + expectedVersion + "\"");
    ShopResponse r = send("PUT", "/v1/orders/" + enc(orderId) + "/note", body, h);
    if (r.status() == 412) {
      ShopResponse g = send("GET", "/v1/orders/" + enc(orderId), null, headers(null, null));
      if (g.status() / 100 != 2) {
        return g.status() >= 500
            ? new ExecutionOutcome.Unknown("http_" + g.status())
            : definitive(g);
      }
      JsonNode cur = Json.parse(g.body());
      if (sameText(cur.get("note"), note)) {
        return new ExecutionOutcome.Succeeded(orderId, "ok", noteResult(cur));
      }
      return new ExecutionOutcome.FailedDefinitive(
          "conflict", "The note was changed by someone else.", 412);
    }
    return classify(r, rb -> new ExecutionOutcome.Succeeded(orderId, "ok", noteResult(rb)));
  }

  static boolean sameText(JsonNode a, JsonNode b) {
    String x = a == null || a.isNull() ? null : a.asText();
    String y = b == null || b.isNull() ? null : b.asText();
    return x == null ? y == null : x.equals(y);
  }

  interface OnSuccess {
    ExecutionOutcome apply(JsonNode body);
  }

  /** Section 9.4. */
  ExecutionOutcome classify(ShopResponse r, OnSuccess ok) {
    int s = r.status();
    if (s / 100 == 2) {
      return ok.apply(Json.parse(r.body()));
    }
    if (s == 429) {
      Duration after = Duration.ofSeconds(1);
      String ra = r.header("Retry-After");
      if (ra != null) {
        try {
          after = Duration.ofSeconds(Long.parseLong(ra.trim()));
        } catch (NumberFormatException ignored) {
          after = Duration.ofSeconds(1);
        }
      }
      return new ExecutionOutcome.RetryableNotApplied("rate_limited", after);
    }
    if (s >= 500) {
      return new ExecutionOutcome.Unknown("http_" + s);
    }
    String code = errorCode(r);
    if (s == 409 && "idempotency_request_in_progress".equals(code)) {
      return new ExecutionOutcome.Unknown("idempotency_request_in_progress");
    }
    if (s == 422 && "idempotency_key_reused".equals(code)) {
      log.error("Shopfront says idempotency_key_reused: this is a bug in Undo");
    }
    if (s == 401 || s == 403) {
      log.error("Shopfront rejected our credentials ({}): check SHOP_API_KEY", s);
    }
    return definitive(r);
  }

  private static ExecutionOutcome definitive(ShopResponse r) {
    String code = errorCode(r);
    String msg = "Shopfront answered " + r.status();
    try {
      msg = Json.parse(r.body()).path("error").path("message").asText(msg);
    } catch (RuntimeException ignored) {
      // keep the default message
    }
    return new ExecutionOutcome.FailedDefinitive(
        code == null ? "http_" + r.status() : code, msg, r.status());
  }

  private static String errorCode(ShopResponse r) {
    try {
      JsonNode c = Json.parse(r.body()).path("error").path("code");
      return c.isTextual() ? c.asText() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  static ObjectNode refundResult(JsonNode rb) {
    ObjectNode o = Json.obj();
    o.put("refund_id", rb.get("id").asText());
    o.put("status", rb.get("status").asText());
    o.put("amount_minor", rb.get("amount_minor").asLong());
    o.put("order_id", rb.path("order_id").asText());
    o.set("settles_after", rb.get("settles_after"));
    return o;
  }

  static ObjectNode creditResult(JsonNode rb) {
    ObjectNode o = Json.obj();
    o.put("store_credit_id", rb.get("id").asText());
    o.put("status", rb.get("status").asText());
    o.put("amount_minor", rb.get("amount_minor").asLong());
    o.put("balance_minor", rb.path("balance_minor").asLong());
    o.put("revoked_minor", rb.path("revoked_minor").asLong());
    o.put("customer_id", rb.path("customer_id").asText());
    return o;
  }

  static ObjectNode ticketResult(JsonNode rb) {
    ObjectNode o = Json.obj();
    o.put("ticket_id", rb.get("id").asText());
    o.put("status", rb.get("status").asText());
    o.set("resolution", rb.get("resolution"));
    return o;
  }

  static ObjectNode noteResult(JsonNode order) {
    ObjectNode o = Json.obj();
    o.put("order_id", order.path("id").asText());
    o.put("note_version", order.path("note_version").asInt());
    o.set("note", order.get("note"));
    return o;
  }

  // ── lookup ──────────────────────────────────────────────────────────

  @Override
  public LookupOutcome lookup(ToolCall call) {
    JsonNode a = call.args();
    try {
      switch (call.tool()) {
        case REFUND -> {
          JsonNode r = read("/v1/refunds?metadata.undo_action_id=" + call.actionId(), null);
          JsonNode data = r == null ? Json.arr() : r.path("data");
          if (data.isEmpty()) {
            return new LookupOutcome.NotFound();
          }
          if (data.size() > 1) {
            log.error("CRITICAL: {} refunds carry undo_action_id {}", data.size(), call.actionId());
          }
          JsonNode first = data.get(0);
          return new LookupOutcome.Found(
              first.get("id").asText(), first.get("status").asText(), refundResult(first));
        }
        case CREDIT -> {
          JsonNode r = read("/v1/store-credits?reference=" + enc("undo_" + call.actionId()), null);
          JsonNode data = r == null ? Json.arr() : r.path("data");
          if (data.isEmpty()) {
            return new LookupOutcome.NotFound();
          }
          JsonNode first = data.get(0);
          return new LookupOutcome.Found(
              first.get("id").asText(), first.get("status").asText(), creditResult(first));
        }
        case EMAIL -> {
          return new LookupOutcome.Unavailable("provider has no lookup");
        }
        case CLOSE -> {
          JsonNode t = read("/v1/tickets/" + enc(a.get("ticket_id").asText()), null);
          if (t != null
              && "CLOSED".equals(t.path("status").asText())
              && sameText(t.get("resolution"), a.get("resolution"))) {
            return new LookupOutcome.Found(t.get("id").asText(), "CLOSED", ticketResult(t));
          }
          return new LookupOutcome.NotFound();
        }
        case NOTE, RESTORE -> {
          String orderId = a.get("order_id").asText();
          JsonNode o = read("/v1/orders/" + enc(orderId), null);
          if (o != null && sameText(o.get("note"), a.get("note"))) {
            return new LookupOutcome.Found(orderId, "ok", noteResult(o));
          }
          return new LookupOutcome.NotFound();
        }
        case CANCEL -> {
          JsonNode r = read("/v1/refunds/" + enc(a.get("refund_id").asText()), null);
          if (r != null && "cancelled".equals(r.path("status").asText())) {
            return new LookupOutcome.Found(r.get("id").asText(), "cancelled", refundResult(r));
          }
          return new LookupOutcome.NotFound();
        }
        case REVOKE -> {
          JsonNode c = read("/v1/store-credits/" + enc(a.get("store_credit_id").asText()), null);
          if (c != null && "revoked".equals(c.path("status").asText())) {
            return new LookupOutcome.Found(c.get("id").asText(), "revoked", creditResult(c));
          }
          return new LookupOutcome.NotFound();
        }
        case REOPEN -> {
          JsonNode t = read("/v1/tickets/" + enc(a.get("ticket_id").asText()), null);
          if (t != null && "OPEN".equals(t.path("status").asText())) {
            return new LookupOutcome.Found(t.get("id").asText(), "OPEN", ticketResult(t));
          }
          return new LookupOutcome.NotFound();
        }
        default -> {
          ExecutionOutcome o = execute(call); // reads: just read again
          if (o instanceof ExecutionOutcome.Succeeded s) {
            return new LookupOutcome.Found(s.providerRef(), s.providerStatus(), s.result());
          }
          return new LookupOutcome.Unavailable(o.toString());
        }
      }
    } catch (ConnectorException | RuntimeException e) {
      return new LookupOutcome.Unavailable(e.getMessage());
    }
  }

  // ── current state and reversal ──────────────────────────────────────

  @Override
  public JsonNode currentState(ActionView action) throws ConnectorException {
    JsonNode a = action.args();
    JsonNode s =
        switch (action.tool()) {
          case REFUND -> read("/v1/refunds/" + enc(action.providerRef()), null);
          case CREDIT -> read("/v1/store-credits/" + enc(action.providerRef()), null);
          case EMAIL -> action.result();
          case CLOSE -> read("/v1/tickets/" + enc(a.get("ticket_id").asText()), null);
          case NOTE -> read("/v1/orders/" + enc(a.get("order_id").asText()), null);
          default -> Json.obj();
        };
    if (s == null) {
      throw new ConnectorException("provider object not found for " + action.tool());
    }
    return s;
  }

  @Override
  public ReversalPlan reversal(ActionView action, JsonNode state, Instant now) {
    JsonNode a = action.args();
    switch (action.tool()) {
      case REFUND -> {
        String status = state.path("status").asText();
        long amount = state.path("amount_minor").asLong(action.moneyOutMinor());
        String id = state.path("id").asText(action.providerRef());
        return switch (status) {
          case "pending" -> {
            ObjectNode args = Json.obj();
            args.put("refund_id", id);
            yield new ReversalPlan.Compensate(CANCEL, args, amount, "Cancel pending refund " + id);
          }
          case "settled" ->
              new ReversalPlan.Irreversible(
                  "Refund already settled to the customer's bank", Optional.empty(), amount);
          default -> new ReversalPlan.NoEffect("Refund is " + status + "; no money left the store");
        };
      }
      case CREDIT -> {
        String status = state.path("status").asText();
        long amount = state.path("amount_minor").asLong(action.moneyOutMinor());
        long balance = state.path("balance_minor").asLong();
        String id = state.path("id").asText(action.providerRef());
        if (status.equals("revoked")) {
          return new ReversalPlan.NoEffect("Store credit already revoked");
        }
        if (balance == 0) {
          return new ReversalPlan.Irreversible(
              "Store credit already fully spent", Optional.empty(), amount);
        }
        ObjectNode args = Json.obj();
        args.put("store_credit_id", id);
        String desc =
            balance < amount
                ? "Revoke remaining store credit "
                    + id
                    + " ("
                    + (amount - balance)
                    + " paise already spent)"
                : "Revoke store credit " + id;
        return new ReversalPlan.Compensate(REVOKE, args, balance, desc);
      }
      case EMAIL -> {
        return new ReversalPlan.Irreversible(
            "Emails cannot be unsent", Optional.of(correction(a)), 0);
      }
      case CLOSE -> {
        String status = state.path("status").asText();
        if (status.equals("OPEN")) {
          return new ReversalPlan.NoEffect("Already reopened");
        }
        if (sameText(state.get("resolution"), a.get("resolution"))) {
          ObjectNode args = Json.obj();
          args.put("ticket_id", a.get("ticket_id").asText());
          args.put("expected_resolution", a.get("resolution").asText());
          return new ReversalPlan.Compensate(
              REOPEN, args, 0, "Reopen ticket " + a.get("ticket_id").asText());
        }
        return new ReversalPlan.Conflict(
            "The ticket was closed again with another resolution", state);
      }
      case NOTE -> {
        if (sameText(state.get("note"), a.get("note"))) {
          ObjectNode args = Json.obj();
          args.put("order_id", a.get("order_id").asText());
          JsonNode beforeNote =
              action.beforeSnapshot() == null ? Json.nul() : action.beforeSnapshot().path("note");
          args.set("note", beforeNote.isMissingNode() ? Json.nul() : beforeNote);
          args.put("expected_version", state.path("note_version").asInt());
          return new ReversalPlan.Compensate(
              RESTORE, args, 0, "Restore previous note on " + a.get("order_id").asText());
        }
        return new ReversalPlan.Conflict("The note was changed after the agent wrote it", state);
      }
      default -> {
        return new ReversalPlan.NoEffect("Reads have no effect");
      }
    }
  }

  static Remediation correction(JsonNode emailArgs) {
    ObjectNode args = Json.obj();
    args.set("ticket_id", emailArgs.get("ticket_id"));
    args.put("customer_id", emailArgs.get("customer_id").asText());
    args.put("template", "correction");
    JsonNode order = emailArgs.get("order_id");
    args.set("order_id", order);
    args.putNull("amount_minor");
    String note =
        order == null || order.isNull()
            ? "Please ignore our earlier email. It was sent by mistake. No action is needed from you."
            : "Please ignore our earlier email about order "
                + order.asText()
                + ". It was sent by mistake. No action is needed from you.";
    args.put("note", note);
    return new Remediation(
        EMAIL, args, "Send a correction email to " + emailArgs.get("customer_id").asText());
  }

  // ── journals (Section 13.3) ─────────────────────────────────────────

  @Override
  public List<JournalSpec> journalOnSuccess(ActionView action) {
    long amt = action.moneyOutMinor();
    return switch (action.tool()) {
      case REFUND ->
          amt == 0
              ? List.of()
              : List.of(
                  new JournalSpec(
                      "REFUND_ISSUED",
                      "execution",
                      List.of(
                          new PostingLine(EXPENSE_REFUNDS, amt),
                          new PostingLine(IN_FLIGHT, -amt))));
      case CREDIT ->
          amt == 0
              ? List.of()
              : List.of(
                  new JournalSpec(
                      "CREDIT_GRANTED",
                      "execution",
                      List.of(
                          new PostingLine(EXPENSE_CREDIT, amt),
                          new PostingLine(customerLiability(action), -amt))));
      default -> List.of();
    };
  }

  @Override
  public List<JournalSpec> journalOnProviderStatus(ActionView action, String newStatus) {
    if (!action.tool().equals(REFUND) || action.moneyOutMinor() == 0) {
      return List.of();
    }
    long amt = action.moneyOutMinor();
    return switch (newStatus) {
      case "settled" ->
          List.of(
              new JournalSpec(
                  "REFUND_SETTLED",
                  "webhook",
                  List.of(new PostingLine(IN_FLIGHT, amt), new PostingLine(CASH, -amt))));
      case "failed" ->
          List.of(
              new JournalSpec(
                  "REFUND_FAILED",
                  "webhook",
                  List.of(
                      new PostingLine(IN_FLIGHT, amt), new PostingLine(EXPENSE_REFUNDS, -amt))));
      case "cancelled" ->
          List.of(
              new JournalSpec(
                  "REFUND_CANCELLED",
                  "webhook",
                  List.of(
                      new PostingLine(IN_FLIGHT, amt), new PostingLine(EXPENSE_REFUNDS, -amt))));
      default -> List.of();
    };
  }

  @Override
  public List<JournalSpec> journalOnCompensated(ActionView original, ActionView compensation) {
    return switch (original.tool()) {
      case REFUND -> {
        long amt = original.moneyOutMinor();
        yield amt == 0
            ? List.of()
            : List.of(
                new JournalSpec(
                    "REFUND_CANCELLED",
                    "compensation",
                    List.of(
                        new PostingLine(IN_FLIGHT, amt), new PostingLine(EXPENSE_REFUNDS, -amt))));
      }
      case CREDIT -> {
        long r =
            compensation.result() == null
                ? 0
                : compensation.result().path("revoked_minor").asLong();
        yield r == 0
            ? List.of()
            : List.of(
                new JournalSpec(
                    "CREDIT_REVOKED",
                    "compensation",
                    List.of(
                        new PostingLine(customerLiability(original), r),
                        new PostingLine(EXPENSE_CREDIT, -r))));
      }
      default -> List.of();
    };
  }

  private static String customerLiability(ActionView action) {
    return "liability:store_credit:" + action.args().path("customer_id").asText();
  }

  // ── webhooks ────────────────────────────────────────────────────────

  @Override
  public ProviderStatusChange parseWebhook(Map<String, String> headers, byte[] body, Instant now)
      throws InvalidWebhookException {
    String sig = null;
    for (Map.Entry<String, String> e : headers.entrySet()) {
      if (e.getKey().equalsIgnoreCase("Shopfront-Signature")) {
        sig = e.getValue();
      }
    }
    if (sig == null) {
      throw new InvalidWebhookException("missing Shopfront-Signature");
    }
    long t = -1;
    String v1 = null;
    for (String part : sig.split(",")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length == 2 && kv[0].equals("t")) {
        try {
          t = Long.parseLong(kv[1]);
        } catch (NumberFormatException e) {
          throw new InvalidWebhookException("bad timestamp");
        }
      } else if (kv.length == 2 && kv[0].equals("v1")) {
        v1 = kv[1];
      }
    }
    if (t < 0 || v1 == null) {
      throw new InvalidWebhookException("malformed signature header");
    }
    if (Math.abs(now.getEpochSecond() - t) > 300) {
      throw new InvalidWebhookException("timestamp outside tolerance");
    }
    String expected = hmac(cfg.webhookSecret(), t, body);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8))) {
      throw new InvalidWebhookException("signature mismatch");
    }
    JsonNode payload;
    try {
      payload = Json.parse(body);
    } catch (IllegalArgumentException e) {
      throw new InvalidWebhookException("body is not JSON");
    }
    JsonNode obj = payload.path("data").path("object");
    String id = payload.path("id").asText(null);
    if (id == null || !obj.isObject()) {
      throw new InvalidWebhookException("missing id or data.object");
    }
    Instant at =
        payload.hasNonNull("created_at") ? Instant.parse(payload.get("created_at").asText()) : now;
    return new ProviderStatusChange(
        id,
        payload.path("type").asText(),
        obj.path("id").asText(),
        obj.path("status").asText(),
        at,
        payload);
  }

  static String hmac(String secret, long t, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── reconciliation listing ──────────────────────────────────────────

  /**
   * Pages refunds, then store credits (both by updated_at), then emails from the lagging export.
   * Cursor: "<phase>|<provider cursor or empty>".
   */
  @Override
  public EffectPage listEffects(Instant from, Instant to, Optional<String> cursor)
      throws ConnectorException {
    String phase = "refunds";
    String inner = null;
    if (cursor.isPresent()) {
      String[] c = cursor.get().split("\\|", 2);
      phase = c[0];
      inner = c.length > 1 && !c[1].isEmpty() ? c[1] : null;
    }
    String path =
        switch (phase) {
          case "refunds" ->
              "/v1/refunds?updated_from="
                  + enc(from.toString())
                  + "&updated_to="
                  + enc(to.toString())
                  + "&limit=200";
          case "credits" ->
              "/v1/store-credits?updated_from="
                  + enc(from.toString())
                  + "&updated_to="
                  + enc(to.toString())
                  + "&limit=200";
          case "emails" ->
              "/v1/exports/emails?from="
                  + enc(from.toString())
                  + "&to="
                  + enc(to.toString())
                  + "&limit=200";
          default -> throw new ConnectorException("bad cursor");
        };
    if (inner != null) {
      path += "&cursor=" + enc(inner);
    }
    JsonNode page = read(path, null);
    if (page == null) {
      throw new ConnectorException("listing not found: " + path);
    }
    List<ProviderEffect> effects = new ArrayList<>();
    for (JsonNode o : page.path("data")) {
      effects.add(effect(phase, o));
    }
    String next = page.path("next_cursor").isTextual() ? page.get("next_cursor").asText() : null;
    Optional<String> nextCursor;
    if (next != null) {
      nextCursor = Optional.of(phase + "|" + next);
    } else {
      nextCursor =
          switch (phase) {
            case "refunds" -> Optional.of("credits|");
            case "credits" -> Optional.of("emails|");
            default -> Optional.empty();
          };
    }
    return new EffectPage(effects, nextCursor);
  }

  private static ProviderEffect effect(String phase, JsonNode o) {
    String ref = o.path("id").asText();
    Instant created = Instant.parse(o.path("created_at").asText());
    return switch (phase) {
      case "refunds" ->
          new ProviderEffect(
              ref,
              "refund",
              uuid(o.path("metadata").path("undo_action_id").asText(null)),
              o.path("status").asText(),
              o.path("amount_minor").asLong(),
              created,
              o);
      case "credits" -> {
        String refText = o.path("reference").asText(null);
        String id = refText != null && refText.startsWith("undo_") ? refText.substring(5) : null;
        yield new ProviderEffect(
            ref,
            "store_credit",
            uuid(id),
            o.path("status").asText(),
            o.path("amount_minor").asLong(),
            created,
            o);
      }
      default ->
          new ProviderEffect(
              ref,
              "email",
              uuid(o.path("metadata").path("undo_action_id").asText(null)),
              o.path("status").asText(),
              0,
              created,
              o);
    };
  }

  private static Optional<UUID> uuid(String s) {
    if (s == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(s));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
