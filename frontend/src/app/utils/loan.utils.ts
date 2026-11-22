import {
  CAR_LOAN_RATE_DEFAULT_PCT,
  CAR_LOAN_TERM_MONTHS,
  LOAN_RATE_SOURCES,
  MORTGAGE_RATE_DEFAULT_PCT,
  MORTGAGE_TERM_MONTHS,
} from '../constants/loan-rates';
import { fmtMoney } from './goal-plan.utils';

/**
 * Standard amortized monthly payment, nearest dollar: P x r / (1 - (1 + r)^-n) with
 * r = annualRatePct / 100 / 12. A 0% rate divides the principal evenly. No principal or no
 * months is a 0 payment. FRED's own arithmetic, never a published figure.
 */
export function loanPayment(principal: number, annualRatePct: number, months: number): number {
  if (!(principal > 0) || !(months > 0)) return 0;
  if (annualRatePct === 0) return Math.round(principal / months);
  const r = annualRatePct / 100 / 12;
  return Math.round((principal * r) / (1 - Math.pow(1 + r, -months)));
}

/** A rate with up to 3 decimals, trailing zeros trimmed: 6.5, 6.125, 7. */
function fmtRate(ratePct: number): string {
  return String(Number(ratePct.toFixed(3)));
}

/**
 * The line under the down payment that shows the loan that follows it:
 * "Then the mortgage: ~$3,903 a month at 6.5% over 30 years, before taxes and insurance." or
 * "Then the loan: ~$521 a month at 7.5% over 60 months."
 */
export function loanLine(kind: 'HOUSE' | 'CAR', price: number, down: number, ratePct: number): string {
  const principal = price - down;
  const rate = fmtRate(ratePct);
  if (kind === 'HOUSE') {
    const monthly = fmtMoney(loanPayment(principal, ratePct, MORTGAGE_TERM_MONTHS));
    return `Then the mortgage: ~${monthly} a month at ${rate}% over ${MORTGAGE_TERM_MONTHS / 12} years, before taxes and insurance.`;
  }
  const monthly = fmtMoney(loanPayment(principal, ratePct, CAR_LOAN_TERM_MONTHS));
  return `Then the loan: ~${monthly} a month at ${rate}% over ${CAR_LOAN_TERM_MONTHS} months.`;
}

/**
 * The source sentence under the loan line. It describes the DEFAULT rate, never the rate shown (a
 * saved or slid rate is FRED's, not the publisher's): "Default rate: Freddie Mac PMMS, 7.28% as of
 * November 19, 2026." Null when no source matches the type.
 */
export function loanSourceLine(kind: 'HOUSE' | 'CAR'): string | null {
  const source = LOAN_RATE_SOURCES.find((s) => s.usedIn === kind);
  if (!source) return null;
  const pct = kind === 'HOUSE' ? MORTGAGE_RATE_DEFAULT_PCT : CAR_LOAN_RATE_DEFAULT_PCT;
  return `Default rate: ${source.shortName}, ${fmtRate(pct)}% ${source.period}.`;
}
