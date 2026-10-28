import { type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { absolute, relative, money } from '../lib/format';
import { toneBg, toneOf, toneText, type Tone } from '../lib/status';

/**
 * Status is always a dot plus the word (never color only). The dot carries the status color; the
 * word stays in ink because warn and muted text on white is below 4.5:1 contrast (20.6).
 */
export function StatusPill({ status, tone }: { status: string; tone?: Tone }) {
  const t = tone ?? toneOf(status);
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[12px] font-medium text-ink">
      <span aria-hidden className={`inline-block h-2 w-2 rounded-full ${toneBg[t]}`} />
      {status}
    </span>
  );
}

export function Money({
  minor,
  className = '',
}: {
  minor: number | null | undefined;
  className?: string;
}) {
  return <span className={`tabular-nums whitespace-nowrap ${className}`}>{money(minor)}</span>;
}

export function Time({ iso }: { iso: string | null | undefined }) {
  if (!iso) return <span className="text-ink-2">—</span>;
  return (
    <time dateTime={iso} title={absolute(iso)} className="text-ink-2 whitespace-nowrap">
      {relative(iso)}
    </time>
  );
}

export function Mono({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <span className={`font-mono text-[12px] ${className}`}>{children}</span>;
}

export function Card({
  children,
  className = '',
  title,
  actions,
  flush = false,
}: {
  children: ReactNode;
  className?: string;
  title?: ReactNode;
  actions?: ReactNode;
  /** No padding, for a table that fills the card. */
  flush?: boolean;
}) {
  return (
    <section className={`rounded-[8px] border border-line bg-surface ${className}`}>
      {(title || actions) && (
        <header className="flex min-h-11 items-center justify-between gap-2 border-b border-line px-4 py-2">
          <h2 className="text-[14px] font-semibold text-ink">{title}</h2>
          {actions}
        </header>
      )}
      <div className={flush ? '' : 'p-4'}>{children}</div>
    </section>
  );
}

const toneRule: Record<Tone, string> = {
  ok: 'border-l-ok',
  warn: 'border-l-warn',
  bad: 'border-l-bad',
  info: 'border-l-info',
  unknown: 'border-l-unknown',
  muted: 'border-l-muted',
};

/**
 * A number on a tile. With a tone the tile asks for attention: a status-colored edge, and when
 * `to` is set the whole tile links to where the person acts on it.
 */
export function Tile({
  label,
  value,
  hint,
  tone,
  to,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  tone?: Tone;
  to?: string;
}) {
  const body = (
    <>
      <div className="text-[12px] font-medium text-ink-2">{label}</div>
      <div
        className={`mt-1.5 text-[28px] font-bold leading-none tracking-tight tabular-nums ${tone ? toneText[tone] : ''}`}
      >
        {value}
      </div>
      {hint && <div className="mt-2 text-[12px] text-ink-2">{hint}</div>}
    </>
  );
  const cls = `block min-w-0 rounded-[8px] border border-line bg-surface p-4 ${
    tone ? `border-l-[3px] ${toneRule[tone]}` : ''
  }`;
  return to ? (
    <Link to={to} className={`${cls} hover:bg-surface-2`}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  );
}

export function PageTitle({
  children,
  sub,
  actions,
}: {
  children: ReactNode;
  sub?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-[20px] font-bold leading-tight tracking-tight">{children}</h1>
        {sub && <div className="mt-1.5 text-[13px] text-ink-2">{sub}</div>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-[8px] border border-dashed border-line px-6 py-8 text-center text-[13px] text-ink-2">
      {children}
    </div>
  );
}

export function Loading({ what = 'Loading' }: { what?: string }) {
  return (
    <div role="status" className="p-6 text-ink-2">
      {what}…
    </div>
  );
}

export function ErrorBox({ error }: { error: unknown }) {
  const msg = error instanceof Error ? error.message : String(error);
  return (
    <div role="alert" className="rounded-[8px] border border-bad/40 bg-bad/10 p-4 text-bad">
      {msg}
    </div>
  );
}

type BtnProps = {
  children: ReactNode;
  onClick?: () => void;
  kind?: 'primary' | 'secondary' | 'danger';
  disabled?: boolean;
  type?: 'button' | 'submit';
  className?: string;
  ariaLabel?: string;
};

export function Button({
  children,
  onClick,
  kind = 'secondary',
  disabled,
  type = 'button',
  className = '',
  ariaLabel,
}: BtnProps) {
  const base =
    'inline-flex h-8 items-center justify-center gap-1.5 rounded-[7px] px-3 text-[13px] font-semibold whitespace-nowrap disabled:opacity-50 disabled:cursor-not-allowed';
  const styles = {
    primary: 'bg-accent-fill text-on-accent hover:opacity-90',
    secondary: 'border border-line bg-surface hover:bg-surface-2 text-ink',
    danger: 'border border-bad/50 text-bad hover:bg-bad/10',
  }[kind];
  return (
    <button
      type={type}
      aria-label={ariaLabel}
      className={`${base} ${styles} ${className}`}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  );
}

/** Pretty JSON. Focusable so a long block can be scrolled by keyboard (20.6). */
export function JsonBlock({ value, label = 'JSON' }: { value: unknown; label?: string }) {
  return (
    <pre
      tabIndex={0}
      role="region"
      aria-label={label}
      className="max-h-96 overflow-auto rounded-[8px] bg-surface-2 p-3 font-mono text-[12px] leading-relaxed whitespace-pre-wrap break-all"
    >
      {value === null || value === undefined ? 'null' : JSON.stringify(value, null, 2)}
    </pre>
  );
}

export function Table({
  head,
  children,
  label,
  bare = false,
}: {
  head: ReactNode[];
  children: ReactNode;
  label: string;
  /** Without its own border, inside a flush card. */
  bare?: boolean;
}) {
  return (
    <div className={`overflow-x-auto ${bare ? '' : 'rounded-[8px] border border-line bg-surface'}`}>
      <table className="w-full border-collapse text-[13px]" aria-label={label}>
        <thead className="bg-surface-2/60 text-left text-[12px] text-ink-2">
          <tr>
            {head.map((h, i) => (
              <th key={i} scope="col" className="whitespace-nowrap px-3 py-2 font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

export function Td({
  children,
  className = '',
  colSpan,
}: {
  children?: ReactNode;
  className?: string;
  colSpan?: number;
}) {
  return (
    <td colSpan={colSpan} className={`border-t border-line px-3 py-2.5 align-top ${className}`}>
      {children}
    </td>
  );
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-1 py-1.5 sm:grid-cols-[160px_1fr] sm:gap-3">
      <div className="text-[13px] text-ink-2">{label}</div>
      <div className="min-w-0 text-[14px]">{children}</div>
    </div>
  );
}
