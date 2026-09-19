/**
 * DEV-ONLY Monthly Freedom Update mock.
 *
 * Forces the MFU popup on a local build so it can be eyeballed without waiting for a month
 * rollover, and makes it REFLECT the active portfolio-mock tier: every equity-driven field (period
 * start/end equity, pig level, change, return, contributions, milestones, projection deltas) is
 * derived from the same mockDashboard()/mockHistory() fixtures the chart paints, using the rules in
 * MonthlyFreedomUpdateService (backend) — keep the two in lockstep. With no portfolio tier active
 * the real /generate payload is used instead (MILESTONE still overlays its card).
 *
 * Usage: set FORCE_MFU = true. MonthlyFreedomUpdateService only consults this when
 * environment.local is true (the local dev loop) — the deployed dev/test/prod builds all have
 * local:false, so it can never leak there. Karma additionally forces it null so HTTP-asserting
 * service specs are immune to the toggle state. While on: the popup fires once per app load (reload
 * to see it again), the "seen" write (/dismiss) is skipped so the real account is untouched, and no
 * reopen snapshot is persisted, so turning it off leaves localStorage exactly as it was.
 */
import { MilestoneDTO, MonthlyFreedomUpdateData } from '../monthly-freedom-update.service';
import { monthsToTarget } from '../../utils/freedom-projection.utils';
import { MockTier, mockDashboard, mockHistory } from './portfolio-mock';

export type MfuMilestoneMode = 'DERIVED' | 'EQUITY' | 'COMBO' | 'NONE';

export interface MfuMockConfig {
  milestone: MfuMilestoneMode;
  quarterly: boolean;
  /** FRED-217: force the freedom year to the current year so the Retire "sky card" renders. */
  retire?: boolean;
}

/** Live projection inputs the service reads from FreedomStatsService / RateAssumptionsService. */
export interface MfuMockInputs {
  /** Contribution driving the freedom date: the saved monthly goal, else the schedule's monthly equivalent. */
  monthlyContribution: number;
  /** Monthly equivalent of the actual schedule (the "current contribution" the CTA offers to raise). */
  scheduleMonthly: number;
  targetPortfolio: number;
  annualReturn: number;
  age: number;
}

// --- DEV TOGGLES -------------------------------------------------------------------------------

/** true = force the Monthly Freedom Update popup on every launch of a local build. */
const FORCE_MFU: boolean = true;

/**
 * Milestone card. 'DERIVED' applies prod's rule to the mocked history (only SMALL, a 28-day account
 * that starts at $0, crosses a threshold last month; the bigger tiers' curves are flat at the end
 * and render the DEFAULT card). The others force a variant WITHOUT touching any equity number:
 *   'EQUITY' — the highest equity milestone the tier's end equity qualifies for (the card shows
 *              the pig only for boundary tiers $1k/$10k/$100k/$1M; $50k/$250k/$500k use the icon)
 *   'COMBO'  — 1st and 10th investment plus that equity milestone (the long-title case)
 *   'NONE'   — the DEFAULT "no major milestones" card
 */
const MILESTONE: MfuMilestoneMode = 'DERIVED';

/** true = render the every-3rd-month quarterly review section (derived payload only). */
const QUARTERLY: boolean = false;

/**
 * FRED-217: true = force projectedFreedomYear to the current year so the Retire "sky card" replaces
 * the invest-more CTA. QUARTERLY still wins (its section hides both CTAs), so toggle this one alone.
 */
const RETIRE: boolean = true;

/** Forced null under karma so specs that assert on HTTP never see the toggle (see portfolio-mock.ts). */
const IN_KARMA = typeof window !== 'undefined' && !!(window as any).__karma__;

export const ACTIVE_MFU_MOCK: MfuMockConfig | null =
  IN_KARMA || !FORCE_MFU ? null : { milestone: MILESTONE, quarterly: QUARTERLY, retire: RETIRE };

// --- Backend constants mirrored from MonthlyFreedomUpdateService -----------------------------

const INVESTMENT_COUNT_MILESTONES = [1, 10, 100, 1000, 10000];
const EQUITY_VALUE_MILESTONES = [1000, 10000, 50000, 100000, 250000, 500000, 1000000];
/**
 * Best-next-move boost, mirroring MonthlyFreedomUpdateService.calculateBoostAmount: a percentage of
 * the member's own recurring amount. Cycle 0 (mfuCount % 3 == 0) = +20%, cycle 1 = +10%, rounded to
 * the nearest $10 and floored at the old flat amount ($50 cycle 0, $25 cycle 1).
 */
function boostAmount(currentPerPeriod: number, mfuCount: number): number {
  const cycle = ((mfuCount % 3) + 3) % 3;
  const pct = cycle === 0 ? 0.20 : 0.10;
  const floor = cycle === 0 ? 50 : 25;
  const roundedToTen = Math.round((currentPerPeriod * pct) / 10) * 10;
  return Math.max(roundedToTen, floor);
}
/** SCF 2022 median financial assets keyed by age-bracket upper bound (AGE_EQUITY_ESTIMATES). */
const AGE_EQUITY_ESTIMATES: Array<[number, number]> = [
  [25, 5400], [30, 11800], [35, 11800], [40, 45740], [45, 45740], [50, 80000], [55, 80000],
  [60, 134000], [65, 134000], [75, 164000], [100, 143000],
];
const DAY_MS = 24 * 60 * 60 * 1000;

// --- Helpers ---------------------------------------------------------------------------------

const pad = (n: number): string => String(n).padStart(2, '0');
const isoDate = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const round2 = (n: number): number => Math.round(n * 100) / 100;
/** LOCAL calendar day of a mock timestamp (they are UTC ISO strings; the chart labels them locally). */
const dayOf = (isoTimestamp: string): string => isoDate(new Date(isoTimestamp));

function currentMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
}

/** findEquityAtDate: exact date match, else the nearest earlier point, else 0 (timestamps ascend). */
function equityAt(history: { timestamps: string[]; values: number[] }, iso: string): number {
  let closest = 0;
  for (let i = 0; i < history.timestamps.length; i++) {
    const day = dayOf(history.timestamps[i]);
    if (day === iso) return history.values[i];
    if (day < iso) closest = history.values[i];
    else break;
  }
  return closest;
}

/** calculateEquityLevel: pig level 1–6 from equity. */
function equityLevel(equity: number): number {
  if (!(equity >= 1_000)) return 1;
  if (equity < 10_000) return 2;
  if (equity < 100_000) return 3;
  if (equity < 1_000_000) return 4;
  if (equity < 5_000_000) return 5;
  return 6;
}

/** calculateStatusPercentile: logistic curve on equity vs. the age bracket's median. */
function statusPercentile(age: number, equity: number): number {
  const bracket = AGE_EQUITY_ESTIMATES.find(([upper]) => upper >= age) ?? AGE_EQUITY_ESTIMATES[AGE_EQUITY_ESTIMATES.length - 1];
  const ratio = equity / bracket[1];
  const percentile = 100 / (1 + Math.exp(-1.5 * (ratio - 1)));
  return Math.max(1, Math.min(99, Math.round(percentile)));
}

/** calculateFreedomYear over the shared months-to-target math. */
function freedomYear(pv: number, pmt: number, fv: number, annualReturn: number): number {
  const months = monthsToTarget(pv, pmt, fv, annualReturn);
  const year = new Date().getFullYear();
  return Math.min(year + Math.ceil(months / 12), year + 100);
}

function formatOrdinal(n: number): string {
  const formatted = n >= 1000 ? n.toLocaleString('en-US') : String(n);
  if (n === 1) return formatted + 'st';
  if (n === 2) return formatted + 'nd';
  if (n === 3) return formatted + 'rd';
  return formatted + 'th';
}

function formatEquityLabel(value: number): string {
  if (value >= 1_000_000) return '$' + Math.floor(value / 1_000_000) + 'M';
  if (value >= 1_000) return '$' + Math.floor(value / 1_000) + 'k';
  return '$' + Math.floor(value);
}

function formatMilestoneKey(value: number): string {
  if (value >= 1_000_000) return Math.floor(value / 1_000_000) + 'M';
  if (value >= 1_000) return Math.floor(value / 1_000) + 'K';
  return String(Math.floor(value));
}

interface MilestoneParts {
  investmentKeys: string[];
  investmentLabels: string[];
  /** Equity threshold to celebrate, or null. */
  equity: number | null;
}

/** calculateMilestones: one combined card, or the DEFAULT entry when nothing was reached. */
function buildMilestone(parts: MilestoneParts): MilestoneDTO[] {
  const total = parts.investmentLabels.length + (parts.equity != null ? 1 : 0);
  if (total === 0) {
    return [{ type: 'DEFAULT', label: 'No major milestones reached', subtitle: "FRED's still so proud of you though" }];
  }
  let title = parts.investmentLabels.length ? parts.investmentLabels.join(' and ') + ' Investment!' : '';
  if (parts.equity != null) {
    title += (title ? ' ' : '') + formatEquityLabel(parts.equity) + ' Total Equity Reached!';
  }
  const subtitle = total === 1 ? 'You reached a major milestone.' : `You reached ${total} major milestones.`;
  const keys = [...parts.investmentKeys];
  if (parts.equity != null) keys.push('EQUITY_' + formatMilestoneKey(parts.equity));
  return [{ type: keys.join(','), label: title, subtitle }];
}

/** Investment-count milestones crossed: prevCount < milestone <= totalCount. */
function investmentMilestones(prevCount: number, totalCount: number): { keys: string[]; labels: string[] } {
  const keys: string[] = [];
  const labels: string[] = [];
  for (const milestone of INVESTMENT_COUNT_MILESTONES) {
    if (prevCount < milestone && totalCount >= milestone) {
      keys.push('INVESTMENT_' + milestone);
      labels.push(formatOrdinal(milestone));
    }
  }
  return { keys, labels };
}

/** Prod rule: the highest equity threshold inside [start, end], or null. */
function crossedEquityMilestone(start: number, end: number): number | null {
  for (let i = EQUITY_VALUE_MILESTONES.length - 1; i >= 0; i--) {
    const milestone = EQUITY_VALUE_MILESTONES[i];
    if (start <= milestone && end >= milestone) return milestone;
  }
  return null;
}

/** The highest equity threshold at or below `equity` (what a forced card can honestly claim), or null. */
function qualifyingEquityMilestone(equity: number): number | null {
  for (let i = EQUITY_VALUE_MILESTONES.length - 1; i >= 0; i--) {
    if (equity >= EQUITY_VALUE_MILESTONES[i]) return EQUITY_VALUE_MILESTONES[i];
  }
  return null;
}

function forcedMilestone(mode: Exclude<MfuMilestoneMode, 'DERIVED'>, endEquity: number): MilestoneDTO[] {
  switch (mode) {
    case 'EQUITY':
      return buildMilestone({ investmentKeys: [], investmentLabels: [], equity: qualifyingEquityMilestone(endEquity) });
    case 'COMBO': {
      const investment = investmentMilestones(0, 10);
      return buildMilestone({ investmentKeys: investment.keys, investmentLabels: investment.labels, equity: qualifyingEquityMilestone(endEquity) });
    }
    case 'NONE':
      return buildMilestone({ investmentKeys: [], investmentLabels: [], equity: null });
  }
}

/** Every field the real DTO carries, zeroed — the base for both the /check and /generate shapes. */
function blank(): MonthlyFreedomUpdateData {
  return {
    shouldShow: false,
    periodStart: '',
    periodEnd: '',
    periodLabel: '',
    statusPercentile: 0,
    currentStreak: 0,
    milestones: [],
    startEquityValue: 0,
    endEquityValue: 0,
    periodProgressDelta: 0,
    positive: false,
    periodContributions: 0,
    equityValueChange: 0,
    returnRate: 0,
    daysBoughtBack: 0,
    projectedFreedomYear: 0,
    currentEquityValue: 0,
    recurringInvestmentAmount: 0,
    investmentFrequency: 'MONTHLY',
    showQuarterlyCompare: false,
    equity12MonthsAgo: 0,
    netWorthChange: 0,
    freedomYearsChange: 0,
    yearlyContributions: 0,
    bestNextMoveYearsEarlier: 0,
    bestNextMoveYear: 0,
    frequencyLabel: 'month',
    currentInvestmentAmount: 0,
    bestNextMoveBoostAmount: 0,
    age: 0,
    reopen: false,
    mfuCount: 0,
    hasMfuHistory: false,
    equityLevel: 0,
    generatedForMonth: currentMonth(),
  };
}

// --- Public generators -----------------------------------------------------------------------

/**
 * Replace the milestone card according to `mode`, leaving every other field (and the input object)
 * untouched. Applied to the derived payload and to a real /generate payload alike.
 */
export function overlayMilestone(data: MonthlyFreedomUpdateData, mode: MfuMilestoneMode): MonthlyFreedomUpdateData {
  if (mode === 'DERIVED') return data;
  return { ...data, milestones: forcedMilestone(mode, data.endEquityValue) };
}

/**
 * The /check shape. Mirrors prod: a tier with no equity (ZERO) gets no popup; a funded tier, or the
 * live backend (null tier), shows.
 */
export function mockMfuCheck(tier: MockTier | null): MonthlyFreedomUpdateData {
  const equity = tier ? mockDashboard(tier).summary.equity : null;
  const shows = equity === null || equity > 0;
  if (!shows) {
    console.info('[mfu-mock] The active portfolio tier has no equity; prod would not show the update, so neither does the mock.');
  }
  return { ...blank(), shouldShow: shows, hasMfuHistory: shows };
}

/**
 * The /generate shape for the active tier: last calendar month, computed from the tier's mocked
 * history and positions with the backend's rules, projected with the app's live inputs.
 */
export function mockMfu(tier: MockTier, inputs: MfuMockInputs, cfg: MfuMockConfig, reopen: boolean): MonthlyFreedomUpdateData {
  const dashboard = mockDashboard(tier);
  // Period lookups read the daily 3M window (the finest window that always covers last month), i.e.
  // the same points the 1M/3M chart draws. The ALL window is only 60 points over the account's life.
  const period = mockHistory(tier, '3M');

  const now = new Date();
  const first = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  const last = new Date(now.getFullYear(), now.getMonth(), 0);
  const periodStart = isoDate(first);
  const periodEnd = isoDate(last);
  const label = (d: Date): string => d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });

  const currentEquity = dashboard.summary.equity;
  const startEquity = equityAt(period, periodStart);
  let endEquity = equityAt(period, periodEnd);
  if (endEquity === 0 && currentEquity > 0) endEquity = currentEquity;
  const delta = round2(endEquity - startEquity);

  const txns = dashboard.recentTransactions;
  const periodContributions = round2(
    txns.filter((t) => dayOf(t.date) >= periodStart && dayOf(t.date) <= periodEnd).reduce((a, t) => a + t.amount, 0)
  );
  const prevCount = txns.filter((t) => dayOf(t.date) < periodStart).length;

  const costBasis = dashboard.positions.reduce((a, p) => a + p.costBasis, 0);
  const unrealized = dashboard.positions.reduce((a, p) => a + p.unrealizedPL, 0);
  const returnRate = costBasis > 0 ? Math.round((unrealized / costBasis) * 10_000) / 100 : 0;

  const investment = investmentMilestones(prevCount, txns.length);
  const milestones = buildMilestone({
    investmentKeys: investment.keys,
    investmentLabels: investment.labels,
    equity: crossedEquityMilestone(startEquity, endEquity),
  });

  const { monthlyContribution, scheduleMonthly, targetPortfolio, annualReturn, age } = inputs;
  const mfuCountVal = cfg.quarterly ? 2 : 0;
  const boost = boostAmount(scheduleMonthly, mfuCountVal);
  const projectedYear = freedomYear(currentEquity, monthlyContribution, targetPortfolio, annualReturn);
  const boostedYear = freedomYear(currentEquity, monthlyContribution + boost, targetPortfolio, annualReturn);
  const monthsFromStart = monthsToTarget(startEquity, monthlyContribution, targetPortfolio, annualReturn);
  const monthsFromEnd = monthsToTarget(endEquity, monthlyContribution, targetPortfolio, annualReturn);

  const all = mockHistory(tier, 'ALL');
  const accountDays = all.timestamps.length ? Math.floor((Date.now() - Date.parse(all.timestamps[0])) / DAY_MS) : 0;

  const data: MonthlyFreedomUpdateData = {
    ...blank(),
    shouldShow: true,
    hasMfuHistory: true,
    reopen,
    periodStart,
    periodEnd,
    periodLabel: `${label(first)} to ${label(last)}`,
    statusPercentile: statusPercentile(age, currentEquity),
    // Prod's streak counts consecutive months with an investment; the mock transactions are too
    // sparse for that rule, so the streak stands in for the account's age in months.
    currentStreak: Math.floor(accountDays / 30),
    milestones,
    startEquityValue: startEquity,
    endEquityValue: endEquity,
    periodProgressDelta: delta,
    positive: delta > 0,
    periodContributions,
    equityValueChange: delta,
    returnRate,
    daysBoughtBack: Math.max(0, Math.round((monthsFromStart - monthsFromEnd) * 30.44)),
    projectedFreedomYear: projectedYear,
    currentEquityValue: currentEquity,
    recurringInvestmentAmount: scheduleMonthly,
    currentInvestmentAmount: scheduleMonthly,
    bestNextMoveBoostAmount: boost,
    bestNextMoveYear: boostedYear,
    bestNextMoveYearsEarlier: Math.max(0, projectedYear - boostedYear),
    age,
    mfuCount: mfuCountVal,
    equityLevel: equityLevel(endEquity),
  };

  // FRED-217: force the freedom-year-reached state so the Retire card is reachable via FORCE_MFU.
  if (cfg.retire) {
    data.projectedFreedomYear = new Date().getFullYear();
  }

  if (cfg.quarterly) {
    const year = mockHistory(tier, '1Y');
    const twelveMonthsAgo = new Date(now);
    twelveMonthsAgo.setMonth(twelveMonthsAgo.getMonth() - 12);
    const iso = isoDate(twelveMonthsAgo);
    const equity12 = equityAt(year, iso);
    const monthsFrom12Ago = monthsToTarget(equity12, monthlyContribution, targetPortfolio, annualReturn);
    const monthsFromNow = monthsToTarget(currentEquity, monthlyContribution, targetPortfolio, annualReturn);
    data.showQuarterlyCompare = true;
    data.equity12MonthsAgo = equity12;
    data.netWorthChange = round2(currentEquity - equity12);
    data.freedomYearsChange = Math.round(((monthsFrom12Ago - monthsFromNow) / 12) * 10) / 10;
    data.yearlyContributions = round2(txns.filter((t) => dayOf(t.date) >= iso).reduce((a, t) => a + t.amount, 0));
  }

  return overlayMilestone(data, cfg.milestone);
}
