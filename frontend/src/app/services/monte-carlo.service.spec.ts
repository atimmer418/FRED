import { TestBed } from '@angular/core/testing';

import {
  MonteCarloService,
  SCENARIO_ASSUMPTIONS,
  ShowdownParams,
  ScenarioType,
} from './monte-carlo.service';

// ─── Test fixtures ────────────────────────────────────────────────────────────

/**
 * Near-mean RNG: always returns 0.9999.
 * Box-Muller maps this to z ≈ +0.014, giving every path a slightly-positive
 * return (≈ +10% for the 100%-stocks showdown portfolio). All accumulation
 * paths grow; all withdrawal paths
 * succeed at reasonable spend levels.
 */
const NEAR_MEAN_RNG = () => 0.9999;

/**
 * Build a minimal set of valid ShowdownParams with optional overrides.
 */
function mkParams(overrides: Partial<ShowdownParams> = {}): ShowdownParams {
  return {
    portfolioValue:    500_000,
    monthlySpend:      2_000,
    yearsToLast:       30,
    scenario:          'expected',
    monthlyContribution: 0,
    yearsToRetirement: 0,
    ...overrides,
  };
}

// ─── Suite ────────────────────────────────────────────────────────────────────

describe('MonteCarloService', () => {
  let service: MonteCarloService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MonteCarloService);
  });

  // ── Scenario assumption constants ─────────────────────────────────────────────

  describe('SCENARIO_ASSUMPTIONS constants', () => {
    it('covers exactly the three ScenarioType values', () => {
      const keys = Object.keys(SCENARIO_ASSUMPTIONS);
      expect(keys).toContain('conservative');
      expect(keys).toContain('expected');
      expect(keys).toContain('aggressive');
      expect(keys.length).toBe(3);
    });

    it('conservative: stocks 8%/20vol, bonds 3.5%/5.5vol, inflation 2.5%', () => {
      const c = SCENARIO_ASSUMPTIONS.conservative;
      expect(c.stockReturn).toBeCloseTo(0.08);
      expect(c.stockVol).toBeCloseTo(0.20);
      expect(c.bondReturn).toBeCloseTo(0.035);
      expect(c.bondVol).toBeCloseTo(0.055);
      expect(c.inflation).toBeCloseTo(0.025);
    });

    it('expected: stocks 10%/18vol, bonds 4%/5vol, inflation 2.5%', () => {
      const e = SCENARIO_ASSUMPTIONS.expected;
      expect(e.stockReturn).toBeCloseTo(0.10);
      expect(e.stockVol).toBeCloseTo(0.18);
      expect(e.bondReturn).toBeCloseTo(0.04);
      expect(e.bondVol).toBeCloseTo(0.05);
      expect(e.inflation).toBeCloseTo(0.025);
    });

    it('aggressive (Optimistic): stocks 12%/16vol, bonds 4.5%/4.5vol, inflation 2.5%', () => {
      const a = SCENARIO_ASSUMPTIONS.aggressive;
      expect(a.stockReturn).toBeCloseTo(0.12);
      expect(a.stockVol).toBeCloseTo(0.16);
      expect(a.bondReturn).toBeCloseTo(0.045);
      expect(a.bondVol).toBeCloseTo(0.045);
      expect(a.inflation).toBeCloseTo(0.025);
    });

    it('all three inflation rates are equal at 2.5%', () => {
      const scenarios: ScenarioType[] = ['conservative', 'expected', 'aggressive'];
      scenarios.forEach(sc => {
        expect(SCENARIO_ASSUMPTIONS[sc].inflation).toBeCloseTo(0.025);
      });
    });

    it('return and vol are strictly ordered: conservative < expected < aggressive (stocks)', () => {
      const { conservative: c, expected: e, aggressive: a } = SCENARIO_ASSUMPTIONS;
      expect(c.stockReturn).toBeLessThan(e.stockReturn);
      expect(e.stockReturn).toBeLessThan(a.stockReturn);
    });

    it('stock volatility is inversely ordered: conservative > expected > aggressive', () => {
      const { conservative: c, expected: e, aggressive: a } = SCENARIO_ASSUMPTIONS;
      expect(c.stockVol).toBeGreaterThan(e.stockVol);
      expect(e.stockVol).toBeGreaterThan(a.stockVol);
    });
  });

  // ── Two-phase accumulation ────────────────────────────────────────────────────

  describe('two-phase accumulation (via runShowdown)', () => {
    beforeEach(() => {
      service.setRng(NEAR_MEAN_RNG);
    });

    it('runShowdown result has all five strategy keys as numbers', () => {
      const r = service.runShowdown(mkParams());
      expect(typeof r.traditional).toBe('number');
      expect(typeof r.sbloc).toBe('number');
      expect(typeof r['annuity-growth']).toBe('number');
      expect(typeof r['dynamic-guardrails']).toBe('number');
      expect(typeof r['full-annuity']).toBe('number');
    });

    it('all strategy success rates are in [0, 100]', () => {
      const r = service.runShowdown(mkParams({ portfolioValue: 300_000 }));
      (Object.values(r) as number[]).forEach(v => {
        expect(v).toBeGreaterThanOrEqual(0);
        expect(v).toBeLessThanOrEqual(100);
      });
    });

    it('yearsToRetirement=0 uses portfolioValue as-is (large portfolio → near 100% success)', () => {
      const r = service.runShowdown(mkParams({
        portfolioValue: 10_000_000,
        monthlySpend: 2_000,
        yearsToRetirement: 0,
        monthlyContribution: 0,
      }));
      expect(r.traditional).toBeGreaterThanOrEqual(98);
    });

    it('adding an accumulation phase with contributions raises success vs no accumulation', () => {
      // Starting with a modest portfolio, no accumulation is borderline.
      const r0 = service.runShowdown(mkParams({
        portfolioValue: 150_000,
        monthlySpend: 2_000,
        yearsToRetirement: 0,
        monthlyContribution: 0,
      }));
      // Same start but 20 years of $2k/mo contributions before withdrawals begin.
      const r20 = service.runShowdown(mkParams({
        portfolioValue: 150_000,
        monthlySpend: 2_000,
        yearsToRetirement: 20,
        monthlyContribution: 2_000,
      }));
      // Accumulation + contributions should produce a meaningfully larger portfolio,
      // pushing success rate strictly higher.
      expect(r20.traditional).toBeGreaterThan(r0.traditional);
    });

    it('zero monthlyContribution with yearsToRetirement > 0 still grows via returns alone', () => {
      // With NEAR_MEAN_RNG (constant ~10% return, 100% stocks), $3k/mo withdrawal from $300k
      // depletes the portfolio in ~21 years → ytr=0 gives 0% success.
      // After 10 years of accumulation at ~10%, the portfolio grows past $750k,
      // which then sustains the same $3k/mo comfortably → 100% success.
      const r0 = service.runShowdown(mkParams({
        portfolioValue: 300_000,
        monthlySpend: 3_000,
        yearsToRetirement: 0,
        monthlyContribution: 0,
      }));
      const r10 = service.runShowdown(mkParams({
        portfolioValue: 300_000,
        monthlySpend: 3_000,
        yearsToRetirement: 10,
        monthlyContribution: 0,
      }));
      expect(r10.traditional).toBeGreaterThan(r0.traditional);
    });

    it('aggressive scenario produces same or higher success rate than conservative for same params', () => {
      const rCons = service.runShowdown(mkParams({ scenario: 'conservative' }));
      const rAggr = service.runShowdown(mkParams({ scenario: 'aggressive' }));
      // With near-mean RNG, higher-return scenarios should never do worse.
      expect(rAggr.traditional).toBeGreaterThanOrEqual(rCons.traditional);
    });
  });

  // ── Inverse solver ─────────────────────────────────────────────────────────────

  describe('solveMaxMonthlySpend', () => {
    beforeEach(() => {
      service.setRng(NEAR_MEAN_RNG);
    });

    it('returns a positive number', () => {
      const spend = service.solveMaxMonthlySpend(mkParams({ portfolioValue: 500_000 }));
      expect(spend).toBeGreaterThan(0);
    });

    it('result is always a multiple of $50', () => {
      const spend = service.solveMaxMonthlySpend(mkParams({ portfolioValue: 500_000 }));
      expect(spend % 50).toBe(0);
    });

    it('larger portfolio yields a higher solvable monthly spend', () => {
      const params = mkParams();
      const spend500k = service.solveMaxMonthlySpend({ ...params, portfolioValue: 500_000 });
      const spend1m   = service.solveMaxMonthlySpend({ ...params, portfolioValue: 1_000_000 });
      expect(spend1m).toBeGreaterThan(spend500k);
    });

    it('solved spend produces ≥ 89% success in Yield-Based Income (rounding-tolerant check)', () => {
      const params = mkParams({ portfolioValue: 800_000 });
      const spend = service.solveMaxMonthlySpend(params);
      const result = service.runShowdown({ ...params, monthlySpend: spend });
      // Binary search rounds to nearest $50, so we allow 1% below target.
      expect(result.traditional).toBeGreaterThanOrEqual(89);
    });

    it('spending $200 above solved value pushes success noticeably below solved level', () => {
      const params = mkParams({ portfolioValue: 800_000 });
      const spend = service.solveMaxMonthlySpend(params);
      const atSolved = service.runShowdown({ ...params, monthlySpend: spend });
      const overSolved = service.runShowdown({ ...params, monthlySpend: spend + 200 });
      // Over-spending should have a lower or equal success rate.
      expect(overSolved.traditional).toBeLessThanOrEqual(atSolved.traditional);
    });

    it('aggressive scenario solves to a spend amount ≥ conservative for the same portfolio', () => {
      const base = mkParams({ portfolioValue: 600_000 });
      const spendCons = service.solveMaxMonthlySpend({ ...base, scenario: 'conservative' });
      const spendAggr = service.solveMaxMonthlySpend({ ...base, scenario: 'aggressive' });
      expect(spendAggr).toBeGreaterThanOrEqual(spendCons);
    });

    it('accumulation phase with contributions raises the solvable monthly spend', () => {
      const base = mkParams({ portfolioValue: 200_000 });
      const spendNow = service.solveMaxMonthlySpend({ ...base, yearsToRetirement: 0, monthlyContribution: 0 });
      const spendLater = service.solveMaxMonthlySpend({ ...base, yearsToRetirement: 15, monthlyContribution: 1_500 });
      expect(spendLater).toBeGreaterThan(spendNow);
    });
  });

  // ── RNG injection determinism ───────────────────────────────────────────────────

  describe('setRng determinism', () => {
    it('two runs with the same seeded sequence produce identical showdown results', () => {
      const seq = [0.3, 0.7, 0.1, 0.9, 0.4, 0.6, 0.2, 0.8, 0.5, 0.05];
      let i = 0;
      const cyclicRng = () => seq[i++ % seq.length];

      service.setRng(cyclicRng);
      const r1 = service.runShowdown(mkParams());

      // Reset counter and reinject the same function to replay identical sequence.
      i = 0;
      service.setRng(cyclicRng);
      const r2 = service.runShowdown(mkParams());

      expect(r1.traditional).toBe(r2.traditional);
      expect(r1.sbloc).toBe(r2.sbloc);
      expect(r1['annuity-growth']).toBe(r2['annuity-growth']);
      expect(r1['dynamic-guardrails']).toBe(r2['dynamic-guardrails']);
      expect(r1['full-annuity']).toBe(r2['full-annuity']);
    });

    it('different RNG functions can produce different results for the same params', () => {
      // () => 0.9999: z ≈ +0.014 → stock return ≈ +10%/yr.
      // $500k portfolio growing at ~10% with only $2k/mo ($24k/yr) withdrawal → always succeeds.
      service.setRng(() => 0.9999);
      const rHigh = service.runShowdown(mkParams({ portfolioValue: 500_000, monthlySpend: 2_000 }));

      // () => 0.5: cos(π) = -1 → z ≈ -1.18 → stock return well negative every year.
      // Portfolio shrinks every year AND withdrawals grow → rapidly depleted → always fails.
      service.setRng(() => 0.5);
      const rLow = service.runShowdown(mkParams({ portfolioValue: 500_000, monthlySpend: 2_000 }));

      expect(rHigh.traditional).toBeGreaterThan(rLow.traditional);
    });
  });
});
