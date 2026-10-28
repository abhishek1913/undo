package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Run;
import dev.undo.core.port.Store;
import dev.undo.server.agent.HostedRunService;
import dev.undo.server.api.Views;
import dev.undo.server.security.ConsoleUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Starts a hosted scenario run (Sections 17.9, 19.2); a worker's runner then works it. */
@RestController
public class HostedRunsController {
  private final HostedRunService hosted;
  private final Store store;

  public HostedRunsController(HostedRunService hosted, Store store) {
    this.hosted = hosted;
    this.store = store;
  }

  @PostMapping("/api/hosted-runs")
  public ResponseEntity<JsonNode> start(
      @AuthenticationPrincipal ConsoleUser u, @RequestBody(required = false) JsonNode body) {
    u.require(ConsoleUser.ADMIN);
    Run run = hosted.start(body == null ? Json.obj() : body);
    var view = Views.run(run, store);
    view.put("tasks", store.tx(tx -> tx.tasks().byRun(run.id)).size());
    return ResponseEntity.status(201).body(view);
  }
}
