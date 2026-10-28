import { useEffect, useRef } from 'react';

export type UndoEvent = {
  seq: number;
  type: string;
  run_id: string | null;
  action_id: string | null;
  at: string;
  data: Record<string, unknown>;
};

/**
 * Live events over SSE (FR-EVT-2). The browser resends Last-Event-ID on reconnect, so the server
 * resumes exactly where the stream stopped.
 */
export function useEventStream(
  opts: { run?: number | null; afterSeq?: number | null; enabled?: boolean },
  onEvent: (e: UndoEvent) => void,
) {
  const handler = useRef(onEvent);
  handler.current = onEvent;
  const { run, afterSeq, enabled = true } = opts;
  useEffect(() => {
    if (!enabled || afterSeq === undefined) return;
    const params = new URLSearchParams();
    if (run) params.set('run', String(run));
    if (afterSeq !== null) params.set('after_seq', String(afterSeq));
    const es = new EventSource(`/api/stream?${params.toString()}`);
    const listener = (ev: MessageEvent) => {
      try {
        handler.current(JSON.parse(ev.data) as UndoEvent);
      } catch {
        // ignore malformed frames
      }
    };
    const types = [
      'run.created',
      'run.completed',
      'run.frozen',
      'run.failed',
      'run.reverting',
      'run.reverted',
      'run.partially_reverted',
      'task.started',
      'task.done',
      'task.failed',
      'llm.turn',
      'action.proposed',
      'action.decided',
      'action.duplicate',
      'approval.requested',
      'approval.approved',
      'approval.rejected',
      'approval.expired',
      'approval.cancelled',
      'action.executing',
      'action.retry_wait',
      'action.unknown',
      'action.succeeded',
      'action.failed',
      'action.needs_review',
      'action.cancelled',
      'action.reviewed',
      'provider.status_changed',
      'webhook.received',
      'revert.previewed',
      'revert.cancelled',
      'revert.started',
      'revert.item_updated',
      'revert.completed',
      'recon.started',
      'recon.completed',
      'recon.finding',
      'recon.finding_updated',
      'budget.denied',
    ];
    types.forEach((t) => es.addEventListener(t, listener));
    es.onmessage = listener;
    return () => es.close();
  }, [run, afterSeq, enabled]);
}
