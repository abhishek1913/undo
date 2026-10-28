create extension if not exists citext;

-- ── identity ─────────────────────────────────────────────────────────
create table agents (
  id           uuid primary key,
  name         text not null unique,
  created_at   timestamptz not null
);

create table api_keys (
  id             uuid primary key,
  agent_id       uuid not null references agents(id),
  prefix         text not null,                 -- first 12 chars, shown in UI: undo_sk_AbCd
  key_hash       bytea not null unique,         -- sha256(full key)
  allowed_tools  text[] not null,               -- glob patterns, e.g. {'shop_*','undo_run_status'}
  default_policy text not null,                 -- policy name
  created_at     timestamptz not null,
  revoked_at     timestamptz
);

create table users (
  id             uuid primary key,
  email          citext not null unique,
  password_hash  text not null,                 -- bcrypt cost 12
  roles          text[] not null,               -- subset of VIEWER, APPROVER, REVERTER, ADMIN
  created_at     timestamptz not null
);

-- ── policy ───────────────────────────────────────────────────────────
create table policy_versions (
  id           uuid primary key,
  name         text not null,
  version      int  not null,
  source_yaml  text not null,
  compiled     jsonb not null,                  -- normalized form used by the evaluator
  sha256       text not null,
  active       boolean not null default false,
  created_by   text not null,
  created_at   timestamptz not null,
  unique (name, version)
);
create unique index policy_one_active_per_name on policy_versions(name) where active;

-- ── runs & tasks ─────────────────────────────────────────────────────
create table runs (
  id                    uuid primary key,
  number                bigint generated always as identity unique,
  agent_id              uuid not null references agents(id),
  kind                  text not null check (kind in ('EXTERNAL','HOSTED')),
  status                text not null check (status in
                          ('ACTIVE','COMPLETED','FROZEN','FAILED','REVERTING','REVERTED','PARTIALLY_REVERTED')),
  title                 text not null,
  scenario              text,                   -- support-triage | lost-package-sweep | null
  model_mode            text check (model_mode in ('claude','scripted')),
  labels                jsonb not null default '{}',
  policy_version_id     uuid not null references policy_versions(id),
  currency              char(3) not null default 'INR',
  money_cap_minor       bigint not null,
  money_reserved_minor  bigint not null default 0,
  money_spent_minor     bigint not null default 0,
  action_cap            int not null,
  action_count          int not null default 0,
  llm_token_cap         bigint not null default 0,
  llm_tokens_used       bigint not null default 0,
  mcp_session_id        text unique,
  lease_owner           text,
  lease_expires_at      timestamptz,
  lease_fence           bigint not null default 0,
  audit_head_hash       text,
  failure_reason        text,
  last_activity_at      timestamptz not null,
  created_at            timestamptz not null,
  completed_at          timestamptz,
  frozen_at             timestamptz,
  check (money_reserved_minor >= 0 and money_spent_minor >= 0),
  check (money_reserved_minor + money_spent_minor <= money_cap_minor),
  check (action_count >= 0 and action_count <= action_cap)
);
create index runs_status on runs(status);
create index runs_hosted_claimable on runs(lease_expires_at nulls first) where kind = 'HOSTED' and status = 'ACTIVE';

create table tasks (
  id              uuid primary key,
  run_id          uuid not null references runs(id),
  seq             int not null,
  input           jsonb not null,               -- {"ticket_id":"TKT-0117"} or {"order_id":"ORD-0042"}
  status          text not null check (status in ('PENDING','IN_PROGRESS','DONE','FAILED')),
  turns           int not null default 0,
  failure_reason  text,
  created_at      timestamptz not null,
  started_at      timestamptz,
  completed_at    timestamptz,
  unique (run_id, seq)
);

create table llm_turns (
  id                   uuid primary key,
  run_id               uuid not null references runs(id),
  task_id              uuid not null references tasks(id),
  turn_no              int not null,
  model                text not null,           -- model that actually served (response.model)
  response             jsonb not null,          -- exact response body, echoed back verbatim
  stop_reason          text not null,
  tool_results         jsonb,                   -- the user message sent after this turn; null until answered
  input_tokens         int not null,
  output_tokens        int not null,
  cache_read_tokens    int not null default 0,
  cache_write_tokens   int not null default 0,
  latency_ms           int not null,
  run_fence            bigint not null,
  created_at           timestamptz not null,
  unique (task_id, turn_no)
);

-- ── actions ──────────────────────────────────────────────────────────
create table actions (
  id                        uuid primary key,
  number                    bigint generated always as identity unique,
  run_id                    uuid not null references runs(id),
  task_id                   uuid references tasks(id),
  kind                      text not null check (kind in ('PRIMARY','COMPENSATION','REMEDIATION')),
  compensates_action_id     uuid references actions(id),
  revert_plan_id            uuid,
  connector                 text not null,
  tool                      text not null,
  effect                    text not null check (effect in ('READ','WRITE')),
  idempotency_class         text not null check (idempotency_class in ('NATURAL','NATIVE_KEY','LOOKUP_BY_REFERENCE','NONE')),
  args                      jsonb not null,
  args_hash                 text not null,
  agent_call_id             text,
  client_idempotency_key    text,
  dedupe_key                text,
  dedupe_active             boolean not null default false,
  duplicate_of_action_id    uuid references actions(id),
  status                    text not null check (status in
                              ('PROPOSED','DENIED','AWAITING_APPROVAL','REJECTED','EXPIRED','APPROVED',
                               'EXECUTING','RETRY_WAIT','UNKNOWN','SUCCEEDED','FAILED','NEEDS_REVIEW','CANCELLED')),
  status_reason             text,
  policy_version_id         uuid not null references policy_versions(id),
  decision                  text check (decision in ('ALLOW','DENY','REQUIRE_APPROVAL')),
  matched_rules             jsonb not null default '[]',   -- [{"id":"refund.large-needs-human","decision":"require_approval","reason":"…"}]
  facts                     jsonb,
  before_snapshot           jsonb,
  money_out_minor           bigint not null default 0,
  budget_state              text not null default 'NONE' check (budget_state in ('NONE','RESERVED','SPENT','RELEASED')),
  provider_idempotency_key  text,
  provider_ref              text,
  provider_status           text,
  result                    jsonb,
  error                     jsonb,
  compensated_by_action_id  uuid references actions(id),
  attempt_count             int not null default 0,
  lookup_count              int not null default 0,
  first_attempt_at          timestamptz,
  next_attempt_at           timestamptz,
  unknown_since             timestamptz,
  resolve_after             timestamptz,
  lease_owner               text,
  lease_expires_at          timestamptz,
  lease_fence               bigint not null default 0,
  created_at                timestamptz not null,
  updated_at                timestamptz not null,
  completed_at              timestamptz
);
create unique index actions_agent_call   on actions(run_id, agent_call_id) where agent_call_id is not null;
create unique index actions_client_key   on actions(run_id, client_idempotency_key) where client_idempotency_key is not null;
create unique index actions_dedupe       on actions(dedupe_key) where dedupe_active;
create unique index actions_one_live_compensation on actions(compensates_action_id)
  where compensates_action_id is not null and status not in ('FAILED','DENIED','CANCELLED','REJECTED','EXPIRED');
create index actions_runnable      on actions(number) where status in ('APPROVED','RETRY_WAIT');
create index actions_executing     on actions(lease_expires_at) where status = 'EXECUTING';
create index actions_unknown       on actions(resolve_after) where status = 'UNKNOWN';
create index actions_run           on actions(run_id, number);
create index actions_provider_ref  on actions(connector, provider_ref) where provider_ref is not null;

create table action_transitions (
  id           bigint generated always as identity primary key,
  action_id    uuid not null references actions(id),
  from_status  text,
  to_status    text not null,
  fence        bigint not null,
  actor        text not null,                   -- agent:<name> | worker:<node> | api:<node> | user:<email> | system:<job>
  reason       text,
  at           timestamptz not null
);
create index action_transitions_action on action_transitions(action_id, id);

create table attempts (
  id           uuid primary key,
  action_id    uuid not null references actions(id),
  kind         text not null check (kind in ('EXECUTE','LOOKUP')),
  attempt_no   int not null,
  node         text not null,
  fence        bigint not null,
  started_at   timestamptz not null,
  finished_at  timestamptz,
  outcome      text check (outcome in ('SUCCEEDED','FAILED_DEFINITIVE','RETRYABLE_NOT_APPLIED','UNKNOWN',
                                       'FOUND','NOT_FOUND','LOOKUP_UNAVAILABLE','STALE_FENCE')),
  http_status  int,
  latency_ms   int,
  detail       jsonb,
  unique (action_id, kind, attempt_no)
);

create table approvals (
  id                 uuid primary key,
  action_id          uuid not null unique references actions(id),
  run_id             uuid not null references runs(id),
  args_hash          text not null,
  approved_args_hash text,                      -- hash submitted by the approver; must equal args_hash
  policy_version_id  uuid not null references policy_versions(id),
  reasons            jsonb not null,
  advisory_note      jsonb,                     -- {"model":"…","text":"…","generated_at":"…"}; never used for decisions
  status             text not null check (status in ('PENDING','APPROVED','REJECTED','EXPIRED','CANCELLED')),
  requested_at       timestamptz not null,
  expires_at         timestamptz not null,
  decided_by         uuid references users(id),
  decided_at         timestamptz,
  decision_note      text
);
create index approvals_pending on approvals(expires_at) where status = 'PENDING';

-- ── ledger ───────────────────────────────────────────────────────────
create table journal_entries (
  id          uuid primary key,
  run_id      uuid not null references runs(id),
  action_id   uuid not null references actions(id),     -- the PRIMARY action the money belongs to
  kind        text not null check (kind in ('REFUND_ISSUED','REFUND_SETTLED','REFUND_CANCELLED','REFUND_FAILED',
                                             'CREDIT_GRANTED','CREDIT_REVOKED')),
  source      text not null check (source in ('execution','webhook','recon','compensation','review')),
  source_ref  text,
  created_at  timestamptz not null,
  unique (action_id, kind)
);

create table postings (
  id            bigint generated always as identity primary key,
  entry_id      uuid not null references journal_entries(id),
  account       text not null,
  amount_minor  bigint not null check (amount_minor <> 0),   -- + debit, − credit
  currency      char(3) not null
);
create index postings_entry on postings(entry_id);

create function check_journal_balanced() returns trigger language plpgsql as $$
declare s bigint;
begin
  select coalesce(sum(amount_minor), 0) into s from postings where entry_id = new.entry_id;
  if s <> 0 then
    raise exception 'journal entry % is not balanced (sum=%)', new.entry_id, s;
  end if;
  return null;
end $$;
create constraint trigger postings_balanced
  after insert or update on postings
  deferrable initially deferred
  for each row execute function check_journal_balanced();

create function forbid_change() returns trigger language plpgsql as $$
begin raise exception '% is append-only', tg_table_name; end $$;
create trigger postings_append_only           before update or delete on postings           for each row execute function forbid_change();
create trigger action_transitions_append_only before update or delete on action_transitions for each row execute function forbid_change();

-- ── revert ───────────────────────────────────────────────────────────
create table revert_plans (
  id                uuid primary key,
  number            bigint generated always as identity unique,
  run_id            uuid not null references runs(id),
  scope             text not null check (scope in ('RUN','ACTIONS')),
  requested_by      uuid not null references users(id),
  status            text not null check (status in ('PREVIEW','EXECUTING','REVERTED','PARTIALLY_REVERTED','CANCELLED')),
  summary           jsonb not null,
  lease_owner       text,
  lease_expires_at  timestamptz,
  lease_fence       bigint not null default 0,
  created_at        timestamptz not null,
  started_at        timestamptz,
  completed_at      timestamptz
);
create unique index revert_one_open_per_run on revert_plans(run_id) where status in ('PREVIEW','EXECUTING');

create table revert_items (
  id                      uuid primary key,
  plan_id                 uuid not null references revert_plans(id),
  order_no                int not null,
  target_action_id        uuid not null references actions(id),
  kind                    text not null check (kind in ('COMPENSATE','IRREVERSIBLE','CONFLICT','ALREADY_REVERTED','NO_EFFECT',
                                                        'BLOCKED_IN_FLIGHT','BLOCKED_NEEDS_REVIEW')),
  status                  text not null check (status in ('PLANNED','IN_PROGRESS','DONE','FAILED','CONFLICT',
                                                          'BECAME_IRREVERSIBLE','SKIPPED')),
  compensation_action_id  uuid references actions(id),
  remediation_action_id   uuid references actions(id),
  money_recovered_minor      bigint not null default 0,
  money_not_recovered_minor  bigint not null default 0,
  note                    text,
  unique (plan_id, order_no),
  unique (plan_id, target_action_id)
);

-- ── webhooks ─────────────────────────────────────────────────────────
create table webhook_events (
  provider          text not null,
  event_id          text not null,
  type              text not null,
  payload           jsonb not null,
  received_at       timestamptz not null,
  processed_at      timestamptz,
  process_attempts  int not null default 0,
  last_error        text,
  primary key (provider, event_id)
);
create index webhook_unprocessed on webhook_events(received_at) where processed_at is null;

-- ── reconciliation ───────────────────────────────────────────────────
create table recon_runs (
  id            uuid primary key,
  number        bigint generated always as identity unique,
  connector     text not null,
  window_start  timestamptz not null,
  window_end    timestamptz not null,
  status        text not null check (status in ('RUNNING','COMPLETED','FAILED')),
  triggered_by  text not null,                  -- schedule | user:<email>
  stats         jsonb not null default '{}',
  started_at    timestamptz not null,
  finished_at   timestamptz
);

create table recon_findings (
  id                   uuid primary key,
  recon_run_id         uuid not null references recon_runs(id),
  type                 text not null check (type in ('GHOST_EFFECT','MISSING_EFFECT','DUPLICATE_EFFECT','AMOUNT_MISMATCH',
                                                     'STATUS_MISMATCH','COMPENSATION_MISMATCH','UNKNOWN_ORIGIN',
                                                     'UNMATCHED_WEBHOOK')),
  severity             text not null check (severity in ('CRITICAL','WARNING','INFO')),
  action_id            uuid references actions(id),
  provider_object_ref  text,
  expected             jsonb,
  actual               jsonb,
  status               text not null check (status in ('OPEN','AUTO_HEALED','ACKNOWLEDGED','RESOLVED')),
  fingerprint          text not null,           -- type + ':' + (action_id or provider_object_ref)
  resolution_note      text,
  resolved_by          uuid references users(id),
  resolved_at          timestamptz,
  created_at           timestamptz not null
);
create unique index recon_open_fingerprint on recon_findings(fingerprint) where status in ('OPEN','ACKNOWLEDGED');

-- ── job leases (recon scheduler, other singleton jobs) ───────────────
create table job_leases (
  name        text primary key,               -- 'recon:shopfront'
  owner       text,
  expires_at  timestamptz,
  fence       bigint not null default 0
);

-- ── events (transactional outbox) ────────────────────────────────────
create table events (
  seq        bigint generated always as identity primary key,
  run_id     uuid references runs(id),
  action_id  uuid,
  type       text not null,
  data       jsonb not null,
  at         timestamptz not null,
  prev_hash  text,                              -- per-run audit chain (FR-SEC-5)
  hash       text
);
create index events_run on events(run_id, seq);
create trigger events_append_only before update or delete on events for each row execute function forbid_change();

create table relay_checkpoints (
  name      text primary key,                   -- 'kafka'
  last_seq  bigint not null
);
