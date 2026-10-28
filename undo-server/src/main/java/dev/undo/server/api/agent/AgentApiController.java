package dev.undo.server.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.engine.Caller;
import dev.undo.core.engine.EngineConfig;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.Registry;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.port.Store;
import dev.undo.core.render.ToolResultRenderer;
import dev.undo.server.api.Problems;
import dev.undo.server.api.Views;
import dev.undo.server.config.EngineBeans;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.security.AgentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent API (Section 19.1): bearer API key, JSON, snake_case. */
@RestController
@RequestMapping("/v1")
public class AgentApiController {
  private final RunService runs;
  private final ProposalService proposals;
  private final Registry registry;
  private final Store store;
  private final EngineConfig cfg;
  private final String node;
  private final boolean inline;

  public AgentApiController(
      RunService runs,
      ProposalService proposals,
      Registry registry,
      Store store,
      EngineConfig cfg,
      EngineBeans.NodeId node,
      UndoProperties props) {
    this.runs = runs;
    this.proposals = proposals;
    this.registry = registry;
    this.store = store;
    this.cfg = cfg;
    this.node = node.value();
    this.inline = props.execution().inline() && props.api();
  }

  @PostMapping("/runs")
  public ResponseEntity<JsonNode> createRun(
      @AuthenticationPrincipal AgentPrincipal agent, @RequestBody(required = false) JsonNode body) {
    JsonNode b = body == null ? Json.obj() : body;
    JsonNode budgets = b.path("budgets");
    Run run =
        runs.create(
            new RunService.CreateRun(
                agent.agentId(),
                RunKind.EXTERNAL,
                b.path("title").asText("REST run"),
                null,
                null,
                b.path("labels").isObject() ? b.get("labels") : null,
                b.path("policy").asText(agent.defaultPolicy()),
                budgets.path("money_out_minor").isIntegralNumber()
                    ? budgets.get("money_out_minor").asLong()
                    : null,
                budgets.path("actions").isIntegralNumber() ? budgets.get("actions").asInt() : null,
                null,
                null));
    return ResponseEntity.status(201).body(Views.run(run, store));
  }

  @GetMapping("/runs/{id}")
  public JsonNode getRun(@AuthenticationPrincipal AgentPrincipal agent, @PathVariable UUID id) {
    return Views.run(ownRun(agent, id), store);
  }

  @PostMapping("/runs/{id}/complete")
  public JsonNode complete(@AuthenticationPrincipal AgentPrincipal agent, @PathVariable UUID id) {
    ownRun(agent, id);
    return Views.run(runs.complete(id, "agent:" + agent.agentName()), store);
  }

  @PostMapping("/runs/{id}/actions")
  public ResponseEntity<JsonNode> propose(
      @AuthenticationPrincipal AgentPrincipal agent,
      @PathVariable UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body,
      HttpServletRequest req) {
    ownRun(agent, id);
    String toolName = body.path("tool").asText("");
    ToolSpec tool = registry.tool(toolName);
    if (tool == null || tool.visibility() != Visibility.AGENT || !agent.mayUse(toolName)) {
      throw new UndoException(
          UndoException.Code.UNKNOWN_TOOL, "No tool " + toolName + " for this key.");
    }
    long wait =
        body.path("wait_ms").isIntegralNumber()
            ? body.get("wait_ms").asLong()
            : cfg.defaultWaitMs();
    JsonNode args = body.has("args") ? body.get("args") : Json.obj();
    ProposalService.Outcome out =
        proposals.propose(
            ProposalService.Request.primary(
                    Caller.api(node, agent.agentName(), inline), id, toolName, args)
                .withClientKey(key)
                .withWait(Math.min(wait, cfg.maxWaitMs())));
    return switch (out) {
      case ProposalService.Outcome.Recorded r -> {
        ObjectNode rendered = ToolResultRenderer.render(r.action(), r.original());
        rendered.set("action_detail", Views.agentAction(r.action()));
        yield ResponseEntity.ok()
            .header(
                "Server-Timing",
                String.format(
                    Locale.ROOT,
                    "undo;dur=%.2f, provider;dur=%.2f",
                    r.undoNanos() / 1e6,
                    r.providerNanos() / 1e6))
            .body(rendered);
      }
      case ProposalService.Outcome.Invalid inv -> {
        ObjectNode p =
            Problems.body(
                422,
                "INVALID_ARGS",
                "The arguments do not match the tool's schema.",
                req.getRequestURI());
        ArrayNode errs = p.putArray("errors");
        inv.errors().forEach(errs::add);
        yield ResponseEntity.status(422).header("Content-Type", "application/problem+json").body(p);
      }
      case ProposalService.Outcome.UnknownTool u ->
          throw new UndoException(UndoException.Code.UNKNOWN_TOOL, "No tool " + u.tool());
      case ProposalService.Outcome.KeyReused k ->
          throw new UndoException(
              UndoException.Code.IDEMPOTENCY_KEY_REUSED,
              "This Idempotency-Key was used for "
                  + k.existing().display()
                  + " with other arguments.");
      case ProposalService.Outcome.StaleFence s ->
          throw new UndoException(UndoException.Code.STALE_FENCE, "stale fence");
    };
  }

  @GetMapping("/actions/{id}")
  public JsonNode action(@AuthenticationPrincipal AgentPrincipal agent, @PathVariable UUID id) {
    Action a =
        store
            .tx(tx -> tx.actions().get(id))
            .orElseThrow(
                () -> new UndoException(UndoException.Code.ACTION_NOT_FOUND, "No action " + id));
    ownRun(agent, a.runId);
    ObjectNode o = Views.agentAction(a);
    o.set("rendered", ToolResultRenderer.render(a, null));
    return o;
  }

  @GetMapping("/tools")
  public JsonNode tools(@AuthenticationPrincipal AgentPrincipal agent) {
    ArrayNode a = Json.arr();
    for (ToolSpec t : registry.all()) {
      if (t.visibility() == Visibility.AGENT && agent.mayUse(t.name())) {
        a.add(Views.tool(t));
      }
    }
    ObjectNode o = Json.obj();
    o.set("tools", a);
    return o;
  }

  private Run ownRun(AgentPrincipal agent, UUID id) {
    Run r = store.tx(tx -> tx.runs().get(id)).orElse(null);
    if (r == null || !r.agentId.equals(agent.agentId())) {
      throw new UndoException(UndoException.Code.RUN_NOT_FOUND, "No run " + id);
    }
    return r;
  }

  static Map<ActionStatus, Long> counts(Store store, UUID runId) {
    return store.tx(tx -> tx.actions().countByStatus(runId));
  }
}
