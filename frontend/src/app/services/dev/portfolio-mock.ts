/**
 * DEV-ONLY portfolio mock fixtures.
 *
 * The local backend can't produce real portfolio history (the chart is a live
 * read-through from Alpaca's sandbox, and the funding/trade jobs run near midnight ET
 * when a dev laptop is off). This module lets you force a fully-coherent fake portfolio
 * of a chosen size so you can eyeball how the UI renders across account magnitudes.
 *
 * Usage: uncomment ONE entry in MOCK_CHOICES below to activate that tier. Leave them all
 * commented for real (live-Alpaca) data. PortfolioService only consults this when
 * environment.local is true (the local dev loop) — the deployed dev/test/prod builds all
 * have local:false, so it can never leak there. Karma additionally forces it null so
 * HTTP-asserting service specs are immune to the toggle state.
 *
 * Values are deterministically "jittered" (seeded, ±a few %) so they look like real
 * account balances (e.g. $2,412,361.47) rather than suspiciously round numbers — but
 * are identical on every reload. Edit the round design numbers in SCENARIOS freely.
 */
import {
  AccountSummary,
  PerformanceData,
  PortfolioDashboardData,
  PortfolioHistory,
  Position,
  Transaction,
} from '../portfolio.service';

export type MockTier = 'ZERO' | 'SMALL' | 'MEDIUM' | 'LARGE' | 'XLARGE';

/**
 * DEV TOGGLE — uncomment ONE line to force that tier; all commented = real data.
 * The first uncommented entry wins.
 */
const MOCK_CHOICES: MockTier[] = [
  // 'ZERO',    // $0 — brand-new, fully empty account
  // 'SMALL',   // ~$5.2k — just getting started
  // 'MEDIUM',  // ~$47.8k — established
  // 'LARGE',   // ~$342k — substantial
  'XLARGE',     // ~$2.4M — large; stress-tests number formatting/layout
];

/**
 * Forced null under karma regardless of the toggle: existing service specs (e.g.
 * portfolio-store.service.spec.ts) use the REAL PortfolioService and assert on HTTP
 * requests — a live toggle would starve them and fail the suite mid-development.
 */
const IN_KARMA = typeof window !== 'undefined' && !!(window as any).__karma__;

export const ACTIVE_MOCK_TIER: MockTier | null = IN_KARMA ? null : (MOCK_CHOICES.find(Boolean) ?? null);

// --- Scenario definitions (round design numbers; jittered to organic at runtime) ----

interface BasePosition {
  symbol: string;
  name: string;
  price: number;        // representative current price
  marketValue: number;  // current market value of the holding
  costBasis: number;    // total cost basis of the holding
}

interface Scenario {
  /** History length in days. */
  spanDays: number;
  /** Recent transaction count. */
  txns: number;
  /** Positions sum (marketValue) to the tier's nominal value; jitter perturbs it. */
  positions: BasePosition[];
}

const SCENARIOS: Record<MockTier, Scenario> = {
  ZERO: { spanDays: 0, txns: 0, positions: [] },
  SMALL: {
    spanDays: 28,
    txns: 3,
    positions: [
      { symbol: 'VOO', name: 'Vanguard S&P 500 ETF', price: 500, marketValue: 3200, costBasis: 3000 },
      { symbol: 'VTI', name: 'Vanguard Total Stock Market ETF', price: 280, marketValue: 2000, costBasis: 1900 },
    ],
  },
  MEDIUM: {
    spanDays: 300,
    txns: 6,
    positions: [
      { symbol: 'VOO', name: 'Vanguard S&P 500 ETF', price: 500, marketValue: 18000, costBasis: 15000 },
      { symbol: 'QQQ', name: 'Invesco QQQ Trust', price: 480, marketValue: 12000, costBasis: 11000 },
      { symbol: 'AAPL', name: 'Apple Inc.', price: 230, marketValue: 10000, costBasis: 9000 },
      { symbol: 'MSFT', name: 'Microsoft Corp.', price: 430, marketValue: 7800, costBasis: 7000 },
    ],
  },
  LARGE: {
    spanDays: 1095,
    txns: 10,
    positions: [
      { symbol: 'VOO', name: 'Vanguard S&P 500 ETF', price: 500, marketValue: 90000, costBasis: 70000 },
      { symbol: 'VTI', name: 'Vanguard Total Stock Market ETF', price: 280, marketValue: 70000, costBasis: 55000 },
      { symbol: 'QQQ', name: 'Invesco QQQ Trust', price: 480, marketValue: 60000, costBasis: 48000 },
      { symbol: 'AAPL', name: 'Apple Inc.', price: 230, marketValue: 55000, costBasis: 44000 },
      { symbol: 'MSFT', name: 'Microsoft Corp.', price: 430, marketValue: 45000, costBasis: 35000 },
      { symbol: 'NVDA', name: 'NVIDIA Corp.', price: 130, marketValue: 22000, costBasis: 18000 },
    ],
  },
  XLARGE: {
    spanDays: 2190,
    txns: 12,
    positions: [
      { symbol: 'VOO', name: 'Vanguard S&P 500 ETF', price: 500, marketValue: 600000, costBasis: 430000 },
      { symbol: 'VTI', name: 'Vanguard Total Stock Market ETF', price: 280, marketValue: 400000, costBasis: 290000 },
      { symbol: 'QQQ', name: 'Invesco QQQ Trust', price: 480, marketValue: 350000, costBasis: 250000 },
      { symbol: 'AAPL', name: 'Apple Inc.', price: 230, marketValue: 300000, costBasis: 210000 },
      { symbol: 'MSFT', name: 'Microsoft Corp.', price: 430, marketValue: 250000, costBasis: 180000 },
      { symbol: 'NVDA', name: 'NVIDIA Corp.', price: 130, marketValue: 200000, costBasis: 120000 },
      { symbol: 'AMZN', name: 'Amazon.com Inc.', price: 185, marketValue: 130000, costBasis: 95000 },
      { symbol: 'GOOGL', name: 'Alphabet Inc.', price: 175, marketValue: 100000, costBasis: 75000 },
      { symbol: 'TSLA', name: 'Tesla Inc.', price: 250, marketValue: 70000, costBasis: 50000 },
    ],
  },
};

// --- Deterministic helpers ----------------------------------------------------------

const DAY_MS = 24 * 60 * 60 * 1000;
const round2 = (n: number): number => Math.round(n * 100) / 100;

/** Deterministic 0..1 hash of a string (FNV-1a) — organic but reproducible. */
function hash01(key: string): number {
  let h = 2166136261;
  for (let i = 0; i < key.length; i++) {
    h ^= key.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return ((h >>> 0) % 1_000_000) / 1_000_000;
}

/** ±pct multiplier around 1.0, seeded by key. */
function jitter(key: string, pct: number): number {
  return 1 + (hash01(key) - 0.5) * 2 * pct;
}

/** Deterministic 0..1 "noise" seeded by index — for history/transaction variation. */
function noise(i: number): number {
  const x = Math.sin((i + 1) * 12.9898) * 43758.5453;
  return x - Math.floor(x);
}

interface ResolvedPosition {
  symbol: string;
  name: string;
  price: number;
  marketValue: number;
  costBasis: number;
}

/**
 * Turn a tier's round design positions into organic (jittered) concrete positions.
 * marketValue and costBasis share one factor per position so the designed gain% is
 * preserved (never flips a holding underwater); price gets its own small factor.
 */
function resolvePositions(tier: MockTier): ResolvedPosition[] {
  return SCENARIOS[tier].positions.map((p) => {
    const f = jitter(`${tier}:${p.symbol}:v`, 0.05); // ±5% on value
    return {
      symbol: p.symbol,
      name: p.name,
      price: round2(p.price * jitter(`${tier}:${p.symbol}:p`, 0.03)), // ±3% on price
      marketValue: round2(p.marketValue * f),
      costBasis: round2(p.costBasis * f),
    };
  });
}

/** Organic portfolio value = Σ jittered market values (0 for an empty account). */
function resolveValue(resolved: ResolvedPosition[]): number {
  return round2(resolved.reduce((a, p) => a + p.marketValue, 0));
}

function pointCount(windowDays: number, period: string): number {
  const byPeriod: Record<string, number> = { '1D': 24, '1W': 7, '1M': 30, '3M': 90, '6M': 26, '1Y': 52, 'ALL': 60 };
  const base = byPeriod[period] ?? 60;
  return Math.max(2, Math.min(base, Math.max(2, windowDays)));
}

/** How many days back the requested period covers (ALL / unknown = full history). */
function periodDays(spanDays: number, period: string): number {
  const map: Record<string, number> = { '1D': 1, '1W': 7, '1M': 30, '3M': 90, '6M': 180, '1Y': 365 };
  return Math.min(map[period] ?? spanDays, spanDays);
}

/** Canonical equity from inception (u=0 → $0) to now (u=1 → value), smoothstep growth. */
function equityAtProgress(u: number, value: number): number {
  const c = Math.max(0, Math.min(1, u));
  return value * (c * c * (3 - 2 * c));
}

/** Organic +0.4–1.0% daily gain per position — shared by buildPositions and the
 *  performance 'Today' row so the dashboard's perf-sync overwrite is a no-op. */
function todayGain(p: ResolvedPosition): number {
  return round2(p.marketValue * (0.004 + hash01(`${p.symbol}:td`) * 0.006));
}

function buildPositions(resolved: ResolvedPosition[], value: number): Position[] {
  return resolved.map((p) => {
    const quantity = round2(p.marketValue / p.price);
    const unrealizedPL = round2(p.marketValue - p.costBasis);
    return {
      symbol: p.symbol,
      name: p.name,
      quantity,
      quantityAvailable: quantity,
      marketValue: p.marketValue,
      costBasis: p.costBasis,
      unrealizedPL,
      unrealizedPLPercent: round2((unrealizedPL / p.costBasis) * 100),
      currentPrice: p.price,
      averageCostBasis: round2(p.costBasis / quantity),
      percentOfAccount: value > 0 ? round2((p.marketValue / value) * 100) : 0,
      todayGainLoss: todayGain(p),
      todayGainLossPercent: round2(0.4 + hash01(`${p.symbol}:tdp`) * 0.6),
    };
  });
}

function buildHistory(value: number, spanDays: number, period: string): PortfolioHistory {
  if (value <= 0) return { timestamps: [], values: [], profitLoss: [], profitLossPercent: [] };
  const windowDays = periodDays(spanDays, period);
  const n = pointCount(windowDays, period);
  const now = Date.now();
  const timestamps: string[] = [];
  const values: number[] = [];
  for (let i = 0; i < n; i++) {
    const frac = i / (n - 1);                       // 0 (window start) .. 1 (now)
    const daysAgo = Math.round((1 - frac) * windowDays);
    const u = (spanDays - daysAgo) / spanDays;      // progress from account inception (0) to now (1)
    const baseVal = equityAtProgress(u, value);
    // deterministic ±1.5% wiggle scaled to local equity; flat on both endpoints so
    // ALL starts exactly at $0 (inception) and every period ends exactly at the value.
    const wiggle = i === 0 || i === n - 1 ? 0 : (noise(daysAgo) - 0.5) * 0.03 * baseVal;
    timestamps.push(new Date(now - daysAgo * DAY_MS).toISOString());
    values.push(round2(Math.max(0, baseVal + wiggle)));
  }
  values[values.length - 1] = value; // end exactly at the tier value
  const base = values[0];
  return {
    timestamps,
    values,
    profitLoss: values.map((v) => round2(v - base)),
    profitLossPercent: base > 0 ? values.map((v) => round2(((v - base) / base) * 100)) : values.map(() => 0),
  };
}

function buildTransactions(resolved: ResolvedPosition[], spanDays: number, txns: number, tier: MockTier): Transaction[] {
  if (txns <= 0 || resolved.length === 0) return [];
  const now = Date.now();
  const out: Transaction[] = [];
  for (let i = 0; i < txns; i++) {
    const p = resolved[i % resolved.length];
    const daysAgo = Math.round((i + 1) * (spanDays / (txns + 1)));
    const amount = round2(50 + noise(i) * 200); // $50–$250 recurring buys
    out.push({
      id: `mock-${tier}-${i}`,
      type: 'buy',
      symbol: p.symbol,
      quantity: round2(amount / p.price),
      price: p.price,
      amount,
      date: new Date(now - daysAgo * DAY_MS).toISOString(),
      status: 'filled',
    });
  }
  return out;
}

// --- Public generators --------------------------------------------------------------

export function mockDashboard(tier: MockTier): PortfolioDashboardData {
  const s = SCENARIOS[tier];
  const resolved = resolvePositions(tier);
  const portfolioValue = resolveValue(resolved);
  const totalInvested = round2(resolved.reduce((a, p) => a + p.costBasis, 0));
  const totalGainLoss = round2(portfolioValue - totalInvested);
  const summary: AccountSummary = {
    portfolioValue,
    todayChange: round2(portfolioValue * 0.0061),
    todayChangePercent: portfolioValue > 0 ? 0.61 : 0,
    buyingPower: 0,
    cash: 0,
    withdrawableCash: 0,
    equity: portfolioValue, // freedom stat-strip reads summary.equity
  };
  return {
    summary,
    positions: buildPositions(resolved, portfolioValue),
    history: buildHistory(portfolioValue, s.spanDays, 'ALL'),
    recentTransactions: buildTransactions(resolved, s.spanDays, s.txns, tier),
    totalInvested,
    totalGainLoss,
    totalGainLossPercent: totalInvested > 0 ? round2((totalGainLoss / totalInvested) * 100) : 0,
  };
}

export function mockHistory(tier: MockTier, period: string = 'ALL'): PortfolioHistory {
  const resolved = resolvePositions(tier);
  return buildHistory(resolveValue(resolved), SCENARIOS[tier].spanDays, period);
}

/** Whole days since Jan 1 of the current year — the YTD window length. */
function daysSinceJanFirst(): number {
  const now = new Date();
  return Math.floor((now.getTime() - new Date(now.getFullYear(), 0, 1).getTime()) / DAY_MS);
}

/**
 * Mirrors the real /performance response (PerformancePeriodCalculator + controller):
 * 'Today', then 1W/1M/3M/6M/YTD/1Y middle rows with windows older than the account
 * omitted, then 'Total'. Middle-row start values ride the same canonical equity curve
 * the chart windows sample, so the analytics card and the chart always agree.
 */
export function mockPerformance(tier: MockTier): PerformanceData[] {
  const s = SCENARIOS[tier];
  const resolved = resolvePositions(tier);
  if (resolved.length === 0) return [];
  const value = resolveValue(resolved);
  const totalInvested = round2(resolved.reduce((a, p) => a + p.costBasis, 0));
  const totalReturn = round2(value - totalInvested);

  const todayReturn = round2(resolved.reduce((a, p) => a + todayGain(p), 0));
  const todayStart = round2(value - todayReturn);
  const rows: PerformanceData[] = [
    {
      period: 'Today',
      startValue: todayStart,
      endValue: value,
      totalReturn: todayReturn,
      totalReturnPercent: todayStart > 0 ? round2((todayReturn / todayStart) * 100) : 0,
    },
  ];

  const middleWindows: Array<[string, number]> = [
    ['1W', 7], ['1M', 30], ['3M', 90], ['6M', 180], ['YTD', daysSinceJanFirst()], ['1Y', 365],
  ];
  for (const [period, windowDays] of middleWindows) {
    if (windowDays > s.spanDays) continue; // window predates account inception — omit
    const startValue = round2(equityAtProgress((s.spanDays - windowDays) / s.spanDays, value));
    const windowReturn = round2(value - startValue);
    rows.push({
      period,
      startValue,
      endValue: value,
      totalReturn: windowReturn,
      totalReturnPercent: startValue > 0 ? round2((windowReturn / startValue) * 100) : 0,
    });
  }

  rows.push({
    period: 'Total',
    startValue: totalInvested,
    endValue: value,
    totalReturn,
    totalReturnPercent: totalInvested > 0 ? round2((totalReturn / totalInvested) * 100) : 0,
    bestDay: round2(value * 0.021),
    worstDay: round2(-value * 0.014),
  });
  return rows;
}
