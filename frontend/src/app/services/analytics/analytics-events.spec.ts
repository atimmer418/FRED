import { CLIENT_EVENT_NAMES, EVENT_NAMES_ARE_COMPLETE, SERVER_EVENT_NAMES } from './analytics-events';
import { toAmountBand, toCountBand, toPercentBand, toYearsBand } from './analytics-bands';

/**
 * The taxonomy is the main guard on what leaves the device, so its conventions are pinned here
 * rather than left to review. Renaming an event breaks every insight built on it, which is why
 * the naming rules are enforced mechanically.
 */
describe('client event taxonomy', () => {
  it('keeps the runtime name list in step with the typed union', () => {
    // The compiler proves this too; the assertion exists so the proof is visible in the suite.
    expect(EVENT_NAMES_ARE_COMPLETE).toBeTrue();
    expect(CLIENT_EVENT_NAMES.length).toBeGreaterThan(0);
  });

  it('has no duplicate names', () => {
    expect(new Set(CLIENT_EVENT_NAMES).size).toBe(CLIENT_EVENT_NAMES.length);
  });

  it('uses snake_case throughout', () => {
    for (const name of CLIENT_EVENT_NAMES) {
      expect(name).withContext(name).toMatch(/^[a-z][a-z0-9]*(_[a-z0-9]+)+$/);
    }
  });

  it('names every event object_action, in the past tense', () => {
    // The vocabulary is deliberately closed: a new verb is a decision, not an accident.
    const allowedVerbs = [
      'opened',
      'viewed',
      'completed',
      'selected',
      'changed',
      'acted',
      'shown',
    ];
    for (const name of CLIENT_EVENT_NAMES) {
      const verb = name.split('_').pop();
      expect(allowedVerbs)
        .withContext(`${name} ends in "${verb}", which is not one of the agreed verbs`)
        .toContain(verb as string);
    }
  });

  it('keeps money, compliance and billing names out of the client taxonomy', () => {
    // These are server-side facts. A client-side version would be a second, wrong answer.
    const reserved = [
      'deposit',
      'order',
      'withdrawal',
      'transfer',
      'kyc',
      'subscription',
      'purchase',
      'payment',
      'account_closed',
      'recurring',
    ];
    for (const name of CLIENT_EVENT_NAMES) {
      for (const word of reserved) {
        expect(name.includes(word))
          .withContext(`${name} contains "${word}", which belongs to the server-side taxonomy`)
          .toBeFalse();
      }
    }
  });
});

describe('server event names', () => {
  it('never collides with a client event name', () => {
    // The whole point of two lists: one fact, one emitter. A name on both sides would be counted
    // twice, by two systems that disagree about what it means.
    const client = new Set<string>(CLIENT_EVENT_NAMES);
    for (const name of SERVER_EVENT_NAMES) {
      expect(client.has(name)).withContext(`${name} is on both lists`).toBeFalse();
    }
  });

  it('has no duplicates and uses the same naming rules as the client', () => {
    expect(new Set(SERVER_EVENT_NAMES).size).toBe(SERVER_EVENT_NAMES.length);
    for (const name of SERVER_EVENT_NAMES) {
      expect(name).withContext(name).toMatch(/^[a-z][a-z0-9]*(_[a-z0-9]+)+$/);
    }
  });

  it('is where the money and compliance words are allowed to live', () => {
    // The mirror of the client rule above: these words belong on this list and nowhere else.
    const joined = SERVER_EVENT_NAMES.join(' ');
    for (const word of ['kyc', 'transfer', 'subscription', 'plaid', 'passkey']) {
      expect(joined.includes(word)).withContext(`no server event mentions ${word}`).toBeTrue();
    }
  });
});

describe('analytics bands', () => {
  it('buckets money without ever revealing the amount', () => {
    expect(toAmountBand(0)).toBe('under_50');
    expect(toAmountBand(49.99)).toBe('under_50');
    expect(toAmountBand(50)).toBe('50_99');
    expect(toAmountBand(249)).toBe('100_249');
    expect(toAmountBand(250)).toBe('250_499');
    expect(toAmountBand(2_000)).toBe('1k_2499');
    expect(toAmountBand(7_500)).toBe('5k_9999');
    expect(toAmountBand(1_500_000)).toBe('1m_plus');
  });

  it('puts boundary values in the higher bucket', () => {
    expect(toAmountBand(99)).toBe('50_99');
    expect(toAmountBand(100)).toBe('100_249');
    expect(toAmountBand(999)).toBe('500_999');
    expect(toAmountBand(1_000)).toBe('1k_2499');
  });

  it('never throws on broken input, because analytics must not break a flow', () => {
    expect(toAmountBand(Number.NaN)).toBe('under_50');
    expect(toAmountBand(-100)).toBe('under_50');
    expect(toAmountBand(Number.POSITIVE_INFINITY)).toBe('1m_plus');
    expect(toCountBand(Number.NaN)).toBe('0');
    expect(toPercentBand(Number.NaN)).toBe('0_9');
  });

  it('buckets counts', () => {
    expect(toCountBand(0)).toBe('0');
    expect(toCountBand(1)).toBe('1');
    expect(toCountBand(3)).toBe('2_3');
    expect(toCountBand(9)).toBe('4_9');
    expect(toCountBand(42)).toBe('10_plus');
  });

  it('buckets years, and treats an unreachable date as never rather than a number', () => {
    expect(toYearsBand(0.5)).toBe('under_1');
    expect(toYearsBand(2)).toBe('1_2');
    expect(toYearsBand(7)).toBe('6_10');
    expect(toYearsBand(25)).toBe('over_20');
    expect(toYearsBand(null)).toBe('never');
    expect(toYearsBand(Number.POSITIVE_INFINITY)).toBe('never');
  });

  it('buckets percentages, with 100 kept distinct from almost-100', () => {
    expect(toPercentBand(0)).toBe('0_9');
    expect(toPercentBand(99.9)).toBe('75_99');
    expect(toPercentBand(100)).toBe('100');
  });
});
