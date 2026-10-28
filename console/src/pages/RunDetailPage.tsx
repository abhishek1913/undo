import * as Tabs from '@radix-ui/react-tabs';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { absolute, clock, money } from '../lib/format';
import { useEventStream } from '../lib/sse';
import type { ActionRow, EventRow, FullAction, Ledger, Loose, RunDetail } from '../lib/types';
import { ConfirmDialog } from '../components/Dialogs';
import { useToast } from '../components/Toasts';
import {
  Button,
  Card,
  Empty,
  ErrorBox,
  Field,
  JsonBlock,
  Loading,
  Money,
  Mono,
  PageTitle,
  StatusPill,
  Table,
  Td,
  Tile,
  Time,
} from '../components/ui';

async function fetchTimeline(number: string): Promise<EventRow[]> {
  const all: EventRow[] = [];
  let after = 0;
  for (let i = 0; i < 50; i++) {
    const page = await api<{ data: EventRow[]; next_after_seq: number }>(
      `/api/runs/${number}/timeline?after_seq=${after}`,
    );
    all.push(...page.data);
    if (page.data.length < 500) break;
    after = page.next_after_seq;
  }
  return all;
}

export function RunDetailPage() {
  const { number = '' } = useParams();
  const qc = useQueryClient();
  const toast = useToast();
  const { has } = useAuth();
  const run = useQuery({
    queryKey: ['run', number],
    queryFn: () => api<RunDetail>(`/api/runs/${number}`),
    refetchInterval: 10_000,
  });
  const [events, setEvents] = useState<EventRow[] | null>(null);
  const [startSeq, setStartSeq] = useState<number | undefined>(undefined);
  const lastRefresh = useRef(0);

  useEffect(() => {
    let alive = true;
    setEvents(null);
    fetchTimeline(number).then((evs) => {
      if (!alive) return;
      setEvents(evs);
      setStartSeq(evs.length ? evs[evs.length - 1]!.seq : 0);
    });
    return () => {
      alive = false;
    };
  }, [number]);

  useEventStream(
    { run: Number(number), afterSeq: startSeq, enabled: startSeq !== undefined },
    (e) => {
      setEvents((xs) => (xs && !xs.some((x) => x.seq === e.seq) ? [...xs, e] : xs));
      const now = Date.now();
      if (now - lastRefresh.current > 1000) {
        lastRefresh.current = now;
        qc.invalidateQueries({ queryKey: ['run', number] });
        qc.invalidateQueries({ queryKey: ['run-actions', number] });
        qc.invalidateQueries({ queryKey: ['run-ledger', number] });
      }
    },
  );

  if (run.isLoading) return <Loading />;
  if (run.error) return <ErrorBox error={run.error} />;
  const r = run.data!;
  const b = r.budgets;
  const mode = [r.model_mode, r.labels?.adversarial ? 'adversarial' : null]
    .filter(Boolean)
    .join(', ');
  return (
    <div>
      <PageTitle
        sub={
          <span>
            {r.policy ? `policy ${r.policy}` : ''} · agent {r.agent ?? '—'} · started{' '}
            <Time iso={r.created_at} />
            {r.failure_reason ? ` · ${r.failure_reason}` : ''}
          </span>
        }
        actions={
          <>
            {r.status === 'ACTIVE' && has('REVERTER') && (
              <ConfirmDialog
                trigger={<Button kind="danger">Freeze</Button>}
                title={`Freeze Run #${r.number}?`}
                description="Pending approvals and queued actions are cancelled and their budget released. Actions in flight are only looked up, never re-sent. The agent cannot propose anything new."
                confirmLabel="Freeze run"
                onConfirm={async () => {
                  await api(`/api/runs/${r.number}/freeze`, { method: 'POST', json: {} });
                  toast(`Run #${r.number} frozen`);
                  qc.invalidateQueries({ queryKey: ['run', number] });
                }}
              />
            )}
            {has('REVERTER') &&
              !['REVERTING', 'REVERTED', 'PARTIALLY_REVERTED'].includes(r.status) && (
                <Link to={`/runs/${r.number}/revert`}>
                  <Button kind="primary">Revert run</Button>
                </Link>
              )}
            {['REVERTING', 'REVERTED', 'PARTIALLY_REVERTED'].includes(r.status) && (
              <Link to={`/runs/${r.number}/revert`}>
                <Button>View revert</Button>
              </Link>
            )}
          </>
        }
      >
        <span className="mr-2">
          Run #{r.number} · {r.title}
        </span>
        {mode && !r.title?.toLowerCase().includes(mode.toLowerCase()) && (
          <span className="mr-2 text-[14px] font-normal text-ink-2">({mode})</span>
        )}
        <StatusPill status={r.status} />
      </PageTitle>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5">
        <Tile
          label="Money out"
          value={<Money minor={b.money_spent_minor} className="text-[22px]" />}
          hint={
            <>
              of {money(b.money_cap_minor)} · reserved {money(b.money_reserved_minor)}
            </>
          }
        />
        <Tile
          label="Actions"
          value={`${b.actions_used} / ${b.action_cap}`}
          hint="write actions used / cap"
        />
        <Tile
          label="Pending approvals"
          value={r.pending_approvals}
          tone={r.pending_approvals ? 'warn' : undefined}
        />
        <Tile label="Unknown" value={r.unknown_now} tone={r.unknown_now ? 'unknown' : undefined} />
        <Tile
          label="Net exposure"
          value={<Money minor={r.net_exposure_minor} className="text-[22px]" />}
          hint="expense postings of this run"
        />
      </div>

      <Tabs.Root defaultValue="timeline" className="mt-5">
        <Tabs.List
          aria-label="Run views"
          className="mb-3 flex gap-1 overflow-x-auto border-b border-line"
        >
          {['timeline', 'actions', 'tasks', 'ledger'].map((t) => (
            <Tabs.Trigger
              key={t}
              value={t}
              className="-mb-px border-b-2 border-transparent px-3 py-2 text-[13px] capitalize text-ink-2 data-[state=active]:border-accent data-[state=active]:font-semibold data-[state=active]:text-ink"
            >
              {t}
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <Tabs.Content value="timeline">
          {events === null ? (
            <Loading what="Loading the timeline" />
          ) : (
            <Timeline events={events} run={r} />
          )}
        </Tabs.Content>
        <Tabs.Content value="actions">
          <ActionsTab number={number} />
        </Tabs.Content>
        <Tabs.Content value="tasks">
          <TasksTab run={r} />
        </Tabs.Content>
        <Tabs.Content value="ledger">
          <LedgerTab number={number} />
        </Tabs.Content>
      </Tabs.Root>
    </div>
  );
}

type Item =
  | { kind: 'event'; e: EventRow }
  | {
      kind: 'action';
      first: EventRow;
      last: EventRow;
      number: number;
      statuses: string[];
      data: Loose;
    };

function groupTimeline(events: EventRow[], run: RunDetail) {
  const taskLabel = new Map<string, string>();
  for (const t of run.tasks) {
    taskLabel.set(t.id, t.input.ticket_id ?? t.input.order_id ?? `task ${t.seq}`);
  }
  const groups = new Map<string, Item[]>();
  const actions = new Map<number, Extract<Item, { kind: 'action' }>>();
  for (const e of events) {
    const d = e.data ?? {};
    const key = (d.task_id as string | undefined) ?? 'run';
    if (!groups.has(key)) groups.set(key, []);
    const n = d.action_number as number | undefined;
    if (n) {
      const existing = actions.get(n);
      if (existing) {
        existing.last = e;
        existing.data = { ...existing.data, ...d };
        const s = String(d.status ?? '');
        if (s && existing.statuses[existing.statuses.length - 1] !== s) existing.statuses.push(s);
        continue;
      }
      const item: Extract<Item, { kind: 'action' }> = {
        kind: 'action',
        first: e,
        last: e,
        number: n,
        statuses: [String(d.status ?? '')],
        data: { ...d },
      };
      actions.set(n, item);
      groups.get(key)!.push(item);
    } else {
      groups.get(key)!.push({ kind: 'event', e });
    }
  }
  return [...groups.entries()].map(([key, items]) => ({
    key,
    label: key === 'run' ? 'Run' : (taskLabel.get(key) ?? 'Task'),
    items,
  }));
}

function Timeline({ events, run }: { events: EventRow[]; run: RunDetail }) {
  const groups = useMemo(() => groupTimeline(events, run), [events, run]);
  if (events.length === 0) return <Empty>Nothing has happened in this run yet.</Empty>;
  return (
    <div className="space-y-4">
      {groups.map((g) => (
        <section
          key={g.key}
          className="rounded-[8px] border border-line bg-surface"
          aria-label={`Timeline ${g.label}`}
        >
          <h3 className="rounded-t-[8px] border-b border-line bg-surface-2/60 px-3 py-2 text-[13px] font-semibold">
            {g.label}
          </h3>
          <ul className="divide-y divide-line">
            {g.items.map((it) =>
              it.kind === 'action' ? (
                <ActionItem key={`a${it.number}`} it={it} />
              ) : (
                <EventItem key={`e${it.e.seq}`} e={it.e} />
              ),
            )}
          </ul>
        </section>
      ))}
    </div>
  );
}

function EventItem({ e }: { e: EventRow }) {
  const d = e.data ?? {};
  const words = e.type.replace(/^(run|task)\./, '').replace(/[._]/g, ' ');
  const text =
    e.type === 'llm.turn'
      ? `Model turn ${d.turn_no ?? ''} · ${d.stop_reason ?? ''}${d.tool_uses ? ` · ${d.tool_uses} tool ${d.tool_uses === 1 ? 'call' : 'calls'}` : ''}`
      : `${words.charAt(0).toUpperCase()}${words.slice(1)}${d.reason ? ` · ${d.reason}` : ''}${d.cancelled_actions ? ` · ${d.cancelled_actions} cancelled` : ''}`;
  return (
    <li className="fade-in flex gap-3 px-3 py-2 text-[13px]">
      <span className="w-16 shrink-0 tabular-nums text-[12px] text-ink-2" title={absolute(e.at)}>
        {clock(e.at)}
      </span>
      <span className="text-ink-2">{text}</span>
    </li>
  );
}

function ActionItem({ it }: { it: Extract<Item, { kind: 'action' }> }) {
  const [open, setOpen] = useState(false);
  const d = it.data;
  const detail = useQuery({
    queryKey: ['action', String(it.number), it.last.seq],
    queryFn: () => api<FullAction>(`/api/actions/${it.number}`),
    enabled: open,
  });
  const path = it.statuses.filter(Boolean);
  const final = path[path.length - 1] ?? 'PROPOSED';
  const rule =
    (d.reason as string | undefined) && !['approved', 'pending'].includes(String(d.reason))
      ? String(d.reason)
      : null;
  return (
    <li className="fade-in px-3 py-2 text-[13px] hover:bg-surface-2/50">
      <button
        type="button"
        className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 text-left"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <span
          className="w-16 shrink-0 tabular-nums text-[12px] text-ink-2"
          title={absolute(it.first.at)}
        >
          {clock(it.first.at)}
        </span>
        <span className="font-mono text-[12px]">{d.action}</span>
        <span>{d.summary ?? d.tool}</span>
        <span className="flex flex-wrap items-center gap-1">
          {path.length > 1 && path.slice(0, -1).includes('UNKNOWN') && (
            <>
              <StatusPill status="UNKNOWN" /> <span className="text-ink-2">→</span>
            </>
          )}
          <StatusPill status={final} />
          {Number(d.attempt) > 1 && (
            <span className="text-[12px] text-ink-2">(attempt {d.attempt})</span>
          )}
        </span>
        {rule && <span className="w-full pl-[76px] text-[12px] text-ink-2">rule {rule}</span>}
      </button>
      {open && (
        <div className="mt-2 rounded-[8px] border border-line bg-bg p-3">
          {detail.isLoading ? (
            <Loading />
          ) : detail.error ? (
            <ErrorBox error={detail.error} />
          ) : (
            <ActionSummary a={detail.data!} />
          )}
        </div>
      )}
    </li>
  );
}

function ActionSummary({ a }: { a: FullAction }) {
  return (
    <div className="grid gap-3 lg:grid-cols-2">
      <div>
        <Field label="Decision">
          {a.decision ?? '—'}{' '}
          {a.status_reason ? <span className="text-ink-2">({a.status_reason})</span> : null}
        </Field>
        <Field label="Rules">
          {a.matched_rules.length === 0
            ? '—'
            : a.matched_rules.map((m) => (
                <div key={m.id}>
                  <Mono>{m.id}</Mono> {m.reason}
                </div>
              ))}
        </Field>
        <Field label="Provider">
          {a.provider_ref ? <Mono>{a.provider_ref}</Mono> : '—'} {a.provider_status ?? ''}
        </Field>
        <Field label="Attempts">
          {a.attempts.map((t) => (
            <div key={`${t.kind}${t.attempt_no}`} className="text-[12px]">
              {t.kind} #{t.attempt_no} · fence {t.fence} · {t.node} · {t.outcome ?? 'in flight'}
            </div>
          ))}
        </Field>
        <Link
          className="text-[13px] underline-offset-2 hover:underline"
          to={`/actions/${a.number}`}
        >
          Open {a.display}
        </Link>
      </div>
      <div className="space-y-2">
        <div className="text-[12px] text-ink-2">Arguments</div>
        <JsonBlock value={a.args} />
        <div className="text-[12px] text-ink-2">Facts</div>
        <JsonBlock value={a.facts} />
      </div>
    </div>
  );
}

function ActionsTab({ number }: { number: string }) {
  const [status, setStatus] = useState('');
  const q = useQuery({
    queryKey: ['run-actions', number, status],
    queryFn: () => api<{ data: ActionRow[] }>(`/api/runs/${number}/actions?status=${status}`),
  });
  return (
    <div>
      <label className="mb-3 flex items-center gap-2 text-[13px]">
        Status
        <select
          className="rounded-[8px] border border-line bg-surface px-2 py-1"
          value={status}
          onChange={(e) => setStatus(e.target.value)}
        >
          {[
            '',
            'SUCCEEDED',
            'AWAITING_APPROVAL',
            'DENIED',
            'FAILED',
            'UNKNOWN',
            'NEEDS_REVIEW',
            'CANCELLED',
            'EXECUTING',
          ].map((s) => (
            <option key={s} value={s}>
              {s || 'any'}
            </option>
          ))}
        </select>
      </label>
      {q.isLoading ? (
        <Loading />
      ) : q.error ? (
        <ErrorBox error={q.error} />
      ) : q.data!.data.length === 0 ? (
        <Empty>No actions.</Empty>
      ) : (
        <Table
          label="Actions"
          head={[
            'Action',
            'Kind',
            'Tool',
            'Summary',
            'Status',
            'Reason',
            'Money',
            'Attempts',
            'Updated',
          ]}
        >
          {q.data!.data.map((a) => (
            <tr key={a.id} className="hover:bg-surface-2">
              <Td>
                <Link className="font-mono text-[12px] hover:underline" to={`/actions/${a.number}`}>
                  {a.display}
                </Link>
              </Td>
              <Td>{a.kind}</Td>
              <Td>{a.tool}</Td>
              <Td className="max-w-[260px] truncate">{a.summary}</Td>
              <Td>
                <StatusPill status={a.status} />
              </Td>
              <Td className="text-[12px] text-ink-2">{a.status_reason ?? ''}</Td>
              <Td>{a.money_out_minor ? <Money minor={a.money_out_minor} /> : '—'}</Td>
              <Td>{a.attempt_count}</Td>
              <Td>
                <Time iso={a.updated_at} />
              </Td>
            </tr>
          ))}
        </Table>
      )}
    </div>
  );
}

function TasksTab({ run }: { run: RunDetail }) {
  if (run.tasks.length === 0)
    return <Empty>This run has no hosted tasks (external agents send actions directly).</Empty>;
  return (
    <Table label="Tasks" head={['#', 'Input', 'Status', 'Turns', 'Failure']}>
      {run.tasks.map((t) => (
        <tr key={t.id}>
          <Td>{t.seq}</Td>
          <Td className="font-mono text-[12px]">
            {t.input.ticket_id ?? t.input.order_id ?? JSON.stringify(t.input)}
          </Td>
          <Td>
            <StatusPill status={t.status} />
          </Td>
          <Td>{t.turns}</Td>
          <Td className="text-ink-2">{t.failure_reason ?? ''}</Td>
        </tr>
      ))}
    </Table>
  );
}

function LedgerTab({ number }: { number: string }) {
  const q = useQuery({
    queryKey: ['run-ledger', number],
    queryFn: () => api<Ledger>(`/api/runs/${number}/ledger`),
  });
  if (q.isLoading) return <Loading />;
  if (q.error) return <ErrorBox error={q.error} />;
  const l = q.data!;
  return (
    <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
      <div>
        {l.entries.length === 0 ? (
          <Empty>No journal entries yet.</Empty>
        ) : (
          <Table label="Journal entries" head={['When', 'Entry', 'Action', 'Source', 'Postings']}>
            {l.entries.map((e) => (
              <tr key={e.id}>
                <Td>
                  <Time iso={e.created_at} />
                </Td>
                <Td className="font-mono text-[12px]">{e.kind}</Td>
                <Td>
                  {e.action_number ? (
                    <Link
                      className="font-mono text-[12px] hover:underline"
                      to={`/actions/${e.action_number}`}
                    >
                      {e.action}
                    </Link>
                  ) : null}
                </Td>
                <Td>{e.source}</Td>
                <Td>
                  {e.postings.map((p, i) => (
                    <div key={i} className="flex justify-between gap-4 font-mono text-[12px]">
                      <span>{p.account}</span>
                      <span className={p.amount_minor < 0 ? 'text-ink-2' : ''}>
                        {money(p.amount_minor)}
                      </span>
                    </div>
                  ))}
                </Td>
              </tr>
            ))}
          </Table>
        )}
      </div>
      <Card title="Net by account">
        <ul className="space-y-1">
          {Object.entries(l.net_by_account).map(([acc, v]) => (
            <li key={acc} className="flex justify-between gap-3 font-mono text-[12px]">
              <span className="truncate">{acc}</span>
              <span>{money(v)}</span>
            </li>
          ))}
        </ul>
        <p className={`mt-3 text-[13px] font-semibold ${l.balanced ? 'text-ok' : 'text-bad'}`}>
          {l.balanced ? 'Balanced ✓' : 'NOT balanced'}
        </p>
        <p className="mt-1 text-[13px] text-ink-2">
          Net exposure: <Money minor={l.net_exposure_minor} />
        </p>
      </Card>
    </div>
  );
}
