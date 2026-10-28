import { useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { currentTheme, setTheme, type Theme } from '../lib/theme';

export type IconName =
  | 'overview'
  | 'runs'
  | 'approvals'
  | 'recon'
  | 'policies'
  | 'settings'
  | 'demo'
  | 'sun'
  | 'moon'
  | 'collapse'
  | 'expand';

/** Line icons, 16 px, drawn on a 24 px grid; always decorative (the label carries the meaning). */
const PATHS: Record<IconName, string> = {
  overview: 'M4 4h7v7H4zM13 4h7v4h-7zM13 10h7v10h-7zM4 13h7v7H4z',
  runs: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01',
  approvals: 'M9 12l2 2 4-4M12 3l7 3v5c0 4.5-3 8.5-7 10-4-1.5-7-5.5-7-10V6z',
  recon: 'M4 9h13l-3-3M20 15H7l3 3',
  policies: 'M6 4h9l3 3v13H6zM9 10h6M9 14h6M9 18h3',
  settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4',
  demo: 'M9 3h6M10 3v6l-5 9a2 2 0 0 0 1.7 3h10.6a2 2 0 0 0 1.7-3l-5-9V3M7.5 14h9',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z',
  collapse: 'M15 6l-6 6 6 6',
  expand: 'M9 6l6 6-6 6',
};

export function Icon({ name, className = '' }: { name: IconName; className?: string }) {
  return (
    <svg
      aria-hidden
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={`h-4 w-4 shrink-0 ${className}`}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}

/** The mark: an undo arrow in the accent. */
function Mark() {
  return (
    <span
      aria-hidden
      className="grid h-7 w-7 shrink-0 place-items-center rounded-[7px] bg-accent-fill text-on-accent"
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={2.2}
        className="h-4 w-4"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M9 14L4 9l5-5" />
        <path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" />
      </svg>
    </span>
  );
}

/** `group` starts a new block in the sidebar: what you operate, what you configure, the demo. */
export type NavItem = {
  to: string;
  label: string;
  icon: IconName;
  group: 'operate' | 'configure' | 'demo';
  badge?: number;
  badgeTone?: 'warn' | 'bad';
};

export function Layout({
  nav,
  userMenu,
  environment = 'local demo',
  children,
}: {
  nav: NavItem[];
  userMenu?: ReactNode;
  environment?: string;
  children: ReactNode;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const [theme, setThemeState] = useState<Theme>(currentTheme());

  function toggleTheme() {
    const next = theme === 'dark' ? 'light' : 'dark';
    setTheme(next);
    setThemeState(next);
  }

  return (
    <div className="flex min-h-screen">
      <aside
        className={`${collapsed ? 'w-[60px]' : 'w-56'} sticky top-0 h-screen shrink-0 border-r border-line bg-surface hidden md:flex flex-col`}
      >
        <div
          className={`flex h-14 items-center gap-2.5 px-4 ${collapsed ? 'justify-center px-0' : ''}`}
        >
          <Mark />
          {!collapsed && <span className="text-[15px] font-bold tracking-tight">Undo</span>}
        </div>
        <nav aria-label="Main" className="flex flex-1 flex-col gap-0.5 px-2.5 pt-2">
          {nav.map((item, i) => (
            <div key={item.to}>
              {i > 0 && nav[i - 1]?.group !== item.group && (
                <div aria-hidden className="mx-2 my-2.5 h-px bg-line" />
              )}
              <NavLink
                to={item.to}
                end={item.to === '/'}
                title={collapsed ? item.label : undefined}
                className={({ isActive }) =>
                  `relative flex h-9 items-center gap-2.5 rounded-[7px] px-2.5 text-[13px] ${
                    collapsed ? 'justify-center' : ''
                  } ${
                    isActive
                      ? 'bg-surface-2 font-semibold text-ink before:absolute before:-left-2.5 before:top-2 before:bottom-2 before:w-[3px] before:rounded-r before:bg-accent'
                      : 'text-ink-2 hover:bg-surface-2 hover:text-ink'
                  }`
                }
              >
                <Icon name={item.icon} />
                {collapsed ? (
                  <span className="sr-only">{item.label}</span>
                ) : (
                  <span className="flex-1 truncate">{item.label}</span>
                )}
                {item.badge !== undefined && item.badge > 0 && (
                  <span
                    className={`rounded-full px-1.5 text-[11px] font-semibold tabular-nums leading-[18px] text-ink ${
                      item.badgeTone === 'bad' ? 'bg-bad/20' : 'bg-warn/25'
                    } ${collapsed ? 'absolute right-0.5 top-0.5' : ''}`}
                  >
                    {item.badge}
                  </span>
                )}
              </NavLink>
            </div>
          ))}
        </nav>
        <div className={`border-t border-line p-2.5 ${collapsed ? 'flex justify-center' : ''}`}>
          <button
            type="button"
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            className="flex h-8 items-center gap-2 rounded-[7px] px-2 text-[12px] text-ink-2 hover:bg-surface-2 hover:text-ink"
            onClick={() => setCollapsed(!collapsed)}
          >
            <Icon name={collapsed ? 'expand' : 'collapse'} />
            {!collapsed && 'Collapse'}
          </button>
        </div>
      </aside>
      <div className="flex-1 min-w-0 flex flex-col">
        <header className="sticky top-0 z-20 h-14 border-b border-line bg-surface/95 backdrop-blur-sm flex items-center justify-between px-4 md:px-6 gap-3">
          <nav
            aria-label="Main (mobile)"
            className="flex md:hidden gap-3 overflow-x-auto text-[13px]"
          >
            {nav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) =>
                  `whitespace-nowrap ${isActive ? 'font-semibold text-ink' : 'text-ink-2'}`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <span className="hidden md:inline-flex items-center gap-2 rounded-full border border-line px-2.5 py-1 text-[12px] text-ink-2">
            <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-ok" />
            {environment}
          </span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={toggleTheme}
              aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
              title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
              className="grid h-8 w-8 place-items-center rounded-[7px] border border-line text-ink-2 hover:bg-surface-2 hover:text-ink"
            >
              <Icon name={theme === 'dark' ? 'sun' : 'moon'} />
            </button>
            {userMenu}
          </div>
        </header>
        <main className="flex-1 min-w-0 px-4 py-5 md:px-8 md:py-7">
          <div className="mx-auto max-w-[1360px]">{children}</div>
        </main>
      </div>
    </div>
  );
}
