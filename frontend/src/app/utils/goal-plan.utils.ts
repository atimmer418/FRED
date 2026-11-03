/**
 * Goal plan math. This file is the only home of goal math in the frontend.
 *
 * Convention: ordinary annuity. Every month the balance grows first
 * (v = v * (1 + r), r = annualRate / 12), then the monthly contribution and any
 * lump for that month index are posted. Month index 1 is the month AFTER `now`;
 * the horizon n runs through the deadline month inclusive. Proposed amounts round
 * UP to whole dollars. All functions are pure and framework-free.
 */

export interface LumpOccurrence {
  /** 1..n, the month the lump is posted (after that month's growth). */
  monthIndex: number;
  amount: number;
}

/** Whole months from the month after `now` through the deadline month inclusive. Month is 1..12. Floors at 0. */
export function horizonMonths(now: Date, deadlineYear: number, deadlineMonth: number): number {
  const months = (deadlineYear - now.getFullYear()) * 12 + (deadlineMonth - (now.getMonth() + 1));
  return Math.max(0, months);
}

/** Same counting as horizonMonths, between two ISO timestamps. Floors at 0. */
export function monthsBetween(fromIso: string, toIso: string): number {
  const from = new Date(fromIso);
  const to = new Date(toIso);
  const months = (to.getFullYear() - from.getFullYear()) * 12 + (to.getMonth() - from.getMonth());
  return Math.max(0, months);
}

/** Monthly contribution (rounded up to whole dollars) that grows pv into fv over n months. */
export function requiredMonthly(pv: number, fv: number, n: number, annualRate: number): number {
  if (n <= 0) return 0;
  const r = annualRate / 12;
  const growth = (1 + r) ** n;
  const gap = fv - pv * (r === 0 ? 1 : growth);
  if (gap <= 0) return 0;
  const monthly = r === 0 ? gap / n : gap / ((growth - 1) / r);
  return Math.max(0, Math.ceil(monthly));
}

/** Balance after n months of growth, monthly contributions and lumps. */
export function futureValue(
  pv: number,
  monthly: number,
  n: number,
  annualRate: number,
  lumps: LumpOccurrence[],
): number {
  const r = annualRate / 12;
  const lumpsByMonth = new Map<number, number>();
  for (const lump of lumps) {
    lumpsByMonth.set(lump.monthIndex, (lumpsByMonth.get(lump.monthIndex) ?? 0) + lump.amount);
  }
  let v = pv;
  for (let i = 1; i <= n; i++) {
    v = v * (1 + r) + monthly + (lumpsByMonth.get(i) ?? 0);
  }
  return v;
}

/** Nearest 50 under 500, nearest 100 under 2,000, nearest 250 above. */
export function friendlyRound(x: number): number {
  const step = x < 500 ? 50 : x < 2000 ? 100 : 250;
  return Math.round(x / step) * step;
}

export interface KidLumpSchedule {
  /** Month indices of birthday lumps, within 1..n-1. */
  birthday: number[];
  /** Month indices of every January within 1..n. */
  newYear: number[];
}

/**
 * Lump months for a kid's goal. Birthdays start the year after birth, fall in
 * `birthMonth` (July when unknown) and never land on the deadline month itself.
 * An undecided birth year is treated as the current year.
 */
export function kidLumpSchedule(
  n: number,
  now: Date,
  birthYear: number | null,
  birthMonth: number | null,
): KidLumpSchedule {
  const by = birthYear ?? now.getFullYear();
  const bm = birthMonth ?? 7;

  const birthday: number[] = [];
  for (let year = by + 1; ; year++) {
    const index = (year - now.getFullYear()) * 12 + (bm - (now.getMonth() + 1));
    if (index > n - 1) break;
    if (index >= 1) birthday.push(index);
  }

  const newYear: number[] = [];
  for (let index = 1; index <= n; index++) {
    if ((now.getMonth() + index) % 12 === 0) newYear.push(index);
  }

  return { birthday, newYear };
}

/** The month a kid turns 18. Month is 1..12 (July when unknown). */
export function kidDeadline(
  now: Date,
  birthYear: number | null,
  birthMonth: number | null,
): { year: number; month: number; assumedBirthYear: boolean } {
  return {
    year: (birthYear ?? now.getFullYear()) + 18,
    month: birthMonth ?? 7,
    assumedBirthYear: birthYear == null,
  };
}
