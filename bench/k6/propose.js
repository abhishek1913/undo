// B1 inline overhead (PRD 23): REST propose of shop_set_order_note (NATURAL, fast) with Shopfront faults off
// and latency 0. Undo's own overhead is read from the Server-Timing header ("undo;dur=…, provider;dur=…").
// Three constant-arrival-rate stages, 30 s each at 50, 200 and 500 req/s, 10 s apart.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const API = __ENV.API || 'http://host.docker.internal:8080';
const KEY = __ENV.KEY;
const overhead = new Trend('undo_overhead_ms', true);
const provider = new Trend('provider_ms', true);
const notSucceeded = new Counter('not_succeeded');

const stage = (rate, start) => ({
  executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: '30s', startTime: start,
  preAllocatedVUs: 100, maxVUs: 800, exec: 'propose', env: { RATE: String(rate) },
});
export const options = {
  scenarios: { r50: stage(50, '0s'), r200: stage(200, '40s'), r500: stage(500, '80s') },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'undo_overhead_ms{scenario:r50}': ['p(99)<60000'],
    'undo_overhead_ms{scenario:r200}': ['p(99)<60000'],
    'undo_overhead_ms{scenario:r500}': ['p(99)<60000'],
    'http_req_duration{scenario:r50}': ['p(99)<60000'],
    'http_req_duration{scenario:r200}': ['p(99)<60000'],
    'http_req_duration{scenario:r500}': ['p(99)<60000'],
    'http_req_failed{scenario:r50}': ['rate<1'],
    'http_req_failed{scenario:r200}': ['rate<1'],
    'http_req_failed{scenario:r500}': ['rate<1'],
  },
};
const H = () => ({ Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' });

// support-default caps a run at 2,000 actions (budgets.actions): each stage gets enough runs.
export function setup() {
  const runs = {};
  for (const [s, rate] of [['r50', 50], ['r200', 200], ['r500', 500]]) {
    runs[s] = [];
    for (let k = 0; k < Math.ceil((rate * 30) / 1900); k++) {
      const r = http.post(`${API}/v1/runs`, JSON.stringify({ title: `B1 ${s} #${k + 1}` }), { headers: H() });
      if (r.status !== 201) throw new Error(`create run: ${r.status} ${r.body}`);
      runs[s].push(r.json('id'));
    }
  }
  return runs;
}

export function propose(runs) {
  const i = exec.scenario.iterationInTest;
  const order = `ORD-${String((i % 3000) + 1).padStart(4, '0')}`;
  const res = http.post(
    `${API}/v1/runs/${runs[exec.scenario.name][i % runs[exec.scenario.name].length]}/actions`,
    JSON.stringify({ tool: 'shop_set_order_note', args: { order_id: order, note: `b1 ${exec.scenario.name} ${i}` }, wait_ms: 2000 }),
    { headers: H() });
  const st = res.headers['Server-Timing'] || '';
  const u = /undo;dur=([\d.]+)/.exec(st), p = /provider;dur=([\d.]+)/.exec(st);
  if (u) overhead.add(parseFloat(u[1]));
  if (p) provider.add(parseFloat(p[1]));
  const ok = check(res, { '200': (r) => r.status === 200 });
  if (!ok || res.json('status') !== 'succeeded') notSucceeded.add(1);
}
