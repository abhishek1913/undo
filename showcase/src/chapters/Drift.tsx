import { AnimatePresence, motion } from 'motion/react';
import { Chapter, EASE, Icon, Pill, type Beat } from '../film';

const BEATS: Beat[] = [
  { id: 'd-told', theme: 'dark', title: 'The agent tells the customer: refunded.', sub: '₹1,850 on order SF-1077. The customer stops worrying.' },
  { id: 'd-fail', theme: 'dark', title: 'Hours later, the bank fails it.', sub: 'The store sends a webhook about it. The webhook never arrives.' },
  { id: 'd-angry', theme: 'dark', title: 'Three weeks later, an angry email.', sub: 'Nobody saw it coming, because nobody was checking.' },
  { id: 'l-scan', theme: 'light', title: 'Every 5 minutes, Undo compares notes with the store.', sub: 'Each of its records is matched to the store’s by action id.' },
  { id: 'l-status', theme: 'light', title: 'One refund failed later. The webhook was missed.', sub: 'Undo says pending. The store says failed.' },
  { id: 'l-heal1', theme: 'light', title: 'That is safe to fix, so Undo fixes it.', sub: 'Through the same code a real webhook uses. Now someone can tell the customer the truth.' },
  { id: 'l-ghost', theme: 'light', title: 'One store credit was UNKNOWN. The store has it.', sub: 'It happened. Undo just never heard back.' },
  { id: 'l-heal2', theme: 'light', title: 'Also safe: it is marked SUCCEEDED.', sub: 'With the store’s own reference as evidence.' },
  { id: 'l-amount', theme: 'light', title: 'These amounts don’t match.', sub: 'Undo won’t guess here. A critical finding goes to a person.' },
];

type Line = { what: string; undo: string; store: string; at?: string; heal?: string; healed?: string; tone?: 'bad' | 'unknown'; finding?: [string, string, string] };
const LINES: Line[] = [
  { what: 'refund SF-1042', undo: '₹2,400 succeeded', store: '₹2,400 paid' },
  { what: 'email C-311', undo: 'sent', store: 'sent' },
  { what: 'refund SF-1077', undo: '₹1,850 pending', store: '₹1,850 failed', at: 'l-status', heal: 'l-heal1', healed: '₹1,850 failed', tone: 'bad', finding: ['STATUS_MISMATCH', 'info', 'fixed automatically'] },
  { what: 'credit C-208', undo: '₹500 unknown', store: '₹500 applied', at: 'l-ghost', heal: 'l-heal2', healed: '₹500 succeeded', tone: 'unknown', finding: ['GHOST_EFFECT', 'warning', 'fixed automatically'] },
  { what: 'refund SF-2210', undo: '₹1,150', store: '₹11,500', at: 'l-amount', tone: 'bad', finding: ['AMOUNT_MISMATCH', 'critical', 'sent to a person'] },
];

export default function Drift() {
  return (
    <Chapter id="story-6" label="Story 6 — The refund that failed later" beats={BEATS}>
      {(s) =>
        s.dark ? (
          <div className="drift-dark">
            <motion.div className="bubble" initial={{ opacity: 0, x: -30 }} animate={{ opacity: 1, x: 0 }} transition={{ duration: 0.6, ease: EASE }}>
              <span className="actor-icon">
                <Icon name="agent" />
              </span>
              <p>Good news! Your refund of ₹1,850 is done.</p>
            </motion.div>
            <div className="bank" data-bad={s.at('d-fail')}>
              <Icon name="bank" />
              <span>Bank, refund for SF-1077</span>
              <Pill s={s.at('d-fail') ? 'FAILED' : 'PENDING'} tone={s.at('d-fail') ? 'bad' : 'run'} />
              {s.at('d-fail') && (
                <motion.span className="webhook" initial={{ x: 0, opacity: 1 }} animate={{ x: 180, opacity: 0 }} transition={{ duration: 1.4, ease: EASE }}>
                  webhook → lost
                </motion.span>
              )}
            </div>
            <AnimatePresence>
              {s.at('d-angry') && (
                <motion.div
                  className="angry"
                  initial={{ opacity: 0, y: 40, rotate: 6, scale: 0.9 }}
                  animate={{ opacity: 1, y: 0, rotate: -2, scale: 1 }}
                  exit={{ opacity: 0 }}
                  transition={{ type: 'spring', stiffness: 180, damping: 16 }}
                >
                  <small>
                    <Icon name="mail" /> 3 weeks later
                  </small>
                  <p>“You told me I was refunded. I wasn’t. Where is my money?”</p>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        ) : (
          <div className="recon-wrap">
            <div className="recon">
              <div className="recon-row recon-head">
                <span />
                <span>Undo says</span>
                <span />
                <span>Store says</span>
              </div>
              {LINES.map((l, k) => {
                const reached = l.at ? s.at(l.at) : true;
                const healed = l.heal ? s.at(l.heal) : false;
                const mark = !reached ? '' : l.finding && !healed ? '≠' : '=';
                return (
                  <motion.div
                    key={l.what}
                    className="recon-row"
                    data-hot={s.is(l.at ?? '', l.heal ?? '')}
                    data-crit={l.finding?.[1] === 'critical' && reached}
                    initial={{ opacity: 0, x: -20 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: k * 0.08, duration: 0.5, ease: EASE }}
                  >
                    <span className="mono dim">{l.what}</span>
                    <span className="cell" data-tone={reached && !healed ? (l.tone === 'unknown' ? 'unknown' : undefined) : undefined}>
                      <AnimatePresence mode="popLayout" initial={false}>
                        <motion.span key={healed ? 'h' : 'o'} initial={{ opacity: 0, y: -10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 10 }}>
                          {healed ? l.healed : l.undo}
                        </motion.span>
                      </AnimatePresence>
                    </span>
                    <motion.span className="mark" data-m={mark} key={mark} initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ type: 'spring', stiffness: 400, damping: 14 }}>
                      {mark}
                    </motion.span>
                    <span className="cell" data-tone={reached && l.tone === 'bad' ? 'bad' : undefined}>
                      {l.store}
                    </span>
                  </motion.div>
                );
              })}
              <motion.span key={s.i} className="scan" initial={{ y: '-10%' }} animate={{ y: '110%' }} transition={{ duration: 1.6, ease: 'linear' }} aria-hidden />
            </div>
            <div className="findings">
              <span className="tray-title">Findings</span>
              <AnimatePresence initial={false}>
                {LINES.filter((l) => l.finding && s.at(l.at!)).map((l) => {
                  const [type, sev, end] = l.finding!;
                  const done = l.heal ? s.at(l.heal) : true;
                  return (
                    <motion.div
                      key={type}
                      className="finding"
                      data-sev={sev}
                      layout
                      initial={{ opacity: 0, x: 30 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0 }}
                      transition={{ type: 'spring', stiffness: 220, damping: 22 }}
                    >
                      <code>{type}</code>
                      <small>
                        {sev}, {done ? end : 'open'}
                      </small>
                    </motion.div>
                  );
                })}
              </AnimatePresence>
            </div>
          </div>
        )
      }
    </Chapter>
  );
}
