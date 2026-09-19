import { Component, OnInit, OnDestroy, ViewChild, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PortfolioService, PortfolioDashboardData, Position, PerformanceData, PortfolioHistory } from '../../services/portfolio.service';
import { PortfolioStoreService, PortfolioBundle, PortfolioSnapshot } from '../../services/portfolio-store.service';
import { LoadingController, ModalController } from '@ionic/angular/standalone';
import { ToastService } from '../../services/toast.service';
import { AuthService, UserProgress } from '../../services/auth.service';
import { MfuPresenterService } from '../../services/mfu-presenter.service';
import { Router } from '@angular/router';
import { firstValueFrom, Subject, takeUntil } from 'rxjs';
import { TabBarScrollDirective } from '../../directives/tab-bar-scroll.directive';
import { PortfolioChartComponent, PortfolioDataPoint } from '../portfolio-chart/portfolio-chart.component';
import { PositionDetailSheetComponent } from '../position-detail-sheet/position-detail-sheet.component';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import {
  IonHeader, IonToolbar, IonTitle, IonButton, IonIcon, IonContent,
  IonRefresher, IonRefresherContent,
  IonSpinner
} from "@ionic/angular/standalone";

@Component({
  selector: 'app-portfolio-dashboard',
  templateUrl: './portfolio-dashboard.component.html',
  styleUrls: ['./portfolio-dashboard.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    PortfolioChartComponent,
    IonHeader, IonToolbar, IonTitle, IonButton, IonIcon, IonContent,
    IonRefresher, IonRefresherContent,
    IonSpinner,
    TabBarScrollDirective
  ]
})
export class PortfolioDashboardComponent implements OnInit, OnDestroy {

  dashboard: PortfolioDashboardData | null = null;
  performanceData: PerformanceData[] = [];
  loading = true;
  isRefreshing = false;
  error: string | null = null;
  selectedTab = 'overview';
  selectedPeriod = 'ALL';
  chartData: PortfolioDataPoint[] = [];
  isFlipped = false;
  backgroundIcons: string[] = [];
  freedomLabel: string = '';

  // Phase 2 (FRED-206) snapshot state
  snapshotAsOf: number | null = null;   // set while last-known snapshot is on screen; null once fresh is applied
  dataIsFresh = false;                  // true once real (revalidated) data is painted — the cover gate waits for THIS
  revalidateFailed = false;             // true if a background revalidate failed while the snapshot shows (offline)
  private firstApply = true;            // only the first real apply drives the MFU check; silent revalidates don't

  // Time period options for chart
  periodOptions = [
    { value: '1M', label: '1M' },
    { value: '3M', label: '3M' },
    { value: '6M', label: '6M' },
    { value: '1Y', label: '1Y' },
    { value: 'ALL', label: 'All' }
  ];

  // ----------- FRED-209 presentation state (no data-pipeline changes) -----------
  scrubState: { value: number; date: string } | null = null;
  analyticsExpanded = false;
  analyticsActive = false;
  private openingSheet = false;
  // -------------------------------------------------------------------------------

  constructor(
    private portfolioService: PortfolioService,
    private portfolioStore: PortfolioStoreService,
    private loadingController: LoadingController,
    private toastService: ToastService,
    private authService: AuthService,
    private mfuPresenter: MfuPresenterService,
    private modalController: ModalController,
    private router: Router,
    private cdr: ChangeDetectorRef
  ) { }

  private destroy$ = new Subject<void>();

  /**
   * Latest userProgress emission — kept current by the subscription in ngOnInit so the
   * freedom label recomputes the instant a my-profile save re-emits userProgress$
   * (saveChanges → loadUserProgress → userProgress$), with no extra HTTP.
   */
  private latestProgress: UserProgress | null = null;

  ngOnInit() {
    // Reactive freedom label: recompute whenever progress changes (e.g. the user saves a
    // new retirement income on my-profile) — not just when a portfolio bundle loads.
    this.authService.userProgress$
      .pipe(takeUntil(this.destroy$))
      .subscribe((progress) => {
        this.latestProgress = progress;
        this.updateFreedomLabel();
      });

    // Phase 2: paint the last-known snapshot instantly if we have one. On a SLOW network the cover
    // cap fires and reveals these last-known numbers (+ an "Updating…" marker) instead of the
    // spinner. On a FAST network the cover keeps holding for FRESH data (see dataIsFresh /
    // waitForTab1DataPainted), so the snapshot stays hidden behind the cover and the user only ever
    // sees their current numbers. loadPortfolioData() then revalidates either way.
    const snap = this.portfolioStore.peekSnapshot();
    if (snap) {
      this.applySnapshot(snap);
    }
    this.loadPortfolioData();
  }

  /** Hydrate the view from a persisted snapshot for instant paint (held behind the cover on fast networks). */
  private applySnapshot(snap: PortfolioSnapshot): void {
    this.dashboard = snap.dashboard;
    this.performanceData = Array.isArray(snap.performance) ? snap.performance : [];
    this.selectedPeriod = snap.period || 'ALL';
    this.chartData = snap.history ? this.processChartData(snap.history) : [];
    this.freedomLabel = snap.freedomLabel || '';
    this.snapshotAsOf = snap.savedAt;
    this.dataIsFresh = false;
    this.generateBackgroundIcons();
    this.loading = false;
  }

  async loadPortfolioData(isRefresh = false) {
    if (isRefresh) {
      this.isRefreshing = true;
    } else if (!this.dashboard) {
      // Only show the full-screen spinner when there is nothing on screen yet. If a snapshot (or
      // prior data) is already showing, revalidate SILENTLY so we never flash a spinner over it.
      this.loading = true;
    }
    this.error = null;

    try {
      const bundle = await firstValueFrom(this.portfolioStore.load$(this.selectedPeriod, isRefresh));
      this.applyBundle(bundle, isRefresh);
    } catch (error: any) {
      console.error('Error loading portfolio data:', error);
      if (!this.dashboard) {
        // Nothing on screen → surface the error card.
        this.error = error.error?.message || 'Failed to load portfolio data';
        this.toastService.showToast('Failed to load portfolio data', 'danger');
      } else if (this.snapshotAsOf) {
        // A snapshot is showing and the revalidate failed (e.g. offline) → keep the snapshot and
        // switch its marker from "Updating…" to "as of <time>". Never erase good data.
        this.revalidateFailed = true;
      }
    } finally {
      if (isRefresh) {
        this.isRefreshing = false;
      } else {
        this.loading = false;
      }
    }
  }

  private applyBundle(bundle: PortfolioBundle, isRefresh: boolean): void {
    this.dashboard = bundle.dashboard || null;
    if (this.dashboard) {
      this.generateBackgroundIcons();
    }

    this.performanceData = Array.isArray(bundle.performance) ? bundle.performance : [];

    // Sync performance data with dashboard summary for consistency
    if (this.dashboard) {
      // Sync 'Total' performance
      let totalPerf = this.performanceData.find(p => p.period === 'Total');
      if (!totalPerf) {
        totalPerf = { period: 'Total', startValue: 0, endValue: 0, totalReturn: 0, totalReturnPercent: 0 };
        this.performanceData.push(totalPerf);
      }
      // Use dashboard values as source of truth
      totalPerf.startValue = this.dashboard.totalInvested;
      totalPerf.endValue = this.dashboard.summary.portfolioValue;
      totalPerf.totalReturn = this.dashboard.totalGainLoss;
      totalPerf.totalReturnPercent = this.dashboard.totalGainLossPercent;

      // Sync 'Today' performance
      let todayPerf = this.performanceData.find(p => p.period === 'Today');
      if (!todayPerf) {
        todayPerf = { period: 'Today', startValue: 0, endValue: 0, totalReturn: 0, totalReturnPercent: 0 };
        this.performanceData.push(todayPerf);
      }

      if (this.dashboard) {
        todayPerf.endValue = this.dashboard.summary.portfolioValue;

        // Use the sum of individual positions' Day G/L for Today's Performance
        // This avoids issues where deposits/withdrawals are incorrectly counted as performance gains/losses
        // in the overall account equity calculation.
        let todayPL = 0;
        if (this.dashboard.positions) {
          todayPL = this.dashboard.positions.reduce((sum, pos) => sum + (pos.todayGainLoss || 0), 0);
        }

        todayPerf.totalReturn = todayPL;

        // Calculate Start Value derived from the Return
        // This creates a consistent "Apple to Apples" view of Portfolio Value Growth
        todayPerf.startValue = todayPerf.endValue - todayPerf.totalReturn;

        // Calculate Percent: Return / Start
        if (todayPerf.startValue !== 0) {
          todayPerf.totalReturnPercent = (todayPerf.totalReturn / todayPerf.startValue) * 100;
        } else {
          todayPerf.totalReturnPercent = 0;
        }
      }
    }

    // Apply history from bundle → chart (mirrors what loadHistoryForPeriod does)
    if (this.dashboard) {
      if (bundle.history) {
        this.dashboard.history = bundle.history;
        this.chartData = this.processChartData(bundle.history);
      } else {
        this.chartData = [];
      }
    }

    // Recalculate the financial freedom label (synchronous — runs before the
    // persistSnapshot below so the snapshot always captures the current label).
    this.updateFreedomLabel();

    // Check if the Monthly Freedom Update should be shown — only on the FIRST real apply, so a silent
    // revalidate after a snapshot hydrate doesn't re-pop the modal. The presenter primes the shared
    // single-flight session and presents the modal already-populated (no "Preparing your update…").
    if (this.firstApply && !isRefresh && this.dashboard && this.dashboard.summary.equity > 0) {
      this.mfuPresenter.presentAutoIfDue();
    }
    this.firstApply = false;

    // Fresh data is now painted: let the cover gate lift onto it (dataIsFresh), clear the snapshot
    // marker, and persist this view-model for the next cold launch.
    this.dataIsFresh = true;
    this.snapshotAsOf = null;
    this.revalidateFailed = false;
    if (this.dashboard) {
      this.portfolioStore.persistSnapshot({
        dashboard: this.dashboard,
        performance: this.performanceData,
        history: bundle.history,
        freedomLabel: this.freedomLabel,
        period: this.selectedPeriod,
      });
    }
  }

  async onRefresh(event: any) {
    await this.loadPortfolioData(true);
    event.target.complete();
  }

  toggleFlip() {
    this.isFlipped = !this.isFlipped;
    console.log('Card flipped:', this.isFlipped);
  }

  onPeriodChange(period: string | number | undefined): void {
    if (period) {
      this.selectedPeriod = period.toString();
      this.loadHistoryForPeriod(this.selectedPeriod);
    }
  }

  async loadHistoryForPeriod(period: string) {
    if (!this.dashboard) return;

    try {
      const history = await firstValueFrom(this.portfolioService.getPortfolioHistory(period));
      if (history) {
        this.dashboard.history = history;
        this.chartData = this.processChartData(history);
      }
    } catch (error: any) {
      console.error('Error loading portfolio history:', error);
      this.chartData = [];
    }
  }

  private processChartData(history: PortfolioHistory): PortfolioDataPoint[] {
    if (!history.timestamps || !history.values || history.timestamps.length !== history.values.length) {
      return [];
    }

    // Check if ALL values are 0 (completely empty portfolio)
    // Allow for some zeros at the beginning (before first investment)
    const hasAnyNonZeroValues = history.values.some(value => value > 0);
    if (!hasAnyNonZeroValues) {
      return []; // Return empty array only if ALL values are 0
    }

    const chartData = history.timestamps.map((timestamp: string, index: number) => ({
      date: timestamp,
      value: history.values[index]
    }));

    return chartData;
  }

  getReturnForCurrentPeriod(): { value: number, percent: number } | null {
    let targetPeriod = this.selectedPeriod;
    if (this.selectedPeriod === 'ALL') targetPeriod = 'Total';


    // We only use the pre-fetched performance data for "Today".
    // For "Total"/"ALL", the pre-fetched data only includes Unrealized Gains (Open Positions).
    // The user prefers the return to be consistent with other periods (Realized + Unrealized),
    // so we force "Total" to fall through to the History-based calculation below.
    if (targetPeriod === 'Today') {
      const perf = this.performanceData.find(p => p.period === targetPeriod);
      console.log('perf:', perf);
      if (perf) {
        return {
          value: perf.totalReturn,
          percent: perf.totalReturnPercent
        };
      }
    }

    // Use history data (Alpaca provided P/L) if available

    // Priority 1: Hybrid Calculation (History Start vs Real-Time End)
    // We use the history to find the "Start" state, and the current dashboard summary for the "End" state.
    // This ensures that "End" includes the very latest deposits, price changes, and P/L that might not be in the history API yet.
    if (this.dashboard?.history?.profitLoss && this.dashboard.history.profitLoss.length > 0 &&
      this.dashboard.history.values && this.dashboard.history.values.length > 0) {

      const history = this.dashboard.history;

      // FOR "ALL" OR "MAX": Just use the Dashboard Totals directly.
      // The user expects these to match the "Total G/L" in the table.
      if (this.selectedPeriod === 'ALL' || this.selectedPeriod === '1Y') { // 1Y is effectively ALL for new accounts
        // If account is older than 1Y, this logic stands: Total G/L / Total Invested
        if (this.dashboard.totalInvested > 0) {
          return {
            value: this.dashboard.totalGainLoss,
            percent: (this.dashboard.totalGainLoss / this.dashboard.totalInvested) * 100
          };
        }
      }

      const last = history.values.length - 1;

      // Find the first index with non-zero equity to avoid "Start=0" skewing the Dietz calculation
      let firstIndex = 0;
      while (firstIndex < last && history.values[firstIndex] === 0) {
        firstIndex++;
      }

      const profitLoss = history.profitLoss || [];
      const plStart = profitLoss[firstIndex] || 0;
      const eqStart = history.values[firstIndex] || 0;

      // Use REAL-TIME values for the "End" state
      // This fixes the "History is stale" issue where a recent deposit isn't in history yet

      // Actually, better approach:
      // Gain_Period = Total_PL_Now - Total_PL_At_Start
      // Basis_Now = Total_Invested_Now

      // Let's rely on the variables we have:
      // End State (Real Time):
      const eqEnd = this.dashboard.summary.portfolioValue; // $635
      const totalPLEnd = this.dashboard.totalGainLoss;     // $18 (from Positions/Summary)

      // Start State (History):
      // plStart is "Cumulative P/L at Start of Period".

      const gainPeriod = totalPLEnd - plStart;

      // Current Invested Capital (Real Time)
      const currentCostBasis = this.dashboard.totalInvested;

      // Safety for zero basis
      let basis = currentCostBasis;
      if (basis <= 0) {
        basis = 1;
      }

      const percent = (gainPeriod / basis) * 100;

      return {
        value: gainPeriod,
        percent: percent
      };
    }

    // Priority 2: Fallback (should be covered by Priority 1 usually)
    if (this.dashboard?.history?.profitLoss && this.dashboard.history.profitLoss.length > 0) {
      const history = this.dashboard.history;
      const profitLossArray = history.profitLoss; // Copy to local const for type safety
      const valuesArray = history.values;
      const lastIndex = history.values.length - 1;

      if (lastIndex >= 0 && profitLossArray && profitLossArray.length > lastIndex) {
        // Get P/L and Equity at start and end of the period
        // Index 0 is the start of the requested period (e.g., 1M ago)
        const plStart = profitLossArray[0] || 0;
        const plEnd = profitLossArray[lastIndex] || 0;

        const valStart = valuesArray[0] || 0;
        const valEnd = valuesArray[lastIndex] || 0;

        // Calculate performance for THIS period
        const plPeriod = plEnd - plStart;

        // Calculate Invested Capital (Net of P/L)
        // Invested = Equity - P/L
        const investedStart = valStart - plStart;
        const investedEnd = valEnd - plEnd;

        // Net New Cash introduced during the period
        const netNewCash = investedEnd - investedStart;

        // Modified Dietz Denominator: Start Capital + (Net New Cash / 2)
        // We assume cash flows happen roughly in the middle or evenly distributed
        const uniqueInvestedCapital = investedStart + (netNewCash / 2);

        if (uniqueInvestedCapital !== 0) {
          const percent = (plPeriod / uniqueInvestedCapital) * 100;
          return { value: plPeriod, percent };
        }
      }
    }

    // Fallback: Calculate from chartData (Only if absolutely no P/L data exists)
    // Warning: This includes deposits/withdrawals as "Performance"
    if (this.chartData && this.chartData.length > 0) {
      const startValue = this.chartData[0].value;
      const endValue = this.chartData[this.chartData.length - 1].value;

      if (startValue === 0) {
        return { value: endValue, percent: 0 };
      }

      const value = endValue - startValue;
      const percent = (value / startValue) * 100;

      return { value, percent };
    }

    return null;
  }

  getPeriodLabel(): string {
    const map: { [key: string]: string } = {
      '1M': '1-month',
      '3M': '3-month',
      '6M': '6-month',
      '1Y': '1-year',
      'ALL': 'total'
    };
    return map[this.selectedPeriod] || this.selectedPeriod;
  }

  formatCurrency(amount: number): string {
    // Handle edge case where value is very small negative (rounds to 0)
    // to avoid displaying "-$0.00"
    const roundedAmount = Math.round(amount * 100) / 100;
    const displayAmount = roundedAmount === 0 ? 0 : amount;

    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(displayAmount);
  }

  formatQuantity(value: number): string {
    if (value === undefined || value === null) return '0';
    return new Intl.NumberFormat('en-US', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 3
    }).format(value);
  }

  formatPercent(value: number): string {
    const sign = value >= 0 ? '+' : '';
    return `${sign}${value.toFixed(2)}%`;
  }

  formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString();
  }

  getGainLossColor(value: number): string {
    return value >= 0 ? 'success' : 'danger';
  }

  getGainLossIcon(value: number): string {
    return value >= 0 ? 'trending-up' : 'trending-down';
  }

  /**
   * Split equity into whole-dollar and cents parts for display.
   * Returns e.g. { whole: '$124,500', cents: '.00' }
   */
  getEquityParts(equity: number): { whole: string; cents: string } {
    const formatted = this.formatCurrency(equity); // e.g. "$124,500.00"
    const dotIndex = formatted.lastIndexOf('.');
    if (dotIndex === -1) {
      return { whole: formatted, cents: '.00' };
    }
    return {
      whole: formatted.substring(0, dotIndex),
      cents: formatted.substring(dotIndex)
    };
  }

  /**
   * Recompute the financial-freedom runway label from current equity + latest progress.
   *
   * Formula: totalDays = equity / (retirementIncome / 365)
   *
   * This shows the cumulative runway — how many days, months, or years the user's
   * current equity could sustain their desired retirement lifestyle (based on their
   * retirementIncome goal, treated as annual spending). Copy names the income basis
   * explicitly — "20 years of $10k/mo (est.)" — so it's clear the estimate is relative
   * to the user's desired retirement income.
   *
   * This is the lifetime total of the same metric used in the Monthly Freedom Update (MFU),
   * where "days bought back" represents how much closer the user moved toward their
   * desired retirement lifestyle during a specific period.
   *
   * Display thresholds:
   *   1–99 days  → "X days of $Yk/mo (est.)"
   *   100+ days  → "X months of $Yk/mo (est.)" (up to 23 months)
   *   24+ months → "X years of $Yk/mo (est.)"
   *
   * Synchronous on purpose: applyBundle() persists freedomLabel into the snapshot right
   * after calling this, and the userProgress$ subscription (ngOnInit) re-runs it the
   * moment a my-profile save re-emits progress — so the label updates immediately,
   * with no extra HTTP.
   */
  private updateFreedomLabel(): void {
    const next = this.computeFreedomLabel();
    if (next !== this.freedomLabel) {
      this.freedomLabel = next;
      this.cdr.detectChanges();
    }
  }

  private computeFreedomLabel(): string {
    // No dashboard yet (snapshot may be showing) → keep whatever label is visible;
    // clearing here would blink the snapshot label out until the bundle arrives.
    if (!this.dashboard) return this.freedomLabel;
    const equity = this.dashboard.summary?.equity ?? 0;
    // Progress not emitted yet → keep whatever is showing (snapshot label) rather than
    // computing against a default income the copy would then wrongly display.
    if (this.latestProgress === null) return this.freedomLabel;
    // A localStorage-fallback emission (transient /user/progress failure) carries no
    // retirementIncome post-onboarding — keep the current label rather than fabricating
    // a "$5k/mo" default in copy that now names the income. DB-sourced progress falls
    // through to the standard default.
    if (this.latestProgress._source === 'localStorageFallback' && !this.latestProgress.retirementIncome) {
      return this.freedomLabel;
    }

    const annualIncome = this.latestProgress.retirementIncome || 60000;
    const dailyExpenses = annualIncome / 365;
    if (equity <= 0 || dailyExpenses <= 0) return '';

    const perMonth = this.formatMonthlyIncome(annualIncome);
    const totalDays = Math.floor(equity / dailyExpenses);
    if (totalDays < 1) return '';
    if (totalDays <= 99) {
      return `worth ${totalDays} day${totalDays !== 1 ? 's' : ''} of ${perMonth} (est.)`;
    }
    const totalMonths = Math.floor(totalDays / 30);
    if (totalMonths <= 23) {
      return `worth ${totalMonths} month${totalMonths !== 1 ? 's' : ''} of ${perMonth} (est.)`;
    }
    const years = Math.floor(totalMonths / 12);
    return `worth ${years} year${years !== 1 ? 's' : ''} of ${perMonth} (est.)`;
  }

  /** "$10k/mo" for round thousands, "$5.5k/mo" otherwise, "$800/mo" under $1k. */
  private formatMonthlyIncome(annualIncome: number): string {
    const monthly = annualIncome / 12;
    if (monthly >= 1000) {
      const k = Math.round((monthly / 1000) * 10) / 10;
      return `$${Number.isInteger(k) ? k.toFixed(0) : k.toFixed(1)}k/mo`;
    }
    return `$${Math.round(monthly)}/mo`;
  }

  onTabChange(event: any) {
    this.selectedTab = event.detail.value;
    // Flip card based on tab selection
    // Overview (default) -> Front side (isFlipped = false)
    // Analytics -> Back side (isFlipped = true)
    this.isFlipped = this.selectedTab === 'analytics';
    // Trigger bar animation on analytics tab enter
    if (this.selectedTab === 'analytics') {
      this.analyticsActive = false;
      this.analyticsExpanded = false;
      this.cdr.detectChanges();
      setTimeout(() => {
        this.analyticsActive = true;
        this.cdr.detectChanges();
      }, 60);
    }
  }

  getPositionsTotalValue(): number {
    if (!this.dashboard?.positions) return 0;
    return this.dashboard.positions.reduce((sum, p) => sum + p.marketValue, 0);
  }

  getPositionsTotalCostBasis(): number {
    if (!this.dashboard?.positions) return 0;
    return this.dashboard.positions.reduce((sum, p) => sum + p.costBasis, 0);
  }

  getPositionsTotalGainLoss(): number {
    if (!this.dashboard?.positions) return 0;
    return this.dashboard.positions.reduce((sum, p) => sum + p.unrealizedPL, 0);
  }

  getPositionsTotalTodayGainLoss(): number {
    if (!this.dashboard?.positions) return 0;
    return this.dashboard.positions.reduce((sum, p) => sum + (p.todayGainLoss || 0), 0);
  }

  getPositionsTotalWeightedReturn(): number {
    const totalCost = this.getPositionsTotalCostBasis();
    if (totalCost === 0) return 0;

    // Weighted Return = (Total Gain / Total Cost) * 100
    // This is mathematically equivalent to the sum of weighted returns if weights are based on cost basis
    return (this.getPositionsTotalGainLoss() / totalCost) * 100;
  }

  getPositionsTotalWeightedTodayReturn(): number {
    // Current Market Value = Positions Total Value (Yesterday) + Total Today G/L
    // Yesterday Value = Current Market Value - Total Today G/L
    // Return % = (Total Today G/L / Yesterday Value) * 100
    // This gives the accurate portfolio-wide day performance
    const currentMarketValue = this.getPositionsTotalValue();
    const todayGL = this.getPositionsTotalTodayGainLoss();
    const yesterdayValue = currentMarketValue - todayGL;

    if (yesterdayValue === 0) return 0;
    return (todayGL / yesterdayValue) * 100;
  }

  generateBackgroundIcons() {
    if (!this.dashboard) return;

    // Choose icon based on total gain/loss
    const iconName = this.dashboard.totalGainLoss >= 0 ? 'trending-up' : 'trending-down';

    // Create an array of icons directly. We'll use CSS Grid/Flex to arrange them "neatly".
    // 80 icons should be plenty to fill the background without overdoing DOM elements.
    this.backgroundIcons = Array(80).fill(iconName);
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // =========================================================================
  // FRED-209 — presentation-only helpers (no data pipeline involvement)
  // =========================================================================

  /** Idle scrub readout: date range of the currently visible chart window. */
  getChartDateRange(): string {
    if (!this.chartData || this.chartData.length === 0) return '';
    const first = new Date(this.chartData[0].date + (this.chartData[0].date.includes('T') ? '' : 'T00:00:00Z'));
    const last = new Date(this.chartData[this.chartData.length - 1].date + (this.chartData[this.chartData.length - 1].date.includes('T') ? '' : 'T00:00:00Z'));
    const opts: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' };
    return first.toLocaleDateString('en-US', opts) + ' to ' + last.toLocaleDateString('en-US', opts);
  }

  /** Receive scrub state emitted by portfolio-chart. */
  onScrubChange(state: { value: number; date: string } | null): void {
    this.scrubState = state;
  }

  /** 'Today' period from performanceData — shown as the Daily row in analytics. */
  getDailyPerformance(): PerformanceData | null {
    return this.performanceData.find(p => p.period === 'Today') ?? null;
  }

  /** 'Total' period from performanceData — shown as the All Time row in analytics. */
  getAllTimePerformance(): PerformanceData | null {
    return this.performanceData.find(p => p.period === 'Total') ?? null;
  }

  /** Middle periods: everything that isn't 'Today' or 'Total'. */
  getMiddlePerformancePeriods(): PerformanceData[] {
    return this.performanceData.filter(p => p.period !== 'Total' && p.period !== 'Today');
  }

  /** Max |return| across all performance periods (for bar scaling). */
  getMaxAbsReturn(): number {
    if (!this.performanceData || this.performanceData.length === 0) return 1;
    return Math.max(...this.performanceData.map(p => Math.abs(p.totalReturnPercent)), 0.01);
  }

  /** Bar fill width as a percentage of the zone card, scaled to max |return|. */
  getBarWidth(perf: PerformanceData): number {
    const max = this.getMaxAbsReturn();
    return Math.abs(perf.totalReturnPercent) / max * 100;
  }

  /** First few letters of a ticker for the holdings tile. */
  getSymbolInitials(symbol: string): string {
    return symbol.substring(0, 4).toUpperCase();
  }

  /** Open the position detail bottom sheet (root-level ModalController). */
  async openPositionSheet(position: Position): Promise<void> {
    if (this.openingSheet) return;
    this.openingSheet = true;
    try {
      const modal = await this.modalController.create({
        component: PositionDetailSheetComponent,
        componentProps: { position },
        ...BOTTOM_SHEET_MODAL_OPTS,
        backdropDismiss: true
      });
      await modal.present();
      await modal.onDidDismiss();
    } finally {
      this.openingSheet = false;
    }
  }
}
