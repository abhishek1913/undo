import { formatDistanceToNowStrict, format } from 'date-fns';

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });

/** Indian style money from minor units (paise): ₹1,23,456.00 */
export function money(minor: number | null | undefined): string {
  if (minor === null || minor === undefined || Number.isNaN(minor)) return '—';
  return inr.format(minor / 100);
}

export function relative(iso: string | null | undefined): string {
  if (!iso) return '—';
  return formatDistanceToNowStrict(new Date(iso), { addSuffix: true });
}

export function absolute(iso: string | null | undefined): string {
  if (!iso) return '';
  return format(new Date(iso), 'yyyy-MM-dd HH:mm:ss');
}

export function clock(iso: string | null | undefined): string {
  if (!iso) return '';
  return format(new Date(iso), 'HH:mm:ss');
}

export function duration(fromIso: string | null | undefined, toIso?: string | null): string {
  if (!fromIso) return '—';
  const ms = (toIso ? new Date(toIso).getTime() : Date.now()) - new Date(fromIso).getTime();
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${s % 60} s`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

export function untilText(iso: string | null | undefined): string {
  if (!iso) return '—';
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return 'expired';
  const m = Math.floor(ms / 60000);
  const h = Math.floor(m / 60);
  return h > 0 ? `expires in ${h} h ${m % 60} m` : `expires in ${m} m`;
}

export function shortHash(h: string | null | undefined): string {
  if (!h) return '—';
  return `${h.slice(0, 4)}…${h.slice(-4)}`;
}
