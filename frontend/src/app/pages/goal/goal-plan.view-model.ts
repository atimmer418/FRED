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
import { audienceLabel } from '../../utils/goal-quiz.utils';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { CASH_RATE } from '../../services/rate-assumptions.service';
import {
  everydayMonthlyForKid,
  everydayRowLabel,
  everydaySubLine,
  kidAgeToday,
  kidsAtHome,
} from '../../utils/kid-cost.utils';
import { buildKidTimeline } from './kid-timeline.model';

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
> & {
  /** The audience of a CUSTOM goal. A draft without one reads "My goal". */
  name?: string;
};

/** Whether a unit's money is planned in the market or in cash, from its rate kind. */
export type UnitPill = 'Invested' | 'Cash';

export type HeroLabel = 'Save this month' | 'Due now' | 'Family plan';

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
  pill: UnitPill;
}

export interface KidAccountView {
  account: KidAccountKey;
  label: '529 plans' | 'Brokerage';
  totals: { monthly: number };
  perKid: KidPlanRow[];
}

export interface FirstYearRow {
  kidIndex: number;
  label: string;
  plan: FirstYearPlan;
  balance: number;
  /** A finished fund stays visible for 12 months after its end month, then hides. */
  hidden: boolean;
  pill: UnitPill;
}

/**
 * KIDS only: what one kid costs this month by FRED's estimate (built on USDA numbers). A kid who has
 * not arrived and a kid in their first year cost 0 here (the baby fund is year one), and so does a
 * kid of 18 or older (the USDA study stops at 17).
 */
export interface EverydayView {
  /** The kid's everyday cost this month, nearest $10; 0 unless priced. */
  monthly: number;
  /** "Age 2 costs" when priced, else "Everyday". */
  label: string;
  /** What the cost covers at this age when priced, else ''. */
  subLine: string;
  /** Why an unpriced kid costs nothing yet: unborn and year one only; null when priced or grown. */
  fromLine: string | null;
  state: 'unborn' | 'year_one' | 'priced' | 'grown';
}

export interface GoalPlanView {
  /**
   * "Due now" when the deadline month has come with a shortfall, else "Family plan" for KIDS and
   * "Save this month" for every other type.
   */
  heroLabel: HeroLabel;
  /** Who the plan is for: "for all 3 kids", "for your home", or a custom goal's name. */
  audience: string;
  /** The How FRED helps note, without its bold "How FRED helps." lead. */
  howFredHelps: string;
  /** KIDS only: what the monthly covers (the whole month: the funds plus everyday costs), shown in the info sheet. */
  coversLine: string | null;
  /** Market plans: the contributions-vs-growth case for leaving the money invested. */
  whyInvestLine: string | null;
  /**
   * What the hero shows. KIDS: the whole month, investMonthly plus every kid's everyday figure.
   * Every other type: the same as investMonthly.
   */
  monthlyTotal: number;
  /**
   * What the plan's units (529s, brokerage, baby funds, or a simple plan) ask for a month. Everything
   * that grades, anchors or reasons about the plan reads this, never the everyday figure.
   */
  investMonthly: number;
  /** KIDS only: one per kid in config order, what that kid costs this month. */
  everyday?: EverydayView[];
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
  /** KIDS only: the family timeline under the hero. */
  timeline?: ReturnType<typeof buildKidTimeline>;
  /** Set when at least one kid has a first-year fund account. */
  firstYear?: { totalMonthly: number; endsLabel: string | null; rows: FirstYearRow[] };
  /** Every type but KIDS: the one pill at the end of the band (KIDS carry one per row). */
  pill?: UnitPill;
  simple?: SimplePlan;
  vacation?: VacationPlan;
}

/** What each sub-view builds; the builder adds the rest. */
type SubView = Omit<GoalPlanView, 'perPaycheck' | 'heroLabel'>;

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

/** Nothing is left to spread over months: the deadline month has come with a shortfall. */
function isDueNowOnly(monthly: number, dueNow: number): boolean {
  return monthly === 0 && dueNow > 0;
}

/**
 * The subtitle's lead: "$X due now" when the invested units have nothing left to spread over months,
 * else "$X/mo" of what the hero shows (KIDS: the whole month, everyday costs included).
 */
function amountLead(investMonthly: number, dueNow: number, shown: number = investMonthly): string {
  return isDueNowOnly(investMonthly, dueNow) ? `${fmtMoney(dueNow)} due now` : `${fmtMoney(shown)}/mo`;
}

function pillOf(kind: RateKind): UnitPill {
  return kind === 'cash' ? 'Cash' : 'Invested';
}

/** Who a goal other than KIDS is for. */
function goalAudience(goal: GoalPlanInput): string {
  switch (goal.type) {
    case 'HOUSE':
      return 'for your home';
    case 'CAR':
      return 'for your car';
    case 'VACATION': {
      const trips = (goal.config as VacationConfig).tripsPerYear;
      return trips === 1 ? 'for your trip' : `for ${trips} trips a year`;
    }
    case 'EMERGENCY_FUND':
      return 'for your safety net';
    case 'WEDDING':
      return 'for the wedding';
    default:
      return goal.name?.trim() || 'My goal';
  }
}

const CASH_HELP = 'Keep this in cash, like a high-yield savings account, ready the day you need it. FRED tracks it here; the money stays outside the app.';

/**
 * The How FRED helps note, by the plan's unit rate kinds: all invested, mixed (naming the cash
 * part), or all cash. A plan with no unit left reads as invested, like its rate kind. Goal money
 * never lives in FRED (Andy, 2026-11-11: no portfolio split; FRED's investing is the Freedom
 * Plan alone), so the note says where it does live and that FRED keeps score.
 */
function howFredHelpsLine(monthly: number, units: ReadonlyArray<{ kind: RateKind; babyFund: boolean }>): string {
  const cash = units.filter(u => u.kind === 'cash');
  if (units.length > 0 && cash.length === units.length) return CASH_HELP;
  const lead = `FRED works out the amount (${fmtMoney(monthly)} a month right now) and keeps score here. `
    + 'The money lives outside the app, in accounts you open and fund yourself; '
    + 'FRED\'s own investing stays on your Freedom Plan, paycheck by paycheck.';
  if (cash.length === 0) return lead;
  const name = cash.every(u => u.babyFund)
    ? (cash.length === 1 ? 'The baby fund stays' : 'The baby funds stay')
    : 'The short-dated money stays';
  return `${lead} ${name} in cash.`;
}

/** "a", "a and b", "a, b and c". */
function englishList(items: string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** Which units a kids plan has; babyFunds counts the unborn kids with a fund (0 = none). */
interface CoveredUnits { babyFunds: number; college: boolean; nestEgg: boolean }

/** "college and the nest egg" / "college" / "the nest egg", the units that sit on top of everyday costs. */
function onTopNames(units: CoveredUnits): string[] {
  return [...(units.college ? ['college'] : []), ...(units.nestEgg ? ['the nest egg'] : [])];
}

/** Capitalise the first letter of a phrase that starts a sentence. */
const sentenceCase = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/**
 * KIDS only: what the monthly covers. The monthly is the whole month: the funds plus everyday costs.
 * College and the nest egg come on top of everyday costs (the USDA figure stops at 17 and leaves
 * college out); the baby fund IS the first year of those costs, saved ahead. The last sentence is
 * what the kids at home cost this month, read from the everyday entries the hero adds up, so the
 * two can never disagree. Null when no unit is left to pay for.
 */
function coversLineFor(
  units: CoveredUnits,
  kids: ReadonlyArray<{ label: string; birthYear: number | null; birthMonth: number | null; accounts: KidInput['accounts'] }>,
  everyday: ReadonlyArray<EverydayView>,
  now: Date,
): string | null {
  const funds = units.babyFunds > 1 ? 'the baby funds are' : 'the baby fund is';
  const names = [...(units.babyFunds > 0 ? [units.babyFunds > 1 ? 'the baby funds' : 'the baby fund'] : []), ...onTopNames(units)];
  if (names.length === 0) return null;
  const onTop = onTopNames(units);
  let relation: string;
  if (onTop.length > 0) {
    relation = `${sentenceCase(englishList(onTop))} ${onTop.length === 1 ? 'is' : 'are'} on top of everyday costs`
      + (units.babyFunds > 0 ? `; ${funds} the first year of them, saved ahead.` : '.');
  } else {
    relation = `${sentenceCase(funds)} the first year of everyday costs, saved ahead.`;
  }

  // Only priced kids (1 to 17) are named: year one is the baby fund (the sentence before says so)
  // and the USDA study stops at 17. The figure is the sum the hero adds, never a second calculation.
  const priced = kids
    .map((kid, i) => ({ kid, i, entry: everyday[i] }))
    .filter(k => k.entry?.state === 'priced')
    .map(({ kid, i, entry }) => ({
      label: kid.label.trim() || `Kid ${i + 1}`,
      age: kidAgeToday(kid.birthYear, kid.birthMonth, now),
      monthly: entry.monthly,
    }));
  const notYet = everyday.some(e => e.state === 'unborn');
  let today: string;
  if (priced.length > 0) {
    const who = englishList(priced.map(k => `${k.label} (${k.age})`));
    const cost = priced.reduce((sum, k) => sum + k.monthly, 0);
    today = `Everyday costs for ${who} run ~${fmtMoney(cost)} a month by FRED's estimate`
      + (notYet ? '; a kid who has not arrived yet costs nothing until they do.' : '.');
  } else if (everyday.some(e => e.state === 'year_one')) {
    // Only a kid under one with a baby fund has year one paid for; otherwise costs start at one.
    const funded = kids.some((kid, i) => everyday[i]?.state === 'year_one' && !!kid.accounts.firstYear);
    today = funded
      ? 'No everyday costs yet: the baby fund covers year one.'
      : 'No everyday costs yet: they start at the first birthday.';
  } else {
    today = notYet ? 'No everyday costs yet; they start when the first kid arrives.' : '';
  }
  return `The monthly is ${englishList(names)} plus everyday costs. ${relation}${today ? ' ' + today : ''}`;
}

const roundToThousand = (n: number) => Math.round(n / 1_000) * 1_000;

/**
 * The case for leaving invested money alone: of what the invested units reach, how much comes
 * from the user (what they have saved so far plus every contribution still to come) and how much
 * from growth, so the two shares add up to the target. Null when growth adds nothing, or when
 * either share rounds to $0.
 */
function whyInvestLineFor(reaches: number, fromYouRaw: number, fromGrowthRaw: number, kids: CoveredUnits | null): string | null {
  if (!(fromGrowthRaw > 0)) return null;
  const fromYou = roundToThousand(fromYouRaw);
  const fromGrowth = roundToThousand(fromGrowthRaw);
  if (fromYou <= 0 || fromGrowth <= 0) return null;
  let lead = 'You will not spend this for years, and those years are what make invested money grow.';
  if (kids) {
    // Name only the invested units this family has: "college and the nest egg come due at 18",
    // "college comes due at 18", or, for a family whose only invested unit is the baby fund,
    // "the baby fund comes due when they arrive".
    const onTop = onTopNames(kids);
    const due = onTop.length === 2 ? 'college and the nest egg come due at 18'
      : onTop.length === 1 ? `${onTop[0]} comes due at 18`
      : kids.babyFunds > 1 ? 'the baby funds come due when they arrive' : 'the baby fund comes due when they arrive';
    lead = `You will not spend the funds for years: ${due}, and those years are what make invested money grow.`;
  }
  const split = `Of the ${fmtMoney(reaches)} this plan reaches, ~${fmtMoney(fromYou)} comes from you and growth adds `
    + `~${fmtMoney(fromGrowth)}. Take it out early and growth stops, and the monthly from you goes up.`;
  return kids ? `${lead} ${split} Anything you need within three years is planned as cash instead.` : `${lead} ${split}`;
}

// ── Builder ──────────────────────────────────────────────────────────────────

export function buildGoalPlanView(goal: GoalPlanInput, ctx: GoalPlanContext): GoalPlanView {
  const imported = goal.clientId !== null && goal.anchorMonthly === 0;
  // The status always grades against the stored anchor rate, never the live chip.
  const statusRate = goal.planRateKind === 'cash' ? CASH_RATE : goal.planRate;

  let view: SubView;
  if (goal.type === 'KIDS') {
    view = kidsView(goal, ctx, imported, statusRate);
  } else if (goal.type === 'VACATION') {
    view = vacationView(goal, ctx);
  } else {
    view = simpleView(goal, ctx, imported, statusRate);
  }

  // Due now reads the invested units only: a kid's everyday cost never makes a plan due.
  const dueNowOnly = isDueNowOnly(view.investMonthly, view.dueNow);
  return {
    ...view,
    heroLabel: dueNowOnly ? 'Due now' : view.kids ? 'Family plan' : 'Save this month',
    // Nothing is left to spread over months, so there is no monthly to explain.
    coversLine: dueNowOnly ? null : view.coversLine,
    whyInvestLine: dueNowOnly ? null : view.whyInvestLine,
    subtitle: imported ? IMPORTED_SUBTITLE : view.subtitle,
    perPaycheck: InvestmentFrequencyUtils.toPerPaycheckEquivalent(view.monthlyTotal, ctx.frequency),
  };
}

function kidsView(
  goal: GoalPlanInput,
  ctx: GoalPlanContext,
  imported: boolean,
  statusRate: number,
): SubView {
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

  // Kids plans are monthly only. The New Year gift lump (gift days) left the plan page on
  // 2026-11-08; a stored giftDays flag is ignored, so an old goal re-solves as monthly only. A goal
  // saved while gift days were on keeps its lower stored anchorMonthly until its next re-anchor, so
  // its status reads a little ahead of the truth in the meantime (dev data only; nothing throws).

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
          giftDays: false,
        });
        const relative = plan.assumedBirthYear || (kid.birthYear != null && kid.birthYear > thisYear);
        return { label: kid.label, plan, balance, relative, pill: pillOf(plan.rateKind) };
      });
      const totals = perKid.reduce((t, k) => ({ monthly: t.monthly + k.plan.monthly }), { monthly: 0 });
      return { account: def.account, label: def.label, totals, perKid };
    });

  const plans: AccountPlan[] = accounts.reduce<AccountPlan[]>(
    (all, a) => all.concat(a.perKid.map(k => k.plan)), []);
  const fundMonthly = unfinishedFunds.reduce((sum, f) => sum + f.row.plan.monthly, 0);
  const investMonthly = accounts.reduce((sum, a) => sum + a.totals.monthly, 0) + fundMonthly;
  // The kids' whole month: the invested units plus what each kid at home costs today.
  const everyday = everydayViews(kids, ctx.now);
  const monthlyTotal = investMonthly + everyday.reduce((sum, e) => sum + e.monthly, 0);
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
        lumps: [],
        extraExpected,
        maxMonths,
        now: ctx.now,
      })
    : { kind: 'started', delta: 0, asOf: null, stale: false };

  const perKidRelative = kids.map((kid, i) =>
    accounts[0]?.perKid[i]?.relative ?? (kid.birthYear == null || kid.birthYear > thisYear));

  // A fund exists and neither long-term account has a target: nothing here runs to an 18th birthday.
  const fundOnly = fundRows.length > 0 && !(cfg.plan529Target > 0) && !(cfg.brokerageTarget > 0);

  const unfinishedDue = unfinishedFunds
    .map(f => f.row.plan.due)
    .sort((a, b) => a.year * 12 + a.month - (b.year * 12 + b.month));
  const lastDue = unfinishedDue[unfinishedDue.length - 1];
  const dueNow = plans.reduce((sum, p) => sum + p.dueNow, 0);
  const audience = audienceLabel(kids);

  const units = [
    ...plans.map(p => ({ kind: p.rateKind, babyFund: false })),
    ...unfinishedFunds.map(f => ({ kind: f.row.plan.rateKind, babyFund: true })),
  ];
  // Why invest it: only the invested units with months left. From you is each unit's monthly over
  // its horizon plus every lump inside it; growth is what the target needs beyond that and the balance.
  const investedAccounts = accounts.reduce<Array<{ account: KidAccountKey; target: number; balance: number; fromYou: number }>>(
    (all, a) => all.concat(a.perKid
      .filter(k => k.plan.rateKind === 'market' && k.plan.n > 0)
      .map(k => ({
        account: a.account,
        target: a.account === 'plan529' ? cfg.plan529Target : cfg.brokerageTarget,
        balance: k.balance,
        fromYou: k.plan.monthly * k.plan.n
          + k.plan.lumps.filter(l => l.monthIndex <= k.plan.n).reduce((sum, l) => sum + l.amount, 0),
      }))),
    [],
  );
  const investedFunds = unfinishedFunds
    .filter(f => f.row.plan.rateKind === 'market' && f.row.plan.n > 0)
    .map(f => ({ target: f.row.plan.target, balance: f.row.balance, fromYou: f.row.plan.monthly * f.row.plan.n }));
  const invested = [...investedAccounts, ...investedFunds];
  const investedTotal = (key: 'target' | 'balance' | 'fromYou') => invested.reduce((total, u) => total + u[key], 0);
  const reaches = investedTotal('target');
  const fromYou = investedTotal('fromYou');
  const investedUnits: CoveredUnits = {
    babyFunds: investedFunds.length,
    college: investedAccounts.some(u => u.account === 'plan529'),
    nestEgg: investedAccounts.some(u => u.account === 'brokerage'),
  };

  return {
    coversLine: kids.length > 0
      ? coversLineFor({
          babyFunds: unfinishedFunds.length,
          college: accounts.some(a => a.account === 'plan529'),
          nestEgg: accounts.some(a => a.account === 'brokerage'),
        }, kids, everyday, ctx.now)
      : null,
    whyInvestLine: invested.length > 0
      // From you = what is already saved plus every contribution still to come; growth is the rest.
      ? whyInvestLineFor(reaches, fromYou + investedTotal('balance'), Math.max(0, reaches - investedTotal('balance') - fromYou), investedUnits)
      : null,
    monthlyTotal,
    investMonthly,
    everyday,
    dueNow,
    rateKind,
    rate,
    headline: fundOnly
      ? 'Ready for the first year'
      : kids.length === 1 ? 'Ready by their 18th birthday' : 'Ready by each 18th birthday',
    subtitle: `${amountLead(investMonthly, dueNow, monthlyTotal)} ${audience}`,
    audience,
    howFredHelps: howFredHelpsLine(investMonthly, units),
    status,
    progressPct: clampPct(balance, target),
    relative: perKidRelative.some(Boolean),
    kids: accounts,
    timeline: buildKidTimeline(kids, ctx.now),
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
 * One everyday entry per kid, in config order. Priced kids (1 to 17) carry their age band's figure,
 * sized by how many kids are at home today; the rest read 0, with a line saying why for a kid who
 * has not arrived or is in their first year.
 */
function everydayViews(kids: ReadonlyArray<KidInput>, now: Date): EverydayView[] {
  const home = kidsAtHome(kids, now);
  return kids.map((kid): EverydayView => {
    const age = kidAgeToday(kid.birthYear, kid.birthMonth, now);
    if (age === null) {
      // "This year" with no month is not yet born, and has no month to name. With the baby fund
      // switched off nothing pays for year one, so the line promises no fund.
      const when = kid.birthYear !== null && kid.birthMonth !== null
        ? `From ${MONTH_ABBR[kid.birthMonth - 1]} ${kid.birthYear}`
        : 'From the day they arrive';
      const what = kid.accounts.firstYear ? 'the baby fund is year one' : 'everyday costs start at the first birthday';
      return { monthly: 0, label: 'Everyday', subLine: '', fromLine: `${when}: ${what}`, state: 'unborn' };
    }
    if (age === 0) {
      // No baby fund: nothing in the plan pays for year one, so the line just says when costs start.
      const fromLine = kid.accounts.firstYear ? 'The baby fund covers year one' : 'Everyday costs start at the first birthday';
      return { monthly: 0, label: 'Everyday', subLine: '', fromLine, state: 'year_one' };
    }
    if (age > 17) return { monthly: 0, label: 'Everyday', subLine: '', fromLine: null, state: 'grown' };
    return {
      monthly: everydayMonthlyForKid(age, home),
      label: everydayRowLabel(age),
      subLine: everydaySubLine(age),
      fromLine: null,
      state: 'priced',
    };
  });
}

/**
 * One row per kid with a first-year fund account. The end month is the stored one; a kid saved
 * without it falls back to the firstYearDue rule. Every baby fund is planned as cash.
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
    // `now` wins for a dated kid: it is the date isKidNotYetBorn reads, so a kid expected this
    // year with no month ends in December even on a goal anchored last year (spec decision 3).
    // An undated kid's 36 months still count from the anchor.
    const due = stored ?? firstYearDue(kid.birthYear, kid.birthMonth, kid.birthYear === null ? anchorDate : ctx.now);
    const plan = proposeFirstYearPlan({
      target: kid.firstYearTarget ?? 0,
      balance: account.balance,
      due,
      undecided: kid.birthYear == null,
      marketRate: ctx.marketRate,
      // Andy, 2026-11-10: money with a date on it stays out of the market, so a baby fund is cash
      // however far out its end month is. kidAccountRateKind still decides the 529 and nest egg.
      rateKind: 'cash',
      now: ctx.now,
    });
    const monthsSinceDue = (ctx.now.getFullYear() - due.year) * 12 + (ctx.now.getMonth() + 1 - due.month);
    out.push({
      row: {
        kidIndex,
        label: kid.label,
        plan,
        balance: account.balance,
        hidden: plan.finished && monthsSinceDue > 12,
        pill: pillOf(plan.rateKind),
      },
      account,
    });
  });
  return out;
}

function vacationView(goal: GoalPlanInput, ctx: GoalPlanContext): SubView {
  const cfg = goal.config as VacationConfig;
  const plan = proposeVacationPlan(cfg, ctx.now, goal.planAnchoredAt);
  const audience = goalAudience(goal);
  return {
    monthlyTotal: plan.monthly,
    investMonthly: plan.monthly,
    dueNow: 0,
    // Trip money is short-term cash with no growth assumed.
    rateKind: 'cash',
    rate: CASH_RATE,
    headline: `${fmtCompact(plan.annual)} a year for ${plural(cfg.tripsPerYear, 'trip', 'trips')}`,
    subtitle: `${amountLead(plan.monthly, 0)} ${audience}`,
    audience,
    howFredHelps: CASH_HELP,
    coversLine: null,
    whyInvestLine: null,
    pill: 'Cash',
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
): SubView {
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

  const audience = goalAudience(goal);

  return {
    monthlyTotal: plan.monthly,
    investMonthly: plan.monthly,
    dueNow: plan.dueNow,
    rateKind: plan.rateKind,
    rate: plan.rate,
    headline: isDueNowOnly(plan.monthly, plan.dueNow)
      ? 'Target month reached'
      : `Reaches ${fmtCompact(target)} in ${MONTH_ABBR[plan.reaches.month - 1]} ${plan.reaches.year}`,
    subtitle: `${amountLead(plan.monthly, plan.dueNow)} ${audience}`,
    audience,
    howFredHelps: howFredHelpsLine(plan.monthly, [{ kind: plan.rateKind, babyFund: false }]),
    coversLine: null,
    whyInvestLine: plan.rateKind === 'market' && plan.n > 0
      ? whyInvestLineFor(target, plan.fromYou + goal.currentBalance, plan.fromGrowth, null)
      : null,
    pill: pillOf(plan.rateKind),
    status,
    progressPct: clampPct(goal.currentBalance, target),
    relative: false,
    simple: plan,
  };
}
