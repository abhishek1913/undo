# ADR-013: Small changes to the Shopfront database

**Context.** Section 12.7 gives the Shopfront DDL. Two needs are not covered by it:

1. Section 12.3 requires that a repeat with the same Idempotency-Key returns the stored response
   with "status and body exactly". The DDL stores `response_body jsonb`. Postgres `jsonb` reorders
   object keys, so a replay would not be byte-identical (the M1 acceptance test caught this).
2. The carrier-bug selection is decided at seed time from a split of the seed's random
   (Section 12.8), and ground truth includes per-ticket details (base category, the bad action an
   adversarial agent tries). Both must survive until `/admin/carrier-bug` and `/admin/ground-truth`
   are called.

**Decision.** `V1__init.sql` is the PRD DDL verbatim. `V2__meta.sql` then:

- changes `idempotency_keys.response_body` to `text` holding the exact response text;
- adds `shop_meta(key text primary key, value jsonb)` for `ground_truth`, `carrier_bug_selection`
  and `seed`;
- adds indexes for the recon listings and the outbox.

Seed times are relative to the start of the UTC day, so seeding twice on the same day gives
byte-identical data (Section 12.8 "same seed → byte-identical data").

**Alternatives.** Keep `jsonb` and canonicalize responses (replays would then differ from the
first response); recompute the carrier-bug selection by re-running the generator (needs the
original "now" anyway).

**Consequences.** Shopfront stays a faithful idempotent provider; the change is invisible to Undo.
