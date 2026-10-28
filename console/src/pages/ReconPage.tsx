import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useEventStream } from '../lib/sse';
import type { Finding, ReconRuns } from '../lib/types';
import { ConfirmDialog } from '../components/Dialogs';
import { useToast } from '../components/Toasts';
import {
  Button,
  Card,
  Empty,
  ErrorBox,
  Loading,
  PageTitle,
  StatusPill,
  Table,
  Td,
  Tile,
  Time,
} from '../components/ui';

const TYPES = [
  'GHOST_EFFECT',
  'UNKNOWN_ORIGIN',
  'MISSING_EFFECT',
  'DUPLICATE_EFFECT',
  'AMOUNT_MISMATCH',
  'STATUS_MISMATCH',
  'COMPENSATION_MISMATCH',
  'UNMATCHED_WEBHOOK',
];
const STATUSES = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED'];

function explain(e: unknown): string {
  if (e instanceof ApiError) {
    const known: Record<string, string> = {
      RECON_RUNNING: 'A reconciliation is already running. Try again in a moment.',
      FINDING_NOT_OPEN: 'Someone else already handled this finding.',
    };
    return known[e.code] ?? `${e.code}: ${e.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}

/** Expected vs actual, one row per field; differing fields are marked. */
function Diff({ f }: { f: Finding }) {
  const exp = f.expected ?? {};
  const act = f.actual ?? {};
  const keys = Array.from(new Set([...Object.keys(exp), ...Object.keys(act)]));
  if (keys.length === 0) return <span className="text-ink-2">—</span>;
  const show = (v: unknown) =>
    v === undefined ? '—' : typeof v === 'string' ? v : JSON.stringify(v);
  return (
    <dl className="grid grid-cols-[auto_1fr_1fr] gap-x-3 gap-y-0.5 font-mono text-[12px]">
      <dt className="text-ink-2" />
      <dd className="text-ink-2">expected</dd>
      <dd className="text-ink-2">actual</dd>
      {keys.map((k) => {
        const differs = JSON.stringify(exp[k]) !== JSON.stringify(act[k]);
        return (
          <div key={k} className="contents">
            <dt className="text-ink-2">{k}</dt>
            <dd className="break-all">{show(exp[k])}</dd>
            <dd className={`break-all ${differs ? 'font-semibold text-bad' : ''}`}>
              {differs && <span className="sr-only">differs: </span>}
              {show(act[k])}
            </dd>
          </div>
        );
      })}
    </dl>
  );
}

function ActionRef({ f }: { f: Finding }) {
  if (!f.action_number) return <span className="text-ink-2">—</span>;
  return (
    <div>
      <Link className="font-mono underline underline-offset-2" to={`/actions/${f.action_number}`}>
        {f.action_display}
      </Link>
      <div className="font-mono text-[12px] text-ink-2">{f.tool}</div>
      {f.run_number !== undefined && (
        <Link
          className="text-[12px] text-ink-2 underline-offset-2 hover:underline"
          to={`/runs/${f.run_number}`}
        >
          run #{f.run_number}
        </Link>
      )}
    </div>
  );
}

/** /reconciliation (Sections 16 and 20.4, F6): last and next run, findings, auto-healed items. */
export function ReconPage() {
  const { has } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();
  const [status, setStatus] = useState('OPEN');
  const [type, setType] = useState('');
  const [busy, setBusy] = useState(false);
  const runs = useQuery({
    queryKey: ['recon-runs'],
    queryFn: () => api<ReconRuns>('/api/recon/runs'),
    refetchInterval: 15000,
  });
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (type) qs.set('type', type);
  const findings = useQuery({
    queryKey: ['findings', status, type],
    queryFn: () => api<{ data: Finding[] }>(`/api/recon/findings?${qs.toString()}`),
    refetchInterval: 15000,
  });
  const healed = useQuery({
    queryKey: ['findings', 'AUTO_HEALED'],
    queryFn: () => api<{ data: Finding[] }>('/api/recon/findings?status=AUTO_HEALED'),
    refetchInterval: 15000,
  });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['recon-runs'] });
    void qc.invalidateQueries({ queryKey: ['findings'] });
    void qc.invalidateQueries({ queryKey: ['overview'] });
  };
  useEventStream({ afterSeq: null }, (e) => {
    if (e.type.startsWith('recon.')) refresh();
  });

  const runNow = async () => {
    setBusy(true);
    try {
      const r = await api<{ number: number; stats: { auto_healed?: number } }>('/api/recon/runs', {
        method: 'POST',
        json: {},
      });
      toast(`Recon run #${r.number} finished; ${r.stats.auto_healed ?? 0} auto-healed.`);
      refresh();
    } catch (e) {
      toast(explain(e), 'bad');
    } finally {
      setBusy(false);
    }
  };

  const act = async (f: Finding, what: 'acknowledge' | 'resolve', note?: string) => {
    try {
      await api(`/api/recon/findings/${f.id}/${what}`, {
        method: 'POST',
        json: note ? { note } : {},
      });
      toast(what === 'resolve' ? 'Finding resolved.' : 'Finding acknowledged.');
      refresh();
    } catch (e) {
      throw new Error(explain(e), { cause: e });
    }
  };

  if (runs.isLoading) return <Loading what="Loading reconciliation" />;
  if (runs.error) return <ErrorBox error={runs.error} />;
  const last = runs.data?.data.find((r) => r.status !== 'RUNNING') ?? null;
  const open = (findings.data?.data ?? []).filter((f) => f.status !== 'RESOLVED');
  const critical = status === 'OPEN' ? open.filter((f) => f.severity === 'CRITICAL').length : null;

  return (
    <div>
      <PageTitle
        sub="Undo compares its records with Shopfront's and reports every difference."
        actions={
          has('ADMIN') && (
            <Button kind="primary" onClick={runNow} disabled={busy}>
              {busy ? 'Running…' : 'Run now'}
            </Button>
          )
        }
      >
        Reconciliation
      </PageTitle>

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Tile
          label="Last run"
          value={last ? `#${last.number}` : '—'}
          hint={last ? <Time iso={last.finished_at} /> : 'never'}
          tone={last?.status === 'FAILED' ? 'bad' : undefined}
        />
        <Tile
          label="Next scheduled"
          value={runs.data?.next_scheduled_at ? <Time iso={runs.data.next_scheduled_at} /> : 'soon'}
          hint={`every ${Math.round((runs.data?.interval_seconds ?? 300) / 60)} min`}
        />
        <Tile
          label="Checked in last run"
          value={last?.stats.actions_checked ?? 0}
          hint={`${last?.stats.provider_objects ?? 0} provider objects · ${last?.stats.matched ?? 0} matched`}
        />
        <Tile
          label="Open critical"
          value={critical ?? '—'}
          tone={critical ? 'bad' : 'ok'}
          hint={status === 'OPEN' ? 'need a person' : 'filter by OPEN'}
        />
      </div>

      <Card title="Findings" className="mb-4">
        <div className="mb-3 flex flex-wrap gap-3 text-[13px]">
          <label className="flex items-center gap-2">
            Status
            <select
              className="rounded-[8px] border border-line bg-bg px-2 py-1"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">All</option>
              {STATUSES.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-2">
            Type
            <select
              className="rounded-[8px] border border-line bg-bg px-2 py-1"
              value={type}
              onChange={(e) => setType(e.target.value)}
            >
              <option value="">All</option>
              {TYPES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </label>
        </div>
        {findings.error && <ErrorBox error={findings.error} />}
        {findings.data && findings.data.data.length === 0 ? (
          <Empty>No findings match. Undo and Shopfront agree.</Empty>
        ) : (
          <Table
            label="Findings"
            head={[
              'Severity',
              'Type',
              'Action',
              'Provider ref',
              'Expected vs actual',
              'Status',
              '',
            ]}
          >
            {(findings.data?.data ?? []).map((f) => (
              <tr
                key={f.id}
                className={f.severity === 'CRITICAL' && f.status === 'OPEN' ? 'bg-bad/5' : ''}
              >
                <Td>
                  <StatusPill status={f.severity} />
                </Td>
                <Td className="font-mono text-[12px]">{f.type}</Td>
                <Td>
                  <ActionRef f={f} />
                </Td>
                <Td className="font-mono text-[12px]">{f.provider_object_ref ?? '—'}</Td>
                <Td className="min-w-[260px]">
                  <Diff f={f} />
                </Td>
                <Td>
                  <StatusPill status={f.status} />
                  <div className="mt-1 text-[12px] text-ink-2">
                    <Time iso={f.created_at} />
                  </div>
                  {f.resolution_note && (
                    <div className="mt-1 max-w-[220px] text-[12px] text-ink-2">
                      “{f.resolution_note}”
                    </div>
                  )}
                </Td>
                <Td className="whitespace-nowrap">
                  {has('APPROVER') && f.status === 'OPEN' && (
                    <Button
                      onClick={() =>
                        act(f, 'acknowledge').catch((e) => toast(String(e.message), 'bad'))
                      }
                    >
                      Acknowledge
                    </Button>
                  )}{' '}
                  {has('APPROVER') && (f.status === 'OPEN' || f.status === 'ACKNOWLEDGED') && (
                    <ConfirmDialog
                      trigger={<Button>Resolve</Button>}
                      title={`Resolve ${f.type}`}
                      description="Say what you checked or fixed. The note is kept with the finding."
                      confirmLabel="Resolve"
                      withNote
                      noteRequired
                      danger={false}
                      onConfirm={(note) => act(f, 'resolve', note)}
                    />
                  )}
                </Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <Card title="Auto-healed">
        {healed.data && healed.data.data.length === 0 ? (
          <Empty>Nothing was auto-healed yet.</Empty>
        ) : (
          <Table label="Auto-healed findings" head={['When', 'Type', 'Action', 'What changed']}>
            {(healed.data?.data ?? []).map((f) => (
              <tr key={f.id}>
                <Td className="whitespace-nowrap">
                  <Time iso={f.created_at} />
                </Td>
                <Td className="font-mono text-[12px]">{f.type}</Td>
                <Td>
                  <ActionRef f={f} />
                </Td>
                <Td>
                  <div className="text-[13px]">{String(f.actual?.healed ?? '')}</div>
                  <Diff f={f} />
                </Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
