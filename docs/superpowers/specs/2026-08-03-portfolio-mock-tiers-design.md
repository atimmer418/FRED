# Portfolio Mock Tiers (Dev-Only) — Design

- Date: 2026-08-03
- Status: Approved
- Author: andy (with Claude)

## Problem

The local dev environment can't produce real portfolio history. The chart is a
**live read-through from Alpaca's sandbox** (`PortfolioDashboardService`), not from
any local table. The jobs that would create real fills are cron'd at ~11:55 PM and
~11:59 PM ET (schedule pickup + the ACH batch transfer), when a dev laptop backend is
off — so no transfers → no orders → no positions → Alpaca returns an empty/flat series.
Result: dev accounts (e.g. `go@go.go`) show an empty chart even after weeks. Making local
trade execution actually produce a chart is a multi-day, externally-dependent, timing-
fragile exercise, and still can't reproduce historical depth.

## Goal

Force a fake, fully-coherent portfolio at a chosen size by commenting a single line
in/out. Deterministic (identical every reload). Covers the whole app. Never reaches
dev/prod.

## Approach — frontend `PortfolioService` seam (chosen)

Every portfolio read funnels through `PortfolioService.getPortfolioDashboard()`,
`getPortfolioHistory(period)`, and `getPerformance()` — both `PortfolioStoreService`
and every direct caller (my-profile, monte-carlo, lump-sum, sell-withdraw, chart period
switch). A one-line guard in each returns a fixture when a dev tier is active, else the
real `http.get`. This makes tab1, the freedom stat-strip (reads `summary.equity`), the
drawdown deck, and all other consumers reflect the tier coherently.

Rejected:
- **Store-only seam** (`PortfolioStoreService.fetchBundle$`) — misses direct callers →
  the app looks inconsistent. Fails the "coherent everywhere" bar.
- **Backend `@Profile("local")` override** — closest to the real data path, but changing
  tiers means editing Java and restarting the backend, not a comment-toggle loop.

## Toggle

`services/dev/portfolio-mock.ts` exposes `ACTIVE_MOCK_TIER`: uncomment exactly one entry
in `MOCK_CHOICES` to pick a tier; all commented = real data. Committed default is all
commented (`null`). (An array + `.find()` is used rather than a `?? null` literal ladder,
which the Angular compiler rejects with TS2869 once a literal is uncommented — a string
literal is never nullish, so `'LARGE' ?? null` is a compile error.)

```ts
const MOCK_CHOICES: MockTier[] = [
  // 'ZERO',
  // 'SMALL',
  // 'MEDIUM',
  // 'LARGE',
  // 'XLARGE',
];
export const ACTIVE_MOCK_TIER: MockTier | null = MOCK_CHOICES.find(Boolean) ?? null;
```

## Gating

`PortfolioService` consults the toggle only when `!environment.production`. Double-gated:
env guard + `null` committed default. The mock can never run in dev/prod (which run the
real Alpaca read-through).

## Tiers (deterministic, coherent — "C: tuned edges")

| Tier   | Value    | Positions | Invested | Gain            | History | Txns |
|--------|----------|-----------|----------|-----------------|---------|------|
| ZERO   | $0       | none      | $0       | $0              | empty   | none |
| SMALL  | $5,200   | 2         | $4,900   | +$300 (+6.1%)   | ~4 wks  | 3    |
| MEDIUM | $47,800  | 4         | $42,000  | +$5,800 (+13.8%)| ~10 mo  | 6    |
| LARGE  | $342,000 | 6         | $270,000 | +$72,000 (+26.7%)| ~3 yr  | 10   |
| XLARGE | $2.4M    | 9         | $1.7M    | +$700k (+41.2%) | ~6 yr   | 12   |

ZERO is a genuinely empty account (no positions, no transactions, empty chart) — the true
new-account state. Positions use real ETF/stock tickers (VOO, VTI, QQQ, AAPL, MSFT, NVDA,
AMZN, GOOGL, TSLA).

Coherence invariants enforced by the generators:
- `portfolioValue = Σ position.marketValue + cash` (cash = 0), derived from the positions
- `equity = portfolioValue` (what the freedom strip reads)
- `totalInvested = Σ position.costBasis`
- `totalGainLoss = portfolioValue − totalInvested`
- `history.values` ends exactly at `portfolioValue`
- per-position `quantity`, `unrealizedPL(%)`, `percentOfAccount` derived consistently

Organic values: the round design numbers in `SCENARIOS` are deterministically jittered
(seeded FNV-1a hash, ±5% on value, ±3% on price) so displayed balances look real
(e.g. `$2,445,591.88`, `VOO @ $504.74`) instead of suspiciously round — but are identical
on every reload. marketValue and costBasis share one factor per position so the designed
gain% is preserved (a holding never flips underwater).

History windowing: one canonical inception→now curve (deterministic smoothstep + seeded
wiggle, no `Math.random`). The requested period *windows* it — `ALL` spans the whole
history so it starts at `$0` (inception) and ends at the value; `1W`/`1M`/etc. show only
the recent slice and correctly do NOT start at `$0`.

## Files

- **NEW** `frontend/src/app/services/dev/portfolio-mock.ts` — toggle + pure generators
  (`mockDashboard`, `mockHistory`, `mockPerformance`).
- **EDIT** `frontend/src/app/services/portfolio.service.ts` — add `of` import + 3 one-line
  guards.
- **NEW** `frontend/src/app/services/dev/portfolio-mock.spec.ts` — per-tier coherence
  tests.

## Testing

Unit spec on the pure generators: per-tier coherence invariants, ZERO fully empty. The
committed toggle (`null`) keeps CI/build on the real HTTP path. Plus the AOT build gate.

## Non-goals

- Not fixing local trade execution.
- Not mocking buy/sell/withdraw mutations (only the read endpoints).
- Not per-period intraday realism — history is sized to the requested period but is a
  single deterministic curve, not true 1D/1W granularity.
