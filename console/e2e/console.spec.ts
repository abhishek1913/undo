import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { Agent, login, shopAdmin } from './stack';

let agent: Agent;

test.beforeAll(async () => {
  agent = await Agent.create();
});

function card(page: Page, action: string) {
  return page.getByTestId('approval-card').filter({ hasText: action });
}

async function expectActionStatus(page: Page, action: string, status: string, timeout = 5000) {
  const n = action.replace('#A-', '');
  await expect
    .poll(async () => (await (await page.request.get(`/api/actions/${n}`)).json()).status, {
      timeout,
    })
    .toBe(status);
}

test('login: wrong password is refused, right password opens the console', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Email').fill('admin@undo.local');
  await page.getByLabel('Password').fill('wrong');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByLabel('Password').fill('undo-demo-admin');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  await page.getByRole('button', { name: 'User menu' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
});

test('approve: the action executes within 1 s of the click', async ({ page }) => {
  const run = await agent.newRun('e2e approve');
  const action = await agent.pendingRefund(run.id);
  await login(page, 'approver');
  await page.goto(`/approvals?run=${run.number}`);
  const c = card(page, action);
  await expect(c).toContainText('refund.large-needs-human');
  await expect(c).toContainText('No advisory note');
  const t0 = Date.now();
  await c.getByRole('button', { name: 'Approve' }).click();
  await expectActionStatus(page, action, 'SUCCEEDED', 1000);
  expect(Date.now() - t0).toBeLessThan(1000);
  await expect(c).toHaveCount(0);
});

test('reject needs a note and records it', async ({ page }) => {
  const run = await agent.newRun('e2e reject');
  const action = await agent.pendingRefund(run.id);
  await login(page, 'approver');
  await page.goto(`/approvals?run=${run.number}`);
  await card(page, action).getByRole('button', { name: 'Reject…' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('button', { name: 'Reject' })).toBeDisabled();
  await dialog.getByLabel('Note (required)').fill('Ask the customer for photos first');
  await dialog.getByRole('button', { name: 'Reject' }).click();
  await expectActionStatus(page, action, 'REJECTED');
  const n = action.replace('#A-', '');
  const detail = await (await page.request.get(`/api/actions/${n}`)).json();
  expect(detail.approval.decision_note).toBe('Ask the customer for photos first');
});

test('bulk approve sends each card hash', async ({ page }) => {
  const run = await agent.newRun('e2e bulk');
  const a1 = await agent.pendingRefund(run.id);
  const a2 = await agent.pendingRefund(run.id);
  await login(page, 'approver');
  await page.goto(`/approvals?run=${run.number}`);
  await expect(page.getByTestId('approval-card')).toHaveCount(2);
  await page.getByRole('button', { name: 'Select all' }).click();
  await page.getByRole('button', { name: 'Approve selected (2)' }).click();
  // exact: Radix also announces the toast for about a second as "Notification Approved 2 of 2"
  await expect(page.getByText('Approved 2 of 2', { exact: true })).toBeVisible();
  await expectActionStatus(page, a1, 'SUCCEEDED');
  await expectActionStatus(page, a2, 'SUCCEEDED');
});

test('keyboard: j/k move, a approves, r opens the reject dialog', async ({ page }) => {
  const run = await agent.newRun('e2e keys');
  const older = await agent.pendingRefund(run.id);
  const newer = await agent.pendingRefund(run.id);
  await login(page, 'approver');
  await page.goto(`/approvals?run=${run.number}`);
  const cards = page.getByTestId('approval-card');
  await expect(cards).toHaveCount(2);
  await cards.first().focus();
  await page.keyboard.press('j');
  await expect(card(page, older)).toBeFocused();
  await page.keyboard.press('k');
  await expect(card(page, newer)).toBeFocused();
  await page.keyboard.press('r');
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.keyboard.press('a');
  await expectActionStatus(page, newer, 'SUCCEEDED');
  await expect(card(page, older)).toBeVisible();
});

test('a mismatched hash shows a clear error and approves nothing', async ({ page }) => {
  const run = await agent.newRun('e2e hash');
  const action = await agent.pendingRefund(run.id);
  await login(page, 'approver');
  await page.route('**/api/approvals/*/approve', async (route) => {
    const body = route.request().postDataJSON() as { args_hash: string };
    await route.continue({ postData: JSON.stringify({ ...body, args_hash: '0'.repeat(64) }) });
  });
  await page.goto(`/approvals?run=${run.number}`);
  const c = card(page, action);
  await c.getByRole('button', { name: 'Approve' }).click();
  await expect(c.getByRole('alert')).toContainText(
    'The arguments changed after this card was shown',
  );
  await expectActionStatus(page, action, 'AWAITING_APPROVAL');
});

test('a REST-driven run shows live in the run page', async ({ page }) => {
  const run = await agent.newRun('e2e live');
  await login(page, 'viewer');
  await page.goto(`/runs/${run.number}`);
  await expect(page.getByRole('heading', { name: /Run #\d+ · e2e live/ })).toBeVisible();
  const r = await agent.propose(run.id, 'shop_get_ticket', { ticket_id: 'TKT-0002' });
  await expect(page.getByText(r.action as string).first()).toBeVisible();
  const order = await agent.bigOrder(run.id);
  const refund = await agent.propose(run.id, 'shop_issue_refund', {
    ticket_id: null,
    order_id: order,
    amount_minor: 2500,
    reason: 'other',
  });
  await expect(page.getByText(refund.action as string).first()).toBeVisible();
  await page.getByRole('tab', { name: 'ledger' }).click();
  await expect(page.getByText('Balanced ✓')).toBeVisible();
});

test('approvals work at 390 px wide', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const run = await agent.newRun('e2e mobile');
  const action = await agent.pendingRefund(run.id);
  await login(page, 'approver');
  await page.goto(`/approvals?run=${run.number}`);
  const button = card(page, action).getByRole('button', { name: 'Approve' });
  await expect(button).toBeVisible();
  const box = (await button.boundingBox())!;
  expect(box.width).toBeGreaterThan(300);
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
});

test('revert: preview, typed confirmation, execute, report', async ({ page }) => {
  const run = await agent.newRun('e2e revert');
  for (let i = 0; i < 2; i++) {
    const order = await agent.bigOrder(run.id);
    const r = await agent.propose(run.id, 'shop_issue_refund', {
      ticket_id: null,
      order_id: order,
      amount_minor: 2500 + i,
      reason: 'other',
    });
    expect(r.status).toBe('succeeded');
  }
  await shopAdmin('POST', `/admin/settlement/run?limit=1&undo_run_id=${run.id}`);
  await login(page, 'approver');
  await page.goto(`/runs/${run.number}/revert`);
  await page.getByRole('button', { name: 'Freeze and preview' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Freeze and preview' }).click();
  const table = page.getByRole('table', { name: 'Revert items' });
  await expect(table.getByText('COMPENSATE')).toBeVisible();
  await expect(table.getByText('IRREVERSIBLE')).toBeVisible();
  const go = page.getByRole('button', { name: 'Execute revert' });
  await expect(go).toBeDisabled();
  await page.getByLabel('Type the run number to confirm').fill(String(run.number));
  await go.click();
  await expect(page.getByTestId('revert-report')).toContainText('Reverted 1 of 2 actions.', {
    timeout: 15_000,
  });
  await expect(page.getByText('PARTIALLY_REVERTED').first()).toBeVisible();
});

test('reconciliation: run now heals a missed webhook and lists it', async ({ page }) => {
  await shopAdmin('POST', '/admin/faults', { webhook_drop_rate: 1 });
  try {
    const run = await agent.newRun('e2e recon');
    const order = await agent.bigOrder(run.id);
    const r = await agent.propose(run.id, 'shop_issue_refund', {
      ticket_id: null,
      order_id: order,
      amount_minor: 1700,
      reason: 'other',
    });
    expect(r.status).toBe('succeeded');
    await shopAdmin('POST', `/admin/settlement/run?limit=1&undo_run_id=${run.id}`);
    await login(page, 'admin');
    await page.goto('/reconciliation');
    await page.getByRole('button', { name: 'Run now' }).click();
    const healed = page.getByRole('table', { name: 'Auto-healed findings' });
    await expect(healed.getByRole('row').filter({ hasText: r.action })).toContainText(
      'STATUS_MISMATCH',
      { timeout: 10_000 },
    );
    await expect(healed.getByRole('row').filter({ hasText: r.action })).toContainText('settled');
  } finally {
    await shopAdmin('POST', '/admin/faults', { preset: 'calm' });
  }
});

test('demo: a scripted hosted run starts from the Demo page and completes', async ({ page }) => {
  await login(page, 'admin');
  await page.goto('/demo');
  await page.getByLabel('Tickets').fill('3');
  await page.getByRole('button', { name: 'Start support run' }).click();
  const link = page.getByRole('link', { name: /^Run #\d+$/ });
  await expect(link).toBeVisible();
  await link.click();
  await expect(page.getByText('COMPLETED').first()).toBeVisible({ timeout: 30_000 });
});

for (const theme of ['light', 'dark'] as const) {
  test(`axe: no serious violations (${theme})`, async ({ page }) => {
    const run = await agent.newRun('e2e axe');
    const action = await agent.pendingRefund(run.id);
    await page.addInitScript((t) => window.localStorage.setItem('undo.theme', t), theme);
    await login(page, 'admin');
    const pages = [
      '/',
      '/runs',
      `/runs/${run.number}`,
      `/runs/${run.number}/revert`,
      `/approvals?run=${run.number}`,
      `/actions/${action.replace('#A-', '')}`,
      '/settings/keys',
      '/reconciliation',
      '/demo',
    ];
    for (const p of pages) {
      await page.goto(p);
      await page.waitForLoadState('networkidle');
      await expect(page.getByRole('status')).toHaveCount(0);
      const result = await new AxeBuilder({ page }).analyze();
      const bad = result.violations.filter(
        (v) => v.impact === 'serious' || v.impact === 'critical',
      );
      expect(
        bad.map((v) => `${p}: ${v.id} ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`),
      ).toEqual([]);
    }
  });
}
