package dev.undo.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.undo.core.connector.Effect;
import dev.undo.core.connector.IdempotencyClass;
import dev.undo.core.json.Json;
import dev.undo.core.model.Action;
import dev.undo.core.model.ActionKind;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.Agent;
import dev.undo.core.model.Approval;
import dev.undo.core.model.ApprovalStatus;
import dev.undo.core.model.FindingStatus;
import dev.undo.core.model.FindingType;
import dev.undo.core.model.JournalEntry;
import dev.undo.core.model.PlanScope;
import dev.undo.core.model.PlanStatus;
import dev.undo.core.model.PolicyVersion;
import dev.undo.core.model.Posting;
import dev.undo.core.model.ReconFinding;
import dev.undo.core.model.ReconRun;
import dev.undo.core.model.ReconRunStatus;
import dev.undo.core.model.RevertPlan;
import dev.undo.core.model.Run;
import dev.undo.core.model.RunKind;
import dev.undo.core.model.RunStatus;
import dev.undo.core.port.ConstraintViolationException;
import dev.undo.core.port.Store;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Section 22.3: the same cases run against InMemoryStore (undo-sim) and PostgresStore
 * (undo-server), so the simulator never proves something about a store that behaves differently.
 */
public abstract class StoreContractTest {
  protected Store store;
  protected final Instant now = Instant.parse("2026-10-24T10:00:00Z");
  private static final AtomicLong SEQ =
      new AtomicLong(System.identityHashCode(StoreContractTest.class));
  protected UUID agentId;
  protected UUID policyId;
  protected UUID userId;

  /** A fresh, empty store. Implementations may also insert a user with id {@link #userId}. */
  protected abstract Store createStore(UUID userId);

  protected static UUID id() {
    long n = SEQ.incrementAndGet();
    return new UUID(0x0192000000007000L | (n >>> 48), 0x8000000000000000L | (n & 0xFFFFFFFFFFFFL));
  }

  @BeforeEach
  void setUpStore() {
    userId = id();
    store = createStore(userId);
    agentId = id();
    policyId = id();
    store.tx(
        tx -> {
          Agent a = new Agent();
          a.id = agentId;
          a.name = "agent-" + agentId;
          a.createdAt = now;
          tx.agents().insert(a);
          PolicyVersion p = new PolicyVersion();
          p.id = policyId;
          p.name = "p-" + policyId.toString().substring(28);
          p.version = 1;
          p.sourceYaml = "x";
          p.compiled = Json.obj();
          p.sha256 = "x";
          p.createdBy = "test";
          p.createdAt = now;
          tx.policies().insert(p);
          return null;
        });
  }

  protected Run run(long cap, int actionCap) {
    return run(cap, actionCap, RunKind.EXTERNAL);
  }

  protected Run run(long cap, int actionCap, RunKind kind) {
    Run r = new Run();
    r.id = id();
    r.agentId = agentId;
    r.kind = kind;
    r.status = RunStatus.ACTIVE;
    r.title = "t";
    r.labels = Json.obj();
    r.policyVersionId = policyId;
    r.moneyCapMinor = cap;
    r.actionCap = actionCap;
    r.lastActivityAt = now;
    r.createdAt = now;
    return store.tx(tx -> tx.runs().insert(r));
  }

  protected Action action(UUID runId, ActionStatus status) {
    Action a = new Action();
    a.id = id();
    a.runId = runId;
    a.kind = ActionKind.PRIMARY;
    a.connector = "shopfront";
    a.tool = "shop_issue_refund";
    a.effect = Effect.WRITE;
    a.idempotencyClass = IdempotencyClass.NATIVE_KEY;
    a.args = Json.parse("{\"order_id\":\"ORD-0001\"}");
    a.argsHash = "h";
    a.status = status;
    a.policyVersionId = policyId;
    a.matchedRules = Json.arr();
    a.createdAt = now;
    a.updatedAt = now;
    return a;
  }

  protected Action insert(Action a) {
    return store.tx(tx -> tx.actions().insert(a));
  }

  protected void assertViolates(String constraint, Runnable r) {
    assertThatThrownBy(r::run)
        .isInstanceOf(ConstraintViolationException.class)
        .satisfies(
            e -> assertThat(((ConstraintViolationException) e).constraint()).isEqualTo(constraint));
  }

  @Test
  void commitIsAtomic() {
    Run r = run(1000, 10);
    Action a = action(r.id, ActionStatus.APPROVED);
    Action b = action(r.id, ActionStatus.APPROVED);
    store.tx(
        tx -> {
          tx.actions().insert(a);
          tx.actions().insert(b);
          return null;
        });
    assertThat(store.<java.util.List<dev.undo.core.model.Action>>tx(tx -> tx.actions().byRun(r.id)))
        .hasSize(2);
  }

  @Test
  void exceptionRollsBackEverything() {
    Run r = run(1000, 10);
    Action a = action(r.id, ActionStatus.APPROVED);
    assertThatThrownBy(
            () ->
                store.tx(
                    tx -> {
                      tx.actions().insert(a);
                      tx.runs().reserve(r.id, 500, 1);
                      throw new IllegalStateException("boom");
                    }))
        .hasMessage("boom");
    assertThat(
            store.<java.util.Optional<dev.undo.core.model.Action>>tx(tx -> tx.actions().get(a.id)))
        .isEmpty();
    assertThat(
            store
                .<java.util.Optional<dev.undo.core.model.Run>>tx(tx -> tx.runs().get(r.id))
                .orElseThrow()
                .moneyReservedMinor)
        .isZero();
  }

  @Test
  void identityValuesAreNeverReusedAfterRollback() {
    Run r = run(1000, 10);
    long first = insert(action(r.id, ActionStatus.APPROVED)).number;
    assertThatThrownBy(
            () ->
                store.tx(
                    tx -> {
                      tx.actions().insert(action(r.id, ActionStatus.APPROVED));
                      throw new IllegalStateException("discard");
                    }))
        .hasMessage("discard");
    long third = insert(action(r.id, ActionStatus.APPROVED)).number;
    assertThat(third).isGreaterThan(first + 1);
  }

  @Test
  void agentCallKeyIsUniquePerRun() {
    Run r = run(1000, 10);
    Action a = action(r.id, ActionStatus.APPROVED);
    a.agentCallId = "toolu_1";
    insert(a);
    Action b = action(r.id, ActionStatus.APPROVED);
    b.agentCallId = "toolu_1";
    assertViolates("actions_agent_call", () -> insert(b));
    Action other = action(run(1000, 10).id, ActionStatus.APPROVED);
    other.agentCallId = "toolu_1";
    insert(other);
    assertThat(
            store.<java.util.Optional<dev.undo.core.model.Action>>tx(
                tx -> tx.actions().byCallKey(r.id, "toolu_1", null)))
        .map(x -> x.id)
        .contains(a.id);
  }

  @Test
  void clientKeyIsUniquePerRun() {
    Run r = run(1000, 10);
    Action a = action(r.id, ActionStatus.APPROVED);
    a.clientIdempotencyKey = "k1";
    insert(a);
    Action b = action(r.id, ActionStatus.APPROVED);
    b.clientIdempotencyKey = "k1";
    assertViolates("actions_client_key", () -> insert(b));
  }

  @Test
  void activeDedupeKeyIsUniqueAcrossRuns() {
    Action a = action(run(1000, 10).id, ActionStatus.APPROVED);
    a.dedupeKey = "refund:order:ORD-0001";
    a.dedupeActive = true;
    insert(a);
    Action inactive = action(run(1000, 10).id, ActionStatus.DENIED);
    inactive.dedupeKey = "refund:order:ORD-0001";
    inactive.dedupeActive = false;
    insert(inactive);
    Action dup = action(run(1000, 10).id, ActionStatus.APPROVED);
    dup.dedupeKey = "refund:order:ORD-0001";
    dup.dedupeActive = true;
    assertViolates("actions_dedupe", () -> insert(dup));
    assertThat(
            store.<java.util.Optional<dev.undo.core.model.Action>>tx(
                tx -> tx.actions().activeByDedupeKey("refund:order:ORD-0001")))
        .map(x -> x.id)
        .contains(a.id);
  }

  @Test
  void oneLiveCompensationPerTarget() {
    Run r = run(1000, 10);
    Action target = insert(action(r.id, ActionStatus.SUCCEEDED));
    Action failed = action(r.id, ActionStatus.FAILED);
    failed.kind = ActionKind.COMPENSATION;
    failed.compensatesActionId = target.id;
    insert(failed);
    Action live = action(r.id, ActionStatus.APPROVED);
    live.kind = ActionKind.COMPENSATION;
    live.compensatesActionId = target.id;
    insert(live);
    Action second = action(r.id, ActionStatus.APPROVED);
    second.kind = ActionKind.COMPENSATION;
    second.compensatesActionId = target.id;
    assertViolates("actions_one_live_compensation", () -> insert(second));
    assertThat(
            store.<java.util.Optional<dev.undo.core.model.Action>>tx(
                tx -> tx.actions().liveCompensationOf(target.id)))
        .map(x -> x.id)
        .contains(live.id);
  }

  @Test
  void conditionalUpdateChecksStatusAndFence() {
    Run r = run(1000, 10);
    Action a = action(r.id, ActionStatus.EXECUTING);
    a.leaseFence = 3;
    Action saved = insert(a);
    saved.status = ActionStatus.SUCCEEDED;
    assertThat(store.<Boolean>tx(tx -> tx.actions().update(saved, ActionStatus.EXECUTING, 2)))
        .isFalse();
    assertThat(store.<Boolean>tx(tx -> tx.actions().update(saved, ActionStatus.UNKNOWN, 3)))
        .isFalse();
    assertThat(
            store
                .<java.util.Optional<dev.undo.core.model.Action>>tx(tx -> tx.actions().get(a.id))
                .orElseThrow()
                .status)
        .isEqualTo(ActionStatus.EXECUTING);
    assertThat(store.<Boolean>tx(tx -> tx.actions().update(saved, ActionStatus.EXECUTING, 3)))
        .isTrue();
    assertThat(
            store
                .<java.util.Optional<dev.undo.core.model.Action>>tx(tx -> tx.actions().get(a.id))
                .orElseThrow()
                .status)
        .isEqualTo(ActionStatus.SUCCEEDED);
  }

  @Test
  void globalCountsAndMoneyOutSinceAgree() {
    Run r = run(1_000_000, 10);
    Action done = action(r.id, ActionStatus.SUCCEEDED);
    done.moneyOutMinor = 2500;
    done.completedAt = now;
    insert(done);
    Action old = action(r.id, ActionStatus.SUCCEEDED);
    old.args = Json.parse("{\"order_id\":\"ORD-0002\"}");
    old.moneyOutMinor = 7000;
    old.completedAt = now.minusSeconds(3600);
    insert(old);
    Action comp = action(r.id, ActionStatus.SUCCEEDED);
    comp.kind = ActionKind.COMPENSATION;
    comp.moneyOutMinor = 900;
    comp.completedAt = now;
    insert(comp);
    insert(action(r.id, ActionStatus.APPROVED));
    long recent = store.tx(tx -> tx.actions().moneyOutSince(now.minusSeconds(60)));
    long twoHours = store.tx(tx -> tx.actions().moneyOutSince(now.minusSeconds(7200)));
    java.util.Map<ActionStatus, Long> counts = store.tx(tx -> tx.actions().globalCounts());
    long inFlight = store.tx(tx -> tx.actions().countInFlight());
    assertThat(recent).isEqualTo(2500);
    assertThat(twoHours).isEqualTo(9500);
    assertThat(counts.get(ActionStatus.SUCCEEDED)).isEqualTo(3);
    assertThat(counts.get(ActionStatus.APPROVED)).isEqualTo(1);
    assertThat(inFlight).isEqualTo(1);
  }

  @Test
  void claimDoesNotReturnTheSameRowTwice() {
    Run r = run(1000, 10);
    Action a = insert(action(r.id, ActionStatus.APPROVED));
    Action b = insert(action(r.id, ActionStatus.APPROVED));
    List<UUID> first =
        store.tx(
            tx -> {
              List<Action> rows = tx.actions().lockRunnable(now, 1);
              for (Action x : rows) {
                ActionStatus prev = x.status;
                long fence = x.leaseFence;
                x.status = ActionStatus.EXECUTING;
                x.leaseFence = fence + 1;
                x.leaseExpiresAt = now.plusSeconds(30);
                assertThat(tx.actions().update(x, prev, fence)).isTrue();
              }
              return rows.stream().map(x -> x.id).toList();
            });
    List<UUID> second =
        store.tx(tx -> tx.actions().lockRunnable(now, 10).stream().map(x -> x.id).toList());
    assertThat(first).containsExactly(a.id);
    assertThat(second).containsExactly(b.id);
  }

  @Test
  void claimSkipsPrimaryActionsOfFrozenRunsButNotCompensations() {
    Run r = run(1000, 10);
    insert(action(r.id, ActionStatus.APPROVED));
    Action comp = action(r.id, ActionStatus.APPROVED);
    comp.kind = ActionKind.COMPENSATION;
    insert(comp);
    store.tx(
        tx -> {
          Run locked = tx.runs().lock(r.id);
          locked.status = RunStatus.FROZEN;
          tx.runs().update(locked);
          return null;
        });
    assertThat(
            store
                .<java.util.List<dev.undo.core.model.Action>>tx(
                    tx -> tx.actions().lockRunnable(now, 10))
                .stream()
                .map(x -> x.id))
        .containsExactly(comp.id);
  }

  @Test
  void retryWaitIsClaimableOnlyWhenDue() {
    Run r = run(1000, 10);
    Action a = action(r.id, ActionStatus.RETRY_WAIT);
    a.nextAttemptAt = now.plusSeconds(5);
    insert(a);
    assertThat(
            store.<java.util.List<dev.undo.core.model.Action>>tx(
                tx -> tx.actions().lockRunnable(now, 10)))
        .isEmpty();
    assertThat(
            store.<java.util.List<dev.undo.core.model.Action>>tx(
                tx -> tx.actions().lockRunnable(now.plusSeconds(5), 10)))
        .hasSize(1);
  }

  @Test
  void budgetReservationIsConditionalAndBackedByACheckConstraint() {
    Run r = run(1000, 2);
    assertThat(store.<Boolean>tx(tx -> tx.runs().reserve(r.id, 600, 1))).isTrue();
    assertThat(store.<Boolean>tx(tx -> tx.runs().reserve(r.id, 600, 1))).isFalse();
    assertThat(store.<Boolean>tx(tx -> tx.runs().reserve(r.id, 400, 1))).isTrue();
    assertThat(store.<Boolean>tx(tx -> tx.runs().reserve(r.id, 0, 1)))
        .isFalse(); // action cap reached
    store.tx(
        tx -> {
          tx.runs().settle(r.id, 400);
          return null;
        });
    Run after = store.tx(tx -> tx.runs().get(r.id)).orElseThrow();
    assertThat(after.moneyReservedMinor).isEqualTo(600);
    assertThat(after.moneySpentMinor).isEqualTo(400);
    assertViolates(
        "runs_check1",
        () ->
            store.tx(
                tx -> {
                  tx.runs().reserveUnchecked(r.id, 1, 0);
                  return null;
                }));
    assertThat(
            store
                .<java.util.Optional<dev.undo.core.model.Run>>tx(tx -> tx.runs().get(r.id))
                .orElseThrow()
                .moneyReservedMinor)
        .isEqualTo(600);
  }

  @Test
  void updateDoesNotWriteBudgetColumns() {
    Run r = run(1000, 2);
    Run stale = store.tx(tx -> tx.runs().get(r.id)).orElseThrow();
    store.tx(tx -> tx.runs().reserve(r.id, 300, 1));
    stale.title = "renamed";
    store.tx(
        tx -> {
          tx.runs().update(stale);
          return null;
        });
    Run now2 = store.tx(tx -> tx.runs().get(r.id)).orElseThrow();
    assertThat(now2.title).isEqualTo("renamed");
    assertThat(now2.moneyReservedMinor).isEqualTo(300);
  }

  JournalEntry entry(UUID runId, UUID actionId, String kind) {
    JournalEntry e = new JournalEntry();
    e.id = id();
    e.runId = runId;
    e.actionId = actionId;
    e.kind = kind;
    e.source = "execution";
    e.createdAt = now;
    return e;
  }

  static Posting posting(String account, long amount) {
    Posting p = new Posting();
    p.account = account;
    p.amountMinor = amount;
    return p;
  }

  @Test
  void journalEntriesAreUniquePerActionAndKind() {
    Run r = run(1000, 10);
    Action a = insert(action(r.id, ActionStatus.SUCCEEDED));
    store.tx(
        tx -> {
          tx.ledger()
              .insert(
                  entry(r.id, a.id, "REFUND_ISSUED"),
                  List.of(posting("expense:refunds", 5), posting("liability:x", -5)));
          return null;
        });
    assertThat(store.<Boolean>tx(tx -> tx.ledger().entryExists(a.id, "REFUND_ISSUED"))).isTrue();
    assertViolates(
        "journal_entries_action_id_kind_key",
        () ->
            store.tx(
                tx -> {
                  tx.ledger()
                      .insert(
                          entry(r.id, a.id, "REFUND_ISSUED"),
                          List.of(posting("expense:refunds", 5), posting("liability:x", -5)));
                  return null;
                }));
    assertThat(
            store.<java.util.List<dev.undo.core.model.Posting>>tx(
                tx -> tx.ledger().postingsByRun(r.id)))
        .hasSize(2);
  }

  @Test
  void journalBalanceIsCheckedAtCommit() {
    Run r = run(1000, 10);
    Action a = insert(action(r.id, ActionStatus.SUCCEEDED));
    assertViolates(
        "postings_balanced",
        () ->
            store.tx(
                tx -> {
                  tx.ledger()
                      .insert(
                          entry(r.id, a.id, "REFUND_ISSUED"),
                          List.of(posting("expense:refunds", 5), posting("liability:x", -4)));
                  return null;
                }));
    assertThat(store.<Boolean>tx(tx -> tx.ledger().entryExists(a.id, "REFUND_ISSUED"))).isFalse();
  }

  @Test
  void approvalsAreOnePerAction() {
    Run r = run(1000, 10);
    Action a = insert(action(r.id, ActionStatus.AWAITING_APPROVAL));
    store.tx(
        tx -> {
          tx.approvals().insert(approval(a));
          return null;
        });
    assertThatThrownBy(
            () ->
                store.tx(
                    tx -> {
                      tx.approvals().insert(approval(a));
                      return null;
                    }))
        .isInstanceOf(ConstraintViolationException.class);
    assertThat(
            store.<java.util.List<dev.undo.core.model.Approval>>tx(
                tx -> tx.approvals().lockExpired(now.plusSeconds(3601), 10)))
        .hasSize(1);
    assertThat(
            store.<java.util.List<dev.undo.core.model.Approval>>tx(
                tx -> tx.approvals().lockExpired(now, 10)))
        .isEmpty();
  }

  Approval approval(Action a) {
    Approval ap = new Approval();
    ap.id = id();
    ap.actionId = a.id;
    ap.runId = a.runId;
    ap.argsHash = a.argsHash;
    ap.policyVersionId = policyId;
    ap.reasons = Json.arr();
    ap.status = ApprovalStatus.PENDING;
    ap.requestedAt = now;
    ap.expiresAt = now.plusSeconds(3600);
    return ap;
  }

  RevertPlan plan(UUID runId, PlanStatus status) {
    RevertPlan p = new RevertPlan();
    p.id = id();
    p.runId = runId;
    p.scope = PlanScope.RUN;
    p.requestedBy = userId;
    p.status = status;
    p.summary = Json.obj();
    p.createdAt = now;
    return p;
  }

  @Test
  void oneOpenRevertPlanPerRun() {
    Run r = run(1000, 10);
    store.tx(tx -> tx.reverts().insertPlan(plan(r.id, PlanStatus.CANCELLED)));
    RevertPlan open = store.tx(tx -> tx.reverts().insertPlan(plan(r.id, PlanStatus.PREVIEW)));
    assertViolates(
        "revert_one_open_per_run",
        () -> store.tx(tx -> tx.reverts().insertPlan(plan(r.id, PlanStatus.EXECUTING))));
    assertThat(
            store.<java.util.Optional<dev.undo.core.model.RevertPlan>>tx(
                tx -> tx.reverts().openForRun(r.id)))
        .map(p -> p.id)
        .contains(open.id);
  }

  @Test
  void openFindingFingerprintsAreUnique() {
    ReconRun rr = new ReconRun();
    rr.id = id();
    rr.connector = "shopfront";
    rr.windowStart = now;
    rr.windowEnd = now;
    rr.status = ReconRunStatus.RUNNING;
    rr.triggeredBy = "test";
    rr.stats = Json.obj();
    rr.startedAt = now;
    ReconRun saved = store.tx(tx -> tx.recon().insertRun(rr));
    store.tx(
        tx -> {
          tx.recon().insertFinding(finding(saved.id, FindingStatus.RESOLVED));
          tx.recon().insertFinding(finding(saved.id, FindingStatus.OPEN));
          return null;
        });
    assertViolates(
        "recon_open_fingerprint",
        () ->
            store.tx(
                tx -> {
                  tx.recon().insertFinding(finding(saved.id, FindingStatus.ACKNOWLEDGED));
                  return null;
                }));
    assertThat(
            store.<java.util.Optional<dev.undo.core.model.ReconFinding>>tx(
                tx -> tx.recon().openByFingerprint("DUPLICATE_EFFECT:RF-1")))
        .isPresent();
  }

  @Test
  void reconQueriesSelectTheWindowAndCountOpenFindings() {
    Run run = run(1_000_000, 100);
    Action inWindow = action(run.id, ActionStatus.SUCCEEDED);
    Action unknownOld = action(run.id, ActionStatus.UNKNOWN);
    unknownOld.updatedAt = now.minusSeconds(2 * 86_400);
    Action succeededOld = action(run.id, ActionStatus.SUCCEEDED);
    succeededOld.updatedAt = now.minusSeconds(2 * 86_400);
    Action otherConnector = action(run.id, ActionStatus.NEEDS_REVIEW);
    otherConnector.connector = "other";
    for (Action a : List.of(inWindow, unknownOld, succeededOld, otherConnector)) {
      insert(a);
    }
    List<Action> picked =
        store.tx(
            tx -> tx.actions().forRecon("shopfront", now.minusSeconds(3600), now.plusSeconds(1)));
    assertThat(picked).extracting(a -> a.id).containsExactlyInAnyOrder(inWindow.id, unknownOld.id);

    ReconRun completed = reconRun(ReconRunStatus.COMPLETED);
    reconRun(ReconRunStatus.FAILED);
    assertThat(
            store
                .<java.util.Optional<ReconRun>>tx(tx -> tx.recon().lastCompleted("shopfront"))
                .map(r -> r.id))
        .contains(completed.id);

    ReconFinding dup = finding(completed.id, FindingStatus.OPEN);
    dup.actionId = inWindow.id;
    ReconFinding healed = finding(completed.id, FindingStatus.AUTO_HEALED);
    healed.type = FindingType.STATUS_MISMATCH;
    healed.severity = dev.undo.core.model.Severity.INFO;
    healed.fingerprint = "STATUS_MISMATCH:" + inWindow.id;
    healed.actionId = inWindow.id;
    ReconFinding acked = finding(completed.id, FindingStatus.ACKNOWLEDGED);
    acked.type = FindingType.UNMATCHED_WEBHOOK;
    acked.severity = dev.undo.core.model.Severity.WARNING;
    acked.fingerprint = "UNMATCHED_WEBHOOK:RF-9";
    store.tx(
        tx -> {
          tx.recon().insertFinding(dup);
          tx.recon().insertFinding(healed);
          tx.recon().insertFinding(acked);
          return null;
        });
    List<dev.undo.core.port.repo.ReconRepo.OpenCount> counts =
        store.tx(tx -> tx.recon().openCounts());
    assertThat(counts)
        .containsExactly(
            new dev.undo.core.port.repo.ReconRepo.OpenCount(
                FindingType.DUPLICATE_EFFECT, dev.undo.core.model.Severity.CRITICAL, 1),
            new dev.undo.core.port.repo.ReconRepo.OpenCount(
                FindingType.UNMATCHED_WEBHOOK, dev.undo.core.model.Severity.WARNING, 1));
    List<ReconFinding> ofAction = store.tx(tx -> tx.recon().findingsOfAction(inWindow.id));
    assertThat(ofAction).hasSize(2);
    List<ReconFinding> healedOnly =
        store.tx(tx -> tx.recon().findings(FindingStatus.AUTO_HEALED, null, 10));
    assertThat(healedOnly).extracting(f -> f.id).containsExactly(healed.id);

    for (int i = 0; i < 3; i++) {
      dev.undo.core.model.WebhookEvent e = new dev.undo.core.model.WebhookEvent();
      e.provider = "shopfront";
      e.eventId = "evt_" + i;
      e.type = "refund.settled";
      e.payload = Json.obj();
      e.receivedAt = i == 2 ? now : now.minusSeconds(1200);
      store.tx(tx -> tx.webhooks().insertIfAbsent(e));
    }
    store.tx(
        tx -> {
          dev.undo.core.model.WebhookEvent done = tx.webhooks().lock("shopfront", "evt_1");
          done.processedAt = now;
          tx.webhooks().update(done);
          return null;
        });
    List<dev.undo.core.model.WebhookEvent> stale =
        store.tx(tx -> tx.webhooks().unprocessedReceivedBefore(now.minusSeconds(600)));
    assertThat(stale).extracting(e -> e.eventId).containsExactly("evt_0");
  }

  private ReconRun reconRun(ReconRunStatus status) {
    ReconRun rr = new ReconRun();
    rr.id = id();
    rr.connector = "shopfront";
    rr.windowStart = now;
    rr.windowEnd = now;
    rr.status = status;
    rr.triggeredBy = "test";
    rr.stats = Json.obj();
    rr.startedAt = now;
    return store.tx(tx -> tx.recon().insertRun(rr));
  }

  ReconFinding finding(UUID runId, FindingStatus status) {
    ReconFinding f = new ReconFinding();
    f.id = id();
    f.reconRunId = runId;
    f.type = FindingType.DUPLICATE_EFFECT;
    f.severity = dev.undo.core.model.Severity.CRITICAL;
    f.providerObjectRef = "RF-1";
    f.status = status;
    f.fingerprint = "DUPLICATE_EFFECT:RF-1";
    f.createdAt = now;
    return f;
  }

  @Test
  void eventsGetIncreasingSequenceNumbers() {
    Run r = run(1000, 10);
    long a =
        store.tx(
            tx -> {
              dev.undo.core.model.Event e = new dev.undo.core.model.Event();
              e.runId = r.id;
              e.type = "x";
              e.data = Json.obj();
              e.at = now;
              return tx.events().append(e).seq;
            });
    long b =
        store.tx(
            tx -> {
              dev.undo.core.model.Event e = new dev.undo.core.model.Event();
              e.runId = r.id;
              e.type = "y";
              e.data = Json.obj();
              e.at = now;
              return tx.events().append(e).seq;
            });
    assertThat(b).isGreaterThan(a);
    assertThat(store.tx(tx -> tx.events().after(a, r.id, 10)).stream().map(e -> e.type))
        .containsExactly("y");
  }

  @Test
  void hostedRunClaimIncrementsFenceAndHeartbeatIsFenced() {
    Run r = run(1000, 10, RunKind.HOSTED);
    Run other = run(1000, 10);
    Run frozenKind = store.tx(tx -> tx.runs().lock(other.id));
    frozenKind.kind = RunKind.HOSTED; // update() never changes the kind
    store.tx(
        tx -> {
          tx.runs().update(frozenKind);
          return null;
        });
    Run claimed =
        store.tx(tx -> tx.runs().claimHosted("w1", now, now.plusSeconds(60))).orElseThrow();
    assertThat(claimed.leaseFence).isEqualTo(1);
    assertThat(
            store.<java.util.Optional<dev.undo.core.model.Run>>tx(
                tx -> tx.runs().claimHosted("w2", now, now.plusSeconds(60))))
        .isEmpty();
    assertThat(store.<Boolean>tx(tx -> tx.runs().heartbeat(r.id, 1, now.plusSeconds(90)))).isTrue();
    Run stolen =
        store
            .tx(tx -> tx.runs().claimHosted("w2", now.plusSeconds(91), now.plusSeconds(150)))
            .orElseThrow();
    assertThat(stolen.leaseFence).isEqualTo(2);
    assertThat(store.<Boolean>tx(tx -> tx.runs().heartbeat(r.id, 1, now.plusSeconds(200))))
        .isFalse();
  }
}
