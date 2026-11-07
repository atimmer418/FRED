/**
 * Derived copy and figures for the goals quiz: what a fund would save, the cost lead line, the
 * audience phrase of a plan, and the kid year slider's bounds and labels. Pure and framework-free.
 */

import { collegePresets, firstYearTargetForRank, kidRanks } from './kid-cost.utils';
import { horizonMonths, isKidNotYetBorn, kidDeadline, requiredMonthly } from './goal-plan.utils';

export type FundKind = 'baby' | 'college' | 'nest';

type KidTiming = { birthYear: number | null; birthMonth: number | null };

/** Per-kid targets for the low and high end of each range. College: the public tuition and public full-cost presets ($47,800 and $123,960). */
function rangeTargets(kind: 'college' | 'nest'): { low: number; high: number } {
  if (kind === 'nest') return { low: 50_000, high: 250_000 };
  const amount = (key: string) => collegePresets().find(p => p.key === key)?.amount ?? 0;
  return { low: amount('public_tuition'), high: amount('public_full') };
}

/** Nearest $5,000 from $20,000 up, else nearest $1,000. */
function roundSavings(x: number): number {
  const step = x >= 20_000 ? 5_000 : 1_000;
  return Math.round(x / step) * step;
}

/**
 * What the family would put in over the whole plan for one fund: the baby fund is the unborn
 * kids' rank targets added; college and the nest egg are, per kid, the flat monthly for the target
 * times that kid's horizon months (no growth credited), summed over the kids. An undated kid
 * counts as born this year. Each end is rounded on its own.
 */
export function fundSavingsRange(
  kind: FundKind,
  kids: ReadonlyArray<KidTiming>,
  now: Date,
  annualRate: number,
): { low: number; high: number } {
  if (kind === 'baby') {
    const ranks = kidRanks(kids);
    const total = kids.reduce(
      (sum, k, i) => (isKidNotYetBorn(k.birthYear, k.birthMonth, now) ? sum + firstYearTargetForRank(ranks[i]) : sum),
      0,
    );
    return { low: total, high: total };
  }
  const putIn = (target: number): number =>
    kids.reduce((sum, k) => {
      const dl = kidDeadline(now, k.birthYear, k.birthMonth);
      const n = horizonMonths(now, dl.year, dl.month);
      return sum + requiredMonthly(0, target, n, annualRate) * n;
    }, 0);
  const t = rangeTargets(kind);
  return { low: roundSavings(putIn(t.low)), high: roundSavings(putIn(t.high)) };
}

/** "$37K", "$1.2M": whole thousands, or millions to one decimal with a trailing .0 dropped. */
function compactSavings(x: number): string {
  if (x >= 1_000_000) return `$${(x / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
  return `$${Math.round(x / 1_000)}K`;
}

/** "$37K" when both ends read the same, else "$50K to $135K". */
export function formatSavings(r: { low: number; high: number }): string {
  const low = compactSavings(r.low);
  const high = compactSavings(r.high);
  return low === high ? low : `${low} to ${high}`;
}

/** The lead above the monthly cost figure. */
export function costLead(kidsCount: number): string {
  if (kidsCount <= 1) return "That's about";
  if (kidsCount === 2) return "For both kids, that's about";
  return `For all ${kidsCount} kids, that's about`;
}

/** Who a kids plan is for: "for Ellie", "for both kids", "for all 3 kids". */
export function audienceLabel(kids: ReadonlyArray<{ label: string }>): string {
  if (kids.length <= 1) {
    const label = kids[0]?.label.trim() ?? '';
    return `for ${label || 'your kid'}`;
  }
  if (kids.length === 2) return 'for both kids';
  return `for all ${kids.length} kids`;
}

/** The kid year slider's range: 17 years back to 10 years ahead, from today's date. */
export function kidYearBounds(now: Date): { min: number; max: number } {
  const year = now.getFullYear();
  return { min: year - 17, max: year + 10 };
}

/** The kid year slider's value label: "Not sure", "Born 2024", "This year", or the year. */
export function kidYearLabel(value: number | null, now: Date): string {
  if (value === null) return 'Not sure';
  const year = now.getFullYear();
  if (value < year) return `Born ${value}`;
  if (value === year) return 'This year';
  return String(value);
}
