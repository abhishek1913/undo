package dev.undo.server.api.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.Agent;
import dev.undo.core.port.Store;
import dev.undo.server.api.Views;
import dev.undo.server.security.ConsoleUser;
import dev.undo.server.security.KeyService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Agents and API keys (ADMIN). The full key is returned once and never stored. */
@RestController
public class AgentsController {
  private final Store store;
  private final KeyService keys;

  public AgentsController(Store store, KeyService keys) {
    this.store = store;
    this.keys = keys;
  }

  @GetMapping("/api/agents")
  public JsonNode list(@AuthenticationPrincipal ConsoleUser u) {
    u.require(ConsoleUser.ADMIN);
    ObjectNode o = Json.obj();
    ArrayNode data = o.putArray("data");
    for (Agent a : store.tx(tx -> tx.agents().all())) {
      ObjectNode ao = data.addObject();
      ao.put("id", a.id.toString());
      ao.put("name", a.name);
      ao.put("created_at", Views.ts(a.createdAt));
      ArrayNode ks = ao.putArray("keys");
      for (var k : keys.keysOf(a.id)) {
        ObjectNode ko = ks.addObject();
        ko.put("id", k.id().toString());
        ko.put("prefix", k.prefix());
        ArrayNode tools = ko.putArray("allowed_tools");
        k.allowedTools().forEach(tools::add);
        ko.put("default_policy", k.defaultPolicy());
        ko.put("created_at", Views.ts(k.createdAt()));
        ko.put("revoked_at", Views.ts(k.revokedAt()));
      }
    }
    return o;
  }

  @PostMapping("/api/agents")
  public ResponseEntity<JsonNode> create(
      @AuthenticationPrincipal ConsoleUser u, @RequestBody JsonNode body) {
    u.require(ConsoleUser.ADMIN);
    Agent a = keys.createAgent(body.path("name").asText(null));
    ObjectNode o = Json.obj();
    o.put("id", a.id.toString());
    o.put("name", a.name);
    return ResponseEntity.status(201).body(o);
  }

  @PostMapping("/api/agents/{id}/keys")
  public ResponseEntity<JsonNode> createKey(
      @AuthenticationPrincipal ConsoleUser u, @PathVariable UUID id, @RequestBody JsonNode body) {
    u.require(ConsoleUser.ADMIN);
    List<String> tools = new ArrayList<>();
    body.path("allowed_tools").forEach(t -> tools.add(t.asText()));
    KeyService.Created k =
        keys.createKey(id, tools, body.path("default_policy").asText("support-default"));
    ObjectNode o = Json.obj();
    o.put("id", k.keyId().toString());
    o.put("key", k.key());
    o.put("prefix", k.prefix());
    o.put(
        "mcp_command",
        "claude mcp add --transport http undo http://localhost:8080/mcp --header \"Authorization: Bearer "
            + k.key()
            + "\"");
    return ResponseEntity.status(201).body(o);
  }

  @PostMapping("/api/keys/{id}/revoke")
  public JsonNode revoke(@AuthenticationPrincipal ConsoleUser u, @PathVariable UUID id) {
    u.require(ConsoleUser.ADMIN);
    return Json.obj().put("revoked", keys.revoke(id));
  }
}
