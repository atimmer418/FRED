/**
 * mfu-mock.spec.ts — DEV-only Monthly Freedom Update mock.
 *
 * Contract: the mock REFLECTS the active portfolio-mock tier. Every equity-driven field is derived
 * from the same mockDashboard()/mockHistory() fixtures the chart paints, using the backend rules in
 * MonthlyFreedomUpdateService (period = last calendar month, equity at the period bounds, pig level,
 * milestone thresholds, position-weighted return, shared projection math). Expected values are
 * recomputed here from the portfolio fixtures independently of mfu-mock.ts, and the clock is pinned
 * (2026-09-01) so the calendar-relative fixtures are deterministic.
 */
import { MockTier, mockDashboard, mockHistory } from './portfolio-mock';
import {
  ACTIVE_MFU_MOCK,
  MfuMockConfig,
  MfuMockInputs,
  mockMfu,
  mockMfuCheck,
  overlayMilestone,
} from './mfu-mock';
import { EquityPigUtils } from '../../utils/equity-pig.utils';
import { monthsToTarget } from '../../utils/freedom-projection.utils';
import { MonthlyFreedomUpdateData } from '../monthly-freedom-update.service';

const FUNDED_TIERS: MockTier[] = ['SMALL', 'MEDIUM', 'LARGE', 'XLARGE'];
const DAY_MS = 86_400_000;
const near = (a: number, b: number, eps = 0.01): boolean => Math.abs(a - b) <= eps;

const INPUTS: MfuMockInputs = {
  monthlyContribution: 500,
  scheduleMonthly: 400,
  targetPortfolio: 1_500_000,
  annualReturn: 0.10,
  age: 35,
};
const DERIVED: MfuMockConfig = { milestone: 'DERIVED', quarterly: false };

// Pinned "today": Sep 1 2026 noon local → last month is Aug 1 – Aug 31 2026.
const TODAY = new Date(2026, 8, 1, 12, 0, 0);

// --- Independent helpers (mirror the backend rules, not the module under test) --------------

const pad = (n: number): string => String(n).padStart(2, '0');
const isoLocal = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
/** The LOCAL calendar day of a mock timestamp (mock timestamps are UTC ISO strings; the chart labels them locally). */
const localDay = (isoTimestamp: string): string => isoLocal(new Date(isoTimestamp));

/** Backend findEquityAtDate: exact date match, else the nearest earlier point, else 0. */
function equityAt(h: { timestamps: string[]; values: number[] }, iso: string): number {
  let closest = 0;
  for (let i = 0; i < h.timestamps.length; i++) {
    const d = localDay(h.timestamps[i]);
    if (d === iso) return h.values[i];
    if (d < iso) closest = h.values[i];
    else break;
  }
  return closest;
}

function freedomYear(pv: number, pmt: number): number {
  const months = monthsToTarget(pv, pmt, INPUTS.targetPortfolio, INPUTS.annualReturn);
  const y = new Date().getFullYear();
  return Math.min(y + Math.ceil(months / 12), y + 100);
}

describe('mfu-mock', () => {
  beforeEach(() => {
    jasmine.clock().install();
    jasmine.clock().mockDate(TODAY);
  });
  afterEach(() => jasmine.clock().uninstall());

  describe('dev toggle', () => {
    it('is forced off under karma so HTTP-asserting specs never see the mock', () => {
      expect(ACTIVE_MFU_MOCK).toBeNull();
    });
  });

  describe('mockMfuCheck', () => {
    it('ZERO tier: no equity means no popup, mirroring prod /check', () => {
      const chk = mockMfuCheck('ZERO');
      expect(chk.shouldShow).toBeFalse();
      expect(chk.hasMfuHistory).toBeFalse();
    });

    it('funded tier: shows, has history, and stamps the current month', () => {
      const chk = mockMfuCheck('XLARGE');
      expect(chk.shouldShow).toBeTrue();
      expect(chk.hasMfuHistory).toBeTrue();
      expect(chk.generatedForMonth).toBe('2026-09');
    });

    it('no tier (live backend payload): shows', () => {
      expect(mockMfuCheck(null).shouldShow).toBeTrue();
    });
  });

  describe('derived payload reflects the mocked portfolio', () => {
    FUNDED_TIERS.forEach((tier) => {
      describe(tier, () => {
        let d: ReturnType<typeof mockDashboard>;
        let period: ReturnType<typeof mockHistory>;
        let m: MonthlyFreedomUpdateData;

        beforeEach(() => {
          d = mockDashboard(tier);
          // The period lookups read the daily 3M window (the finest window that always covers last
          // month), i.e. the same points the 1M/3M chart draws.
          period = mockHistory(tier, '3M');
          m = mockMfu(tier, INPUTS, DERIVED, false);
        });

        it('covers last calendar month', () => {
          expect(m.periodStart).toBe('2026-08-01');
          expect(m.periodEnd).toBe('2026-08-31');
          expect(m.periodLabel).toBe('Aug 1 to Aug 31');
          expect(m.generatedForMonth).toBe('2026-09');
        });

        it('current equity is the tier equity the rest of the app shows', () => {
          expect(m.currentEquityValue).toBe(d.summary.equity);
        });

        it('start equity is the mocked history value at the period start', () => {
          expect(near(m.startEquityValue, equityAt(period, '2026-08-01'))).toBe(true);
        });

        it('end equity is the history value at the period end, else current equity', () => {
          const atEnd = equityAt(period, '2026-08-31');
          expect(near(m.endEquityValue, atEnd > 0 ? atEnd : d.summary.equity)).toBe(true);
        });

        it('change is end minus start', () => {
          const delta = m.endEquityValue - m.startEquityValue;
          expect(near(m.periodProgressDelta, delta)).toBe(true);
          expect(near(m.equityValueChange, delta)).toBe(true);
          expect(m.positive).toBe(delta > 0);
        });

        it('pig level matches the end equity (the same pig the profile shows for that equity)', () => {
          expect(EquityPigUtils.pigSrcFromLevel(m.equityLevel))
            .toBe(EquityPigUtils.pigSrcFromEquity(m.endEquityValue));
        });

        it('return rate is unrealized P/L over cost basis across the tier positions', () => {
          const costBasis = d.positions.reduce((a, p) => a + p.costBasis, 0);
          const unrealized = d.positions.reduce((a, p) => a + p.unrealizedPL, 0);
          expect(near(m.returnRate, Math.round((unrealized / costBasis) * 10_000) / 100, 0.011)).toBe(true);
        });

        it('period contributions sum the mocked transactions dated inside the period', () => {
          const inPeriod = d.recentTransactions
            .filter((t) => localDay(t.date) >= '2026-08-01' && localDay(t.date) <= '2026-08-31')
            .reduce((a, t) => a + t.amount, 0);
          expect(near(m.periodContributions, inPeriod)).toBe(true);
        });

        it('freedom year comes from the shared projection math with the live inputs', () => {
          expect(m.projectedFreedomYear).toBe(freedomYear(m.currentEquityValue, INPUTS.monthlyContribution));
        });

        it('best next move is +20% of the schedule ($80/month here) and never later than the base year', () => {
          // Dynamic boost: cycle 0 = 20% of the $400 schedule = $80 (rounded to $10, floored at $50).
          expect(m.bestNextMoveBoostAmount).toBe(80);
          expect(m.bestNextMoveYear).toBe(freedomYear(m.currentEquityValue, INPUTS.monthlyContribution + 80));
          expect(m.bestNextMoveYearsEarlier).toBe(m.projectedFreedomYear - m.bestNextMoveYear);
          expect(m.frequencyLabel).toBe('month');
          expect(m.investmentFrequency).toBe('MONTHLY');
          expect(m.currentInvestmentAmount).toBe(INPUTS.scheduleMonthly);
          expect(m.recurringInvestmentAmount).toBe(INPUTS.scheduleMonthly);
        });

        it('days bought back follows the projection delta between the period bounds', () => {
          const fromStart = monthsToTarget(m.startEquityValue, INPUTS.monthlyContribution, INPUTS.targetPortfolio, INPUTS.annualReturn);
          const fromEnd = monthsToTarget(m.endEquityValue, INPUTS.monthlyContribution, INPUTS.targetPortfolio, INPUTS.annualReturn);
          expect(m.daysBoughtBack).toBe(Math.max(0, Math.round((fromStart - fromEnd) * 30.44)));
        });

        it('percentile is clamped to 1..99 and age echoes the input', () => {
          expect(m.statusPercentile).toBeGreaterThanOrEqual(1);
          expect(m.statusPercentile).toBeLessThanOrEqual(99);
          expect(m.age).toBe(INPUTS.age);
        });

        it('streak reflects the account age in months', () => {
          const inception = Date.parse(mockHistory(tier, 'ALL').timestamps[0]);
          const days = Math.floor((Date.now() - inception) / DAY_MS);
          expect(m.currentStreak).toBe(Math.floor(days / 30));
        });

        it('is a first-of-month update by default: non-quarterly, mfuCount 0, seen-able', () => {
          expect(m.shouldShow).toBeTrue();
          expect(m.hasMfuHistory).toBeTrue();
          expect(m.showQuarterlyCompare).toBeFalse();
          expect(m.mfuCount).toBe(0);
          expect(m.reopen).toBeFalse();
          expect(mockMfu(tier, INPUTS, DERIVED, true).reopen).toBeTrue();
        });
      });
    });

    it('percentile grows with equity (XLARGE is far above the age-bracket median)', () => {
      const small = mockMfu('SMALL', INPUTS, DERIVED, false).statusPercentile;
      const xlarge = mockMfu('XLARGE', INPUTS, DERIVED, false).statusPercentile;
      expect(xlarge).toBe(99);
      expect(xlarge).toBeGreaterThan(small);
    });
  });

  describe('derived milestones apply the prod rule to the mocked history', () => {
    it('SMALL (28-day account) crosses $1k from $0 and logs its first investment: one combined card', () => {
      const ms = mockMfu('SMALL', INPUTS, DERIVED, false).milestones;
      expect(ms.length).toBe(1);
      expect(ms[0].type).toBe('INVESTMENT_1,EQUITY_1K');
      expect(ms[0].label).toBe('1st Investment! $1k Total Equity Reached!');
      expect(ms[0].subtitle).toBe('You reached 2 major milestones.');
    });

    (['MEDIUM', 'LARGE', 'XLARGE'] as MockTier[]).forEach((tier) => {
      it(`${tier} stayed inside one range last month: DEFAULT card`, () => {
        const ms = mockMfu(tier, INPUTS, DERIVED, false).milestones;
        expect(ms.length).toBe(1);
        expect(ms[0].type).toBe('DEFAULT');
        expect(ms[0].label).toBe('No major milestones reached');
        expect(ms[0].subtitle).toBe("FRED's still so proud of you though");
      });
    });
  });

  describe('milestone modes only swap the card, never the equity', () => {
    const EXPECTED_EQUITY: Record<string, { key: string; label: string }> = {
      SMALL: { key: 'EQUITY_1K', label: '$1k' },
      MEDIUM: { key: 'EQUITY_10K', label: '$10k' },
      LARGE: { key: 'EQUITY_250K', label: '$250k' },
      XLARGE: { key: 'EQUITY_1M', label: '$1M' },
    };

    FUNDED_TIERS.forEach((tier) => {
      it(`EQUITY forces the highest milestone ${tier}'s end equity qualifies for`, () => {
        const derived = mockMfu(tier, INPUTS, DERIVED, false);
        const forced = mockMfu(tier, INPUTS, { milestone: 'EQUITY', quarterly: false }, false);
        expect(forced.milestones[0].type).toBe(EXPECTED_EQUITY[tier].key);
        expect(forced.milestones[0].label).toBe(`${EXPECTED_EQUITY[tier].label} Total Equity Reached!`);
        expect(forced.milestones[0].subtitle).toBe('You reached a major milestone.');
        expect(forced.startEquityValue).toBe(derived.startEquityValue);
        expect(forced.endEquityValue).toBe(derived.endEquityValue);
        expect(forced.currentEquityValue).toBe(derived.currentEquityValue);
        expect(forced.equityLevel).toBe(derived.equityLevel);
      });
    });

    it('COMBO adds the 1st and 10th investment milestones to the equity one', () => {
      const ms = mockMfu('XLARGE', INPUTS, { milestone: 'COMBO', quarterly: false }, false).milestones[0];
      expect(ms.type).toBe('INVESTMENT_1,INVESTMENT_10,EQUITY_1M');
      expect(ms.label).toBe('1st and 10th Investment! $1M Total Equity Reached!');
      expect(ms.subtitle).toBe('You reached 3 major milestones.');
    });

    it('NONE renders the DEFAULT card', () => {
      const ms = mockMfu('XLARGE', INPUTS, { milestone: 'NONE', quarterly: false }, false).milestones[0];
      expect(ms.type).toBe('DEFAULT');
    });
  });

  describe('overlayMilestone (applied to a real /generate payload when no tier is active)', () => {
    const real = { endEquityValue: 2_400_000, milestones: [{ type: 'DEFAULT', label: 'x', subtitle: 'y' }], periodLabel: 'Jul 1 to Jul 31' } as unknown as MonthlyFreedomUpdateData;

    it('DERIVED leaves the payload untouched', () => {
      expect(overlayMilestone(real, 'DERIVED')).toBe(real);
    });

    it('EQUITY rewrites only the milestone card from the payload end equity', () => {
      const out = overlayMilestone(real, 'EQUITY');
      expect(out.milestones[0].type).toBe('EQUITY_1M');
      expect(out.milestones[0].label).toBe('$1M Total Equity Reached!');
      expect(out.periodLabel).toBe('Jul 1 to Jul 31');
      expect(out.endEquityValue).toBe(2_400_000);
      expect(real.milestones[0].type).toBe('DEFAULT'); // input not mutated
    });

    it('EQUITY below $1k has nothing to force and falls back to DEFAULT', () => {
      const tiny = { ...real, endEquityValue: 500 } as MonthlyFreedomUpdateData;
      expect(overlayMilestone(tiny, 'EQUITY').milestones[0].type).toBe('DEFAULT');
    });
  });

  describe('quarterly toggle', () => {
    it('turns on the every-3rd-month review with numbers from the same curve', () => {
      const d = mockDashboard('XLARGE');
      const year = mockHistory('XLARGE', '1Y');
      const twelveAgo = new Date(TODAY);
      twelveAgo.setMonth(twelveAgo.getMonth() - 12);
      const iso = isoLocal(twelveAgo);
      const e12 = equityAt(year, iso);
      const m = mockMfu('XLARGE', INPUTS, { milestone: 'DERIVED', quarterly: true }, false);

      expect(m.showQuarterlyCompare).toBeTrue();
      expect(m.mfuCount).toBe(2);
      // Quarterly is cycle 2 (CTA hidden), but the field still mirrors the backend: 10% of $400 = $40.
      expect(m.bestNextMoveBoostAmount).toBe(40);
      expect(e12).toBeGreaterThan(0);
      expect(near(m.equity12MonthsAgo, e12)).toBe(true);
      expect(near(m.netWorthChange, d.summary.equity - e12)).toBe(true);
      const yearly = d.recentTransactions.filter((t) => localDay(t.date) >= iso).reduce((a, t) => a + t.amount, 0);
      expect(near(m.yearlyContributions, yearly)).toBe(true);
      const from12 = monthsToTarget(e12, INPUTS.monthlyContribution, INPUTS.targetPortfolio, INPUTS.annualReturn);
      const fromNow = monthsToTarget(d.summary.equity, INPUTS.monthlyContribution, INPUTS.targetPortfolio, INPUTS.annualReturn);
      expect(near(m.freedomYearsChange, Math.round(((from12 - fromNow) / 12) * 10) / 10, 0.051)).toBe(true);
    });

    it('a tier younger than a year reports $0 twelve months ago', () => {
      const m = mockMfu('MEDIUM', INPUTS, { milestone: 'DERIVED', quarterly: true }, false);
      expect(m.equity12MonthsAgo).toBe(0);
      expect(m.netWorthChange).toBe(m.currentEquityValue);
    });
  });

  describe('evening local time (every mock timestamp\'s UTC date is already tomorrow)', () => {
    beforeEach(() => jasmine.clock().mockDate(new Date(2026, 8, 1, 21, 30, 0)));

    it('reads the period bounds by LOCAL calendar day, the day the chart labels each point with', () => {
      const period = mockHistory('MEDIUM', '3M');
      const m = mockMfu('MEDIUM', INPUTS, DERIVED, false);
      const startIdx = period.timestamps.findIndex((t) => localDay(t) === '2026-08-01');
      const endIdx = period.timestamps.findIndex((t) => localDay(t) === '2026-08-31');
      expect(startIdx).toBeGreaterThan(-1);
      expect(endIdx).toBeGreaterThan(-1);
      expect(m.startEquityValue).toBe(period.values[startIdx]);
      expect(m.endEquityValue).toBe(period.values[endIdx]);
    });
  });

  describe('ZERO tier', () => {
    it('still produces a harmless empty update (level 1, DEFAULT card) if generated', () => {
      const m = mockMfu('ZERO', INPUTS, DERIVED, false);
      expect(m.currentEquityValue).toBe(0);
      expect(m.equityLevel).toBe(1);
      expect(m.milestones[0].type).toBe('DEFAULT');
      expect(m.currentStreak).toBe(0);
      expect(m.daysBoughtBack).toBe(0);
    });
  });
});
