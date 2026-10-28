// B2 step 1 (PRD 23): create N actions that wait for approval (shop_send_email, template "correction").
// bench/run.sh then bulk-approves them and times the drain with 1, 2 and 4 workers.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const API = __ENV.API || 'http://host.docker.internal:8080';
const KEY = __ENV.KEY;
const N = parseInt(__ENV.N || '5000');
export const options = {
  scenarios: { create: { executor: 'shared-iterations', vus: 32, iterations: N, maxDuration: '10m' } },
};
const H = () => ({ Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' });

// support-default caps a run at 2,000 actions (budgets.actions), so the actions are spread over runs.
export function setup() {
  const runs = [];
  for (let k = 0; k < Math.ceil(N / 2000); k++) {
    const r = http.post(`${API}/v1/runs`, JSON.stringify({ title: `B2 ${__ENV.WORKERS} workers #${k + 1}` }), { headers: H() });
    if (r.status !== 201) throw new Error(`create run: ${r.status} ${r.body}`);
    runs.push(r.json('id'));
  }
  return { runs };
}

export default function (d) {
  const i = exec.scenario.iterationInTest;
  const cust = `CUS-${String((i % 200) + 1).padStart(4, '0')}`;
  const res = http.post(
    `${API}/v1/runs/${d.runs[i % d.runs.length]}/actions`,
    JSON.stringify({
      tool: 'shop_send_email',
      args: { ticket_id: null, customer_id: cust, template: 'correction', order_id: null, amount_minor: null, note: `b2 ${i}` },
      wait_ms: 0,
    }),
    { headers: H() });
  check(res, { 'awaiting approval': (r) => r.status === 200 || r.status === 202 });
}
