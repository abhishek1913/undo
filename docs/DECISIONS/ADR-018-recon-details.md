# ADR-018: Reconciliation details the PRD leaves open

Status: accepted (M7)

Section 16 defines inputs, matching, finding types and output. These points needed a decision.

1. **Changed while listing → skipped.** The provider listing runs outside any transaction. Each
   action is then checked in its own transaction with the row locked; if the action's `updated_at`
   is at or after the moment the listing started, the check is skipped and the next run looks at
   it. Without this, a webhook applied during the listing (refund `pending` → `settled`) would be
   "healed" back to the older status the listing saw, and a compensation that finished during the
   listing would look like a COMPENSATION_MISMATCH.
2. **Status checks only for money objects.** STATUS_MISMATCH, AMOUNT_MISMATCH and
   COMPENSATION_MISMATCH are checked for refunds and store credits. Emails have no status
   webhooks (`queued` → `sent` happens at Shopfront only) and no amount, so comparing their status
   would raise an INFO finding for every email without telling anyone anything. Emails are still
   checked for GHOST, MISSING and DUPLICATE.
3. **MISSING confirmation without a lookup.** 16.3 says MISSING is confirmed by `connector.lookup`
   "or the export for emails". The email tool has no lookup (`Unavailable`), so the confirmation is
   a second, narrow listing from one minute before the action's first attempt to now. Any provider
   error means "not confirmed" (no finding).
4. **COMPENSATION_MISMATCH before STATUS_MISMATCH.** For an original marked compensated, a
   provider status other than `cancelled`/`revoked` is CRITICAL COMPENSATION_MISMATCH, never an
   auto-healed status change. A compensated original whose provider status is `cancelled` but whose
   own `provider_status` is still `pending` (the cancel webhook was lost) is healed as
   STATUS_MISMATCH; the journal's `(action_id, kind)` check keeps REFUND_CANCELLED single (the
   compensation already posted it). Bug flag `JOURNAL_SKIPS_EXISTENCE_CHECK` breaks exactly this.
5. **UNKNOWN_ORIGIN has no action id.** `recon_findings.action_id` references `actions(id)`, so the
   unknown id goes into `actual.undo_action_id` and the fingerprint uses the provider reference.
6. **Lease and crashes.** The job lease `recon:<connector>` (TTL 5 min) is released when a run ends
   normally or with an exception. A crash (simulator: `SimulatedCrash`, an `Error`) keeps it until
   it expires, like a dead process. The next holder marks every RUNNING run of the connector FAILED
   (`stats.error = "abandoned"`) before it starts. Crash point `RECON_AFTER_FETCH` is in the
   simulator's expected set.
7. **Scheduler.** Every worker checks every 30 s whether the connector is due (no completed run, or
   the last one finished `undo.recon.interval` ago). `undo.recon.scheduler-enabled=false` turns the
   scheduler off; the integration tests use it so a background run cannot heal state another test
   is about to assert on. The simulator uses a 10-minute interval (cost) and adds operator
   "run now" requests.
8. **On-demand runs are synchronous.** `POST /api/recon/runs` (ADMIN) runs the reconciliation in
   the request and answers 201 with the finished run, or 409 `RECON_RUNNING` when the lease is
   held. New error codes: `RECON_RUNNING`, `FINDING_NOT_OPEN` (acknowledge or resolve of a
   finding that is not open).
9. **Stats.** Besides the fields of 16.4 the run stats hold `over_refunded_orders` (orders with
   both Undo refunds and refunds without `undo_action_id`, 16.2) and `errors` (actions whose check
   threw; the simulator treats any error in the final run as an INV-9 violation).
10. **Late compensation of an earlier plan lease holder (simulator seed 35294, a real bug).** A
   revert step whose plan lease expired can still create a compensation after a new holder
   started. The new holder reads the provider state, sees the effect of that compensation
   (credit already revoked), and used to mark the item DONE "already gone" with nothing
   recovered, so INV-6 failed (exposure 21,800 left). The step now looks for a live compensation
   of the target again whenever the reversal is not COMPENSATE: the stale holder's insert
   committed before the provider effect the new holder read, so the second look always finds it.
   Unit test: `RevertScenarioTest.lateCompensationOfAnEarlierLeaseHolderIsLinkedNotAlreadyGone`;
   regression seed `NONE 35294`.
11. **INV-2 added to the simulator.** It was not implemented in M3. It runs at the end with
   INV-9: every SUCCEEDED PRIMARY action of a reconciled tool has exactly one provider object, every
   FAILED/DENIED/REJECTED/EXPIRED/CANCELLED one has none.
