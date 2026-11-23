import {
  CAR_LOAN_RATE_DEFAULT_PCT,
  CAR_LOAN_TERM_MONTHS,
  LOAN_RATE_SOURCES,
  MORTGAGE_RATE_DEFAULT_PCT,
  MORTGAGE_TERM_MONTHS,
} from '../constants/loan-rates';
import { loanLine, loanPayment, loanSourceLine } from './loan.utils';

describe('loanPayment', () => {
  // Hand check: r = 6 / 100 / 12 = 0.005, (1.005)^360 ~= 6.0226,
  // payment = 100,000 x 0.005 / (1 - 1 / 6.0226) ~= 599.55, nearest dollar 600.
  it('is the standard amortized payment, nearest dollar', () => {
    expect(loanPayment(100_000, 6, 360)).toBe(600);
  });

  it('$617,500 at 6.5% over 360 months is $3,903', () => {
    // r = 0.065 / 12, payment ~= 3,903.02
    expect(loanPayment(617_500, 6.5, 360)).toBe(3903);
  });

  it('$26,000 at 7.5% over 60 months is $521', () => {
    // r = 0.075 / 12 = 0.00625, payment ~= 520.99
    expect(loanPayment(26_000, 7.5, 60)).toBe(521);
  });

  it('0% divides evenly', () => {
    expect(loanPayment(12_000, 0, 60)).toBe(200);
    expect(loanPayment(10_000, 0, 3)).toBe(3333);
  });

  it('principal 0 is 0', () => {
    expect(loanPayment(0, 6.5, 360)).toBe(0);
    expect(loanPayment(-5_000, 6.5, 360)).toBe(0);
  });

  it('months 0 is 0', () => {
    expect(loanPayment(100_000, 6, 0)).toBe(0);
    expect(loanPayment(100_000, 0, 0)).toBe(0);
  });
});

describe('loanLine', () => {
  it('house wording: price less the down payment, over 30 years', () => {
    expect(loanLine('HOUSE', 650_000, 32_500, 6.5)).toBe(
      'Then the mortgage: ~$3,903 a month at 6.5% over 30 years, before taxes and insurance.',
    );
  });

  it('car wording: price less the down payment, over 60 months', () => {
    expect(loanLine('CAR', 30_000, 4_000, 7.5)).toBe('Then the loan: ~$521 a month at 7.5% over 60 months.');
  });

  it('shows the rate with up to 3 decimals, trimmed', () => {
    expect(loanLine('HOUSE', 650_000, 32_500, 6.125)).toBe(
      'Then the mortgage: ~$3,752 a month at 6.125% over 30 years, before taxes and insurance.',
    );
    expect(loanLine('CAR', 30_000, 4_000, 7)).toBe('Then the loan: ~$515 a month at 7% over 60 months.');
    expect(loanLine('CAR', 30_000, 4_000, 7.12345)).toContain('at 7.123% over');
  });

  it('a down payment that covers the price leaves no payment', () => {
    expect(loanLine('CAR', 20_000, 25_000, 7.5)).toBe('Then the loan: ~$0 a month at 7.5% over 60 months.');
  });
});

describe('loan rate constants', () => {
  it('terms are 30 years and 60 months', () => {
    expect(MORTGAGE_TERM_MONTHS).toBe(360);
    expect(CAR_LOAN_TERM_MONTHS).toBe(60);
  });

  it('the sourced defaults are finite positive rates below 30%', () => {
    for (const rate of [MORTGAGE_RATE_DEFAULT_PCT, CAR_LOAN_RATE_DEFAULT_PCT]) {
      expect(Number.isFinite(rate)).toBeTrue();
      expect(rate).toBeGreaterThan(0);
      expect(rate).toBeLessThan(30);
    }
  });

  it('every source has a name, period, url and usedIn, one per goal kind', () => {
    expect(LOAN_RATE_SOURCES.length).toBe(2);
    for (const s of LOAN_RATE_SOURCES) {
      expect(s.name.length).toBeGreaterThan(0);
      expect(s.period.length).toBeGreaterThan(0);
      expect(s.url).toMatch(/^https:\/\//);
      expect(['HOUSE', 'CAR']).toContain(s.usedIn);
    }
    expect(LOAN_RATE_SOURCES.map((s) => s.usedIn).sort()).toEqual(['CAR', 'HOUSE']);
  });
});

describe('loanSourceLine', () => {
  it('names the default rate and its source, built from the constants', () => {
    expect(loanSourceLine('HOUSE')).toBe('Default rate: Freddie Mac PMMS, 7.28% as of November 19, 2026.');
    expect(loanSourceLine('CAR')).toBe('Default rate: Federal Reserve G.19, 7.54% for 2026 Q3 (preliminary).');
  });
});
