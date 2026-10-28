export type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'unknown' | 'muted';

/** Status → color token (Section 20.2). Always shown with the word, never color alone. */
export function toneOf(status: string | null | undefined): Tone {
  switch (status) {
    case 'SUCCEEDED':
    case 'DONE':
    case 'COMPLETED':
    case 'REVERTED':
    case 'APPROVED_OK':
    case 'RESOLVED':
    case 'AUTO_HEALED':
    case 'INFO_OK':
      return 'ok';
    case 'AWAITING_APPROVAL':
    case 'RETRY_WAIT':
    case 'WARNING':
    case 'PENDING':
    case 'FROZEN':
    case 'PARTIALLY_REVERTED':
    case 'CONFLICT':
    case 'BECAME_IRREVERSIBLE':
    case 'IRREVERSIBLE':
    case 'ACKNOWLEDGED':
    case 'PREVIEW':
      return 'warn';
    case 'DENIED':
    case 'FAILED':
    case 'CRITICAL':
    case 'REJECTED':
    case 'OPEN':
      return 'bad';
    case 'EXECUTING':
    case 'ACTIVE':
    case 'REVERTING':
    case 'APPROVED':
    case 'IN_PROGRESS':
    case 'INFO':
    case 'COMPENSATE':
      return 'info';
    case 'UNKNOWN':
    case 'NEEDS_REVIEW':
    case 'BLOCKED_IN_FLIGHT':
    case 'BLOCKED_NEEDS_REVIEW':
      return 'unknown';
    default:
      return 'muted';
  }
}

export const toneText: Record<Tone, string> = {
  ok: 'text-ok',
  warn: 'text-warn',
  bad: 'text-bad',
  info: 'text-info',
  unknown: 'text-unknown',
  muted: 'text-muted',
};

export const toneBg: Record<Tone, string> = {
  ok: 'bg-ok',
  warn: 'bg-warn',
  bad: 'bg-bad',
  info: 'bg-info',
  unknown: 'bg-unknown',
  muted: 'bg-muted',
};
