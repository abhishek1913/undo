import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useMemo, useRef } from 'react';
import { api } from '../lib/api';
import type { EventRow, Overview } from '../lib/types';
import { useEventStream } from '../lib/sse';
import {
  Card,
  Empty,
  ErrorBox,
  Loading,
  Money,
  PageTitle,
  StatusPill,
  Table,
  Td,
  Tile,
  Time,
} from '../components/ui';
import { EventText } from '../components/EventText';
import { clock, absolute } from '../lib/format';

/**
 * Newest first, each action once: an action's proposed → decided → executing → succeeded events
 * become one line at its latest status, with the number of steps folded into it.
 */
function condense(events: EventRow[]): { e: EventRow; steps: number }[] {
  const out: { e: EventRow; steps: number }[] = [];
  const byAction = new Map<unknown, { e: EventRow; steps: number }>();
  for (const e of events) {
    const n = e.data?.action_number;
    if (n === undefined || n === null) {
      out.push({ e, steps: 1 });
      continue;
    }
    const seen = byAction.get(n);
    if (seen) {
      seen.steps++;
      if (e.seq > seen.e.seq) seen.e = e;
    } else {
      const row = { e, steps: 1 };
      byAction.set(n, row);
      out.push(row);
    }
  }
  return out;
}

export function OverviewPage() {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ['overview'],
    queryFn: () => api<Overview>('/api/overview'),
    refetchInterval: 10_000,
  });
  const startSeq = useMemo(() => (q.data ? q.data.latest_seq : undefined), [q.data === undefined]); // eslint-disable-line react-hooks/exhaustive-deps
  const last = useRef(0);
  useEventStream({ afterSeq: startSeq ?? undefined, enabled: startSeq !== undefined }, () => {
    const now = Date.now();
    if (now - last.current > 1000) {
      last.current = now;
      qc.invalidateQueries({ queryKey: ['overview'] });
    }
  });
  if (q.isLoading) return <Loading />;
  if (q.error) return <ErrorBox error={q.error} />;
  const o = q.data!;
  const t = o.tiles;
  return (
    <div>
      <PageTitle sub="What agents are doing right now, and what is waiting for a person.">
        Overview
      </PageTitle>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <Tile
          label="Active runs"
          value={t.active_runs}
          hint={t.active_runs ? 'Working now' : 'No run is working'}
          to="/runs"
        />
        <Tile
          label="Pending approvals"
          value={t.pending_approvals}
          tone={t.pending_approvals ? 'warn' : undefined}
          hint={t.pending_approvals ? 'Waiting for you to decide' : 'Nothing waiting'}
          to={t.pending_approvals ? '/approvals' : undefined}
        />
        <Tile
          label="Unknown now"
          value={t.unknown_now}
          tone={t.unknown_now ? 'unknown' : undefined}
          hint={t.unknown_now ? 'Undo is checking with the provider' : 'Every outcome is known'}
        />
        <Tile
          label="Needs review"
          value={t.needs_review}
          tone={t.needs_review ? 'unknown' : undefined}
          hint={t.needs_review ? 'A person must say what happened' : 'Nothing to review'}
        />
        <Tile
          label="Open critical findings"
          value={t.open_critical_findings}
          tone={t.open_critical_findings ? 'bad' : undefined}
          hint={t.open_critical_findings ? 'Open in Reconciliation' : 'Provider and Undo agree'}
          to={t.open_critical_findings ? '/reconciliation' : undefined}
        />
        <Tile
          label="Money out today"
          value={<Money minor={t.money_out_today_minor} className="text-[24px]" />}
          hint="Refunds and credits, all runs"
        />
      </div>
      <div className="mt-6 grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,7fr)_minmax(0,5fr)]">
        <Card title="Live activity">
          {o.activity.length === 0 ? (
            <Empty>No activity yet. Start a run from the Demo page or the API.</Empty>
          ) : (
            <ul className="-my-1 divide-y divide-line text-[13px]" aria-label="Live activity">
              {condense(o.activity).map(({ e, steps }) => (
                <li key={e.seq} className="fade-in flex items-baseline gap-4 py-2">
                  <span
                    className="w-16 shrink-0 tabular-nums text-[12px] text-ink-2"
                    title={absolute(e.at)}
                  >
                    {clock(e.at)}
                  </span>
                  <EventText e={e} steps={steps} />
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card
          flush
          title="Runs"
          actions={
            <Link to="/runs" className="text-[13px] text-ink-2 hover:underline">
              All runs
            </Link>
          }
        >
          {o.runs.length === 0 ? (
            <div className="p-4">
              <Empty>No runs yet. Start one from the Demo page or the API.</Empty>
            </div>
          ) : (
            <Table bare label="Latest runs" head={['Run', 'Title', 'Status', 'Spent']}>
              {o.runs.map((r) => (
                <tr key={r.id} className="hover:bg-surface-2">
                  <Td>
                    <Link
                      className="font-mono text-[12px] hover:underline"
                      to={`/runs/${r.number}`}
                    >
                      #{r.number}
                    </Link>
                  </Td>
                  <Td className="min-w-[160px]">
                    <div>{r.title}</div>
                    <div className="mt-0.5 text-[12px]">
                      <Time iso={r.created_at} />
                    </div>
                  </Td>
                  <Td>
                    <StatusPill status={r.status} />
                  </Td>
                  <Td>
                    <Money minor={r.budgets.money_spent_minor} />
                  </Td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      </div>
    </div>
  );
}
