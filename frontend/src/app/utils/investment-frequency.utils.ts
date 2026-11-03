/**
 * investment-frequency.utils.ts
 *
 * The client copy of the backend InvestmentFrequency enum
 * (backend/src/main/java/com/investingapp/backend/model/InvestmentFrequency.java).
 *
 * PARITY: FrequencyFactorParityTest reads FREQUENCY_FACTOR_HUNDREDTHS and PAYCHECKS_PER_YEAR
 * from this file and compares them with the enum; investment-frequency.utils.spec.ts pins the
 * same values here. If you change one, change both. The unified Freedom Date depends on both
 * stacks converting a per-paycheck amount to the same monthly number.
 *
 * Factors are stored in hundredths (4.33 becomes 433) so the cents arithmetic in
 * toMonthlyEquivalent stays exact. Assumes cents-quantized amounts (the DB stores
 * investment_amount at scale 2); sub-cent inputs are quantized to cents before conversion.
 */
export type InvestmentFrequencyKey = 'WEEKLY' | 'BIWEEKLY' | 'SEMI_MONTHLY' | 'MONTHLY';

/** Paychecks per month, in hundredths: WEEKLY 4.33, BIWEEKLY 2.17, SEMI_MONTHLY 2, MONTHLY 1. */
export const FREQUENCY_FACTOR_HUNDREDTHS: Record<InvestmentFrequencyKey, number> = {
  WEEKLY: 433,
  BIWEEKLY: 217,
  SEMI_MONTHLY: 200,
  MONTHLY: 100,
};

/** Paychecks per year. */
export const PAYCHECKS_PER_YEAR: Record<InvestmentFrequencyKey, number> = {
  WEEKLY: 52,
  BIWEEKLY: 26,
  SEMI_MONTHLY: 24,
  MONTHLY: 12,
};

/** Legacy spelling still present on older rows and older clients. */
const SEMI_MONTHLY_ALIAS = 'SEMIMONTHLY';

export class InvestmentFrequencyUtils {

  /**
   * Normalizes a raw frequency string to a table key: trims, upper-cases and maps the
   * SEMIMONTHLY alias. Returns null for null, blank or unknown input (mirrors
   * InvestmentFrequency.tryParse on the backend).
   */
  static parse(frequency: string | null | undefined): InvestmentFrequencyKey | null {
    if (!frequency) return null;
    const upper = frequency.trim().toUpperCase();
    const key = upper === SEMI_MONTHLY_ALIAS ? 'SEMI_MONTHLY' : upper;
    return Object.prototype.hasOwnProperty.call(FREQUENCY_FACTOR_HUNDREDTHS, key)
      ? (key as InvestmentFrequencyKey)
      : null;
  }

  /**
   * Converts a per-execution investment amount to its monthly equivalent.
   * Unknown or missing frequency falls back to MONTHLY (factor 1), as before.
   */
  static toMonthlyEquivalent(amount: number, frequency: string | null | undefined): number {
    if (!amount || amount <= 0) return 0;

    const key = InvestmentFrequencyUtils.parse(frequency) ?? 'MONTHLY';
    const factorHundredths = FREQUENCY_FACTOR_HUNDREDTHS[key];

    // Exact integer-cents arithmetic reproduces the backend's
    // BigDecimal setScale(2, HALF_UP) without binary floating-point drift
    // (15.5 x 4.33 = 67.115 must round up to 67.12).
    const cents = Math.round(amount * 100);
    const monthlyCents = Math.floor((cents * factorHundredths + 50) / 100);
    return monthlyCents / 100;
  }

  /**
   * Converts a monthly amount to what one paycheck of the given frequency should carry.
   * Mirror of InvestmentFrequency.fromMonthly on the backend: monthly divided by the
   * paychecks-per-month factor, rounded to cents HALF_UP. Returns null for an unknown or
   * missing frequency, and 0 for a zero or negative monthly amount.
   */
  static toPerPaycheckEquivalent(monthly: number, frequency: string | null | undefined): number | null {
    const key = InvestmentFrequencyUtils.parse(frequency);
    if (!key) return null;
    if (!monthly || monthly <= 0) return 0;

    const factorHundredths = FREQUENCY_FACTOR_HUNDREDTHS[key];

    // Per-paycheck cents = monthlyCents x 100 / factor. HALF_UP means floor(x + 0.5),
    // which is floor((2N + F) / 2F) in integers, so no floating-point drift.
    const monthlyCents = Math.round(monthly * 100);
    const numerator = monthlyCents * 100;
    const perPaycheckCents = Math.floor((2 * numerator + factorHundredths) / (2 * factorHundredths));
    return perPaycheckCents / 100;
  }
}
