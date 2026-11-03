// Pure form model for the goal page (setup and edit). No Angular imports.

import {
  AccountState, EmergencyFundConfig, Goal, GoalConfig, GoalIcon, GoalType, HouseCarConfig,
  KidInput, KidsConfig, TargetConfig, VacationConfig,
} from '../../models/goal.model';

export interface KidFormRow {
  label: string;
  birthYear: number | null;
  birthMonth: number | null;
  undecided: boolean;
}

export interface GoalForm {
  type: GoalType;
  name: string;
  icon: GoalIcon;
  kids: { brokerageTarget: number | null; plan529Target: number | null; kids: KidFormRow[] };
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

export function defaultForm(type: GoalType, now: Date): GoalForm {
  const year = now.getFullYear();
  let yearsOut = 5;
  if (type === 'CAR') yearsOut = 3;
  else if (type === 'EMERGENCY_FUND') yearsOut = 2;
  return {
    type,
    name: TYPE_NAME[type],
    icon: TYPE_ICON[type],
    kids: { brokerageTarget: null, plan529Target: null, kids: [newKidRow(0)] },
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

export function formToConfig(form: GoalForm, existing: Goal | null): GoalConfig {
  switch (form.type) {
    case 'KIDS': {
      const old = kidsOf(existing);
      const kids: KidInput[] = form.kids.kids.map((row, i) => ({
        label: row.label.trim() || `Kid ${i + 1}`,
        birthYear: row.undecided ? null : row.birthYear,
        birthMonth: row.undecided ? null : row.birthMonth,
        accounts: old[i]
          ? { brokerage: { ...old[i].accounts.brokerage }, plan529: { ...old[i].accounts.plan529 } }
          : { brokerage: freshAccount(), plan529: freshAccount() },
      }));
      const config: KidsConfig = {
        brokerageTarget: form.kids.brokerageTarget ?? 0,
        plan529Target: form.kids.plan529Target ?? 0,
        kids,
      };
      return config;
    }
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
export function planInputsChanged(form: GoalForm, existing: Goal): boolean {
  if (form.type !== existing.type) return true;
  switch (form.type) {
    case 'KIDS': {
      const c = existing.config as KidsConfig;
      if ((form.kids.brokerageTarget ?? 0) !== c.brokerageTarget) return true;
      if ((form.kids.plan529Target ?? 0) !== c.plan529Target) return true;
      if (form.kids.kids.length !== c.kids.length) return true;
      return form.kids.kids.some((row, i) => {
        const year = row.undecided ? null : row.birthYear;
        const month = row.undecided ? null : row.birthMonth;
        return year !== c.kids[i].birthYear || month !== c.kids[i].birthMonth;
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

/** Field-keyed error messages. Empty object means the form is valid. */
export function validateForm(form: GoalForm, now: Date): Record<string, string> {
  const errors: Record<string, string> = {};
  const currentYear = now.getFullYear();
  const yearMessage = `Enter a year from ${currentYear} to ${currentYear + 50}.`;

  if (!form.name.trim()) errors['name'] = 'Give this goal a name.';

  const checkYear = (): void => {
    const y = form.targetYear;
    if (y == null || !Number.isInteger(y) || y < currentYear || y > currentYear + 50) {
      errors['targetYear'] = yearMessage;
    }
  };

  switch (form.type) {
    case 'KIDS': {
      const a = form.kids.brokerageTarget;
      const b = form.kids.plan529Target;
      const hasA = a != null && a > 0;
      const hasB = b != null && b > 0;
      if (!hasA && !hasB) errors['kidsTargets'] = 'Enter at least one target for your kids.';
      else if ((a ?? 0) > MAX_AMOUNT || (b ?? 0) > MAX_AMOUNT) {
        errors['kidsTargets'] = 'That is more than we can plan for. Try a smaller amount.';
      }
      form.kids.kids.forEach((row, i) => {
        if (row.undecided) return;
        const y = row.birthYear;
        if (y != null && currentYear - y >= 18) {
          errors[`kid.${i}.birthYear`] = 'Already 18 or older. Use Something else with a target year instead.';
        } else if (y == null || y < currentYear - 17 || y > currentYear + 10) {
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
