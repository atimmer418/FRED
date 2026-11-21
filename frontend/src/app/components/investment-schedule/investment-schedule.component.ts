import { Component, ElementRef, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import {
  IonContent, IonHeader, IonToolbar, IonFooter, IonSpinner, ModalController, NavController, ViewWillLeave
} from '@ionic/angular/standalone';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../../services/analytics/analytics-error.utils';
import { InvestmentService } from '../../services/investment.service'; // Import this
import { ToastService } from '../../services/toast.service';
import { environment } from '../../../environments/environment';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { AmountTrayHost } from '../plan-amount-tray/amount-tray-host';
import { AmountTraySlotComponent } from '../plan-amount-tray/amount-tray-slot.component';
import { PiecewiseScale } from '../../utils/plan-amount-scale';
import { addDays, dayOfWeek, formatFull, formatShort, parseYmd, todayYmd, ymd } from '../../utils/local-date';
import { nextBusinessDay } from '../../utils/business-days';
import { quickPicks } from '../../utils/quick-picks';
import { InvestmentFrequencyKey } from '../../utils/investment-frequency.utils';
import { presentDateSheet } from '../fred-date-sheet/fred-date-sheet.component';

export interface InvestmentSchedule {
  payFrequency: InvestmentFrequencyKey;
  startDate: string;
  isEnabled: boolean;
}

export interface CreateInvestmentScheduleRequest {
  monthlyAmount: number;
  frequency: string;
}

export interface InvestmentScheduleResponse {
  id: number;
  monthlyAmount: number;
  frequency: string;
  achRequestId?: string;
  isPaused: boolean;
  createdAt: string;
  updatedAt: string;
}

/** The one tray-driven field on this screen. */
type TrayField = 'amount';

/** The next pay date is locked on this frequency: the backend owns the 1st and 15th. */
const LOCKED_FREQUENCY: InvestmentFrequencyKey = 'SEMI_MONTHLY';
/** The echo under the locked date, as the mockup words it. */
export const PAY_DATE_LOCKED_ECHO = 'Set by your pay schedule: the 1st and 15th.';

const MONDAY = 1;

/** The Monday strictly after the date. */
function nextMondayAfter(from: string): string {
  let current = addDays(from, 1);
  while (dayOfWeek(current) !== MONDAY) current = addDays(current, 1);
  return current;
}

/** The 1st of the month after the one the date is in. */
function firstOfNextMonth(from: string): string {
  const { y, m } = parseYmd(from);
  return m === 12 ? ymd(y + 1, 1, 1) : ymd(y, m + 1, 1);
}

/**
 * The amount per paycheck, as the rollout table sets it
 * (docs/superpowers/specs/2026-11-11-amount-tray-rollout-design.md): $5 to $10,000, the dollar step
 * growing with the amount; minus and plus move $5.
 */
const AMOUNT_SCALE: PiecewiseScale = {
  min: 5,
  max: 10_000,
  fine: 5,
  segments: [{ to: 500, step: 5 }, { to: 2000, step: 50 }, { to: 5000, step: 250 }, { to: 10_000, step: 500 }],
};

@Component({
  selector: 'app-investment-schedule',
  templateUrl: './investment-schedule.component.html',
  styleUrls: ['./investment-schedule.component.scss'],
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonContent, IonHeader, IonToolbar, IonFooter, IonSpinner,
    KeyboardAvoidDirective, AmountTraySlotComponent
  ]
})
export class InvestmentScheduleComponent implements OnInit, OnDestroy, ViewWillLeave {
  // User's financial data from initial survey
  monthlyGoal: number = 0;

  /** The amount per paycheck: a signal, so the tray under it follows every change. */
  readonly investmentAmount = signal(0);
  /** The slide tray under the amount: open, close, outside tap and the label swap live in the host. */
  readonly tray: AmountTrayHost<TrayField>;
  readonly PAY_DATE_LOCKED_ECHO = PAY_DATE_LOCKED_ECHO;

  isSubmitting: boolean = false;
  isReady: boolean = false;

  /** True when a transfer draft was restored from storage, i.e. the user has been here before. */
  private resumedFromSavedDraft = false;
  
  // ACATS Transfer
  showTransferOptions: boolean = false;
  transferBrokerageDtc: string = '';
  transferAccountNumber: string = '';

  brokerageOptions = [
    { name: 'Alinea', dtc: '2402' },
    { name: 'Charles Schwab', dtc: '0164' },
    { name: 'E*TRADE', dtc: '0385' },
    { name: 'Fidelity', dtc: '0226' },
    { name: 'Public', dtc: '0158' },
    { name: 'Robinhood', dtc: '6769' },
    { name: 'TD Ameritrade', dtc: '0188' },
    { name: 'Vanguard', dtc: '0062' },
    { name: 'Webull', dtc: '0158' },
  ];

  constructor(
    private router: Router,
    private navCtrl: NavController,
    private authService: AuthService,
    private investmentService: InvestmentService,
    private http: HttpClient,
    private analytics: AnalyticsService,
    private toastService: ToastService,
    private modalCtrl: ModalController,
    el: ElementRef<HTMLElement>
  ) {
    this.tray = new AmountTrayHost<TrayField>({
      root: el,
      fields: {
        amount: {
          label: 'Amount',
          scale: AMOUNT_SCALE,
          value: this.investmentAmount,
          set: v => this.investmentAmount.set(v),
          valueSuffix: ' per paycheck',
        },
      },
    });
  }

  // Investment schedule configuration
  schedule: InvestmentSchedule = {
    payFrequency: 'BIWEEKLY',
    startDate: '', // This will be the next pay date
    isEnabled: true
  };

  // Frequency options with descriptions and calculation details
  frequencyOptions: Array<{
    value: InvestmentFrequencyKey; label: string; description: string; paychecksPerMonth: number; daysBetween: number | null;
  }> = [
    {
      value: 'WEEKLY',
      label: 'Weekly',
      description: 'Every 7 days',
      paychecksPerMonth: 4.33,
      daysBetween: 7
    },
    {
      value: 'BIWEEKLY',
      label: 'Bi-weekly',
      description: 'Every 2 weeks',
      paychecksPerMonth: 2.17,
      daysBetween: 14
    },
    {
      value: 'SEMI_MONTHLY',
      label: 'Semi-monthly',
      description: '1st and 15th of each month',
      paychecksPerMonth: 2.0,
      daysBetween: null // Special case - handled separately
    },
    {
      value: 'MONTHLY',
      label: 'Monthly',
      description: 'Once per month',
      paychecksPerMonth: 1.0,
      daysBetween: null // Special case - handled separately
    }
  ];

  ngOnInit() {
    console.log('[InvestmentScheduleComponent] ngOnInit - Initializing Investment Schedule Page.');

    this.restoreAcatsFromStorage();
    // Fired before the data loads: the saved-schedule restore is asynchronous, so the only state
    // this screen knows about synchronously is the transfer draft.
    this.analytics.trackOnboardingStepViewed('investment_schedule', null, this.resumedFromSavedDraft);
    this.loadUserFinancialData();

    // Let Manrope render before revealing content
    setTimeout(() => this.isReady = true, 50);

  }

  ngOnDestroy(): void {
    this.tray.destroy();
  }

  /** Leaving the screen: the tray snaps shut instead of collapsing into a screen that is going. */
  ionViewWillLeave(): void {
    this.tray.close(true);
  }

  private restoreAcatsFromStorage(): void {
    const saved = localStorage.getItem('pendingAcats');
    if (saved) {
      try {
        const { dtc, account } = JSON.parse(saved);
        this.transferBrokerageDtc = dtc || '';
        this.transferAccountNumber = account || '';
        if (this.transferBrokerageDtc || this.transferAccountNumber) {
          this.showTransferOptions = true;
          this.resumedFromSavedDraft = true;
        }
      } catch (_) {}
    }
  }

  goBack(): void {
    this.router.navigateByUrl('/kyc-verification', { replaceUrl: true });
  }

  loadUserFinancialData(): void {
    console.log('[InvestmentScheduleComponent] Loading user financial data...');

    // Try to restore a previously saved schedule first
    this.authService.getCurrentInvestmentSchedule().subscribe({
      next: (savedSchedule) => {
        if (savedSchedule && savedSchedule.frequency && savedSchedule.investmentAmount) {
          console.log('[InvestmentScheduleComponent] ✅ Restoring saved schedule:', savedSchedule);
          this.schedule.payFrequency = savedSchedule.frequency;
          this.investmentAmount.set(savedSchedule.investmentAmount);
          this.schedule.startDate = this.parseDateValue(savedSchedule.startDate) || this.getInvestmentDate();
        } else {
          this.schedule.startDate = this.getInvestmentDate();
        }
        this.loadMonthlyGoalAndCalculate();
      },
      error: () => {
        this.schedule.startDate = this.getInvestmentDate();
        this.loadMonthlyGoalAndCalculate();
      }
    });
  }

  private loadMonthlyGoalAndCalculate(): void {
    const currentProgress = this.authService.getCurrentProgress();

    if (currentProgress && currentProgress.monthlyInvestment) {
      this.setFinancialData(currentProgress);
    } else {
      this.authService.getUserProgress().subscribe({
        next: (progress) => this.setFinancialData(progress),
        error: () => {
          this.monthlyGoal = 500;
          this.calculateRecommendedAmount();
        }
      });
    }

    this.authService.userProgress$.subscribe(progress => {
      if (progress && progress.monthlyInvestment) {
        this.setFinancialData(progress);
      }
    });
  }

  private setFinancialData(progress: any): void {
    console.log('[InvestmentScheduleComponent] setFinancialData, monthlyInvestment:', progress?.monthlyInvestment);

    if (progress && progress.monthlyInvestment) {
      this.monthlyGoal = progress.monthlyInvestment;
      console.log('[InvestmentScheduleComponent] ✅ Loaded user monthly investment amount:', this.monthlyGoal);
    } else {
      console.log('[InvestmentScheduleComponent] ⚠️ No monthlyInvestment found in progress data.');
      this.monthlyGoal = 0;
    }

    this.calculateRecommendedAmount();
  }

  // Method to manually refresh user data (useful for debugging)
  refreshUserData(): void {
    console.log('[InvestmentScheduleComponent] 🔄 Manually refreshing user data...');
    this.authService.loadUserProgress();
  }

  onFrequencyChange(frequency: InvestmentFrequencyKey) {
    this.schedule.payFrequency = frequency;
    this.calculateRecommendedAmount();
    // Update the suggested pay date based on the new frequency
    this.schedule.startDate = this.getInvestmentDate();
    // Angular's change detection will automatically update the description when these properties change
    console.log('[InvestmentScheduleComponent] Frequency changed to:', frequency, 'Start date set to:', this.schedule.startDate);
  }

  /** The next pay date is set by the backend on SEMI_MONTHLY: the button is disabled with the echo. */
  payDateLocked(): boolean {
    return this.schedule.payFrequency === LOCKED_FREQUENCY;
  }

  /** The button's text: 'Mon, Dec 1', with the year once it is not this year. */
  payDateText(): string {
    return this.schedule.startDate ? formatShort(this.schedule.startDate) : '';
  }

  payDateAriaLabel(): string {
    const date = this.schedule.startDate ? `, ${formatFull(this.schedule.startDate)}` : '';
    return `Next pay date${date}${this.payDateLocked() ? ', set by your pay schedule' : '. Opens a date picker'}`;
  }

  /**
   * The date sheet in upcoming mode: tomorrow onward, business days only (so the weekend bump this
   * screen used to make is gone: the sheet cannot return a weekend), quick picks for the chosen
   * frequency. Nothing changes until Done.
   */
  async openPayDateSheet(): Promise<void> {
    if (this.payDateLocked()) return;
    const today = todayYmd();
    const min = this.getMinDate();
    const date = await presentDateSheet(this.modalCtrl, {
      title: 'Next pay date',
      mode: 'upcoming',
      value: this.schedule.startDate || null,
      min,
      businessDaysOnly: true,
      quickPicks: quickPicks(this.schedule.payFrequency, min, today, true),
    });
    if (date !== null) this.schedule.startDate = date;
  }

  private parseDateValue(value: any): string {
    if (!value) return '';
    if (Array.isArray(value)) {
      const [y, m, d] = value;
      return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    }
    if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}/.test(value)) {
      return value.substring(0, 10);
    }
    return '';
  }

  /** The earliest pay date the sheet offers: tomorrow. */
  getMinDate(): string {
    return addDays(todayYmd(), 1);
  }

  calculateRecommendedAmount() {
    if (this.monthlyGoal <= 0) return;

    const selectedFrequency = this.getSelectedFrequencyDetails();

    if (selectedFrequency) {
      this.investmentAmount.set(Math.round(this.monthlyGoal / selectedFrequency.paychecksPerMonth));
    }
  }

  getSelectedFrequencyDetails() {
    return this.frequencyOptions.find(f => f.value === this.schedule.payFrequency);
  }

  getMonthlyProjection(): number {
    const frequencyDetails = this.getSelectedFrequencyDetails();
    if (!frequencyDetails) return 0;
    return this.investmentAmount() * frequencyDetails.paychecksPerMonth;
  }

  getMonthlyShortfall(): number {
    return Math.max(0, Math.round(this.monthlyGoal - this.getMonthlyProjection()));
  }

  getAnnualProjection(): number {
    return this.getMonthlyProjection() * 12;
  }

  /**
   * The suggested pay date for the frequency. The sheet only offers business days, so every seed
   * but SEMI_MONTHLY's lands on one; SEMI_MONTHLY stays on the 1st or 15th (the field is locked
   * and the backend owns that rule).
   */
  private getInvestmentDate(): string {
    const today = todayYmd();

    switch (this.schedule.payFrequency) {
      case 'WEEKLY':
        return nextMondayAfter(today);

      case 'BIWEEKLY':
        return addDays(nextMondayAfter(today), 7);

      case 'SEMI_MONTHLY':
        return this.getNextSemiMonthlyDate();

      case 'MONTHLY':
        // A weekend 1st seeds the Monday after it. The backend anchors MONTHLY on that day of the
        // month, so the moved day, not the 1st, is the recurring day until the date is edited.
        return nextBusinessDay(firstOfNextMonth(today));

      default:
        return nextBusinessDay(addDays(today, 1));
    }
  }

  private getNextSemiMonthlyDate(): string {
    const today = new Date();
    const currentDay = today.getDate();
    const currentMonth = today.getMonth();
    const currentYear = today.getFullYear();

    let nextDate: Date;

    if (currentDay < 15) {
      // Before the 15th: the 15th of this month
      nextDate = new Date(currentYear, currentMonth, 15);
    } else {
      // On or after the 15th: the 1st of next month
      nextDate = new Date(currentYear, currentMonth + 1, 1);
    }

    // Format date as YYYY-MM-DD in local time to avoid timezone issues
    const year = nextDate.getFullYear();
    const month = String(nextDate.getMonth() + 1).padStart(2, '0');
    const day = String(nextDate.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  canProceed(): boolean {
    if (this.investmentAmount() <= 0 || this.schedule.startDate === '') return false;
    if (this.showTransferOptions && (!this.transferBrokerageDtc || !this.transferAccountNumber)) return false;
    return true;
  }

  goToStockPreferences(): void {
    this.tray.close();
    console.log('[InvestmentScheduleComponent] Proceeding to investment confirmation with schedule:', this.schedule);
    // At the tap. The amount and frequency themselves are a server-side fact (recurring_created).
    this.analytics.trackOnboardingStepCompleted('investment_schedule', null);
    this.isSubmitting = true;

    // Prepare data for backend - match CreateInvestmentScheduleRequest DTO
    const investmentScheduleData = {
      investmentAmount: this.investmentAmount(),
      frequency: this.schedule.payFrequency,
      startDate: this.schedule.startDate // Send as string in YYYY-MM-DD format
    };

    console.log('[InvestmentScheduleComponent] Saving investment schedule:', investmentScheduleData);
    console.log('[InvestmentScheduleComponent] Start date being sent:', this.schedule.startDate);

    // Save ACATS transfer data if selected
    if (this.showTransferOptions && this.transferBrokerageDtc && this.transferAccountNumber) {
        this.investmentService.pendingAcatsRequest = {
            dtcNumber: this.transferBrokerageDtc,
            accountNumber: this.transferAccountNumber
        };
        localStorage.setItem('pendingAcats', JSON.stringify({ dtc: this.transferBrokerageDtc, account: this.transferAccountNumber }));
        console.log('Saved pending ACATS request');
    } else {
        this.investmentService.pendingAcatsRequest = null;
        localStorage.removeItem('pendingAcats');
    }

    // Save investment schedule using AuthService
    this.authService.createOrUpdateInvestmentSchedule(investmentScheduleData)
      .subscribe({
        next: (response) => {
          console.log('[InvestmentScheduleComponent] ✅ Investment schedule saved successfully:', response);

          // Complete the investmentSchedule step using the unified method
          this.authService.completeStep('investmentSchedule').subscribe({
            next: () => {
              console.log('InvestmentSchedule step completed successfully');
              // Navigate to investment confirmation
              this.navCtrl.navigateForward('/investment-confirmation', { replaceUrl: true });
            },
            error: (err) => {
              console.error('Failed to complete InvestmentSchedule step:', err);
              // Still navigate even if progress update fails
              this.navCtrl.navigateForward('/investment-confirmation', { replaceUrl: true });
            }
          });
        },
        error: (error) => {
          console.error('[InvestmentScheduleComponent] ❌ Error saving investment schedule:', error);
          this.isSubmitting = false;
          this.analytics.trackErrorShown(
            isNetworkFailure(error) ? 'network' : 'save_failed',
            'onboarding',
            'toast',
            httpStatusOf(error),
            true,
          );
          this.toastService.show({ severity: 'error', message: 'Error saving investment schedule. Please try again.' });
        }
      });
  }

  formatCurrency(amount: number): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0
    }).format(amount);
  }
}