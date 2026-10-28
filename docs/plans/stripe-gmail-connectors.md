# Plan: Stripe and Gmail connectors

Status: design only. Not scheduled. Build after M10 (PRD 32.3 lists Stripe as a stretch goal).

This plan says what each connector does, which idempotency class each tool gets, how revert and
reconciliation work, and how to test without production credentials. It follows the Connector SPI
(PRD Section 11) and the outcome table (PRD 9.4). Points marked **Spike** must be checked against the
real provider before code depends on them.

## 1. Where connectors fit

```
 Agent (Claude Code over MCP, a company chatbot over REST, or the hosted runner)
   │ tools/call stripe_issue_refund {...}
   ▼
 Undo: schema → dedupe → facts → policy → budget → ledger intent → connector.execute → outcome
   │
   ▼
 Stripe API / Gmail API
```

Nothing changes for agents. A new connector's tools appear in MCP `tools/list` and REST once the
connector is registered and the active policy names them. Policy is deny-by-default, so a tool that
the policy does not mention is denied.

## 2. Shared work (do once, before either connector)

| Item | Change |
|---|---|
| Modules | `connector-stripe/`, `connector-gmail/` in the root `pom.xml`. Same ArchUnit rules as `connector-shopfront` (depend on `undo-core` only). |
| Transport | Each connector gets an HTTP-shaped interface (`StripeApi`, `GmailApi`) like `ShopfrontApi`, so classification is the same in server and simulator. |
| Registration | `EngineBeans.registry(...)` builds `List<Connector>` from the enabled connectors. Enable each with `undo.connectors.stripe.enabled` / `undo.connectors.gmail.enabled`. |
| Webhooks | `WebhookController` is hard-wired to `POST /webhooks/shopfront`. Change it to `POST /webhooks/{connectorId}` and dispatch to that connector's `parseWebhook`. |
| Secrets | Stripe secret key, Stripe webhook secret, Gmail OAuth client id/secret and refresh token come from env vars through `UndoProperties`. Never logged, never in events. |
| Policy | Add rules for the new tools to a policy file (examples in 3.6 and 4.6). |

## 3. Stripe connector

### 3.1 Tools

| Tool | Visibility | Effect | Class | Stripe call |
|---|---|---|---|---|
| `stripe_get_payment` | AGENT | READ | NATURAL | `GET /v1/payment_intents/{id}` (expand latest charge) |
| `stripe_issue_refund` | AGENT | WRITE | **NATIVE_KEY** | `POST /v1/refunds` `{payment_intent, amount, reason, metadata.undo_action_id}` + `Idempotency-Key` |
| `stripe_grant_credit` | AGENT | WRITE | **NATIVE_KEY** | `POST /v1/customers/{id}/balance_transactions` `{amount: -N, currency, metadata.undo_action_id}` + `Idempotency-Key` |
| `stripe_cancel_refund` | INTERNAL | WRITE | NATIVE_KEY | `POST /v1/refunds/{id}/cancel` |
| `stripe_reverse_credit` | INTERNAL | WRITE | NATIVE_KEY | `POST /v1/customers/{id}/balance_transactions` `{amount: +N}` |

- `providerIdempotencyKey` = the action's stable key. `nativeKeyTtl` = 24 h (Stripe keeps keys for
  at least 24 h). The resolver's safe window (9.5) is therefore 23 h, same as Shopfront.
- `metadata.undo_action_id` on every write gives lookup and reconciliation a reference even after
  the key expires.
- `dedupeKeyTemplate`: `refund:pi:{payment_intent}` (one refund per payment per run, like
  `refund:order:{order_id}` for Shopfront). `moneyOutPointer`: `/amount_minor`.
- Amounts are integers in the smallest currency unit, which matches `amount_minor`.

### 3.2 Facts for policy

`stripe_get_payment` returns, and `facts()` for the write tools fetches:
`amount`, `amount_refunded`, `amount_remaining` (= amount − amount_refunded), `currency`, `customer`,
`status`, `created`. For credits: the customer's current `balance` and `email`.

### 3.3 Outcome classification (on top of PRD 9.4)

| Stripe answer | Outcome |
|---|---|
| 2xx | `Succeeded(providerRef = re_… / cbtxn_…, providerStatus = refund.status)` |
| 400 `idempotency_error` (same key, different params) | `FailedDefinitive` + ERROR log (a bug, like `idempotency_key_reused`) |
| 409 (concurrent request with the same key) | `Unknown` (short grace), like Shopfront's `idempotency_request_in_progress`. **Spike:** confirm the exact code. |
| 400/402/404 card or business errors (`charge_already_refunded`, `amount_too_large`, …) | `FailedDefinitive` |
| 401/403 | `FailedDefinitive` + ERROR log (config) |
| 429 | `RetryableNotApplied(retryAfter)` |
| 5xx, timeout after send | `Unknown` → resolver re-executes with the same key inside the safe window |

### 3.4 Lookup, revert, webhooks

- **Lookup** (key window passed, or run frozen): `GET /v1/refunds?payment_intent=…` and match
  `metadata.undo_action_id`. For credits: list the customer's balance transactions and match the same
  metadata. Strong consistency is assumed. **Spike:** confirm list results include a just-created
  object; if not, set `Consistency.EVENTUAL` with a `lookupLag`.
- **Revert a refund — honest answer: usually irreversible.** Stripe only lets you cancel a refund
  whose status is `requires_action`. Most card refunds go straight to `pending`/`succeeded`, so
  `reversal()` returns `Irreversible(reason, remediation = draft a "please repay" email for approval,
  moneyNotRecovered = amount)`. Only `requires_action` refunds get `Compensate(stripe_cancel_refund)`.
  A `failed`/`canceled` refund is `NoEffect`. The demo story must say this plainly.
- **Revert a credit:** `reversalNeedsState = true`, `currentState` = customer `balance`. If the
  balance still holds the whole credit → `Compensate(stripe_reverse_credit)`. If the customer spent
  part of it → `Conflict` (a human decides; never push the balance positive silently).
- **Webhooks:** `charge.refund.updated` / `refund.updated` → `ProviderStatusChange` (refund status
  changes heal through `WebhookService.applyStatusChange`). Verify `Stripe-Signature`
  (`t=…,v1=…`, HMAC-SHA256 of `"{t}.{body}"` with the endpoint secret, 5-minute tolerance).
- **Journal:** refund → `REFUND_ISSUED` lines the same shape as Shopfront refunds; status
  `failed`/`canceled` → reversing entry. Credit → store-credit liability lines.

### 3.5 Reconciliation

`listEffects(from, to)`: `GET /v1/refunds?created[gte]=…&created[lt]=…&limit=100`, paginate with
`starting_after`. Each refund with `metadata.undo_action_id` maps to an action; one without is
UNKNOWN_ORIGIN or ignored by filter (refunds made by humans in the Stripe dashboard are normal).
Credits: Stripe has no global list of balance transactions. **Spike:** use `GET /v1/events`
(`type=customer.balance_transaction.created`, 30-day retention) or reconcile credits per customer
that Undo touched. Until decided, `providerObjectType` for credits is empty (not reconciled), and the
docs say so.

### 3.6 Policy example

```yaml
  tools:
    stripe_issue_refund:
      default: require_approval
      rules:
        - id: stripe_issue_refund.over-remaining
          decision: deny
          reason: "Refund is more than what is left on the payment"
          when: { field: args.amount_minor, op: gt, ref: facts.amount_remaining }
        - id: stripe_issue_refund.small
          decision: allow
          reason: "Small refund on a succeeded payment"
          when:
            all:
              - { field: facts.status, op: eq, value: "succeeded" }
              - { field: args.amount_minor, op: lte, value: 500000 }   # ₹5,000
```

## 4. Gmail connector

### 4.1 Tools

| Tool | Visibility | Effect | Class | Gmail call |
|---|---|---|---|---|
| `gmail_send_email` | AGENT | WRITE | **NONE** (start here) | `POST gmail/v1/users/me/messages/send` with a base64url RFC 2822 `raw` |

Gmail has no idempotency key and an email cannot be unsent. So:

- Start as **NONE**: an `Unknown` send goes to NEEDS_REVIEW, never resent automatically. This is
  at-most-once, and the README says so (PRD G1).
- Every message carries headers `X-Undo-Action-Id: <action id>` and
  `Message-ID: <undo-<action id>@undo.local>`.
- **Spike → upgrade to LOOKUP_BY_REFERENCE.** If Gmail keeps the given `Message-ID`, lookup is
  `users.messages.list?q=rfc822msgid:<id> in:sent`. If it does not, lookup lists `in:sent after:<first
  attempt − 1 min>` and reads `X-Undo-Action-Id` with `format=metadata`. Mark consistency `EVENTUAL`
  and measure `lookupLag`. Only after the spike shows lookups are reliable does the class change.

### 4.2 Auth

OAuth 2.0 with scope `gmail.send`; lookup and recon also need `gmail.readonly` (the narrower
`gmail.metadata` scope does not allow the `q` search). For a Google Workspace company, a service
account with domain-wide delegation avoids a stored user refresh token. `gmail.readonly` is a
restricted scope: fine for a company's own internal app, but a public app needs Google's review.

### 4.3 Outcome classification

| Gmail answer | Outcome |
|---|---|
| 2xx | `Succeeded(providerRef = message id, providerStatus = "sent")` |
| 400 (bad message) | `FailedDefinitive` |
| 401 | `FailedDefinitive` + ERROR log (token expired or revoked; the connector refreshes once first) |
| 403 with reason `rateLimitExceeded` / `userRateLimitExceeded` | `RetryableNotApplied` (unlike PRD 9.4, where 403 means config) |
| 403 other | `FailedDefinitive` + ERROR log |
| 429 | `RetryableNotApplied(retryAfter)` |
| 5xx, timeout after send | `Unknown` → NEEDS_REVIEW (class NONE) |

### 4.4 Revert, webhooks, reconciliation

- **Revert:** always `Irreversible("An email cannot be unsent", remediation = gmail_send_email
  correction draft, needs approval, 0)`. Same as Shopfront emails.
- **Webhooks:** none. Gmail push (Pub/Sub) reports mailbox changes, not delivery; not needed.
- **Reconciliation:** `providerObjectType = "email"`. `listEffects` lists `in:sent` in the window and
  reads `X-Undo-Action-Id`. Checks GHOST, MISSING and DUPLICATE only (no status or amount), as
  ADR-018 already does for Shopfront emails.

### 4.5 Limits

Gmail has daily send limits (lower for personal accounts than for Workspace). Put a per-run `actions`
budget and a policy rule on recipient domains so a hijacked agent cannot email the world.

### 4.6 Policy example

```yaml
    gmail_send_email:
      default: require_approval
      rules:
        - id: gmail_send_email.customer-only
          decision: allow
          reason: "Reply to the customer who opened the ticket"
          when: { field: args.to, op: eq, ref: facts.ticket_customer_email }
```

(`facts.ticket_customer_email` assumes the ticket system is also a connector. Without one, keep
`default: require_approval`.)

## 5. Testing without production credentials

| Layer | Stripe | Gmail |
|---|---|---|
| Unit | Every row of 3.3 with a fake `StripeApi` | Every row of 4.3 with a fake `GmailApi` |
| Request shape | `stripe/stripe-mock` in Docker (no key). **It is stateless**: it checks request and response shapes only, not idempotency or lookups. | A small fake HTTP server in the test |
| Exactly-once proof | `SimStripe` in `undo-sim`: keyed idempotency with a 24 h TTL, async refund status, cancel only in `requires_action`, customer balance. Run the invariants over seeds, with crashes and dropped responses. | `SimGmail`: no keys, sent folder with headers, search lag |
| Real end to end (optional) | Stripe **test mode** key (`sk_test_…`, free, no real money): one script that refunds, kills the worker, and checks one refund exists | A throwaway Google account; one send and one lookup |

Bug flags to add so the simulator proves the tests bite: `STRIPE_KEY_PER_ATTEMPT` (new key on retry
→ duplicate refund) and `GMAIL_RESEND_ON_UNKNOWN` (NONE class resent → duplicate email).

## 6. Later: a generic MCP connector

Many companies already run MCP servers for their tools. A `connector-mcp` would connect to one as an
MCP client, read `tools/list`, and expose each tool through Undo. MCP gives name, description and
input schema only; its `readOnlyHint` / `idempotentHint` annotations are set by the server and are
not trusted. So every discovered tool starts as WRITE, class NONE, irreversible, and
`require_approval`. A per-tool profile file (written by a person) then upgrades it: which argument is
the idempotency key, which tool looks it up, which tool undoes it. Day one gives approval and audit
with no code; each profile adds stronger guarantees.

## 7. Rough effort

| Piece | Estimate |
|---|---|
| Shared work (section 2) | ½ day |
| Stripe connector + `SimStripe` + tests | 2–3 days |
| Gmail connector + `SimGmail` + tests (+ spike) | 1–1½ days |
| Generic MCP connector | 3–4 days |

## 8. Open questions

1. Stripe credits: reconcile through the Events API or per touched customer? (3.5)
2. Gmail: does `messages.send` keep a client `Message-ID`? Decides lookup strategy. (4.1)
3. Stripe lookup consistency right after create. (3.4)
4. Refund revert is mostly irreversible at Stripe. Is "please repay" the right remediation, or
   should policy push large refunds to approval so revert matters less?
