import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { absolute, money } from '../lib/format';
import { useEventStream } from '../lib/sse';
import type { FullAction } from '../lib/types';
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
  Time,
} from '../components/ui';

const PROVIDER_PAGE = 'http://localhost:8090';

export function ActionDetailPage() {
  const { number = '' } = useParams();
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ['action', number],
    queryFn: () => api<FullAction>(`/api/actions/${number}`),
  });
  const runNumber = q.data?.run_number;
  useEventStream({ run: runNumber, afterSeq: null, enabled: runNumber !== undefined }, (e) => {
    if (e.data && (e.data as { action_number?: number }).action_number === Number(number)) {
      qc.invalidateQueries({ queryKey: ['action', number] });
    }
  });
  if (q.isLoading) return <Loading />;
  if (q.error) return <ErrorBox error={q.error} />;
  const a = q.data!;
  return (
    <div>
      <PageTitle
        sub={
          <span>
            <Link className="hover:underline" to={`/runs/${a.run_number}`}>
              Run #{a.run_number}
            </Link>{' '}
            · {a.kind} · {a.idempotency_class} · {a.policy ?? ''} · created{' '}
            <Time iso={a.created_at} />
          </span>
        }
      >
        <span className="mr-2 font-mono">{a.display}</span>
        <span className="mr-2">{a.tool}</span>
        <StatusPill status={a.status} />
        {a.status_reason && (
          <span className="ml-2 text-[14px] font-normal text-ink-2">{a.status_reason}</span>
        )}
      </PageTitle>

      {a.status === 'NEEDS_REVIEW' && <ResolvePanel a={a} />}

      <div className="grid gap-4 xl:grid-cols-2">
        <Card title="Arguments">
          <JsonBlock value={a.args} />
          <p className="mt-2 text-[12px] text-ink-2">
            args hash <Mono className="break-all">{a.args_hash}</Mono>
          </p>
        </Card>
        <Card title="Decision">
          <Field label="Decision">{a.decision ?? '—'}</Field>
          <Field label="Matched rules">
            {a.matched_rules.length === 0 ? (
              <span className="text-ink-2">none (default)</span>
            ) : (
              a.matched_rules.map((m) => (
                <div key={m.id}>
                  <Mono>{m.id}</Mono> → {m.decision} · {m.reason}
                </div>
              ))
            )}
          </Field>
          <Field label="Money out">
            {a.money_out_minor ? <Money minor={a.money_out_minor} /> : '—'}
          </Field>
          <Field label="Budget">{a.budget_state}</Field>
          {a.duplicate_of && (
            <Field label="Duplicate of">
              <Link className="font-mono hover:underline" to={`/actions/${a.duplicate_of_number}`}>
                {a.duplicate_of}
              </Link>
            </Field>
          )}
        </Card>
        <Card title="Facts">
          <JsonBlock value={a.facts} />
        </Card>
        <Card title="Before snapshot">
          <JsonBlock value={a.before_snapshot} />
        </Card>
      </div>

      <Section title="Attempts">
        {a.attempts.length === 0 ? (
          <Empty>No attempts — the action never reached the provider.</Empty>
        ) : (
          <Table
            label="Attempts"
            head={['No', 'Kind', 'Node', 'Fence', 'Started', 'Outcome', 'Latency', 'HTTP']}
          >
            {a.attempts.map((t) => (
              <tr key={`${t.kind}-${t.attempt_no}`}>
                <Td>{t.attempt_no}</Td>
                <Td>{t.kind}</Td>
                <Td className="font-mono text-[12px]">{t.node}</Td>
                <Td>{t.fence}</Td>
                <Td>
                  <span title={absolute(t.started_at)}>
                    <Time iso={t.started_at} />
                  </span>
                </Td>
                <Td>
                  {t.outcome ? (
                    <StatusPill status={t.outcome} />
                  ) : (
                    <span className="text-ink-2">in flight</span>
                  )}
                </Td>
                <Td className="font-mono text-[12px]">
                  {t.latency_ms !== undefined ? `${t.latency_ms} ms` : '—'}
                </Td>
                <Td className="font-mono text-[12px]">{t.http_status ?? '—'}</Td>
              </tr>
            ))}
          </Table>
        )}
      </Section>

      <Section title="Transitions">
        <Table label="Transitions" head={['At', 'From', 'To', 'Fence', 'Actor', 'Reason']}>
          {a.transitions.map((t, i) => (
            <tr key={i}>
              <Td className="font-mono text-[12px] whitespace-nowrap">{absolute(t.at)}</Td>
              <Td>{t.from ? <StatusPill status={t.from} /> : '—'}</Td>
              <Td>
                <StatusPill status={t.to} />
              </Td>
              <Td>{t.fence}</Td>
              <Td className="font-mono text-[12px]">{t.actor}</Td>
              <Td className="text-ink-2">{t.reason ?? ''}</Td>
            </tr>
          ))}
        </Table>
      </Section>

      <div className="mt-5 grid gap-4 xl:grid-cols-2">
        <Card title="Approval">
          {a.approval ? (
            <>
              <Field label="Status">
                <StatusPill status={a.approval.status} />
              </Field>
              <Field label="Requested">
                <Time iso={a.approval.requested_at} />
              </Field>
              <Field label="Decided">
                <Time iso={a.approval.decided_at} />
              </Field>
              <Field label="Approved hash">
                <Mono className="break-all">{a.approval.approved_args_hash ?? '—'}</Mono>
              </Field>
              <Field label="Note">{a.approval.decision_note ?? '—'}</Field>
              <Field label="Advisory (AI)">
                {a.approval.advisory_note?.text ?? (
                  <span className="text-ink-2">No advisory note</span>
                )}
              </Field>
            </>
          ) : (
            <p className="text-ink-2">No approval was needed.</p>
          )}
        </Card>
        <Card title="Provider">
          <Field label="Reference">{a.provider_ref ? <Mono>{a.provider_ref}</Mono> : '—'}</Field>
          <Field label="Status">{a.provider_status ?? '—'}</Field>
          <Field label="Idempotency key">
            {a.provider_idempotency_key ? (
              <Mono className="break-all">{a.provider_idempotency_key}</Mono>
            ) : (
              '—'
            )}
          </Field>
          <Field label="Lookups">{a.lookup_count}</Field>
          {a.unknown_since && (
            <Field label="Unknown since">
              <Time iso={a.unknown_since} />
            </Field>
          )}
          <a
            className="text-[13px] underline-offset-2 hover:underline"
            href={PROVIDER_PAGE}
            target="_blank"
            rel="noreferrer"
          >
            Open provider page ↗
          </a>
        </Card>
        <Card title="Postings">
          {a.postings.length === 0 ? (
            <p className="text-ink-2">No ledger postings.</p>
          ) : (
            <ul className="space-y-1">
              {a.postings.map((p, i) => (
                <li key={i} className="flex justify-between gap-3 font-mono text-[12px]">
                  <span>{p.account}</span>
                  <span>{money(p.amount_minor)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="Compensation">
          {a.compensates ? (
            <p>
              This action undoes{' '}
              <Link className="font-mono hover:underline" to={`/actions/${a.compensates_number}`}>
                {a.compensates}
              </Link>
            </p>
          ) : null}
          {a.compensated_by ? (
            <p>
              Undone by{' '}
              <Link
                className="font-mono hover:underline"
                to={`/actions/${a.compensated_by_number}`}
              >
                {a.compensated_by}
              </Link>
            </p>
          ) : null}
          {!a.compensates && !a.compensated_by && <p className="text-ink-2">No compensation.</p>}
        </Card>
      </div>
      <Section title="Result">
        <JsonBlock value={a.error ?? a.result} />
      </Section>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mt-5">
      <h2 className="mb-2 text-[13px] font-semibold uppercase tracking-wide text-ink-2">{title}</h2>
      {children}
    </section>
  );
}

/** Manual resolution for NEEDS_REVIEW (FR-REC-4): a person states what happened at the provider. */
function ResolvePanel({ a }: { a: FullAction }) {
  const { has } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();
  const [outcome, setOutcome] = useState<'happened' | 'did_not_happen'>('did_not_happen');
  const [ref, setRef] = useState(a.provider_ref ?? '');
  const [status, setStatus] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!has('APPROVER')) {
    return (
      <div className="mb-4 rounded-[8px] border border-unknown/50 bg-unknown/10 p-4 text-[14px]">
        This action needs a person to say whether it happened. An approver can resolve it here.
      </div>
    );
  }
  return (
    <form
      aria-label="Resolve"
      className="mb-5 rounded-[8px] border border-unknown/50 bg-unknown/10 p-4"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError(null);
        try {
          await api(`/api/actions/${a.number}/review`, {
            method: 'POST',
            json: {
              outcome,
              provider_ref: outcome === 'happened' ? ref || null : null,
              provider_status: outcome === 'happened' ? status || null : null,
              note,
            },
          });
          toast(`${a.display} resolved`);
          qc.invalidateQueries({ queryKey: ['action', String(a.number)] });
        } catch (err) {
          setError(err instanceof Error ? err.message : String(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      <h2 className="text-[15px] font-semibold">Resolve</h2>
      <p className="mt-1 text-[13px] text-ink-2">
        Undo could not prove whether this call took effect. Check the provider, then record what you
        found.
      </p>
      <fieldset className="mt-3 flex flex-wrap gap-4 text-[14px]">
        <legend className="sr-only">Outcome</legend>
        <label className="flex items-center gap-2">
          <input
            type="radio"
            name="outcome"
            checked={outcome === 'happened'}
            onChange={() => setOutcome('happened')}
          />
          It happened
        </label>
        <label className="flex items-center gap-2">
          <input
            type="radio"
            name="outcome"
            checked={outcome === 'did_not_happen'}
            onChange={() => setOutcome('did_not_happen')}
          />
          It did not happen
        </label>
      </fieldset>
      {outcome === 'happened' && (
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <label className="text-[13px]">
            Provider ref
            <input
              className="mt-1 block w-full rounded-[8px] border border-line bg-bg p-2 font-mono text-[13px]"
              value={ref}
              onChange={(e) => setRef(e.target.value)}
            />
          </label>
          <label className="text-[13px]">
            Provider status
            <input
              className="mt-1 block w-full rounded-[8px] border border-line bg-bg p-2 font-mono text-[13px]"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
              placeholder="e.g. pending"
            />
          </label>
        </div>
      )}
      <label className="mt-3 block text-[13px]">
        Note (required)
        <textarea
          className="mt-1 block w-full rounded-[8px] border border-line bg-bg p-2 text-[14px]"
          rows={2}
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
      </label>
      {error && (
        <p role="alert" className="mt-2 text-[13px] text-bad">
          {error}
        </p>
      )}
      <div className="mt-3 flex justify-end">
        <Button
          kind="primary"
          type="submit"
          disabled={busy || !note.trim() || (outcome === 'happened' && !ref.trim())}
        >
          Record outcome
        </Button>
      </div>
    </form>
  );
}
