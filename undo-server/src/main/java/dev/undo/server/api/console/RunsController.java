package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Event;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.model.RunStatus;
import dev.undo.core.port.Store;
import dev.undo.server.security.ConsoleUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Console runs API (Section 19.2). */
@RestController
@RequestMapping("/api/runs")
public class RunsController {
  private final Store store;
  private final RunService runs;

  public RunsController(Store store, RunService runs) {
    this.store = store;
    this.runs = runs;
  }

  Run run(long number) {
    return store
        .tx(tx -> tx.runs().byNumber(number))
        .orElseThrow(
            () -> new UndoException(UndoException.Code.RUN_NOT_FOUND, "No run #" + number));
  }

  @GetMapping
  public JsonNode list(
      @AuthenticationPrincipal ConsoleUser u,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) Long cursor,
      @RequestParam(defaultValue = "50") int limit) {
    u.require(ConsoleUser.VIEWER);
    RunStatus s = status == null || status.isBlank() ? null : RunStatus.valueOf(status);
    RunKind k = kind == null || kind.isBlank() ? null : RunKind.valueOf(kind);
    return store.tx(
        tx -> {
          List<Run> rows = tx.runs().list(s, k, cursor, Math.min(limit, 200));
          ObjectNode o = Json.obj();
          ArrayNode data = o.putArray("data");
          rows.forEach(r -> data.add(ConsoleViews.runSummary(tx, r)));
          o.put(
              "next_cursor",
              rows.size() == Math.min(limit, 200) ? rows.get(rows.size() - 1).number : null);
          return o;
        });
  }

  @GetMapping("/{number}")
  public JsonNode detail(@AuthenticationPrincipal ConsoleUser u, @PathVariable long number) {
    u.require(ConsoleUser.VIEWER);
    Run r = run(number);
    return store.tx(
        tx -> {
          ObjectNode o = ConsoleViews.runSummary(tx, r);
          ObjectNode ledger = ConsoleViews.ledger(tx, r);
          o.set("net_by_account", ledger.get("net_by_account"));
          o.put("net_exposure_minor", ledger.get("net_exposure_minor").asLong());
          o.put(
              "unknown_now",
              tx.actions().countByStatus(r.id).getOrDefault(ActionStatus.UNKNOWN, 0L));
          var tasks = o.putArray("tasks");
          tx.tasks()
              .byRun(r.id)
              .forEach(
                  t -> {
                    ObjectNode to = tasks.addObject();
                    to.put("id", t.id.toString());
                    to.put("seq", t.seq);
                    to.set("input", t.input);
                    to.put("status", t.status.name());
                    to.put("turns", t.turns);
                    to.put("failure_reason", t.failureReason);
                  });
          var plans = o.putArray("revert_plans");
          tx.reverts()
              .byRun(r.id)
              .forEach(
                  p ->
                      plans
                          .addObject()
                          .put("number", p.number)
                          .put("status", p.status.name())
                          .put("scope", p.scope.name()));
          return o;
        });
  }

  @GetMapping("/{number}/timeline")
  public JsonNode timeline(
      @AuthenticationPrincipal ConsoleUser u,
      @PathVariable long number,
      @RequestParam(name = "after_seq", defaultValue = "0") long after) {
    u.require(ConsoleUser.VIEWER);
    Run r = run(number);
    List<Event> events = store.tx(tx -> tx.events().after(after, r.id, 500));
    ObjectNode o = Json.obj();
    ArrayNode data = o.putArray("data");
    events.forEach(e -> data.add(ConsoleViews.event(e)));
    o.put("next_after_seq", events.isEmpty() ? after : events.get(events.size() - 1).seq);
    return o;
  }

  @GetMapping("/{number}/actions")
  public JsonNode actions(
      @AuthenticationPrincipal ConsoleUser u,
      @PathVariable long number,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String tool) {
    u.require(ConsoleUser.VIEWER);
    Run r = run(number);
    List<Action> all = store.tx(tx -> tx.actions().byRun(r.id));
    ObjectNode o = Json.obj();
    ArrayNode data = o.putArray("data");
    for (Action a : all) {
      if ((status == null || status.isBlank() || a.status.name().equals(status))
          && (tool == null || tool.isBlank() || a.tool.equals(tool))) {
        data.add(ConsoleViews.actionRow(a));
      }
    }
    return o;
  }

  @GetMapping("/{number}/ledger")
  public JsonNode ledger(@AuthenticationPrincipal ConsoleUser u, @PathVariable long number) {
    u.require(ConsoleUser.VIEWER);
    Run r = run(number);
    return store.tx(tx -> ConsoleViews.ledger(tx, r));
  }

  @PostMapping("/{number}/freeze")
  public JsonNode freeze(@AuthenticationPrincipal ConsoleUser u, @PathVariable long number) {
    u.require(ConsoleUser.REVERTER);
    Run r = runs.freeze(run(number).id, u.actor());
    return store.tx(tx -> ConsoleViews.runSummary(tx, r));
  }
}
