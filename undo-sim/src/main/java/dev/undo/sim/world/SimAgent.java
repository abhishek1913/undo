package dev.undo.sim.world;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undo.core.agent.TicketScript;
import dev.undo.core.agent.TicketTruth;
import dev.undo.core.engine.Caller;
import dev.undo.core.engine.ProposalService;
import dev.undo.core.engine.RunService;
import dev.undo.core.engine.UndoException;
import dev.undo.core.json.Json;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.render.ToolResultRenderer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Drives one EXTERNAL run like a REST client would: creates the run, works its tickets with the
 * scripted ticket agent (optionally several tickets in parallel), and completes the run. Every call
 * goes to a random live API node; when that node dies mid-request the agent retries with the same
 * Idempotency-Key.
 */
final class SimAgent {
  private final World w;
  private final int index;
  private final SplittableRandom r;
  private final List<String> tickets;
  private final int concurrency;
  private final long moneyCap;
  private final int actionCap;
  UUID runId;
  boolean done;

  SimAgent(
      World w,
      int index,
      SplittableRandom r,
      List<String> tickets,
      int concurrency,
      long moneyCap,
      int actionCap) {
    this.w = w;
    this.index = index;
    this.r = r;
    this.tickets = tickets;
    this.concurrency = concurrency;
    this.moneyCap = moneyCap;
    this.actionCap = actionCap;
  }

  void start(long atMicros) {
    w.loop.at(
        atMicros,
        "agent " + index + " start",
        () -> w.sched.spawn("agent " + index, w.immortal, this::body));
  }

  private void body() {
    Run run =
        rpc(
            "create run",
            wk ->
                wk.runs.create(
                    new RunService.CreateRun(
                        w.agentId,
                        RunKind.EXTERNAL,
                        "Sim run " + index,
                        "support-triage",
                        "scripted",
                        null,
                        "support-default",
                        moneyCap > 0 ? moneyCap : null,
                        actionCap > 0 ? actionCap : null,
                        null,
                        null)));
    runId = run.id;
    w.onRunCreated(run);
    Deque<String> queue = new ArrayDeque<>(tickets);
    List<Runnable> lanes = new ArrayList<>();
    for (int i = 0; i < concurrency; i++) {
      int lane = i;
      lanes.add(
          () -> {
            while (!queue.isEmpty()) {
              String t = queue.poll();
              if (!work(t, lane)) {
                return; // the run is no longer active
              }
            }
          });
    }
    w.parallel("agent " + index + " lanes", lanes);
    try {
      rpc("complete run", wk -> wk.runs.complete(runId, "agent:demo-support"));
    } catch (UndoException e) {
      // RUN_NOT_ACTIVE: frozen by the operator meanwhile
    }
    done = true;
    w.operator.onRunEnded(run);
  }

  /** Returns false when the run stopped accepting work. */
  private boolean work(String ticketId, int lane) {
    TicketTruth truth = w.truth.get(ticketId);
    TicketScript script = new TicketScript(ticketId, truth, w.knobs, r.split()::nextDouble);
    List<JsonNode> results = List.of();
    int turn = 0;
    while (true) {
      List<TicketScript.Call> calls = script.next(results);
      if (calls.isEmpty()) {
        return true;
      }
      turn++;
      List<JsonNode> out = new ArrayList<>();
      if (calls.size() == 1) {
        out.add(call(ticketId, turn, 0, calls.get(0)));
      } else {
        JsonNode[] box = new JsonNode[calls.size()];
        List<Runnable> par = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
          int k = i;
          int t = turn;
          par.add(() -> box[k] = call(ticketId, t, k, calls.get(k)));
        }
        w.parallel("agent " + index + " parallel", par);
        for (JsonNode b : box) {
          out.add(b);
        }
      }
      for (JsonNode o : out) {
        if ("denied".equals(o.path("status").asText())
            && "This run is no longer active.".equals(o.path("reason").asText())) {
          return false;
        }
      }
      results = out;
    }
  }

  private JsonNode call(String ticketId, int turn, int slot, TicketScript.Call c) {
    String key = "r" + index + ":" + ticketId + ":" + turn + ":" + slot;
    ProposalService.Outcome o =
        rpc(
            "propose " + c.tool(),
            wk ->
                wk.proposals.propose(
                    ProposalService.Request.primary(
                            Caller.api(wk.node, "demo-support", true), runId, c.tool(), c.args())
                        .withClientKey(key)
                        .withWait(w.params.waitMs())));
    return render(o);
  }

  static JsonNode render(ProposalService.Outcome o) {
    return switch (o) {
      case ProposalService.Outcome.Recorded rec ->
          ToolResultRenderer.render(rec.action(), rec.original());
      case ProposalService.Outcome.Invalid inv -> ToolResultRenderer.invalid(inv.errors());
      case ProposalService.Outcome.UnknownTool u ->
          ToolResultRenderer.invalid(List.of("unknown tool " + u.tool()));
      case ProposalService.Outcome.KeyReused k ->
          ToolResultRenderer.invalid(List.of("idempotency key reused"));
      case ProposalService.Outcome.StaleFence s -> Json.parse("{\"status\":\"unknown\"}");
    };
  }

  interface WorkerCall<T> {
    T apply(SimWorker wk);
  }

  /** A request to a random live API node; retried (same key) when that node dies. */
  private <T> T rpc(String name, WorkerCall<T> f) {
    for (int attempt = 0; ; attempt++) {
      SimWorker wk = w.randomLiveWorker(r);
      if (wk == null) {
        w.sched.sleep(
            WorldParams.s(1) + (long) (r.nextDouble() * WorldParams.s(4)), "agent wait for a node");
        continue;
      }
      try {
        return w.sched.call(wk.owner, name + " on " + wk.node, () -> f.apply(wk));
      } catch (CoopScheduler.RpcFailed e) {
        w.sched.sleep(
            WorldParams.s(1) + (long) (r.nextDouble() * WorldParams.s(4)), "agent retry " + name);
      }
    }
  }
}
