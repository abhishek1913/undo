import * as Menu from '@radix-ui/react-dropdown-menu';
import { useQuery } from '@tanstack/react-query';
import { Route, Routes } from 'react-router-dom';
import { Layout, type NavItem } from './components/Layout';
import { ToastProvider } from './components/Toasts';
import { Loading } from './components/ui';
import { api } from './lib/api';
import { AuthProvider, useAuth } from './lib/auth';
import type { Overview } from './lib/types';
import { ActionDetailPage } from './pages/ActionDetailPage';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { DemoPage } from './pages/DemoPage';
import { KeysPage } from './pages/KeysPage';
import { LoginPage } from './pages/LoginPage';
import { OverviewPage } from './pages/OverviewPage';
import { RunDetailPage } from './pages/RunDetailPage';
import { ReconPage } from './pages/ReconPage';
import { RevertPage } from './pages/RevertPage';
import { RunsPage } from './pages/RunsPage';

function Placeholder({ title }: { title: string }) {
  return (
    <section>
      <h1 className="mb-2 text-[22px] font-bold tracking-tight">{title}</h1>
      <p className="text-ink-2">This page is built in a later milestone.</p>
    </section>
  );
}

function UserMenu() {
  const { user, logout } = useAuth();
  if (!user) return null;
  return (
    <Menu.Root>
      <Menu.Trigger asChild>
        <button
          type="button"
          aria-label={`${user.email} · user menu`}
          className="flex h-8 items-center gap-2 rounded-[7px] border border-line pl-1 pr-2.5 text-[12px] hover:bg-surface-2"
        >
          <span
            aria-hidden
            className="grid h-6 w-6 place-items-center rounded-[5px] bg-surface-2 text-ink-2"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={1.8}
              strokeLinecap="round"
              className="h-3.5 w-3.5"
            >
              <circle cx="12" cy="8" r="4" />
              <path d="M4 21a8 8 0 0 1 16 0" />
            </svg>
          </span>
          <span className="hidden sm:inline">{user.email}</span>
        </button>
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content
          align="end"
          sideOffset={4}
          className="z-50 min-w-[200px] rounded-[8px] border border-line bg-surface p-1 text-[13px] shadow"
        >
          <Menu.Label className="px-2 py-1 text-[12px] text-ink-2">
            Roles: {user.roles.join(', ')}
          </Menu.Label>
          <Menu.Separator className="my-1 h-px bg-line" />
          <Menu.Item
            className="cursor-pointer rounded px-2 py-1.5 outline-none data-[highlighted]:bg-surface-2"
            onSelect={() => void logout()}
          >
            Sign out
          </Menu.Item>
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  );
}

function Shell() {
  const overview = useQuery({
    queryKey: ['overview'],
    queryFn: () => api<Overview>('/api/overview'),
    refetchInterval: 10_000,
  });
  const t = overview.data?.tiles;
  const nav: NavItem[] = [
    { to: '/', label: 'Overview', icon: 'overview', group: 'operate' },
    { to: '/runs', label: 'Runs', icon: 'runs', group: 'operate' },
    {
      to: '/approvals',
      label: 'Approvals',
      icon: 'approvals',
      group: 'operate',
      badge: t?.pending_approvals,
      badgeTone: 'warn',
    },
    {
      to: '/reconciliation',
      label: 'Reconciliation',
      icon: 'recon',
      group: 'operate',
      badge: t?.open_critical_findings,
      badgeTone: 'bad',
    },
    { to: '/policies', label: 'Policies', icon: 'policies', group: 'configure' },
    { to: '/settings/keys', label: 'Settings', icon: 'settings', group: 'configure' },
    ...(overview.data?.demo
      ? [{ to: '/demo', label: 'Demo', icon: 'demo', group: 'demo' } satisfies NavItem]
      : []),
  ];
  return (
    <Layout nav={nav} environment={overview.data?.environment} userMenu={<UserMenu />}>
      <Routes>
        <Route path="/" element={<OverviewPage />} />
        <Route path="/runs" element={<RunsPage />} />
        <Route path="/runs/:number" element={<RunDetailPage />} />
        <Route path="/runs/:number/revert" element={<RevertPage />} />
        <Route path="/approvals" element={<ApprovalsPage />} />
        <Route path="/actions/:number" element={<ActionDetailPage />} />
        <Route path="/reconciliation" element={<ReconPage />} />
        <Route path="/policies" element={<Placeholder title="Policies" />} />
        <Route path="/settings/keys" element={<KeysPage />} />
        <Route path="/demo" element={<DemoPage />} />
        <Route path="*" element={<Placeholder title="Not found" />} />
      </Routes>
    </Layout>
  );
}

function Gate() {
  const { user, loading } = useAuth();
  if (loading) return <Loading what="Checking your session" />;
  if (!user) return <LoginPage />;
  return <Shell />;
}

export function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <Gate />
      </ToastProvider>
    </AuthProvider>
  );
}
