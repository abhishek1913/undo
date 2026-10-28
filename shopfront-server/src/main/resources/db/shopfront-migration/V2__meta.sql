-- Small key/value table for seed metadata (carrier-bug selection, ground truth). See ADR-013.
create table shop_meta (key text primary key, value jsonb not null);
create index refunds_updated on refunds(updated_at, id);
create index store_credits_updated on store_credits(updated_at, id);
create index emails_created on emails(created_at, id);
create index refunds_order on refunds(order_id);
create index webhook_outbox_due on webhook_outbox(next_attempt_at) where delivered_at is null;
-- jsonb reorders object keys; idempotent replays must be byte-identical, so keep the exact text.
alter table idempotency_keys alter column response_body type text using response_body::text;
