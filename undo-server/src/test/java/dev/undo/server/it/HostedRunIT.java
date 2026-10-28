package dev.undo.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.model.Action;
import dev.undo.core.model.LlmTurn;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.core.model.Task;
import dev.undo.core.model.TaskStatus;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hosted scripted runs end to end (Section 17) on Postgres, worked by the IT node's runner loop.
 */
class HostedRunIT extends StackTestSupport {
  @BeforeAll
  static void seed() throws Exception {
    seedOnce();
  }

  @Test
  void scriptedSupportRunWorksEveryTicketThroughTheRunner() throws Exception {
    ConsoleClient admin = ConsoleClient.as("admin@undo.local", "undo-demo-admin");
    ConsoleClient viewer = ConsoleClient.as("viewer@undo.local", "undo-demo-viewer");
    String body =
        "{\"scenario\":\"support-triage\",\"model_mode\":\"scripted\",\"ticket_offset\":40,\"limit\":4,"
            + "\"adversarial\":false,\"repeat_call_rate\":0}";
    assertThat(viewer.post("/api/hosted-runs", body).status()).isEqualTo(403);
    Resp r = admin.post("/api/hosted-runs", body);
    assertThat(r.status()).isEqualTo(201);
    assertThat(r.body().get("tasks").asInt()).isEqualTo(4);
    UUID id = UUID.fromString(r.body().get("id").asText());
    await(
        "hosted run completes",
        Duration.ofSeconds(60),
        () -> store.tx(tx -> tx.runs().get(id)).orElseThrow().status != RunStatus.ACTIVE);
    Run run = store.tx(tx -> tx.runs().get(id)).orElseThrow();
    assertThat(run.status).isEqualTo(RunStatus.COMPLETED);
    assertThat(run.llmTokensUsed).isPositive();
    List<Task> tasks = store.tx(tx -> tx.tasks().byRun(id));
    assertThat(tasks).allMatch(t -> t.status == TaskStatus.DONE);
    Set<String> callIds = new HashSet<>();
    int toolUses = 0;
    for (Task t : tasks) {
      for (LlmTurn turn : store.tx(tx -> tx.turns().byTask(t.id))) {
        for (JsonNode b : turn.response.path("content")) {
          if (b.path("type").asText().equals("tool_use")) {
            toolUses++;
            callIds.add(b.path("id").asText());
          }
        }
      }
    }
    List<Action> actions = actionsOf(id);
    // INV-11: one action per tool_use id, none without one.
    assertThat(actions).allMatch(a -> a.agentCallId != null && callIds.contains(a.agentCallId));
    assertThat(actions.stream().map(a -> a.agentCallId).distinct().count())
        .isEqualTo(actions.size());
    assertThat(actions.size()).isEqualTo(toolUses);
    JsonNode timeline = viewer.get("/api/runs/" + run.number + "/timeline").body();
    assertThat(timeline.toString()).contains("llm.turn");
  }

  @Test
  void claudeModeWithoutAKeyIsRefused() throws Exception {
    ConsoleClient admin = ConsoleClient.as("admin@undo.local", "undo-demo-admin");
    Resp r =
        admin.post(
            "/api/hosted-runs",
            "{\"scenario\":\"support-triage\",\"model_mode\":\"claude\",\"limit\":1}");
    assertThat(r.status()).isEqualTo(422);
    assertThat(r.body().toString()).contains("ANTHROPIC_API_KEY");
  }
}
