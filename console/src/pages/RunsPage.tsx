import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../lib/api';
import type { RunSummary } from '../lib/types';
import {
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
import { duration } from '../lib/format';

const STATUSES = [
  '',
  'ACTIVE',
  'COMPLETED',
  'FROZEN',
  'FAILED',
  'REVERTING',
  'REVERTED',
  'PARTIALLY_REVERTED',
];
const KINDS = ['', 'EXTERNAL', 'HOSTED'];

export function RunsPage() {
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? '';
  const kind = params.get('kind') ?? '';
  const q = useQuery({
    queryKey: ['runs', status, kind],
    queryFn: () => api<{ data: RunSummary[] }>(`/api/runs?status=${status}&kind=${kind}&limit=100`),
    refetchInterval: 5000,
  });
  function set(k: string, v: string) {
    const p = new URLSearchParams(params);
    if (v) p.set(k, v);
    else p.delete(k);
    setParams(p);
  }
  return (
    <div>
      <PageTitle sub="Every unit of agent work.">Runs</PageTitle>
      <div className="mb-3 flex flex-wrap gap-3 text-[13px]">
        <label className="flex items-center gap-2">
          Status
          <select
            className="rounded-[8px] border border-line bg-surface px-2 py-1"
            value={status}
            onChange={(e) => set('status', e.target.value)}
          >
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s || 'any'}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-2">
          Kind
          <select
            className="rounded-[8px] border border-line bg-surface px-2 py-1"
            value={kind}
            onChange={(e) => set('kind', e.target.value)}
          >
            {KINDS.map((s) => (
              <option key={s} value={s}>
                {s || 'any'}
              </option>
            ))}
          </select>
        </label>
      </div>
      {q.isLoading ? (
        <Loading />
      ) : q.error ? (
        <ErrorBox error={q.error} />
      ) : q.data!.data.length === 0 ? (
        <Empty>No runs match these filters.</Empty>
      ) : (
        <Table
          label="Runs"
          head={[
            'Run',
            'Title',
            'Kind',
            'Scenario',
            'Status',
            'Actions (write/total)',
            'Money spent / cap',
            'Pending',
            'Started',
            'Duration',
          ]}
        >
          {q.data!.data.map((r) => (
            <tr key={r.id} className="hover:bg-surface-2">
              <Td>
                <Link className="font-mono text-[12px] hover:underline" to={`/runs/${r.number}`}>
                  #{r.number}
                </Link>
              </Td>
              <Td>{r.title}</Td>
              <Td>{r.kind}</Td>
              <Td>{r.scenario ?? '—'}</Td>
              <Td>
                <StatusPill status={r.status} />
              </Td>
              <Td className="font-mono text-[12px]">
                {r.actions_write}/{r.actions_total}
              </Td>
              <Td className="whitespace-nowrap">
                <Money minor={r.budgets.money_spent_minor} /> /{' '}
                <Money minor={r.budgets.money_cap_minor} className="text-ink-2" />
              </Td>
              <Td>{r.pending_approvals || '—'}</Td>
              <Td>
                <Time iso={r.created_at} />
              </Td>
              <Td className="whitespace-nowrap">
                {duration(r.created_at, r.completed_at ?? r.frozen_at)}
              </Td>
            </tr>
          ))}
        </Table>
      )}
    </div>
  );
}
