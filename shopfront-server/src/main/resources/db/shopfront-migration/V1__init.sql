create table customers (id text primary key, name text not null, email text not null, created_at timestamptz not null);
create table orders (
  id text primary key, customer_id text not null references customers(id), item_name text not null,
  total_minor bigint not null check (total_minor > 0), currency char(3) not null default 'INR',
  status text not null, carrier_status text not null, placed_at timestamptz not null, delivered_at timestamptz,
  note text, note_version int not null default 0, carrier_bug_original text);
create table tickets (
  id text primary key, customer_id text not null references customers(id), order_id text references orders(id),
  category text not null,            -- hidden ground truth, never returned by /v1
  subject text not null, body text not null, status text not null, resolution text,
  created_at timestamptz not null, closed_at timestamptz);
create table refunds (
  id text primary key, order_id text not null references orders(id), amount_minor bigint not null check (amount_minor > 0),
  reason text not null, status text not null, metadata jsonb not null default '{}',
  created_at timestamptz not null, settles_after timestamptz not null, updated_at timestamptz not null);
create index refunds_undo_action on refunds((metadata->>'undo_action_id'));
create table store_credits (
  id text primary key, customer_id text not null references customers(id), amount_minor bigint not null,
  balance_minor bigint not null, reason text not null, reference text, status text not null,
  revoked_minor bigint not null default 0, created_at timestamptz not null, updated_at timestamptz not null);
create index store_credits_reference on store_credits(reference);
create table emails (
  id text primary key, customer_id text not null references customers(id), template text not null,
  variables jsonb not null, metadata jsonb not null default '{}', status text not null, created_at timestamptz not null);
create table idempotency_keys (key text primary key, fingerprint text not null, response_status int not null,
  response_body jsonb not null, created_at timestamptz not null);
create table webhook_outbox (id text primary key, type text not null, payload jsonb not null, attempts int not null default 0,
  next_attempt_at timestamptz not null, delivered_at timestamptz, created_at timestamptz not null);
create table fault_config (id int primary key check (id = 1), config jsonb not null);
