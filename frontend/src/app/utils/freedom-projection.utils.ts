/**
 * Shared freedom-date projection math.
 *
 * Single implementation of the months-to-target formula used by the my-profile
 * Freedom Plan card, the Freedom detail sheet, and (via its own port)
 * FreedomStatsService — annuity-with-growth solved for n, capped at 600 months
 * (50 years) on degenerate inputs. Keep in lockstep with the backend's
 * MonthlyFreedomUpdateService projection.
 */
export function monthsToTarget(
  pv: number,
  pmt: number,
  fv: number,
  annualReturn: number,
): number {
  if (pv >= fv) return 0;
  const r = annualReturn / 12;
  if (pmt <= 0) {
    if (pv <= 0) return 600;
    const months = Math.log(fv / pv) / Math.log(1 + r);
    return (isNaN(months) || !isFinite(months) || months < 0) ? 600 : months;
  }
  const months = Math.log((fv + pmt / r) / (pv + pmt / r)) / Math.log(1 + r);
  return (isNaN(months) || !isFinite(months) || months < 0) ? 600 : months;
}
