package dev.undo.server.api.demo;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.engine.UndoException;
import dev.undo.server.demo.DemoService;
import dev.undo.server.security.ConsoleUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo API (Section 19.3, ADMIN, only when undo.demo.enabled). M6 adds seed (the end-to-end suite
 * needs a clean store each run); faults, settle, carrier bug and kill-worker come with M8.
 */
@RestController
public class DemoController {
  private final DemoService demo;
  private final dev.undo.core.port.Store store;

  public DemoController(DemoService demo, dev.undo.core.port.Store store) {
    this.demo = demo;
    this.store = store;
  }

  private void demoOnly(ConsoleUser u) {
    if (!demo.enabled()) {
      throw new UndoException(UndoException.Code.FORBIDDEN, "Demo mode is off.");
    }
    u.require(ConsoleUser.ADMIN);
  }

  /** {seed} → Shopfront /admin/seed, then resets Undo data. */
  @PostMapping("/api/demo/seed")
  public ResponseEntity<JsonNode> seed(
      @AuthenticationPrincipal ConsoleUser u, @RequestBody(required = false) JsonNode body)
      throws Exception {
    demoOnly(u);
    return ResponseEntity.ok(demo.seed(body == null ? 42 : body.path("seed").asLong(42)));
  }

  @GetMapping("/api/demo/faults")
  public JsonNode faults(@AuthenticationPrincipal ConsoleUser u) throws Exception {
    demoOnly(u);
    return demo.faults();
  }

  /** {preset} or individual rates → Shopfront /admin/faults. */
  @PostMapping("/api/demo/faults")
  public JsonNode setFaults(@AuthenticationPrincipal ConsoleUser u, @RequestBody JsonNode body)
      throws Exception {
    demoOnly(u);
    return demo.setFaults(body);
  }

  /** {limit, run_number?} → Shopfront settlement, only that run's refunds when given. */
  @PostMapping("/api/demo/settle")
  public JsonNode settle(
      @AuthenticationPrincipal ConsoleUser u, @RequestBody(required = false) JsonNode body)
      throws Exception {
    demoOnly(u);
    int limit = body == null ? 3 : body.path("limit").asInt(3);
    java.util.UUID run = null;
    if (body != null && body.path("run_number").canConvertToLong()) {
      long n = body.get("run_number").asLong();
      run =
          store
              .tx(tx -> tx.runs().byNumber(n))
              .orElseThrow(() -> new UndoException(UndoException.Code.RUN_NOT_FOUND, "No run " + n))
              .id;
    }
    return demo.settle(limit, run);
  }

  @PostMapping("/api/demo/carrier-bug")
  public JsonNode carrierBug(@AuthenticationPrincipal ConsoleUser u, @RequestBody JsonNode body)
      throws Exception {
    demoOnly(u);
    return demo.carrierBug(body.path("enabled").asBoolean(false));
  }

  @PostMapping("/api/demo/kill-worker")
  public JsonNode killWorker(@AuthenticationPrincipal ConsoleUser u) throws Exception {
    demoOnly(u);
    return demo.killWorker();
  }
}
