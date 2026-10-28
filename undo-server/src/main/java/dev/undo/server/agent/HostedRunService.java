package dev.undo.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.agent.AgentRunner;
import dev.undo.core.agent.SupportAgent;
import dev.undo.core.agent.TicketScript;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.model.Agent;
import dev.undo.core.model.Run;
import dev.undo.core.port.Clock;
import dev.undo.core.port.Ids;
import dev.undo.core.port.Store;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Scenario launcher (Section 17.9): turns a scenario request into a hosted run with one task per
 * ticket (support-triage) or per lost order (lost-package-sweep). Not core code: it pages
 * Shopfront's listings.
 */
@Service
public class HostedRunService {
  public static final String AGENT = "hosted-support";
  private final AgentRunner runner;
  private final ShopfrontHttp shop;
  private final ModelClients models;
  private final Store store;
  private final Ids ids;
  private final Clock clock;

  public HostedRunService(
      AgentRunner runner,
      ShopfrontHttp shop,
      ModelClients models,
      Store store,
      Ids ids,
      Clock clock) {
    this.runner = runner;
    this.shop = shop;
    this.models = models;
    this.store = store;
    this.ids = ids;
    this.clock = clock;
  }

  public Run start(JsonNode req) {
    String scenario = req.path("scenario").asText(SupportAgent.SUPPORT_TRIAGE);
    String mode = req.path("model_mode").asText("scripted");
    if (!mode.equals("scripted") && !mode.equals("claude")) {
      throw new UndoException(
          UndoException.Code.INVALID_REQUEST, "model_mode must be scripted or claude");
    }
    if (mode.equals("claude") && !models.claudeAvailable()) {
      throw new UndoException(
          UndoException.Code.INVALID_REQUEST,
          "Claude mode needs ANTHROPIC_API_KEY on the server. Use model_mode scripted.");
    }
    List<JsonNode> tasks = new ArrayList<>();
    String title;
    switch (scenario) {
      case SupportAgent.SUPPORT_TRIAGE -> {
        int offset = Math.max(0, req.path("ticket_offset").asInt(0));
        int limit = req.path("limit").asInt(20);
        if (limit < 1 || limit > 500) {
          throw new UndoException(UndoException.Code.INVALID_REQUEST, "limit must be 1..500");
        }
        TicketScript.Knobs knobs =
            new TicketScript.Knobs(
                req.path("adversarial").asBoolean(true),
                req.path("repeat_call_rate").asDouble(0.05),
                req.path("parallel_dup_rate").asDouble(0.02));
        List<String> open = openTickets(offset + limit);
        for (String t : open.subList(Math.min(offset, open.size()), open.size())) {
          tasks.add(SupportAgent.ticketTask(t, knobs));
        }
        title = "Support triage · " + tasks.size() + " tickets · " + mode;
      }
      case SupportAgent.LOST_PACKAGE_SWEEP -> {
        for (JsonNode o : shop.admin("GET", "/admin/lost-orders", null).path("data")) {
          tasks.add(SupportAgent.lostPackageTask(o.asText()));
        }
        title = "Lost-package sweep · " + tasks.size() + " orders · " + mode;
      }
      default ->
          throw new UndoException(
              UndoException.Code.INVALID_REQUEST, "unknown scenario " + scenario);
    }
    if (tasks.isEmpty()) {
      throw new UndoException(UndoException.Code.INVALID_REQUEST, "The scenario has no tasks.");
    }
    ObjectNode labels = Json.obj();
    labels.put("scenario", scenario);
    if (req.hasNonNull("model")) {
      labels.put("model", req.get("model").asText());
    }
    return runner.createRun(agentId(), title, scenario, mode, labels, tasks);
  }

  private List<String> openTickets(int max) {
    List<String> out = new ArrayList<>();
    String cursor = null;
    while (out.size() < max) {
      JsonNode page =
          shop.api(
              "GET",
              "/v1/tickets?status=open&limit=100" + (cursor == null ? "" : "&cursor=" + cursor),
              null);
      for (JsonNode t : page.path("data")) {
        if (out.size() < max) {
          out.add(t.path("id").asText());
        }
      }
      if (!page.path("next_cursor").isTextual()) {
        break;
      }
      cursor = page.get("next_cursor").asText();
    }
    return out;
  }

  private java.util.UUID agentId() {
    return store.tx(
        tx ->
            tx.agents()
                .byName(AGENT)
                .map(a -> a.id)
                .orElseGet(
                    () -> {
                      Agent a = new Agent();
                      a.id = ids.next();
                      a.name = AGENT;
                      a.createdAt = clock.now();
                      tx.agents().insert(a);
                      return a.id;
                    }));
  }
}
