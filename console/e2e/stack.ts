import { readFileSync } from 'node:fs';
import { request, type APIRequestContext, type Page } from '@playwright/test';

/** Test helpers that talk to the running stack: console API as admin, agent API with a fresh key. */
export const CONSOLE = process.env.CONSOLE_URL ?? 'http://localhost:3000';
export const UNDO = process.env.UNDO_URL ?? 'http://localhost:8080';

export const USERS = {
  admin: { email: 'admin@undo.local', password: 'undo-demo-admin' },
  approver: { email: 'approver@undo.local', password: 'undo-demo-approver' },
  viewer: { email: 'viewer@undo.local', password: 'undo-demo-viewer' },
};

export async function login(page: Page, who: keyof typeof USERS) {
  await page.goto('/');
  await page.getByLabel('Email').fill(USERS[who].email);
  await page.getByLabel('Password').fill(USERS[who].password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('heading', { name: 'Overview' }).waitFor();
}

export class Agent {
  private constructor(
    private readonly http: APIRequestContext,
    private readonly key: string,
  ) {}

  /** Creates an agent and key through the console API as admin. */
  static async create(): Promise<Agent> {
    const admin = await request.newContext({ baseURL: CONSOLE });
    const r = await admin.post('/api/auth/login', { data: USERS.admin });
    if (!r.ok()) throw new Error(`admin login failed: ${r.status()}`);
    const csrf = (await r.json()).csrf_token as string;
    const headers = { 'X-CSRF-Token': csrf };
    const agent = await admin.post('/api/agents', { data: { name: `e2e-${Date.now()}` }, headers });
    const id = (await agent.json()).id as string;
    const key = await admin.post(`/api/agents/${id}/keys`, {
      data: { allowed_tools: ['*'], default_policy: 'support-default' },
      headers,
    });
    const secret = (await key.json()).key as string;
    await admin.dispose();
    return new Agent(await request.newContext({ baseURL: UNDO }), secret);
  }

  private auth() {
    return { Authorization: `Bearer ${this.key}` };
  }

  async newRun(title = 'e2e'): Promise<{ id: string; number: number }> {
    const r = await this.http.post('/v1/runs', { data: { title }, headers: this.auth() });
    if (r.status() !== 201) throw new Error(`create run: ${r.status()} ${await r.text()}`);
    const b = await r.json();
    return { id: b.id, number: b.number };
  }

  async propose(run: string, tool: string, args: unknown, waitMs = 3000) {
    for (;;) {
      const r = await this.http.post(`/v1/runs/${run}/actions`, {
        data: { tool, args, wait_ms: waitMs },
        headers: this.auth(),
      });
      if (r.status() === 429) {
        // The per-key rate limit (FR-SEC) is working; wait as told and try again.
        await new Promise((ok) => setTimeout(ok, 1000 * Number(r.headers()['retry-after'] ?? 1)));
        continue;
      }
      if (!r.ok()) throw new Error(`propose ${tool}: ${r.status()} ${await r.text()}`);
      return r.json();
    }
  }

  private readonly tried = new Set<string>();

  /**
   * An order nobody refunded yet, with room above the ₹10,000 approval threshold. Refunds dedupe per
   * order (FR-PROP-3), so each test needs its own order; `make down && make up` reseeds the store.
   */
  async bigOrder(run: string): Promise<string> {
    for (let i = 0; i < 400; i++) {
      const id = `ORD-${String(1 + Math.floor(Math.random() * 400)).padStart(4, '0')}`;
      if (this.tried.has(id)) continue;
      this.tried.add(id);
      const r = await this.propose(run, 'shop_get_order', { order_id: id });
      if (
        r.status === 'succeeded' &&
        r.result.refunded_minor === 0 &&
        r.result.total_minor > 1_100_000
      )
        return id;
    }
    throw new Error('no unrefunded big order left; reseed the store');
  }

  /** Proposes a refund that policy sends to a human; returns the action display id (#A-…). */
  async pendingRefund(run: string): Promise<string> {
    for (let i = 0; i < 20; i++) {
      const order = await this.bigOrder(run);
      const amount = 1_000_100 + Math.floor(Math.random() * 50_000);
      const r = await this.propose(run, 'shop_issue_refund', {
        ticket_id: null,
        order_id: order,
        amount_minor: amount,
        reason: 'other',
      });
      if (r.status === 'pending_approval') return r.action as string;
      if (r.status !== 'duplicate')
        throw new Error(`expected pending_approval, got ${JSON.stringify(r)}`);
    }
    throw new Error('every candidate order already has an active refund');
  }
}

/** Shopfront admin calls (settlement, note edits), with the token from env or ../.env. */
export async function shopAdmin(method: 'POST' | 'PUT', path: string, data?: unknown) {
  const token =
    process.env.SHOP_ADMIN_TOKEN ??
    readFileSync(new URL('../../.env', import.meta.url), 'utf8')
      .split('\n')
      .find((l) => l.startsWith('SHOP_ADMIN_TOKEN='))!
      .slice('SHOP_ADMIN_TOKEN='.length)
      .trim();
  const http = await request.newContext({
    baseURL: process.env.SHOP_URL ?? 'http://localhost:8090',
    extraHTTPHeaders: { 'X-Admin-Token': token },
  });
  const r = method === 'POST' ? await http.post(path, { data }) : await http.put(path, { data });
  if (!r.ok()) throw new Error(`shop admin ${path}: ${r.status()}`);
  const body = await r.json();
  await http.dispose();
  return body;
}
