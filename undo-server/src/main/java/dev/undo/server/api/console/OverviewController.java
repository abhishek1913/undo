package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.engine.Registry;
import dev.undo.core.json.Json;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.core.model.Severity;
import dev.undo.core.port.Clock;
import dev.undo.core.port.Store;
import dev.undo.server.api.Views;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.security.ConsoleUser;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/overview (home tiles) and GET /api/tools (all tools, INTERNAL marked). */
@RestController
public class OverviewController {
  private final Store store;
  private final Clock clock;
  private final Registry registry;
  private final UndoProperties props;

  public OverviewController(Store store, Clock clock, Registry registry, UndoProperties props) {
    this.store = store;
    this.clock = clock;
    this.registry = registry;
    this.props = props;
  }

  @GetMapping("/api/overview")
  public JsonNode overview(@AuthenticationPrincipal ConsoleUser u) {
    u.require(ConsoleUser.VIEWER);
    return store.tx(
        tx -> {
          Map<ActionStatus, Long> counts = tx.actions().globalCounts();
          ObjectNode o = Json.obj();
          ObjectNode tiles = o.putObject("tiles");
          tiles.put("active_runs", tx.runs().list(RunStatus.ACTIVE, null, null, 10_000).size());
          tiles.put(
              "pending_approvals",
              tx.approvals().list(ApprovalStatus.PENDING, null, 10_000).size());
          tiles.put("unknown_now", counts.getOrDefault(ActionStatus.UNKNOWN, 0L));
          tiles.put("needs_review", counts.getOrDefault(ActionStatus.NEEDS_REVIEW, 0L));
          tiles.put(
              "open_critical_findings",
              tx.recon().openCounts().stream()
                  .filter(c -> c.severity() == Severity.CRITICAL)
                  .mapToLong(c -> c.count())
                  .sum());
          tiles.put(
              "money_out_today_minor",
              tx.actions()
                  .moneyOutSince(
                      clock.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant()));
          tiles.put("in_flight", tx.actions().countInFlight());
          ArrayNode activity = o.putArray("activity");
          long max = tx.events().maxSeq();
          var events = tx.events().after(Math.max(0, max - 30), null, 30);
          for (int i = events.size() - 1; i >= 0; i--) {
            activity.add(ConsoleViews.event(events.get(i)));
          }
          ArrayNode runs = o.putArray("runs");
          for (Run r : tx.runs().list(null, null, null, 10)) {
            runs.add(ConsoleViews.runSummary(tx, r));
          }
          o.put("demo", props.demo().enabled());
          o.put("environment", props.demo().enabled() ? "local demo" : "local");
          o.put("latest_seq", max);
          return o;
        });
  }

  @GetMapping("/api/tools")
  public JsonNode tools(@AuthenticationPrincipal ConsoleUser u) {
    u.require(ConsoleUser.VIEWER);
    ArrayNode a = Json.arr();
    for (ToolSpec t : registry.all()) {
      ObjectNode o = Views.tool(t);
      o.put("connector", t.connectorId());
      o.put("internal", t.visibility() == dev.undo.core.connector.Visibility.INTERNAL);
      a.add(o);
    }
    return Json.obj().set("tools", a);
  }
}
