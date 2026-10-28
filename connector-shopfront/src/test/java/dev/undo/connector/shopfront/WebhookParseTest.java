package dev.undo.connector.shopfront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.undo.core.connector.InvalidWebhookException;
import dev.undo.core.connector.ProviderStatusChange;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookParseTest {
  static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
  static final ShopfrontConnector C =
      new ShopfrontConnector(
          r -> null,
          new ShopfrontConnector.Config(
              "k", "whsec", Duration.ofMinutes(15), Duration.ofSeconds(3)),
          () -> NOW);
  static final byte[] BODY =
      "{\"id\":\"evt_1\",\"type\":\"refund.settled\",\"created_at\":\"2026-10-24T11:59:00Z\",\"data\":{\"object\":{\"id\":\"RF-1\",\"status\":\"settled\"}}}"
          .getBytes(StandardCharsets.UTF_8);

  static Map<String, String> sig(long t, String secret) {
    return Map.of(
        "Shopfront-Signature", "t=" + t + ",v1=" + ShopfrontConnector.hmac(secret, t, BODY));
  }

  @Test
  void validSignatureParses() throws Exception {
    ProviderStatusChange c = C.parseWebhook(sig(NOW.getEpochSecond(), "whsec"), BODY, NOW);
    assertThat(c.eventId()).isEqualTo("evt_1");
    assertThat(c.providerRef()).isEqualTo("RF-1");
    assertThat(c.newStatus()).isEqualTo("settled");
  }

  @Test
  void wrongSecretOrTamperedBodyIsRejected() {
    assertThatThrownBy(() -> C.parseWebhook(sig(NOW.getEpochSecond(), "other"), BODY, NOW))
        .isInstanceOf(InvalidWebhookException.class);
    byte[] tampered =
        new String(BODY, StandardCharsets.UTF_8)
            .replace("settled\"}", "failed\"}")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> C.parseWebhook(sig(NOW.getEpochSecond(), "whsec"), tampered, NOW))
        .isInstanceOf(InvalidWebhookException.class);
  }

  @Test
  void timestampToleranceIs300Seconds() throws Exception {
    C.parseWebhook(sig(NOW.getEpochSecond() - 300, "whsec"), BODY, NOW);
    assertThatThrownBy(() -> C.parseWebhook(sig(NOW.getEpochSecond() - 301, "whsec"), BODY, NOW))
        .hasMessageContaining("tolerance");
    assertThatThrownBy(() -> C.parseWebhook(Map.of(), BODY, NOW)).hasMessageContaining("missing");
  }
}
