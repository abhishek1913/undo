import { request } from '@playwright/test';
import { CONSOLE, USERS } from './stack';

/**
 * Starts every run from a clean world: POST /api/demo/seed (seed 42) reseeds Shopfront and empties
 * Undo's run data (19.3). Refunds dedupe per order forever, so reused data would run out.
 */
export default async function globalSetup() {
  const admin = await request.newContext({ baseURL: CONSOLE });
  const login = await admin.post('/api/auth/login', { data: USERS.admin });
  if (!login.ok()) throw new Error(`admin login failed: ${login.status()} (is UNDO_DEMO=true?)`);
  const csrf = (await login.json()).csrf_token as string;
  const r = await admin.post('/api/demo/seed', {
    data: { seed: 42 },
    headers: { 'X-CSRF-Token': csrf },
  });
  if (!r.ok()) throw new Error(`demo seed failed: ${r.status()} ${await r.text()}`);
  await admin.dispose();
}
