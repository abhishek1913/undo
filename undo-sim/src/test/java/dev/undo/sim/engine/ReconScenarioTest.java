package dev.undo.sim.engine;

import static dev.undo.sim.engine.Harness.action;
import static dev.undo.sim.engine.Harness.refundArgs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.undo.core.engine.UndoException;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.FindingStatus;
import dev.undo.core.model.FindingType;
import dev.undo.core.model.PlanStatus;
import dev.undo.core.model.ReconFinding;
import dev.undo.core.model.ReconRun;
import dev.undo.core.model.ReconRunStatus;
import dev.undo.core.model.Run;
import dev.undo.core.model.Severity;
import dev.undo.core.port.Bug;
import dev.undo.core.recon.ReconService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reconciliation (Section 16) on the in-memory engine with the real connector and router. */
class ReconScenarioTest {
  final Harness h = new Harness();

  {
    h.shopAdmin(
        "POST", "/admin/seed", "{\"seed\":42,\"customers\":60,\"orders\":400,\"tickets\":200}");
  }

  private Action refund(Run run, int skip) {
    String ticket = h.smallDamagedTicket(skip);
    String[] o = h.ownOrder(ticket);
    long amount = Math.min(Long.parseLong(o[1]), 250_000);
    Action a = action(h.propose(run, "shop_issue_refund", refundArgs(ticket, o[0], amount)));
    return h.reload(a);
  }

  /** Recon reads only actions that did not change while it listed: let a second pass. */
  private ReconRun recon() {
    h.advance(Duration.ofSeconds(1));
    ReconRun r = h.recon.runNow("shopfront", "user:test", "w1");
    assertThat(r.status).isEqualTo(ReconRunStatus.COMPLETED);
    return r;
  }

  private List<ReconFinding> findings(FindingType type) {
    return h.store.tx(tx -> tx.recon().findings(null, type, 100));
  }

  /** A refund created at Shopfront by someone else, carrying the given undo_action_id. */
  private void refundAtShopfront(String order, long amount, UUID undoActionId) {
    String body =
        "{\"order_id\":\""
            + order
            + "\",\"amount_minor\":"
            + amount
            + ",\"reason\":\"damaged\",\"metadata\":{\"undo_action_id\":\""
            + undoActionId
            + "\"}}";
    int status =
        h.shop
            .handle(
                dev.shopfront.core.router.ShopRequest.of(
                    "POST",
                    "/v1/refunds",
                    Map.of(
                        "Authorization", "Bearer k", "Idempotency-Key", "manual-" + undoActionId),
                    body))
            .status();
    assertThat(status).isEqualTo(201);
  }

  @Test
  void reconAutoHealsMissedWebhook() {
    Run run = h.run();
    Action a = refund(run, 0);
    assertThat(a.providerStatus).isEqualTo("pending");
    // The refund settles at the provider; the webhook never reaches Undo.
    h.shopAdmin("POST", "/admin/settlement/run?limit=1", null);

    ReconRun r = recon();
    assertThat(h.reload(a).providerStatus).isEqualTo("settled");
    boolean settled = h.store.tx(tx -> tx.ledger().entryExists(a.id, "REFUND_SETTLED"));
    assertThat(settled).isTrue();
    List<ReconFinding> fs = findings(FindingType.STATUS_MISMATCH);
    assertThat(fs).hasSize(1);
    assertThat(fs.get(0).status).isEqualTo(FindingStatus.AUTO_HEALED);
    assertThat(fs.get(0).severity).isEqualTo(Severity.INFO);
    assertThat(fs.get(0).expected.path("provider_status").asText()).isEqualTo("pending");
    assertThat(fs.get(0).actual.path("provider_status").asText()).isEqualTo("settled");
    assertThat(r.stats.path("auto_healed").asInt()).isEqualTo(1);
    assertThat(r.stats.path("matched").asInt()).isZero();

    // A second run finds nothing new: the statuses agree now.
    ReconRun again = recon();
    assertThat(findings(FindingType.STATUS_MISMATCH)).hasSize(1);
    assertThat(again.stats.path("matched").asInt()).isEqualTo(1);
    assertThat(again.stats.path("auto_healed").asInt()).isZero();
  }

  @Test
  void reconAutoHealsGhostEffectForUnknownAction() {
    Run run = h.run();
    h.faults.add(Harness.Fault.DROP_RESPONSE); // applied at Shopfront, response lost
    Action a = refund(run, 0);
    assertThat(a.status).isEqualTo(ActionStatus.UNKNOWN);
    assertThat(h.reload(run).moneyReservedMinor).isEqualTo(a.moneyOutMinor);

    recon();
    Action healed = h.reload(a);
    assertThat(healed.status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(healed.providerRef).startsWith("RF-");
    assertThat(h.refundsFor(a.id)).isEqualTo(1);
    Run after = h.reload(run);
    assertThat(after.moneyReservedMinor).isZero();
    assertThat(after.moneySpentMinor).isEqualTo(a.moneyOutMinor);
    List<ReconFinding> fs = findings(FindingType.GHOST_EFFECT);
    assertThat(fs).hasSize(1);
    assertThat(fs.get(0).status).isEqualTo(FindingStatus.AUTO_HEALED);
    assertThat(fs.get(0).severity).isEqualTo(Severity.WARNING);
    assertThat(fs.get(0).providerObjectRef).isEqualTo(healed.providerRef);
    // The resolver later finds nothing to do: its lease of an UNKNOWN action is gone.
    h.advance(Duration.ofMinutes(5));
    h.work("w1");
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(h.refundsFor(a.id)).isEqualTo(1);
  }

  @Test
  void reconHealsNeedsReviewEmailFromTheExport() {
    Run run = h.run();
    String[] o = h.ownOrder("TKT-0001");
    h.faults.add(Harness.Fault.DROP_RESPONSE);
    Action a =
        action(
            h.propose(
                run,
                "shop_send_email",
                "{\"ticket_id\":\"TKT-0001\",\"customer_id\":\""
                    + o[2]
                    + "\",\"template\":\"need_more_info\",\"order_id\":null,\"amount_minor\":null,\"note\":null}"));
    assertThat(a.status).isEqualTo(ActionStatus.NEEDS_REVIEW);
    h.advance(Duration.ofMinutes(20)); // the export lags
    recon();
    assertThat(h.reload(a).status).isEqualTo(ActionStatus.SUCCEEDED);
    assertThat(h.reload(a).providerRef).startsWith("EM-");
    assertThat(findings(FindingType.GHOST_EFFECT))
        .singleElement()
        .satisfies(f -> assertThat(f.status).isEqualTo(FindingStatus.AUTO_HEALED));
  }

  @Test
  void reconFlagsDuplicateEffectAsCritical() {
    Run run = h.run();
    Action a = refund(run, 0);
    assertThat(a.status).isEqualTo(ActionStatus.SUCCEEDED);
    // Someone creates a second refund at Shopfront carrying the same real undo_action_id.
    refundAtShopfront(a.args.get("order_id").asText(), 100, a.id);
    assertThat(h.refundsFor(a.id)).isEqualTo(2);

    recon();
    List<ReconFinding> fs = findings(FindingType.DUPLICATE_EFFECT);
    assertThat(fs).hasSize(1);
    ReconFinding f = fs.get(0);
    assertThat(f.severity).isEqualTo(Severity.CRITICAL);
    assertThat(f.status).isEqualTo(FindingStatus.OPEN);
    assertThat(f.actionId).isEqualTo(a.id);
    assertThat(f.actual.path("provider_objects").asInt()).isEqualTo(2);
    assertThat(f.fingerprint).isEqualTo("DUPLICATE_EFFECT:" + a.id);

    // FR-RECON-4: re-running does not duplicate the open finding.
    recon();
    assertThat(findings(FindingType.DUPLICATE_EFFECT)).hasSize(1);

    // A person acknowledges and resolves it; a later run opens a new one (still duplicated).
    h.recon.acknowledge(f.id, "user:approver");
    assertThatThrownBy(() -> h.recon.resolve(f.id, " ", null, "user:approver"))
        .isInstanceOf(UndoException.class);
    ReconFinding resolved = h.recon.resolve(f.id, "refunded twice by hand", null, "user:approver");
    assertThat(resolved.status).isEqualTo(FindingStatus.RESOLVED);
    assertThatThrownBy(() -> h.recon.acknowledge(f.id, "user:approver"))
        .isInstanceOfSatisfying(
            UndoException.class,
            e -> assertThat(e.code()).isEqualTo(UndoException.Code.FINDING_NOT_OPEN));
    recon();
    assertThat(findings(FindingType.DUPLICATE_EFFECT)).hasSize(2);
  }

  @Test
  void refundWithAnUnknownUndoActionIdIsUnknownOrigin() {
    String[] o = h.ownOrder(h.smallDamagedTicket(0));
    UUID stranger = UUID.fromString("0192ffff-0000-7000-8000-000000000001");
    refundAtShopfront(o[0], 100, stranger);
    recon();
    assertThat(findings(FindingType.UNKNOWN_ORIGIN))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.severity).isEqualTo(Severity.CRITICAL);
              assertThat(f.actionId).isNull();
              assertThat(f.actual.path("undo_action_id").asText()).isEqualTo(stranger.toString());
            });
  }

  @Test
  void compensatedRefundWithMissedCancelWebhookPostsTheCancelOnce() {
    Run run = h.run();
    Action a = refund(run, 0);
    var plan = h.revertAll(run, "w1");
    assertThat(plan.status).isEqualTo(PlanStatus.REVERTED);
    assertThat(h.reload(a).providerStatus).isEqualTo("pending"); // no webhook reached Undo
    recon();
    assertThat(h.reload(a).providerStatus).isEqualTo("cancelled");
    assertThat(findings(FindingType.COMPENSATION_MISMATCH)).isEmpty();
    assertThat(findings(FindingType.STATUS_MISMATCH)).hasSize(1);
    long cancels =
        h.store.tx(tx -> tx.ledger().entriesByRun(run.id)).stream()
            .filter(e -> e.actionId.equals(a.id) && e.kind.equals("REFUND_CANCELLED"))
            .count();
    assertThat(cancels).isEqualTo(1);
  }

  @Test
  void journalSkipsExistenceCheckBugPostsTheCancelTwice() {
    h.bugs.add(Bug.JOURNAL_SKIPS_EXISTENCE_CHECK);
    Run run = h.run();
    refund(run, 0);
    h.revertAll(run, "w1");
    h.advance(Duration.ofSeconds(1));
    // The heal's journal write hits journal_entries (action_id, kind) unique; the action's check
    // fails and is counted as an error.
    ReconRun r = h.recon.runNow("shopfront", "user:test", "w1");
    assertThat(r.stats.path("errors").asInt()).isEqualTo(1);
    assertThat(h.store.violations).anySatisfy(v -> assertThat(v).contains("journal"));
  }

  @Test
  void oneReconAtATimeAndScheduledRunsWaitForTheInterval() {
    long fence =
        h.locks
            .tryAcquire(ReconService.leaseName("shopfront"), "other", Duration.ofMinutes(5))
            .orElseThrow();
    assertThatThrownBy(() -> h.recon.runNow("shopfront", "user:test", "w1"))
        .isInstanceOfSatisfying(
            UndoException.class,
            e -> assertThat(e.code()).isEqualTo(UndoException.Code.RECON_RUNNING));
    assertThat(h.recon.runScheduledIfDue("shopfront", "w1")).isEmpty();
    h.locks.release(ReconService.leaseName("shopfront"), fence);

    ReconRun first = h.recon.runScheduledIfDue("shopfront", "w1").orElseThrow();
    assertThat(first.triggeredBy).isEqualTo("schedule");
    assertThat(Duration.between(first.windowStart, first.windowEnd))
        .isEqualTo(Duration.ofHours(24).minusMinutes(1));
    h.advance(Duration.ofMinutes(2));
    assertThat(h.recon.runScheduledIfDue("shopfront", "w1")).isEmpty();
    h.advance(Duration.ofMinutes(4));
    ReconRun second = h.recon.runScheduledIfDue("shopfront", "w1").orElseThrow();
    // Incremental window: from the last window end minus the 10 min overlap.
    assertThat(second.windowStart).isEqualTo(first.windowEnd.minus(Duration.ofMinutes(10)));
  }

  @Test
  void crashAfterFetchLeavesARunThatTheNextRunMarksFailed() {
    h.armed.add(dev.undo.core.port.CrashPoint.RECON_AFTER_FETCH);
    assertThatThrownBy(() -> h.recon.runNow("shopfront", "user:test", "w1"))
        .isInstanceOf(dev.undo.sim.SimulatedCrash.class);
    // The dead process keeps the lease until it expires.
    assertThatThrownBy(() -> h.recon.runNow("shopfront", "user:test", "w2"))
        .isInstanceOf(UndoException.class);
    h.advance(Duration.ofMinutes(6));
    ReconRun next = recon();
    List<ReconRun> runs = h.store.tx(tx -> tx.recon().runs(10));
    assertThat(runs).hasSize(2);
    assertThat(runs.get(1).status).isEqualTo(ReconRunStatus.FAILED);
    assertThat(runs.get(1).stats.path("error").asText()).isEqualTo("abandoned");
    assertThat(next.status).isEqualTo(ReconRunStatus.COMPLETED);
  }

  @Test
  void openCountsGroupByTypeAndSeverity() {
    Run run = h.run();
    Action a = refund(run, 0);
    refundAtShopfront(a.args.get("order_id").asText(), 100, a.id);
    recon();
    List<dev.undo.core.port.repo.ReconRepo.OpenCount> counts =
        h.store.tx(tx -> tx.recon().openCounts());
    assertThat(counts)
        .containsExactly(
            new dev.undo.core.port.repo.ReconRepo.OpenCount(
                FindingType.DUPLICATE_EFFECT, Severity.CRITICAL, 1));
  }
}
