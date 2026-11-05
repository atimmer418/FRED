// Pure form model for the goal page (setup and edit). No Angular imports.

import {
  AccountState, EmergencyFundConfig, Goal, GoalConfig, GoalIcon, GoalType, HouseCarConfig,
  KidInput, KidsConfig, TargetConfig, VacationConfig,
} from '../../models/goal.model';
import { AT_18_PRESETS, CHILDBIRTH_DOLLAR_YEAR_LABEL } from '../../constants/kid-costs';
import {
  KidCostFigures, KidCostStage, collegePresets, firstYearTargetForRank, kidRanks,
} from '../../utils/kid-cost.utils';
import { firstYearDue, horizonMonths, isKidNotYetBorn } from '../../utils/goal-plan.utils';
import { FirstYearRow, fmtMoney } from './goal-plan.view-model';

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
  collegeChoice: CollegeChoice;
  at18Choice: At18Choice;
}

export interface GoalForm {
  type: GoalType;
  name: string;
  icon: GoalIcon;
  kids: KidsForm;
  price: number | null;
  downPaymentPct: number | null;
  fullPrice: boolean;
  targetYear: number | null;
  tripsPerYear: 1 | 2 | 3 | 4;
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
export const MAX_KIDS = 10;

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

/**
 * The fund switch default for a NEW goal: on only when at least one kid is not yet born and every
 * such kid is undecided or at least 12 months away (unknown month counted as July).
 */
export function defaultFirstYearFund(kids: KidFormRow[], now: Date): boolean {
  const eligible = kids.map(kidTiming).filter(t => isKidNotYetBorn(t.birthYear, t.birthMonth, now));
  return eligible.length > 0
    && eligible.every(t => t.birthYear === null || horizonMonths(now, t.birthYear, t.birthMonth ?? 7) >= 12);
}

const DEFAULT_COLLEGE: CollegeChoice = 'public_tuition';
const DEFAULT_AT_18 = 25_000;

/** The 529 target a college row sets; null for none (custom keeps whatever is typed). */
export function collegeAmount(choice: CollegeChoice): number | null {
  return collegePresets().find(p => p.key === choice)?.amount ?? null;
}

function collegeChoiceFor(target: number): CollegeChoice {
  if (!(target > 0)) return 'none';
  return collegePresets().find(p => p.amount === target)?.key ?? 'custom';
}

function at18ChoiceFor(target: number): At18Choice {
  if (!(target > 0)) return 'none';
  return AT_18_PRESETS.includes(target) ? target : 'custom';
}

function defaultKidsForm(type: GoalType, now: Date): KidsForm {
  const kids = [newKidRow(0)];
  if (type !== 'KIDS') {
    return { brokerageTarget: null, plan529Target: null, kids, firstYearFund: false, collegeChoice: 'none', at18Choice: 'none' };
  }
  return {
    brokerageTarget: DEFAULT_AT_18,
    plan529Target: collegeAmount(DEFAULT_COLLEGE),
    kids,
    firstYearFund: defaultFirstYearFund(kids, now),
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
    fullPrice: false,
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
      form.fullPrice = c.downPaymentPct == null;
      form.downPaymentPct = c.downPaymentPct ?? 20;
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
  };
}

export function formToConfig(form: GoalForm, existing: Goal | null, now: Date): GoalConfig {
  switch (form.type) {
    case 'KIDS':
      return kidsToConfig(form.kids, existing, now);
    case 'HOUSE':
    case 'CAR': {
      const config: HouseCarConfig = {
        price: form.price ?? 0,
        downPaymentPct: form.fullPrice ? null : form.downPaymentPct,
        targetYear: form.targetYear ?? 0,
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
      const c = existing.config as HouseCarConfig;
      const pct = form.fullPrice ? null : form.downPaymentPct;
      return (form.price ?? 0) !== c.price || pct !== c.downPaymentPct || (form.targetYear ?? 0) !== c.targetYear;
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

export type KidsQuizCard = 1 | 2 | 3 | 4 | 5;

/** Which error keys each kids quiz card owns. Cards 1 and 3 have no fields that can be wrong. */
function quizCardOwns(card: KidsQuizCard, key: string): boolean {
  switch (card) {
    case 2: return key.startsWith('kid.');
    case 4: return key === 'plan529Target';
    case 5: return key === 'brokerageTarget' || key === 'name' || key === 'kidsTargets';
    default: return false;
  }
}

/** The errors one kids quiz card shows and blocks Next on. */
export function kidsQuizErrors(
  form: GoalForm,
  card: KidsQuizCard,
  now: Date,
  existing: Goal | null = null,
): Record<string, string> {
  const all = validateForm(form, now, existing);
  const out: Record<string, string> = {};
  for (const key of Object.keys(all)) {
    if (quizCardOwns(card, key)) out[key] = all[key];
  }
  return out;
}

// ── Kids copy: the quiz, "What a kid costs" and "First-year fund" ─────────────
// Wording is verbatim from docs/superpowers/specs/2026-11-05-goals-v3-kid-costs-addendum.md.

const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function roundTo(value: number, step: number): number {
  return Math.round(value / step) * step;
}

/** "Mon YYYY" for a stored year and month (1..12). */
export function monthYearLabel(due: { year: number; month: number }): string {
  return `${MONTH_ABBR[due.month - 1]} ${due.year}`;
}

/** The per-child headline by family size, to the nearest $1,000 (no closing period). */
export function costHeadline(c: KidCostFigures): string {
  const amount = fmtMoney(roundTo(c.perChildTotal, 1000));
  switch (c.sizeWording) {
    case 'only': return `About ${amount} to raise an only child to 18`;
    case 'two': return `About ${amount} per child to 18 in a two-child family`;
    default: return `About ${amount} per child to 18 in a family of three or more`;
  }
}

/** "For your 3 kids, about $757,000 in all, ..." for more than one kid, else null. */
export function costFamilyLine(c: KidCostFigures): string | null {
  if (c.kidsCount < 2) return null;
  return `For your ${c.kidsCount} kids, about ${fmtMoney(roundTo(c.familyTotal, 1000))} in all, if they are home at the same time.`;
}

/** "about $18,000 a year, $1,500 a month" (nearest $100 and $10). */
export function costStageLine(stage: KidCostStage): string {
  return `about ${fmtMoney(roundTo(stage.annual, 100))} a year, ${fmtMoney(roundTo(stage.monthly, 10))} a month`;
}

export function costBirthLine(c: KidCostFigures): string {
  return `Pregnancy and birth, with employer insurance: about ${fmtMoney(roundTo(c.childbirthOutOfPocket, 100))} out of pocket (Peterson-KFF, ${CHILDBIRTH_DOLLAR_YEAR_LABEL})`;
}

/** The quiz's one-line source under the cost figures. */
export function costSourceLine(c: KidCostFigures): string {
  return `National averages for a middle-income, two-parent family. USDA figures from 2015, adjusted to ${c.asOfLabel} dollars by FRED. Birth cost from Peterson-KFF, ${CHILDBIRTH_DOLLAR_YEAR_LABEL}, with employer insurance.`;
}

/** The cost card note; its ending depends on whether a college amount is set. */
export function costNote(c: KidCostFigures, hasCollege: boolean): string {
  const lead = `Yearly costs are national averages for a middle-income, two-parent family: USDA figures from 2015, adjusted to ${c.asOfLabel} dollars by FRED. Families with more children spend less on each one, and an only child costs more (USDA). Your costs will differ. College is not included`;
  return lead + (hasCollege ? ', which is what the 529 below is for.' : '. You can add a college plan from Edit.');
}

export function firstChildLine(): string {
  return `The first year alone, with birth costs: about ${fmtMoney(firstYearTargetForRank(1))} for a first child.`;
}

export function shortRunwayLine(months: number): string {
  return `That is a lot to save in ${months} ${months === 1 ? 'month' : 'months'}. Most of the first year is paid from your income as you go, so save what you can.`;
}

/** The lines one first-year fund row shows: a lead line and, while unfinished, its monthly line. */
export function fundRowLines(row: FirstYearRow): { lead: string; plan: string | null } {
  const { plan, label } = row;
  const target = fmtMoney(plan.target);
  if (plan.finished) {
    const saved = `${label}: ${fmtMoney(row.balance)} saved of ${target}`;
    return {
      lead: plan.undecided ? `${saved}. Three years are up, so set a date to keep planning.` : `${saved} for the first year.`,
      plan: null,
    };
  }
  const monthly = `${fmtMoney(plan.monthly)} a month until ${monthYearLabel(plan.due)}.`;
  let line: string;
  if (plan.undecided) {
    line = plan.rateKind === 'cash'
      ? `${monthly} No date yet. Planned as cash at about 4%, since this runway ends within 3 years.`
      : `${monthly} No date yet, so this one is invested at your return assumption.`;
  } else {
    line = plan.rateKind === 'cash' ? `${monthly} Planned as cash at about 4%, since you need it within 3 years.` : monthly;
  }
  return { lead: `${label}: about ${target} for birth costs and the first year.`, plan: line };
}

/** Local ISO timestamp without a Z suffix (yyyy-MM-ddTHH:mm:ss), the shape the backend stores. */
export function toLocalIso(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
