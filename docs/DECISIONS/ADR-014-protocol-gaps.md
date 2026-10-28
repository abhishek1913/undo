# ADR-014: Gaps found while implementing the execution protocol

The PRD is followed exactly except for the points below. Each one is a place where following the
text literally would break a stated invariant or could not be implemented with the given types.

## 1. `ToolCall` carries `runId` and `beforeSnapshot`

Section 13.3 says the refund body contains `metadata: {undo_action_id, undo_run_id}` and that the
note write sends `If-Match: "v{before.note_version}"`. The `ToolCall` record of Section 11 has
neither the run id nor the before snapshot, and connectors hold no state. We add both fields.

## 2. `RetryableNotApplied` after an earlier unknown attempt stays UNKNOWN

Section 9.3 E4 maps `RetryableNotApplied` to `RETRY_WAIT` (or `FAILED max_attempts_not_applied`).
That is only safe when no earlier attempt of the action might have applied. Example: attempt 1 of a
refund is applied but its response is lost (UNKNOWN); the resolver re-executes; attempt 2 gets a
429 before the provider looked at the key. Now:

- `RETRY_WAIT` is wrong because freeze side effects cancel RETRY_WAIT actions "because not applied"
  (FR-RUN-5, 8.2) — the action would end CANCELLED with its budget released while the refund exists
  (breaks INV-2 and INV-3).
- `FAILED max_attempts_not_applied` is wrong for the same reason.

**Decision.** A `RetryableNotApplied` outcome proves only that *this* attempt did not apply. When
`unknown_since` is set (an earlier attempt's outcome is still unknown), the action goes
`EXECUTING → UNKNOWN` (a legal edge) with `resolve_after = now + max(retryAfter, backoff)`, and the
normal resolution table decides what happens next (re-execute for NATIVE_KEY/NATURAL, lookup for
LOOKUP_BY_REFERENCE or frozen runs). `RETRY_WAIT` therefore always means "nothing applied yet",
which is what the freeze rule assumes.

## 3. A trusted lookup NotFound after max attempts ends FAILED

Section 9.5 `decide()` returns LOOKUP once `attempt_count >= max_attempts`. If that lookup is a
trusted NotFound on an active run, Section 9.3 would move the action to APPROVED and re-execute it,
past the attempt limit, forever. We end it `UNKNOWN → FAILED` with reason
`max_attempts_not_applied` (the lookup proves nothing was applied; budget released, dedupe key
inactive), the same outcome FR-EXEC-7 gives a never-applied action.

## 4. Allow rules

Section 14 defines precedence as "any matching deny → DENY; else any matching require_approval →
REQUIRE_APPROVAL; else the tool's default". Read literally, a matching `allow` rule would have no
effect. We use deny > require_approval > matching allow rule > tool default. With the shipped
policy (no allow rules) the result is identical.

## 5. Facts for a missing object

When a fact read returns 404 (for example the agent names an order that does not exist), the fact
is recorded as `null` instead of failing the whole proposal with `facts_unavailable` (which is
marked retryable and would invite the agent to retry). Rules that need the missing value then fail
closed with `policy_error`. Network errors and 5xx still give `facts_unavailable`.

## 6. Facts timeout

The 3 s facts timeout (FR-PROP-6) is passed to the transport as the request header
`X-Undo-Timeout-Ms`; the HTTP `ShopfrontApi` and the simulator network both honor and strip it.

## 7. A not-applied answer after a freeze ends the action

Found by the simulator (seed 2183, INV-8). An email was EXECUTING when the operator froze its run.
The provider then answered 429. Section 9.3 E4 moves the action to RETRY_WAIT, but the freeze side
effects had already run, and the claim query never returns PRIMARY actions of a frozen run, so the
action stayed RETRY_WAIT forever. **Decision.** In E4, a `RetryableNotApplied` outcome for a PRIMARY
action whose run no longer allows execution (and with no earlier unknown attempt) ends the action
`EXECUTING → FAILED` with reason `not_applied_run_frozen` — the same result the lookup path gives
a frozen run (budget released, dedupe key inactive).

## 8. A success can carry a later provider status

Found by the simulator (seed 17594, INV-6). An UNKNOWN refund on a frozen run was found by lookup
after the provider had already failed it, so the action went `SUCCEEDED` with
`provider_status = failed`. E5 posts only `journalOnSuccess` (`REFUND_ISSUED`); the
`REFUND_FAILED` entry is normally posted by the webhook path, which sees no status change because
the status is already `failed`. **Decision.** `applySuccess` also writes
`journalOnProviderStatus(action, provider_status)` for the status it records (empty for
`pending`/`ok`). Entries are unique per (action, kind), so a later webhook posts nothing twice.

