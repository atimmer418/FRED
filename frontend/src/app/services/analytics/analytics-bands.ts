/**
 * Banding helpers.
 *
 * A raw dollar amount must never leave the device, so every money value becomes one of a fixed set
 * of buckets before it is sent. These helpers exist so that every call site bands the same way:
 * two screens using slightly different buckets would make the two unanalysable together.
 *
 * Years and counts get the same treatment for a different reason: a raw year-to-freedom or goal
 * count is not sensitive, but it is high-cardinality enough to make a chart useless.
 */

/** One bucket of money. Deliberately coarse at the top, where few users are and detail identifies. */
export type AmountBand =
  | 'under_50'
  | '50_99'
  | '100_249'
  | '250_499'
  | '500_999'
  | '1k_2499'
  | '2500_4999'
  | '5k_9999'
  | '10k_49k'
  | '50k_249k'
  | '250k_999k'
  | '1m_plus';

/** A small count: goals on a plan, sessions on an account, strategies that held. */
export type CountBand = '0' | '1' | '2_3' | '4_9' | '10_plus';

/** A span in years: time to freedom, time to a goal. */
export type YearsBand = 'under_1' | '1_2' | '3_5' | '6_10' | '11_20' | 'over_20' | 'never';

/** A percentage, in tenths. Used for progress toward a goal. */
export type PercentBand = '0_9' | '10_24' | '25_49' | '50_74' | '75_99' | '100';

const AMOUNT_BOUNDS: ReadonlyArray<readonly [number, AmountBand]> = [
  [50, 'under_50'],
  [100, '50_99'],
  [250, '100_249'],
  [500, '250_499'],
  [1_000, '500_999'],
  [2_500, '1k_2499'],
  [5_000, '2500_4999'],
  [10_000, '5k_9999'],
  [50_000, '10k_49k'],
  [250_000, '50k_249k'],
  [1_000_000, '250k_999k'],
];

/**
 * Buckets a dollar amount. Broken input never throws, because analytics must not break a user
 * flow: NaN and negatives fall to the lowest bucket, and Infinity to the highest.
 *
 * Callers must say which unit they are banding (monthly or annual) in the property name, because
 * the same number means different things on different screens.
 */
export function toAmountBand(amount: number): AmountBand {
  // Infinity means "very large", so it belongs at the top, not the bottom: sending it to the
  // lowest bucket would quietly under-report instead of looking wrong.
  if (amount === Number.POSITIVE_INFINITY) {
    return '1m_plus';
  }
  if (!Number.isFinite(amount) || amount < 0) {
    return 'under_50';
  }
  for (const [limit, band] of AMOUNT_BOUNDS) {
    if (amount < limit) {
      return band;
    }
  }
  return '1m_plus';
}

export function toCountBand(count: number): CountBand {
  if (count === Number.POSITIVE_INFINITY) {
    return '10_plus';
  }
  if (!Number.isFinite(count) || count <= 0) {
    return '0';
  }
  if (count === 1) {
    return '1';
  }
  if (count <= 3) {
    return '2_3';
  }
  return count <= 9 ? '4_9' : '10_plus';
}

/** `years` may be null or non-finite, which is what an unreachable freedom date looks like. */
export function toYearsBand(years: number | null | undefined): YearsBand {
  if (years === null || years === undefined || !Number.isFinite(years)) {
    return 'never';
  }
  if (years < 1) {
    return 'under_1';
  }
  if (years <= 2) {
    return '1_2';
  }
  if (years <= 5) {
    return '3_5';
  }
  if (years <= 10) {
    return '6_10';
  }
  return years <= 20 ? '11_20' : 'over_20';
}

/** `percent` is 0-100. */
export function toPercentBand(percent: number): PercentBand {
  if (percent === Number.POSITIVE_INFINITY) {
    return '100';
  }
  if (!Number.isFinite(percent) || percent < 10) {
    return '0_9';
  }
  if (percent < 25) {
    return '10_24';
  }
  if (percent < 50) {
    return '25_49';
  }
  if (percent < 75) {
    return '50_74';
  }
  return percent < 100 ? '75_99' : '100';
}
