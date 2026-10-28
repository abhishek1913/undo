package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.engine.RecoveryService;
import dev.undo.core.engine.UndoException;
import dev.undo.core.model.Action;
import dev.undo.core.port.Store;
import dev.undo.server.security.ConsoleUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/actions/{number}; POST /api/actions/{number}/review (FR-REC-5). */
@RestController
@RequestMapping("/api/actions")
public class ActionsController {
  private final Store store;
  private final RecoveryService recovery;

  public ActionsController(Store store, RecoveryService recovery) {
    this.store = store;
    this.recovery = recovery;
  }

  Action action(long number) {
    return store
        .tx(tx -> tx.actions().byNumber(number))
        .orElseThrow(
            () -> new UndoException(UndoException.Code.ACTION_NOT_FOUND, "No action #A-" + number));
  }

  @GetMapping("/{number}")
  public JsonNode get(@AuthenticationPrincipal ConsoleUser u, @PathVariable long number) {
    u.require(ConsoleUser.VIEWER);
    Action a = action(number);
    return store.tx(tx -> ConsoleViews.fullAction(tx, a));
  }

  @PostMapping("/{number}/review")
  public JsonNode review(
      @AuthenticationPrincipal ConsoleUser u,
      @PathVariable long number,
      @RequestBody JsonNode body) {
    u.require(ConsoleUser.APPROVER);
    String outcome = body.path("outcome").asText("");
    if (!outcome.equals("happened") && !outcome.equals("did_not_happen")) {
      throw new UndoException(
          UndoException.Code.INVALID_REQUEST, "outcome must be happened or did_not_happen");
    }
    Action a = action(number);
    Action done =
        recovery.review(
            a.id,
            outcome.equals("happened"),
            body.path("provider_ref").asText(null),
            body.path("provider_status").asText(null),
            body.path("note").asText(null),
            u.actor());
    return store.tx(tx -> ConsoleViews.fullAction(tx, done));
  }
}
