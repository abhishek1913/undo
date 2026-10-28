-- Chaos invariants on the `undo` database (PRD 22.5 step 6). Every row must have violations = 0.
select 'budget: reserved + spent within the cap (per run)' as invariant, count(*) as violations
  from runs where money_reserved_minor + money_spent_minor > money_cap_minor
union all
select 'budget: run totals equal the sum of its actions (reserved, spent)', count(*)
  from runs r where
    r.money_spent_minor    <> coalesce((select sum(money_out_minor) from actions a where a.run_id = r.id and a.budget_state = 'SPENT'), 0)
 or r.money_reserved_minor <> coalesce((select sum(money_out_minor) from actions a where a.run_id = r.id and a.budget_state = 'RESERVED'), 0)
union all
select 'journal: every entry balances per currency', count(*) from (
  select entry_id from postings group by entry_id, currency having sum(amount_minor) <> 0) u
union all
select 'no stuck actions (PROPOSED, APPROVED, EXECUTING, RETRY_WAIT, UNKNOWN)', count(*)
  from actions where status in ('PROPOSED','APPROVED','EXECUTING','RETRY_WAIT','UNKNOWN')
union all
select 'no live lease on a finished action', count(*)
  from actions where lease_owner is not null and lease_expires_at > now()
   and status in ('SUCCEEDED','FAILED','DENIED','REJECTED','EXPIRED','CANCELLED')
union all
select 'no action with two live compensations', count(*) from (
  select compensates_action_id from actions
   where compensates_action_id is not null and status not in ('FAILED','CANCELLED','DENIED','REJECTED','EXPIRED')
   group by compensates_action_id having count(*) > 1) u
union all
select 'transitions are legal (8.2) and continuous per action', count(*) from (
  select t.id from (
    select id, action_id, from_status, to_status,
           lag(to_status) over (partition by action_id order by id) as prev_to
      from action_transitions) t
   where (t.prev_to is not null and t.from_status is distinct from t.prev_to)
      or (t.from_status is not null and (t.from_status, t.to_status) not in (
        ('PROPOSED','DENIED'),('PROPOSED','AWAITING_APPROVAL'),('PROPOSED','APPROVED'),
        ('AWAITING_APPROVAL','APPROVED'),('AWAITING_APPROVAL','REJECTED'),('AWAITING_APPROVAL','EXPIRED'),('AWAITING_APPROVAL','CANCELLED'),
        ('APPROVED','EXECUTING'),('APPROVED','CANCELLED'),
        ('EXECUTING','SUCCEEDED'),('EXECUTING','FAILED'),('EXECUTING','NEEDS_REVIEW'),('EXECUTING','RETRY_WAIT'),('EXECUTING','UNKNOWN'),
        ('RETRY_WAIT','EXECUTING'),('RETRY_WAIT','CANCELLED'),
        ('UNKNOWN','EXECUTING'),('UNKNOWN','APPROVED'),('UNKNOWN','SUCCEEDED'),('UNKNOWN','FAILED'),('UNKNOWN','NEEDS_REVIEW'),
        ('NEEDS_REVIEW','SUCCEEDED'),('NEEDS_REVIEW','FAILED'))) ) u
union all
select 'no action whose status differs from its last transition', count(*) from actions a
  where a.status <> (select to_status from action_transitions t where t.action_id = a.id order by t.id desc limit 1)
union all
select 'no two PRIMARY actions for one agent call id (INV-11)', count(*) from (
  select agent_call_id from actions where agent_call_id is not null and kind = 'PRIMARY'
   group by run_id, agent_call_id having count(*) > 1) u
union all
select 'no SUCCEEDED write action without provider evidence (ref or result)', count(*)
  from actions where effect = 'WRITE' and status = 'SUCCEEDED' and provider_ref is null and result is null
union all
select 'no open CRITICAL recon finding', count(*)
  from recon_findings where severity = 'CRITICAL' and status in ('OPEN','ACKNOWLEDGED')
union all
select 'hosted runs finished (none ACTIVE)', count(*) from runs where kind = 'HOSTED' and status = 'ACTIVE';
