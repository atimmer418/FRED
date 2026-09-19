import { Injectable } from '@angular/core';

// ─── Existing types (unchanged) ──────────────────────────────────────────────

export type StrategyType = 'traditional' | 'sbloc' | 'annuity-growth' | 'dynamic-guardrails' | 'full-annuity';

export interface SimulationParams {
  strategy: StrategyType;
  portfolioValue: number;
  annualWithdrawal: number;
  yearsToLast: number;
  useNTSX: boolean;
  // Advanced parameters
  sblocInterestRate?: number;
  sblocLtvLimit?: number;
  annuityPercentage?: number; // 0 to 1
  guardrailLower?: number;
  guardrailUpper?: number;
}

export interface SimulationResult {
  successProbability: number;
  medianEndValue: number;
  minimumEndValue: number;
  failureRate: number;
  medianTrajectory: number[]; // Array of median portfolio values per year [year0, year1, ...]
  worstCaseTrajectory: number[]; // Array of worst-case values
  bestCaseTrajectory: number[]; // Array of best-case values
}

// ─── FRED-207: Scenario types and assumption constants ────────────────────────

export type ScenarioType = 'conservative' | 'expected' | 'aggressive';

export interface ScenarioAssumptions {
  stockReturn: number;
  stockVol: number;
  bondReturn: number;
  bondVol: number;
  /** Inflation rate used for withdrawal growth in the showdown sim. */
  inflation: number;
}

/**
 * Named assumption constants for the three market scenarios.
 *
 * The showdown simulates the FRED portfolio: 100% stocks (VTI · VXUS · VBR),
 * so only stockReturn/stockVol drive returns (2026-09-12; previously a 70/30
 * stock/bond blend). Bond fields remain for the legacy runSimulation API.
 *
 * Internal keys are stable storage identifiers; UI labels map as
 * conservative → "Conservative (8%)", expected → "Moderate (10%)",
 * aggressive → "Optimistic (12%)". The expected stock return matches the
 * RateAssumptionsService default (10%) so Moderate = the app standard.
 *
 * Conservative : stocks 8% / 20 vol
 * Moderate     : stocks 10% / 18 vol
 * Optimistic   : stocks 12% / 16 vol
 * Inflation 2.5% for all scenarios.
 */
export const SCENARIO_ASSUMPTIONS: Record<ScenarioType, ScenarioAssumptions> = {
  conservative: { stockReturn: 0.08, stockVol: 0.20, bondReturn: 0.035, bondVol: 0.055, inflation: 0.025 },
  expected:     { stockReturn: 0.10, stockVol: 0.18, bondReturn: 0.04,  bondVol: 0.05,  inflation: 0.025 },
  aggressive:   { stockReturn: 0.12, stockVol: 0.16, bondReturn: 0.045, bondVol: 0.045, inflation: 0.025 },
};

/**
 * Parameters for the new showdown API (FRED-207).
 * All five strategies are run against the same set of 1,000 paths.
 */
export interface ShowdownParams {
  /** Combined starting portfolio value (FRED portfolio + outside accounts if toggled). */
  portfolioValue: number;
  /** Monthly retirement spend; annualized ×12 inside each simulation path. */
  monthlySpend: number;
  /** Number of withdrawal years to test in each path. */
  yearsToLast: number;
  /** Market assumption set to draw returns from. */
  scenario: ScenarioType;
  /**
   * Monthly contribution during the pre-retirement accumulation phase.
   * 0 means no accumulation — the withdrawal phase starts at portfolioValue.
   */
  monthlyContribution: number;
  /**
   * Years of accumulation before withdrawals begin.
   * 0 is a withdrawal-only run (equivalent to retiring today).
   */
  yearsToRetirement: number;
}

/** Per-strategy success rates (0–100) returned by runShowdown. */
export interface ShowdownResult {
  traditional: number;
  sbloc: number;
  'annuity-growth': number;
  'dynamic-guardrails': number;
  'full-annuity': number;
}

// ─── Service ─────────────────────────────────────────────────────────────────

@Injectable({
  providedIn: 'root'
})
export class MonteCarloService {

  private readonly SIMULATIONS = 1000;
  private readonly INFLATION_RATE = 0.025;

  // Legacy market assumptions (Expected scenario — used by the original runSimulation API)
  private readonly STOCK_RETURN = 0.10;
  private readonly STOCK_VOL = 0.18;
  private readonly BOND_RETURN = 0.04;
  private readonly BOND_VOL = 0.05;

  // NTSX Assumptions (90/60)
  private readonly NTSX_RETURN = 0.10;
  private readonly NTSX_VOL = 0.15;

  /**
   * Random-number source. Override in unit tests by calling setRng() with a
   * seeded deterministic function so simulations produce reproducible results.
   * Defaults to Math.random — no production behaviour change.
   */
  private _rng: () => number = Math.random;

  constructor() { }

  /**
   * Inject a custom random-number source for deterministic unit testing.
   * Must be called before running any simulation that should be seeded.
   */
  setRng(fn: () => number): void {
    this._rng = fn;
  }

  // =========================================================================
  // LEGACY PUBLIC API (unchanged — used by retirement-planning until replaced)
  // =========================================================================

  /**
   * Run a simulation for a specific strategy configuration.
   */
  public runSimulation(params: SimulationParams): SimulationResult {
    switch (params.strategy) {
      case 'sbloc':
        return this.simulateSBLOC(params);
      case 'annuity-growth':
        return this.simulateAnnuityGrowth(params);
      case 'dynamic-guardrails':
        return this.simulateDynamicGuardrails(params);
      case 'full-annuity':
        return this.simulateFullAnnuity(params);
      case 'traditional':
      default:
        return this.simulateTraditional(params);
    }
  }

  /**
   * Run all strategies in parallel for comparison.
   */
  public runComparison(baseParams: Omit<SimulationParams, 'strategy'>): Record<StrategyType, SimulationResult> {
    const strategies: StrategyType[] = ['traditional', 'sbloc', 'annuity-growth', 'dynamic-guardrails', 'full-annuity'];
    const results: any = {};
    strategies.forEach(strategy => {
      results[strategy] = this.runSimulation({ ...baseParams, strategy });
    });
    return results;
  }

  // =========================================================================
  // FRED-207: SHOWDOWN API
  // =========================================================================

  /**
   * Run all five withdrawal strategies against 1,000 simulated market paths and
   * return each strategy's success rate (0–100).
   *
   * Two-phase logic: when yearsToRetirement > 0, each path first accumulates
   * the portfolio (with monthlyContribution) for that many years before the
   * withdrawal phase begins. At yearsToRetirement === 0 the accumulation is
   * skipped and portfolioValue is used directly — identical to the old one-phase
   * approach.
   */
  public runShowdown(params: ShowdownParams): ShowdownResult {
    return {
      traditional:          this._sd_traditional(params),
      sbloc:                this._sd_sbloc(params),
      'annuity-growth':     this._sd_annuityGrowth(params),
      'dynamic-guardrails': this._sd_guardrails(params),
      'full-annuity':       this._sd_fullAnnuity(params),
    };
  }

  /**
   * Inverse solver: find the maximum monthly spend at which the Traditional 4%
   * strategy succeeds in at least targetPct of simulated paths (binary search,
   * 12 iterations). Result is rounded to the nearest $50.
   *
   * @param params - ShowdownParams without monthlySpend (it is the unknown).
   * @param targetPct - Target success rate fraction, default 0.90 (90%).
   */
  public solveMaxMonthlySpend(
    params: Omit<ShowdownParams, 'monthlySpend'>,
    targetPct: number = 0.90
  ): number {
    let lo = 200, hi = 60000;
    for (let i = 0; i < 12; i++) {
      const mid = (lo + hi) / 2;
      const rate = this._sd_traditional({ ...params, monthlySpend: mid }) / 100;
      if (rate >= targetPct) lo = mid;
      else hi = mid;
    }
    return Math.round(lo / 50) * 50;
  }

  // =========================================================================
  // SCENARIO HELPERS (FRED-207 internal)
  // =========================================================================

  /**
   * Draw an annual return for the FRED portfolio (100% stocks: VTI · VXUS ·
   * VBR) from the given scenario. Switched from a 70/30 stock/bond blend
   * 2026-09-12 so simulations model what users actually hold: higher mean
   * return with the higher volatility priced in by the simulation itself.
   */
  private _scenarioReturn(scenario: ScenarioType): number {
    const a = SCENARIO_ASSUMPTIONS[scenario];
    return this._normalRandom(a.stockReturn, a.stockVol);
  }

  /**
   * Accumulate a starting value with monthly contributions for `years` years,
   * drawing market returns from the given scenario.
   * Returns Math.max(result, 0) so paths never go negative in accumulation.
   * When years === 0 the starting value is returned unchanged.
   */
  private _accumulate(start: number, contribMo: number, years: number, scenario: ScenarioType): number {
    if (years === 0) return start;
    let v = start;
    for (let y = 0; y < years; y++) {
      const r = this._scenarioReturn(scenario);
      // Annual compounding of existing balance + mid-year contribution approximation
      v = v * (1 + r) + contribMo * 12 * (1 + r / 2);
    }
    return Math.max(v, 0);
  }

  /**
   * A spend entered in today's dollars must be inflation-adjusted to the
   * start of the withdrawal phase: with an accumulation phase of N years,
   * today's $4,000/mo of spending power costs (1+inflation)^N as many nominal
   * dollars on day one of retirement. Without this, seeded/stepped
   * yearsToRetirement overstated success (review-confirmed 2026-09-12).
   */
  private _retirementStartSpend(params: ShowdownParams): number {
    const inf = SCENARIO_ASSUMPTIONS[params.scenario].inflation;
    return params.monthlySpend * 12 * Math.pow(1 + inf, params.yearsToRetirement);
  }

  /** Resolve the per-path starting withdrawal-phase portfolio value. */
  private _phaseStart(params: ShowdownParams): number {
    return this._accumulate(
      params.portfolioValue,
      params.monthlyContribution,
      params.yearsToRetirement,
      params.scenario
    );
  }

  // =========================================================================
  // SHOWDOWN STRATEGY IMPLEMENTATIONS (FRED-207 internal)
  // =========================================================================

  private _sd_traditional(params: ShowdownParams): number {
    const annual = this._retirementStartSpend(params);
    const sc = params.scenario;
    const inf = SCENARIO_ASSUMPTIONS[sc].inflation;
    let ok = 0;
    for (let s = 0; s < this.SIMULATIONS; s++) {
      let v = this._phaseStart(params);
      let w = annual;
      let alive = true;
      for (let y = 0; y < params.yearsToLast; y++) {
        v *= (1 + this._scenarioReturn(sc));
        w *= (1 + inf);
        v -= w;
        if (v <= 0) { alive = false; break; }
      }
      if (alive) ok++;
    }
    return (ok / this.SIMULATIONS) * 100;
  }

  private _sd_sbloc(params: ShowdownParams): number {
    const annual = this._retirementStartSpend(params);
    const sc = params.scenario;
    const inf = SCENARIO_ASSUMPTIONS[sc].inflation;
    const ir = 0.055;
    const ltvMax = 0.70;
    let ok = 0;
    for (let s = 0; s < this.SIMULATIONS; s++) {
      let v = this._phaseStart(params);
      let w = annual;
      let debt = 0;
      let alive = true;
      for (let y = 0; y < params.yearsToLast; y++) {
        const r = this._scenarioReturn(sc);
        v *= (1 + r);
        w *= (1 + inf);
        if (r < 0) {
          debt += w;
          debt *= (1 + ir);
        } else {
          v -= w;
          if (r > 0.12 && debt > 0) {
            const pay = Math.min(debt, v * 0.1);
            debt -= pay;
            v -= pay;
          }
        }
        if (v <= 0 || debt / v > ltvMax) { alive = false; break; }
      }
      if (alive) ok++;
    }
    return (ok / this.SIMULATIONS) * 100;
  }

  private _sd_annuityGrowth(params: ShowdownParams): number {
    const annual = this._retirementStartSpend(params);
    const sc = params.scenario;
    const inf = SCENARIO_ASSUMPTIONS[sc].inflation;
    let ok = 0;
    for (let s = 0; s < this.SIMULATIONS; s++) {
      const startV = this._phaseStart(params);
      // Annuitize just enough to cover essential expenses (= annual spend) at 6% payout
      const annAmt = Math.min(annual / 0.06, startV);
      const income = annAmt * 0.06;
      let g = startV - annAmt;
      let w = annual;
      let alive = true;
      for (let y = 0; y < params.yearsToLast; y++) {
        g *= (1 + this._scenarioReturn(sc));
        w *= (1 + inf);
        g -= Math.max(0, w - income);
        if (g <= 0) {
          g = 0;
          if (w > income) { alive = false; break; }
        }
      }
      if (alive) ok++;
    }
    return (ok / this.SIMULATIONS) * 100;
  }

  private _sd_guardrails(params: ShowdownParams): number {
    const sc = params.scenario;
    const inf = SCENARIO_ASSUMPTIONS[sc].inflation;
    let ok = 0;
    for (let s = 0; s < this.SIMULATIONS; s++) {
      let v = this._phaseStart(params);
      let w = v * 0.05; // Initial 5% withdrawal
      let alive = true;
      for (let y = 0; y < params.yearsToLast; y++) {
        v *= (1 + this._scenarioReturn(sc));
        const rate = w / v;
        if (rate > 0.06) w *= 0.90;
        else if (rate < 0.04) w *= 1.10;
        else w *= (1 + inf);
        v -= w;
        if (v <= 0) { alive = false; break; }
      }
      if (alive) ok++;
    }
    return (ok / this.SIMULATIONS) * 100;
  }

  private _sd_fullAnnuity(params: ShowdownParams): number {
    const annual = this._retirementStartSpend(params);
    // With accumulation, the annuity income is stochastic (depends on grown value)
    if (params.yearsToRetirement > 0) {
      let ok = 0;
      for (let s = 0; s < this.SIMULATIONS; s++) {
        if (this._phaseStart(params) * 0.06 >= annual) ok++;
      }
      return (ok / this.SIMULATIONS) * 100;
    }
    // Without accumulation, outcome is deterministic
    return params.portfolioValue * 0.06 >= annual ? 100 : 0;
  }

  // =========================================================================
  // LEGACY STRATEGY IMPLEMENTATIONS
  // =========================================================================

  private simulateTraditional(params: SimulationParams): SimulationResult {
    let successCount = 0;
    const endValues: number[] = [];
    const yearlyValues: number[][] = [];

    for (let sim = 0; sim < this.SIMULATIONS; sim++) {
      let portfolioVal = params.portfolioValue;
      let withdrawal = params.annualWithdrawal;
      let success = true;
      const trajectory: number[] = [portfolioVal];

      for (let year = 0; year < params.yearsToLast; year++) {
        const ret = this.getPortfolioReturn(params.useNTSX);
        portfolioVal *= (1 + ret);
        withdrawal *= (1 + this.INFLATION_RATE);
        portfolioVal -= withdrawal;

        if (portfolioVal <= 0) {
          success = false;
          portfolioVal = 0;
        }
        trajectory.push(portfolioVal);
        if (!success) break;
      }

      while (trajectory.length <= params.yearsToLast) {
        trajectory.push(0);
      }

      endValues.push(portfolioVal);
      yearlyValues.push(trajectory);
      if (success && portfolioVal > 0) successCount++;
    }

    return this.processResults(successCount, endValues, yearlyValues, params.yearsToLast);
  }

  private simulateSBLOC(params: SimulationParams): SimulationResult {
    let successCount = 0;
    const endValues: number[] = [];
    const yearlyValues: number[][] = [];

    const interestRate = params.sblocInterestRate ?? 0.055;
    const ltvLimit = params.sblocLtvLimit ?? 0.70;

    for (let sim = 0; sim < this.SIMULATIONS; sim++) {
      let portfolioVal = params.portfolioValue;
      let withdrawal = params.annualWithdrawal;
      let sblocDebt = 0;
      let success = true;
      const trajectory: number[] = [portfolioVal];

      for (let year = 0; year < params.yearsToLast; year++) {
        const ret = this.getPortfolioReturn(params.useNTSX);
        portfolioVal *= (1 + ret);
        withdrawal *= (1 + this.INFLATION_RATE);

        if (ret < 0) {
          sblocDebt += withdrawal;
          sblocDebt *= (1 + interestRate);
        } else {
          portfolioVal -= withdrawal;
          if (ret > 0.12 && sblocDebt > 0) {
            const payoff = Math.min(sblocDebt, portfolioVal * 0.1);
            sblocDebt -= payoff;
            portfolioVal -= payoff;
          }
        }

        const ltv = sblocDebt / portfolioVal;
        if (ltv > ltvLimit || portfolioVal <= 0) {
          success = false;
          portfolioVal = 0;
        }

        let netValue = Math.max(0, portfolioVal - sblocDebt);
        trajectory.push(netValue);

        if (!success) break;
      }

      while (trajectory.length <= params.yearsToLast) {
        trajectory.push(0);
      }

      endValues.push(trajectory[trajectory.length - 1]);
      yearlyValues.push(trajectory);
      if (success) successCount++;
    }

    return this.processResults(successCount, endValues, yearlyValues, params.yearsToLast);
  }

  private simulateAnnuityGrowth(params: SimulationParams): SimulationResult {
    let successCount = 0;
    const endValues: number[] = [];
    const yearlyValues: number[][] = [];

    const annuityPct = params.annuityPercentage ?? 0.50;
    const annuityAmount = params.portfolioValue * annuityPct;
    const growthStart = params.portfolioValue - annuityAmount;
    const annuityIncome = annuityAmount * 0.06;

    for (let sim = 0; sim < this.SIMULATIONS; sim++) {
      let growthVal = growthStart;
      let withdrawal = params.annualWithdrawal;
      let success = true;
      const trajectory: number[] = [growthVal];

      for (let year = 0; year < params.yearsToLast; year++) {
        const ret = this.getPortfolioReturn(params.useNTSX);
        growthVal *= (1 + ret);
        withdrawal *= (1 + this.INFLATION_RATE);

        const need = Math.max(0, withdrawal - annuityIncome);
        growthVal -= need;

        if (growthVal <= 0) {
          growthVal = 0;
          if (withdrawal > annuityIncome) success = false;
        }

        trajectory.push(growthVal);
        if (!success && growthVal <= 0) break;
      }

      while (trajectory.length <= params.yearsToLast) {
        trajectory.push(0);
      }

      endValues.push(growthVal);
      yearlyValues.push(trajectory);
      if (success) successCount++;
    }

    return this.processResults(successCount, endValues, yearlyValues, params.yearsToLast);
  }

  private simulateDynamicGuardrails(params: SimulationParams): SimulationResult {
    let successCount = 0;
    const endValues: number[] = [];
    const yearlyValues: number[][] = [];

    const lower = params.guardrailLower ?? 0.04;
    const upper = params.guardrailUpper ?? 0.06;

    for (let sim = 0; sim < this.SIMULATIONS; sim++) {
      let portfolioVal = params.portfolioValue;
      let withdrawal = params.portfolioValue * 0.05; // Initial 5%
      let success = true;
      const trajectory: number[] = [portfolioVal];

      for (let year = 0; year < params.yearsToLast; year++) {
        const ret = this.getPortfolioReturn(params.useNTSX);
        portfolioVal *= (1 + ret);

        const rate = withdrawal / portfolioVal;

        if (rate > upper) withdrawal *= 0.90;
        else if (rate < lower) withdrawal *= 1.10;
        else withdrawal *= (1 + this.INFLATION_RATE);

        portfolioVal -= withdrawal;

        if (portfolioVal <= 0) {
          success = false;
          portfolioVal = 0;
        }

        trajectory.push(portfolioVal);
        if (!success) break;
      }

      while (trajectory.length <= params.yearsToLast) {
        trajectory.push(0);
      }

      endValues.push(portfolioVal);
      yearlyValues.push(trajectory);
      if (success) successCount++;
    }

    return this.processResults(successCount, endValues, yearlyValues, params.yearsToLast);
  }

  private simulateFullAnnuity(params: SimulationParams): SimulationResult {
    // Deterministic. 6% payout.
    const income = params.portfolioValue * 0.06;
    const success = income >= params.annualWithdrawal;
    const trajectory = Array(params.yearsToLast + 1).fill(0);

    return {
      successProbability: success ? 100 : 0,
      medianEndValue: 0,
      minimumEndValue: 0,
      failureRate: success ? 0 : 100,
      medianTrajectory: trajectory,
      worstCaseTrajectory: trajectory,
      bestCaseTrajectory: trajectory
    };
  }

  // =========================================================================
  // HELPERS
  // =========================================================================

  private getPortfolioReturn(useNTSX: boolean): number {
    if (useNTSX) {
      return this._normalRandom(this.NTSX_RETURN, this.NTSX_VOL);
    } else {
      const stock = this._normalRandom(this.STOCK_RETURN, this.STOCK_VOL);
      const bond = this._normalRandom(this.BOND_RETURN, this.BOND_VOL);
      return 0.7 * stock + 0.3 * bond;
    }
  }

  /**
   * Box-Muller normal random. Uses this._rng so unit tests can inject a seeded
   * source for deterministic output. Guard u1 against log(0).
   */
  private _normalRandom(mean: number, stdDev: number): number {
    const u1 = this._rng() || 1e-9;
    const u2 = this._rng();
    const z0 = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    return z0 * stdDev + mean;
  }

  /** @deprecated Use _normalRandom — kept for internal backward compatibility. */
  private generateNormalRandom(mean: number, stdDev: number): number {
    return this._normalRandom(mean, stdDev);
  }

  private processResults(
    successCount: number,
    endValues: number[],
    yearlyValues: number[][],
    years: number
  ): SimulationResult {
    endValues.sort((a, b) => a - b);

    const medianTrajectory: number[] = [];
    const worstCaseTrajectory: number[] = [];
    const bestCaseTrajectory: number[] = [];

    for (let t = 0; t <= years; t++) {
      const valuesAtT = yearlyValues.map(sim => sim[t]).sort((a, b) => a - b);
      medianTrajectory.push(valuesAtT[Math.floor(valuesAtT.length * 0.5)]);
      worstCaseTrajectory.push(valuesAtT[Math.floor(valuesAtT.length * 0.05)]);
      bestCaseTrajectory.push(valuesAtT[Math.floor(valuesAtT.length * 0.95)]);
    }

    return {
      successProbability: (successCount / this.SIMULATIONS) * 100,
      medianEndValue: endValues[Math.floor(endValues.length / 2)],
      minimumEndValue: endValues[0],
      failureRate: ((this.SIMULATIONS - successCount) / this.SIMULATIONS) * 100,
      medianTrajectory,
      worstCaseTrajectory,
      bestCaseTrajectory
    };
  }
}
