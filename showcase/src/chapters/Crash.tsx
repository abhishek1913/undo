import { AnimatePresence, motion } from 'motion/react';
import { Actor, Chapter, EASE, Pill, type Beat } from '../film';

const BEATS: Beat[] = [
  { id: 'd-work', theme: 'dark', title: 'A worker is halfway through three calls.', sub: 'A refund, some store credit and an email, already on their way to the store.' },
  { id: 'd-kill', theme: 'dark', title: 'kill -9.', sub: 'The process dies mid-call. No goodbye, no cleanup.' },
  { id: 'd-restart', theme: 'dark', title: 'The job restarts from the top.', sub: 'It has no record of what already reached the store.' },
  { id: 'd-twice', theme: 'dark', title: 'Everything happens twice.', sub: 'Two refunds. Two store credits. Two emails.' },
  { id: 'l-lease', theme: 'light', title: 'With Undo, each worker holds a lease.', sub: '30 seconds, plus a fence number: 7. Every write must carry it.' },
  { id: 'l-kill', theme: 'light', title: 'kill -9.', sub: 'Worker 1 dies mid-call. Again.' },
  { id: 'l-expire', theme: 'light', title: 'The lease runs out.', sub: 'Nobody knows what reached the store, so all three actions become UNKNOWN.' },
  { id: 'l-claim', theme: 'light', title: 'Worker 2 takes over. Fence 8.', sub: 'Each kind of action is now resolved the way that is safe for it.' },
  { id: 'l-refund', theme: 'light', title: 'The refund has an idempotency key.', sub: 'Sent again with the same key. The store returns the first answer.' },
  { id: 'l-credit', theme: 'light', title: 'The store credit carries a reference.', sub: 'Undo looks it up, finds it was applied, and sends nothing.' },
  { id: 'l-email', theme: 'light', title: 'An email can’t be checked or deduped.', sub: 'So Undo never resends it blindly. A person decides.' },
  { id: 'l-zombie', theme: 'light', title: 'Then worker 1 wakes up.', sub: 'It tries to write with fence 7. Rejected: the fence is 8 now.' },
  { id: 'l-proof', theme: 'light', title: 'The real test: 30 kills, 0 duplicates.', sub: 'make chaos killed workers 30 times in 10 minutes. Recovery always started within 30.4 seconds.' },
];

const ACTS = [
  { n: 610, what: 'Refund ₹2,400', cls: 'idempotency key', beat: 'l-refund', end: 'SUCCEEDED', how: 'same key, first answer' },
  { n: 611, what: 'Store credit ₹500', cls: 'lookup by reference', beat: 'l-credit', end: 'SUCCEEDED', how: 'found by lookup' },
  { n: 612, what: 'Email “refund sent”', cls: 'no dedupe, no lookup', beat: 'l-email', end: 'NEEDS_REVIEW', how: 'a person checks' },
];

function Lease({ fence, left, slow }: { fence: number | null; left: number; slow?: boolean }) {
  return (
    <div className="lease">
      <span className="mono">{fence === null ? 'no lease' : `fence ${fence}`}</span>
      <span className="lease-bar">
        <motion.i initial={false} animate={{ scaleX: left }} transition={{ duration: slow ? 2.4 : 0.6, ease: slow ? 'linear' : EASE }} />
      </span>
    </div>
  );
}

export default function Crash() {
  return (
    <Chapter id="story-4" label="Story 4 — The worker that died mid-call" beats={BEATS}>
      {(s) => {
        const killed = s.dark ? s.at('d-kill') : s.at('l-kill') && !s.at('l-zombie');
        const w2 = !s.dark && s.at('l-claim');
        const doubled = s.dark && s.at('d-twice');
        const status = (a: (typeof ACTS)[number]) => {
          if (s.dark) return s.at('d-restart') ? 'EXECUTING' : s.at('d-kill') ? '???' : 'EXECUTING';
          if (s.at(a.beat)) return a.end;
          if (s.at('l-expire')) return 'UNKNOWN';
          return 'EXECUTING';
        };
        return (
          <div className="crash">
            <AnimatePresence>
              {s.is('d-kill', 'l-kill') && <motion.div key={s.beat.id} className="flash" initial={{ opacity: 0.9 }} animate={{ opacity: 0 }} transition={{ duration: 1.2 }} />}
            </AnimatePresence>

            <div className="workers">
              <div className="worker" data-dead={killed}>
                <Actor icon="worker" title="Worker 1" sub={killed ? 'killed' : s.is('l-zombie', 'l-proof') ? 'back from a pause' : 'running'} dead={killed} />
                {!s.dark && <Lease fence={s.at('l-expire') && !s.at('l-zombie') ? null : 7} left={s.at('l-expire') ? 0 : s.at('l-kill') ? 0.55 : 0.75} slow={s.is('l-expire')} />}
                <AnimatePresence>
                  {s.is('d-kill', 'l-kill') && (
                    <motion.b
                      className="kill"
                      initial={{ opacity: 0, scale: 2.2, rotate: -14 }}
                      animate={{ opacity: 1, scale: 1, rotate: -8 }}
                      exit={{ opacity: 0 }}
                      transition={{ type: 'spring', stiffness: 380, damping: 16 }}
                    >
                      kill -9
                    </motion.b>
                  )}
                  {s.is('l-zombie') && (
                    <motion.span className="zombie" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ delay: 0.3 }}>
                      write with fence 7 <b>rejected</b>
                    </motion.span>
                  )}
                </AnimatePresence>
              </div>
              <div className="worker" data-idle={s.dark ? !s.at('d-restart') : !w2}>
                {s.dark ? (
                  <Actor icon="worker" title="Job restart" sub={s.at('d-restart') ? 'from the top' : 'waiting'} />
                ) : (
                  <>
                    <Actor icon="worker" title="Worker 2" sub={w2 ? 'took over' : 'idle'} />
                    <Lease fence={w2 ? 8 : null} left={w2 ? 1 : 0} />
                  </>
                )}
              </div>
            </div>

            <ul className="acts">
              {ACTS.map((a) => {
                const st = status(a);
                return (
                  <motion.li key={a.n} className="act" data-hot={!s.dark && s.is(a.beat)} layout>
                    <span className="mono dim">act {a.n}</span>
                    <span className="grow">
                      {a.what}
                      <small>{a.cls}</small>
                    </span>
                    <span className="act-end">
                      <Pill s={st} tone={st === '???' ? 'bad' : undefined} />
                      {!s.dark && s.at(a.beat) && <small>{a.how}</small>}
                    </span>
                  </motion.li>
                );
              })}
            </ul>

            <div className="tally">
              <span className="tray-title">At the store</span>
              {['refund', 'store credit', 'email'].map((k) => (
                <div key={k} className="tally-row" data-bad={doubled}>
                  <motion.b key={doubled ? 2 : 1} initial={{ scale: 1.8, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={{ type: 'spring', stiffness: 300, damping: 15 }}>
                    {doubled ? 2 : 1}
                  </motion.b>
                  {doubled ? `${k}s` : k}
                </div>
              ))}
            </div>
          </div>
        );
      }}
    </Chapter>
  );
}
