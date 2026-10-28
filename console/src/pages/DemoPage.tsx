import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../lib/api';
import { useToast } from '../components/Toasts';
import { Button, Card, ErrorBox, Loading, PageTitle } from '../components/ui';

type Faults = Record<string, number | boolean>;

const RATES: { key: string; label: string }[] = [
  { key: 'drop_after_commit_rate', label: 'Drop response after commit' },
  { key: 'error_5xx_rate', label: '5xx before handling' },
  { key: 'rate_limit_rate', label: '429 rate limit' },
  { key: 'hang_after_commit_rate', label: 'Hang after commit' },
  { key: 'webhook_drop_rate', label: 'Drop webhooks' },
  { key: 'webhook_duplicate_rate', label: 'Duplicate webhooks' },
  { key: 'settlement_failure_rate', label: 'Settlement failures' },
];

function explain(e: unknown): string {
  if (e instanceof ApiError) return `${e.code}: ${e.message}`;
  return e instanceof Error ? e.message : String(e);
}

/** /demo (Section 20.4): the controls of the demo script, in order. */
export function DemoPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const [seed, setSeed] = useState('42');
  const [mode, setMode] = useState('scripted');
  const [tickets, setTickets] = useState('20');
  const [adversarial, setAdversarial] = useState(true);
  const [settleN, setSettleN] = useState('3');
  const [carrierBug, setCarrierBug] = useState(false);
  const [lastRun, setLastRun] = useState<{ number: number; title: string } | null>(null);
  const [sweepRun, setSweepRun] = useState<number | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const faults = useQuery({
    queryKey: ['demo-faults'],
    queryFn: () => api<Faults>('/api/demo/faults'),
  });

  const run = async (what: string, f: () => Promise<string | void>) => {
    setBusy(what);
    try {
      const msg = await f();
      if (msg) toast(msg);
    } catch (e) {
      toast(explain(e), 'bad');
    } finally {
      setBusy(null);
    }
  };

  const setFaults = (body: Record<string, unknown>) =>
    run('faults', async () => {
      await api('/api/demo/faults', { method: 'POST', json: body });
      await qc.invalidateQueries({ queryKey: ['demo-faults'] });
      return 'Faults updated.';
    });

  const startRun = (scenario: string) =>
    run(scenario, async () => {
      const r = await api<{ number: number; title: string }>('/api/hosted-runs', {
        method: 'POST',
        json:
          scenario === 'support-triage'
            ? { scenario, model_mode: mode, limit: Number(tickets), adversarial }
            : { scenario, model_mode: mode },
      });
      if (scenario === 'lost-package-sweep') setSweepRun(r.number);
      setLastRun(r);
      return `Started run #${r.number}: ${r.title}`;
    });

  return (
    <div>
      <PageTitle sub="Local demo controls. Everything here talks to the simulated store, Shopfront.">
        Demo
      </PageTitle>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="1. Seed the store">
          <div className="flex flex-wrap items-end gap-3">
            <label className="text-[13px]">
              Seed
              <input
                className="mt-1 block w-28 rounded-[8px] border border-line bg-bg px-2 py-1"
                value={seed}
                onChange={(e) => setSeed(e.target.value)}
                inputMode="numeric"
              />
            </label>
            <Button
              kind="primary"
              disabled={busy !== null}
              onClick={() =>
                run('seed', async () => {
                  await api('/api/demo/seed', { method: 'POST', json: { seed: Number(seed) } });
                  await qc.invalidateQueries();
                  return `Seeded Shopfront with seed ${seed}; Undo's run data was reset.`;
                })
              }
            >
              Seed store
            </Button>
          </div>
        </Card>

        <Card title="2. Start a support run">
          <div className="flex flex-wrap items-end gap-3 text-[13px]">
            <label>
              Model
              <select
                className="mt-1 block rounded-[8px] border border-line bg-bg px-2 py-1"
                value={mode}
                onChange={(e) => setMode(e.target.value)}
              >
                <option value="scripted">Scripted</option>
                <option value="claude">Claude (needs ANTHROPIC_API_KEY)</option>
              </select>
            </label>
            <label>
              Tickets
              <input
                className="mt-1 block w-20 rounded-[8px] border border-line bg-bg px-2 py-1"
                value={tickets}
                onChange={(e) => setTickets(e.target.value)}
                inputMode="numeric"
              />
            </label>
            <label className="flex items-center gap-2 pb-1.5">
              <input
                type="checkbox"
                checked={adversarial}
                onChange={(e) => setAdversarial(e.target.checked)}
              />
              Adversarial
            </label>
            <Button
              kind="primary"
              disabled={busy !== null}
              onClick={() => startRun('support-triage')}
            >
              Start support run
            </Button>
          </div>
          {lastRun && (
            <p className="mt-3 text-[13px]">
              Latest:{' '}
              <Link className="underline underline-offset-2" to={`/runs/${lastRun.number}`}>
                Run #{lastRun.number}
              </Link>{' '}
              · {lastRun.title}
            </p>
          )}
        </Card>

        <Card title="3. Faults at Shopfront" className="lg:col-span-2">
          <div className="mb-3 flex flex-wrap gap-2">
            <Button disabled={busy !== null} onClick={() => setFaults({ preset: 'calm' })}>
              Calm
            </Button>
            <Button disabled={busy !== null} onClick={() => setFaults({ preset: 'flaky' })}>
              Flaky network
            </Button>
            <Button
              kind="danger"
              disabled={busy !== null}
              onClick={() => setFaults({ preset: 'chaos' })}
            >
              Chaos
            </Button>
          </div>
          {faults.isLoading && <Loading what="Loading faults" />}
          {faults.error && <ErrorBox error={faults.error} />}
          {faults.data && (
            <div className="grid gap-x-6 gap-y-2 sm:grid-cols-2">
              {RATES.map((r) => {
                const v = Number(faults.data[r.key] ?? 0);
                return (
                  <label
                    key={r.key}
                    className="grid grid-cols-[1fr_auto] items-center gap-2 text-[13px]"
                  >
                    <span>
                      {r.label}{' '}
                      <span className="tabular-nums text-ink-2">{Math.round(v * 100)}%</span>
                    </span>
                    <input
                      type="range"
                      min={0}
                      max={100}
                      step={5}
                      defaultValue={Math.round(v * 100)}
                      aria-label={r.label}
                      onMouseUp={(e) => setFaults({ [r.key]: Number(e.currentTarget.value) / 100 })}
                      onKeyUp={(e) => setFaults({ [r.key]: Number(e.currentTarget.value) / 100 })}
                    />
                  </label>
                );
              })}
            </div>
          )}
        </Card>

        <Card title="4. Kill a worker">
          <p className="mb-3 text-[13px] text-ink-2">
            The worker halts like kill -9 and Docker restarts it. Hosted runs resume on the next
            worker that takes the lease; no action is created twice.
          </p>
          <Button
            kind="danger"
            disabled={busy !== null}
            onClick={() =>
              run('kill', async () => {
                const r = await api<{ halting: boolean }>('/api/demo/kill-worker', {
                  method: 'POST',
                  json: {},
                });
                return r.halting
                  ? 'Worker halted; it restarts in a few seconds.'
                  : 'No worker answered.';
              })
            }
          >
            Kill a worker
          </Button>
        </Card>

        <Card title="5. Carrier bug and lost-package sweep">
          <div className="flex flex-wrap items-center gap-3 text-[13px]">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={carrierBug}
                onChange={(e) => {
                  const on = e.target.checked;
                  setCarrierBug(on);
                  void run('carrier', async () => {
                    await api('/api/demo/carrier-bug', { method: 'POST', json: { enabled: on } });
                    return on ? 'Carrier bug on: some orders now show LOST.' : 'Carrier bug off.';
                  });
                }}
              />
              Carrier bug
            </label>
            <Button disabled={busy !== null} onClick={() => startRun('lost-package-sweep')}>
              Start lost-package sweep
            </Button>
          </div>
        </Card>

        <Card title="6. Settle refunds">
          <div className="flex flex-wrap items-end gap-3 text-[13px]">
            <label>
              How many
              <input
                className="mt-1 block w-20 rounded-[8px] border border-line bg-bg px-2 py-1"
                value={settleN}
                onChange={(e) => setSettleN(e.target.value)}
                inputMode="numeric"
              />
            </label>
            <Button
              disabled={busy !== null}
              onClick={() =>
                run('settle', async () => {
                  await api('/api/demo/settle', {
                    method: 'POST',
                    json: sweepRun
                      ? { limit: Number(settleN), run_number: sweepRun }
                      : { limit: Number(settleN) },
                  });
                  return sweepRun
                    ? `Settled up to ${settleN} refunds of Run #${sweepRun}.`
                    : `Settled up to ${settleN} refunds.`;
                })
              }
            >
              Settle {sweepRun ? `refunds of Run #${sweepRun}` : 'refunds'}
            </Button>
          </div>
        </Card>

        <Card title="7. Links" className="lg:col-span-2">
          <ul className="flex flex-wrap gap-4 text-[13px]">
            <li>
              <a
                className="underline underline-offset-2"
                href="http://localhost:8090"
                target="_blank"
                rel="noreferrer"
              >
                Shopfront provider page
              </a>
            </li>
            <li>
              <a
                className="underline underline-offset-2"
                href="http://localhost:3001"
                target="_blank"
                rel="noreferrer"
              >
                Grafana (obs profile)
              </a>
            </li>
            <li>
              <a
                className="underline underline-offset-2"
                href="http://localhost:16686"
                target="_blank"
                rel="noreferrer"
              >
                Jaeger (obs profile)
              </a>
            </li>
          </ul>
        </Card>
      </div>
    </div>
  );
}
