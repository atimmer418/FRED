/**
 * FreedomStatsService — FRED-200
 *
 * Singleton that owns the four data fetches (userProgress, portfolio equity,
 * KYC birth-year, investment schedule) and computes the freedom stat strip
 * values (freedom year, freedom age, dollars away) that tab3 displays.
 *
 * First signals usage in the app — intentional, approved deviation from the
 * BehaviorSubject convention (CONTEXT.md §Services).
 */

import { Injectable, signal, computed, Signal, WritableSignal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { timeout, retry, timer, throwError } from 'rxjs';
import { AuthService, UserProgress } from './auth.service';
import { AlpacaService } from './alpaca.service';
import { PortfolioStoreService } from './portfolio-store.service';
import { InvestmentFrequencyUtils } from '../utils/investment-frequency.utils';
import { RateAssumptionsService } from './rate-assumptions.service';

/** Shape of the three computed stat-strip values. */
export interface FreedomStats {
  freedomYear: string;
  freedomAge: string;
  dollarsAway: string;
}

/** Shape persisted in localStorage. */
interface StatSnapshot {
  userId: string;
  freedomYear: string;
  freedomAge: string;
  dollarsAway: string;
}

/** localStorage key prefix for the user-scoped snapshot. */
const SNAPSHOT_KEY_PREFIX = 'fred.statStrip.v1.';

@Injectable({ providedIn: 'root' })
export class FreedomStatsService {

  // Projection rates come from RateAssumptionsService (user-adjustable;
  // defaults mirror MonthlyFreedomUpdateService.ASSUMED_ANNUAL_RETURN / 4% SWR
  // on the backend so at-default numbers match what the Monthly Freedom Update
  // computes). Read as signals inside computeds so rate edits re-derive
  // everything live.
  private readonly rates = inject(RateAssumptionsService);
  private readonly DEFAULT_TARGET = 1_500_000;

  // --- Injected services ---
  private authService = inject(AuthService);
  private portfolioStore = inject(PortfolioStoreService);
  private alpacaService = inject(AlpacaService);

  // --- Reactive state ---

  /**
   * Bridge from the existing BehaviorSubject-based userProgress$ to signals.
   * `null` means "not yet emitted".
   */
  readonly userProgress: Signal<UserProgress | null>;

  /**
   * Current portfolio equity in dollars.
   * `null` = not yet fetched; number (including 0) = fetch complete.
   */
  readonly equity: WritableSignal<number | null> = signal(null);

  /**
   * User's birth year, parsed from KYC date_of_birth.
   * `null` = not yet fetched; `0` = fetched but DOB was absent; positive = year.
   */
  readonly birthYear: WritableSignal<number | null> = signal(null);

  /**
   * Monthly-equivalent of the user's ACTUAL investment schedule.
   * `null` = not yet fetched; `0` = no schedule / fetch failed (survey fallback).
   */
  readonly scheduleMonthly: WritableSignal<number | null> = signal(null);

  /**
   * Snapshot of last-known display values painted from localStorage on cold launch.
   * Cleared once real data arrives. `null` means no valid snapshot active.
   */
  private readonly _snapshot: WritableSignal<StatSnapshot | null> = signal(null);

  /**
   * True while equity, birthYear, or scheduleMonthly haven't resolved yet (and no snapshot is active).
   */
  readonly isLoading: Signal<boolean>;

  /** The three computed stat-strip display values. */
  readonly stats: Signal<FreedomStats>;

  /**
   * Monthly investing GOAL the user saves on my-profile (survey/profile
   * monthlyInvestment). This is the number that drives the freedom date.
   */
  readonly monthlyTargetInvestment: Signal<number>;

  /**
   * Monthly equivalent of the ACTUAL investment schedule (0 = no schedule
   * or not yet fetched). Tracked for display; does not drive the freedom
   * date unless no goal is saved.
   */
  readonly monthlyActualInvestment: Signal<number>;

  /** Desired retirement income, annual dollars (null until progress loads). */
  readonly retirementIncomeAnnual: Signal<number | null>;

  constructor() {
    // Bridge userProgress$ into signal space.
    this.userProgress = toSignal(this.authService.userProgress$, { initialValue: null });

    // Unified freedom-store fields — single source of truth for the plan
    // numbers shared by tab3, MFU, and my-profile.
    this.monthlyTargetInvestment = computed(() => this.userProgress()?.monthlyInvestment ?? 0);
    this.monthlyActualInvestment = computed(() => this.scheduleMonthly() ?? 0);
    this.retirementIncomeAnnual = computed(() => this.userProgress()?.retirementIncome ?? null);

    // Loading gate: still loading when any fetch hasn't completed and
    // there is no snapshot to show in the meantime.
    this.isLoading = computed(() => {
      const notReady = this.equity() === null || this.birthYear() === null || this.scheduleMonthly() === null;
      const hasSnap = this._snapshot() !== null;
      return notReady && !hasSnap;
    });

    // Computed stats — re-runs whenever any input signal changes.
    this.stats = computed(() => {
      const progress = this.userProgress();
      const equity = this.equity();
      const birthYear = this.birthYear();
      const scheduleMonthly = this.scheduleMonthly();
      const snapshot = this._snapshot();

      // Still waiting for fetches — return snapshot values if we have them.
      if (equity === null || birthYear === null || scheduleMonthly === null) {
        if (snapshot) {
          return {
            freedomYear: snapshot.freedomYear,
            freedomAge: snapshot.freedomAge,
            dollarsAway: snapshot.dollarsAway
          };
        }
        return { freedomYear: '—', freedomAge: '—', dollarsAway: '—' };
      }

      const retirementIncomeAnnual: number | null = progress?.retirementIncome ?? null;

      // Contribution — the SAVED monthly investing goal (profile/survey
      // monthlyInvestment), falling back to the actual schedule's monthly
      // equivalent when no goal is set. Flipped 2026-08-01 (was
      // schedule-else-survey) so my-profile saves always propagate to the
      // freedom date. Mirrors MonthlyFreedomUpdateService (backend) — keep
      // the two in lockstep.
      const surveyMonthly: number = progress?.monthlyInvestment ?? 0;
      const monthlyContribution: number = surveyMonthly > 0 ? surveyMonthly : scheduleMonthly;

      // FI target — income-based 4% rule, $1.5M default.
      // UNIFIED with the Monthly Freedom Update by design: both sides compute
      // retirementIncome / 0.04 live (see MonthlyFreedomUpdateService
      // .resolveTargetPortfolio). Keep the two implementations in lockstep.
      const targetPortfolio: number =
        (retirementIncomeAnnual != null && retirementIncomeAnnual > 0)
          ? retirementIncomeAnnual / this.rates.withdrawalRate()
          : this.DEFAULT_TARGET;

      const currentEquity = equity ?? 0;
      const hasLiveInputs = currentEquity > 0 || monthlyContribution > 0;

      // Freedom year — income-based projection, computed client-side.
      let resolvedFreedomYear: number | null = null;
      if (hasLiveInputs) {
        resolvedFreedomYear = this._calculateFreedomYearClientSide(
          currentEquity,
          monthlyContribution,
          targetPortfolio
        );
      }

      // Freedom date
      const freedomYear = resolvedFreedomYear != null ? String(resolvedFreedomYear) : '—';

      // Freedom age — requires both a freedom year and a known birth year.
      let freedomAge = '—';
      if (resolvedFreedomYear != null && birthYear > 0) {
        const age = resolvedFreedomYear - birthYear;
        freedomAge = age > 0 ? String(age) : '—';
      }

      // Dollars away — gap to the same FI target.
      let dollarsAway = '—';
      if (hasLiveInputs) {
        const gap = Math.max(0, targetPortfolio - currentEquity);
        dollarsAway = this._formatCompactCurrency(gap);
      }

      const result: FreedomStats = { freedomYear, freedomAge, dollarsAway };

      // Persist snapshot after each successful computation.
      this._persistSnapshot(result);

      return result;
    });

    // Hydrate from snapshot on cold launch (before the network returns).
    this._hydrateFromSnapshot();

    // Start the initial fetches.
    this._fetchEquity();
    this._fetchBirthYear();
    this._fetchSchedule();
  }

  /**
   * Trigger a silent background refresh of equity, KYC, and schedule data.
   * Does NOT reset signals to null — so currently-displayed values stay visible
   * (stale-while-revalidate). Called by tab3 on re-entry and after profile saves.
   */
  refresh(): void {
    this._fetchEquity();
    this._fetchBirthYear();
    this._fetchSchedule();
  }

  // ---------------------------------------------------------------------------
  // Private — snapshot persistence (FRED-199 ordering: clear before userId gone)
  // ---------------------------------------------------------------------------

  private _hydrateFromSnapshot(): void {
    const userId = localStorage.getItem('userId');
    if (!userId) return;

    try {
      const raw = localStorage.getItem(SNAPSHOT_KEY_PREFIX + userId);
      if (!raw) return;

      const snapshot: StatSnapshot = JSON.parse(raw);

      // Validate: snapshot must belong to the current user and have all fields.
      if (!snapshot || snapshot.userId !== userId) return;
      if (!snapshot.freedomYear || !snapshot.freedomAge || !snapshot.dollarsAway) return;

      this._snapshot.set(snapshot);
    } catch {
      // Corrupt data — silently ignore, fall back to '—' loading state.
    }
  }

  private _persistSnapshot(result: FreedomStats): void {
    const userId = localStorage.getItem('userId');
    if (!userId) return;

    // Only persist if there's at least some real data (not all dashes).
    if (result.freedomYear === '—' && result.dollarsAway === '—') return;

    const snapshot: StatSnapshot = {
      userId,
      freedomYear: result.freedomYear,
      freedomAge: result.freedomAge,
      dollarsAway: result.dollarsAway
    };

    try {
      localStorage.setItem(SNAPSHOT_KEY_PREFIX + userId, JSON.stringify(snapshot));
    } catch {
      // Storage quota exceeded or private mode — silently ignore.
    }
  }

  // ---------------------------------------------------------------------------
  // Private — data fetches
  // ---------------------------------------------------------------------------

  private _fetchEquity(): void {
    // Route through the FRED-205 single-flight store so concurrent callers
    // (constructor + ionViewWillEnter refresh) join one in-flight HTTP request
    // rather than each firing their own.
    this.portfolioStore.load$().subscribe({
      next: (bundle) => {
        const value = bundle?.dashboard?.summary?.equity ?? 0;
        this.equity.set(value);
        // Clear snapshot once real data has arrived.
        this._snapshot.set(null);
      },
      error: () => {
        this.equity.set(0);
        this._snapshot.set(null);
      }
    });
  }

  private _fetchBirthYear(): void {
    // Bounded transient-only retry — verbatim from FRED-196's KYC call in tab3.
    // Retries on TimeoutError / status 0 / status ≥ 500; never on 401/403/404.
    // Error handler sets birthYear = 0 so the render gate can never hang.
    this.alpacaService.getKycData()
      .pipe(
        timeout(10_000),
        retry({
          count: 2,
          delay: (err: any, attempt: number) => {
            const status = err?.status ?? 0;
            const isTimeout = err?.name === 'TimeoutError';
            const transient = isTimeout || status === 0 || status >= 500;
            if (!transient) return throwError(() => err);
            return timer(400 * Math.pow(2, attempt - 1)); // 400ms, 800ms
          }
        })
      )
      .subscribe({
        next: (kyc) => {
          const dob: string | undefined = kyc?.identity?.date_of_birth;
          if (dob) {
            const parsed = parseInt(dob.substring(0, 4), 10);
            this.birthYear.set(isNaN(parsed) ? 0 : parsed);
          } else {
            // KYC returned but DOB was absent — genuine no-DOB.
            this.birthYear.set(0);
          }
        },
        error: () => {
          // KYC failed — freedom age will show '—'; other stats unaffected.
          this.birthYear.set(0);
        }
      });
  }

  private _fetchSchedule(): void {
    this.authService.getCurrentInvestmentSchedule()
      .subscribe({
        next: (schedule: any) => {
          const amount = Number(schedule?.investmentAmount) || 0;
          this.scheduleMonthly.set(
            InvestmentFrequencyUtils.toMonthlyEquivalent(amount, schedule?.frequency)
          );
        },
        error: () => {
          // No schedule reachable — survey fallback; gate must never hang.
          this.scheduleMonthly.set(0);
        }
      });
  }

  // ---------------------------------------------------------------------------
  // Private — math (ported byte-for-byte from tab3's loadStatStrip)
  // ---------------------------------------------------------------------------

  private _calculateFreedomYearClientSide(
    currentEquity: number,
    monthlyContribution: number,
    targetPortfolio: number
  ): number {
    const months = this._calculateMonthsToTarget(currentEquity, monthlyContribution, targetPortfolio);
    const years = Math.ceil(months / 12);
    const currentYear = new Date().getFullYear();
    return Math.min(currentYear + years, currentYear + 100);
  }

  private _calculateMonthsToTarget(
    currentEquity: number,
    monthlyContribution: number,
    targetPortfolio: number
  ): number {
    if (currentEquity >= targetPortfolio) return 0;
    if (currentEquity <= 0 && monthlyContribution <= 0) return 600;

    const r = this.rates.annualReturn() / 12;
    const PV = currentEquity;
    const FV = targetPortfolio;
    const PMT = monthlyContribution;

    if (PMT <= 0) {
      if (PV <= 0) return 600;
      const months = Math.log(FV / PV) / Math.log(1 + r);
      if (isNaN(months) || !isFinite(months) || months < 0) return 600;
      return months;
    }

    const numerator = Math.log((FV + PMT / r) / (PV + PMT / r));
    const denominator = Math.log(1 + r);
    if (denominator === 0 || isNaN(numerator) || !isFinite(numerator)) return 600;
    const months = numerator / denominator;
    if (isNaN(months) || !isFinite(months) || months < 0) return 600;
    return months;
  }

  private _formatCompactCurrency(value: number): string {
    if (!isFinite(value) || isNaN(value)) return '—';
    if (value === 0) return '$0';
    if (value >= 1_000_000) {
      const m = value / 1_000_000;
      return '$' + (m % 1 === 0 ? m.toFixed(0) : m.toFixed(2)) + 'M';
    }
    if (value >= 1_000) {
      const k = value / 1_000;
      return '$' + (k % 1 === 0 ? k.toFixed(0) : k.toFixed(1)) + 'K';
    }
    return '$' + Math.round(value).toLocaleString();
  }
}
