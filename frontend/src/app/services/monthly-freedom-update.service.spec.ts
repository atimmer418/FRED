/**
 * monthly-freedom-update.service.spec.ts
 *
 * Wiring contract for the DEV-only MFU mock (services/dev/mfu-mock.ts). With the mock off (always
 * the case under karma) every call is a plain HTTP request. With it on, /check and /dismiss never
 * leave the device, /generate is derived from the active portfolio tier (or the real payload is
 * overlaid when no tier is active), and the projection inputs come from the live freedom stats.
 */
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import { MonthlyFreedomUpdateService, MonthlyFreedomUpdateData } from './monthly-freedom-update.service';
import { FreedomStatsService } from './freedom-stats.service';
import { RateAssumptionsService } from './rate-assumptions.service';
import { mockDashboard } from './dev/portfolio-mock';
import { MfuMockConfig, mockMfu } from './dev/mfu-mock';
import { environment } from '../../environments/environment';

const API = `${environment.backendApiUrl}/monthly-freedom-update`;
const DERIVED: MfuMockConfig = { milestone: 'DERIVED', quarterly: false };

interface StatsStub {
  monthlyTargetInvestment: WritableSignal<number>;
  monthlyActualInvestment: WritableSignal<number>;
  retirementIncomeAnnual: WritableSignal<number | null>;
  birthYear: WritableSignal<number | null>;
}

describe('MonthlyFreedomUpdateService', () => {
  let service: MonthlyFreedomUpdateService;
  let http: HttpTestingController;
  let stats: StatsStub;

  beforeEach(() => {
    stats = {
      monthlyTargetInvestment: signal(500),
      monthlyActualInvestment: signal(400),
      retirementIncomeAnnual: signal<number | null>(60_000),
      birthYear: signal<number | null>(1990),
    };
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        MonthlyFreedomUpdateService,
        { provide: FreedomStatsService, useValue: stats },
        { provide: RateAssumptionsService, useValue: { annualReturn: signal(0.10), withdrawalRate: signal(0.04) } },
      ],
    });
    service = TestBed.inject(MonthlyFreedomUpdateService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('mock off (the karma default)', () => {
    it('reports the mock inactive', () => {
      expect(service.isMockActive()).toBeFalse();
    });

    it('checkShouldShow GETs /check', () => {
      service.checkShouldShow().subscribe();
      const req = http.expectOne(`${API}/check`);
      expect(req.request.method).toBe('GET');
      req.flush({ shouldShow: false });
    });

    it('generateUpdate GETs /generate with the reopen flag', () => {
      service.generateUpdate(true).subscribe();
      const req = http.expectOne((r) => r.url === `${API}/generate`);
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('reopen')).toBe('true');
      req.flush({});
    });

    it('commitSeen POSTs /dismiss with the shown month', () => {
      service.commitSeen('2026-08').subscribe();
      const req = http.expectOne(`${API}/dismiss`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ seenMonth: '2026-08' });
      req.flush({ success: true });
    });
  });

  describe('mock on, portfolio tier active', () => {
    beforeEach(() => {
      spyOn(service as any, 'mockConfig').and.returnValue(DERIVED);
      spyOn(service as any, 'mockTier').and.returnValue('MEDIUM');
    });

    it('reports the mock active', () => {
      expect(service.isMockActive()).toBeTrue();
    });

    it('checkShouldShow answers locally: show, no HTTP', () => {
      let out: MonthlyFreedomUpdateData | undefined;
      service.checkShouldShow().subscribe((d) => (out = d));
      expect(out!.shouldShow).toBeTrue();
      http.expectNone(`${API}/check`);
    });

    it('generateUpdate derives the payload from the tier, no HTTP', () => {
      let out: MonthlyFreedomUpdateData | undefined;
      service.generateUpdate(true).subscribe((d) => (out = d));
      expect(out!.currentEquityValue).toBe(mockDashboard('MEDIUM').summary.equity);
      expect(out!.reopen).toBeTrue();
      http.expectNone((r) => r.url === `${API}/generate`);
    });

    it('projects with the live inputs: saved goal, income-based target, KYC age', () => {
      let out: MonthlyFreedomUpdateData | undefined;
      service.generateUpdate(false).subscribe((d) => (out = d));
      const age = new Date().getFullYear() - 1990;
      const expected = mockMfu('MEDIUM', {
        monthlyContribution: 500, scheduleMonthly: 400, targetPortfolio: 60_000 / 0.04, annualReturn: 0.10, age,
      }, DERIVED, false);
      expect(out!.age).toBe(age);
      expect(out!.currentInvestmentAmount).toBe(400);
      expect(out!.projectedFreedomYear).toBe(expected.projectedFreedomYear);
      expect(out!.statusPercentile).toBe(expected.statusPercentile);
    });

    it('falls back to the schedule contribution, age 35 and the $1.5M target when the goal, KYC age and income are absent', () => {
      stats.monthlyTargetInvestment.set(0);
      stats.retirementIncomeAnnual.set(null);
      stats.birthYear.set(0);
      let out: MonthlyFreedomUpdateData | undefined;
      service.generateUpdate(false).subscribe((d) => (out = d));
      const expected = mockMfu('MEDIUM', {
        monthlyContribution: 400, scheduleMonthly: 400, targetPortfolio: 1_500_000, annualReturn: 0.10, age: 35,
      }, DERIVED, false);
      expect(out!.age).toBe(35);
      expect(out!.projectedFreedomYear).toBe(expected.projectedFreedomYear);
      expect(out!.bestNextMoveYear).toBe(expected.bestNextMoveYear);
      expect(out!.daysBoughtBack).toBe(expected.daysBoughtBack);
    });

    it('commitSeen resolves locally and never POSTs /dismiss', () => {
      let out: any;
      service.commitSeen('2026-09').subscribe((d) => (out = d));
      expect(out).toEqual({ success: true });
      http.expectNone(`${API}/dismiss`);
    });
  });

  describe('mock on, no portfolio tier (live backend payload)', () => {
    beforeEach(() => {
      spyOn(service as any, 'mockConfig').and.returnValue({ milestone: 'EQUITY', quarterly: false });
      spyOn(service as any, 'mockTier').and.returnValue(null);
    });

    it('checkShouldShow answers locally: show, no HTTP', () => {
      let out: MonthlyFreedomUpdateData | undefined;
      service.checkShouldShow().subscribe((d) => (out = d));
      expect(out!.shouldShow).toBeTrue();
      http.expectNone(`${API}/check`);
    });

    it('generateUpdate GETs the real /generate and overlays only the milestone card', () => {
      let out: MonthlyFreedomUpdateData | undefined;
      service.generateUpdate(false).subscribe((d) => (out = d));
      const req = http.expectOne((r) => r.url === `${API}/generate`);
      req.flush({
        endEquityValue: 2_400_000,
        periodLabel: 'Aug 1 to Aug 31',
        milestones: [{ type: 'DEFAULT', label: 'No major milestones reached', subtitle: 'x' }],
      });
      expect(out!.milestones[0].type).toBe('EQUITY_1M');
      expect(out!.milestones[0].label).toBe('$1M Total Equity Reached!');
      expect(out!.periodLabel).toBe('Aug 1 to Aug 31');
      expect(out!.endEquityValue).toBe(2_400_000);
    });
  });
});
