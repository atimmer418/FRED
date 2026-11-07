/**
 * Goal plan view model. Pure, no Angular: turns a goal (saved or drafted) into
 * the numbers and copy the goal page and the my-profile summary row show.
 *
 * Two different rates meet here:
 *   - the LIVE plan uses `ctx.marketRate` (the chip) through the proposals, and
 *   - the STATUS uses the goal's stored plan rate, so a chip tap never moves it.
 */

import {
  AccountState,
  Goal,
  HouseCarConfig,
  KidInput,
  KidsConfig,
  RateKind,
  TargetConfig,
  EmergencyFundConfig,
  VacationConfig,
} from '../../models/goal.model';
import {
  AccountPlan,
  FirstYearPlan,
  LumpOccurrence,
  PlanStatus,
  SimplePlan,
  VacationPlan,
  firstYearDue,
  futureValue,
  horizonMonths,
  kidAccountRateKind,
  kidDeadline,
  monthsBetween,
  planStatus,
  proposeFirstYearPlan,
  proposeKidAccountPlan,
  proposeSimplePlan,
  proposeVacationPlan,
  simpleTarget,
  vacationStatus,
} from '../../utils/goal-plan.utils';
import { KidCostFigures, kidCostFigures } from '../../utils/kid-cost.utils';
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
  /** Simple goals only. KIDS ignore it and resolve a kind per account from the anchor (see kidsView). */
  rateKindOverride: RateKind | null;
  /** True for a setup draft, or an edit draft whose plan inputs changed: the plan anchors at `now`. */
  reanchorPending?: boolean;
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
  totals: { monthly: number; newYear: number };
  perKid: KidPlanRow[];
}

export interface FirstYearRow {
  kidIndex: number;
  label: string;
  plan: FirstYearPlan;
  balance: number;
  /** A finished fund stays visible for 12 months after its end month, then hides. */
  hidden: boolean;
}

export interface GoalPlanView {
  monthlyTotal: number;
  /**
   * What is due now in a deadline month or after it: the shortfall the plan can no longer spread
   * over months. Simple goals: the plan's dueNow. KIDS: the 529 and brokerage accounts' dueNow.
   */
  dueNow: number;
  perPaycheck: number | null;
  rateKind: RateKind;
  rate: number;
  headline: string;
  subtitle: string;
  status: PlanStatus;
  progressPct: number;
  relative: boolean;
  kids?: KidAccountView[];
  /** Always set for a KIDS goal. */
  kidCosts?: KidCostFigures;
  /** Set when at least one kid has a first-year fund account. */
  firstYear?: { totalMonthly: number; endsLabel: string | null; rows: FirstYearRow[] };
  simple?: SimplePlan;
  vacation?: VacationPlan;
}

const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

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

/** The subtitle's lead: "$X/mo", or "$X due now" when nothing is left to spread over months. */
function amountLead(monthly: number, dueNow: number): string {
  return monthly === 0 && dueNow > 0 ? `${fmtMoney(dueNow)} due now` : `${fmtMoney(monthly)}/mo`;
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

  // The plan was anchored here. A setup draft, or an edit draft whose plan inputs changed,
  // re-anchors at "now".
  const parsedAnchor = new Date(goal.planAnchoredAt);
  const anchorDate = ctx.reanchorPending || !Number.isFinite(parsedAnchor.getTime()) ? ctx.now : parsedAnchor;

  /**
   * Each kid account has its own rate kind, fixed at the anchor: cash when the account had under
   * 36 months to go at the anchor date, market otherwise. The goal-level kind and the context
   * override play no part for a kids goal.
   */
  const accountKind = (birthYear: number | null, birthMonth: number | null): RateKind => {
    const dl = kidDeadline(anchorDate, birthYear, birthMonth);
    return kidAccountRateKind(anchorDate, dl.year, dl.month);
  };

  const fundRows = firstYearRows(kids, goal, ctx, anchorDate);
  const unfinishedFunds = fundRows.filter(r => !r.row.plan.finished);

  // Status lumps come from the ANCHOR-time plan, so their month indices count from planAnchoredAt
  // like planStatus does, and the chip can never move them.
  const anchorLumps: LumpOccurrence[] = [];

  // Gift days post to the brokerage (the nest egg) when it has a target, else to the 529.
  const giftAccount: KidAccountKey = cfg.brokerageTarget > 0 ? 'brokerage' : 'plan529';
  const giftDays = !!cfg.giftDays;

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
          giftDays: giftDays && def.account === giftAccount,
        });
        const anchorPlan = proposeKidAccountPlan({
          target: def.target,
          balance: account.anchorBalance,
          birthYear: kid.birthYear,
          birthMonth: kid.birthMonth,
          marketRate: goal.planRate,
          rateKindOverride: kind,
          now: anchorDate,
          giftDays: giftDays && def.account === giftAccount,
        });
        anchorLumps.push(...anchorPlan.lumps);
        const relative = plan.assumedBirthYear || (kid.birthYear != null && kid.birthYear > thisYear);
        return { label: kid.label, plan, balance, relative };
      });
      const totals = perKid.reduce(
        (t, k) => ({
          monthly: t.monthly + k.plan.monthly,
          newYear: t.newYear + k.plan.newYearLump,
        }),
        { monthly: 0, newYear: 0 },
      );
      return { account: def.account, label: def.label, totals, perKid };
    });

  const plans: AccountPlan[] = accounts.reduce<AccountPlan[]>(
    (all, a) => all.concat(a.perKid.map(k => k.plan)), []);
  const fundMonthly = unfinishedFunds.reduce((sum, f) => sum + f.row.plan.monthly, 0);
  const monthlyTotal = accounts.reduce((sum, a) => sum + a.totals.monthly, 0) + fundMonthly;
  // Cash only when every unfinished account plan is cash; no unfinished plan reads as market.
  const unfinishedKinds = [...plans.map(p => p.rateKind), ...unfinishedFunds.map(f => f.row.plan.rateKind)];
  const rateKind: RateKind = unfinishedKinds.length > 0 && unfinishedKinds.every(k => k === 'cash') ? 'cash' : 'market';
  const rate = rateKind === 'cash' ? CASH_RATE : ctx.marketRate;

  // Status: one family plan over the sums of every 529 and brokerage account, plus unfinished
  // first-year funds, which are graded on their own path and added to the expected balance.
  // An account with no target is left out, the same as on the plan cards.
  const plannedKeys = accountDefs.filter(def => def.target > 0).map(def => def.account);
  let balance = 0;
  let anchorBalance = 0;
  let anchorMonthly = 0;
  const loggedAt: string[] = [];
  for (const kid of kids) {
    for (const key of plannedKeys) {
      const a = kid.accounts[key];
      balance += a.balance;
      anchorBalance += a.anchorBalance;
      anchorMonthly += a.anchorMonthly;
      if (a.balanceUpdatedAt) loggedAt.push(a.balanceUpdatedAt);
    }
  }
  let target = kids.length * ((cfg.plan529Target || 0) + (cfg.brokerageTarget || 0));
  for (const f of unfinishedFunds) {
    balance += f.account.balance;
    target += f.row.plan.target;
    if (f.account.balanceUpdatedAt) loggedAt.push(f.account.balanceUpdatedAt);
  }
  const latest: string | null = loggedAt.reduce<string | null>(
    (best, iso) => (best === null || new Date(iso).getTime() > new Date(best).getTime() ? iso : best), null);
  const monthsLogged = latest ? monthsBetween(goal.planAnchoredAt, latest) : 0;
  const extraExpected = unfinishedFunds.reduce((sum, f) => {
    const fundRate = f.row.plan.rateKind === 'cash' ? CASH_RATE : goal.planRate;
    const months = Math.min(monthsLogged, horizonMonths(anchorDate, f.row.plan.due.year, f.row.plan.due.month));
    return sum + futureValue(f.account.anchorBalance, f.account.anchorMonthly, months, fundRate, []);
  }, 0);
  // No contributions are expected past the last 529 or brokerage deadline, counted from the anchor.
  const accountHorizons = plannedKeys.length > 0
    ? kids.map(kid => {
        const dl = kidDeadline(anchorDate, kid.birthYear, kid.birthMonth);
        return horizonMonths(anchorDate, dl.year, dl.month);
      })
    : [];
  const maxMonths = accountHorizons.length > 0 ? Math.max(...accountHorizons) : undefined;
  // A 0 target (every fund finished, no 529 or brokerage target) is never "done".
  const status: PlanStatus = target > 0 || goal.completed
    ? planStatus({
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
        extraExpected,
        maxMonths,
        now: ctx.now,
      })
    : { kind: 'started', delta: 0, asOf: null, stale: false };

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

  // A fund exists and neither long-term account has a target: nothing here runs to an 18th birthday.
  const fundOnly = fundRows.length > 0 && !(cfg.plan529Target > 0) && !(cfg.brokerageTarget > 0);
  const kidsLabel = plural(kids.length, 'kid', 'kids');

  const unfinishedDue = unfinishedFunds
    .map(f => f.row.plan.due)
    .sort((a, b) => a.year * 12 + a.month - (b.year * 12 + b.month));
  const lastDue = unfinishedDue[unfinishedDue.length - 1];
  const dueNow = plans.reduce((sum, p) => sum + p.dueNow, 0);
  const lead = amountLead(monthlyTotal, dueNow);

  return {
    monthlyTotal,
    dueNow,
    rateKind,
    rate,
    headline: fundOnly
      ? 'Ready for the first year'
      : kids.length === 1 ? 'Ready by their 18th birthday' : 'Ready by each 18th birthday',
    subtitle: fundOnly
      ? `${lead} · ${kidsLabel} · first-year fund`
      : `${lead} · ${kidsLabel} · ${span}`,
    status,
    progressPct: clampPct(balance, target),
    relative: perKidRelative.some(Boolean),
    kids: accounts,
    kidCosts: kidCostFigures(kids.length),
    ...(fundRows.length > 0
      ? {
          firstYear: {
            totalMonthly: fundMonthly,
            endsLabel: lastDue ? `${MONTH_ABBR[lastDue.month - 1]} ${lastDue.year}` : null,
            rows: fundRows.map(f => f.row),
          },
        }
      : {}),
  };
}

/**
 * One row per kid with a first-year fund account. The end month is the stored one; a kid saved
 * without it falls back to the rule from the anchor. Every fund resolves its own rate kind from
 * the anchor date to its end month.
 */
function firstYearRows(
  kids: KidInput[],
  goal: GoalPlanInput,
  ctx: GoalPlanContext,
  anchorDate: Date,
): Array<{ row: FirstYearRow; account: AccountState }> {
  const out: Array<{ row: FirstYearRow; account: AccountState }> = [];
  kids.forEach((kid, kidIndex) => {
    const account = kid.accounts.firstYear;
    if (!account) return;
    const stored = kid.firstYearDueYear != null && kid.firstYearDueMonth != null
      ? { year: kid.firstYearDueYear, month: kid.firstYearDueMonth }
      : null;
    const due = stored ?? firstYearDue(kid.birthYear, kid.birthMonth, anchorDate);
    const plan = proposeFirstYearPlan({
      target: kid.firstYearTarget ?? 0,
      balance: account.balance,
      due,
      undecided: kid.birthYear == null,
      marketRate: ctx.marketRate,
      rateKind: kidAccountRateKind(anchorDate, due.year, due.month),
      now: ctx.now,
    });
    const monthsSinceDue = (ctx.now.getFullYear() - due.year) * 12 + (ctx.now.getMonth() + 1 - due.month);
    out.push({
      row: { kidIndex, label: kid.label, plan, balance: account.balance, hidden: plan.finished && monthsSinceDue > 12 },
      account,
    });
  });
  return out;
}

function vacationView(goal: GoalPlanInput, ctx: GoalPlanContext): Omit<GoalPlanView, 'perPaycheck'> {
  const cfg = goal.config as VacationConfig;
  const plan = proposeVacationPlan(cfg, ctx.now, goal.planAnchoredAt);
  return {
    monthlyTotal: plan.monthly,
    dueNow: 0,
    // Trip money is short-term cash with no growth assumed.
    rateKind: 'cash',
    rate: CASH_RATE,
    headline: `${fmtCompact(plan.annual)} a year for ${plural(cfg.tripsPerYear, 'trip', 'trips')}`,
    subtitle: `${fmtMoney(plan.monthly)}/mo · ${plural(cfg.tripsPerYear, 'trip', 'trips')} a year`,
    status: vacationStatus(plan, ctx.now),
    progressPct: clampPct(plan.savedThisYear, plan.annual),
    relative: false,
    vacation: plan,
  };
}

/** The date a simple goal's plan was anchored, or `now` for a pending re-anchor or an unreadable date. */
function statusAnchorDate(goal: GoalPlanInput, ctx: GoalPlanContext): Date {
  const parsed = new Date(goal.planAnchoredAt);
  return ctx.reanchorPending || !Number.isFinite(parsed.getTime()) ? ctx.now : parsed;
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
    // No contributions are expected after the target month, counted from the anchor.
    maxMonths: horizonMonths(statusAnchorDate(goal, ctx), targetYear, 12),
    now: ctx.now,
  });

  const lead = amountLead(plan.monthly, plan.dueNow);
  let subtitle: string;
  if (goal.type === 'HOUSE' || goal.type === 'CAR') {
    const pct = (goal.config as HouseCarConfig).downPaymentPct;
    subtitle = `${lead} · ${pct == null ? 'full price' : `${pct}% down`} · ${targetYear}`;
  } else {
    subtitle = `${lead} · ${fmtCompact(target)} by ${targetYear}`;
  }
  const dueNowOnly = plan.monthly === 0 && plan.dueNow > 0;

  return {
    monthlyTotal: plan.monthly,
    dueNow: plan.dueNow,
    rateKind: plan.rateKind,
    rate: plan.rate,
    headline: dueNowOnly
      ? 'Target month reached'
      : `Reaches ${fmtCompact(target)} in ${MONTH_ABBR[plan.reaches.month - 1]} ${plan.reaches.year}`,
    subtitle,
    status,
    progressPct: clampPct(goal.currentBalance, target),
    relative: false,
    simple: plan,
  };
}
