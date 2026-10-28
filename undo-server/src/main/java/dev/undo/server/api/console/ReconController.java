package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.connector.shopfront.ShopfrontConnector;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.FindingStatus;
import dev.undo.core.model.FindingType;
import dev.undo.core.model.ReconFinding;
import dev.undo.core.model.ReconRun;
import dev.undo.core.port.Store;
import dev.undo.core.port.TxContext;
import dev.undo.core.recon.ReconService;
import dev.undo.server.api.Views;
import dev.undo.server.config.EngineBeans;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.security.ConsoleUser;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reconciliation runs and findings (Sections 16 and 19.2). */
@RestController
public class ReconController {
  private final Store store;
  private final ReconService recon;
  private final UndoProperties props;
  private final String node;

  public ReconController(
      Store store, ReconService recon, UndoProperties props, EngineBeans.NodeId node) {
    this.store = store;
    this.recon = recon;
    this.props = props;
    this.node = node.value();
  }

  @GetMapping("/api/recon/runs")
  public JsonNode runs(@AuthenticationPrincipal ConsoleUser u) {
    u.require(ConsoleUser.VIEWER);
    return store.tx(
        tx -> {
          ObjectNode o = Json.obj();
          ReconRun last = tx.recon().lastCompleted(ShopfrontConnector.ID).orElse(null);
          o.put("interval_seconds", props.recon().interval().toSeconds());
          o.put(
              "next_scheduled_at",
              last == null || last.finishedAt == null
                  ? null
                  : Views.ts(last.finishedAt.plus(props.recon().interval())));
          ArrayNode data = o.putArray("data");
          for (ReconRun r : tx.recon().runs(50)) {
            data.add(run(r));
          }
          return o;
        });
  }

  @PostMapping("/api/recon/runs")
  public ResponseEntity<JsonNode> runNow(@AuthenticationPrincipal ConsoleUser u) {
    u.require(ConsoleUser.ADMIN);
    ReconRun r = recon.runNow(ShopfrontConnector.ID, u.actor(), node);
    return ResponseEntity.status(201).body(run(r));
  }

  @GetMapping("/api/recon/findings")
  public JsonNode findings(
      @AuthenticationPrincipal ConsoleUser u,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type) {
    u.require(ConsoleUser.VIEWER);
    FindingStatus st = parse(FindingStatus.class, status, "status");
    FindingType ty = parse(FindingType.class, type, "type");
    return store.tx(
        tx -> {
          ObjectNode o = Json.obj();
          ArrayNode data = o.putArray("data");
          for (ReconFinding f : tx.recon().findings(st, ty, 500)) {
            data.add(finding(tx, f));
          }
          return o;
        });
  }

  @PostMapping("/api/recon/findings/{id}/acknowledge")
  public JsonNode acknowledge(@AuthenticationPrincipal ConsoleUser u, @PathVariable UUID id) {
    u.require(ConsoleUser.APPROVER);
    ReconFinding f = recon.acknowledge(id, u.actor());
    return store.tx(tx -> finding(tx, f));
  }

  @PostMapping("/api/recon/findings/{id}/resolve")
  public JsonNode resolve(
      @AuthenticationPrincipal ConsoleUser u,
      @PathVariable UUID id,
      @RequestBody(required = false) JsonNode body) {
    u.require(ConsoleUser.APPROVER);
    String note = body == null ? null : body.path("note").asText(null);
    ReconFinding f = recon.resolve(id, note, u.id(), u.actor());
    return store.tx(tx -> finding(tx, f));
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String v, String name) {
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, v);
    } catch (IllegalArgumentException e) {
      throw new UndoException(UndoException.Code.INVALID_REQUEST, "unknown " + name + ": " + v);
    }
  }

  static ObjectNode run(ReconRun r) {
    ObjectNode o = Json.obj();
    o.put("id", r.id.toString());
    o.put("number", r.number);
    o.put("connector", r.connector);
    o.put("status", r.status.name());
    o.put("triggered_by", r.triggeredBy);
    o.put("window_start", Views.ts(r.windowStart));
    o.put("window_end", Views.ts(r.windowEnd));
    o.put("started_at", Views.ts(r.startedAt));
    o.put("finished_at", Views.ts(r.finishedAt));
    o.set("stats", r.stats);
    return o;
  }

  static ObjectNode finding(TxContext tx, ReconFinding f) {
    ObjectNode o = Json.obj();
    o.put("id", f.id.toString());
    o.put("type", f.type.name());
    o.put("severity", f.severity.name());
    o.put("status", f.status.name());
    o.put("provider_object_ref", f.providerObjectRef);
    o.set("expected", f.expected);
    o.set("actual", f.actual);
    o.put("resolution_note", f.resolutionNote);
    o.put("resolved_at", Views.ts(f.resolvedAt));
    o.put("created_at", Views.ts(f.createdAt));
    if (f.actionId != null) {
      Action a = tx.actions().get(f.actionId).orElse(null);
      if (a != null) {
        o.put("action_id", a.id.toString());
        o.put("action_number", a.number);
        o.put("action_display", a.display());
        o.put("tool", a.tool);
        tx.runs().get(a.runId).ifPresent(run -> o.put("run_number", run.number));
      }
    }
    return o;
  }
}
