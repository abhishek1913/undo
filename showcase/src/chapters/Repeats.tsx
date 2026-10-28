import { Cast, Chapter, Icon, Item, Items, type Packet, Pill, TrayTitle, type Beat } from '../film';

/* ---------------- Story 1: the answer that never came back ---------------- */

const LOST: Beat[] = [
  { id: 'd-ask', theme: 'dark', title: 'The agent asks for a refund.', sub: '₹2,400 on order SF-1042, sent straight to the payments store.' },
  { id: 'd-pay', theme: 'dark', title: 'The store pays it.', sub: 'A refund is created. The money has left.' },
  { id: 'd-lost', theme: 'dark', title: 'The answer never comes back.', sub: 'The network drops the reply. The agent can’t tell that it worked.' },
  { id: 'd-retry', theme: 'dark', title: 'So it tries again.', sub: 'Agent frameworks retry when they hear nothing. That is the default.' },
  { id: 'd-twice', theme: 'dark', title: 'Refunded twice.', sub: 'The store can’t tell a retry from a new request. ₹4,800 is gone instead of ₹2,400.' },
  { id: 'l-undo', theme: 'light', title: 'Now put Undo in the middle.', sub: 'Every tool call goes through it. Nothing reaches the store any other way.' },
  { id: 'l-intent', theme: 'light', title: 'First, Undo writes down the intent.', sub: 'Before anyone is called, the action is saved with a stable idempotency key: k7f3.' },
  { id: 'l-send', theme: 'light', title: 'Then it calls the store, with the key.', sub: 'The key travels with the request.' },
  { id: 'l-pay', theme: 'light', title: 'The store pays, and remembers the key.' },
  { id: 'l-lost', theme: 'light', title: 'The answer is lost again.', sub: 'Same network, same bad luck.' },
  { id: 'l-unknown', theme: 'light', title: 'Undo doesn’t guess.', sub: 'The action is marked UNKNOWN. Not failed, and never retried blindly.' },
  { id: 'l-resend', theme: 'light', title: 'Same request. Same key.', sub: 'Undo’s resolver sends it again, still carrying k7f3.' },
  { id: 'l-first', theme: 'light', title: 'The store answers with the first result.', sub: 'It has seen k7f3 before, so it makes no new refund.' },
  { id: 'l-once', theme: 'light', title: 'Exactly once.', sub: 'One refund. The ledger says SUCCEEDED, and the agent finally gets its answer.' },
];

const LOST_PACKETS: Record<string, Packet> = {
  'd-ask': { key: '1', from: 0, to: 1, label: 'refund ₹2,400' },
  'd-lost': { key: '2', from: 1, to: 0, label: 'refunded ✓', tone: 'ok', lost: true },
  'd-retry': { key: '3', from: 0, to: 1, label: 'refund ₹2,400' },
  'l-intent': { key: '4', from: 0, to: 0.5, label: 'refund ₹2,400' },
  'l-send': { key: '5', from: 0.5, to: 1, label: 'key k7f3' },
  'l-lost': { key: '6', from: 1, to: 0.5, label: 'refunded ✓', tone: 'ok', lost: true },
  'l-resend': { key: '7', from: 0.5, to: 1, label: 'key k7f3' },
  'l-first': { key: '8', from: 1, to: 0.5, label: 'first answer', tone: 'ok' },
  'l-once': { key: '9', from: 0.5, to: 0, label: 'SUCCEEDED', tone: 'ok' },
};

export function LostAnswer() {
  return (
    <Chapter id="story-1" label="Story 1 — The answer that never came back" beats={LOST}>
      {(s) => {
        const status = s.at('l-once') ? 'SUCCEEDED' : s.at('l-unknown') ? 'UNKNOWN' : s.at('l-send') ? 'EXECUTING' : 'PROPOSED';
        const twice = s.at('d-twice') && s.dark;
        return (
          <Cast
            shot={s}
            undoIn={!s.dark}
            packet={LOST_PACKETS[s.beat.id]}
            trays={[
              <>
                <TrayTitle>Agent</TrayTitle>
                <Items>
                  {s.is('d-lost', 'l-lost', 'l-unknown', 'l-resend', 'l-first') && (
                    <Item key="wait">
                      <span className="spinner" /> waiting for an answer…
                    </Item>
                  )}
                  {s.is('l-once') && (
                    <Item key="got" tone="ok">
                      <Icon name="check" /> refund done
                    </Item>
                  )}
                </Items>
              </>,
              <>
                <TrayTitle>Undo’s ledger</TrayTitle>
                <Items>
                  {s.at('l-intent') && (
                    <Item key="a481" tone={status === 'UNKNOWN' ? 'unknown' : status === 'SUCCEEDED' ? 'ok' : undefined} big>
                      <span className="mono dim">act 481</span>
                      <span className="grow">
                        refund ₹2,400
                        <small>
                          <Icon name="key" /> k7f3
                        </small>
                      </span>
                      <Pill s={status} />
                    </Item>
                  )}
                </Items>
              </>,
              <>
                <TrayTitle>Store receipts</TrayTitle>
                <Items>
                  {(s.dark ? s.at('d-pay') : s.at('l-pay')) && (
                    <Item key={`r1-${s.beat.theme}`} tone={twice ? 'bad' : undefined} big>
                      <span className="mono dim">rf_1</span>
                      <span className="grow">
                        ₹2,400
                        {!s.dark && (
                          <small>
                            {s.at('l-first') ? 'k7f3 seen twice, paid once' : (
                              <>
                                <Icon name="key" /> k7f3
                              </>
                            )}
                          </small>
                        )}
                      </span>
                    </Item>
                  )}
                  {twice && (
                    <Item key="r2" tone="bad" big>
                      <span className="mono dim">rf_2</span>
                      <span className="grow">₹2,400</span>
                      <b className="stamp">PAID TWICE</b>
                    </Item>
                  )}
                </Items>
              </>,
            ]}
          />
        );
      }}
    </Chapter>
  );
}

/* ---------------- Story 2: the agent that asks twice ---------------- */

const TWICE: Beat[] = [
  { id: 'd-ask', theme: 'dark', title: 'The model asks for a refund.', sub: '₹1,150 on order SF-2210.' },
  { id: 'd-pay', theme: 'dark', title: 'The store pays it.' },
  { id: 'd-again', theme: 'dark', title: 'Two turns later, it asks again.', sub: 'Models repeat themselves. Crashed jobs start over from the top.' },
  { id: 'd-twice', theme: 'dark', title: 'Paid twice. Again.', sub: 'To the store, a repeat looks exactly like a new request.' },
  { id: 'l-key', theme: 'light', title: 'With Undo, every write has a business key.', sub: 'For a refund it is refund:order:{order_id}. Only one active action can hold it.' },
  { id: 'l-pay', theme: 'light', title: 'The first refund goes through.', sub: 'Act 502 holds refund:order:SF-2210.' },
  { id: 'l-again', theme: 'light', title: 'The model asks again.' },
  { id: 'l-match', theme: 'light', title: 'Undo finds the key already taken.', sub: 'The repeat is recorded as act 503, a duplicate of 502.' },
  { id: 'l-reply', theme: 'light', title: 'It answers with the first result.', sub: 'The store is never called again. The agent hears: already refunded, here is act 502.' },
];

const TWICE_PACKETS: Record<string, Packet> = {
  'd-ask': { key: '1', from: 0, to: 1, label: 'refund ₹1,150' },
  'd-again': { key: '2', from: 0, to: 1, label: 'refund ₹1,150' },
  'l-key': { key: '3', from: 0, to: 0.5, label: 'refund ₹1,150' },
  'l-pay': { key: '4', from: 0.5, to: 1, label: 'refund ₹1,150' },
  'l-again': { key: '5', from: 0, to: 0.5, label: 'refund ₹1,150' },
  'l-reply': { key: '6', from: 0.5, to: 0, label: 'duplicate of 502', tone: 'wait' },
};

export function AsksTwice() {
  return (
    <Chapter id="story-2" label="Story 2 — The agent that asks twice" beats={TWICE}>
      {(s) => {
        const second = s.dark ? s.at('d-again') : s.at('l-again');
        return (
          <Cast
            shot={s}
            undoIn={!s.dark}
            packet={TWICE_PACKETS[s.beat.id]}
            trays={[
              <>
                <TrayTitle>What the model said</TrayTitle>
                <Items>
                  <Item key={`c1-${s.beat.theme}`}>
                    <span className="mono grow">issue_refund(SF-2210, 1150)</span>
                  </Item>
                  {second && (
                    <Item key={`c2-${s.beat.theme}`} tone="hot">
                      <span className="mono grow">issue_refund(SF-2210, 1150)</span>
                      <span className="tag">again</span>
                    </Item>
                  )}
                </Items>
              </>,
              <>
                <TrayTitle>Undo’s ledger</TrayTitle>
                <Items>
                  {!s.dark && (
                    <Item key="a502" tone={s.is('l-match') ? 'hot' : s.at('l-pay') ? 'ok' : undefined} big>
                      <span className="mono dim">act 502</span>
                      <span className="grow">
                        refund ₹1,150
                        <small className="mono">refund:order:SF-2210</small>
                      </span>
                      <Pill s={s.at('l-pay') ? 'SUCCEEDED' : 'EXECUTING'} />
                    </Item>
                  )}
                  {s.at('l-match') && (
                    <Item key="a503" big>
                      <span className="mono dim">act 503</span>
                      <span className="grow">
                        same key
                        <small>duplicate of 502</small>
                      </span>
                      <Pill s="DUPLICATE" />
                    </Item>
                  )}
                </Items>
              </>,
              <>
                <TrayTitle>Store receipts</TrayTitle>
                <Items>
                  {(s.dark ? s.at('d-pay') : s.at('l-pay')) && (
                    <Item key={`r1-${s.beat.theme}`} tone={s.dark && s.at('d-twice') ? 'bad' : undefined} big>
                      <span className="mono dim">rf_1</span>
                      <span className="grow">₹1,150</span>
                    </Item>
                  )}
                  {s.dark && s.at('d-twice') && (
                    <Item key="r2" tone="bad" big>
                      <span className="mono dim">rf_2</span>
                      <span className="grow">₹1,150</span>
                      <b className="stamp">PAID TWICE</b>
                    </Item>
                  )}
                </Items>
              </>,
            ]}
          />
        );
      }}
    </Chapter>
  );
}
