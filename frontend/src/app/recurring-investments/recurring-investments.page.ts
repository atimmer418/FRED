import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { NavController } from '@ionic/angular';
import { Subject, takeUntil } from 'rxjs';
import {
  IonHeader,
  IonToolbar,
  IonContent,
  ModalController
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  pauseOutline,
  playOutline,
  calendarOutline,
  cashOutline,
  timeOutline,
  checkmarkCircleOutline,
  alertCircleOutline,
  settingsOutline,
  informationCircleOutline, addOutline
} from 'ionicons/icons';
import { InvestmentService, InvestmentSchedule, CreateInvestmentScheduleRequest } from '../services/investment.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AccountStatusService } from '../services/account-status.service';
import { presentConfirmSheet } from '../components/fred-confirm-sheet/fred-confirm-sheet.component';
import { presentDateSheet } from '../components/fred-date-sheet/fred-date-sheet.component';
import { KeyboardAvoidDirective } from '../directives/keyboard-avoid.directive';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../services/analytics/analytics-error.utils';
import { SkeletonComponent } from '../components/skeleton/skeleton.component';
import { tradeWindow } from '../utils/business-days';
import { dayOfWeek, formatFull, formatLong, formatMonthDay, formatShort, parseYmd, todayYmd, ymd } from '../utils/local-date';
import { quickPicks } from '../utils/quick-picks';
import { PlaidService } from '../services/plaid.service';
import { PrimaryBankAccount } from '../models/primary-bank-account';
import { ReconnectCardComponent } from '../components/reconnect-card/reconnect-card.component';

interface InvestmentFrequencyOption {
  value: 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'SEMI_MONTHLY';
  label: string;
  description: string;
  paychecksPerMonth: number;
}

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/** The echo under a locked Transfer Date: the backend owns the 1st and 15th. */
export const SEMI_MONTHLY_LOCK_ECHO = 'Set by your pay schedule: the 1st and 15th.';

@Component({
  selector: 'app-recurring-investments',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonHeader,
    IonToolbar,
    IonContent,
    KeyboardAvoidDirective,
    SkeletonComponent,
    ReconnectCardComponent
  ],
  templateUrl: './recurring-investments.page.html',
  styleUrls: ['./recurring-investments.page.scss']
})
export class RecurringInvestmentsPage implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  currentInvestment: InvestmentSchedule | null = null;
  /** nextInvestmentDate is held as a local YYYY-MM-DD string once edited; the loaded shape is read through toYmd(). */
  editedInvestment: Partial<InvestmentSchedule> = {
    frequency: 'WEEKLY',
    isPaused: false,
    nextInvestmentDate: undefined
  };
  /**
   * The amount field as typed. A string, not a number, so a half-typed "12." survives change
   * detection; editedAmount() is the number the rest of the page reads.
   */
  amountInput = '';

  isLoading = false;

  /** The linked bank from GET /plaid/primary-bank-account; null when unlinked or unreadable. */
  bankAccount: PrimaryBankAccount | null = null;
  private accountRejected = false;

  frequencyOptions: InvestmentFrequencyOption[] = [
    {
      value: 'WEEKLY',
      label: 'Weekly',
      description: 'Every week on the same day of the week',
      paychecksPerMonth: 4.33
    },
    {
      value: 'BIWEEKLY',
      label: 'Bi-weekly',
      description: 'Every two weeks on the same day',
      paychecksPerMonth: 2.17
    },
    {
      value: 'SEMI_MONTHLY',
      label: 'Semi-monthly',
      description: 'On the 1st and 15th of each month',
      paychecksPerMonth: 2
    },
    {
      value: 'MONTHLY',
      label: 'Monthly',
      description: 'Once per month on the same date',
      paychecksPerMonth: 1
    }
  ];

  suggestedAmount: number | null = null;

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private investmentService: InvestmentService,
    private passkeyService: PasskeyService,
    private pinService: PinService,
    private toastService: ToastService,
    private accountStatusService: AccountStatusService,
    private modalCtrl: ModalController,
    private navCtrl: NavController,
    private analytics: AnalyticsService,
    private plaidService: PlaidService
  ) {
    addIcons({ cashOutline, timeOutline, calendarOutline, addOutline, checkmarkCircleOutline, pauseOutline, playOutline, alertCircleOutline, settingsOutline, informationCircleOutline });
  }

  ngOnInit() {
    this.route.queryParams.pipe(takeUntil(this.destroy$)).subscribe(params => {
      if (params['suggestedAmount']) {
        this.suggestedAmount = Number(params['suggestedAmount']);
      }
    });
    this.loadCurrentInvestment();
    this.loadBankAccount();
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /** What the reconnect card is given: nothing for a rejected applicant, who cannot invest (R18). */
  get reconnectAccount(): PrimaryBankAccount | null {
    return this.accountRejected ? null : this.bankAccount;
  }

  /**
   * The bank is read independently of the schedule (R10), so the card shows for a user with no
   * schedule too. A failed read leaves no card and no toast: the schedule is this page's job.
   */
  private loadBankAccount(): void {
    this.accountStatusService.accountStatus$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => this.accountRejected = status === 'REJECTED');
    this.plaidService.getPrimaryBankAccount()
      .pipe(takeUntil(this.destroy$))
      .subscribe(account => this.bankAccount = account);
  }

  /** Reconnect reopens the same Item in update mode; that page falls back to a fresh link on 409. */
  onReconnect(): void {
    this.router.navigate(['/change-bank-account'], { queryParams: { mode: 'update' } });
  }

  private loadCurrentInvestment() {
    this.isLoading = true;
    this.investmentService.getCurrentSchedule()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (investment: InvestmentSchedule) => {
          this.currentInvestment = investment;
          if (investment) {
            this.amountInput = String(this.suggestedAmount ?? investment.investmentAmount);
            this.editedInvestment = {
              frequency: investment.frequency,
              isPaused: investment.isPaused,
              nextInvestmentDate: investment.nextInvestmentDate
            };
          }
          this.isLoading = false;
        },
        error: (error) => {
          console.error('Error loading investment schedule:', error);
          if (error.status === 404) {
            console.log('No investment schedule found - user probably hasn\'t set one up yet');
            this.currentInvestment = null;
          } else {
            // The 404 above is the normal "no schedule yet" state and shows nothing, so only this
            // branch is an error the user actually saw.
            this.analytics.trackErrorShown(
              isNetworkFailure(error) ? 'network' : 'load_failed',
              'portfolio',
              'toast',
              httpStatusOf(error),
              false,
            );
            this.toastService.showToast('Failed to load investment schedule', 'danger');
          }
          this.isLoading = false;
        }
      });
  }

  getFrequencyLabel(): string {
    const option = this.frequencyOptions.find(opt => opt.value === this.currentInvestment?.frequency);
    return option?.label || 'Not set';
  }

  getFrequencyText(): string {
    if (!this.currentInvestment) return 'period';

    switch (this.currentInvestment.frequency) {
      case 'WEEKLY':
        return 'week';
      case 'BIWEEKLY':
        return 'two weeks';
      case 'MONTHLY':
        return 'month';
      case 'SEMI_MONTHLY':
        return '1st and 15th of the month';
      default:
        return 'period';
    }
  }

  getEditedFrequencyText(): string {
    if (!this.editedInvestment?.frequency) return 'period';

    switch (this.editedInvestment.frequency) {
      case 'WEEKLY':
        return 'week';
      case 'BIWEEKLY':
        return 'two weeks';
      case 'MONTHLY':
        return 'month';
      case 'SEMI_MONTHLY':
        return '1st and 15th of the month';
      default:
        return 'period';
    }
  }

  getFrequencyDescription(): string {
    if (!this.currentInvestment) return '';

    const option = this.frequencyOptions.find(opt => opt.value === this.currentInvestment?.frequency);
    const baseDescription = option?.description || '';

    // The day the schedule runs on: chosenDate is the user's anchor, before any holiday adjustment.
    const date = this.toYmd(this.currentInvestment.chosenDate || this.currentInvestment.nextInvestmentDate || this.currentInvestment.startDate);
    if (!date) return baseDescription;

    const weekday = WEEKDAYS[dayOfWeek(date)];
    switch (this.currentInvestment.frequency) {
      case 'WEEKLY':
        return `Every ${weekday}`;
      case 'BIWEEKLY':
        return `Every other ${weekday}`;
      case 'MONTHLY':
        return `Every ${this.getOrdinal(parseYmd(date).d)}`;
      case 'SEMI_MONTHLY':
        return 'Every 1st and 15th';
      default:
        return baseDescription;
    }
  }

  private getOrdinal(day: number): string {
    const j = day % 10;
    const k = day % 100;
    if (j === 1 && k !== 11) {
      return day + 'st';
    }
    if (j === 2 && k !== 12) {
      return day + 'nd';
    }
    if (j === 3 && k !== 13) {
      return day + 'rd';
    }
    return day + 'th';
  }

  /** The 1st/15th this page seeded for semi-monthly, so leaving semi-monthly can tell it from a date the user picked. */
  private semiMonthlySeed: string | null = null;

  /**
   * Handle frequency change
   */
  onFrequencyChange() {
    if (this.editedInvestment.frequency !== 'SEMI_MONTHLY') {
      // The seed can fall on a weekend, which no other frequency may save: the saved date shows again.
      if (this.semiMonthlySeed !== null && this.editedInvestment.nextInvestmentDate === this.semiMonthlySeed) {
        this.editedInvestment.nextInvestmentDate = this.currentInvestment?.nextInvestmentDate;
        this.semiMonthlySeed = null;
      }
      return;
    }
    const now = new Date();
    const currentDay = now.getDate();
    const currentMonth = now.getMonth();
    const currentYear = now.getFullYear();

    let nextDate: Date;

    // If today is 2nd-15th -> Next date is 15th of current month
    if (currentDay >= 2 && currentDay <= 15) {
      nextDate = new Date(Date.UTC(currentYear, currentMonth, 15, 12, 0, 0));
    }
    // If today is 16th-1st (16th-End or 1st) -> Next date is 1st (of next month if 16th+, or current month if 1st)
    else if (currentDay >= 16) {
      nextDate = new Date(Date.UTC(currentYear, currentMonth + 1, 1, 12, 0, 0));
    }
    else {
      // Today is the 1st
      // "if the date is the 16th-1st, the transfer date should show the 1st"
      // If today is the 1st, we show the 1st (of current month).
      nextDate = new Date(Date.UTC(currentYear, currentMonth, 1, 12, 0, 0));
    }

    const nYear = nextDate.getUTCFullYear();
    const nMonth = (nextDate.getUTCMonth() + 1).toString().padStart(2, '0');
    const nDay = nextDate.getUTCDate().toString().padStart(2, '0');

    this.semiMonthlySeed = `${nYear}-${nMonth}-${nDay}`;
    this.editedInvestment.nextInvestmentDate = this.semiMonthlySeed;
  }

  /**
   * A schedule date as a local YYYY-MM-DD string, or null when there is none. The backend sends
   * either a Java LocalDate array [year, month, day] or a date string, sometimes with a time part;
   * everything after this boundary is the plain date. A malformed string reads as no date.
   */
  private toYmd(dateInput: string | number[] | undefined | null): string | null {
    if (!dateInput) return null;
    if (Array.isArray(dateInput)) {
      return dateInput.length === 3 ? ymd(dateInput[0], dateInput[1], dateInput[2]) : null;
    }
    const datePart = dateInput.split('T')[0];
    try {
      parseYmd(datePart);
      return datePart;
    } catch {
      return null;
    }
  }

  /** The transfer date the field shows: the edit, else the saved schedule's. */
  transferDate(): string | null {
    return this.toYmd(this.editedInvestment.nextInvestmentDate) ?? this.toYmd(this.currentInvestment?.nextInvestmentDate);
  }

  /** Semi-monthly runs on the 1st and 15th by the backend's rule, so the date is not the user's to pick. */
  isTransferDateLocked(): boolean {
    return this.editedInvestment.frequency === 'SEMI_MONTHLY';
  }

  /** "Fri, Nov 20" in the field, or '' while no date is set (the template shows the placeholder). */
  transferDateText(): string {
    const date = this.transferDate();
    return date ? formatShort(date) : '';
  }

  transferDateAriaLabel(): string {
    const date = this.transferDate();
    const when = date ? formatFull(date) : 'not set';
    return `Transfer date, ${when}${this.isTransferDateLocked() ? ', set by your pay schedule' : '. Opens a date picker'}`;
  }

  /** The one line under the field: when the trades land, or why the field is locked. */
  transferEcho(): string {
    if (this.isTransferDateLocked()) return SEMI_MONTHLY_LOCK_ECHO;
    const date = this.transferDate();
    return date ? `Est. investment ${tradeWindow(date)}` : '';
  }

  /** Opens the date sheet; the edit lands only on Done. */
  async openTransferDate() {
    if (this.isTransferDateLocked()) return;
    const today = todayYmd();
    const frequency = this.editedInvestment.frequency ?? this.currentInvestment?.frequency ?? 'WEEKLY';
    const date = await presentDateSheet(this.modalCtrl, {
      title: 'Transfer date',
      mode: 'upcoming',
      value: this.transferDate(),
      min: today,
      businessDaysOnly: true,
      quickPicks: quickPicks(frequency, today, today, true),
      subline: d => `Trades land ${tradeWindow(d)}`,
    });
    if (date !== null) {
      this.editedInvestment.nextInvestmentDate = date;
    }
  }

  getMonthlyProjection(): number {
    return Math.floor(this.getAnnualProjection() / 12);
  }

  getAnnualProjection(): number {
    if (!this.currentInvestment) return 0;
    const amount = this.currentInvestment.investmentAmount;

    switch (this.currentInvestment.frequency) {
      case 'WEEKLY':
        return amount * 52;
      case 'BIWEEKLY':
        return amount * 26;
      case 'SEMI_MONTHLY':
        return amount * 24;
      case 'MONTHLY':
        return amount * 12;
      default:
        return 0;
    }
  }

  formatCurrency(amount: number): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0
    }).format(amount);
  }

  /** "Oct 26, 2026", or "Oct 26" without the year; 'Not set' when there is no date. */
  formatDate(dateInput: string | number[], includeYear: boolean = true): string {
    const date = this.toYmd(dateInput);
    if (!date) return 'Not set';
    return includeYear ? formatLong(date) : formatMonthDay(date);
  }

  /**
   * The typed amount as a number: 0 when the field is empty or not a number. The field is
   * type="text" so a half-typed "12." survives, which means commas reach us raw: a comma that
   * groups thousands ("1,000") is dropped and a lone comma ("12,50", the decimal keypad under a
   * non-US region) is the decimal point.
   */
  editedAmount(): number {
    const raw = this.amountInput.trim().replace(/,(?=\d{3}(?!\d))/g, '').replace(',', '.');
    const amount = raw === '' ? NaN : Number(raw);
    return Number.isFinite(amount) ? amount : 0;
  }

  /** Save needs at least $1, the same floor saveChanges() holds. */
  isAmountValid(): boolean {
    return this.editedAmount() >= 1;
  }

  /** Leaving the field with a fraction of a dollar lifts it to the $1 floor. */
  onAmountChange() {
    const amount = this.editedAmount();
    if (amount > 0 && amount < 1) {
      this.amountInput = '1';
    }
  }

  async toggleInvestmentStatus() {
    if (!this.currentInvestment) return;

    const isPausing = !this.currentInvestment.isPaused;

    // Pausing with a streak costs the streak, so ask first.
    if (isPausing && this.currentInvestment.monthlyStreak > 0) {
      const streak = this.currentInvestment.monthlyStreak;
      const ok = await presentConfirmSheet(this.modalCtrl, {
        tone: 'destructive',
        icon: 'pause_circle',
        title: 'Pause investments?',
        body: `This will reset your ${streak}-month investing streak to 0. Investing less is better than investing nothing. Consider reducing your amount instead.`,
        confirmLabel: 'Pause anyway',
        cancelLabel: 'Keep investing',
      });
      if (!ok) return;
    }

    // No streak, or resuming: nothing to confirm.
    this.executePauseOrResume(isPausing);
  }

  private async executePauseOrResume(isPausing: boolean) {
    if (!this.currentInvestment) return;

    this.isLoading = true;

    try {
      // Step-up authentication check
      const hasPin = await this.pinService.hasPin();

      if (hasPin) {
        const verified = await this.pinService.promptPin('verify');
        if (!verified) {
          this.analytics.trackErrorShown('auth_required', 'portfolio', 'toast', null, false);
          this.toastService.showToast('Authentication required to change investment status.', 'warning');
          this.isLoading = false;
          return;
        }
      }

      // Gate: only check account status when resuming (unpausing)
      if (!isPausing && !this.accountStatusService.isAccountActive()) {
        this.analytics.trackErrorShown('unavailable', 'portfolio', 'toast', null, false);
        this.toastService.showToast(
          'Your account is not yet active. Investments will resume once your account is fully verified.',
          'warning'
        );
        this.isLoading = false;
        return;
      }

      const operation = isPausing ?
        this.investmentService.pauseSchedule(this.currentInvestment.id) :
        this.investmentService.resumeSchedule(this.currentInvestment.id);

      operation.pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (updatedSchedule: InvestmentSchedule) => {
            this.currentInvestment = updatedSchedule;
            this.toastService.showToast(isPausing ?
              'Investment schedule paused successfully!' :
              'Investment schedule resumed successfully!', 'success');
            this.isLoading = false;
          },
          error: (error) => {
            console.error('Error updating investment status:', error);
            // Fallback: handle 403 from backend as account-not-active gate
            if (error.status === 403) {
              // The server's version of the same account-not-active gate.
              this.analytics.trackErrorShown('unavailable', 'portfolio', 'toast', 403, false);
              this.toastService.showToast(
                'Your account is not yet active. Investments will resume once your account is fully verified.',
                'warning'
              );
            } else {
              this.analytics.trackErrorShown(
                isNetworkFailure(error) ? 'network' : 'save_failed',
                'portfolio',
                'toast',
                httpStatusOf(error),
                false,
              );
              this.toastService.showToast('Failed to update investment schedule. Please try again.', 'danger');
            }
            this.isLoading = false;
          }
        });
    } catch (e) {
      console.error('Unexpected error:', e);
      this.isLoading = false;
    }
  }

  async saveChanges() {
    const amount = this.editedAmount();
    if (!amount || amount < 1) {
      this.analytics.trackErrorShown('validation', 'portfolio', 'toast', null, false);
      this.toastService.showToast('Investment amount must be at least $1', 'danger');
      return;
    }

    if (!this.currentInvestment) return;

    this.isLoading = true;

    try {
      // Step-up authentication check
      const hasPin = await this.pinService.hasPin();

      if (hasPin) {
        const verified = await this.pinService.promptPin('verify');
        if (!verified) {
          this.analytics.trackErrorShown('auth_required', 'portfolio', 'toast', null, false);
          this.toastService.showToast('Authentication required to save changes.', 'warning');
          this.isLoading = false;
          return;
        }
      }

      // Always the existing start date, to preserve history.
      const startDate = this.currentInvestment.startDate;
      const startDateString = Array.isArray(startDate) ? ymd(startDate[0], startDate[1], startDate[2]) : startDate;

      // Create update request
      const updateRequest: CreateInvestmentScheduleRequest = {
        investmentAmount: amount,
        frequency: this.editedInvestment.frequency || this.currentInvestment.frequency,
        startDate: startDateString,
        nextInvestmentDate: this.toYmd(this.editedInvestment.nextInvestmentDate) ?? undefined
      };

      this.investmentService.createSchedule(updateRequest)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: (updatedSchedule: InvestmentSchedule) => {
            this.toastService.showToast('Investment schedule updated successfully!', 'success');
            this.loadCurrentInvestment();
          },
          error: (error) => {
            console.error('Error updating investment:', error);
            this.analytics.trackErrorShown(
              isNetworkFailure(error) ? 'network' : 'save_failed',
              'portfolio',
              'toast',
              httpStatusOf(error),
              false,
            );
            this.toastService.showToast('Failed to update investment schedule. Please try again.', 'danger');
            this.isLoading = false;
          }
        });
    } catch (e) {
      console.error('Unexpected error:', e);
      this.isLoading = false;
    }
  }

  goBack() {
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  deleteInvestmentSchedule() {
    // TODO: Implement delete functionality with confirmation dialog
    console.log('Delete investment schedule');
  }

  /**
   * Check if there are unsaved changes
   */
  hasChanges(): boolean {
    if (!this.currentInvestment) return false;

    // Check amount
    if (this.currentInvestment.investmentAmount !== this.editedAmount()) {
      return true;
    }

    // Check frequency
    if (this.currentInvestment.frequency !== this.editedInvestment.frequency) {
      return true;
    }

    // Both dates as plain YYYY-MM-DD, whatever shape each arrived in.
    return this.toYmd(this.currentInvestment.nextInvestmentDate) !== this.toYmd(this.editedInvestment.nextInvestmentDate);
  }
}
