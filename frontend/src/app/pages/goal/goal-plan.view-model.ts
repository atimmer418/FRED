/**
 * Goal plan view model. Pure, no Angular: turns a goal (saved or drafted) into
 * the numbers and copy the goal page and the my-profile summary row show.
 *
 * Two different rates meet here:
 *   - the LIVE plan uses `ctx.marketRate` (the chip) through the proposals, and
 *   - the STATUS uses the goal's stored plan rate, so a chip tap never moves it.
 */

import {
  Goal,
  HouseCarConfig,
  KidsConfig,
  RateKind,
  TargetConfig,
  EmergencyFundConfig,
  VacationConfig,
} from '../../models/goal.model';
import {
  AccountPlan,
  CASH_HORIZON_MONTHS,
  LumpOccurrence,
  PlanStatus,
  SimplePlan,
  VacationPlan,
  horizonMonths,
  kidDeadline,
  planStatus,
  proposeKidAccountPlan,
  proposeSimplePlan,
  proposeVacationPlan,
  simpleTarget,
  vacationStatus,
} from '../../utils/goal-plan.utils';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { CASH_RATE } from '../../services/rate-assumptions.service';

export type GoalPlanInput = Pick<
  Goal,
  | 'type'
  | 'config'
  | 'currentBalance'
  | 'completed'
  | 'clientId'
  | 'anchorBalance'
  | 'anchorMonthly'
  | 'planRate'
  | 'planRateKind'
  | 'planAnchoredAt'
  | 'balanceUpdatedAt'
>;

export interface GoalPlanContext {
  marketRate: number;
  frequency: string;
  now: Date;
  /** Simple goals only. KIDS resolve a kind per account from the anchor (see kidsView). */
  rateKindOverride: RateKind | null;
}

export type KidAccountKey = 'plan529' | 'brokerage';

export interface KidPlanRow {
  label: string;
  plan: AccountPlan;
  balance: number;
  relative: boolean;
}

export interface KidAccountView {
  account: KidAccountKey;
  label: '529 plans' | 'Brokerage';
  totals: { monthly: number; birthday: number; newYear: number };
  perKid: KidPlanRow[];
}

export interface GoalPlanView {
  monthlyTotal: number;
  perPaycheck: number | null;
  rateKind: RateKind;
  rate: number;
  headline: string;
  subtitle: string;
  status: PlanStatus;
  progressPct: number;
  relative: boolean;
  reasonLine: string | null;
  kids?: KidAccountView[];
  simple?: SimplePlan;
  vacation?: VacationPlan;
}

const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const REASON_COPY: Record<'short_horizon' | 'small_plan', string> = {
  short_horizon: 'Under two years to go, so this one is a steady monthly amount',
  small_plan: 'A small plan, so this one is a steady monthly amount',
};

const IMPORTED_SUBTITLE = 'Plan updated, take a look';

// ── Formatting ───────────────────────────────────────────────────────────────

/** Whole dollars with thousands separators: 1310 -> "$1,310". */
export function fmtMoney(n: number): string {
  const v = Number.isFinite(n) ? Math.round(n) : 0;
  return '$' + v.toLocaleString('en-US');
}

/** Short money: 950 -> "$950", 1500 -> "$1.5K", 372338 -> "$372K", 1180000 -> "$1.18M". */
export function fmtCompact(n: number): string {
  const v = Number.isFinite(n) ? Math.max(0, n) : 0;
  const trim = (s: string) => s.replace(/\.?0+$/, '');
  if (v < 1_000) return '$' + Math.round(v);
  if (v < 10_000) return '$' + trim((v / 1_000).toFixed(1)) + 'K';
  const k = Math.round(v / 1_000);
  if (k < 1_000) return '$' + k + 'K';
  return '$' + trim((v / 1_000_000).toFixed(2)) + 'M';
}

/** "Mon YYYY" from a local ISO timestamp, or '' when missing or unreadable. */
export function fmtMonthYear(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return '';
  return `${MONTH_ABBR[d.getMonth()]} ${d.getFullYear()}`;
}

function clampPct(part: number, whole: number): number {
  if (!(whole > 0)) return 0;
  return Math.min(100, Math.max(0, (part / whole) * 100));
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

// ── Builder ──────────────────────────────────────────────────────────────────

export function buildGoalPlanView(goal: GoalPlanInput, ctx: GoalPlanContext): GoalPlanView {
  const imported = goal.clientId !== null && goal.anchorMonthly === 0;
  // The status always grades against the stored anchor rate, never the live chip.
  const statusRate = goal.planRateKind === 'cash' ? CASH_RATE : goal.planRate;

  let view: Omit<GoalPlanView, 'perPaycheck'>;
  if (goal.type === 'KIDS') {
    view = kidsView(goal, ctx, imported, statusRate);
  } else if (goal.type === 'VACATION') {
    view = vacationView(goal, ctx);
  } else {
    view = simpleView(goal, ctx, imported, statusRate);
  }

  return {
    ...view,
    subtitle: imported ? IMPORTED_SUBTITLE : view.subtitle,
    perPaycheck: InvestmentFrequencyUtils.toPerPaycheckEquivalent(view.monthlyTotal, ctx.frequency),
  };
}

function kidsView(
  goal: GoalPlanInput,
  ctx: GoalPlanContext,
  imported: boolean,
  statusRate: number,
): Omit<GoalPlanView, 'perPaycheck'> {
  const cfg = goal.config as KidsConfig;
  const kids = cfg.kids ?? [];
  const thisYear = ctx.now.getFullYear();

  const accountDefs: Array<{ account: KidAccountKey; label: KidAccountView['label']; target: number }> = [
    { account: 'plan529', label: '529 plans', target: cfg.plan529Target },
    { account: 'brokerage', label: 'Brokerage', target: cfg.brokerageTarget },
  ];

  // The plan was anchored here (a setup draft anchors at "now").
  const parsedAnchor = new Date(goal.planAnchoredAt);
  const anchorDate = Number.isFinite(parsedAnchor.getTime()) ? parsedAnchor : ctx.now;

  /**
   * Each kid account has its own rate kind, fixed at the anchor: cash when the goal was planned as
   * cash, or when the account had under 36 months to go at the anchor date; market otherwise.
   */
  const accountKind = (birthYear: number | null, birthMonth: number | null): RateKind => {
    if (goal.planRateKind === 'cash') return 'cash';
    const dl = kidDeadline(anchorDate, birthYear, birthMonth);
    return horizonMonths(anchorDate, dl.year, dl.month) < CASH_HORIZON_MONTHS ? 'cash' : 'market';
  };

  // Status lumps come from the ANCHOR-time plan, so their month indices count from planAnchoredAt
  // like planStatus does, and the chip can never move them.
  const anchorLumps: LumpOccurrence[] = [];

  // An account with no target has nothing to plan (a 529-only family is valid).
  const accounts: KidAccountView[] = accountDefs
    .filter(def => def.target > 0)
    .map(def => {
      const perKid = kids.map(kid => {
        const account = kid.accounts[def.account];
        const balance = account.balance;
        const kind = accountKind(kid.birthYear, kid.birthMonth);
        const plan = proposeKidAccountPlan({
          target: def.target,
          balance,
          birthYear: kid.birthYear,
          birthMonth: kid.birthMonth,
          marketRate: ctx.marketRate,
          rateKindOverride: kind,
          now: ctx.now,
        });
        const anchorPlan = proposeKidAccountPlan({
          target: def.target,
          balance: account.anchorBalance,
          birthYear: kid.birthYear,
          birthMonth: kid.birthMonth,
          marketRate: goal.planRate,
          rateKindOverride: kind,
          now: anchorDate,
        });
        anchorLumps.push(...anchorPlan.lumps);
        const relative = plan.assumedBirthYear || (kid.birthYear != null && kid.birthYear > thisYear);
        return { label: kid.label, plan, balance, relative };
      });
      const totals = perKid.reduce(
        (t, k) => ({
          monthly: t.monthly + k.plan.monthly,
          birthday: t.birthday + k.plan.birthdayLump,
          newYear: t.newYear + k.plan.newYearLump,
        }),
        { monthly: 0, birthday: 0, newYear: 0 },
      );
      return { account: def.account, label: def.label, totals, perKid };
    });

  const plans: AccountPlan[] = accounts.reduce<AccountPlan[]>(
    (all, a) => all.concat(a.perKid.map(k => k.plan)), []);
  const monthlyTotal = accounts.reduce((sum, a) => sum + a.totals.monthly, 0);
  const rateKind: RateKind = plans.length > 0 && plans.every(p => p.rateKind === 'cash') ? 'cash' : 'market';
  const rate = rateKind === 'cash' ? CASH_RATE : ctx.marketRate;

  // Status: one family plan over the sums of every account of every kid.
  let balance = 0;
  let anchorBalance = 0;
  let anchorMonthly = 0;
  let latest: string | null = null;
  for (const kid of kids) {
    for (const key of ['plan529', 'brokerage'] as const) {
      const a = kid.accounts[key];
      balance += a.balance;
      anchorBalance += a.anchorBalance;
      anchorMonthly += a.anchorMonthly;
      if (a.balanceUpdatedAt && (!latest || new Date(a.balanceUpdatedAt).getTime() > new Date(latest).getTime())) {
        latest = a.balanceUpdatedAt;
      }
    }
  }
  const target = kids.length * ((cfg.plan529Target || 0) + (cfg.brokerageTarget || 0));
  const status = planStatus({
    completed: goal.completed,
    imported,
    target,
    balance,
    anchorBalance,
    anchorMonthly,
    planAnchoredAt: goal.planAnchoredAt,
    balanceUpdatedAt: latest,
    rate: statusRate,
    lumps: anchorLumps,
    now: ctx.now,
  });

  const perKidRelative = kids.map((kid, i) =>
    accounts[0]?.perKid[i]?.relative ?? (kid.birthYear == null || kid.birthYear > thisYear));
  const allRelative = kids.length > 0 && perKidRelative.every(Boolean);

  let span: string;
  if (allRelative) {
    span = kids.length === 1 ? '18-year plan' : '18-year plans';
  } else {
    const years = kids
      .filter((_, i) => !perKidRelative[i])
      .map(kid => (kid.birthYear as number) + 18);
    const lo = Math.min(...years);
    const hi = Math.max(...years);
    span = lo === hi ? String(lo) : `${lo} to ${hi}`;
  }

  // Reason line only when no account of any kid carries rituals.
  let reasonLine: string | null = null;
  if (plans.length > 0 && plans.every(p => p.ritualsReason !== null)) {
    reasonLine = REASON_COPY[plans.some(p => p.ritualsReason === 'short_horizon') ? 'short_horizon' : 'small_plan'];
  }

  return {
    monthlyTotal,
    rateKind,
    rate,
    headline: kids.length === 1 ? 'Ready by their 18th birthday' : 'Ready by each 18th birthday',
    subtitle: `${fmtMoney(monthlyTotal)}/mo · ${plural(kids.length, 'kid', 'kids')} · ${span}`,
    status,
    progressPct: clampPct(balance, target),
    relative: perKidRelative.some(Boolean),
    reasonLine,
    kids: accounts,
  };
}

function vacationView(goal: GoalPlanInput, ctx: GoalPlanContext): Omit<GoalPlanView, 'perPaycheck'> {
  const cfg = goal.config as VacationConfig;
  const plan = proposeVacationPlan(cfg, ctx.now);
  return {
    monthlyTotal: plan.monthly,
    // Trip money is short-term cash with no growth assumed.
    rateKind: 'cash',
    rate: CASH_RATE,
    headline: `${fmtCompact(plan.annual)} a year for ${plural(cfg.tripsPerYear, 'trip', 'trips')}`,
    subtitle: `${fmtMoney(plan.monthly)}/mo · ${plural(cfg.tripsPerYear, 'trip', 'trips')} a year`,
    status: vacationStatus(plan, ctx.now),
    progressPct: clampPct(plan.savedThisYear, plan.annual),
    relative: false,
    reasonLine: null,
    vacation: plan,
  };
}

function targetYearOf(goal: GoalPlanInput): number {
  return (goal.config as HouseCarConfig | EmergencyFundConfig | TargetConfig).targetYear;
}

function simpleView(
  goal: GoalPlanInput,
  ctx: GoalPlanContext,
  imported: boolean,
  statusRate: number,
): Omit<GoalPlanView, 'perPaycheck'> {
  const target = simpleTarget(goal.type, goal.config);
  const targetYear = targetYearOf(goal);
  const plan = proposeSimplePlan({
    target,
    balance: goal.currentBalance,
    targetYear,
    marketRate: ctx.marketRate,
    forceCash: goal.type === 'EMERGENCY_FUND',
    rateKindOverride: ctx.rateKindOverride,
    now: ctx.now,
  });
  const status = planStatus({
    completed: goal.completed,
    imported,
    target,
    balance: goal.currentBalance,
    anchorBalance: goal.anchorBalance,
    anchorMonthly: goal.anchorMonthly,
    planAnchoredAt: goal.planAnchoredAt,
    balanceUpdatedAt: goal.balanceUpdatedAt,
    rate: statusRate,
    lumps: [],
    now: ctx.now,
  });

  let subtitle: string;
  if (goal.type === 'HOUSE' || goal.type === 'CAR') {
    const pct = (goal.config as HouseCarConfig).downPaymentPct;
    subtitle = `${fmtMoney(plan.monthly)}/mo · ${pct == null ? 'full price' : `${pct}% down`} · ${targetYear}`;
  } else {
    subtitle = `${fmtMoney(plan.monthly)}/mo · ${fmtCompact(target)} by ${targetYear}`;
  }

  return {
    monthlyTotal: plan.monthly,
    rateKind: plan.rateKind,
    rate: plan.rate,
    headline: `Reaches ${fmtCompact(target)} in ${MONTH_ABBR[plan.reaches.month - 1]} ${plan.reaches.year}`,
    subtitle,
    status,
    progressPct: clampPct(goal.currentBalance, target),
    relative: false,
    reasonLine: null,
    simple: plan,
  };
}
