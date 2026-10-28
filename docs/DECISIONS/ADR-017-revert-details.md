# ADR-017: Revert details the PRD leaves open

Status: accepted (M6)

Section 15 defines preview, execute and the worker step. These points needed a decision.

1. **Provider status from the current state.** 15.1 step 3 says: if the state shows another
   provider status than the action, apply it first. The state is only trusted for that when it
   describes the action's own object (`state.id == provider_ref`) and the action has a real
   provider status (not null, not `ok`, which note writes use). Emails (state = the action's own
   result) and order notes (state = the order) never trigger a status change.
2. **Item note.** `revert_items.note` is text. It holds JSON `{text, remediation?}` so the remediation
   template of an IRREVERSIBLE item survives between preview and execution (15.1 says "stored in
   item note JSON"). The console API returns `note` (the text) and `remediation` separately.
3. **Compensation failure codes.** 15.2 names `refund_not_cancellable` and `conflict`. Shopfront's
   reopen answers `ticket_state_conflict`, so any code `conflict` or ending in `_conflict` means
   CONFLICT. On `refund_not_cancellable` the step re-reads the provider state and decides again:
   NoEffect (the refund failed or was cancelled meanwhile) → DONE "already gone", nothing lost;
   Irreversible (settled) → BECAME_IRREVERSIBLE with the plan's own money not recovered; anything
   else → FAILED. Found by the simulator (seed 18140): without the re-read a failed refund was
   reported as money lost.
4. **ALREADY_REVERTED carries what was lost.** When a later plan meets a target an earlier plan
   compensated, the item's `money_not_recovered_minor` is the target's money out minus what that
   compensation recovered (a partly spent store credit). Such an item with money not recovered
   makes the plan PARTIALLY_REVERTED, so INV-6 (exposure = Σ not recovered) holds across plans.
5. **Money recovered** comes from the compensation's provider result: `revoked_minor` when present
   (credits), else `amount_minor` (refunds), capped at the target's money out.
6. **Report text.** Deterministic, grouped by the target's tool name in alphabetical order, for
   example: "Reverted 1 of 2 actions. Undone: 1 × shop_issue_refund (₹30.00 recovered). Cannot be
   undone: 1 × shop_issue_refund (₹25.00 not recoverable)." Items without an effect (NO_EFFECT,
   ALREADY_REVERTED, DONE as "already gone") are not counted. The PRD's example sentence uses
   per-tool nouns; a generic core cannot know them, so it names tools.
7. **Plan lease.** Each worker round claims the plan lease (fence + 1), does one step, and releases
   it. Every item write checks the fence in the same TX. A provider read that fails leaves the item
   as it is; the next step tries again.
8. **ACTIONS scope** leaves the run status alone (only RUN plans move the run to REVERTING and then
   to the plan's end status). FR-REV-9: targets must be PRIMARY write actions of the run, else 422.
9. **`/api/demo/seed` came early.** The Playwright suite needs a clean store every run (refunds
   dedupe per order forever), so M6 adds this one Demo API endpoint (19.3); the others come with M8.
