import {
  audienceLabel,
  formatSavings,
  fundSavingsRange,
  kidYearBounds,
  kidYearLabel,
} from './goal-quiz.utils';

const NOW = new Date(2026, 10, 15); // 15 Nov 2026

/** The spec's sample family: born 2024, expected 2028, not yet dated. */
const SAMPLE = [
  { birthYear: 2024, birthMonth: null },
  { birthYear: 2028, birthMonth: null },
  { birthYear: null, birthMonth: null },
];

describe('fundSavingsRange', () => {
  it('baby savings sums the unborn kids rank targets: born 2024, 2028, undated -> 37300', () => {
    expect(fundSavingsRange('baby', SAMPLE, NOW, 0.10)).toEqual({ low: 37_300, high: 37_300 });
    expect(formatSavings(fundSavingsRange('baby', SAMPLE, NOW, 0.10))).toBe('$37K');
  });

  it('baby savings is 0 when every kid is born', () => {
    expect(fundSavingsRange('baby', [{ birthYear: 2020, birthMonth: null }], NOW, 0.10)).toEqual({ low: 0, high: 0 });
  });

  // Ruling R1: the sample family's College range is "$55K to $135K" (exact sums 53,100 / 137,400
  // under the spec's rounding rule). The spec and the mockup carry the same figure.
  it('college savings for the sample family is the spec pin $55K to $135K', () => {
    expect(formatSavings(fundSavingsRange('college', SAMPLE, NOW, 0.10))).toBe('$55K to $135K');
  });

  it('college savings for the sample family computes to 55K to 135K and nest 55K to 275K', () => {
    expect(fundSavingsRange('college', SAMPLE, NOW, 0.10)).toEqual({ low: 55_000, high: 135_000 });
    expect(fundSavingsRange('nest', SAMPLE, NOW, 0.10)).toEqual({ low: 55_000, high: 275_000 });
    expect(formatSavings(fundSavingsRange('college', SAMPLE, NOW, 0.10))).toBe('$55K to $135K');
    expect(formatSavings(fundSavingsRange('nest', SAMPLE, NOW, 0.10))).toBe('$55K to $275K');
  });

  it('rounds under $20,000 to the nearest $1,000 and from $20,000 to the nearest $5,000', () => {
    // One undated kid, 212 months at 10%: 83 x 212 = 17,596 and 215 x 212 = 45,580.
    expect(fundSavingsRange('college', [{ birthYear: null, birthMonth: null }], NOW, 0.10))
      .toEqual({ low: 18_000, high: 45_000 });
  });

  it('counts nothing for a kid already past 18', () =>
    expect(fundSavingsRange('nest', [{ birthYear: 2008, birthMonth: 6 }], NOW, 0.10)).toEqual({ low: 0, high: 0 }));
});

describe('formatSavings', () => {
  it('formatSavings collapses equal ends and uses K and M', () => {
    expect(formatSavings({ low: 37_300, high: 37_300 })).toBe('$37K');
    expect(formatSavings({ low: 50_000, high: 135_000 })).toBe('$50K to $135K');
    expect(formatSavings({ low: 1_200_000, high: 1_200_000 })).toBe('$1.2M');
    expect(formatSavings({ low: 1_000_000, high: 1_000_000 })).toBe('$1M');
    expect(formatSavings({ low: 275_000, high: 1_250_000 })).toBe('$275K to $1.3M');
  });
  it('shows one figure when the ends format the same', () =>
    expect(formatSavings({ low: 37_300, high: 37_400 })).toBe('$37K'));
});

describe('audienceLabel', () => {
  it('audienceLabel: one kid uses its label, two is both kids, three is all 3 kids', () => {
    expect(audienceLabel([{ label: 'Ellie' }])).toBe('for Ellie');
    expect(audienceLabel([{ label: '  ' }])).toBe('for your kid');
    expect(audienceLabel([{ label: 'A' }, { label: 'B' }])).toBe('for both kids');
    expect(audienceLabel([{ label: 'A' }, { label: 'B' }, { label: 'C' }])).toBe('for all 3 kids');
  });
});

describe('kidYearBounds and kidYearLabel', () => {
  it('kidYearBounds follows the clock: 2026 -> 2009..2036, 2027 -> 2010..2037', () => {
    expect(kidYearBounds(NOW)).toEqual({ min: 2009, max: 2036 });
    expect(kidYearBounds(new Date(2027, 0, 2))).toEqual({ min: 2010, max: 2037 });
  });
  it('labels a year relative to now', () => {
    expect(kidYearLabel(null, NOW)).toBe('Not sure');
    expect(kidYearLabel(2024, NOW)).toBe('Born 2024');
    expect(kidYearLabel(2026, NOW)).toBe('This year');
    expect(kidYearLabel(2028, NOW)).toBe('2028');
  });
});
