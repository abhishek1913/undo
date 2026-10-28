import * as Dialog from '@radix-ui/react-dialog';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { money, shortHash, untilText } from '../lib/format';
import { useEventStream } from '../lib/sse';
import type { ApprovalCard } from '../lib/types';
import { useToast } from '../components/Toasts';
import { Button, Empty, ErrorBox, Loading, Mono, PageTitle, StatusPill } from '../components/ui';

/** Turns an API failure into a sentence a reviewer can act on. */
function explain(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'APPROVAL_HASH_MISMATCH')
      return 'The arguments changed after this card was shown. Nothing was approved — reload and review again.';
    if (e.code === 'APPROVAL_NOT_PENDING') return 'This approval was already decided or cancelled.';
    if (e.code === 'APPROVAL_EXPIRED') return 'This approval expired. The action will not run.';
    return `${e.code}: ${e.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}

export function ApprovalsPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const { has } = useAuth();
  const canDecide = has('APPROVER');
  const [params, setParams] = useSearchParams();
  const run = params.get('run') ?? '';
  const tool = params.get('tool') ?? '';
  const key = ['approvals', run, tool];
  const q = useQuery({
    queryKey: key,
    queryFn: () =>
      api<{ data: ApprovalCard[] }>(
        `/api/approvals?status=PENDING${run ? `&run=${run}` : ''}${tool ? `&tool=${tool}` : ''}`,
      ),
    refetchInterval: 15_000,
  });
  const cards = useMemo(
    () => [...(q.data?.data ?? [])].sort((a, b) => b.requested_at.localeCompare(a.requested_at)),
    [q.data],
  );
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [focus, setFocus] = useState(0);
  const [rejecting, setRejecting] = useState<ApprovalCard[] | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const refs = useRef<(HTMLElement | null)[]>([]);
  const last = useRef(0);

  useEventStream({ afterSeq: null, enabled: true }, (e) => {
    if (!e.type.startsWith('approval.')) return;
    const now = Date.now();
    if (now - last.current > 500) {
      last.current = now;
      qc.invalidateQueries({ queryKey: ['approvals'] });
    }
  });

  useEffect(() => {
    setSelected((s) => new Set([...s].filter((id) => cards.some((c) => c.id === id))));
    setFocus((f) => Math.min(f, Math.max(0, cards.length - 1)));
  }, [cards]);

  const approve = useCallback(
    async (c: ApprovalCard) => {
      try {
        await api(`/api/approvals/${c.id}/approve`, {
          method: 'POST',
          json: { args_hash: c.args_hash },
        });
        setErrors((e) => ({ ...e, [c.id]: '' }));
        toast(`Approved ${c.action} — executing now`);
        qc.invalidateQueries({ queryKey: ['approvals'] });
      } catch (e) {
        setErrors((x) => ({ ...x, [c.id]: explain(e) }));
      }
    },
    [qc, toast],
  );

  async function reject(list: ApprovalCard[], note: string) {
    if (list.length === 1) {
      await api(`/api/approvals/${list[0]!.id}/reject`, { method: 'POST', json: { note } });
      toast(`Rejected ${list[0]!.action}`);
    } else {
      await bulk('reject', list, note);
    }
    qc.invalidateQueries({ queryKey: ['approvals'] });
  }

  async function bulk(decision: 'approve' | 'reject', list: ApprovalCard[], note?: string) {
    const r = await api<{
      results: { approval_id: string; ok: boolean; code?: string; message?: string }[];
    }>('/api/approvals/bulk', {
      method: 'POST',
      json: {
        decision,
        note,
        items: list.map((c) => ({ approval_id: c.id, args_hash: c.args_hash })),
      },
    });
    const failed = r.results.filter((x) => !x.ok);
    const errs: Record<string, string> = {};
    for (const f of failed)
      errs[f.approval_id] = explain(new ApiError(409, f.code ?? 'ERROR', f.message ?? ''));
    setErrors((e) => ({ ...e, ...errs }));
    toast(
      `${decision === 'approve' ? 'Approved' : 'Rejected'} ${r.results.length - failed.length} of ${r.results.length}${failed.length ? ` · ${failed.length} failed` : ''}`,
    );
    setSelected(new Set());
    qc.invalidateQueries({ queryKey: ['approvals'] });
  }

  useEffect(() => {
    function onKey(ev: KeyboardEvent) {
      const t = ev.target as HTMLElement | null;
      if (rejecting || ev.metaKey || ev.ctrlKey || ev.altKey) return;
      if (
        t &&
        (t.tagName === 'INPUT' ||
          t.tagName === 'TEXTAREA' ||
          t.tagName === 'SELECT' ||
          t.isContentEditable)
      )
        return;
      if (cards.length === 0) return;
      const c = cards[focus];
      if (ev.key === 'j') {
        const n = Math.min(cards.length - 1, focus + 1);
        setFocus(n);
        refs.current[n]?.focus();
      } else if (ev.key === 'k') {
        const n = Math.max(0, focus - 1);
        setFocus(n);
        refs.current[n]?.focus();
      } else if (ev.key === 'a' && c && canDecide) {
        ev.preventDefault();
        void approve(c);
      } else if (ev.key === 'r' && c && canDecide) {
        ev.preventDefault();
        setRejecting([c]);
      } else if (ev.key === 'x' && c) {
        toggle(c.id);
      } else {
        return;
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [cards, focus, rejecting, canDecide, approve]);

  function toggle(id: string) {
    setSelected((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  function setFilter(k: string, v: string) {
    const p = new URLSearchParams(params);
    if (v) p.set(k, v);
    else p.delete(k);
    setParams(p);
  }

  const chosen = cards.filter((c) => selected.has(c.id));
  return (
    <div>
      <PageTitle
        sub={
          <span>
            Keyboard: <kbd>j</kbd>/<kbd>k</kbd> move · <kbd>a</kbd> approve · <kbd>r</kbd> reject ·{' '}
            <kbd>x</kbd> select
          </span>
        }
      >
        Approvals
      </PageTitle>
      <div className="mb-3 flex flex-wrap items-end gap-3 text-[13px]">
        <label className="flex flex-col gap-1">
          Run
          <input
            className="w-28 rounded-[8px] border border-line bg-surface px-2 py-1 font-mono"
            inputMode="numeric"
            value={run}
            onChange={(e) => setFilter('run', e.target.value.replace(/\D/g, ''))}
            placeholder="any"
          />
        </label>
        <label className="flex flex-col gap-1">
          Tool
          <select
            className="rounded-[8px] border border-line bg-surface px-2 py-1"
            value={tool}
            onChange={(e) => setFilter('tool', e.target.value)}
          >
            {[
              '',
              'shop_issue_refund',
              'shop_issue_store_credit',
              'shop_send_email',
              'shop_close_ticket',
              'shop_set_order_note',
            ].map((t) => (
              <option key={t} value={t}>
                {t || 'any'}
              </option>
            ))}
          </select>
        </label>
        {canDecide && cards.length > 0 && (
          <div className="ml-auto flex w-full flex-wrap gap-2 sm:w-auto">
            <Button
              onClick={() =>
                setSelected(
                  selected.size === cards.length ? new Set() : new Set(cards.map((c) => c.id)),
                )
              }
              className="flex-1 sm:flex-none"
            >
              {selected.size === cards.length ? 'Clear selection' : 'Select all'}
            </Button>
            <Button
              kind="primary"
              disabled={chosen.length === 0}
              onClick={() => void bulk('approve', chosen)}
              className="flex-1 sm:flex-none"
            >
              Approve selected ({chosen.length})
            </Button>
            <Button
              kind="danger"
              disabled={chosen.length === 0}
              onClick={() => setRejecting(chosen)}
              className="flex-1 sm:flex-none"
            >
              Reject selected ({chosen.length})
            </Button>
          </div>
        )}
      </div>

      {q.isLoading ? (
        <Loading />
      ) : q.error ? (
        <ErrorBox error={q.error} />
      ) : cards.length === 0 ? (
        <Empty>No pending approvals — agents are within policy.</Empty>
      ) : (
        <ul className="space-y-3" aria-label="Pending approvals">
          {cards.map((c, i) => (
            <li key={c.id}>
              <article
                ref={(el) => {
                  refs.current[i] = el;
                }}
                tabIndex={0}
                onFocus={() => setFocus(i)}
                aria-label={`Approval ${c.action} ${c.tool}`}
                data-testid="approval-card"
                className={`rounded-[8px] border bg-surface p-5 ${i === focus ? 'border-accent ring-1 ring-accent/30' : 'border-line'}`}
              >
                <header className="flex flex-wrap items-start gap-x-3 gap-y-1">
                  {canDecide && (
                    <input
                      type="checkbox"
                      className="mt-1"
                      aria-label={`Select ${c.action}`}
                      checked={selected.has(c.id)}
                      onChange={() => toggle(c.id)}
                    />
                  )}
                  <div className="min-w-0 flex-1">
                    <h2 className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                      <span className="text-[20px] font-bold leading-none tracking-tight tabular-nums">
                        {c.amount_minor ? money(c.amount_minor) : 'No amount'}
                      </span>
                      <Mono className="text-[13px] text-ink-2">{c.tool}</Mono>
                    </h2>
                    <p className="mt-2 text-[13px] text-ink-2">
                      {c.ticket_id ? (
                        <>
                          Ticket {c.ticket_id} “{c.ticket_subject}” ·{' '}
                        </>
                      ) : null}
                      <Link
                        className="text-ink underline decoration-line underline-offset-2 hover:decoration-ink"
                        to={`/runs/${c.run_number}`}
                      >
                        Run #{c.run_number}
                      </Link>{' '}
                      ·{' '}
                      <Link
                        className="font-mono text-[12px] text-ink underline decoration-line underline-offset-2 hover:decoration-ink"
                        to={`/actions/${c.action_number}`}
                      >
                        {c.action}
                      </Link>{' '}
                      · {untilText(c.expires_at)}
                    </p>
                  </div>
                  <StatusPill status={c.status === 'PENDING' ? 'AWAITING_APPROVAL' : c.status} />
                </header>
                <dl className="mt-4 grid grid-cols-1 border-t border-line pt-4 gap-x-4 gap-y-2 text-[14px] sm:grid-cols-[150px_1fr]">
                  <dt className="text-[13px] text-ink-2">Why it needs you</dt>
                  <dd>
                    {c.reasons.map((r) => (
                      <div key={r.id}>
                        <Mono>{r.id}</Mono>: {r.reason}
                      </div>
                    ))}
                  </dd>
                  <dt className="text-[13px] text-ink-2">Facts</dt>
                  <dd>
                    <Facts c={c} />
                  </dd>
                  <dt className="text-[13px] text-ink-2">Advisory (AI)</dt>
                  <dd className={c.advisory_note?.text ? '' : 'text-ink-2'}>
                    {c.advisory_note?.text ?? 'No advisory note'}
                  </dd>
                  <dt className="text-[13px] text-ink-2">Args hash</dt>
                  <dd>
                    <Mono className="break-all">
                      <span title={c.args_hash}>{shortHash(c.args_hash)}</span>
                    </Mono>
                  </dd>
                </dl>
                {errors[c.id] && (
                  <p
                    role="alert"
                    className="mt-3 rounded-[8px] border border-bad/40 bg-bad/10 p-2 text-[13px] text-bad"
                  >
                    {errors[c.id]}
                  </p>
                )}
                {canDecide && (
                  <div className="mt-4 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                    <Button
                      kind="danger"
                      onClick={() => setRejecting([c])}
                      className="w-full sm:w-auto"
                    >
                      Reject…
                    </Button>
                    <Button
                      kind="primary"
                      onClick={() => void approve(c)}
                      className="w-full sm:w-auto"
                    >
                      Approve
                    </Button>
                  </div>
                )}
              </article>
            </li>
          ))}
        </ul>
      )}
      <RejectDialog
        cards={rejecting}
        onClose={() => {
          setRejecting(null);
          refs.current[focus]?.focus();
        }}
        onReject={reject}
      />
    </div>
  );
}

function Facts({ c }: { c: ApprovalCard }) {
  const f = c.facts ?? {};
  const order = f.order && typeof f.order === 'object' ? f.order : null;
  const customer = f.customer && typeof f.customer === 'object' ? f.customer : null;
  const ticket = f.ticket && typeof f.ticket === 'object' ? f.ticket : null;
  const lines: string[] = [];
  if (order) {
    lines.push(`Order ${order.id} · ${order.item_name} · ${money(order.total_minor)}`);
    const owner = ticket
      ? ticket.customer_id === order.customer_id
        ? 'matches ticket'
        : `ticket is from ${ticket.customer_id}`
      : 'no ticket';
    lines.push(`Owner ${order.customer_id} (${owner}) · refunded ${money(order.refunded_minor)}`);
  }
  if (customer)
    lines.push(
      `Customer ${customer.id} · ${customer.name} · store credit ${money(customer.store_credit_balance_minor)}`,
    );
  if (f.order_age_days !== undefined) lines.push(`Order age ${f.order_age_days} days`);
  if (lines.length === 0) return <span className="text-ink-2">No facts gathered</span>;
  return (
    <>
      {lines.map((l) => (
        <div key={l}>{l}</div>
      ))}
    </>
  );
}

function RejectDialog({
  cards,
  onClose,
  onReject,
}: {
  cards: ApprovalCard[] | null;
  onClose: () => void;
  onReject: (c: ApprovalCard[], note: string) => Promise<void>;
}) {
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setNote('');
    setError(null);
  }, [cards]);
  return (
    <Dialog.Root open={cards !== null} onOpenChange={(o) => !o && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/40" />
        <Dialog.Content className="fixed left-1/2 top-1/2 w-[min(92vw,460px)] -translate-x-1/2 -translate-y-1/2 rounded-[8px] border border-line bg-surface p-5 shadow-lg focus:outline-none">
          <Dialog.Title className="text-[16px] font-semibold">
            Reject{' '}
            {cards && cards.length === 1 ? cards[0]!.action : `${cards?.length ?? 0} actions`}
          </Dialog.Title>
          <Dialog.Description className="mt-2 text-[14px] text-ink-2">
            The agent sees the note as the reason. A note is required.
          </Dialog.Description>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              if (!cards || !note.trim()) return;
              setBusy(true);
              try {
                await onReject(cards, note.trim());
                onClose();
              } catch (err) {
                setError(explain(err));
              } finally {
                setBusy(false);
              }
            }}
          >
            <label className="mt-4 block text-[13px]">
              Note (required)
              <textarea
                className="mt-1 block w-full rounded-[8px] border border-line bg-bg p-2 text-[14px]"
                rows={3}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                autoFocus
              />
            </label>
            {error && (
              <p role="alert" className="mt-3 text-[13px] text-bad">
                {error}
              </p>
            )}
            <div className="mt-5 flex justify-end gap-2">
              <Dialog.Close asChild>
                <Button>Cancel</Button>
              </Dialog.Close>
              <Button kind="danger" type="submit" disabled={busy || !note.trim()}>
                Reject
              </Button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
