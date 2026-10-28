import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useState } from 'react';
import { Chapter, CountUp, EASE, Pill, type Beat } from '../film';

const BEATS: Beat[] = [
  { id: 'd-run', theme: 'dark', title: 'A bulk run refunds 23 “lost” parcels.', sub: 'An operator ran: refund every order the carrier marked LOST.' },
  { id: 'd-bug', theme: 'dark', title: 'The carrier data was wrong.', sub: 'The parcels were never lost. 23 customers were refunded by mistake.' },
  { id: 'd-hand', theme: 'dark', title: 'Someone fixes it by hand.', sub: 'Reading logs, one order at a time, over days.' },
  { id: 'd-miss', theme: 'dark', title: 'Days later, three are still wrong.', sub: 'And nobody is sure which three.' },
  { id: 'l-run', theme: 'light', title: 'Same run, through Undo.', sub: 'As each action happened, it recorded how to undo itself.' },
  { id: 'l-life', theme: 'light', title: 'Life goes on for a while.', sub: '3 of the refunds settle at the bank. A person edits 1 order note.' },
  { id: 'l-click', theme: 'light', title: 'Revert run.', sub: 'Undo freezes the run and builds a preview before touching anything.' },
  { id: 'l-preview', theme: 'light', title: 'It shows what it can undo, and what it can’t.' },
  { id: 'l-confirm', theme: 'light', title: 'You type the run number to confirm.' },
  { id: 'l-back', theme: 'light', title: 'Undo works backwards, newest first.' },
  { id: 'l-done', theme: 'light', title: 'Partially reverted. And honest about it.', sub: 'Settled refunds and a person’s edit are reported, never overwritten. Correction emails wait for a human.' },
];

const N = 23;
const SETTLED = new Set([4, 11, 17]);
const EDITED = 8;
const MISSED = new Set([3, 13, 19]);
const FLIP = { rotateY: [0, 180, 360] };
const FLAT = { rotateY: 0 };

function Typed({ text }: { text: string }) {
  const [n, setN] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setN((k) => Math.min(text.length, k + 1)), 220);
    return () => clearInterval(t);
  }, [text]);
  return (
    <>
      {text.slice(0, n)}
      <span className="caret" />
    </>
  );
}

export default function Revert() {
  return (
    <Chapter id="story-5" label="Story 5 — The whole run was wrong" beats={BEATS}>
      {(s) => {
        const back = !s.dark && s.at('l-back');
        const life = !s.dark && s.at('l-life');
        return (
          <div className="rv" data-dark={s.dark}>
            <div className="board">
              <div className="board-head">
                <b>Run 482</b>
                <span>lost-package sweep</span>
                <AnimatePresence mode="wait">
                  {s.dark && s.at('d-bug') && (
                    <motion.span key="bug" className="flag" data-tone="bad" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0 }}>
                      carrier bug
                    </motion.span>
                  )}
                  {!s.dark && s.at('l-click') && (
                    <motion.span key="rv" className="flag" data-tone="unknown" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0 }}>
                      {back ? 'reverting' : 'frozen'}
                    </motion.span>
                  )}
                </AnimatePresence>
              </div>
              <div className="tiles">
                {Array.from({ length: N }, (_, k) => {
                  const made = s.dark ? s.at('d-run') : s.at('l-run');
                  const settled = life && SETTLED.has(k);
                  const edited = life && k === EDITED;
                  const handFixed = s.dark && s.at('d-hand') && !MISSED.has(k);
                  const missed = s.dark && s.at('d-miss') && MISSED.has(k);
                  const refund = !made ? 'empty' : back ? (settled ? 'kept' : 'back') : handFixed ? 'back' : missed ? 'bad' : 'done';
                  const note = !made ? 'empty' : back ? (edited ? 'kept' : 'back') : handFixed ? 'back' : 'done';
                  const delay = back ? (N - 1 - k) * 0.09 : handFixed ? k * 0.12 : s.is('d-run', 'l-run') ? k * 0.04 : 0;
                  return (
                    <motion.div
                      key={k}
                      className="tile"
                      data-bug={s.dark && s.is('d-bug')}
                      initial={false}
                      animate={back ? FLIP : FLAT}
                      transition={{ delay, duration: 0.7, ease: EASE }}
                    >
                      <span className="bar" data-s={refund} style={{ transitionDelay: `${delay + 0.3}s` }} />
                      <span className="bar bar-note" data-s={note} style={{ transitionDelay: `${delay + 0.3}s` }} />
                      {settled && <i className="badge" data-k="lock" title="settled at the bank" />}
                      {edited && <i className="badge" data-k="pen" title="edited by a person" />}
                      {missed && <i className="badge" data-k="miss">?</i>}
                    </motion.div>
                  );
                })}
              </div>
              <div className="legend">
                <span><i data-s="done" />refund and note</span>
                <span><i data-s="back" />undone</span>
                <span><i data-s="kept" />can’t undo, reported</span>
                {s.dark && <span><i data-s="bad" />still wrong</span>}
              </div>
            </div>

            <AnimatePresence>
              {!s.dark && s.at('l-click') && (
                <motion.div
                  className="preview"
                  initial={{ opacity: 0, x: 40, scale: 0.96 }}
                  animate={{ opacity: 1, x: 0, scale: 1 }}
                  exit={{ opacity: 0, x: 40 }}
                  transition={{ duration: 0.6, ease: EASE }}
                >
                  <span className="tray-title">{s.at('l-done') ? 'Revert report' : 'Revert preview'}</span>
                  {s.at('l-preview') ? (
                    <ul>
                      <li data-tone="back"><b><CountUp to={20} /></b>refunds cancelled</li>
                      <li data-tone="back"><b><CountUp to={22} /></b>order notes restored</li>
                      <li data-tone="kept"><b><CountUp to={3} /></b>refunds already settled, can’t be cancelled</li>
                      <li data-tone="kept"><b><CountUp to={1} /></b>note edited by a person, left alone</li>
                      <li data-tone="wait"><b><CountUp to={23} /></b>correction emails, waiting for approval</li>
                    </ul>
                  ) : (
                    <p className="building">
                      <span className="spinner" /> building the plan…
                    </p>
                  )}
                  <div className="confirm">
                    {s.at('l-done') ? (
                      <Pill s="PARTIALLY_REVERTED" tone="wait" />
                    ) : (
                      <>
                        <span className="confirm-field">{s.at('l-confirm') ? <Typed text="482" /> : <span className="dim">run number</span>}</span>
                        <motion.span className="exec" data-on={back} animate={s.is('l-back') ? { scale: [1, 0.92, 1] } : {}} transition={{ duration: 0.3 }}>
                          {back ? 'Reverting…' : 'Execute revert'}
                        </motion.span>
                      </>
                    )}
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        );
      }}
    </Chapter>
  );
}
