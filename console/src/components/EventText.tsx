import { Link } from 'react-router-dom';
import type { EventRow } from '../lib/types';
import { money } from '../lib/format';
import { StatusPill } from './ui';

/** "action.succeeded" → "succeeded"; "recon.finding_updated" → "recon finding updated". */
function words(type: string) {
  return type.replace(/^(action|run|task)\./, '').replace(/[._]/g, ' ');
}

/**
 * One line describing an event (data always carries what is needed, Section 10.2). `steps` is how
 * many events of the same action were folded into this line (the overview shows each action once).
 */
export function EventText({ e, steps = 1 }: { e: EventRow; steps?: number }) {
  const d = e.data ?? {};
  if (e.type.startsWith('run.')) {
    return (
      <span className="min-w-0">
        <Link
          className="font-semibold underline-offset-2 hover:underline"
          to={`/runs/${d.run_number}`}
        >
          Run #{d.run_number}
        </Link>{' '}
        {words(e.type)}
        {d.title ? <span className="text-ink-2"> · {d.title}</span> : null}
      </span>
    );
  }
  if (e.type.startsWith('task.')) {
    const subject = d.ticket_id ?? d.order_id;
    return (
      <span className="text-ink-2">
        {subject ? `${subject} ` : 'Task '}
        {words(e.type)}
      </span>
    );
  }
  if (e.type === 'llm.turn') {
    const subject = d.ticket_id ?? d.order_id;
    return (
      <span className="text-ink-2">
        Model turn {d.turn_no}
        {subject ? ` on ${subject}` : ''} · {d.stop_reason}
      </span>
    );
  }
  if (d.action_number) {
    return (
      <span className="inline-flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-0.5">
        <Link className="font-mono text-[12px] hover:underline" to={`/actions/${d.action_number}`}>
          {d.action}
        </Link>
        <span>{d.tool}</span>
        {d.amount_minor ? <span className="tabular-nums">{money(d.amount_minor)}</span> : null}
        <StatusPill status={String(d.status ?? e.type)} />
        {steps > 1 && <span className="text-[12px] text-ink-2">{steps} steps</span>}
      </span>
    );
  }
  return <span className="text-ink-2">{words(e.type)}</span>;
}
