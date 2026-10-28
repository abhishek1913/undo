package dev.undo.sim.store;

import dev.undo.core.model.Action;
import dev.undo.core.model.ActionTransition;
import dev.undo.core.model.Approval;
import dev.undo.core.model.Attempt;
import dev.undo.core.model.Event;
import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.LlmTurn;
import dev.undo.core.model.Posting;
import dev.undo.core.model.ReconFinding;
import dev.undo.core.model.RevertItem;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.model.Task;
import dev.undo.core.model.WebhookEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Read-only view of committed state for invariant checks (rows are shared; do not modify). */
public final class Snapshot {
  private final InMemoryStore s;

  Snapshot(InMemoryStore s) {
    this.s = s;
  }

  public Collection<Run> runs() {
    return s.runs.base.values();
  }

  public Run run(UUID id) {
    return s.runs.base.get(id);
  }

  public Collection<Action> actions() {
    return s.actions.base.values();
  }

  public List<Action> actionsWithStatus(dev.undo.core.model.ActionStatus status) {
    List<Action> out = new java.util.ArrayList<>();
    for (UUID id :
        s.actions.indexData.get("actions_by_status").getOrDefault(status, java.util.Set.of())) {
      out.add(s.actions.base.get(id));
    }
    return out;
  }

  public List<Action> actionsOfRun(UUID runId) {
    return s.committedActionsOfRun(runId);
  }

  public Action action(UUID id) {
    return s.actions.base.get(id);
  }

  public Collection<Approval> approvals() {
    return s.approvals.base.values();
  }

  public Collection<JournalEntry> entries() {
    return s.entries.base.values();
  }

  public Collection<Posting> postings() {
    return s.postings.base.values();
  }

  public JournalEntry entry(UUID id) {
    return s.entries.base.get(id);
  }

  public List<ActionTransition> transitions() {
    return s.transitions;
  }

  public List<ActionTransition> transitionsOf(UUID actionId) {
    return s.transitionsByAction.getOrDefault(actionId, List.of());
  }

  public Collection<RevertPlan> plans() {
    return s.plans.base.values();
  }

  public Collection<RevertItem> items() {
    return s.items.base.values();
  }

  public Collection<ReconFinding> findings() {
    return s.findings.base.values();
  }

  public Collection<WebhookEvent> webhooks() {
    return s.webhooks.base.values();
  }

  public Collection<Task> tasks() {
    return s.tasks.base.values();
  }

  public Collection<LlmTurn> turns() {
    return s.turns.base.values();
  }

  public Collection<Attempt> attempts() {
    return s.attempts.base.values();
  }

  public List<Event> events() {
    return s.events;
  }

  public Approval approvalOf(UUID actionId) {
    for (Approval a : s.approvals.base.values()) {
      if (a.actionId.equals(actionId)) {
        return a;
      }
    }
    return null;
  }
}
