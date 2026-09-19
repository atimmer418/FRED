import { MockTier, mockDashboard, mockHistory, mockPerformance } from './portfolio-mock';

const FUNDED_TIERS: MockTier[] = ['SMALL', 'MEDIUM', 'LARGE', 'XLARGE'];
const ALL_TIERS: MockTier[] = ['ZERO', ...FUNDED_TIERS];

// Floating-point-safe comparison for money math.
const near = (a: number, b: number, eps = 0.01): boolean => Math.abs(a - b) <= eps;

describe('portfolio-mock generators', () => {
  describe('coherence invariants (funded tiers)', () => {
    FUNDED_TIERS.forEach((tier) => {
      describe(tier, () => {
        const d = mockDashboard(tier);

        it('portfolioValue equals sum of position market values + cash', () => {
          const sumMv = d.positions.reduce((a, p) => a + p.marketValue, 0);
          expect(near(d.summary.portfolioValue, sumMv + d.summary.cash)).toBe(true);
        });

        it('equity equals portfolioValue (freedom strip reads equity)', () => {
          expect(d.summary.equity).toBe(d.summary.portfolioValue);
        });

        it('totalInvested equals sum of position cost bases', () => {
          const sumCb = d.positions.reduce((a, p) => a + p.costBasis, 0);
          expect(near(d.totalInvested, sumCb)).toBe(true);
        });

        it('totalGainLoss equals value minus invested', () => {
          expect(near(d.totalGainLoss, d.summary.portfolioValue - d.totalInvested)).toBe(true);
        });

        it('shows a gain (curated tiers are all up)', () => {
          expect(d.totalGainLoss).toBeGreaterThan(0);
          expect(d.totalGainLossPercent).toBeGreaterThan(0);
        });

        it('position percentOfAccount sums to ~100%', () => {
          const sumPct = d.positions.reduce((a, p) => a + p.percentOfAccount, 0);
          expect(near(sumPct, 100, 0.5)).toBe(true);
        });

        it('has recent transactions', () => {
          expect(d.recentTransactions.length).toBeGreaterThan(0);
        });

        it('performance Total row reconciles with the dashboard', () => {
          const total = mockPerformance(tier).find((r) => r.period === 'Total')!;
          expect(total).toBeDefined();
          expect(total.endValue).toBe(d.summary.portfolioValue);
          expect(near(total.startValue, d.totalInvested)).toBe(true);
        });

        it('values are organic, not suspiciously round', () => {
          expect(d.summary.portfolioValue % 1000).not.toBe(0);
          d.positions.forEach((p) => expect(p.marketValue % 1000).not.toBe(0));
        });
      });
    });
  });

  describe('history windowing', () => {
    FUNDED_TIERS.forEach((tier) => {
      it(`${tier}: ALL starts at $0 (inception) and ends exactly at the portfolio value`, () => {
        const all = mockHistory(tier, 'ALL').values;
        expect(all.length).toBeGreaterThan(1);
        expect(all[0]).toBe(0);
        expect(all[all.length - 1]).toBe(mockDashboard(tier).summary.portfolioValue);
      });

      it(`${tier}: a short window (1W) does NOT start at $0`, () => {
        const week = mockHistory(tier, '1W').values;
        expect(week[0]).toBeGreaterThan(0);
        expect(week.length).toBeLessThan(mockHistory(tier, 'ALL').values.length);
      });

      it(`${tier}: timestamps and values are the same length`, () => {
        const h = mockHistory(tier, 'ALL');
        expect(h.timestamps.length).toBe(h.values.length);
      });
    });

    it('6M windows the recent 180 days for long-history tiers, not the full ALL span', () => {
      (['LARGE', 'XLARGE'] as MockTier[]).forEach((tier) => {
        const all = mockHistory(tier, 'ALL').values;
        const sixM = mockHistory(tier, '6M').values;
        expect(sixM[0]).toBeGreaterThan(0); // NOT from inception ($0) — that's ALL's shape
        expect(sixM[sixM.length - 1]).toBe(all[all.length - 1]); // still ends at the value
        const sixMTs = mockHistory(tier, '6M').timestamps;
        const oldest = Date.now() - new Date(sixMTs[0]).getTime();
        expect(oldest).toBeLessThanOrEqual(181 * 24 * 60 * 60 * 1000); // window ≤ ~180 days
      });
    });
  });

  describe('performance period rows (analytics contract)', () => {
    // Mirrors the real /performance response (PerformancePeriodCalculator):
    // Today, then 1W/1M/3M/6M/YTD/1Y with too-old windows omitted, then Total.
    const DAY = 24 * 60 * 60 * 1000;
    const daysSinceJan1 = Math.floor(
      (Date.now() - new Date(new Date().getFullYear(), 0, 1).getTime()) / DAY,
    );
    const periods = (tier: MockTier): string[] => mockPerformance(tier).map((r) => r.period);
    const row = (tier: MockTier, period: string) =>
      mockPerformance(tier).find((r) => r.period === period);

    it('long-history tiers emit the full ordered contract', () => {
      (['LARGE', 'XLARGE'] as MockTier[]).forEach((tier) => {
        expect(periods(tier)).toEqual(['Today', '1W', '1M', '3M', '6M', 'YTD', '1Y', 'Total']);
      });
    });

    it('SMALL (28d) omits every window older than the account', () => {
      const p = periods('SMALL');
      expect(p[0]).toBe('Today');
      expect(p).toContain('1W');
      expect(p[p.length - 1]).toBe('Total');
      ['1M', '3M', '6M', '1Y'].forEach((x) => expect(p).not.toContain(x));
      // YTD appears only while Jan 1 falls inside the 28-day span
      expect(p.includes('YTD')).toBe(daysSinceJan1 <= 28);
    });

    it('MEDIUM (300d) has 1W through 6M but never 1Y', () => {
      const p = periods('MEDIUM');
      ['1W', '1M', '3M', '6M'].forEach((x) => expect(p).toContain(x));
      expect(p).not.toContain('1Y');
    });

    it('middle rows are chart-consistent: start/end match the same period history window', () => {
      (['1W', '1M', '3M', '6M', '1Y'] as string[]).forEach((period) => {
        const r = row('XLARGE', period)!;
        const h = mockHistory('XLARGE', period).values;
        expect(r.startValue).toBe(h[0]);
        expect(r.endValue).toBe(h[h.length - 1]);
      });
    });

    it('middle row return math is internally consistent', () => {
      (['1W', '1M', '3M', '6M', '1Y'] as string[]).forEach((period) => {
        const r = row('XLARGE', period)!;
        expect(near(r.totalReturn, r.endValue - r.startValue)).toBe(true);
        expect(near(r.totalReturnPercent, (r.totalReturn / r.startValue) * 100, 0.01)).toBe(true);
        expect(r.totalReturn).toBeGreaterThan(0); // smoothstep growth curve only rises
      });
    });

    it('Today row equals the positions todayGainLoss sum (dashboard perf-sync is a no-op)', () => {
      const d = mockDashboard('XLARGE');
      const today = row('XLARGE', 'Today')!;
      const positionsToday = d.positions.reduce((a, p) => a + p.todayGainLoss, 0);
      expect(near(today.totalReturn, positionsToday)).toBe(true);
      expect(today.endValue).toBe(d.summary.portfolioValue);
      expect(near(today.startValue, today.endValue - today.totalReturn)).toBe(true);
    });
  });

  describe('ZERO is a genuinely empty account', () => {
    const d = mockDashboard('ZERO');

    it('has zero value, equity, invested, and gain', () => {
      expect(d.summary.portfolioValue).toBe(0);
      expect(d.summary.equity).toBe(0);
      expect(d.totalInvested).toBe(0);
      expect(d.totalGainLoss).toBe(0);
      expect(d.totalGainLossPercent).toBe(0);
    });

    it('has no positions and no transactions', () => {
      expect(d.positions.length).toBe(0);
      expect(d.recentTransactions.length).toBe(0);
    });

    it('has an empty history and empty performance', () => {
      expect(d.history.values.length).toBe(0);
      expect(d.history.timestamps.length).toBe(0);
      expect(mockHistory('ZERO').values.length).toBe(0);
      expect(mockPerformance('ZERO').length).toBe(0);
    });
  });

  describe('determinism', () => {
    it('produces identical values across calls (seeded, no randomness)', () => {
      ALL_TIERS.forEach((tier) => {
        expect(mockHistory(tier).values).toEqual(mockHistory(tier).values);
        expect(mockDashboard(tier).summary.portfolioValue).toBe(mockDashboard(tier).summary.portfolioValue);
      });
    });
  });
});
