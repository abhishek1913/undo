import { AnimatePresence, motion } from 'motion/react';
import { useRef } from 'react';
import { Chapter, CountUp, EASE, gsap, reduced, scrollTop, useGSAP, type Beat } from '../film';

/* ---------------- how it works: the ten steps ---------------- */

const STEPS: Beat[] = [
  ['Check the arguments.', 'Every call is validated against the tool’s JSON Schema.'],
  ['Catch repeats.', 'Same call id, or the same business key? Return the first result.'],
  ['Fetch the facts.', 'Read-only data from the store: who owns the order, what is refundable.'],
  ['Decide by policy.', 'Allow, ask a human, or deny. Deny is the default.'],
  ['Reserve the budget.', 'Money, action count and tokens, in one atomic update.'],
  ['Write the intent.', 'Saved to the ledger before anyone is called.'],
  ['Call the store.', 'With a stable idempotency key.'],
  ['Record the outcome.', 'Succeeded, failed, or UNKNOWN. Never a guess.'],
  ['Resolve the unknowns.', 'Same key, a lookup, or a person decides.'],
  ['Keep the books.', 'Journal postings, the undo plan, and every event.'],
].map(([title, sub], k) => ({ id: `s${k}`, theme: 'light' as const, title, sub }));

export function HowItWorks() {
  return (
    <Chapter id="how" label="How it works — every call, ten steps" beats={STEPS} perBeat={38} className="how">
      {(s) => (
        <div className="pipe-wrap">
          <AnimatePresence mode="popLayout" initial={false}>
            <motion.span
              key={s.i}
              className="big-n"
              initial={{ opacity: 0, y: 80, rotateX: -60 }}
              animate={{ opacity: 1, y: 0, rotateX: 0 }}
              exit={{ opacity: 0, y: -80, rotateX: 60 }}
              transition={{ duration: 0.6, ease: EASE }}
              aria-hidden
            >
              {String(s.i + 1).padStart(2, '0')}
            </motion.span>
          </AnimatePresence>
          <div className="pipe">
            <span className="pipe-line" />
            <motion.span className="pipe-fill" initial={false} animate={{ scaleX: s.i / (STEPS.length - 1) }} transition={{ duration: 0.6, ease: EASE }} />
            {STEPS.map((b, k) => (
              <span key={b.id} className="pipe-dot" data-on={k <= s.i} data-now={k === s.i}>
                <i>{k + 1}</i>
              </span>
            ))}
            <span className="pipe-track">
              <motion.span className="pipe-orb" initial={false} animate={{ left: `${(s.i / (STEPS.length - 1)) * 100}%` }} transition={{ duration: 0.6, ease: EASE }} />
            </span>
          </div>
        </div>
      )}
    </Chapter>
  );
}

/* ---------------- proof ---------------- */

type ProofBeat = Beat & { n: number; unit?: string; label: string; decimals?: number; tone?: 'ok' | 'wait' };
const PROOF: ProofBeat[] = [
  { id: 'p-chaos', theme: 'dark', title: 'Then we tried to break it.', sub: 'make chaos ran the real Docker stack for 10 minutes and killed workers with SIGKILL.', n: 30, label: 'worker kills' },
  { id: 'p-writes', theme: 'dark', title: 'Thousands of real write actions.', sub: '3,040 of them reached the store at least once.', n: 3508, label: 'write actions' },
  { id: 'p-unknown', theme: 'dark', title: 'Hundreds of answers lost.', sub: 'Every one was resolved. None was retried blindly.', n: 277, label: 'actions went UNKNOWN' },
  { id: 'p-zero', theme: 'dark', title: 'And not one double effect.', sub: 'Undo and the store agree on all 581 refunds, 967 emails and 181 store credits.', n: 0, label: 'duplicate effects', tone: 'ok' },
  { id: 'p-sim', theme: 'dark', title: 'A simulator replays whole worlds.', sub: 'The real engine against crashes, lost messages and a lying agent. All 11 planted bugs were caught.', n: 100000, label: 'seeds, 0 violations', tone: 'ok' },
  { id: 'p-fast', theme: 'dark', title: 'And it stays out of the way.', sub: 'Undo’s own time per call at p95, at 50 calls a second. Policy evaluation alone takes 853 ns.', n: 8.2, decimals: 1, unit: 'ms', label: 'overhead per call' },
  { id: 'p-honest', theme: 'dark', title: 'Not everything is green yet.', sub: 'A one-million-seed run breaks one revert invariant (INV-6) in 22 seeds. It is written down in PROGRESS.md and still open.', n: 22, label: 'open violations at 1,000,000 seeds', tone: 'wait' },
];

export function Proof() {
  return (
    <Chapter id="proof" label="Proof — measured on 2026-10-24" beats={PROOF} perBeat={50} className="proof">
      {(s) => {
        const b = PROOF[s.i];
        return (
          <div className="proof-stage">
            <AnimatePresence mode="wait">
              <motion.div
                key={b.id}
                className="proof-num"
                data-tone={b.tone}
                initial={{ opacity: 0, scale: 0.8, filter: 'blur(20px)' }}
                animate={{ opacity: 1, scale: 1, filter: 'blur(0px)' }}
                exit={{ opacity: 0, scale: 1.1, filter: 'blur(14px)' }}
                transition={{ duration: 0.6, ease: EASE }}
              >
                <b>
                  <CountUp to={b.n} ms={1400} format={(v) => (b.decimals ? v.toFixed(b.decimals) : Math.round(v).toLocaleString('en-US'))} />
                  {b.unit && <small>{b.unit}</small>}
                </b>
                <span>{b.label}</span>
              </motion.div>
            </AnimatePresence>
          </div>
        );
      }}
    </Chapter>
  );
}

/* ---------------- finale ---------------- */

const QUOTE = 'AI can propose. AI never decides what is correct.';

export function Finale() {
  const ref = useRef<HTMLElement>(null);
  useGSAP(
    () => {
      if (reduced()) return;
      gsap.fromTo(
        '.q-word',
        { opacity: 0.1 },
        { opacity: 1, stagger: 0.1, ease: 'none', scrollTrigger: { trigger: '.quote', start: 'top 85%', end: 'bottom 40%', scrub: true } },
      );
    },
    { scope: ref },
  );
  return (
    <footer className="finale" ref={ref}>
      <p className="quote" aria-label={QUOTE}>
        {QUOTE.split(' ').map((w, k) => (
          <span className="q-word" key={k} aria-hidden>
            {w}{' '}
          </span>
        ))}
      </p>
      <div className="finale-grid">
        <div className="run-it">
          <p>Run it on your own machine</p>
          <pre>
            <span>make up</span>
            <small>Postgres, the mock store, Undo and the console</small>
            <span>make sim SEEDS=100000</span>
            <small>the simulator</small>
            <span>make chaos</span>
            <small>ten minutes of kill -9 on the real stack</small>
          </pre>
        </div>
        <button className="again" onClick={scrollTop}>
          Watch it again
        </button>
      </div>
      <p className="fine">Java 21, PostgreSQL 17, React. Every number here comes from docs/BENCHMARKS.md and docs/CHAOS_RESULTS.md.</p>
    </footer>
  );
}
