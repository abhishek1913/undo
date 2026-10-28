package dev.undo.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.connector.ToolSpec;
import dev.undo.core.connector.Visibility;
import dev.undo.core.engine.Caller;
import dev.undo.core.engine.EventLog;
import dev.undo.core.engine.Metrics;
import dev.undo.core.engine.Ports;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.RunService;
import dev.undo.core.json.Json;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.LlmTurn;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunStatus;
import dev.undo.core.model.Task;
import dev.undo.core.model.TaskStatus;
import dev.undo.core.port.Bug;
import dev.undo.core.port.CrashPoint;
import dev.undo.core.port.ModelClient;
import dev.undo.core.port.TxContext;
import dev.undo.core.render.ToolResultRenderer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The hosted agent runner (Section 17.4). A worker claims an ACTIVE hosted run with a fenced lease
 * and works its tasks in order; every model response is persisted before its tool calls are
 * answered, and every tool call is proposed with {@code agentCallId = tool_use.id}, so a run
 * resumed on another worker after a crash finishes the last persisted turn without creating new
 * actions. Every write is fenced by the run lease: a zombie runner changes nothing.
 */
public final class AgentRunner {
  /** Runner settings (Section 17.1, 26). */
  public record Config(
      String model,
      String effort,
      int maxTokens,
      int maxTurnsPerTask,
      Duration lease,
      Duration heartbeat,
      long toolWaitMs,
      List<Duration> modelBackoff) {
    public static Config defaults() {
      return new Config(
          "claude-opus-5-5",
          "medium",
          16_000,
          12,
          Duration.ofSeconds(60),
          Duration.ofSeconds(15),
          15_000,
          List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)));
    }
  }

  /** The lease moved to another worker (or the run left ACTIVE): stop at once. */
  static final class LeaseLost extends RuntimeException {
    LeaseLost() {
      super("run lease lost", null, false, false);
    }
  }

  private final Ports p;
  private final ProposalService proposals;
  private final RunService runs;
  private final Function<Run, ModelClient> models;
  private final Config cfg;

  /** {@code models} picks the client for a run from its {@code model_mode}. */
  public AgentRunner(
      Ports p,
      ProposalService proposals,
      RunService runs,
      Function<Run, ModelClient> models,
      Config cfg) {
    this.p = p;
    this.proposals = proposals;
    this.runs = runs;
    this.models = models;
    this.cfg = cfg;
  }

  /**
   * Creates an ACTIVE hosted run with its tasks (PENDING, seq 1..n) in one TX. The scenario
   * launcher (server, simulator) decides the task inputs.
   */
  public Run createRun(
      java.util.UUID agentId,
      String title,
      String scenario,
      String modelMode,
      JsonNode labels,
      List<? extends JsonNode> taskInputs) {
    return p.store()
        .tx(
            tx -> {
              Run run =
                  runs.create(
                      tx,
                      new RunService.CreateRun(
                          agentId,
                          dev.undo.core.model.RunKind.HOSTED,
                          title,
                          scenario,
                          modelMode,
                          labels,
                          "support-default",
                          null,
                          null,
                          null,
                          null));
              Instant now = p.clock().now();
              int seq = 0;
              for (JsonNode in : taskInputs) {
                Task t = new Task();
                t.id = p.ids().next();
                t.runId = run.id;
                t.seq = ++seq;
                t.input = in;
                t.status = TaskStatus.PENDING;
                t.createdAt = now;
                tx.tasks().insert(t);
              }
              return run;
            });
  }

  public record Claimed(Run run, long fence) {}

  /** Claims one ACTIVE hosted run whose lease is free or expired. TX: one. */
  public Optional<Claimed> claim(String node) {
    return p.store()
        .tx(
            tx -> {
              Instant now = p.clock().now();
              return tx.runs()
                  .claimHosted(node, now, now.plus(cfg.lease()))
                  .map(r -> new Claimed(r, r.leaseFence));
            });
  }

  /** Works the claimed run until it ends, the lease is lost, or a task cannot go on. */
  public void process(Claimed c, String node) {
    Session s = new Session(c.run().id, c.fence(), node);
    try {
      s.agentName =
          p.store().tx(tx -> tx.agents().get(c.run().agentId).map(a -> a.name).orElse("agent"));
      while (true) {
        s.beat();
        Run run = p.store().tx(tx -> tx.runs().get(s.runId)).orElseThrow();
        if (run.status != RunStatus.ACTIVE || run.leaseFence != s.fence) {
          return;
        }
        List<Task> tasks = p.store().tx(tx -> tx.tasks().byRun(s.runId));
        Task task =
            tasks.stream()
                .filter(t -> t.status == TaskStatus.IN_PROGRESS)
                .findFirst()
                .or(() -> tasks.stream().filter(t -> t.status == TaskStatus.PENDING).findFirst())
                .orElse(null);
        if (task == null) {
          fenced(s, tx -> runs.complete(tx, s.runId, actor(s), s.fence));
          return;
        }
        if (!processTask(s, run, task)) {
          return;
        }
      }
    } catch (LeaseLost e) {
      // another worker owns the run now
    }
  }

  private final class Session {
    final java.util.UUID runId;
    final long fence;
    final String node;
    String agentName = "agent";
    Instant lastBeat;

    Session(java.util.UUID runId, long fence, String node) {
      this.runId = runId;
      this.fence = fence;
      this.node = node;
      this.lastBeat = p.clock().now();
    }

    /** Heartbeat every 15 s; 0 rows means the lease is gone. */
    void beat() {
      Instant now = p.clock().now();
      if (Duration.between(lastBeat, now).compareTo(cfg.heartbeat()) < 0) {
        return;
      }
      boolean ok = p.store().tx(tx -> tx.runs().heartbeat(runId, fence, now.plus(cfg.lease())));
      if (!ok) {
        throw new LeaseLost();
      }
      lastBeat = now;
    }
  }

  private static String actor(Session s) {
    return "runner:" + s.node;
  }

  /** Runs {@code body} in a TX only while this worker still holds the run lease. */
  private <T> T fenced(Session s, Function<TxContext, T> body) {
    return p.store()
        .tx(
            tx -> {
              Run r = tx.runs().lock(s.runId);
              if (r.leaseFence != s.fence) {
                throw new LeaseLost();
              }
              return body.apply(tx);
            });
  }

  /** Returns false when the run must stop (failed or not ACTIVE any more). */
  private boolean processTask(Session s, Run run, Task t) {
    try (Metrics.Span sp =
        p.metrics().span("agent.task", "task", t.id.toString(), "run", s.runId.toString())) {
      return processTask0(s, run, t);
    }
  }

  private boolean processTask0(Session s, Run run, Task t) {
    Task task =
        fenced(
            s,
            tx -> {
              Task cur = tx.tasks().get(t.id).orElseThrow();
              if (cur.status == TaskStatus.PENDING) {
                cur.status = TaskStatus.IN_PROGRESS;
                cur.startedAt = p.clock().now();
                tx.tasks().update(cur);
                EventLog.append(tx, cur.startedAt, cur.runId, null, "task.started", taskData(cur));
              }
              return cur;
            });
    List<LlmTurn> turns = p.store().tx(tx -> tx.turns().byTask(task.id));
    if (!turns.isEmpty()) {
      LlmTurn last = turns.get(turns.size() - 1);
      switch (last.stopReason) {
        case "tool_use" -> {
          if (last.toolResults == null) {
            answer(s, task, last);
          }
        }
        case "end_turn" -> {
          return finishTask(s, task, TaskStatus.DONE, null);
        }
        case "pause_turn" -> {
          // nothing to answer; the next request continues
        }
        default -> {
          return finishTask(s, task, TaskStatus.FAILED, failure(last));
        }
      }
    }
    ModelClient model = models.apply(run);
    List<ToolSpec> tools = agentTools();
    while (true) {
      s.beat();
      Task cur = p.store().tx(tx -> tx.tasks().get(task.id)).orElseThrow();
      List<LlmTurn> history = p.store().tx(tx -> tx.turns().byTask(task.id));
      if (cur.turns >= cfg.maxTurnsPerTask()) {
        return finishTask(s, cur, TaskStatus.FAILED, "max_turns");
      }
      Run now = p.store().tx(tx -> tx.runs().get(s.runId)).orElseThrow();
      if (now.status != RunStatus.ACTIVE) {
        return false;
      }
      if (now.llmTokenCap > 0 && now.llmTokensUsed >= now.llmTokenCap) {
        fenced(s, tx -> runs.fail(tx, s.runId, "llm_budget_exhausted", actor(s), s.fence));
        return false;
      }
      ModelRequest req =
          new ModelRequest(
              modelOf(now),
              SupportAgent.SYSTEM_PROMPT,
              tools,
              Transcript.rebuild(cur, history),
              cfg.maxTokens(),
              cfg.effort(),
              cur.input,
              cur.seq);
      Instant t0 = p.clock().now();
      ModelResponse resp;
      try (Metrics.Span sp = p.metrics().span("agent.turn", "task", cur.id.toString())) {
        resp = call(s, model, req);
        if (resp != null) {
          sp.attr("stop_reason", resp.stopReason());
        }
      }
      if (resp == null) {
        return finishTask(s, cur, TaskStatus.FAILED, "model_unavailable");
      }
      int latency = (int) Duration.between(t0, p.clock().now()).toMillis();
      p.crash().hit(CrashPoint.RUNNER_AFTER_MODEL_RESPONSE);
      LlmTurn turn =
          fenced(
              s,
              tx -> {
                Task tk = tx.tasks().get(task.id).orElseThrow();
                LlmTurn lt = new LlmTurn();
                lt.id = p.ids().next();
                lt.runId = s.runId;
                lt.taskId = tk.id;
                lt.turnNo = tk.turns + 1;
                lt.model = resp.model();
                lt.response = resp.raw();
                lt.stopReason = resp.stopReason();
                lt.inputTokens = resp.inputTokens();
                lt.outputTokens = resp.outputTokens();
                lt.cacheReadTokens = resp.cacheReadTokens();
                lt.cacheWriteTokens = resp.cacheWriteTokens();
                lt.latencyMs = latency;
                lt.runFence = s.fence;
                lt.createdAt = p.clock().now();
                tx.turns().insert(lt);
                tk.turns = lt.turnNo;
                tx.tasks().update(tk);
                tx.runs()
                    .addTokens(
                        s.runId,
                        (long) resp.inputTokens()
                            + resp.outputTokens()
                            + resp.cacheReadTokens()
                            + resp.cacheWriteTokens());
                ObjectNode d = taskData(tk);
                d.put("turn_no", lt.turnNo);
                d.put("stop_reason", lt.stopReason);
                d.put("model", lt.model);
                d.put("tool_uses", Transcript.toolUses(lt.response).size());
                d.put("input_tokens", lt.inputTokens);
                d.put("output_tokens", lt.outputTokens);
                d.put("cache_read_input_tokens", lt.cacheReadTokens);
                d.put("latency_ms", latency);
                EventLog.append(tx, lt.createdAt, s.runId, null, "llm.turn", d);
                return lt;
              });
      p.metrics()
          .count("undo_llm_turns_total", "model", turn.model, "stop_reason", turn.stopReason);
      p.metrics()
          .time(
              "undo_llm_turn_seconds",
              Duration.ofMillis(latency).toNanos(),
              "model",
              turn.model,
              "stop_reason",
              turn.stopReason);
      p.metrics()
          .add("undo_llm_tokens_total", turn.inputTokens, "model", turn.model, "kind", "input");
      p.metrics()
          .add("undo_llm_tokens_total", turn.outputTokens, "model", turn.model, "kind", "output");
      p.metrics()
          .add(
              "undo_llm_tokens_total",
              turn.cacheReadTokens,
              "model",
              turn.model,
              "kind",
              "cache_read");
      p.metrics()
          .add(
              "undo_llm_tokens_total",
              turn.cacheWriteTokens,
              "model",
              turn.model,
              "kind",
              "cache_write");
      if (turn.cacheReadTokens > 0) {
        p.metrics().count("undo_llm_cache_hits_total", "model", turn.model);
      }
      p.crash().hit(CrashPoint.RUNNER_AFTER_TURN_PERSIST);
      switch (turn.stopReason) {
        case "end_turn" -> {
          return finishTask(s, cur, TaskStatus.DONE, null);
        }
        case "tool_use" -> answer(s, cur, turn);
        case "pause_turn" -> {
          // continue: the history already holds the assistant content
        }
        default -> {
          return finishTask(s, cur, TaskStatus.FAILED, failure(turn));
        }
      }
    }
  }

  private static String failure(LlmTurn t) {
    return switch (t.stopReason) {
      case "max_tokens" -> "max_tokens";
      case "refusal" ->
          "refusal:" + t.response.path("stop_details").path("category").asText("unknown");
      default -> "unexpected_stop_reason";
    };
  }

  /** The model call with the runner's own backoff (5 s, 30 s, 2 min) on transient failures. */
  private ModelResponse call(Session s, ModelClient model, ModelRequest req) {
    for (int attempt = 0; ; attempt++) {
      try {
        return model.create(req);
      } catch (ModelException e) {
        p.metrics().count("undo_llm_errors_total", "kind", e.kind().name());
        if (!e.transientFailure() || attempt >= cfg.modelBackoff().size()) {
          return null;
        }
        p.waiter().sleep(cfg.modelBackoff().get(attempt));
        s.beat();
      }
    }
  }

  /** Proposes every tool_use of the turn in order and stores all results in one message. */
  private void answer(Session s, Task task, LlmTurn turn) {
    List<ObjectNode> results = new ArrayList<>();
    for (JsonNode block : Transcript.toolUses(turn.response)) {
      String id = block.path("id").asText();
      ProposalService.Request req =
          new ProposalService.Request(
              Caller.runner(s.node, s.agentName, s.fence),
              s.runId,
              task.id,
              ActionKind.PRIMARY,
              null,
              null,
              block.path("name").asText(),
              block.path("input"),
              p.bugs().on(Bug.RUNNER_NO_CALL_ID) ? null : id,
              null,
              cfg.toolWaitMs());
      ProposalService.Outcome out = proposals.propose(req);
      if (out instanceof ProposalService.Outcome.StaleFence) {
        throw new LeaseLost();
      }
      JsonNode rendered = render(out);
      results.add(Transcript.toolResult(id, rendered, ToolResultRenderer.isError(rendered)));
      s.beat();
    }
    ArrayNode arr = Transcript.arr(results);
    fenced(
        s,
        tx -> {
          LlmTurn cur =
              tx.turns().byTask(task.id).stream()
                  .filter(x -> x.turnNo == turn.turnNo)
                  .findFirst()
                  .orElseThrow();
          if (cur.toolResults == null) {
            cur.toolResults = arr;
            tx.turns().setToolResults(cur);
          }
          return null;
        });
  }

  /** The agent-facing JSON of a proposal outcome (Section 13.4). */
  public static JsonNode render(ProposalService.Outcome o) {
    return switch (o) {
      case ProposalService.Outcome.Recorded rec ->
          ToolResultRenderer.render(rec.action(), rec.original());
      case ProposalService.Outcome.Invalid inv -> ToolResultRenderer.invalid(inv.errors());
      case ProposalService.Outcome.UnknownTool u ->
          ToolResultRenderer.invalid(List.of("unknown tool " + u.tool()));
      case ProposalService.Outcome.KeyReused k ->
          ToolResultRenderer.invalid(List.of("idempotency key reused"));
      case ProposalService.Outcome.StaleFence st -> Json.parse("{\"status\":\"unknown\"}");
    };
  }

  private boolean finishTask(Session s, Task task, TaskStatus status, String reason) {
    fenced(
        s,
        tx -> {
          Task cur = tx.tasks().get(task.id).orElseThrow();
          if (cur.status == TaskStatus.DONE || cur.status == TaskStatus.FAILED) {
            return null;
          }
          cur.status = status;
          cur.failureReason = reason;
          cur.completedAt = p.clock().now();
          tx.tasks().update(cur);
          ObjectNode d = taskData(cur);
          if (reason != null) {
            d.put("reason", reason);
          }
          EventLog.append(
              tx,
              cur.completedAt,
              cur.runId,
              null,
              status == TaskStatus.DONE ? "task.done" : "task.failed",
              d);
          return null;
        });
    p.metrics().count("undo_tasks_total", "status", status.name());
    return true;
  }

  private static ObjectNode taskData(Task t) {
    ObjectNode d = Json.obj();
    d.put("task_id", t.id.toString());
    d.put("task_seq", t.seq);
    d.put("task_status", t.status.name());
    JsonNode in = t.input;
    if (in.hasNonNull("ticket_id")) {
      d.put("ticket_id", in.get("ticket_id").asText());
    }
    if (in.hasNonNull("order_id")) {
      d.put("order_id", in.get("order_id").asText());
    }
    return d;
  }

  private String modelOf(Run run) {
    String m = run.labels == null ? null : run.labels.path("model").asText(null);
    if ("scripted".equals(run.modelMode)) {
      return ScriptedModelClient.MODEL;
    }
    return m != null && !m.isBlank() ? m : cfg.model();
  }

  /** AGENT-visible tools in a stable order (the prompt cache needs byte-stable tools). */
  private List<ToolSpec> agentTools() {
    return p.registry().all().stream()
        .filter(t -> t.visibility() == Visibility.AGENT)
        .sorted(Comparator.comparing(ToolSpec::name))
        .toList();
  }
}
