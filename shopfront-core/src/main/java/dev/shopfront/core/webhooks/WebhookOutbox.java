package dev.shopfront.core.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.shopfront.core.domain.OutboxRow;
import dev.shopfront.core.faults.FaultConfig;
import dev.shopfront.core.json.Json;
import dev.shopfront.core.port.ShopRandom;
import dev.shopfront.core.store.ShopTx;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Outgoing webhooks (Section 12.4): outbox rows written in the same TX as the change. */
public final class WebhookOutbox {
  private WebhookOutbox() {}

  public static void enqueue(
      ShopTx tx, ShopRandom random, Instant now, String type, ObjectNode object) {
    String id = "evt_" + uuid(random);
    ObjectNode payload = Json.obj();
    payload.put("id", id);
    payload.put("type", type);
    payload.put("created_at", now.toString());
    payload.putObject("data").set("object", object);
    tx.insertOutbox(new OutboxRow(id, type, payload, 0, now, null, now));
  }

  public static String uuid(ShopRandom r) {
    long hi = r.nextLong();
    long lo = r.nextLong();
    return new java.util.UUID(
            (hi & ~0xF000L) | 0x4000L, (lo & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L)
        .toString();
  }

  public static List<OutboxRow> due(ShopTx tx, Instant now, int limit) {
    return tx.outboxDue(now, limit);
  }

  public static byte[] body(OutboxRow row) {
    return Json.bytes(row.payload());
  }

  /** Shopfront-Event-Id and Shopfront-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>. */
  public static Map<String, String> headers(
      OutboxRow row, byte[] body, String secret, Instant now) {
    long t = now.getEpochSecond();
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Content-Type", "application/json");
    h.put("Shopfront-Event-Id", row.id());
    h.put("Shopfront-Signature", "t=" + t + ",v1=" + sign(secret, t, body));
    return h;
  }

  public static String sign(String secret, long t, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** How one delivery attempt behaves under the configured webhook faults. */
  public record Delivery(boolean drop, int copies, long delayMs) {}

  public static Delivery plan(FaultConfig f, ShopRandom r) {
    boolean drop = r.nextDouble() < f.webhookDropRate();
    int copies = r.nextDouble() < f.webhookDuplicateRate() ? 2 : 1;
    long delay = f.webhookDelayMsMax() > 0 ? r.nextInt(f.webhookDelayMsMax() + 1) : 0;
    return new Delivery(drop, copies, delay);
  }

  public static void markDelivered(ShopTx tx, String id, Instant now) {
    tx.outboxRow(id).ifPresent(r -> tx.putOutbox(r.delivered(now)));
  }

  public static void markFailed(ShopTx tx, String id, Instant now) {
    tx.outboxRow(id).ifPresent(r -> tx.putOutbox(r.failed(now)));
  }

  public static JsonNode payload(OutboxRow row) {
    return row.payload();
  }
}
