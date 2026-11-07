/**
 * Goal plan math. This file is the only home of goal math in the frontend.
 *
 * Convention: ordinary annuity. Every month the balance grows first
 * (v = v * (1 + r), r = annualRate / 12), then the monthly contribution and any
 * lump for that month index are posted. Month index 1 is the month AFTER `now`;
 * the horizon n runs through the deadline month inclusive. Proposed amounts round
 * UP to whole dollars. All functions are pure and framework-free.
 */

import {
  EmergencyFundConfig,
  GoalConfig,
  GoalType,
  HouseCarConfig,
  RateKind,
  TargetConfig,
  VacationConfig,
} from '../models/goal.model';
import { CASH_RATE } from '../services/rate-assumptions.service';

// ---------------------------------------------------------------------------
// Core
// ---------------------------------------------------------------------------

export const CASH_HORIZON_MONTHS = 36;
/** Gift days: a flat amount per kid every January, offered from a 24-month horizon. */
export const GIFT_DAY_LUMP = 1000;
export const GIFT_MIN_MONTHS = 24;
export const STALE_BALANCE_DAYS = 60;

export interface LumpOccurrence {
  /** 1..n, the month the lump is posted (after that month's growth). */
  monthIndex: number;
  amount: number;
}

/** Whole months from the month after `now` through the deadline month inclusive. Month is 1..12. Floors at 0. */
export function horizonMonths(now: Date, deadlineYear: number, deadlineMonth: number): number {
  if (!Number.isFinite(deadlineYear) || !Number.isFinite(deadlineMonth)) return 0;
  const months = (deadlineYear - now.getFullYear()) * 12 + (deadlineMonth - (now.getMonth() + 1));
  return Math.max(0, months);
}

/**
 * Same counting as horizonMonths, between two ISO timestamps. Floors at 0.
 * Inputs are local ISO timestamps without a Z suffix (yyyy-MM-ddTHH:mm:ss), which is what the backend sends.
 */
export function monthsBetween(fromIso: string, toIso: string): number {
  const from = new Date(fromIso);
  const to = new Date(toIso);
  const months = (to.getFullYear() - from.getFullYear()) * 12 + (to.getMonth() - from.getMonth());
  return Number.isFinite(months) ? Math.max(0, months) : 0;
}

/** Monthly contribution (rounded up to whole dollars) that grows pv into fv over n months. */
export function requiredMonthly(pv: number, fv: number, n: number, annualRate: number): number {
  if (![pv, fv, n, annualRate].every(Number.isFinite) || n <= 0) return 0;
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
  if (![pv, monthly, n, annualRate].every(Number.isFinite)) return 0;
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

// ---------------------------------------------------------------------------
// Kid plan schedule
// ---------------------------------------------------------------------------

/** Month indices of every January within 1..n (index 1 is the month after `now`). */
export function kidLumpSchedule(n: number, now: Date): number[] {
  if (!Number.isFinite(n)) return [];
  const januaries: number[] = [];
  for (let index = 1; index <= n; index++) {
    if ((now.getMonth() + index) % 12 === 0) januaries.push(index);
  }
  return januaries;
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

// ---------------------------------------------------------------------------
// Rate
// ---------------------------------------------------------------------------

/** forceCash wins; else the anchored override; else cash under 36 months and market from 36 up. */
export function resolveRate(args: {
  n: number;
  forceCash: boolean;
  marketRate: number;
  rateKindOverride: RateKind | null;
}): { rateKind: RateKind; rate: number } {
  const rateKind: RateKind = args.forceCash
    ? 'cash'
    : (args.rateKindOverride ?? (args.n < CASH_HORIZON_MONTHS ? 'cash' : 'market'));
  return { rateKind, rate: rateKind === 'cash' ? CASH_RATE : args.marketRate };
}

// ---------------------------------------------------------------------------
// Kid account plan
// ---------------------------------------------------------------------------

export interface AccountPlan {
  n: number;
  rateKind: RateKind;
  rate: number;
  monthly: number;
  /** GIFT_DAY_LUMP when gift days are on and available, else 0. */
  newYearLump: number;
  lumps: LumpOccurrence[];
  /** True when the horizon is at least GIFT_MIN_MONTHS and the January lumps alone fall short of the target. */
  giftDaysAvailable: boolean;
  dueNow: number;
  deadline: { year: number; month: number };
  assumedBirthYear: boolean;
  milestones: { at18: number; at22: number; at30: number };
}

/** The account plan for one kid. With gift days on (and available), $1,000 lands every January and the monthly is solved net of it. */
export function proposeKidAccountPlan(input: {
  target: number;
  balance: number;
  birthYear: number | null;
  birthMonth: number | null;
  marketRate: number;
  rateKindOverride: RateKind | null;
  now: Date;
  giftDays: boolean;
}): AccountPlan {
  const { target, balance, now } = input;
  const dl = kidDeadline(now, input.birthYear, input.birthMonth);
  const n = horizonMonths(now, dl.year, dl.month);
  const { rateKind, rate } = resolveRate({
    n,
    forceCash: false,
    marketRate: input.marketRate,
    rateKindOverride: input.rateKindOverride,
  });
  const r = rate / 12;

  const januaryLumps: LumpOccurrence[] = kidLumpSchedule(n, now).map((monthIndex) => ({
    monthIndex,
    amount: GIFT_DAY_LUMP,
  }));
  // No shrink loop and no cap: when the lumps alone would reach the target, the offer is absent.
  const giftDaysAvailable = n >= GIFT_MIN_MONTHS && target - futureValue(0, 0, n, rate, januaryLumps) > 0;
  const giftsOn = input.giftDays && giftDaysAvailable;
  const lumps = giftsOn ? januaryLumps : [];
  const newYearLump = giftsOn ? GIFT_DAY_LUMP : 0;
  const monthly = requiredMonthly(balance, target - futureValue(0, 0, n, rate, lumps), n, rate);

  return {
    n,
    rateKind,
    rate,
    monthly,
    newYearLump,
    lumps,
    giftDaysAvailable,
    dueNow: n === 0 ? Math.max(0, target - balance) : 0,
    deadline: { year: dl.year, month: dl.month },
    assumedBirthYear: dl.assumedBirthYear,
    milestones: { at18: target, at22: target * (1 + r) ** 48, at30: target * (1 + r) ** 144 },
  };
}

// ---------------------------------------------------------------------------
// First-year fund
// ---------------------------------------------------------------------------

/** An undecided kid's fund runs this many months from the month the fund was created. */
export const FIRST_YEAR_UNDECIDED_MONTHS = 36;

/** Cash under 36 months from the anchor date to the account's end month, market from 36 up. */
export function kidAccountRateKind(anchorDate: Date, endYear: number, endMonth: number): RateKind {
  return horizonMonths(anchorDate, endYear, endMonth) < CASH_HORIZON_MONTHS ? 'cash' : 'market';
}

/** True while the birth month has not started yet. An unknown birth year counts as not yet born. */
export function isKidNotYetBorn(birthYear: number | null, birthMonth: number | null, now: Date): boolean {
  if (birthYear === null) return true;
  return horizonMonths(now, birthYear, birthMonth ?? 7) > 0;
}

/** The month a first-year fund ends: the birth month when decided, else 36 months after `createdAt`'s month. */
export function firstYearDue(
  birthYear: number | null,
  birthMonth: number | null,
  createdAt: Date,
): { year: number; month: number } {
  if (birthYear !== null) return { year: birthYear, month: birthMonth ?? 7 };
  const total = createdAt.getFullYear() * 12 + createdAt.getMonth() + FIRST_YEAR_UNDECIDED_MONTHS;
  return { year: Math.floor(total / 12), month: (total % 12) + 1 };
}

export interface FirstYearPlan {
  n: number;
  rateKind: RateKind;
  rate: number;
  monthly: number;
  target: number;
  /** True once the end month has arrived: nothing left to contribute. */
  finished: boolean;
  due: { year: number; month: number };
  undecided: boolean;
}

/** Monthly contributions only, no lumps. The caller decides the rate kind. */
export function proposeFirstYearPlan(input: {
  target: number;
  balance: number;
  due: { year: number; month: number };
  undecided: boolean;
  marketRate: number;
  rateKind: RateKind;
  now: Date;
}): FirstYearPlan {
  const n = horizonMonths(input.now, input.due.year, input.due.month);
  const rate = input.rateKind === 'cash' ? CASH_RATE : input.marketRate;
  const finished = n === 0;
  return {
    n,
    rateKind: input.rateKind,
    rate,
    monthly: finished ? 0 : requiredMonthly(input.balance, input.target, n, rate),
    target: input.target,
    finished,
    due: input.due,
    undecided: input.undecided,
  };
}

// ---------------------------------------------------------------------------
// Simple plan (house, car, emergency fund, wedding, custom)
// ---------------------------------------------------------------------------

export interface SimplePlan {
  n: number;
  rateKind: RateKind;
  rate: number;
  monthly: number;
  target: number;
  dueNow: number;
  reaches: { year: number; month: number };
  fromYou: number;
  fromGrowth: number;
}

export function proposeSimplePlan(input: {
  target: number;
  balance: number;
  targetYear: number;
  marketRate: number;
  forceCash: boolean;
  rateKindOverride: RateKind | null;
  now: Date;
}): SimplePlan {
  const { target, balance, targetYear, now } = input;
  const n = horizonMonths(now, targetYear, 12);
  const { rateKind, rate } = resolveRate({
    n,
    forceCash: input.forceCash,
    marketRate: input.marketRate,
    rateKindOverride: input.rateKindOverride,
  });
  const monthly = requiredMonthly(balance, target, n, rate);
  const fromYou = monthly * n;
  return {
    n,
    rateKind,
    rate,
    monthly,
    target,
    dueNow: n === 0 ? Math.max(0, target - balance) : 0,
    reaches: { year: targetYear, month: 12 },
    fromYou,
    // No months left means no growth: the shortfall is due now, not from growth.
    fromGrowth: n === 0 ? 0 : Math.max(0, target - balance - fromYou),
  };
}

/** The amount a simple goal needs. Types without a simple target return 0. */
export function simpleTarget(type: GoalType, config: GoalConfig): number {
  switch (type) {
    case 'HOUSE':
    case 'CAR': {
      const c = config as HouseCarConfig;
      return (c.price * (c.downPaymentPct ?? 100)) / 100;
    }
    case 'EMERGENCY_FUND': {
      const c = config as EmergencyFundConfig;
      return c.monthlyExpenses * c.months;
    }
    case 'WEDDING':
    case 'CUSTOM':
      return (config as TargetConfig).targetAmount;
    default:
      return 0;
  }
}

// ---------------------------------------------------------------------------
// Vacation plan
// ---------------------------------------------------------------------------

export interface VacationPlan {
  annual: number;
  monthly: number;
  savedThisYear: number;
  expectedSoFar: number;
  resetNeeded: boolean;
}

/**
 * Expected months count from the plan's anchor month when it was anchored this calendar year,
 * else from January, so a plan started mid-year is not graded for months before it existed.
 */
export function proposeVacationPlan(cfg: VacationConfig, now: Date, planAnchoredAt?: string | null): VacationPlan {
  const annual = cfg.tripsPerYear * cfg.budgetPerTrip;
  const monthly = Math.ceil(annual / 12);
  const resetNeeded = cfg.savedYear !== now.getFullYear();
  const anchor = planAnchoredAt ? new Date(planAnchoredAt) : null;
  const startMonthIndex = anchor && Number.isFinite(anchor.getTime()) && anchor.getFullYear() === now.getFullYear()
    ? anchor.getMonth()
    : 0;
  return {
    annual,
    monthly,
    savedThisYear: resetNeeded ? 0 : cfg.savedThisYear,
    expectedSoFar: monthly * Math.max(0, now.getMonth() - startMonthIndex),
    resetNeeded,
  };
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

export type PlanStatusKind = 'started' | 'imported' | 'ahead' | 'on_plan' | 'behind' | 'done';

export interface PlanStatus {
  kind: PlanStatusKind;
  delta: number;
  asOf: string | null;
  stale: boolean;
}

function kindForDelta(delta: number, target: number): 'ahead' | 'on_plan' | 'behind' {
  const tolerance = Math.max(50, 0.01 * target);
  if (delta > tolerance) return 'ahead';
  if (delta < -tolerance) return 'behind';
  return 'on_plan';
}

export function planStatus(args: {
  completed: boolean;
  imported: boolean;
  target: number;
  balance: number;
  anchorBalance: number;
  anchorMonthly: number;
  planAnchoredAt: string;
  balanceUpdatedAt: string | null;
  rate: number;
  lumps: LumpOccurrence[];
  /** Expected balance of accounts graded on their own path (first-year funds), added to this plan's. Default 0. */
  extraExpected?: number;
  /** The plan's horizon from its anchor: no contributions are expected past the deadline. */
  maxMonths?: number;
  now: Date;
}): PlanStatus {
  const { balanceUpdatedAt } = args;
  const stale =
    balanceUpdatedAt !== null &&
    args.now.getTime() - new Date(balanceUpdatedAt).getTime() > STALE_BALANCE_DAYS * 86_400_000;
  const base = { asOf: balanceUpdatedAt, stale };

  if (args.completed || args.balance >= args.target) return { kind: 'done', delta: 0, ...base };
  if (args.imported) return { kind: 'imported', delta: 0, ...base };
  if (balanceUpdatedAt === null) return { kind: 'started', delta: 0, ...base };

  const logged = monthsBetween(args.planAnchoredAt, balanceUpdatedAt);
  const months = args.maxMonths !== undefined ? Math.min(logged, args.maxMonths) : logged;
  const expected =
    futureValue(
      args.anchorBalance,
      args.anchorMonthly,
      months,
      args.rate,
      args.lumps.filter((l) => l.monthIndex <= months),
    ) + (args.extraExpected ?? 0);
  const delta = args.balance - expected;
  return { kind: kindForDelta(delta, args.target), delta, ...base };
}

/**
 * Vacation never reads done: the year resets, so the goal is always in progress. Until an amount
 * is logged for the current year it reads Plan started, never Behind.
 */
export function vacationStatus(plan: VacationPlan, _now: Date): PlanStatus {
  if (plan.resetNeeded || plan.savedThisYear === 0) {
    return { kind: 'started', delta: 0, asOf: null, stale: false };
  }
  const delta = plan.savedThisYear - plan.expectedSoFar;
  return { kind: kindForDelta(delta, plan.annual), delta, asOf: null, stale: false };
}
