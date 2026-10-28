package dev.undo.server.webhook;

import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.connector.InvalidWebhookException;
import dev.undo.core.engine.WebhookService;
import dev.undo.server.api.Problems;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-WH-1: verify HMAC and timestamp, store idempotently, answer 200 after the durable insert. */
@RestController
public class WebhookController {
  private final WebhookService webhooks;

  public WebhookController(WebhookService webhooks) {
    this.webhooks = webhooks;
  }

  @PostMapping("/webhooks/shopfront")
  public ResponseEntity<?> shopfront(HttpServletRequest req) throws IOException {
    byte[] body = req.getInputStream().readAllBytes();
    Map<String, String> headers = new HashMap<>();
    for (String n : Collections.list(req.getHeaderNames())) {
      headers.put(n, req.getHeader(n));
    }
    try {
      WebhookService.Ingest r = webhooks.ingest(ShopfrontConnector.ID, headers, body);
      return ResponseEntity.ok(
          Map.of("received", true, "duplicate", r == WebhookService.Ingest.DUPLICATE));
    } catch (InvalidWebhookException e) {
      return Problems.of(400, "INVALID_WEBHOOK", e.getMessage(), req.getRequestURI());
    }
  }
}
