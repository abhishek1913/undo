import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { money } from '../lib/format';
import type { RevertPlan, RunDetail } from '../lib/types';
import { ConfirmDialog } from '../components/Dialogs';
import { useToast } from '../components/Toasts';
import {
  Button,
  Card,
  Empty,
  ErrorBox,
  Loading,
  Money,
  PageTitle,
  StatusPill,
  Table,
  Td,
  Time,
} from '../components/ui';

const OPEN = ['PREVIEW', 'EXECUTING'];

function explain(e: unknown): string {
  if (e instanceof ApiError) {
    const known: Record<string, string> = {
      REVERT_BLOCKED:
        'Some actions are still in flight or need review. The preview refreshes on its own; try again when nothing is blocked.',
      CONFIRMATION_MISMATCH: 'The run number you typed does not match.',
      REVERT_PREVIEW_EXPIRED: 'The preview is older than 15 minutes. Create a new preview.',
      ALREADY_REVERTED: 'This run was already reverted.',
      REVERT_IN_PROGRESS: 'A revert of this run is already running.',
    };
    return known[e.code] ?? `${e.code}: ${e.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}

/** /runs/:number/revert (Section 20.4): preview, typed confirmation, live progress, report. */
export function RevertPage() {
  const { number = '' } = useParams();
  const { has } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();
  const run = useQuery({
    queryKey: ['run', number],
    queryFn: () => api<RunDetail>(`/api/runs/${number}`),
  });
  const latest = run.data?.revert_plans.length
    ? run.data.revert_plans[run.data.revert_plans.length - 1]!
    : null;
  const [planNumber, setPlanNumber] = useState<number | null>(null);
  const current = planNumber ?? latest?.number ?? null;
  const plan = useQuery({
    queryKey: ['plan', current],
    queryFn: () => api<RevertPlan>(`/api/revert-plans/${current}`),
    enabled: current !== null,
    refetchInterval: (q) => (q.state.data && OPEN.includes(q.state.data.status) ? 2000 : false),
  });
  const [typed, setTyped] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const refreshing = useRef(false);

  // A preview with blocked items re-checks them on every poll (15.1).
  const p = plan.data;
  useEffect(() => {
    if (
      !p ||
      p.status !== 'PREVIEW' ||
      p.summary.blocked === 0 ||
      refreshing.current ||
      !has('REVERTER')
    )
      return;
    refreshing.current = true;
    api(`/api/revert-plans/${p.number}/refresh`, { method: 'POST', json: {} })
      .then(() => qc.invalidateQueries({ queryKey: ['plan', p.number] }))
      .catch(() => {})
      .finally(() => {
        refreshing.current = false;
      });
  }, [p, qc, has]);

  if (run.isLoading) return <Loading />;
  if (run.error) return <ErrorBox error={run.error} />;
  const r = run.data!;

  async function createPreview() {
    setBusy(true);
    setError(null);
    try {
      const created = await api<RevertPlan>(`/api/runs/${r.number}/revert-plans`, {
        method: 'POST',
        json: { scope: 'RUN' },
      });
      setPlanNumber(created.number);
      qc.invalidateQueries({ queryKey: ['run', number] });
    } catch (e) {
      setError(explain(e));
    } finally {
      setBusy(false);
    }
  }

  async function execute() {
    if (!p) return;
    setBusy(true);
    setError(null);
    try {
      await api(`/api/revert-plans/${p.number}/execute`, {
        method: 'POST',
        json: { confirm_run_number: Number(typed) },
      });
      toast(`Revert of Run #${r.number} started`);
      qc.invalidateQueries({ queryKey: ['plan', p.number] });
    } catch (e) {
      setError(explain(e));
    } finally {
      setBusy(false);
    }
  }

  const header = (
    <PageTitle
      sub={
        <span>
          <Link className="hover:underline" to={`/runs/${r.number}`}>
            Back to the run
          </Link>
          {p && (
            <>
              {' '}
              · plan {p.number} created <Time iso={p.created_at} /> · run{' '}
              <StatusPill status={p.run_status} />
            </>
          )}
        </span>
      }
    >
      <span className="mr-2">
        Revert run #{r.number} · {r.title}
      </span>
      {p && <StatusPill status={p.status} />}
    </PageTitle>
  );

  if (!p || (p.status === 'CANCELLED' && planNumber === null)) {
    return (
      <div>
        {header}
        <Card title="Preview first">
          <p className="text-[14px]">
            The preview shows what Undo will undo, what cannot be undone and why, and what is still
            in flight. Nothing is changed at the provider yet.
            {r.status === 'ACTIVE' && ' The run is ACTIVE: creating the preview freezes it first.'}
          </p>
          {error && (
            <p role="alert" className="mt-3 text-[13px] text-bad">
              {error}
            </p>
          )}
          {has('REVERTER') ? (
            <div className="mt-4">
              {r.status === 'ACTIVE' ? (
                <ConfirmDialog
                  trigger={<Button kind="primary">Freeze and preview</Button>}
                  title={`Freeze Run #${r.number} and preview the revert?`}
                  description="Pending approvals and queued actions are cancelled. The agent cannot propose anything new."
                  confirmLabel="Freeze and preview"
                  onConfirm={createPreview}
                />
              ) : (
                <Button kind="primary" onClick={() => void createPreview()} disabled={busy}>
                  Create preview
                </Button>
              )}
            </div>
          ) : (
            <p className="mt-3 text-[13px] text-ink-2">Only a reverter can revert a run.</p>
          )}
        </Card>
      </div>
    );
  }

  const c = p.summary.counts_by_kind;
  const finished = p.status === 'REVERTED' || p.status === 'PARTIALLY_REVERTED';
  const remediations = p.items.filter((i) => i.remediation_number).length;
  return (
    <div>
      {header}
      {finished && (
        <Card title="Report" className="mb-4">
          <p className="text-[15px]" data-testid="revert-report">
            {p.summary.report_text}
          </p>
          <p className="mt-2 text-[13px] text-ink-2">
            Recovered <Money minor={p.summary.money_recovered_minor} /> · not recovered{' '}
            <Money minor={p.summary.money_not_recovered_minor} /> · net exposure after{' '}
            <Money minor={p.summary.net_exposure_after_minor} />
          </p>
          {remediations > 0 && (
            <Link to={`/approvals?run=${r.number}`} className="mt-3 inline-block">
              <Button kind="primary">Open remediation approvals ({remediations})</Button>
            </Link>
          )}
        </Card>
      )}
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <Summary
          label="Will undo"
          n={c.COMPENSATE ?? 0}
          money={p.summary.money_recoverable_minor}
          word="recoverable"
        />
        <Summary
          label="Cannot undo"
          n={c.IRREVERSIBLE ?? 0}
          money={p.summary.money_not_recoverable_minor}
          word="not recoverable"
        />
        <Summary label="Conflicts" n={c.CONFLICT ?? 0} />
        <Summary label="Blocked" n={(c.BLOCKED_IN_FLIGHT ?? 0) + (c.BLOCKED_NEEDS_REVIEW ?? 0)} />
      </div>
      <Table
        label="Revert items"
        head={['#', 'Action', 'Tool', 'What happens', 'Plan', 'Status', 'Money']}
      >
        {p.items.map((it) => (
          <tr key={it.order_no}>
            <Td>{it.order_no}</Td>
            <Td>
              <Link
                className="font-mono text-[12px] hover:underline"
                to={`/actions/${it.action_number}`}
              >
                {it.action}
              </Link>
            </Td>
            <Td className="font-mono text-[12px]">{it.tool}</Td>
            <Td className="max-w-[360px]">
              {it.note}
              {it.compensation && (
                <div className="text-[12px] text-ink-2">
                  compensation{' '}
                  <Link
                    className="font-mono hover:underline"
                    to={`/actions/${it.compensation_number}`}
                  >
                    {it.compensation}
                  </Link>{' '}
                  {it.compensation_status}
                </div>
              )}
              {it.remediation && (
                <div className="text-[12px] text-ink-2">
                  {it.remediation.description}
                  {it.remediation_action && (
                    <>
                      {' · '}
                      <Link
                        className="font-mono hover:underline"
                        to={`/actions/${it.remediation_number}`}
                      >
                        {it.remediation_action}
                      </Link>{' '}
                      {it.remediation_status}
                    </>
                  )}
                </div>
              )}
            </Td>
            <Td>
              <StatusPill status={it.kind} />
            </Td>
            <Td>
              <StatusPill status={it.status} />
            </Td>
            <Td className="whitespace-nowrap">
              {it.money_recovered_minor ? <Money minor={it.money_recovered_minor} /> : null}
              {it.money_not_recovered_minor ? (
                <div className="text-ink-2">
                  <Money minor={it.money_not_recovered_minor} /> lost
                </div>
              ) : null}
              {!it.money_recovered_minor && !it.money_not_recovered_minor ? '—' : null}
            </Td>
          </tr>
        ))}
      </Table>
      {p.items.length === 0 && <Empty>This run has no write actions to revert.</Empty>}
      {p.status === 'PREVIEW' && has('REVERTER') && (
        <Card title="Execute" className="mt-4">
          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              void execute();
            }}
          >
            <label className="text-[13px]">
              Type the run number to confirm
              <input
                className="mt-1 block w-32 rounded-[8px] border border-line bg-bg p-2 font-mono text-[14px]"
                inputMode="numeric"
                value={typed}
                onChange={(e) => setTyped(e.target.value.replace(/\D/g, ''))}
                placeholder={String(r.number)}
              />
            </label>
            <Button
              onClick={async () => {
                await api(`/api/revert-plans/${p.number}/cancel`, { method: 'POST', json: {} });
                toast('Preview cancelled');
                qc.invalidateQueries({ queryKey: ['plan', p.number] });
              }}
            >
              Cancel preview
            </Button>
            <Button
              kind="danger"
              type="submit"
              disabled={busy || typed !== String(r.number) || p.summary.blocked > 0}
            >
              Execute revert
            </Button>
            {p.summary.blocked > 0 && (
              <span className="text-[13px] text-ink-2">
                {p.summary.blocked} blocked item(s); refreshing…
              </span>
            )}
          </form>
          {error && (
            <p role="alert" className="mt-3 text-[13px] text-bad">
              {error}
            </p>
          )}
        </Card>
      )}
      {p.status === 'EXECUTING' && (
        <p role="status" className="mt-4 text-[14px] text-ink-2">
          Reverting… items are processed one at a time, newest first.
        </p>
      )}
      {p.status === 'CANCELLED' && has('REVERTER') && (
        <div className="mt-4">
          <Button kind="primary" onClick={() => void createPreview()}>
            Create a new preview
          </Button>
        </div>
      )}
    </div>
  );
}

function Summary({
  label,
  n,
  money: m,
  word,
}: {
  label: string;
  n: number;
  money?: number;
  word?: string;
}) {
  return (
    <div className="rounded-[8px] border border-line bg-surface p-4">
      <div className="text-[12px] text-ink-2">{label}</div>
      <div className="mt-1 text-[28px] font-semibold tabular-nums">{n}</div>
      {m !== undefined && m > 0 && (
        <div className="text-[12px] text-ink-2">
          {money(m)} {word}
        </div>
      )}
    </div>
  );
}
