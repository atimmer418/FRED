import {
  FREQUENCY_FACTOR_HUNDREDTHS,
  InvestmentFrequencyUtils,
  PAYCHECKS_PER_YEAR,
} from './investment-frequency.utils';

describe('InvestmentFrequencyUtils', () => {

  // PARITY: FrequencyFactorParityTest (backend/src/test/java/com/investingapp/backend/model)
  // reads these two tables from source and compares them with InvestmentFrequency.java.
  // If you change one, change both.
  it('pins the client factor table in hundredths', () => {
    expect(FREQUENCY_FACTOR_HUNDREDTHS).toEqual({
      WEEKLY: 433,
      BIWEEKLY: 217,
      SEMI_MONTHLY: 200,
      MONTHLY: 100,
    });
  });

  it('pins paychecks per year', () => {
    expect(PAYCHECKS_PER_YEAR).toEqual({
      WEEKLY: 52,
      BIWEEKLY: 26,
      SEMI_MONTHLY: 24,
      MONTHLY: 12,
    });
  });

  it('parses trimmed, case-insensitive keys and the SEMIMONTHLY alias', () => {
    expect(InvestmentFrequencyUtils.parse(' weekly ')).toBe('WEEKLY');
    expect(InvestmentFrequencyUtils.parse('Biweekly')).toBe('BIWEEKLY');
    expect(InvestmentFrequencyUtils.parse('semimonthly')).toBe('SEMI_MONTHLY');
    expect(InvestmentFrequencyUtils.parse('SEMI_MONTHLY')).toBe('SEMI_MONTHLY');
    expect(InvestmentFrequencyUtils.parse('QUARTERLY')).toBeNull();
    expect(InvestmentFrequencyUtils.parse('')).toBeNull();
    expect(InvestmentFrequencyUtils.parse(null)).toBeNull();
    expect(InvestmentFrequencyUtils.parse(undefined)).toBeNull();
  });

  it('converts WEEKLY with the backend factor x4.33', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(100, 'WEEKLY')).toBe(433);
  });

  it('converts BIWEEKLY with x2.17 (backend parity, not the old my-profile 2.16)', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(100, 'BIWEEKLY')).toBe(217);
  });

  it('converts semi-monthly with x2, accepting both spellings', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(250, 'SEMI_MONTHLY')).toBe(500);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(250, 'semimonthly')).toBe(500);
  });

  it('passes monthly through unchanged and is case-insensitive', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, 'monthly')).toBe(500);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, 'weekly')).toBe(2165);
  });

  it('rounds to cents like the backend setScale(2, HALF_UP)', () => {
    // 115.47 x 4.33 = 499.9851, rounded to 499.99
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(115.47, 'WEEKLY')).toBe(499.99);
    // Halfway-cent boundary: 15.5 x 4.33 = 67.115, HALF_UP gives 67.12
    // (naive float Math.round gives 67.11, the exact bug this guards)
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(15.5, 'WEEKLY')).toBe(67.12);
    // Halfway-cent boundary for biweekly: 12.5 x 2.17 = 27.125, rounded to 27.13
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(12.5, 'BIWEEKLY')).toBe(27.13);
  });

  it('returns 0 for zero/negative amounts; unknown or missing frequency defaults to monthly', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(0, 'WEEKLY')).toBe(0);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(-5, 'WEEKLY')).toBe(0);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, 'QUARTERLY')).toBe(500);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, undefined)).toBe(500);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, null)).toBe(500);
  });
});
