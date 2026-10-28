/** Free-form JSON from the server (facts, event data); fields are read defensively. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Loose = Record<string, any>;

export type Budgets = {
  money_cap_minor: number;
  money_reserved_minor: number;
  money_spent_minor: number;
  money_remaining_minor: number;
  action_cap: number;
  actions_used: number;
  llm_token_cap: number;
  llm_tokens_used: number;
};

export type RunSummary = {
  id: string;
  number: number;
  kind: string;
  status: string;
  title: string;
  scenario: string | null;
  model_mode: string | null;
  labels: Record<string, unknown>;
  budgets: Budgets;
  counts_by_status: Record<string, number>;
  actions_total: number;
  actions_write: number;
  pending_approvals: number;
  agent?: string;
  policy?: string;
  failure_reason: string | null;
  created_at: string;
  completed_at: string | null;
  frozen_at: string | null;
};

export type RunDetail = RunSummary & {
  net_by_account: Record<string, number>;
  net_exposure_minor: number;
  unknown_now: number;
  tasks: {
    id: string;
    seq: number;
    input: Record<string, string>;
    status: string;
    turns: number;
    failure_reason: string | null;
  }[];
  revert_plans: { number: number; status: string; scope: string }[];
};

export type ActionRow = {
  id: string;
  number: number;
  display: string;
  run_id: string;
  kind: string;
  tool: string;
  args: Record<string, unknown>;
  args_hash: string;
  status: string;
  status_reason: string | null;
  decision: string | null;
  matched_rules: { id: string; decision: string; reason: string }[];
  idempotency_class: string;
  money_out_minor: number;
  provider_ref: string | null;
  provider_status: string | null;
  result: unknown;
  error: { code?: string; message?: string } | null;
  attempt_count: number;
  created_at: string;
  updated_at: string;
  completed_at: string | null;
  summary: string;
  task_id: string | null;
  compensates_action_id: string | null;
  compensated_by_action_id: string | null;
  agent_status: string;
};

export type Approval = {
  id: string;
  status: string;
  args_hash: string;
  approved_args_hash: string | null;
  reasons: { id: string; decision: string; reason: string }[];
  advisory_note: { model?: string; text?: string; generated_at?: string } | null;
  requested_at: string;
  expires_at: string;
  decided_at: string | null;
  decision_note: string | null;
};

export type ApprovalCard = Approval & {
  action_number: number;
  action: string;
  action_id: string;
  action_status: string;
  kind: string;
  tool: string;
  args: Record<string, unknown>;
  facts: Loose | null;
  amount_minor: number;
  amount_text: string;
  ticket_id?: string;
  ticket_subject?: string;
  run_number: number;
  run_title: string;
};

export type FullAction = ActionRow & {
  run_number: number;
  run_status: string;
  facts: Loose | null;
  before_snapshot: unknown;
  dedupe_key: string | null;
  dedupe_active: boolean;
  budget_state: string;
  provider_idempotency_key: string | null;
  lease_fence: number;
  lookup_count: number;
  unknown_since: string | null;
  rendered: unknown;
  policy?: string;
  duplicate_of?: string;
  duplicate_of_number?: number;
  transitions: {
    from: string | null;
    to: string;
    fence: number;
    actor: string;
    reason: string | null;
    at: string;
  }[];
  attempts: {
    kind: string;
    attempt_no: number;
    node: string;
    fence: number;
    started_at: string;
    finished_at: string | null;
    outcome: string | null;
    http_status?: number;
    latency_ms?: number;
    detail: unknown;
  }[];
  approval?: Approval;
  postings: { account: string; amount_minor: number }[];
  compensated_by?: string;
  compensated_by_number?: number;
  compensates?: string;
  compensates_number?: number;
};

export type Ledger = {
  entries: {
    id: string;
    kind: string;
    source: string;
    source_ref: string | null;
    created_at: string;
    action_number?: number;
    action?: string;
    postings: { account: string; amount_minor: number }[];
  }[];
  net_by_account: Record<string, number>;
  balanced: boolean;
  net_exposure_minor: number;
};

export type Overview = {
  tiles: {
    active_runs: number;
    pending_approvals: number;
    unknown_now: number;
    needs_review: number;
    open_critical_findings: number;
    money_out_today_minor: number;
    in_flight: number;
  };
  activity: EventRow[];
  runs: RunSummary[];
  demo: boolean;
  environment: string;
  latest_seq: number;
};

export type EventRow = {
  seq: number;
  type: string;
  run_id: string | null;
  action_id: string | null;
  at: string;
  data: Loose;
};

export type RevertItem = {
  order_no: number;
  kind: string;
  status: string;
  money_recovered_minor: number;
  money_not_recovered_minor: number;
  note: string | null;
  remediation: { tool: string; description: string; args: Loose } | null;
  action_number: number;
  action: string;
  tool: string;
  summary: string;
  money_out_minor: number;
  compensation_number?: number;
  compensation?: string;
  compensation_status?: string;
  remediation_number?: number;
  remediation_action?: string;
  remediation_status?: string;
  remediation_approval_status?: string;
};

export type RevertPlan = {
  id: string;
  number: number;
  scope: string;
  status: string;
  summary: {
    counts_by_kind: Record<string, number>;
    money_recoverable_minor: number;
    money_not_recoverable_minor: number;
    blocked: number;
    remediations: number;
    generated_at: string;
    report_text?: string;
    money_recovered_minor?: number;
    money_not_recovered_minor?: number;
    net_exposure_after_minor?: number;
  };
  created_at: string;
  started_at: string | null;
  completed_at: string | null;
  run_number: number;
  run_title: string;
  run_status: string;
  items: RevertItem[];
};

export type ReconRun = {
  id: string;
  number: number;
  connector: string;
  status: string;
  triggered_by: string;
  window_start: string;
  window_end: string;
  started_at: string;
  finished_at: string | null;
  stats: {
    provider_objects?: number;
    actions_checked?: number;
    matched?: number;
    findings_by_type?: Record<string, number>;
    auto_healed?: number;
    duration_ms?: number;
    error?: string;
  };
};

export type ReconRuns = {
  interval_seconds: number;
  next_scheduled_at: string | null;
  data: ReconRun[];
};

export type Finding = {
  id: string;
  type: string;
  severity: string;
  status: string;
  provider_object_ref: string | null;
  expected: Record<string, unknown> | null;
  actual: Record<string, unknown> | null;
  resolution_note: string | null;
  resolved_at: string | null;
  created_at: string;
  action_id?: string;
  action_number?: number;
  action_display?: string;
  tool?: string;
  run_number?: number;
};
