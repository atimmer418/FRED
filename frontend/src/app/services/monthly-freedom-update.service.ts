import { Injectable, Injector } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { ACTIVE_MOCK_TIER, MockTier } from './dev/portfolio-mock';
import { ACTIVE_MFU_MOCK, MfuMockConfig, MfuMockInputs, mockMfu, mockMfuCheck, overlayMilestone } from './dev/mfu-mock';
import { FreedomStatsService } from './freedom-stats.service';
import { RateAssumptionsService } from './rate-assumptions.service';

export interface MilestoneDTO {
  type: string;
  label: string;
  subtitle: string;
}

export interface MonthlyFreedomUpdateData {
  shouldShow: boolean;

  // Period
  periodStart: string;
  periodEnd: string;
  periodLabel: string;

  // Header badges
  statusPercentile: number;
  currentStreak: number;

  // Milestones
  milestones: MilestoneDTO[];

  // Freedom calculation
  startEquityValue: number;
  endEquityValue: number;
  periodProgressDelta: number;
  positive: boolean;

  // Analytics
  periodContributions: number;
  equityValueChange: number;
  returnRate: number;
  daysBoughtBack: number;

  // Projection
  projectedFreedomYear: number;
  currentEquityValue: number;
  recurringInvestmentAmount: number;
  investmentFrequency: string;

  // Quarterly review
  showQuarterlyCompare: boolean;
  equity12MonthsAgo: number;
  netWorthChange: number;
  freedomYearsChange: number;
  yearlyContributions: number;

  // Best next action
  bestNextMoveYearsEarlier: number;
  bestNextMoveYear: number;
  frequencyLabel: string;
  currentInvestmentAmount: number;
  bestNextMoveBoostAmount: number;

  // User age
  age: number;

  // Reopen flag
  reopen: boolean;

  // MFU count (number of unique non-reopen MFUs the user has seen)
  mfuCount: number;

  // Whether the user has any MFU history available to reopen
  hasMfuHistory: boolean;

  // Pig level (1-6) based on end equity
  equityLevel: number;

  // Server-produced month (yyyy-MM) this update was generated for; echoed back on commitSeen so the
  // "seen" stamp is the month actually shown, never a post-rollover month.
  generatedForMonth: string;
}

@Injectable({
  providedIn: 'root'
})
export class MonthlyFreedomUpdateService {
  private apiUrl = `${environment.backendApiUrl}/monthly-freedom-update`;

  // Projection fallbacks, mirroring MonthlyFreedomUpdateService (backend) / FreedomStatsService.
  private static readonly DEFAULT_TARGET_PORTFOLIO = 1_500_000;
  private static readonly DEFAULT_AGE = 35;

  constructor(private http: HttpClient, private injector: Injector) {}

  /**
   * DEV ONLY: the active MFU mock config, or null for real behavior. Gated on environment.local
   * (NOT just !production) exactly like PortfolioService.activeTier(): the deployed dev/test builds
   * also have production:false but must hit the real backend; only the local dev loop has
   * local:true. ACTIVE_MFU_MOCK is additionally forced null under karma (see dev/mfu-mock.ts).
   */
  private mockConfig(): MfuMockConfig | null {
    return environment.local ? ACTIVE_MFU_MOCK : null;
  }

  /** DEV ONLY: the portfolio tier the mocked update reflects, or null to use the real /generate payload. */
  private mockTier(): MockTier | null {
    return environment.local ? ACTIVE_MOCK_TIER : null;
  }

  /** True while the DEV mock drives this service; MfuStoreService skips snapshot I/O while it does. */
  isMockActive(): boolean {
    return this.mockConfig() !== null;
  }

  private getAuthHeaders(): HttpHeaders {
    const token = localStorage.getItem('jwtToken');
    return new HttpHeaders({
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    });
  }

  /**
   * Check if the Monthly Freedom Update should be shown
   */
  checkShouldShow(): Observable<MonthlyFreedomUpdateData> {
    if (this.mockConfig()) return of(mockMfuCheck(this.mockTier()));
    return this.http.get<MonthlyFreedomUpdateData>(`${this.apiUrl}/check`, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * Generate the full Monthly Freedom Update data
   */
  generateUpdate(reopen: boolean = false): Observable<MonthlyFreedomUpdateData> {
    const mock = this.mockConfig();
    if (mock) {
      const tier = this.mockTier();
      // A mocked portfolio tier drives the whole update; with none active the real payload is
      // shown and only its milestone card is overlaid.
      if (tier) return of(mockMfu(tier, this.liveInputs(), mock, reopen));
      return this.fetchUpdate(reopen).pipe(map((data) => overlayMilestone(data, mock.milestone)));
    }
    return this.fetchUpdate(reopen);
  }

  private fetchUpdate(reopen: boolean): Observable<MonthlyFreedomUpdateData> {
    return this.http.get<MonthlyFreedomUpdateData>(`${this.apiUrl}/generate`, {
      headers: this.getAuthHeaders(),
      params: { reopen: reopen.toString() }
    });
  }

  /**
   * Commit the update as "seen" for the shown month. Fired at data-ready (not on the dismiss button)
   * so the primary CTA / swipe paths can't skip it. seenMonth echoes the server-produced
   * generatedForMonth so the stamp is the month actually displayed.
   */
  commitSeen(seenMonth?: string): Observable<any> {
    // DEV mock: never stamp the real account, so the popup keeps firing while the toggle is on.
    if (this.mockConfig()) return of({ success: true });
    const body = seenMonth ? { seenMonth } : {};
    return this.http.post(`${this.apiUrl}/dismiss`, body, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * DEV ONLY: the projection inputs tab3's freedom strip uses (saved monthly goal else the schedule,
   * retirement income over the withdrawal rate else $1.5M, the user-adjustable return, KYC age),
   * so the mocked update's freedom year matches the live app. Resolved lazily through the Injector
   * so the real code path never instantiates FreedomStatsService (which starts its own fetches).
   */
  private liveInputs(): MfuMockInputs {
    const stats = this.injector.get(FreedomStatsService);
    const rates = this.injector.get(RateAssumptionsService);
    const goal = stats.monthlyTargetInvestment();
    const scheduleMonthly = stats.monthlyActualInvestment();
    const income = stats.retirementIncomeAnnual();
    const birthYear = stats.birthYear() ?? 0;
    return {
      monthlyContribution: goal > 0 ? goal : scheduleMonthly,
      scheduleMonthly,
      targetPortfolio: income != null && income > 0
        ? income / rates.withdrawalRate()
        : MonthlyFreedomUpdateService.DEFAULT_TARGET_PORTFOLIO,
      annualReturn: rates.annualReturn(),
      age: birthYear > 0 ? new Date().getFullYear() - birthYear : MonthlyFreedomUpdateService.DEFAULT_AGE,
    };
  }
}
