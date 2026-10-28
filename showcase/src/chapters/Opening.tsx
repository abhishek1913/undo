import { AnimatePresence, motion } from 'motion/react';
import { Chapter, EASE, type Beat } from '../film';

const BEATS: Beat[] = [
  { id: 'brand', theme: 'dark', title: 'Exactly once. And one click to take it back.', sub: 'Undo sits between an AI agent and the real systems it changes.' },
  { id: 'money', theme: 'dark', title: 'AI agents can now move real money.', sub: 'They refund customers, grant store credit and send emails. On their own.' },
  { id: 'wrong', theme: 'dark', title: 'And sometimes it goes wrong.', sub: 'Networks drop answers. Workers crash. Tickets lie. Whole runs rest on bad data.' },
  { id: 'ask', theme: 'dark', title: 'So who makes sure each action happens exactly once?', sub: 'And who can take it back when it shouldn’t have happened at all?' },
  { id: 'undo', theme: 'dark', title: 'Undo. Here are six stories.', sub: 'Each one starts in the dark, where things go wrong. Then Undo turns the lights on.' },
];

const CHIPS = [
  { t: 'refund ₹2,400', x: -38, y: -30 },
  { t: 'store credit ₹500', x: 30, y: -36 },
  { t: 'email “refund sent”', x: -44, y: 6 },
  { t: 'close ticket TKT-0117', x: 36, y: 2 },
  { t: 'refund ₹1,150', x: -20, y: 34 },
  { t: 'order note updated', x: 24, y: 36 },
  { t: 'refund ₹78,000', x: 2, y: -46 },
  { t: 'refund ₹1,850', x: 4, y: 46 },
];

function Mark({ draw }: { draw: boolean }) {
  return (
    <svg className="logo-mark" viewBox="0 0 120 120" aria-hidden>
      <defs>
        <linearGradient id="mk" x1="0" x2="1" y1="0" y2="1">
          <stop offset="0" stopColor="#c4b5fd" />
          <stop offset="0.5" stopColor="#7c5cff" />
          <stop offset="1" stopColor="#38bdf8" />
        </linearGradient>
      </defs>
      <motion.path
        d="M40 38h36a24 24 0 0 1 0 48H40"
        fill="none"
        stroke="url(#mk)"
        strokeWidth="9"
        strokeLinecap="round"
        initial={{ pathLength: 0 }}
        animate={{ pathLength: draw ? 1 : 0 }}
        transition={{ duration: 1.4, ease: EASE }}
      />
      <motion.path
        d="M54 22 38 38l16 16"
        fill="none"
        stroke="url(#mk)"
        strokeWidth="9"
        strokeLinecap="round"
        strokeLinejoin="round"
        initial={{ pathLength: 0 }}
        animate={{ pathLength: draw ? 1 : 0 }}
        transition={{ duration: 0.8, delay: draw ? 0.9 : 0, ease: EASE }}
      />
    </svg>
  );
}

export default function Opening() {
  return (
    <Chapter id="top" beats={BEATS} perBeat={45} className="opening">
      {(s) => {
        const brand = s.is('brand', 'undo');
        const chips = s.is('money', 'wrong');
        return (
          <div className="op">
            <div className="aurora" data-state={s.beat.id} aria-hidden>
              <i />
              <i />
              <i />
            </div>

            <AnimatePresence>
              {brand && (
                <motion.div
                  key="brand"
                  className="brand"
                  initial={{ opacity: 0, scale: 0.85, filter: 'blur(20px)' }}
                  animate={{ opacity: 1, scale: 1, filter: 'blur(0px)' }}
                  exit={{ opacity: 0, scale: 1.08, filter: 'blur(16px)' }}
                  transition={{ duration: 0.9, ease: EASE }}
                >
                  <Mark draw={brand} />
                  <span className="wordmark">Undo</span>
                </motion.div>
              )}
            </AnimatePresence>

            <div className="chips" data-wrong={s.is('wrong')} aria-hidden>
              {CHIPS.map((c, k) => (
                <motion.span
                  key={c.t}
                  className="chip"
                  style={{ ['--d' as string]: `${k * 0.37}s` }}
                  initial={false}
                  animate={
                    chips
                      ? { opacity: 1, x: `${c.x * 0.9}vw`, y: `${c.y * 0.55}vh`, scale: 1 }
                      : s.is('ask')
                        ? { opacity: 0, x: 0, y: 0, scale: 0.3 }
                        : { opacity: 0, x: `${c.x * 1.4}vw`, y: `${c.y}vh`, scale: 0.8 }
                  }
                  transition={{ duration: 1, delay: k * 0.05, ease: EASE }}
                >
                  {c.t}
                </motion.span>
              ))}
            </div>

            <AnimatePresence>
              {s.is('ask') && (
                <motion.div
                  key="ring"
                  className="ring"
                  initial={{ opacity: 0, scale: 0.2 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 2.4 }}
                  transition={{ duration: 1, ease: EASE }}
                  aria-hidden
                >
                  <i />
                </motion.div>
              )}
            </AnimatePresence>

            {s.is('brand') && (
              <span className="scroll-cue" aria-hidden>
                <i />
                Scroll
              </span>
            )}
          </div>
        );
      }}
    </Chapter>
  );
}
