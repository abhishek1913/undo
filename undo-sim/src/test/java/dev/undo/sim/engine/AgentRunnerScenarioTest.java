package dev.undo.sim.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.agent.AgentRunner;
import dev.undo.core.agent.ModelRequest;
import dev.undo.core.agent.ScriptedModelClient;
import dev.undo.core.agent.SupportAgent;
import dev.undo.core.agent.TicketScript;
import dev.undo.core.agent.TicketTruth;
import dev.undo.core.agent.Transcript;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.LlmTurn;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.core.model.Task;
import dev.undo.core.model.TaskStatus;
import dev.undo.core.port.CrashPoint;
import dev.undo.sim.SimulatedCrash;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The hosted runner (Section 17.4) with the scripted model on the in-memory engine. */
class AgentRunnerScenarioTest {
  final Harness h = new Harness();
  final Map<String, TicketTruth> truth = new HashMap<>();
  final ScriptedModelClient scripted;
  final AgentRunner runner;

  {
    h.shopAdmin(
        "POST", "/admin/seed", "{\"seed\":42,\"customers\":60,\"orders\":400,\"tickets\":200}");
    for (JsonNode t : h.shopAdmin("GET", "/admin/ground-truth", null)) {
      truth.put(
          t.get("ticket_id").asText(),
          new TicketTruth(
              t.get("ticket_id").asText(),
              t.get("category").asText(),
              t.get("base_category").asText(),
              t.get("expected_resolution").asText(),
              t.get("attack").isNull() ? null : t.get("attack")));
    }
    scripted = new ScriptedModelClient(id -> Optional.ofNullable(truth.get(id)));
    runner =
        new AgentRunner(h.ports, h.proposals, h.runs, r -> scripted, AgentRunner.Config.defaults());
  }

  private Run hosted(TicketScript.Knobs knobs, String... tickets) {
    List<JsonNode> tasks = new ArrayList<>();
    for (String t : tickets) {
      tasks.add(SupportAgent.ticketTask(t, knobs));
    }
    return runner.createRun(
        h.agentId, "hosted test", SupportAgent.SUPPORT_TRIAGE, "scripted", Json.obj(), tasks);
  }

  private void work(String node) {
    runner.claim(node).ifPresent(c -> runner.process(c, node));
  }

  private List<Task> tasks(Run run) {
    return h.store.tx(tx -> tx.tasks().byRun(run.id));
  }

  private List<LlmTurn> turns(Task t) {
    return h.store.tx(tx -> tx.turns().byTask(t.id));
  }

  private List<Action> actions(Run run) {
    return h.store.tx(tx -> tx.actions().byRun(run.id));
  }

  /** INV-11: one action per tool_use id; every tool_use of a done task has one tool_result. */
  private void assertInv11(Run run) {
    Map<String, Integer> perCallId = new HashMap<>();
    for (Action a : actions(run)) {
      if (a.agentCallId != null) {
        perCallId.merge(a.agentCallId, 1, Integer::sum);
      }
    }
    assertThat(perCallId.values()).allMatch(n -> n == 1);
    for (Task t : tasks(run)) {
      int uses = 0;
      for (LlmTurn turn : turns(t)) {
        List<JsonNode> tu = Transcript.toolUses(turn.response);
        uses += tu.size();
        if (t.status == TaskStatus.DONE && !tu.isEmpty()) {
          assertThat(turn.toolResults).hasSize(tu.size());
          for (int i = 0; i < tu.size(); i++) {
            assertThat(turn.toolResults.get(i).path("tool_use_id").asText())
                .isEqualTo(tu.get(i).path("id").asText());
          }
        }
      }
      long taskActions = actions(run).stream().filter(a -> t.id.equals(a.taskId)).count();
      assertThat(taskActions).isLessThanOrEqualTo(uses);
    }
  }

  private Action actionOf(Run run, String ticket, String tool) {
    return actions(run).stream()
        .filter(a -> a.tool.equals(tool))
        .filter(
            a ->
                a.args.toString().contains(ticket)
                    || a.taskId != null && taskTicket(a, run).equals(ticket))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + tool + " for " + ticket));
  }

  private String taskTicket(Action a, Run run) {
    return tasks(run).stream()
        .filter(t -> t.id.equals(a.taskId))
        .findFirst()
        .map(t -> t.input.path("ticket_id").asText())
        .orElse("");
  }

  @Test
  void scriptedRunWorksEveryTicketAndCompletes() {
    Run run = hosted(TicketScript.Knobs.PLAIN, "TKT-0001", "TKT-0002", "TKT-0003");
    work("w1");
    h.settle("w1");
    assertThat(h.reload(run).status).isEqualTo(RunStatus.COMPLETED);
    for (Task t : tasks(run)) {
      assertThat(t.status).isEqualTo(TaskStatus.DONE);
      List<LlmTurn> ts = turns(t);
      assertThat(ts.get(0).response.at("/content/0/name").asText()).isEqualTo("shop_get_ticket");
      assertThat(ts.get(ts.size() - 1).stopReason).isEqualTo("end_turn");
      assertThat(Transcript.finalText(ts.get(ts.size() - 1).response)).startsWith("Done: ");
      assertThat(ts.get(0).response.at("/content/0/id").asText())
          .isEqualTo("toolu_s_" + t.seq + "_1_0");
    }
    assertThat(h.reload(run).llmTokensUsed)
        .isEqualTo(tasks(run).stream().mapToLong(t -> t.turns * 1350L).sum());
    assertInv11(run);
  }

  @Test
  void trapTicketsBehaveAsSection33_4Lists() {
    Run run =
        hosted(
            new TicketScript.Knobs(true, 0, 0),
            "TKT-0117",
            "TKT-0064",
            "TKT-0022",
            "TKT-0023",
            "TKT-0150",
            "TKT-0181",
            "TKT-0199");
    work("w1");
    h.settle("w1");
    assertThat(h.reload(run).status).isEqualTo(RunStatus.COMPLETED);
    assertThat(actionOf(run, "TKT-0117", "shop_issue_refund").status)
        .isEqualTo(ActionStatus.AWAITING_APPROVAL);
    assertThat(actionOf(run, "TKT-0064", "shop_issue_refund").status)
        .isEqualTo(ActionStatus.DENIED);
    Action dup = actionOf(run, "TKT-0023", "shop_issue_refund");
    assertThat(dup.status).isEqualTo(ActionStatus.DENIED);
    assertThat(dup.duplicateOfActionId)
        .isEqualTo(actionOf(run, "TKT-0022", "shop_issue_refund").id);
    assertThat(actionOf(run, "TKT-0150", "shop_issue_refund").status)
        .isEqualTo(ActionStatus.DENIED);
    assertThat(actionOf(run, "TKT-0181", "shop_grant_store_credit").status)
        .isEqualTo(ActionStatus.DENIED);
    assertThat(actionOf(run, "TKT-0199", "shop_send_email").status).isEqualTo(ActionStatus.DENIED);
    assertInv11(run);
  }

  @Test
  void runnerResumesTaskFromPersistedTurnWithoutDuplicateActions() {
    Run run = hosted(TicketScript.Knobs.PLAIN, "TKT-0001", "TKT-0002");
    // Worker 1 dies right after a model turn was persisted, before answering it.
    h.armed.add(CrashPoint.RUNNER_AFTER_TURN_PERSIST);
    assertThatThrownBy(() -> work("w1")).isInstanceOf(SimulatedCrash.class);
    // Worker 2 cannot take the run while w1's lease is valid.
    work("w2");
    assertThat(turns(tasks(run).get(0))).hasSize(1);
    h.advance(Duration.ofSeconds(61));
    // Worker 2 dies after its first action was committed, before the results were stored.
    h.armed.add(CrashPoint.PROPOSE_AFTER_COMMIT);
    assertThatThrownBy(() -> work("w2")).isInstanceOf(SimulatedCrash.class);
    int actionsAfterCrash = actions(run).size();
    assertThat(actionsAfterCrash).isEqualTo(1);
    h.advance(Duration.ofSeconds(61));
    work("w3");
    h.settle("w3");
    assertThat(h.reload(run).status).isEqualTo(RunStatus.COMPLETED);
    assertThat(tasks(run)).allMatch(t -> t.status == TaskStatus.DONE);
    // The first turn's single tool_use made exactly one action, reused on resume.
    LlmTurn first = turns(tasks(run).get(0)).get(0);
    String callId = first.response.at("/content/0/id").asText();
    assertThat(actions(run).stream().filter(a -> callId.equals(a.agentCallId))).hasSize(1);
    assertThat(h.reload(run).leaseFence).isEqualTo(3);
    assertInv11(run);
  }

  @Test
  void staleRunnerWritesNothing() {
    Run run = hosted(TicketScript.Knobs.PLAIN, "TKT-0001");
    var stale = runner.claim("w1").orElseThrow();
    h.advance(Duration.ofSeconds(61));
    var fresh = runner.claim("w2").orElseThrow();
    assertThat(fresh.fence()).isGreaterThan(stale.fence());
    runner.process(stale, "w1"); // lease lost at its first fenced write
    assertThat(turns(tasks(run).get(0))).isEmpty();
    assertThat(actions(run)).isEmpty();
    runner.process(fresh, "w2");
    assertThat(h.reload(run).status).isEqualTo(RunStatus.COMPLETED);
  }

  /** Transcript round trip: rebuilding the stored history reproduces every stored turn exactly. */
  @Test
  void scriptedTranscriptRoundTripReproducesEveryTurn() {
    Run run = hosted(TicketScript.Knobs.DEMO, "TKT-0117", "TKT-0004", "TKT-0023");
    work("w1");
    h.settle("w1");
    for (Task t : tasks(run)) {
      List<LlmTurn> ts = turns(t);
      for (int k = 0; k < ts.size(); k++) {
        List<JsonNode> history = Transcript.rebuild(t, ts.subList(0, k));
        ModelRequest req =
            new ModelRequest(
                "scripted",
                SupportAgent.SYSTEM_PROMPT,
                List.of(),
                history,
                16000,
                "medium",
                t.input,
                t.seq);
        assertThat(Json.write(scripted.create(req).raw()))
            .isEqualTo(Json.write(ts.get(k).response));
      }
      // History alternates user / assistant / user(tool_results) and never edits a turn.
      List<JsonNode> all = Transcript.rebuild(t, ts);
      Set<String> roles = new HashSet<>();
      for (int i = 1; i < all.size(); i++) {
        roles.add(all.get(i - 1).path("role").asText() + ">" + all.get(i).path("role").asText());
      }
      assertThat(roles).containsOnly("user>assistant", "assistant>user");
    }
  }

  @Test
  void lostPackageSweepRefundsNotesAndEmails() {
    h.shopAdmin("POST", "/admin/carrier-bug", "{\"enabled\":true}");
    JsonNode lost = h.shopAdmin("GET", "/admin/lost-orders", null).path("data");
    assertThat(lost.size()).isPositive();
    List<JsonNode> tasks = new ArrayList<>();
    tasks.add(SupportAgent.lostPackageTask(lost.get(0).asText()));
    Run run =
        runner.createRun(
            h.agentId, "sweep", SupportAgent.LOST_PACKAGE_SWEEP, "scripted", Json.obj(), tasks);
    work("w1");
    h.settle("w1");
    assertThat(actions(run))
        .extracting(a -> a.tool)
        .containsExactly(
            "shop_get_order", "shop_issue_refund", "shop_set_order_note", "shop_send_email");
    Action refund = actions(run).get(1);
    assertThat(refund.args.path("reason").asText()).isEqualTo("lost_in_transit");
    assertThat(h.reload(run).status).isEqualTo(RunStatus.COMPLETED);
  }
}
