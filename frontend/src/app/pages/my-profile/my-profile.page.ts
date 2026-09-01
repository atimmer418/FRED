import { Component, OnInit, OnDestroy, inject } from '@angular/core';
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
import { FirstTimeTourService } from '../../services/first-time-tour.service';

/** Computed projection for a single user-added goal. */
export interface GoalProjection {
  goal: Goal;
  onTrack: boolean;
  projectedLabel: string;
  progressPct: number;
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

  // ── Freedom Plan inputs (editable) ────────────────────────────────────────
  monthlyInvestGoal: number = 2500;
  retirementIncomeGoal: number = 5000; // Monthly display; stored annually

  // ── Computed plan values ───────────────────────────────────────────────────
  currentScheduledInvestment: number = 0;
  investmentFrequencyText: string = '';
  portfolioGoal: number = 0;
  yearsToReach: number | string = 0;
  yearsToReachCurrent: number | string = 0;
  yearsToReachRemaining: number | string | null = null;
  currentMonthlyEquivalent: number = 0;
  currentPortfolioValue: number = 0;
  progressPercentage: number = 0;

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
    return EquityPigUtils.pigSrcFromEquity(this.currentPortfolioValue);
  }

  // ── Tier ──────────────────────────────────────────────────────────────────
  selectedTier: string = '';

  get isPiggy(): boolean { return this.selectedTier === 'piggy' || this.selectedTier === 'core' || this.selectedTier === ''; }
  get isPlus(): boolean  { return this.selectedTier === 'plus'; }
  get isPro(): boolean   { return this.selectedTier === 'pro'; }
  get isPlusOrPro(): boolean { return this.isPlus || this.isPro; }

  // ── State ──────────────────────────────────────────────────────────────────
  isDirty: boolean = false;
  isSubExpired: boolean = false;
  private originalValues: { monthly: number; income: number } = { monthly: 0, income: 0 };
  private exactAnnualIncome: number | null = null;
  currentFrequency: string = '';

  // ── Constants ──────────────────────────────────────────────────────────────
  private readonly SAFE_WITHDRAWAL_RATE = 0.04;
  private readonly AVG_MARKET_YIELD = 0.10;

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

  get goalProjections(): GoalProjection[] {
    return this.goals.map(g => this._projectGoal(g));
  }

  // ── TLH ───────────────────────────────────────────────────────────────────
  get tlhEstimateDisplay(): string {
    const est = tlhEstimate(this.currentPortfolioValue);
    return est > 0 ? `~$${est.toLocaleString()}/yr` : '~$0/yr';
  }

  // ── Freedom Date (for the Goals freedom row) ───────────────────────────────
  get freedomYear(): number | null {
    const year = parseInt(this.freedomStats.stats().freedomYear, 10);
    return isNaN(year) ? null : year;
  }

  get yearsToFreedom(): number | null {
    if (!this.freedomYear) return null;
    return this.freedomYear - new Date().getFullYear();
  }

  get freedomGoalSub(): string {
    const goal = this.portfolioGoal > 0
      ? (this.portfolioGoal >= 1_000_000
          ? '$' + (this.portfolioGoal / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M'
          : '$' + Math.round(this.portfolioGoal / 1_000) + 'k')
      : '—';
    const yr = this.freedomYear ?? '—';
    const invest = this.monthlyInvestGoal ? `$${this.monthlyInvestGoal.toLocaleString()}/mo` : '—';
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
    public freedomStats: FreedomStatsService
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

          if (progress.monthlyInvestment) {
            this.monthlyInvestGoal = progress.monthlyInvestment;
          }

          if (progress.retirementIncome) {
            this.exactAnnualIncome = progress.retirementIncome;
            this.retirementIncomeGoal = Math.round(progress.retirementIncome / 12);
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

          this.originalValues = {
            monthly: this.monthlyInvestGoal,
            income: this.retirementIncomeGoal
          };

          this.calculatePlan();

          const fullyOnboarded = progress.investmentConfirmationCompleted === true;
          const hasActiveSub = progress.selectedTier != null;
          this.isSubExpired = fullyOnboarded && !hasActiveSub;

          // Load persisted goals + prefs now that userId is stable.
          this._loadGoalsAndPrefs();
        }
      });

    this.authService.getCurrentInvestmentSchedule().subscribe({
      next: (investment) => {
        if (investment) {
          this.currentScheduledInvestment = investment.investmentAmount;
          this.currentFrequency = investment.frequency ? investment.frequency.toLowerCase() : '';
          this.formatScheduleText(investment);
          this.calculateCurrentScheduleYears();
        }
      },
      error: () => {
        this.currentScheduledInvestment = 0;
        this.investmentFrequencyText = '';
        this.currentFrequency = '';
        this.currentMonthlyEquivalent = 0;
        this.yearsToReachCurrent = '∞';
      }
    });

    this.portfolioService.getPortfolioDashboard().subscribe({
      next: (data) => {
        if (data && data.summary) {
          this.currentPortfolioValue = data.summary.equity || 0;
          this.calculateProgress();
          this.calculateCurrentScheduleYears();
        }
      },
      error: () => {
        this.currentPortfolioValue = 0;
        this.calculateProgress();
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

  calculatePlan(): void {
    this.calculatePortfolioGoal();
    this.calculateHypotheticalYears();
    this.calculateCurrentScheduleYears();
    this.checkDirty();
  }

  onIncomeChange(): void {
    if (this.retirementIncomeGoal && this.retirementIncomeGoal.toString().length > 5) {
      this.retirementIncomeGoal = Number(this.retirementIncomeGoal.toString().slice(0, 5));
    }
    this.calculatePortfolioGoal();
    this.calculateHypotheticalYears();
    this.calculateCurrentScheduleYears();
    this.checkDirty();
  }

  onInvestChange(): void {
    if (this.monthlyInvestGoal && this.monthlyInvestGoal.toString().length > 5) {
      this.monthlyInvestGoal = Number(this.monthlyInvestGoal.toString().slice(0, 5));
    }
    this.calculateHypotheticalYears();
    this.checkDirty();
  }

  private calculatePortfolioGoal(): void {
    let annualIncome: number;
    if (this.exactAnnualIncome !== null &&
        Math.round(this.exactAnnualIncome / 12) === this.retirementIncomeGoal) {
      annualIncome = this.exactAnnualIncome;
    } else {
      annualIncome = this.retirementIncomeGoal * 12;
    }
    this.portfolioGoal = annualIncome / this.SAFE_WITHDRAWAL_RATE;
    this.calculateProgress();
  }

  private calculateProgress(): void {
    if (this.portfolioGoal > 0) {
      this.progressPercentage = Math.min(this.currentPortfolioValue / this.portfolioGoal, 1.0);
    } else {
      this.progressPercentage = 0;
    }
  }

  private calculateHypotheticalYears(): void {
    this.yearsToReach = this.monthlyInvestGoal > 0
      ? this.calculateYears(this.monthlyInvestGoal)
      : '∞';
  }

  private calculateCurrentScheduleYears(): void {
    if (this.currentScheduledInvestment > 0) {
      const monthly = InvestmentFrequencyUtils.toMonthlyEquivalent(
        this.currentScheduledInvestment, this.currentFrequency);
      this.currentMonthlyEquivalent = monthly;
      this.yearsToReachCurrent = this.calculateYears(monthly);
      if (this.currentPortfolioValue > 0) {
        this.yearsToReachRemaining = this.calculateYearsWithPV(monthly, this.currentPortfolioValue);
      } else {
        this.yearsToReachRemaining = null;
      }
    }
  }

  calculateYearsWithPV(monthlyAmount: number, currentEquity: number): string | number {
    if (monthlyAmount <= 0) return '∞';
    if (currentEquity >= this.portfolioGoal) return 0;
    const r = this.AVG_MARKET_YIELD / 12;
    const numerator = Math.log((this.portfolioGoal + monthlyAmount / r) / (currentEquity + monthlyAmount / r));
    const denominator = Math.log(1 + r);
    const months = numerator / denominator;
    return (isNaN(months) || !isFinite(months)) ? '∞' : (months / 12).toFixed(1);
  }

  calculateYears(monthlyAmount: number): string | number {
    if (monthlyAmount <= 0) return '∞';
    const r = this.AVG_MARKET_YIELD / 12;
    const numerator = Math.log((this.portfolioGoal * r / monthlyAmount) + 1);
    const denominator = Math.log(1 + r);
    const months = numerator / denominator;
    return (isNaN(months) || !isFinite(months)) ? '∞' : (months / 12).toFixed(1);
  }

  calculateYearsToReach(monthlyAmount: number): void {
    this.yearsToReach = this.calculateYears(monthlyAmount);
  }

  checkDirty(): void {
    this.isDirty =
      this.monthlyInvestGoal !== this.originalValues.monthly ||
      this.retirementIncomeGoal !== this.originalValues.income;
  }

  saveChanges(): void {
    if (!this.isDirty) return;
    if (!this.monthlyInvestGoal || !this.retirementIncomeGoal) {
      this.toastService.showToast('Please enter valid amounts for both goals.', 'warning');
      return;
    }
    this.authService.updateUserProfile({
      monthlyInvestment: this.monthlyInvestGoal,
      retirementIncome: this.retirementIncomeGoal * 12
    }).subscribe({
      next: () => {
        this.toastService.showToast('Profile updated!', 'success');
        this.isDirty = false;
        this.originalValues = { monthly: this.monthlyInvestGoal, income: this.retirementIncomeGoal };
        this.authService.loadUserProgress();
      },
      error: () => {
        this.toastService.showToast('Failed to save changes.', 'danger');
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
      this.openPlusNudge();
      return;
    }
    this.selectedSchedule = schedule;
    this.goalsService.savePrefs(this.userId, { rebalanceSchedule: schedule });
  }

  // ── Goals ─────────────────────────────────────────────────────────────────

  async openAddGoalSheet(editGoal?: Goal): Promise<void> {
    if (!this.isPlusOrPro) {
      await this.openPlusNudge();
      return;
    }
    const modal = await this.modalController.create({
      component: GoalAddSheetComponent,
      componentProps: editGoal ? { editGoal } : {},
      cssClass: 'mc-bottom-sheet-modal',
    });
    await modal.present();
    const { role, data } = await modal.onDidDismiss();
    if (role === 'saved' && data) {
      const newGoal = this.goalsService.addGoal(this.userId, data);
      this.goals = [...this.goals, newGoal];
      this.toastService.showToast('Goal added — projection tracked', 'success');
    } else if (role === 'updated' && data) {
      this.goalsService.updateGoal(this.userId, data);
      this.goals = this.goals.map(g => g.id === data.id ? data : g);
      this.toastService.showToast('Goal updated', 'success');
    }
  }

  deleteGoal(goalId: string): void {
    this.goalsService.deleteGoal(this.userId, goalId);
    this.goals = this.goals.filter(g => g.id !== goalId);
    this.toastService.showToast('Goal removed', 'success');
  }

  /** Long-press on a goal row opens the edit sheet with a delete affordance. */
  async onGoalLongPress(goal: Goal): Promise<void> {
    if (!this.isPlusOrPro) return;
    const alert = await this.alertController.create({
      header: goal.name,
      buttons: [
        {
          text: 'Edit',
          handler: () => { this.openAddGoalSheet(goal); }
        },
        {
          text: 'Delete',
          role: 'destructive',
          handler: () => { this.deleteGoal(goal.id); }
        },
        { text: 'Cancel', role: 'cancel' }
      ]
    });
    await alert.present();
  }

  // ── Nudge sheets ───────────────────────────────────────────────────────────

  /**
   * Goals area tapped on Piggy — presents the Plus upgrade sheet.
   * Wired to: mc-info-sheet mode='nudge' with Plus-targeted copy.
   * On 'upgrade' role: delegates to upgradeToPlusClicked() (same as tab2 pattern).
   */
  async openGoalsVeilNudge(): Promise<void> {
    if (this.isPlusOrPro) return;
    await this.openPlusNudge();
  }

  /** Reusable Plus upgrade nudge — used by goals veil + rebalancing lock tap. */
  async openPlusNudge(): Promise<void> {
    const modal = await this.modalController.create({
      component: McInfoSheetComponent,
      componentProps: {
        mode: 'nudge',
        nudgeTitle: 'Unlock multi-goal tracking with Piggy Plus',
        nudgeBody: 'Track your house, emergency fund, college savings, and more — side by side with projected timelines.',
        nudgeCtaLabel: 'Upgrade to Piggy Plus',
        targetTier: 'plus',
      },
      cssClass: 'mc-bottom-sheet-modal',
    });
    await modal.present();
    const { role } = await modal.onDidDismiss();
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
        nudgeBody: 'Rule-based Tax Loss Harvesting automatically sells losing positions and replaces them with similar ETFs — potentially saving you hundreds per year.',
        nudgeCtaLabel: 'Upgrade to Pro',
        targetTier: 'pro',
      },
      cssClass: 'mc-bottom-sheet-modal',
    });
    await modal.present();
    const { role } = await modal.onDidDismiss();
    if (role === 'upgrade') {
      this.upgradeToProClicked();
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

    const monthly = Math.max(0, g.monthlyOutsideFRED);

    // Months needed to accumulate targetAmount at the stated monthly saving.
    // Guard: if monthly is 0, projection is infinite → rendered as "late".
    let monthsNeeded: number;
    if (monthly <= 0) {
      monthsNeeded = 9999;
    } else {
      monthsNeeded = Math.ceil(g.targetAmount / monthly);
    }

    const projYear = currentYear + Math.floor((currentMonth + monthsNeeded) / 12);
    const projMonth = (currentMonth + monthsNeeded) % 12;

    const projDate = new Date(projYear, projMonth, 1);
    const targetDate = new Date(g.targetYear, 11, 31);

    const onTrack = projDate <= targetDate;

    const projectedLabel = projDate.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });

    // Progress: rough fraction of time elapsed toward the target (capped 2%–100%)
    const totalMonths = (g.targetYear - currentYear) * 12 + (12 - currentMonth);
    const pct = totalMonths > 0
      ? Math.max(2, Math.min(100, Math.round((monthsNeeded > 0 ? (1 - monthsNeeded / totalMonths) * 100 : 100))))
      : 100;

    return { goal: g, onTrack, projectedLabel, progressPct: pct };
  }
}
