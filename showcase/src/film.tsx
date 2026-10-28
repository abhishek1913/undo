import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useGSAP } from '@gsap/react';
import Lenis from 'lenis';
import 'lenis/dist/lenis.css';
import { AnimatePresence, motion } from 'motion/react';
import { type ReactNode, useEffect, useRef, useState } from 'react';

gsap.registerPlugin(ScrollTrigger, useGSAP);
export { gsap, ScrollTrigger, useGSAP };

export const EASE = [0.22, 1, 0.36, 1] as const;
export const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/* ---------- smooth scroll ---------- */
let lenis: Lenis | null = null;
export function useSmoothScroll() {
  useEffect(() => {
    if (reduced()) return;
    lenis = new Lenis({ lerp: 0.085, smoothWheel: true });
    lenis.on('scroll', ScrollTrigger.update);
    const tick = (t: number) => lenis?.raf(t * 1000);
    gsap.ticker.add(tick);
    gsap.ticker.lagSmoothing(0);
    const onClick = (e: MouseEvent) => {
      const a = (e.target as HTMLElement).closest('a[href^="#"]');
      const el = a && document.querySelector<HTMLElement>(a.getAttribute('href')!);
      if (!el) return;
      e.preventDefault();
      lenis?.scrollTo(el, { duration: 1.6 });
    };
    document.addEventListener('click', onClick);
    return () => {
      document.removeEventListener('click', onClick);
      gsap.ticker.remove(tick);
      lenis?.destroy();
      lenis = null;
    };
  }, []);
}
export const scrollTop = () => (lenis ? lenis.scrollTo(0, { duration: 2.2 }) : window.scrollTo(0, 0));

/* ---------- a pinned chapter, stepped by scroll ---------- */
export type Theme = 'dark' | 'light';
export type Beat = { id: string; theme: Theme; title: string; sub?: string };
export type Shot = {
  i: number;
  beat: Beat;
  dark: boolean;
  /** true once the chapter has reached beat `id` */
  at: (id: string) => boolean;
  is: (...ids: string[]) => boolean;
};

export function Chapter({
  id,
  label,
  beats,
  perBeat = 55,
  className = '',
  children,
}: {
  id: string;
  label?: string;
  beats: Beat[];
  perBeat?: number;
  className?: string;
  children: (s: Shot) => ReactNode;
}) {
  const ref = useRef<HTMLElement>(null);
  const bar = useRef<HTMLElement>(null);
  const [i, setI] = useState(0);

  useGSAP(
    () => {
      ScrollTrigger.create({
        trigger: ref.current,
        start: 'top top',
        end: () => `+=${beats.length * window.innerHeight * (perBeat / 100)}`,
        pin: true,
        anticipatePin: 1,
        invalidateOnRefresh: true,
        onUpdate: (self) => {
          setI(Math.min(beats.length - 1, Math.floor(self.progress * beats.length)));
          if (bar.current) bar.current.style.transform = `scaleX(${self.progress})`;
        },
      });
    },
    { scope: ref },
  );

  const beat = beats[i];
  const shot: Shot = {
    i,
    beat,
    dark: beat.theme === 'dark',
    at: (b) => {
      const k = beats.findIndex((x) => x.id === b);
      return k !== -1 && i >= k;
    },
    is: (...ids) => ids.includes(beat.id),
  };
  const hasLight = beats.some((b) => b.theme === 'light');
  const hasDark = beats.some((b) => b.theme === 'dark');

  return (
    <section ref={ref} id={id} className={`chapter ${className}`} data-theme={beat.theme}>
      <div className="light-wash" aria-hidden />
      {label && (
        <div className="ch-top">
          <span className="ch-label">{label}</span>
          {hasLight && hasDark && (
            <span className="ch-mode" data-theme={beat.theme}>
              <i />
              {shot.dark ? 'Without Undo' : 'With Undo'}
            </span>
          )}
        </div>
      )}
      <div className="ch-caption" aria-live="polite">
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={i}
            initial={{ opacity: 0, y: 26, filter: 'blur(10px)' }}
            animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
            exit={{ opacity: 0, y: -18, filter: 'blur(8px)' }}
            transition={{ duration: 0.5, ease: EASE }}
          >
            <h2>{beat.title}</h2>
            {beat.sub && <p>{beat.sub}</p>}
          </motion.div>
        </AnimatePresence>
      </div>
      <div className="ch-visual">{children(shot)}</div>
      <div className="ch-foot" aria-hidden>
        <span className="ch-bar">
          <i ref={bar} />
        </span>
        <span className="ch-count">
          {String(i + 1).padStart(2, '0')} / {String(beats.length).padStart(2, '0')}
        </span>
      </div>
    </section>
  );
}

/* ---------- icons ---------- */
export type IconName = 'agent' | 'undo' | 'store' | 'worker' | 'human' | 'bank' | 'mail' | 'check' | 'x' | 'key' | 'lock';
export function Icon({ name }: { name: IconName }) {
  const p = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.7, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  return (
    <svg className="icon" viewBox="0 0 24 24" aria-hidden>
      {name === 'agent' && <path {...p} d="M12 3c.6 4.2 2.8 6.4 7 7-4.2.6-6.4 2.8-7 7-.6-4.2-2.8-6.4-7-7 4.2-.6 6.4-2.8 7-7z" />}
      {name === 'undo' && (
        <>
          <path {...p} d="M8 7h6.5a5 5 0 0 1 0 10H8" />
          <path {...p} d="M10.5 4 7 7l3.5 3" />
        </>
      )}
      {name === 'store' && (
        <>
          <path {...p} d="M4 9.5 5.5 5h13L20 9.5" />
          <path {...p} d="M4 9.5c0 1.5 1.2 2.5 2.7 2.5S9.3 11 9.3 9.5c0 1.5 1.2 2.5 2.7 2.5s2.7-1 2.7-2.5c0 1.5 1.2 2.5 2.6 2.5S20 11 20 9.5" />
          <path {...p} d="M5.5 12v7h13v-7M10 19v-4h4v4" />
        </>
      )}
      {name === 'worker' && (
        <>
          <rect {...p} x="4" y="5" width="16" height="6" rx="1.5" />
          <rect {...p} x="4" y="13" width="16" height="6" rx="1.5" />
          <path {...p} d="M7.5 8h.01M7.5 16h.01" />
        </>
      )}
      {name === 'human' && (
        <>
          <circle {...p} cx="12" cy="8.5" r="3.5" />
          <path {...p} d="M5 19.5c1.2-3.4 3.8-5 7-5s5.8 1.6 7 5" />
        </>
      )}
      {name === 'bank' && <path {...p} d="M4 9.5 12 5l8 4.5M5.5 10v7M10 10v7M14 10v7M18.5 10v7M4 19h16" />}
      {name === 'mail' && (
        <>
          <rect {...p} x="3.5" y="6" width="17" height="12" rx="2" />
          <path {...p} d="m4 7 8 6 8-6" />
        </>
      )}
      {name === 'check' && <path {...p} d="m5 12.5 4.5 4.5L19 7.5" />}
      {name === 'x' && <path {...p} d="M6 6l12 12M18 6 6 18" />}
      {name === 'key' && (
        <>
          <circle {...p} cx="8" cy="12" r="3.5" />
          <path {...p} d="M11.5 12H20M17 12v3M20 12v2" />
        </>
      )}
      {name === 'lock' && (
        <>
          <rect {...p} x="5" y="10.5" width="14" height="9" rx="2" />
          <path {...p} d="M8 10.5V8a4 4 0 0 1 8 0v2.5" />
        </>
      )}
    </svg>
  );
}

/* ---------- status pill ---------- */
export type Tone = 'ok' | 'unknown' | 'wait' | 'bad' | 'run' | 'muted';
const TONE: Record<string, Tone> = {
  PROPOSED: 'run',
  EXECUTING: 'run',
  UNKNOWN: 'unknown',
  SUCCEEDED: 'ok',
  AWAITING_APPROVAL: 'wait',
  NEEDS_REVIEW: 'wait',
  DENIED: 'bad',
  REJECTED: 'bad',
  DUPLICATE: 'wait',
  REVERTED: 'muted',
};
export function Pill({ s, tone }: { s: string; tone?: Tone }) {
  return (
    <AnimatePresence mode="popLayout" initial={false}>
      <motion.span
        key={s}
        className="pill"
        data-tone={tone ?? TONE[s] ?? 'run'}
        initial={{ opacity: 0, scale: 0.7 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{ opacity: 0, scale: 0.7 }}
        transition={{ type: 'spring', stiffness: 420, damping: 24 }}
      >
        {s}
      </motion.span>
    </AnimatePresence>
  );
}

/* ---------- the cast: agent, Undo, store, joined by one wire ---------- */
export type Packet = { key: string; from: number; to: number; label: string; tone?: Tone; lost?: boolean };

export function Cast({
  shot,
  packet,
  undoIn,
  agentSub = 'Claude or any MCP client',
  storeSub = 'payments provider',
  trays,
}: {
  shot: Shot;
  packet?: Packet | null;
  undoIn: boolean;
  agentSub?: string;
  storeSub?: string;
  trays: [ReactNode, ReactNode, ReactNode];
}) {
  return (
    <div className="cast">
      <div className="cast-row">
        <div className="wire" data-on={undoIn}>
          <span className="wire-line" />
          <AnimatePresence>
            {packet && (
              <motion.div
                key={packet.key}
                className="runner"
                initial={{ x: `${packet.from * 100}%` }}
                animate={{ x: `${(packet.lost ? (packet.from + packet.to) / 2 : packet.to) * 100}%` }}
                exit={{ opacity: 0, transition: { duration: 0.2 } }}
                transition={{ duration: packet.lost ? 0.6 : 0.95, ease: [0.45, 0, 0.2, 1] }}
              >
                <motion.span
                  className="packet"
                  data-tone={packet.tone ?? 'run'}
                  animate={packet.lost ? { opacity: [1, 1, 0], scale: [1, 1.15, 0.4] } : { opacity: 1 }}
                  transition={packet.lost ? { duration: 1, times: [0, 0.6, 1] } : undefined}
                >
                  {packet.label}
                </motion.span>
                {packet.lost && <Burst />}
              </motion.div>
            )}
          </AnimatePresence>
        </div>
        <Actor icon="agent" title="Agent" sub={agentSub} />
        <motion.div
          className="actor-slot"
          initial={false}
          animate={undoIn ? { opacity: 1, scale: 1, y: 0 } : { opacity: 0, scale: 0.6, y: 20 }}
          transition={{ type: 'spring', stiffness: 160, damping: 18 }}
        >
          <Actor icon="undo" title="Undo" sub="ledger + policy" hero />
        </motion.div>
        <Actor icon="store" title="Store" sub={storeSub} />
      </div>
      <div className="trays" data-dark={shot.dark}>
        {trays.map((t, k) => (
          <div className="tray" key={k}>
            {k === 1 && !undoIn ? (
              <>
                <TrayTitle>Undo</TrayTitle>
                <p className="no-undo">Not here yet. Nothing writes down what was asked, or checks it.</p>
              </>
            ) : (
              t
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

export function Actor({ icon, title, sub, hero = false, dead = false }: { icon: IconName; title: string; sub?: string; hero?: boolean; dead?: boolean }) {
  return (
    <motion.div
      className="actor"
      data-hero={hero}
      animate={dead ? { opacity: 0.25, scale: 0.94, filter: 'grayscale(1)' } : { opacity: 1, scale: 1, filter: 'grayscale(0)' }}
      transition={{ duration: 0.5, ease: EASE }}
    >
      <span className="actor-icon">
        <Icon name={icon} />
      </span>
      <span className="actor-text">
        <b>{title}</b>
        {sub && <small>{sub}</small>}
      </span>
    </motion.div>
  );
}

function Burst() {
  const bits = [
    [-34, -26],
    [28, -32],
    [38, 8],
    [-38, 14],
    [-10, 34],
    [16, 30],
    [0, -40],
  ];
  return (
    <span className="burst" aria-hidden>
      {bits.map(([x, y], k) => (
        <motion.i
          key={k}
          initial={{ x: 0, y: 0, opacity: 0 }}
          animate={{ x, y, opacity: [0, 1, 0], rotate: x * 5 }}
          transition={{ delay: 0.55, duration: 0.8, ease: EASE }}
        />
      ))}
      <motion.b initial={{ opacity: 0, y: 0 }} animate={{ opacity: [0, 1, 1, 0], y: -30 }} transition={{ delay: 0.5, duration: 1.8 }}>
        lost
      </motion.b>
    </span>
  );
}

/* ---------- small building blocks for trays ---------- */
export function TrayTitle({ children }: { children: ReactNode }) {
  return <span className="tray-title">{children}</span>;
}

export function Items({ children }: { children: ReactNode }) {
  return (
    <ul className="items">
      <AnimatePresence initial={false}>{children}</AnimatePresence>
    </ul>
  );
}

export function Item({ tone, children, big = false }: { tone?: 'bad' | 'ok' | 'hot' | 'unknown'; children: ReactNode; big?: boolean }) {
  return (
    <motion.li
      className="item"
      data-tone={tone}
      data-big={big}
      layout
      initial={{ opacity: 0, y: -16, scale: 0.92 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, scale: 0.92, transition: { duration: 0.2 } }}
      transition={{ type: 'spring', stiffness: 260, damping: 22 }}
    >
      {children}
    </motion.li>
  );
}

/** Counts up to `to` whenever it mounts. */
export function CountUp({ to, ms = 1200, format = (n: number) => n.toLocaleString('en-IN') }: { to: number; ms?: number; format?: (n: number) => string }) {
  const [n, setN] = useState(0);
  useEffect(() => {
    if (reduced()) return setN(to);
    let raf = 0;
    const t0 = performance.now();
    const tick = (t: number) => {
      const k = Math.min(1, (t - t0) / ms);
      setN(to * (1 - Math.pow(1 - k, 4)));
      if (k < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [to, ms]);
  return <>{format(n)}</>;
}
