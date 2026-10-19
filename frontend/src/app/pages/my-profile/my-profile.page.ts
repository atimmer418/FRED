import { Component, OnInit, OnDestroy, Signal, computed, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  IonContent, IonHeader, IonIcon, IonAvatar, AlertController, ModalController, NavController
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { shareOutline, checkmarkCircleOutline, saveOutline, camera, walletOutline, timeOutline, ticketOutline } from 'ionicons/icons';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { PortfolioService } from '../../services/portfolio.service';
import { AccountStatusService } from '../../services/account-status.service';
import { MonthlyFreedomUpdateService } from '../../services/monthly-freedom-update.service';
import { Router } from '@angular/router';
import { Observable } from 'rxjs';
import { EquityPigUtils } from '../../utils/equity-pig.utils';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { GoalsService, Goal, GoalIcon, RebalanceSchedule } from '../../services/goals.service';
import { McInfoSheetComponent } from '../../components/mc-info-sheet/mc-info-sheet.component';
import { GoalAddSheetComponent } from '../../components/goal-add-sheet/goal-add-sheet.component';
import { BOTTOM_SHEET_MODAL_OPTS } from '../../utils/bottom-sheet.utils';
import { RateAssumptionsService, DEFAULT_ANNUAL_RETURN, DEFAULT_WITHDRAWAL_RATE } from '../../services/rate-assumptions.service';
import { monthsToTarget } from '../../utils/freedom-projection.utils';
import { FreedomDetailSheetComponent, FreedomSheetResult } from '../../components/freedom-detail-sheet/freedom-detail-sheet.component';
import { FirstTimeTourService } from '../../services/first-time-tour.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { ErrorCategory, GatedFeature, GoalAction, Tier } from '../../services/analytics/analytics-events';
import { toAmountBand, toCountBand, toPercentBand, toYearsBand } from '../../services/analytics/analytics-bands';
import { httpStatusOf, isNetworkFailure } from '../../services/analytics/analytics-error.utils';

/** Computed projection for a single user-added goal. */
export interface GoalProjection {
  goal: Goal;
  onTrack: boolean;
  projectedLabel: string;
  progressPct: number;
  /** Per-paycheck saving converted with the user's pay frequency. */
  monthlyEquivalent: number;
}

/** Rebalance schedule options shown as chips. */
const REBALANCE_SCHEDULES: RebalanceSchedule[] = ['Quarterly', 'Semi-annual', 'Annual'];

/**
 * Next-rebalance date by schedule.
 * Heuristic: next calendar quarter/half/year boundary from today.
 */
function nextRebalanceDate(schedule: RebalanceSchedule): string {
  const today = new Date();
  const y = today.getFullYear();
  const m = today.getMonth(); // 0-indexed

  let candidate: Date;
  switch (schedule) {
    case 'Quarterly':
      // Next of: Apr 1, Jul 1, Oct 1, Jan 1
      if (m < 3)       candidate = new Date(y, 3, 1);
      else if (m < 6)  candidate = new Date(y, 6, 1);
      else if (m < 9)  candidate = new Date(y, 9, 1);
      else             candidate = new Date(y + 1, 0, 1);
      break;
    case 'Semi-annual':
      candidate = m < 6 ? new Date(y, 6, 1) : new Date(y + 1, 0, 1);
      break;
    case 'Annual':
      candidate = new Date(y + 1, 0, 1);
      break;
  }
  return candidate!.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

/**
 * TLH heuristic estimate for non-Pro users.
 * Formula (documented in implementation-notes-FRED-216.md):
 *   estimate = round(portfolioValue × 0.003 / 10) × 10
 *   i.e. ~0.30 %/yr of invested value, rounded to the nearest $10.
 * Label is always "~$X/yr" — clearly marked as an estimate.
 */
function tlhEstimate(portfolioValue: number): number {
  if (portfolioValue <= 0) return 0;
  return Math.round(portfolioValue * 0.003 / 10) * 10;
}

@Component({
  selector: 'app-my-profile',
  templateUrl: './my-profile.page.html',
  styleUrls: ['./my-profile.page.scss'],
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonContent, IonHeader, IonIcon, IonAvatar,
    KeyboardAvoidDirective
  ]
})
export class MyProfilePage implements OnInit, OnDestroy {

  // ── Lifecycle ──────────────────────────────────────────────────────────────
  private readonly destroy$ = new Subject<void>();

  // ── Action-required banner ─────────────────────────────────────────────────
  actionRequired$: Observable<boolean>;

  // ── User info ──────────────────────────────────────────────────────────────
  userName: string = 'Investor';
  userEmail: string = '';

  // ── Freedom Plan state (signals) ──────────────────────────────────────────
  // Signals, not plain fields, deliberately: signal writes schedule change
  // detection through the hybrid scheduler even when an event listener runs
  // outside the Angular zone (WKWebView keystrokes were intermittently not
  // flushing plain-field updates on device — 2026-08-03).
  // 0 = not yet seeded (empty tiles). Never default to fabricated amounts:
  // the tiles render before the first /user/progress emission, and Save's
  // truthy guard must be able to tell "unseeded" from a real value.
  readonly monthlyInvestGoal = signal(0);
  readonly retirementIncomeGoal = signal(0); // Monthly display; stored annually
  readonly currentPortfolioValue = signal(0);

  // ── Derived plan values ────────────────────────────────────────────────────
  currentScheduledInvestment: number = 0;
  investmentFrequencyText: string = '';
  currentMonthlyEquivalent: number = 0;

  readonly portfolioGoal: Signal<number> = computed(() => {
    const monthly = this.retirementIncomeGoal();
    const exact = this.exactAnnualIncome();
    // Preserve the exact stored annual income while the rounded monthly
    // display value is untouched (avoids drift from /12 → ×12 round-trips).
    const annual = (exact !== null && Math.round(exact / 12) === monthly)
      ? exact
      : monthly * 12;
    return annual / this.SAFE_WITHDRAWAL_RATE;
  });

  readonly progressPercentage: Signal<number> = computed(() => {
    const goal = this.portfolioGoal();
    return goal > 0 ? Math.min(this.currentPortfolioValue() / goal, 1.0) : 0;
  });

  // ── Referral ───────────────────────────────────────────────────────────────
  referralCode: string = '';
  redeemCodeInput: string = '';
  referralCount: number = 0;
  hasAppliedReferral: boolean = false;
  isLoadingReferral: boolean = false;
  isCodeValid: boolean = false;
  referralThreshold: number = 3;
  referralRewardTriggered: boolean = false;

  get referralSegments(): number[] {
    return Array.from({ length: this.referralThreshold }, (_, i) => i);
  }

  // ── Percentile / pig level ─────────────────────────────────────────────────
  statusPercentile: number | null = null;
  equityLevel: number = 0;

  get avatarSrc(): string {
    return EquityPigUtils.pigSrcFromEquity(this.currentPortfolioValue());
  }

  // ── Tier ──────────────────────────────────────────────────────────────────
  selectedTier: string = '';

  get isPiggy(): boolean { return this.selectedTier === 'piggy' || this.selectedTier === 'core' || this.selectedTier === ''; }
  get isPlus(): boolean  { return this.selectedTier === 'plus'; }
  get isPro(): boolean   { return this.selectedTier === 'pro'; }
  get isPlusOrPro(): boolean { return this.isPlus || this.isPro; }

  // ── State ──────────────────────────────────────────────────────────────────
  isSubExpired: boolean = false;
  private readonly originalValues = signal<{ monthly: number; income: number }>({ monthly: 0, income: 0 });
  private readonly exactAnnualIncome = signal<number | null>(null);
  /** True once the tiles have been seeded from a real DB progress emission. */
  private hasSeededPlan = false;
  currentFrequency: string = '';

  readonly isDirty: Signal<boolean> = computed(() => {
    const original = this.originalValues();
    return this.monthlyInvestGoal() !== original.monthly ||
           this.retirementIncomeGoal() !== original.income;
  });

  // ── Projection rates (user-adjustable, frontend-wide) ─────────────────────
  // Guarded inject: the freedom-plan math specs construct this page directly
  // (new MyProfilePage(...)) outside an injection context — they fall back to
  // the standard defaults. Getters keep the original constant names so every
  // call site (and its signal reactivity) is unchanged.
  private ratesService: RateAssumptionsService | null = (() => {
    try { return inject(RateAssumptionsService); } catch { return null; }
  })();

  private get SAFE_WITHDRAWAL_RATE(): number {
    return this.ratesService?.withdrawalRate() ?? DEFAULT_WITHDRAWAL_RATE;
  }
  private get AVG_MARKET_YIELD(): number {
    return this.ratesService?.annualReturn() ?? DEFAULT_ANNUAL_RETURN;
  }

  // Guarded for the same reason as ratesService: the math specs construct this
  // page with `new MyProfilePage(...)`, outside any injection context.
  private tourService: FirstTimeTourService | null = (() => {
    try { return inject(FirstTimeTourService); } catch { return null; }
  })();

  // ── Rebalancing ────────────────────────────────────────────────────────────
  readonly rebalanceSchedules = REBALANCE_SCHEDULES;
  selectedSchedule: RebalanceSchedule = 'Quarterly';

  get nextRebalanceDate(): string {
    return nextRebalanceDate(this.selectedSchedule);
  }

  // ── Goals ─────────────────────────────────────────────────────────────────
  goals: Goal[] = [];

  // Memoized projections. The getter runs on EVERY change-detection cycle;
  // returning fresh objects each time made the trackBy-less *ngFor destroy and
  // recreate every goal row per CD tick. On WKWebView that DOM churn runs
  // mid-tap (each touch triggers CD) and iOS cancels the synthesized click —
  // the entire page reads as frozen the moment one saved goal renders
  // (2026-08-14 device freeze). Recompute only when inputs actually change.
  private _projCache: GoalProjection[] = [];
  private _projCacheGoals: Goal[] | null = null;
  private _projCacheFreq = '';

  get goalProjections(): GoalProjection[] {
    if (this._projCacheGoals !== this.goals || this._projCacheFreq !== this.currentFrequency) {
      this._projCache = this.goals.map(g => this._projectGoal(g));
      this._projCacheGoals = this.goals;
      this._projCacheFreq = this.currentFrequency;
    }
    return this._projCache;
  }

  /** trackBy: rows keyed by goal id — never recreated for identity-only changes. */
  trackGoal(_index: number, proj: GoalProjection): string {
    return proj.goal.id;
  }

  // ── TLH ───────────────────────────────────────────────────────────────────
  get tlhEstimateDisplay(): string {
    const est = tlhEstimate(this.currentPortfolioValue());
    return est > 0 ? `~$${est.toLocaleString()}/yr` : '~$0/yr';
  }

  // ── Freedom projection (live from the editable tiles) ──────────────────────
  // Computed from the two tile values + current equity so the card reacts as a
  // what-if calculator before any save. Same PV-inclusive formula and rounding
  // as FreedomStatsService._calculateMonthsToTarget — keep the two in lockstep.
  readonly freedomYear: Signal<number | null> = computed(() => {
    const goal = this.portfolioGoal();
    if (goal <= 0) return null;
    const pv = this.currentPortfolioValue();
    const pmt = this.monthlyInvestGoal() || 0;
    if (pv <= 0 && pmt <= 0) return null;
    const months = this._monthsToTarget(pv, pmt, goal);
    const currentYear = new Date().getFullYear();
    return Math.min(currentYear + Math.ceil(months / 12), currentYear + 100);
  });

  readonly freedomAge: Signal<string> = computed(() => {
    const year = this.freedomYear();
    const birthYear = this.freedomStats.birthYear();
    if (year === null || !birthYear || birthYear <= 0) return '—';
    const age = year - birthYear;
    return age > 0 ? String(age) : '—';
  });

  readonly yearsToFreedom: Signal<number | null> = computed(() => {
    const year = this.freedomYear();
    if (!year) return null;
    return year - new Date().getFullYear();
  });

  get freedomGoalSub(): string {
    const goalValue = this.portfolioGoal();
    const goal = goalValue > 0
      ? (goalValue >= 1_000_000
          ? '$' + (goalValue / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M'
          : '$' + Math.round(goalValue / 1_000) + 'k')
      : '—';
    const yr = this.freedomYear() ?? '—';
    const invest = this.monthlyInvestGoal() ? `$${this.monthlyInvestGoal().toLocaleString()}/mo` : '—';
    return `${goal} by ${yr} · ${invest} through FRED`;
  }

  // ── Referral upgrade offers ────────────────────────────────────────────────
  get referralUpgradeOffers(): Array<{ tier: string; label: string; discountedPrice: number; regularPrice: number }> {
    const offers: Array<{ tier: string; label: string; discountedPrice: number; regularPrice: number }> = [];
    if (this.referralCount < 1) return offers;
    if (this.selectedTier !== 'pro') {
      offers.push({ tier: 'pro', label: 'Premium', discountedPrice: 20, regularPrice: 40 });
    }
    if (this.referralCount >= 2 && this.selectedTier === 'core') {
      offers.push({ tier: 'plus', label: 'Standard', discountedPrice: 10, regularPrice: 15 });
    }
    return offers;
  }

  // ── UserId (for localStorage keying) ──────────────────────────────────────
  private get userId(): string {
    return localStorage.getItem('userId') ?? '';
  }

  constructor(
    private authService: AuthService,
    private toastService: ToastService,
    private portfolioService: PortfolioService,
    private accountStatusService: AccountStatusService,
    private mfuService: MonthlyFreedomUpdateService,
    private router: Router,
    private alertController: AlertController,
    private navCtrl: NavController,
    private modalController: ModalController,
    private goalsService: GoalsService,
    public freedomStats: FreedomStatsService,
    private analytics: AnalyticsService
  ) {
    addIcons({ camera, walletOutline, timeOutline, shareOutline, ticketOutline, checkmarkCircleOutline, saveOutline });
    this.actionRequired$ = this.accountStatusService.actionRequired$;
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.loadUserData();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ── Navigation ─────────────────────────────────────────────────────────────

  goBack(): void {
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  goToDocumentUpload(): void {
    this.router.navigateByUrl('/document-upload');
  }

  reactivateSubscription(): void {
    console.log('[MyProfile] Reactivate subscription requested');
  }

  // ── Data loading ───────────────────────────────────────────────────────────

  loadUserData(): void {
    this.authService.userProgress$
      .pipe(takeUntil(this.destroy$))
      .subscribe(progress => {
        if (progress) {
          if (progress.firstName && progress.lastName) {
            this.userName = `${progress.firstName} ${progress.lastName}`;
          } else if (progress.firstName) {
            this.userName = progress.firstName;
          }

          // Seed the tiles from real DB emissions only. The FIRST DB emission
          // always seeds (pre-seed keystrokes must not pin the empty defaults
          // as the baseline forever); after that, seeding respects unsaved
          // edits — userProgress$ re-emits in the background (token refresh
          // calls loadUserProgress) and must not clobber what the user is
          // typing. localStorageFallback emissions carry no plan values for
          // onboarded users and never seed or stamp the baseline.
          const seedPlan =
            (progress as any)._source !== 'localStorageFallback' &&
            (!this.hasSeededPlan || !this.isDirty());
          if (seedPlan) {
            if (progress.monthlyInvestment) {
              this.monthlyInvestGoal.set(progress.monthlyInvestment);
            }

            if (progress.retirementIncome) {
              this.exactAnnualIncome.set(progress.retirementIncome);
              this.retirementIncomeGoal.set(Math.round(progress.retirementIncome / 12));
            }
          }

          if (progress.referralCode) {
            this.referralCode = progress.referralCode;
          }
          if (progress.referralCount !== undefined) {
            this.referralCount = progress.referralCount;
          }
          if (progress.hasAppliedReferral !== undefined) {
            this.hasAppliedReferral = progress.hasAppliedReferral;
          }
          if (progress.referralRewardTriggered !== undefined) {
            this.referralRewardTriggered = progress.referralRewardTriggered;
          }

          if (progress.privateBeta)                          this.referralThreshold = 3;
          else if (progress.selectedTier === 'plus')         this.referralThreshold = 2;
          else if (progress.selectedTier === 'pro')          this.referralThreshold = 1;
          else                                               this.referralThreshold = 3;

          if (progress.selectedTier) {
            this.selectedTier = progress.selectedTier;
          }

          if (seedPlan) {
            this.hasSeededPlan = true;
            // Stamping the baseline clears any pre-seed dirt (isDirty is
            // computed from this signal).
            this.originalValues.set({
              monthly: this.monthlyInvestGoal(),
              income: this.retirementIncomeGoal()
            });
          }

          const fullyOnboarded = progress.investmentConfirmationCompleted === true;
          const hasActiveSub = progress.selectedTier != null;
          this.isSubExpired = fullyOnboarded && !hasActiveSub;

          // Load persisted goals + prefs now that userId is stable.
          this._loadGoalsAndPrefs();
          this.ratesService?.ensureUser();
        }
      });

    this.authService.getCurrentInvestmentSchedule().subscribe({
      next: (investment) => {
        if (investment) {
          this.currentScheduledInvestment = investment.investmentAmount;
          this.currentFrequency = investment.frequency ? investment.frequency.toLowerCase() : '';
          this.formatScheduleText(investment);
          this.currentMonthlyEquivalent = InvestmentFrequencyUtils.toMonthlyEquivalent(
            this.currentScheduledInvestment, this.currentFrequency);

          // Flipped-rule fallback (2026-08-01): with no saved goal, the
          // schedule's monthly equivalent is the contribution tab3 projects
          // with — seed the tile from it so the at-rest card matches.
          if (!this.monthlyInvestGoal() && !this.isDirty() && this.currentMonthlyEquivalent > 0) {
            const monthly = Math.round(this.currentMonthlyEquivalent);
            this.monthlyInvestGoal.set(monthly);
            this.originalValues.update(v => ({ ...v, monthly }));
          }
        }
      },
      error: () => {
        this.currentScheduledInvestment = 0;
        this.investmentFrequencyText = '';
        this.currentFrequency = '';
        this.currentMonthlyEquivalent = 0;
      }
    });

    this.portfolioService.getPortfolioDashboard().subscribe({
      next: (data) => {
        if (data && data.summary) {
          this.currentPortfolioValue.set(data.summary.equity || 0);
        }
      },
      error: () => {
        this.currentPortfolioValue.set(0);
      }
    });

    this.mfuService.checkShouldShow().subscribe({
      next: (data) => {
        if (data && data.hasMfuHistory && data.statusPercentile > 0) {
          this.statusPercentile = data.statusPercentile;
        } else {
          this.statusPercentile = null;
        }
        if (data && data.equityLevel > 0) {
          this.equityLevel = data.equityLevel;
        }
      },
      error: () => {
        this.statusPercentile = null;
      }
    });
  }

  // ── Plan calculations ───────────────────────────────────────────────────────

  formatScheduleText(investment: any): void {
    if (!investment || !investment.frequency) {
      this.investmentFrequencyText = '';
      return;
    }
    const freq = investment.frequency.toLowerCase();
    switch (freq) {
      case 'weekly':
        this.investmentFrequencyText = `every ${this.getDayOfWeekName(investment.dayOfWeek || investment.startDate)}`;
        break;
      case 'biweekly':
        this.investmentFrequencyText = `every other ${this.getDayOfWeekName(investment.dayOfWeek || investment.startDate)}`;
        break;
      case 'semi_monthly':
        this.investmentFrequencyText = 'every 1st and 15th';
        break;
      case 'monthly':
        const dom = investment.dayOfMonth || (investment.startDate ? new Date(investment.startDate).getDate() : 1);
        this.investmentFrequencyText = `every ${this.getOrdinal(dom)}`;
        break;
      default:
        this.investmentFrequencyText = '';
    }
  }

  getDayOfWeekName(source: string | Date): string {
    if (!source) return 'day';
    if (typeof source === 'string' && isNaN(Date.parse(source))) {
      return source.charAt(0).toUpperCase() + source.slice(1).toLowerCase();
    }
    const date = new Date(source);
    return date.toLocaleDateString('en-US', { weekday: 'long', timeZone: 'UTC' });
  }

  getOrdinal(n: number): string {
    const s = ['th', 'st', 'nd', 'rd'];
    const v = n % 100;
    return n + (s[(v - 20) % 10] || s[v] || s[0]);
  }

  // The tile handlers only write the signal — portfolioGoal, freedomYear,
  // progressPercentage, and isDirty are all computed() and re-render on their
  // own, with change detection scheduled by the signal write itself.
  onIncomeInput(event: Event): void {
    this.retirementIncomeGoal.set(this._sanitizeTileInput(event));
  }

  onInvestInput(event: Event): void {
    this.monthlyInvestGoal.set(this._sanitizeTileInput(event));
  }

  /**
   * Digits only, capped at 5 digits, with the input element rewritten in
   * place. This is the sole maxlength enforcement by design: a DOM maxlength
   * truncates pasted formatted values ("$5,000" → "$5,00" → 500) before this
   * handler can strip the separators, and it is ignored on type="number"
   * inputs anyway.
   */
  private _sanitizeTileInput(event: Event): number {
    const input = event.target as HTMLInputElement;
    const digits = input.value.replace(/\D/g, '').slice(0, 5);
    const value = digits ? parseInt(digits, 10) : 0;
    const canonical = value > 0 ? String(value) : '';
    if (input.value !== canonical) {
      input.value = canonical;
    }
    return value;
  }

  /**
   * Months until pv grows to fv at 10%/yr with a monthly contribution.
   * Ported from FreedomStatsService._calculateMonthsToTarget (600 = 50-year
   * cap on degenerate inputs) so the card stays in parity with tab3/MFU.
   */
  private _monthsToTarget(pv: number, pmt: number, fv: number): number {
    return monthsToTarget(pv, pmt, fv, this.AVG_MARKET_YIELD);
  }

  saveChanges(): void {
    if (!this.isDirty()) return;
    const monthly = this.monthlyInvestGoal();
    const income = this.retirementIncomeGoal();
    if (!monthly || !income) {
      this.toastService.showToast('Please enter valid amounts for both goals.', 'warning');
      // The copy is not sent: category plus how it was shown is the whole payload.
      this.analytics.trackErrorShown('validation', 'my_profile', 'toast');
      return;
    }
    this.authService.updateUserProfile({
      monthlyInvestment: monthly,
      retirementIncome: income * 12
    }).subscribe({
      next: () => {
        this.toastService.showToast('Profile updated!', 'success');
        // Baseline = saved values; the isDirty computed clears itself.
        this.originalValues.set({ monthly, income });
        this.authService.loadUserProgress();
      },
      error: (error) => {
        this.toastService.showToast('Failed to save changes.', 'danger');
        // Split by analytics-error.utils' rule rather than by message: a request that never
        // reached a server is a network failure, anything with a status is a failed save. The
        // baseline is untouched on failure, so the Save button is still there to retry.
        const category: ErrorCategory = isNetworkFailure(error) ? 'network' : 'save_failed';
        this.analytics.trackErrorShown(category, 'my_profile', 'toast', httpStatusOf(error), true);
      }
    });
  }

  // ── Referral ───────────────────────────────────────────────────────────────

  async copyReferral(): Promise<void> {
    if (!this.referralCode) return;
    await navigator.clipboard.writeText(this.referralCode);
    this.toastService.showToast('Referral code copied!', 'success');
  }

  onReferralCodeInput(value: string): void {
    if (!value || value.trim().length < 3) { this.isCodeValid = false; return; }
    this.authService.validateReferralCode(value.trim()).subscribe({
      next: (res) => { this.isCodeValid = res.valid; },
      error: () => { this.isCodeValid = false; }
    });
  }

  redeemCode(): void {
    if (!this.redeemCodeInput || this.redeemCodeInput.trim() === '') {
      this.toastService.showToast('Please enter a referral code.', 'warning');
      return;
    }
    if (this.referralCode && this.redeemCodeInput.trim().toUpperCase() === this.referralCode.toUpperCase()) {
      this.toastService.showToast('You cannot use your own referral code.', 'danger');
      return;
    }
    if (this.hasAppliedReferral) {
      this.toastService.showToast('You have already applied a referral code.', 'warning');
      return;
    }
    this.isLoadingReferral = true;
    this.authService.applyReferralCode(this.redeemCodeInput).subscribe({
      next: () => {
        this.isLoadingReferral = false;
        this.toastService.showToast('Referral code applied successfully!', 'success');
        this.redeemCodeInput = '';
        this.authService.loadUserProgress();
      },
      error: (error) => {
        this.isLoadingReferral = false;
        const msg = error.error?.message || 'Failed to apply referral code.';
        this.toastService.showToast(msg, 'danger');
      }
    });
  }

  // ── Upgrade offers (referral-triggered) ────────────────────────────────────

  async confirmTierUpgrade(tier: string, label: string, price: number): Promise<void> {
    const alert = await this.alertController.create({
      header: `Upgrade to ${label}`,
      message: `Upgrade to ${label} for $${price}/mo using your referral discount?`,
      buttons: [
        { text: 'Cancel', role: 'cancel' },
        {
          text: 'Confirm',
          handler: () => {
            this.authService.updateUserProfile({ selectedTier: tier, billingPeriod: 'monthly' }).subscribe({
              next: () => {
                this.selectedTier = tier;
                this.toastService.showToast(`Upgraded to ${label}!`, 'success');
              },
              error: () => {
                this.toastService.showToast('Upgrade failed. Please try again.', 'danger');
              }
            });
          }
        }
      ]
    });
    await alert.present();
  }

  // ── Rebalancing ────────────────────────────────────────────────────────────

  selectSchedule(schedule: RebalanceSchedule): void {
    if (!this.isPlusOrPro) {
      this.openPlusNudge('rebalancing');
      return;
    }
    this.selectedSchedule = schedule;
    this.goalsService.savePrefs(this.userId, { rebalanceSchedule: schedule });
    // The chip IS the commit point — savePrefs is a synchronous localStorage write, so the
    // outcome is known here and is always 'applied'.
    this.analytics.track({
      name: 'setting_changed',
      props: {
        setting: 'rebalance_schedule',
        enabled: null,
        value: MyProfilePage.scheduleValue(schedule),
        outcome: 'applied',
      },
    });
  }

  /** 'Semi-annual' → 'semi_annual'. A stable slug, not the display label. */
  private static scheduleValue(schedule: RebalanceSchedule): string {
    return schedule.toLowerCase().replace(/[^a-z0-9]+/g, '_');
  }

  // ── Freedom detail sheet ──────────────────────────────────────────────────

  /**
   * Full Freedom Plan story — editing tiles, insights, and adjustable rate
   * assumptions. Opened from the Freedom Plan card and the Freedom Date row.
   */
  async openFreedomSheet(): Promise<void> {
    this.ratesService?.ensureUser();
    const modal = await this.modalController.create({
      component: FreedomDetailSheetComponent,
      componentProps: {
        income: this.retirementIncomeGoal(),
        invest: this.monthlyInvestGoal(),
        equity: this.currentPortfolioValue(),
      },
      ...BOTTOM_SHEET_MODAL_OPTS,
      // Tall variant: this sheet's content should fit without scrolling.
      cssClass: 'mc-bottom-sheet-modal mc-bottom-sheet-tall',
    });
    // Tour: hide the overlay while the sheet is up, then hand control back
    // when it closes (either call is a no-op unless the tour is on this step).
    this.tourService?.suspend();
    await modal.present();
    const { role, data } = await modal.onDidDismiss<FreedomSheetResult>();
    this.tourService?.freedomSheetClosed();
    if (role === 'saved' && data) {
      this.ratesService?.setRates(data.annualReturn, data.withdrawalRate);
      // The sheet's Save is gated on a complete plan; guard anyway so a
      // zeroed payload can never wipe the live signals.
      if (data.income > 0) this.retirementIncomeGoal.set(data.income);
      if (data.invest > 0) this.monthlyInvestGoal.set(data.invest);
      if (this.isDirty()) {
        this.saveChanges();
      } else {
        // Only the assumptions moved — no server write needed.
        this.toastService.showToast('Plan updated', 'success');
      }
    }
  }

  // ── Goals ─────────────────────────────────────────────────────────────────

  async openAddGoalSheet(editGoal?: Goal): Promise<void> {
    if (!this.isPlusOrPro) {
      await this.openPlusNudge('goals');
      return;
    }
    const modal = await this.modalController.create({
      component: GoalAddSheetComponent,
      componentProps: {
        payFrequency: this.currentFrequency,
        ...(editGoal ? { editGoal } : {}),
      },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
    const { role, data } = await modal.onDidDismiss();
    if (role === 'saved' && data) {
      const newGoal = this.goalsService.addGoal(this.userId, data);
      this.goals = [...this.goals, newGoal];
      this.toastService.showToast('Goal added. Projection tracked.', 'success');
      this._trackGoalChanged('created', newGoal);
    } else if (role === 'updated' && data) {
      this.goalsService.updateGoal(this.userId, data);
      this.goals = this.goals.map(g => g.id === data.id ? data : g);
      this.toastService.showToast('Goal updated', 'success');
      this._trackGoalChanged('updated', data);
    } else if (role === 'completed' && data) {
      this.goalsService.updateGoal(this.userId, data);
      this.goals = this.goals.map(g => g.id === data.id ? data : g);
      this.toastService.showToast(
        data.completed ? 'Goal marked complete 🎉' : 'Goal reopened', 'success');
      // Two sides of one toggle, told apart by the goal's new state.
      this._trackGoalChanged(data.completed ? 'completed' : 'reopened', data);
    } else if (role === 'deleted' && data) {
      // Read the goal before it goes: the dismiss payload is the id alone, and the id is the one
      // thing that must never be sent — the icon and the bands are what describe it.
      const removed = this.goals.find(g => g.id === data);
      this.deleteGoal(data);
      if (removed) {
        this._trackGoalChanged('removed', removed);
      }
    }
  }

  /**
   * The single goal instrumentation point: every change arrives as a sheet dismiss role, so the
   * five actions are reported from one place with one shape. Called AFTER `goals` has been
   * updated, because `goal_count` is the count the user is left with.
   *
   * The goal's name is free text the user typed and its id is a UUID: neither is in the payload.
   */
  private _trackGoalChanged(action: GoalAction, goal: Goal): void {
    const target = goal.targetAmount || 0;
    const progress = target > 0
      ? toPercentBand(((goal.startingValue || 0) / target) * 100)
      : null;
    this.analytics.track({
      name: 'goal_changed',
      props: {
        action,
        icon: goal.icon,
        target_band: toAmountBand(target),
        years_to_target: toYearsBand(
          goal.targetYear ? goal.targetYear - new Date().getFullYear() : null),
        progress,
        goal_count: toCountBand(this.goals.length),
      },
    });
  }

  deleteGoal(goalId: string): void {
    this.goalsService.deleteGoal(this.userId, goalId);
    this.goals = this.goals.filter(g => g.id !== goalId);
    this.toastService.showToast('Goal removed', 'success');
  }

  // ── Nudge sheets ───────────────────────────────────────────────────────────

  /**
   * Goals area tapped on Piggy — presents the Plus upgrade sheet.
   * Wired to: mc-info-sheet mode='nudge' with Plus-targeted copy.
   * On 'upgrade' role: delegates to upgradeToPlusClicked() (same as tab2 pattern).
   */
  async openGoalsVeilNudge(): Promise<void> {
    if (this.isPlusOrPro) return;
    await this.openPlusNudge('goals');
  }

  /**
   * Reusable Plus upgrade nudge — used by goals veil + rebalancing lock tap.
   *
   * `feature` only names which gate was hit for analytics; the sheet, the copy and the upgrade
   * path are identical for both and unchanged.
   */
  async openPlusNudge(feature: GatedFeature = 'goals'): Promise<void> {
    const modal = await this.modalController.create({
      component: McInfoSheetComponent,
      componentProps: {
        mode: 'nudge',
        nudgeTitle: 'Unlock multi-goal tracking with Piggy Plus',
        nudgeBody: 'Track your house, emergency fund, college savings, and more, side by side with projected timelines.',
        nudgeCtaLabel: 'Upgrade to Piggy Plus',
        targetTier: 'plus',
      },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
    this._trackNudge(feature, 'shown');
    const { role } = await modal.onDidDismiss();
    this._trackNudge(feature, role === 'upgrade' ? 'upgrade_tapped' : 'dismissed');
    if (role === 'upgrade') {
      this.upgradeToPlusClicked();
    }
  }

  /**
   * TLH "Want to save this money?" tapped on non-Pro — presents the Pro upgrade sheet.
   * Wired to: mc-info-sheet mode='nudge' with Pro-targeted copy (defaults).
   */
  async openTlhProNudge(): Promise<void> {
    if (this.isPro) return;
    const modal = await this.modalController.create({
      component: McInfoSheetComponent,
      componentProps: {
        mode: 'nudge',
        nudgeTitle: 'Go deeper with Piggy Pro',
        nudgeBody: 'Rule-based Tax Loss Harvesting automatically sells losing positions and replaces them with similar ETFs, potentially saving you hundreds per year.',
        nudgeCtaLabel: 'Upgrade to Pro',
        targetTier: 'pro',
      },
      ...BOTTOM_SHEET_MODAL_OPTS,
    });
    await modal.present();
    this._trackNudge('tax_loss_harvesting', 'shown');
    const { role } = await modal.onDidDismiss();
    this._trackNudge('tax_loss_harvesting', role === 'upgrade' ? 'upgrade_tapped' : 'dismissed');
    if (role === 'upgrade') {
      this.upgradeToProClicked();
    }
  }

  /**
   * A paywalled surface was hit. Product data only: which gate, what the user has today, and
   * what they did with the nudge. No price, no billing period, no purchase — those are the
   * billing workstream's and are untouched here.
   */
  private _trackNudge(feature: GatedFeature, action: 'shown' | 'upgrade_tapped' | 'dismissed'): void {
    this.analytics.track({
      name: 'paywall_nudge_shown',
      props: { feature, tier: this._tier(), action },
    });
  }

  /** The user's tier as the taxonomy names it. 'piggy' is the legacy name for core; '' is none. */
  private _tier(): Tier {
    switch (this.selectedTier) {
      case 'piggy':
      case 'core': return 'core';
      case 'plus': return 'plus';
      case 'pro':  return 'pro';
      default:     return null;
    }
  }

  /** Plus upgrade handler — mirrors the tab2 retirementPlanning pattern. */
  upgradeToPlusClicked(): void {
    // TODO: trigger Plus subscription IAP
    console.log('[MyProfile] Plus upgrade requested');
  }

  /** Pro upgrade handler. */
  upgradeToProClicked(): void {
    // TODO: trigger Pro subscription IAP
    console.log('[MyProfile] Pro upgrade requested');
  }

  // ── Private helpers ────────────────────────────────────────────────────────

  private _loadGoalsAndPrefs(): void {
    const uid = this.userId;
    if (!uid) return;
    this.goals = this.goalsService.getGoals(uid);
    const prefs = this.goalsService.getPrefs(uid);
    this.selectedSchedule = prefs.rebalanceSchedule;
  }

  private _projectGoal(g: Goal): GoalProjection {
    const today = new Date();
    const currentYear = today.getFullYear();
    const currentMonth = today.getMonth();

    // Per-paycheck saving → monthly, using the user's schedule frequency
    // (FRED's invest-per-paycheck model makes that the pay frequency).
    const monthly = InvestmentFrequencyUtils.toMonthlyEquivalent(
      Math.max(0, g.perPaycheckOutsideFRED), this.currentFrequency);

    if (g.completed) {
      return { goal: g, onTrack: true, projectedLabel: 'Done', progressPct: 100, monthlyEquivalent: monthly };
    }

    // Progress bar: actual saved fraction (floor 2% so the bar reads as started).
    const savedPct = g.targetAmount > 0
      ? Math.max(2, Math.min(100, Math.round(((g.startingValue || 0) / g.targetAmount) * 100)))
      : 100;

    // Months needed to cover what's left after the starting value.
    const remaining = Math.max(0, g.targetAmount - (g.startingValue || 0));

    // $0/paycheck with an unmet target: no pace → no honest date to project
    // (the old 9999-month sentinel rendered "Projected · Nov 2859").
    if (remaining > 0 && monthly <= 0) {
      return { goal: g, onTrack: false, projectedLabel: 'no pace set', progressPct: savedPct, monthlyEquivalent: monthly };
    }

    const monthsNeeded = remaining <= 0 ? 0 : Math.ceil(remaining / monthly);

    const projYear = currentYear + Math.floor((currentMonth + monthsNeeded) / 12);
    const projMonth = (currentMonth + monthsNeeded) % 12;

    const projDate = new Date(projYear, projMonth, 1);
    const targetDate = new Date(g.targetYear, 11, 31);

    const onTrack = projDate <= targetDate;

    const projectedLabel = projDate.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });

    return { goal: g, onTrack, projectedLabel, progressPct: savedPct, monthlyEquivalent: monthly };
  }
}
