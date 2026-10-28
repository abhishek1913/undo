package dev.undo.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.agent.AgentRunner;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.engine.Caller;
import dev.undo.core.engine.EngineConfig;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.Registry;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.model.RunStatus;
import dev.undo.core.port.Ids;
import dev.undo.core.port.Store;
import dev.undo.core.render.ToolResultRenderer;
import dev.undo.server.config.EngineBeans;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.security.AgentPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP server (Section 18), Streamable HTTP with JSON responses. Hand-written JSON-RPC subset
 * ({@code initialize}, {@code notifications/initialized}, {@code ping}, {@code tools/list}, {@code
 * tools/call}), as 18 allows (ADR-008). Auth: the agent key (ApiKeyFilter). One MCP session = one
 * EXTERNAL run; {@code DELETE /mcp} completes it.
 */
@RestController
public class McpController {
  static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
  static final String SESSION = "Mcp-Session-Id";

  private final RunService runs;
  private final ProposalService proposals;
  private final Registry registry;
  private final Store store;
  private final Ids ids;
  private final EngineConfig cfg;
  private final String node;
  private final boolean inline;

  public McpController(
      RunService runs,
      ProposalService proposals,
      Registry registry,
      Store store,
      Ids ids,
      EngineConfig cfg,
      UndoProperties props,
      EngineBeans.NodeId node) {
    this.runs = runs;
    this.proposals = proposals;
    this.registry = registry;
    this.store = store;
    this.ids = ids;
    this.cfg = cfg;
    this.node = node.value();
    this.inline = props.execution().inline() && props.api();
  }

  @GetMapping("/mcp")
  public ResponseEntity<Void> get() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .header("Allow", "POST, DELETE")
        .build();
  }

  @DeleteMapping("/mcp")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal AgentPrincipal agent,
      @RequestHeader(value = SESSION, required = false) String session) {
    Run run = session == null ? null : sessionRun(agent, session);
    if (run == null) {
      return ResponseEntity.notFound().build();
    }
    if (run.status == RunStatus.ACTIVE) {
      runs.complete(run.id, "mcp:" + agent.agentName());
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<JsonNode> post(
      @AuthenticationPrincipal AgentPrincipal agent,
      @RequestHeader(value = SESSION, required = false) String session,
      @RequestHeader(value = "X-Undo-Run-Id", required = false) String attachRun,
      @RequestBody JsonNode msg) {
    if (msg.isArray()) {
      return ResponseEntity.badRequest().body(error(null, -32600, "Batches are not supported."));
    }
    JsonNode id = msg.get("id");
    String method = msg.path("method").asText("");
    JsonNode params = msg.path("params");
    if (id == null || id.isNull()) {
      // A notification (notifications/initialized and others): accepted, no body.
      return ResponseEntity.accepted().build();
    }
    if (method.equals("initialize")) {
      return initialize(agent, id, params, attachRun);
    }
    if (method.equals("ping")) {
      return ResponseEntity.ok(result(id, Json.obj()));
    }
    if (session == null) {
      return ResponseEntity.badRequest().body(error(id, -32600, "Missing Mcp-Session-Id header."));
    }
    Run run = sessionRun(agent, session);
    if (run == null) {
      return ResponseEntity.status(404).body(error(id, -32001, "Unknown or ended session."));
    }
    return switch (method) {
      case "tools/list" -> ResponseEntity.ok(result(id, toolsList(agent)));
      case "tools/call" -> ResponseEntity.ok(call(agent, run, id, params));
      default -> ResponseEntity.ok(error(id, -32601, "Method not found: " + method));
    };
  }

  private ResponseEntity<JsonNode> initialize(
      AgentPrincipal agent, JsonNode id, JsonNode params, String attachRun) {
    String asked = params.path("protocolVersion").asText("");
    String version = VERSIONS.contains(asked) ? asked : VERSIONS.get(0);
    String sessionId = "mcp_" + ids.next().toString().replace("-", "");
    Run run;
    if (attachRun != null && !attachRun.isBlank()) {
      UUID rid;
      try {
        rid = UUID.fromString(attachRun);
      } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest()
            .body(error(id, -32602, "X-Undo-Run-Id is not a run id."));
      }
      run =
          store.tx(
              tx -> {
                Run r = tx.runs().lock(rid);
                if (!r.agentId.equals(agent.agentId()) || r.status != RunStatus.ACTIVE) {
                  throw new UndoException(
                      UndoException.Code.RUN_NOT_FOUND,
                      "No ACTIVE run " + rid + " for this agent.");
                }
                r.mcpSessionId = sessionId;
                tx.runs().update(r);
                return r;
              });
    } else {
      String client = params.path("clientInfo").path("name").asText("client");
      run =
          runs.create(
              new RunService.CreateRun(
                  agent.agentId(),
                  RunKind.EXTERNAL,
                  "MCP: " + client,
                  null,
                  null,
                  null,
                  agent.defaultPolicy(),
                  null,
                  null,
                  null,
                  sessionId));
    }
    ObjectNode r = Json.obj();
    r.put("protocolVersion", version);
    r.putObject("capabilities").putObject("tools").put("listChanged", false);
    ObjectNode info = r.putObject("serverInfo");
    info.put("name", "undo");
    info.put("version", "0.1.0");
    r.put(
        "instructions",
        "Every tool call goes through Undo: policy, approvals, exactly-once execution and revert."
            + " This session is Undo run #"
            + run.number
            + ".");
    return ResponseEntity.ok().header(SESSION, sessionId).body(result(id, r));
  }

  private ObjectNode toolsList(AgentPrincipal agent) {
    ObjectNode r = Json.obj();
    ArrayNode tools = r.putArray("tools");
    for (ToolSpec t : registry.all()) {
      if (t.visibility() == Visibility.AGENT && agent.mayUse(t.name())) {
        ObjectNode o = tools.addObject();
        o.put("name", t.name());
        o.put("description", t.description());
        o.set("inputSchema", t.inputSchema());
      }
    }
    return r;
  }

  private ObjectNode call(AgentPrincipal agent, Run run, JsonNode id, JsonNode params) {
    String name = params.path("name").asText("");
    ToolSpec tool = registry.tool(name);
    if (tool == null || tool.visibility() != Visibility.AGENT || !agent.mayUse(name)) {
      return error(id, -32602, "Unknown tool: " + name);
    }
    JsonNode args = params.path("arguments").isObject() ? params.get("arguments") : Json.obj();
    String key = params.path("_meta").path("undo.dev/idempotency-key").asText(null);
    ProposalService.Outcome out =
        proposals.propose(
            ProposalService.Request.primary(
                    Caller.mcp(node, agent.agentName(), inline), run.id, name, args)
                .withClientKey(key)
                .withWait(Math.min(10_000, cfg.maxWaitMs())));
    JsonNode rendered = AgentRunner.render(out);
    ObjectNode r = Json.obj();
    ObjectNode text = r.putArray("content").addObject();
    text.put("type", "text");
    text.put("text", Json.write(rendered));
    r.put("isError", ToolResultRenderer.isError(rendered));
    return result(id, r);
  }

  private Run sessionRun(AgentPrincipal agent, String session) {
    return store
        .tx(tx -> tx.runs().byMcpSession(session))
        .filter(r -> r.agentId.equals(agent.agentId()))
        .orElse(null);
  }

  private static ObjectNode result(JsonNode id, JsonNode result) {
    ObjectNode o = Json.obj();
    o.put("jsonrpc", "2.0");
    o.set("id", id);
    o.set("result", result);
    return o;
  }

  private static ObjectNode error(JsonNode id, int code, String message) {
    ObjectNode o = Json.obj();
    o.put("jsonrpc", "2.0");
    o.set("id", id);
    ObjectNode e = o.putObject("error");
    e.put("code", code);
    e.put("message", message);
    return o;
  }
}
