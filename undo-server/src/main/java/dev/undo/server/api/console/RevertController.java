package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.engine.UndoException;
import dev.undo.core.model.PlanScope;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.port.Store;
import dev.undo.core.revert.RevertService;
import dev.undo.server.security.ConsoleUser;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Revert plans (Sections 15 and 19.2). Keys cannot revert; only REVERTER or ADMIN sessions. */
@RestController
public class RevertController {
  private final Store store;
  private final RevertService reverts;

  public RevertController(Store store, RevertService reverts) {
    this.store = store;
    this.reverts = reverts;
  }

  @PostMapping("/api/runs/{number}/revert-plans")
  public ResponseEntity<JsonNode> preview(
      @AuthenticationPrincipal ConsoleUser u,
      @PathVariable long number,
      @RequestBody(required = false) JsonNode body) {
    u.require(ConsoleUser.REVERTER);
    String scopeText = body == null ? "RUN" : body.path("scope").asText("RUN");
    PlanScope scope;
    try {
      scope = PlanScope.valueOf(scopeText);
    } catch (IllegalArgumentException e) {
      throw new UndoException(UndoException.Code.INVALID_REQUEST, "scope must be RUN or ACTIONS");
    }
    List<Long> numbers = new ArrayList<>();
    if (body != null) {
      body.path("action_numbers").forEach(n -> numbers.add(n.asLong()));
    }
    RevertPlan p = reverts.preview(number, scope, numbers, u.id(), u.actor());
    return ResponseEntity.status(201).body(view(p));
  }

  @GetMapping("/api/revert-plans/{number}")
  public JsonNode get(@AuthenticationPrincipal ConsoleUser u, @PathVariable long number) {
    u.require(ConsoleUser.VIEWER);
    return view(plan(number));
  }

  @PostMapping("/api/revert-plans/{number}/refresh")
  public JsonNode refresh(@AuthenticationPrincipal ConsoleUser u, @PathVariable long number) {
    u.require(ConsoleUser.REVERTER);
    return view(reverts.refresh(number, u.actor()));
  }

  @PostMapping("/api/revert-plans/{number}/execute")
  public JsonNode execute(
      @AuthenticationPrincipal ConsoleUser u,
      @PathVariable long number,
      @RequestBody JsonNode body) {
    u.require(ConsoleUser.REVERTER);
    if (!body.path("confirm_run_number").canConvertToLong()) {
      throw new UndoException(
          UndoException.Code.CONFIRMATION_MISMATCH, "Send confirm_run_number (the run number).");
    }
    return view(reverts.execute(number, body.get("confirm_run_number").asLong(), u.actor()));
  }

  @PostMapping("/api/revert-plans/{number}/cancel")
  public JsonNode cancel(@AuthenticationPrincipal ConsoleUser u, @PathVariable long number) {
    u.require(ConsoleUser.REVERTER);
    return view(reverts.cancel(number, u.actor()));
  }

  private RevertPlan plan(long number) {
    return store
        .tx(tx -> tx.reverts().planByNumber(number))
        .orElseThrow(
            () -> new UndoException(UndoException.Code.PLAN_NOT_FOUND, "No revert plan " + number));
  }

  private JsonNode view(RevertPlan p) {
    return store.tx(tx -> ConsoleViews.plan(tx, tx.reverts().plan(p.id).orElseThrow()));
  }
}
