package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.engine.ApprovalService;
import dev.undo.core.json.Json;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.Run;
import dev.undo.core.port.Store;
import dev.undo.server.security.ConsoleUser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Approvals (FR-APR-2, -3, -8). Agent keys never reach these endpoints (FR-SEC-3). */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalsController {
  private final Store store;
  private final ApprovalService approvals;

  public ApprovalsController(Store store, ApprovalService approvals) {
    this.store = store;
    this.approvals = approvals;
  }

  @GetMapping
  public JsonNode list(
      @AuthenticationPrincipal ConsoleUser u,
      @RequestParam(defaultValue = "PENDING") String status,
      @RequestParam(required = false) Long run,
      @RequestParam(required = false) String tool) {
    u.require(ConsoleUser.VIEWER);
    ApprovalStatus s = status.equals("ALL") ? null : ApprovalStatus.valueOf(status);
    return store.tx(
        tx -> {
          UUID runId =
              run == null
                  ? null
                  : tx.runs().byNumber(run).map((Run r) -> r.id).orElse(new UUID(0, 0));
          ObjectNode o = Json.obj();
          ArrayNode data = o.putArray("data");
          for (Approval ap : tx.approvals().list(s, runId, 500)) {
            ObjectNode card = ConsoleViews.card(tx, ap);
            if (tool == null || tool.isBlank() || tool.equals(card.path("tool").asText())) {
              data.add(card);
            }
          }
          return o;
        });
  }

  @PostMapping("/{id}/approve")
  public JsonNode approve(
      @AuthenticationPrincipal ConsoleUser u, @PathVariable UUID id, @RequestBody JsonNode body) {
    u.require(ConsoleUser.APPROVER);
    approvals.approve(
        id, body.path("args_hash").asText(null), u.id(), u.actor(), body.path("note").asText(null));
    return store.tx(tx -> ConsoleViews.card(tx, tx.approvals().get(id).orElseThrow()));
  }

  @PostMapping("/{id}/reject")
  public JsonNode reject(
      @AuthenticationPrincipal ConsoleUser u, @PathVariable UUID id, @RequestBody JsonNode body) {
    u.require(ConsoleUser.APPROVER);
    approvals.reject(id, u.id(), u.actor(), body.path("note").asText(null));
    return store.tx(tx -> ConsoleViews.card(tx, tx.approvals().get(id).orElseThrow()));
  }

  @PostMapping("/bulk")
  public JsonNode bulk(@AuthenticationPrincipal ConsoleUser u, @RequestBody JsonNode body) {
    u.require(ConsoleUser.APPROVER);
    boolean approve = body.path("decision").asText().equals("approve");
    List<ApprovalService.BulkItem> items = new ArrayList<>();
    for (JsonNode it : body.path("items")) {
      items.add(
          new ApprovalService.BulkItem(
              UUID.fromString(it.path("approval_id").asText()), it.path("args_hash").asText(null)));
    }
    ObjectNode o = Json.obj();
    ArrayNode res = o.putArray("results");
    for (ApprovalService.BulkResult r :
        approvals.bulk(approve, items, u.id(), u.actor(), body.path("note").asText(null))) {
      ObjectNode x = res.addObject();
      x.put("approval_id", r.approvalId().toString());
      x.put("ok", r.ok());
      x.put("code", r.code());
      x.put("message", r.message());
    }
    return o;
  }
}
