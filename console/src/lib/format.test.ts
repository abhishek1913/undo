import { describe, expect, it } from 'vitest';
import { money, shortHash } from './format';
import { toneOf } from './status';

describe('format', () => {
  it('formats money Indian style from paise', () => {
    expect(money(12345600)).toBe('₹1,23,456.00');
    expect(money(7800000)).toBe('₹78,000.00');
    expect(money(null)).toBe('—');
  });

  it('shortens hashes to first and last four', () => {
    expect(shortHash('9c1e' + '0'.repeat(56) + 'f02a')).toBe('9c1e…f02a');
  });

  it('maps statuses to the PRD color tokens', () => {
    expect(toneOf('SUCCEEDED')).toBe('ok');
    expect(toneOf('AWAITING_APPROVAL')).toBe('warn');
    expect(toneOf('DENIED')).toBe('bad');
    expect(toneOf('EXECUTING')).toBe('info');
    expect(toneOf('NEEDS_REVIEW')).toBe('unknown');
    expect(toneOf('CANCELLED')).toBe('muted');
  });
});
