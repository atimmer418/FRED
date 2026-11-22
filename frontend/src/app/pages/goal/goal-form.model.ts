// Pure form model for the goal page (setup and edit). No Angular imports.

import {
  AccountState, EmergencyFundConfig, Goal, GoalConfig, GoalIcon, GoalType, HouseCarConfig,
  KidInput, KidsConfig, KidUnitAccount, TargetConfig, VacationConfig,
} from '../../models/goal.model';
import { AT_18_PRESETS } from '../../constants/kid-costs';
import { CAR_LOAN_RATE_DEFAULT_PCT, MORTGAGE_RATE_DEFAULT_PCT } from '../../constants/loan-rates';
import { collegePresets, firstYearTargetForRank, kidRanks } from '../../utils/kid-cost.utils';
import { firstYearDue, isKidNotYetBorn, kidDeadline } from '../../utils/goal-plan.utils';
import { daysInMonth, ymd } from '../../utils/local-date';

export interface KidFormRow {
  label: string;
  birthYear: number | null;
  birthMonth: number | null;
  undecided: boolean;
}

/** The college question's rows: three presets, another amount, or none. */
export type CollegeChoice = 'public_tuition' | 'public_full' | 'private_tuition' | 'custom' | 'none';
/** The at-18 question's rows: a preset amount, another amount, or nothing extra. */
export type At18Choice = number | 'custom' | 'none';

export interface KidsForm {
  brokerageTarget: number | null;
  plan529Target: number | null;
  kids: KidFormRow[];
  firstYearFund: boolean;
  /** $1,000 per kid every January. Off by default. */
  giftDays: boolean;
  collegeChoice: CollegeChoice;
  at18Choice: At18Choice;
}

export interface GoalForm {
  type: GoalType;
  name: string;
  icon: GoalIcon;
  kids: KidsForm;
  price: number | null;
  /** HOUSE: 5, 10 or 20. A CAR keeps whatever it stored; its plan uses downPaymentAmount. */
  downPaymentPct: number | null;
  /** The loan that follows the down payment, in percent (6.5 means 6.5%). Never a plan input. */
  loanRate: number | null;
  /** CAR: the dollar down payment FRED plans. A house plans from downPaymentPct. */
  downPaymentAmount: number | null;
  targetYear: number | null;
  /** 1 to 10. */
  tripsPerYear: number;
  budgetPerTrip: number | null;
  monthlyExpenses: number | null;
  months: 3 | 6;
  targetAmount: number | null;
}

export const GOAL_TYPES: ReadonlyArray<GoalType> = [
  'KIDS', 'HOUSE', 'VACATION', 'CAR', 'EMERGENCY_FUND', 'WEDDING', 'CUSTOM',
];

/** The seven icons a CUSTOM goal can pick from. */
export const CUSTOM_ICON_OPTIONS: ReadonlyArray<{ icon: GoalIcon; label: string }> = [
  { icon: 'home', label: 'Home' },
  { icon: 'health_and_safety', label: 'Health' },
  { icon: 'flag', label: 'Goal' },
  { icon: 'balance', label: 'Balance' },
  { icon: 'school', label: 'Education' },
  { icon: 'directions_car', label: 'Vehicle' },
  { icon: 'savings', label: 'Savings' },
];

export const MAX_AMOUNT = 100_000_000;
/** A new car goal plans this much down. */
export const DEFAULT_CAR_DOWN = 5_000;
export const CAR_DOWN_STEP = 500;
const CAR_DOWN_MAX = 25_000;
const NO_CAR_DOWN = 'With $0 down there is nothing to save. Set a down payment, or pick Something else.';
export const MAX_KIDS = 6;

const TYPE_ICON: Record<GoalType, GoalIcon> = {
  KIDS: 'child_care',
  HOUSE: 'home',
  VACATION: 'flight',
  CAR: 'directions_car',
  EMERGENCY_FUND: 'health_and_safety',
  WEDDING: 'favorite',
  CUSTOM: 'flag',
};

const TYPE_NAME: Record<GoalType, string> = {
  KIDS: 'Our kids',
  HOUSE: 'First home',
  VACATION: 'Trips',
  CAR: 'Next car',
  EMERGENCY_FUND: 'Emergency fund',
  WEDDING: 'Wedding',
  CUSTOM: 'My goal',
};

export function isGoalType(value: unknown): value is GoalType {
  return typeof value === 'string' && (GOAL_TYPES as ReadonlyArray<string>).includes(value);
}

export function newKidRow(index: number): KidFormRow {
  return { label: `Kid ${index + 1}`, birthYear: null, birthMonth: null, undecided: true };
}

/** A kid row's timing as stored: an undecided kid has no birth year or month. */
export function kidTiming(row: KidFormRow): { birthYear: number | null; birthMonth: number | null } {
  return row.undecided
    ? { birthYear: null, birthMonth: null }
    : { birthYear: row.birthYear, birthMonth: row.birthMonth };
}

/** The fund switch default for a NEW goal: on whenever at least one kid is not yet born, whatever the runway. */
export function defaultFirstYearFund(kids: KidFormRow[], now: Date): boolean {
  return kids.map(kidTiming).some(t => isKidNotYetBorn(t.birthYear, t.birthMonth, now));
}

export const DEFAULT_COLLEGE: CollegeChoice = 'public_tuition';
/** The nest egg slider's starting point: most users slide up from here. */
export const DEFAULT_AT_18 = 100_000;

/** The 529 target a college row sets; null for none (custom keeps whatever is typed). */
export function collegeAmount(choice: CollegeChoice): number | null {
  return collegePresets().find(p => p.key === choice)?.amount ?? null;
}

function collegeChoiceFor(target: number): CollegeChoice {
  if (!(target > 0)) return 'none';
  return collegePresets().find(p => p.amount === target)?.key ?? 'custom';
}

/** The at-18 row a stored or slid target reads as: a preset amount, custom, or none at $0. */
export function at18ChoiceFor(target: number): At18Choice {
  if (!(target > 0)) return 'none';
  return AT_18_PRESETS.includes(target) ? target : 'custom';
}

/**
 * The rate a house or car loan starts from: the type's exact sourced default (7.28 for a mortgage,
 * 7.54 for a car loan), so a user who never moves the slider saves the published figure. The slider
 * sits at the nearest eighth; only sliding it writes an eighth. Null for other types.
 */
export function defaultLoanRate(type: GoalType): number | null {
  if (type === 'HOUSE') return MORTGAGE_RATE_DEFAULT_PCT;
  if (type === 'CAR') return CAR_LOAN_RATE_DEFAULT_PCT;
  return null;
}

function defaultKidsForm(type: GoalType, now: Date): KidsForm {
  const kids = [newKidRow(0)];
  if (type !== 'KIDS') {
    return {
      brokerageTarget: null, plan529Target: null, kids, firstYearFund: false, giftDays: false,
      collegeChoice: 'none', at18Choice: 'none',
    };
  }
  return {
    brokerageTarget: DEFAULT_AT_18,
    plan529Target: collegeAmount(DEFAULT_COLLEGE),
    kids,
    firstYearFund: defaultFirstYearFund(kids, now),
    giftDays: false,
    collegeChoice: DEFAULT_COLLEGE,
    at18Choice: DEFAULT_AT_18,
  };
}

export function defaultForm(type: GoalType, now: Date): GoalForm {
  const year = now.getFullYear();
  let yearsOut = 5;
  if (type === 'CAR') yearsOut = 3;
  else if (type === 'EMERGENCY_FUND') yearsOut = 2;
  return {
    type,
    name: TYPE_NAME[type],
    icon: TYPE_ICON[type],
    kids: defaultKidsForm(type, now),
    price: null,
    downPaymentPct: 20,
    loanRate: null,
    downPaymentAmount: type === 'CAR' ? DEFAULT_CAR_DOWN : null,
    targetYear: year + yearsOut,
    tripsPerYear: 2,
    budgetPerTrip: null,
    monthlyExpenses: null,
    months: 6,
    targetAmount: null,
  };
}

export function formFromGoal(goal: Goal): GoalForm {
  const form = defaultForm(goal.type, new Date());
  form.name = goal.name;
  form.icon = goal.icon;
  form.targetYear = null;
  switch (goal.type) {
    case 'KIDS': {
      const c = goal.config as KidsConfig;
      form.kids = {
        brokerageTarget: c.brokerageTarget > 0 ? c.brokerageTarget : null,
        plan529Target: c.plan529Target > 0 ? c.plan529Target : null,
        kids: c.kids.map((k, i) => ({
          label: k.label || `Kid ${i + 1}`,
          birthYear: k.birthYear,
          birthMonth: k.birthMonth,
          undecided: k.birthYear == null,
        })),
        // A saved goal keeps its switch: missing and null both read as off.
        firstYearFund: c.firstYearFund === true,
        giftDays: c.giftDays === true,
        collegeChoice: collegeChoiceFor(c.plan529Target),
        at18Choice: at18ChoiceFor(c.brokerageTarget),
      };
      if (form.kids.kids.length === 0) form.kids.kids = [newKidRow(0)];
      break;
    }
    case 'HOUSE':
    case 'CAR': {
      const c = goal.config as HouseCarConfig;
      form.price = c.price;
      // Full price is gone: a legacy full-price goal reads as 20% down.
      form.downPaymentPct = c.downPaymentPct ?? 20;
      form.loanRate = c.loanRate ?? defaultLoanRate(goal.type);
      if (goal.type === 'CAR') {
        // A car stored before dollar down payments: its old percent (null was the full price), to the
        // nearest $500, never more than the price or the slider's $25,000 top.
        const legacy = Math.round((c.price * (c.downPaymentPct ?? 100)) / 100 / CAR_DOWN_STEP) * CAR_DOWN_STEP;
        form.downPaymentAmount = c.downPaymentAmount ?? Math.min(legacy, c.price, CAR_DOWN_MAX);
      } else {
        form.downPaymentAmount = null;
      }
      form.targetYear = c.targetYear;
      break;
    }
    case 'VACATION': {
      const c = goal.config as VacationConfig;
      form.tripsPerYear = c.tripsPerYear;
      form.budgetPerTrip = c.budgetPerTrip;
      break;
    }
    case 'EMERGENCY_FUND': {
      const c = goal.config as EmergencyFundConfig;
      form.monthlyExpenses = c.monthlyExpenses;
      form.months = c.months;
      form.targetYear = c.targetYear;
      break;
    }
    default: {
      const c = goal.config as TargetConfig;
      form.targetAmount = c.targetAmount;
      form.targetYear = c.targetYear;
    }
  }
  return form;
}

function freshAccount(): AccountState {
  return { balance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 };
}

function kidsOf(existing: Goal | null): KidInput[] {
  return existing && existing.type === 'KIDS' ? (existing.config as KidsConfig).kids : [];
}

/**
 * KIDS rows as they would be saved, with first-year funds matched to stored ones by kid INDEX
 * (the stepper only ever removes the last kid, and the server matches the same way).
 *
 * Fund on, per kid: a stored fund is always kept (timing unchanged: account, target and end date
 * carry over; timing changed: account and balance carry over, the end date is recomputed and may
 * simply read finished). A kid with no stored fund gets a fresh one only while not yet born.
 * A stored target survives only when the kid count and every kid's timing are unchanged;
 * otherwise each fund takes the target for its birth rank.
 */
function kidsToConfig(form: KidsForm, existing: Goal | null, now: Date): KidsConfig {
  const old = kidsOf(existing);
  const timings = form.kids.map(kidTiming);
  const sameTiming = (i: number): boolean =>
    !!old[i] && old[i].birthYear === timings[i].birthYear && old[i].birthMonth === timings[i].birthMonth;
  const allTimingSame = old.length === timings.length && timings.every((_, i) => sameTiming(i));
  const ranks = kidRanks(timings);

  const kids: KidInput[] = form.kids.map((row, i) => {
    const { birthYear, birthMonth } = timings[i];
    const base: KidInput = {
      label: row.label.trim() || `Kid ${i + 1}`,
      birthYear,
      birthMonth,
      accounts: old[i]
        ? { brokerage: { ...old[i].accounts.brokerage }, plan529: { ...old[i].accounts.plan529 } }
        : { brokerage: freshAccount(), plan529: freshAccount() },
    };
    if (!form.firstYearFund) return base;

    const stored = old[i]?.accounts.firstYear ?? null;
    let due: { year: number; month: number };
    if (stored) {
      const storedDue = old[i].firstYearDueYear != null && old[i].firstYearDueMonth != null
        ? { year: old[i].firstYearDueYear as number, month: old[i].firstYearDueMonth as number }
        : null;
      due = sameTiming(i) && storedDue ? storedDue : firstYearDue(birthYear, birthMonth, now);
    } else if (isKidNotYetBorn(birthYear, birthMonth, now)) {
      due = firstYearDue(birthYear, birthMonth, now);
    } else {
      return base;
    }
    const storedTarget = old[i]?.firstYearTarget ?? 0;
    const target = stored && storedTarget > 0 && allTimingSame ? storedTarget : firstYearTargetForRank(ranks[i]);
    return {
      ...base,
      firstYearTarget: target,
      firstYearDueYear: due.year,
      firstYearDueMonth: due.month,
      accounts: { ...base.accounts, firstYear: stored ? { ...stored } : freshAccount() },
    };
  });

  return {
    brokerageTarget: form.brokerageTarget ?? 0,
    plan529Target: form.plan529Target ?? 0,
    kids,
    // On only when the switch is on and at least one kid ends up holding a fund.
    firstYearFund: kids.some(k => !!k.accounts.firstYear),
    giftDays: form.giftDays,
  };
}

export function formToConfig(form: GoalForm, existing: Goal | null, now: Date): GoalConfig {
  switch (form.type) {
    case 'KIDS':
      return kidsToConfig(form.kids, existing, now);
    case 'HOUSE':
    case 'CAR': {
      const price = form.price ?? 0;
      const config: HouseCarConfig = {
        price,
        downPaymentPct: form.downPaymentPct,
        targetYear: form.targetYear ?? 0,
        loanRate: form.loanRate,
        // A car down payment over the price is the price; a house plans from its percent.
        downPaymentAmount: form.type === 'CAR' && form.downPaymentAmount != null
          ? Math.min(form.downPaymentAmount, price)
          : null,
      };
      return config;
    }
    case 'VACATION': {
      const old = existing && existing.type === 'VACATION' ? (existing.config as VacationConfig) : null;
      const config: VacationConfig = {
        tripsPerYear: form.tripsPerYear,
        budgetPerTrip: form.budgetPerTrip ?? 0,
        savedThisYear: old ? old.savedThisYear : 0,
        savedYear: old ? old.savedYear : new Date().getFullYear(),
      };
      return config;
    }
    case 'EMERGENCY_FUND': {
      const config: EmergencyFundConfig = {
        monthlyExpenses: form.monthlyExpenses ?? 0,
        months: form.months,
        targetYear: form.targetYear ?? 0,
      };
      return config;
    }
    default: {
      const config: TargetConfig = {
        targetAmount: form.targetAmount ?? 0,
        targetYear: form.targetYear ?? 0,
      };
      return config;
    }
  }
}

/**
 * True when a plan input differs from the saved goal. Name, icon and kid labels are
 * never plan inputs. Mirrors the server rule.
 */
export function planInputsChanged(form: GoalForm, existing: Goal, now: Date): boolean {
  if (form.type !== existing.type) return true;
  switch (form.type) {
    case 'KIDS': {
      const c = existing.config as KidsConfig;
      const next = kidsToConfig(form.kids, existing, now);
      if (next.brokerageTarget !== c.brokerageTarget) return true;
      if (next.plan529Target !== c.plan529Target) return true;
      if ((next.firstYearFund ?? false) !== (c.firstYearFund ?? false)) return true;
      if (next.kids.length !== c.kids.length) return true;
      return next.kids.some((kid, i) => {
        const was = c.kids[i];
        return kid.birthYear !== was.birthYear
          || kid.birthMonth !== was.birthMonth
          || !!kid.accounts.firstYear !== !!was.accounts.firstYear
          || (kid.firstYearTarget ?? null) !== (was.firstYearTarget ?? null)
          || (kid.firstYearDueYear ?? null) !== (was.firstYearDueYear ?? null)
          || (kid.firstYearDueMonth ?? null) !== (was.firstYearDueMonth ?? null);
      });
    }
    case 'HOUSE':
    case 'CAR': {
      // The loan rate only changes the line under the plan, never the plan.
      const c = existing.config as HouseCarConfig;
      const next = formToConfig(form, existing, now) as HouseCarConfig;
      return next.price !== c.price || next.downPaymentPct !== c.downPaymentPct || next.targetYear !== c.targetYear
        || (next.downPaymentAmount ?? null) !== (c.downPaymentAmount ?? null);
    }
    case 'VACATION': {
      const c = existing.config as VacationConfig;
      return form.tripsPerYear !== c.tripsPerYear || (form.budgetPerTrip ?? 0) !== c.budgetPerTrip;
    }
    case 'EMERGENCY_FUND': {
      const c = existing.config as EmergencyFundConfig;
      return (form.monthlyExpenses ?? 0) !== c.monthlyExpenses || form.months !== c.months
        || (form.targetYear ?? 0) !== c.targetYear;
    }
    default: {
      const c = existing.config as TargetConfig;
      return (form.targetAmount ?? 0) !== c.targetAmount || (form.targetYear ?? 0) !== c.targetYear;
    }
  }
}

function amountError(value: number | null): string | null {
  if (value == null || !(value > 0)) return 'Enter an amount greater than $0.';
  if (value > MAX_AMOUNT) return 'That is more than we can plan for. Try a smaller amount.';
  return null;
}

/**
 * Field-keyed error messages. Empty object means the form is valid. `existing` (edit mode) lets a
 * stored first-year fund on a kid who has since arrived count toward a valid kids plan, and lets
 * an unchanged stored target year or kid birth year stand once the calendar has passed it (the
 * server accepts those the same way).
 */
export function validateForm(form: GoalForm, now: Date, existing: Goal | null = null): Record<string, string> {
  const errors: Record<string, string> = {};
  const currentYear = now.getFullYear();
  const yearMessage = `Enter a year from ${currentYear} to ${currentYear + 50}.`;
  const storedTargetYear = existing && existing.type === form.type && form.type !== 'KIDS' && form.type !== 'VACATION'
    ? (existing.config as HouseCarConfig | EmergencyFundConfig | TargetConfig).targetYear
    : null;
  const storedKids = kidsOf(existing);

  if (!form.name.trim()) errors['name'] = 'Give this goal a name.';

  const checkYear = (): void => {
    const y = form.targetYear;
    const pastAndNew = y != null && y < currentYear && y !== storedTargetYear;
    if (y == null || !Number.isInteger(y) || pastAndNew || y > currentYear + 50) {
      errors['targetYear'] = yearMessage;
    }
  };

  switch (form.type) {
    case 'KIDS': {
      const a = form.kids.brokerageTarget;
      const b = form.kids.plan529Target;
      const hasA = a != null && a > 0;
      const hasB = b != null && b > 0;
      if (form.kids.collegeChoice === 'custom') {
        const e = amountError(b);
        if (e) errors['plan529Target'] = e;
      }
      if (form.kids.at18Choice === 'custom') {
        const e = amountError(a);
        if (e) errors['brokerageTarget'] = e;
      }
      const holdsFund = form.kids.firstYearFund && kidsToConfig(form.kids, existing, now).firstYearFund === true;
      // An amount over the cap already shows its own error on its field; say it once.
      const overCap = ((a ?? 0) > MAX_AMOUNT && !errors['brokerageTarget'])
        || ((b ?? 0) > MAX_AMOUNT && !errors['plan529Target']);
      if (!hasA && !hasB && !holdsFund) errors['kidsTargets'] = 'Enter at least one target for your kids.';
      else if (overCap) {
        errors['kidsTargets'] = 'That is more than we can plan for. Try a smaller amount.';
      }
      form.kids.kids.forEach((row, i) => {
        if (row.undecided) return;
        const y = row.birthYear;
        // A stored kid who has since turned 18 keeps the plan writable until it is changed.
        const storedSame = y != null && storedKids[i]?.birthYear === y;
        if (y != null && currentYear - y >= 18 && !storedSame) {
          errors[`kid.${i}.birthYear`] = 'Already 18 or older. Use Something else with a target year instead.';
        } else if (y == null || (y < currentYear - 17 && !storedSame) || y > currentYear + 10) {
          errors[`kid.${i}.birthYear`] = `Enter a year from ${currentYear - 17} to ${currentYear + 10}.`;
        }
      });
      break;
    }
    case 'HOUSE':
    case 'CAR': {
      const e = amountError(form.price);
      if (e) errors['price'] = e;
      // Over the price is clamped on save, not refused.
      if (form.type === 'CAR' && !((form.downPaymentAmount ?? 0) > 0)) errors['downPaymentAmount'] = NO_CAR_DOWN;
      checkYear();
      break;
    }
    case 'VACATION': {
      const e = amountError(form.budgetPerTrip);
      if (e) errors['budgetPerTrip'] = e;
      break;
    }
    case 'EMERGENCY_FUND': {
      const e = amountError(form.monthlyExpenses);
      if (e) errors['monthlyExpenses'] = e;
      checkYear();
      break;
    }
    default: {
      const e = amountError(form.targetAmount);
      if (e) errors['targetAmount'] = e;
      checkYear();
    }
  }
  return errors;
}

// ── Labels ───────────────────────────────────────────────────────────────────

const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Mon YYYY" for a stored year and month (1..12). */
export function monthYearLabel(due: { year: number; month: number }): string {
  return `${MONTH_ABBR[due.month - 1]} ${due.year}`;
}

/** Local ISO timestamp without a Z suffix (yyyy-MM-ddTHH:mm:ss), the shape the backend stores. */
export function toLocalIso(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** The last day of a month (1..12) as yyyy-MM-dd. */
function lastDayOf(year: number, month: number): string {
  return ymd(year, month, daysInMonth(year, month));
}

/**
 * The last day paydays credit a unit (yyyy-MM-dd), written beside anchorMonthly whenever the plan
 * anchors: Dec 31 of the target year for a simple goal; the last day of the 18th-birthday month for
 * a kid's 529 or brokerage (July when the month is unknown, born this year when undecided); the last
 * day of the baby fund's due month; null for vacation (it stops at its target and resets each year),
 * and null for a kids goal without a kid and account to name.
 */
export function creditsUntilFor(
  type: GoalType,
  config: GoalConfig,
  kidIndex?: number,
  account?: KidUnitAccount,
  now: Date = new Date(),
): string | null {
  switch (type) {
    case 'VACATION':
      return null;
    case 'KIDS': {
      const kid = kidIndex === undefined ? undefined : (config as KidsConfig).kids?.[kidIndex];
      if (!kid || !account) return null;
      if (account === 'firstYear') {
        const due = kid.firstYearDueYear != null && kid.firstYearDueMonth != null
          ? { year: kid.firstYearDueYear, month: kid.firstYearDueMonth }
          : firstYearDue(kid.birthYear, kid.birthMonth, now);
        return lastDayOf(due.year, due.month);
      }
      const dl = kidDeadline(now, kid.birthYear, kid.birthMonth);
      return lastDayOf(dl.year, dl.month);
    }
    default:
      return `${(config as HouseCarConfig | EmergencyFundConfig | TargetConfig).targetYear}-12-31`;
  }
}
