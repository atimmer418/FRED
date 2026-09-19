/**
 * freedom-stats.service.spec.ts — FRED-200
 *
 * Unit tests for FreedomStatsService.
 *
 * Covers:
 *  - AC-1: Math correctness (target, freedom year, freedom age, dollars away)
 *  - AC-2: Signals are the reactive primitive (signal-based API)
 *  - AC-5: FRED-196 KYC resilience (bounded transient-only retry)
 *  - AC-6: tab3-vs-MFU split (no MFU fields read)
 *  - AC-7: Unhappy paths (zero equity, failed progress, failed portfolio)
 *  - AC-8: localStorage snapshot (persist, hydrate, clear on foreign user)
 *
 * Note on fakeAsync + RxJS timers:
 *   The retry delay uses timer() which in zone-patched Jasmine fakeAsync is
 *   advanced by tick(). We tick past both possible delays (400ms + 800ms = 1200ms)
 *   plus the 10 000ms timeout guard to ensure all timers drain.
 */

import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { of, throwError, Observable, BehaviorSubject } from 'rxjs';
import { FreedomStatsService } from './freedom-stats.service';
import { AuthService } from './auth.service';
import { PortfolioStoreService, PortfolioBundle } from './portfolio-store.service';
import { AlpacaService } from './alpaca.service';

// ---------------------------------------------------------------------------
// Shared stub data
// ---------------------------------------------------------------------------

const userProgressStub = {
  getStartedCompleted: true,
  surveyInitialCompleted: true,
  fiPlanResultsCompleted: true,
  authFinalizeCompleted: true,
  kycVerificationCompleted: true,
  linkPlaidCompleted: true,
  investmentScheduleCompleted: true,
  investmentConfirmationCompleted: true,
  monthlyInvestment: 500,
  retirementIncome: 60000  // Annual → target = 60000 / 0.04 = $1,500,000
};

const portfolioBundleStub: PortfolioBundle = {
  dashboard: {
    summary: { equity: 50000, portfolioValue: 50000, todayChange: 0, todayChangePercent: 0, buyingPower: 0, cash: 0, settledCash: 0 },
    positions: [],
    history: { timestamps: [], values: [] },
    recentTransactions: [],
    totalInvested: 0,
    totalGainLoss: 0,
    totalGainLossPercent: 0
  } as any,
  performance: [],
  history: null
};

const kycDataStub = { identity: { date_of_birth: '1990-05-15' } };

// ---------------------------------------------------------------------------
// Factory helpers
// ---------------------------------------------------------------------------

const scheduleStub = { investmentAmount: 500, frequency: 'MONTHLY', isPaused: false };

function makeAuthServiceMock(
  progress = userProgressStub,
  scheduleObservable: Observable<any> = of(scheduleStub)
) {
  return {
    userProgress$: new BehaviorSubject(progress).asObservable(),
    getCurrentInvestmentSchedule: jasmine.createSpy('getCurrentInvestmentSchedule')
      .and.returnValue(scheduleObservable)
  };
}

function makePortfolioStoreMock(equity = 50000) {
  const bundle: PortfolioBundle = {
    ...portfolioBundleStub,
    dashboard: { ...portfolioBundleStub.dashboard, summary: { ...portfolioBundleStub.dashboard.summary, equity } }
  };
  return {
    load$: jasmine.createSpy('load$').and.returnValue(of(bundle))
  };
}

function makeAlpacaServiceMock(kycObservable: Observable<any>) {
  return {
    getKycData: jasmine.createSpy('getKycData').and.returnValue(kycObservable)
  };
}

/** Configure TestBed with the given mocks. */
function configureTestBed(
  authMock = makeAuthServiceMock(),
  portfolioStoreMock = makePortfolioStoreMock(),
  kycObservable: Observable<any> = of(kycDataStub)
): void {
  TestBed.configureTestingModule({
    providers: [
      FreedomStatsService,
      { provide: AuthService, useValue: authMock },
      { provide: PortfolioStoreService, useValue: portfolioStoreMock },
      { provide: AlpacaService, useValue: makeAlpacaServiceMock(kycObservable) }
    ]
  });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Get the service instance, which triggers constructor (fetches start). */
function getService(): FreedomStatsService {
  return TestBed.inject(FreedomStatsService);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('FreedomStatsService — FRED-200', () => {

  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  // =========================================================================
  // AC-1: Math correctness
  // =========================================================================

  describe('AC-1: math correctness', () => {

    it('uses retirementIncome / 0.04 as the FI target', fakeAsync(() => {
      // retirementIncome = 60000 → target = 60000 / 0.04 = 1,500,000
      // equity = 50000, contribution = 500 → freedomYear will be a future year
      configureTestBed();
      const svc = getService();

      tick(0);

      // dollarsAway = max(0, 1_500_000 - 50_000) = $1.45M
      expect(svc.stats().dollarsAway).toBe('$1.45M');
    }));

    it('defaults to $1.5M target when retirementIncome is absent', fakeAsync(() => {
      const progressNoIncome = { ...userProgressStub, retirementIncome: undefined, monthlyInvestment: 500 };
      configureTestBed(makeAuthServiceMock(progressNoIncome as any));
      const svc = getService();

      tick(0);

      // target = 1_500_000; equity = 50_000 → dollarsAway = $1.45M
      expect(svc.stats().dollarsAway).toBe('$1.45M');
    }));

    it('computes freedomAge = freedomYear - birthYear', fakeAsync(() => {
      const birthYear = 1990;
      configureTestBed(
        makeAuthServiceMock(),
        makePortfolioStoreMock(),
        of({ identity: { date_of_birth: `${birthYear}-03-22` } })
      );
      const svc = getService();

      tick(0);

      const yearNum = parseInt(svc.stats().freedomYear, 10);
      const expectedAge = yearNum - birthYear;
      expect(svc.stats().freedomAge).toBe(String(expectedAge));
      expect(parseInt(svc.stats().freedomAge, 10)).toBeGreaterThan(0);
    }));

    it('dollarsAway is max(0, target - equity) — never negative', fakeAsync(() => {
      // equity > target → gap = 0
      configureTestBed(
        makeAuthServiceMock(),
        makePortfolioStoreMock(2_000_000) // already at FI
      );
      const svc = getService();

      tick(0);

      expect(svc.stats().dollarsAway).toBe('$0');
    }));

    it('freedomYear matches the same formula as original tab3 code', fakeAsync(() => {
      // Manually compute the expected year:
      // target = 60000 / 0.04 = 1_500_000
      // equity = 50000, monthly = 500, rate = 0.10/12
      // PMT = 500, PV = 50000, FV = 1_500_000
      // months = log((FV + PMT/r) / (PV + PMT/r)) / log(1+r)
      const r = 0.10 / 12;
      const PMT = 500;
      const PV = 50000;
      const FV = 1_500_000;
      const numerator = Math.log((FV + PMT / r) / (PV + PMT / r));
      const denominator = Math.log(1 + r);
      const months = numerator / denominator;
      const years = Math.ceil(months / 12);
      const expectedYear = Math.min(new Date().getFullYear() + years, new Date().getFullYear() + 100);

      configureTestBed();
      const svc = getService();
      tick(0);

      expect(svc.stats().freedomYear).toBe(String(expectedYear));
    }));

  });

  // =========================================================================
  // AC-2: Signals are the reactive primitive
  // =========================================================================

  describe('AC-2: signals API', () => {

    it('exposes equity as a writable signal', fakeAsync(() => {
      configureTestBed();
      const svc = getService();
      tick(0);

      // After fetches resolve, equity should be a number.
      expect(typeof svc.equity()).toBe('number');
    }));

    it('exposes isLoading signal that starts true and becomes false once fetches settle', fakeAsync(() => {
      configureTestBed();
      const svc = getService();

      // Immediately after construction, loading should be true (fetches not settled yet).
      // With synchronous of() mocks they settle in the same tick.
      tick(0);

      expect(svc.isLoading()).toBeFalse();
    }));

    it('stats() returns all three values as strings', fakeAsync(() => {
      configureTestBed();
      const svc = getService();
      tick(0);

      const s = svc.stats();
      expect(typeof s.freedomYear).toBe('string');
      expect(typeof s.freedomAge).toBe('string');
      expect(typeof s.dollarsAway).toBe('string');
    }));

  });

  // =========================================================================
  // AC-5: FRED-196 KYC resilience preserved
  // =========================================================================

  describe('AC-5: KYC retry resilience', () => {

    it('retries a transient 503 twice and uses the success value on the third attempt', fakeAsync(() => {
      let callCount = 0;
      const kycObservable = new Observable((observer) => {
        callCount++;
        if (callCount <= 2) {
          observer.error({ status: 503, name: 'HttpErrorResponse' });
        } else {
          observer.next({ identity: { date_of_birth: '1990-05-15' } });
          observer.complete();
        }
      });

      configureTestBed(makeAuthServiceMock(), makePortfolioStoreMock(), kycObservable);
      const svc = getService();

      tick(0);   // portfolio resolves immediately
      tick(1200); // advance past retry delays (400ms + 800ms)

      const age = parseInt(svc.stats().freedomAge, 10);
      expect(age).toBeGreaterThan(0);
      expect(svc.isLoading()).toBeFalse();

      tick(10000); // drain timeout guard
    }));

    it('does NOT retry a 401 — fails fast', fakeAsync(() => {
      let callCount = 0;
      const kycObservable = new Observable((observer) => {
        callCount++;
        observer.error({ status: 401, name: 'HttpErrorResponse' });
      });

      configureTestBed(makeAuthServiceMock(), makePortfolioStoreMock(), kycObservable);
      const svc = getService();

      tick(0);

      expect(callCount).toBe(1);
      expect(svc.stats().freedomAge).toBe('—');
      expect(svc.isLoading()).toBeFalse();

      tick(10000);
    }));

    it('permanently-failing KYC (503 exhausts retries) → freedomAge "—" and isLoading false', fakeAsync(() => {
      const kycObservable = new Observable((observer) => {
        observer.error({ status: 503, name: 'HttpErrorResponse' });
      });

      configureTestBed(makeAuthServiceMock(), makePortfolioStoreMock(), kycObservable);
      const svc = getService();

      tick(0);
      tick(1200); // retry delays

      expect(svc.stats().freedomAge).toBe('—');
      expect(svc.isLoading()).toBeFalse();

      tick(10000);
    }));

    it('200 KYC with missing date_of_birth → freedomAge stays —', fakeAsync(() => {
      configureTestBed(
        makeAuthServiceMock(),
        makePortfolioStoreMock(),
        of({ identity: {} })
      );
      const svc = getService();

      tick(0);

      expect(svc.stats().freedomAge).toBe('—');
      expect(svc.isLoading()).toBeFalse();

      tick(10000);
    }));

    it('freedomYear and dollarsAway are populated even when KYC permanently fails', fakeAsync(() => {
      const kycObservable = new Observable((observer) => {
        observer.error({ status: 503 });
      });

      configureTestBed(makeAuthServiceMock(), makePortfolioStoreMock(), kycObservable);
      const svc = getService();

      tick(0);
      tick(1200);

      expect(svc.stats().freedomYear).not.toBe('—');
      expect(parseInt(svc.stats().freedomYear, 10)).toBeGreaterThan(2026);
      expect(svc.stats().dollarsAway).not.toBe('—');
      expect(svc.stats().dollarsAway).toMatch(/^\$/);

      tick(10000);
    }));

  });

  // =========================================================================
  // AC-6: tab3-vs-MFU split — service does NOT read MFU fields
  // =========================================================================

  describe('AC-6: tab3 computes live — never reads persisted MFU output', () => {

    it('does not read currentFreedomEstimate (MFU value) when computing freedom year', fakeAsync(() => {
      // Progress with currentFreedomEstimate set to a specific year (2030)
      // The service should NOT use this value.
      const progressWithMfuEstimate = {
        ...userProgressStub,
        currentFreedomEstimate: 2030  // persisted MFU output — tab3 must compute live, never read this
      };
      configureTestBed(makeAuthServiceMock(progressWithMfuEstimate as any));
      const svc = getService();

      tick(0);

      // The service computes its own income-based year, not 2030 (the MFU value).
      const year = parseInt(svc.stats().freedomYear, 10);
      expect(year).not.toBe(2030);
      expect(year).toBeGreaterThan(2026);

      tick(10000);
    }));

  });

  // =========================================================================
  // AC-7: Unhappy paths
  // =========================================================================

  describe('AC-7: unhappy paths', () => {

    it('null/zero equity → dollarsAway uses max(0, target - 0) — full target amount', fakeAsync(() => {
      configureTestBed(makeAuthServiceMock(), makePortfolioStoreMock(0));
      const svc = getService();
      tick(0);

      // With equity = 0, contribution = 500 → hasLiveInputs = true
      // retirementIncome = 60000 → target = 1_500_000
      // dollarsAway = max(0, 1_500_000 - 0) = $1.50M
      // _formatCompactCurrency: 1.5 % 1 !== 0 → toFixed(2) → '$1.50M'
      expect(svc.stats().dollarsAway).toBe('$1.50M');

      tick(10000);
    }));

    it('failed portfolio fetch degrades gracefully — no crash, equity = 0', fakeAsync(() => {
      const failingStoreMock = {
        load$: jasmine.createSpy('load$').and.returnValue(
          throwError(() => ({ status: 500 }))
        )
      };

      configureTestBed(makeAuthServiceMock(), failingStoreMock);
      const svc = getService();
      tick(0);

      expect(svc.equity()).toBe(0);
      expect(svc.isLoading()).toBeFalse();
      // Stats should still render (using equity = 0, contribution = 500)
      expect(svc.stats().freedomYear).not.toBe('—');

      tick(10000);
    }));

    it('stats shows — when no live inputs at all', fakeAsync(() => {
      const progressNoInputs = {
        ...userProgressStub,
        monthlyInvestment: 0,
        retirementIncome: 0
      };
      // "No live inputs AT ALL" must also mean no schedule — otherwise the
      // default $500 schedule stub would supply a live contribution.
      configureTestBed(
        makeAuthServiceMock(progressNoInputs as any, of(null)),
        makePortfolioStoreMock(0)
      );
      const svc = getService();
      tick(0);

      // No equity, no contribution → no live inputs → freedomYear = '—'
      expect(svc.stats().freedomYear).toBe('—');
      expect(svc.stats().dollarsAway).toBe('—');

      tick(10000);
    }));

  });

  // =========================================================================
  // AC-8: localStorage snapshot
  // =========================================================================

  describe('AC-8: localStorage snapshot', () => {

    it('persists a snapshot to localStorage after stats compute', fakeAsync(() => {
      localStorage.setItem('userId', '42');

      configureTestBed();
      const svc = getService();
      tick(0);

      // Reading stats() triggers the computed, which calls _persistSnapshot.
      const s = svc.stats();
      expect(s.freedomYear).not.toBe('—'); // sanity check — real data arrived

      const raw = localStorage.getItem('fred.statStrip.v1.42');
      expect(raw).not.toBeNull();

      const snapshot = JSON.parse(raw!);
      expect(snapshot.userId).toBe('42');
      expect(snapshot.freedomYear).toBeTruthy();
      expect(snapshot.dollarsAway).toBeTruthy();
    }));

    it('snapshot is keyed to userId — different userId sees no snapshot', fakeAsync(() => {
      // Put a snapshot for user 99
      localStorage.setItem('fred.statStrip.v1.99', JSON.stringify({
        userId: '99',
        freedomYear: '2050',
        freedomAge: '55',
        dollarsAway: '$800K'
      }));

      // Now launch as user 42 — should not see user 99's snapshot
      localStorage.setItem('userId', '42');

      configureTestBed();
      const svc = getService();

      // The snapshot for user 99 should NOT appear for user 42.
      // hydrateFromSnapshot looks for 'fred.statStrip.v1.42' which doesn't exist.
      // With synchronous of() mocks, fetches complete in constructor so isLoading = false,
      // but stats must not show user 99's values.
      tick(0);
      const s = svc.stats();

      // The computed year is calculated live from the user's actual progress,
      // NOT from user 99's cached '2050'.
      expect(s.freedomYear).not.toBe('2050');
      expect(s.dollarsAway).not.toBe('$800K');

      tick(10000);
    }));

    it('corrupt snapshot falls back to loading state without crashing', fakeAsync(() => {
      localStorage.setItem('userId', '42');
      localStorage.setItem('fred.statStrip.v1.42', 'not-valid-json{{{');

      configureTestBed();
      const svc = getService();

      // Should not throw; should start in loading state.
      expect(() => svc.isLoading()).not.toThrow();

      tick(0);
      tick(10000);
    }));

    it('does not persist a snapshot when all values are dashes', fakeAsync(() => {
      localStorage.setItem('userId', '42');

      // Progress with no inputs and equity = 0 → freedomYear and dollarsAway = '—'
      const progressNoInputs = {
        ...userProgressStub,
        monthlyInvestment: 0,
        retirementIncome: 0
      };
      // "No live inputs AT ALL" must also mean no schedule — otherwise the
      // default $500 schedule stub would supply a live contribution.
      configureTestBed(
        makeAuthServiceMock(progressNoInputs as any, of(null)),
        makePortfolioStoreMock(0)
      );
      const svc = getService();
      tick(0);

      // Read stats to trigger the computed (and any _persistSnapshot call).
      const s = svc.stats();
      expect(s.freedomYear).toBe('—');
      expect(s.dollarsAway).toBe('—');

      // Snapshot should NOT be written when all values are '—'.
      const raw = localStorage.getItem('fred.statStrip.v1.42');
      expect(raw).toBeNull();

      tick(10000);
    }));

  });

  // =========================================================================
  // refresh() — silent background refresh
  // =========================================================================

  describe('refresh()', () => {

    it('calls portfolio store and KYC again without resetting equity to null', fakeAsync(() => {
      const portfolioStoreMock = makePortfolioStoreMock(50000);
      const kycMock = makeAlpacaServiceMock(of(kycDataStub));

      TestBed.configureTestingModule({
        providers: [
          FreedomStatsService,
          { provide: AuthService, useValue: makeAuthServiceMock() },
          { provide: PortfolioStoreService, useValue: portfolioStoreMock },
          { provide: AlpacaService, useValue: kycMock }
        ]
      });

      const svc = TestBed.inject(FreedomStatsService);
      tick(0); // initial fetches

      svc.refresh();

      // equity should not be reset to null during refresh
      expect(svc.equity()).not.toBeNull();

      // Store's load$() should have been called twice (initial + refresh)
      expect(portfolioStoreMock.load$).toHaveBeenCalledTimes(2);

      tick(10000);
    }));

  });

  // =========================================================================
  // Unified contribution source (freedom date unification, 2026-07-07 spec)
  // =========================================================================

  describe('unified contribution source', () => {

    it('prefers the saved Investing goal over the actual schedule (2026-08-01 flip)', fakeAsync(() => {
      // weekly $100 → ×4.33 = $433/mo schedule, but the saved goal is $500/mo.
      configureTestBed(makeAuthServiceMock(userProgressStub,
        of({ investmentAmount: 100, frequency: 'WEEKLY', isPaused: false })));
      const svc = getService();
      tick(0);

      expect(svc.scheduleMonthly()).toBe(433);
      expect(svc.isLoading()).toBeFalse();
      // Discriminates the contribution SOURCE: goal $500/mo · equity $50k ·
      // target $1.5M → 319.56 months → 27 years. If the projection regressed
      // to the schedule's $433/mo it would read 28 years — fails on regression.
      expect(svc.stats().freedomYear).toBe(String(new Date().getFullYear() + 27));
    }));

    it('uses the saved goal when no schedule exists', fakeAsync(() => {
      configureTestBed(makeAuthServiceMock(userProgressStub, of(null)));
      const svc = getService();
      tick(0);

      expect(svc.scheduleMonthly()).toBe(0);
      // goal $500/mo · equity $50k · target $1.5M → 319.56 months → 27 years
      const expectedYear = String(new Date().getFullYear() + 27);
      expect(svc.stats().freedomYear).toBe(expectedYear);
    }));

    it('falls back to the schedule monthly-equivalent when no goal is saved', fakeAsync(() => {
      const progressNoGoal = { ...userProgressStub, monthlyInvestment: undefined } as any;
      configureTestBed(makeAuthServiceMock(progressNoGoal,
        of({ investmentAmount: 100, frequency: 'WEEKLY', isPaused: false })));
      const svc = getService();
      tick(0);

      // schedule $433/mo · equity $50k · target $1.5M → 328.08 months → 28 years
      expect(svc.stats().freedomYear).toBe(String(new Date().getFullYear() + 28));
    }));

    it('falls back to the survey value and resolves the gate when the schedule fetch errors', fakeAsync(() => {
      configureTestBed(makeAuthServiceMock(userProgressStub,
        throwError(() => new Error('schedule down'))));
      const svc = getService();
      tick(0);

      expect(svc.scheduleMonthly()).toBe(0);
      expect(svc.isLoading()).toBeFalse();
      expect(svc.stats().freedomYear).not.toBe('—');
    }));

    it('cross-stack parity fixture: $10k equity, $500/mo schedule, $60k income → currentYear + 32', fakeAsync(() => {
      // PARITY: mirrored in MonthlyFreedomUpdateServiceTest.parityFixtureFreedomYear
      // (backend). If you change one, change both.
      configureTestBed(
        makeAuthServiceMock(userProgressStub, of({ investmentAmount: 500, frequency: 'MONTHLY', isPaused: false })),
        makePortfolioStoreMock(10000)
      );
      const svc = getService();
      tick(0);

      expect(svc.stats().freedomYear).toBe(String(new Date().getFullYear() + 32));
    }));
  });

});
