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
  IonButton,
  IonButtons,
  IonDatetime,
  IonModal,
  AlertController
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
import { KeyboardAvoidDirective } from '../directives/keyboard-avoid.directive';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../services/analytics/analytics-error.utils';

interface InvestmentFrequencyOption {
  value: 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'SEMI_MONTHLY';
  label: string;
  description: string;
  paychecksPerMonth: number;
}

@Component({
  selector: 'app-recurring-investments',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonHeader,
    IonToolbar,
    IonContent,
    IonButton,
    IonButtons,
    IonDatetime,
    IonModal,
    KeyboardAvoidDirective
  ],
  templateUrl: './recurring-investments.page.html',
  styleUrls: ['./recurring-investments.page.scss']
})
export class RecurringInvestmentsPage implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  currentInvestment: InvestmentSchedule | null = null;
  editedInvestment: Partial<InvestmentSchedule> = {
    investmentAmount: 0,
    frequency: 'WEEKLY',
    isPaused: false,
    nextInvestmentDate: undefined
  };

  isLoading = false;
  isDatePickerOpen = false;

  // Helper property for date picker minimum date
  readonly todayISO = new Date().toISOString();

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
    private alertController: AlertController,
    private navCtrl: NavController,
    private analytics: AnalyticsService
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
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private loadCurrentInvestment() {
    this.isLoading = true;
    this.investmentService.getCurrentSchedule()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (investment: InvestmentSchedule) => {
          this.currentInvestment = investment;
          if (investment) {
            this.editedInvestment = {
              investmentAmount: this.suggestedAmount ?? investment.investmentAmount,
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

    // Add specific day information based on frequency and start date
    // Use chosenDate (user preference) if available, otherwise fallback to nextInvestmentDate or startDate
    const dateInput = this.currentInvestment.chosenDate || this.currentInvestment.nextInvestmentDate || this.currentInvestment.startDate;

    if (dateInput) {
      let date: Date;

      // Handle array format from Java LocalDate serialization [year, month, day]
      if (Array.isArray(dateInput) && dateInput.length === 3) {
        const [year, month, day] = dateInput;
        // Construct UTC date at noon
        date = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
      } else if (typeof dateInput === 'string') {
        // Parse string manually to avoid UTC conversion issues
        // If it contains 'T', split by 'T' first to get the date part
        const datePart = dateInput.includes('T') ?
          dateInput.split('T')[0] :
          dateInput;

        const parts = datePart.split('-');
        if (parts.length === 3) {
          const year = parseInt(parts[0]);
          const month = parseInt(parts[1]);
          const day = parseInt(parts[2]);
          // Construct UTC date at noon
          date = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
        } else {
          date = new Date(dateInput);
        }
      } else {
        return baseDescription;
      }

      if (isNaN(date.getTime())) {
        return baseDescription;
      }

      const dayOfWeek = date.toLocaleDateString('en-US', { weekday: 'long', timeZone: 'UTC' });
      const dayOfMonth = date.getUTCDate();

      switch (this.currentInvestment.frequency) {
        case 'WEEKLY':
          return `Every ${dayOfWeek}`;
        case 'BIWEEKLY':
          return `Every other ${dayOfWeek}`;
        case 'MONTHLY':
          const ordinal = this.getOrdinal(dayOfMonth);
          return `Every ${ordinal}`;
        case 'SEMI_MONTHLY':
          return 'Every 1st and 15th';
        default:
          return baseDescription;
      }
    }

    return baseDescription;
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

  /**
   * Handle frequency change
   */
  onFrequencyChange() {
    if (this.editedInvestment.frequency === 'SEMI_MONTHLY') {
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
      
      this.editedInvestment.nextInvestmentDate = `${nYear}-${nMonth}-${nDay}`;
    }
  }

  /**
   * Convert date to ISO string for datetime picker
   */
  getDateForPicker(dateInput: string | number[] | undefined): string {
    if (!dateInput) return '';

    try {
      let date: Date;

      if (Array.isArray(dateInput) && dateInput.length === 3) {
        const [year, month, day] = dateInput;
        // Construct UTC date at noon
        date = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
      } else if (typeof dateInput === 'string') {
        // Parse string manually to avoid UTC conversion issues
        // If it contains 'T', split by 'T' first to get the date part
        const datePart = dateInput.includes('T') ? dateInput.split('T')[0] : dateInput;
        const parts = datePart.split('-');
        if (parts.length === 3) {
          const year = parseInt(parts[0]);
          const month = parseInt(parts[1]);
          const day = parseInt(parts[2]);
          // Construct UTC date at noon
          date = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
        } else {
          date = new Date(dateInput);
        }
      } else {
        return '';
      }

      if (isNaN(date.getTime())) {
        return '';
      }

      // Return ISO string for the picker (which expects YYYY-MM-DD or full ISO)
      // Use UTC methods to get the date parts we constructed
      const year = date.getUTCFullYear();
      const month = (date.getUTCMonth() + 1).toString().padStart(2, '0');
      const day = date.getUTCDate().toString().padStart(2, '0');
      return `${year}-${month}-${day}`;
    } catch (error) {
      console.error('Error converting date for picker:', error);
      return '';
    }
  }

  /**
   * Confirm date selection from picker
   */
  confirmDate(value: any) {
    if (value) {
      // Handle both string and array (though for date presentation usually string)
      const selectedDate = Array.isArray(value) ? value[0] : value;
      this.editedInvestment.nextInvestmentDate = selectedDate;
    }
  }

  /**
   * Handle date selection from picker
   */
  onNextInvestmentDateChange(event: any) {
    const selectedDate = event.detail.value;
    if (selectedDate) {
      // Store as ISO string in editedInvestment
      this.editedInvestment.nextInvestmentDate = selectedDate;
    }
  }

  /**
   * Open date picker modal
   */
  openDatePicker() {
    this.isDatePickerOpen = true;
  }

  /**
   * Close date picker modal
   */
  closeDatePicker() {
    this.isDatePickerOpen = false;
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

  formatDate(dateInput: string | number[], includeYear: boolean = true): string {
    if (!dateInput) return 'Not set';

    try {
      let date: Date;

      // Handle array format from Java LocalDate serialization [year, month, day]
      if (Array.isArray(dateInput) && dateInput.length === 3) {
        const [year, month, day] = dateInput;
        // Construct UTC date at noon to avoid timezone issues
        date = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
      } else if (typeof dateInput === 'string') {
        // Handle string format - parse manually to avoid UTC issues
        // If it contains 'T', split by 'T' first to get the date part
        const datePart = dateInput.includes('T') ? dateInput.split('T')[0] : dateInput;
        const parts = datePart.split('-');
        if (parts.length === 3) {
          const year = parseInt(parts[0]);
          const month = parseInt(parts[1]);
          const day = parseInt(parts[2]);
          // Construct UTC date at noon
          date = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
        } else {
          date = new Date(dateInput);
        }
      } else {
        console.error('Unexpected date format:', dateInput);
        return 'Invalid date format';
      }

      if (isNaN(date.getTime())) {
        return 'Invalid date';
      }

      const options: Intl.DateTimeFormatOptions = {
        month: 'short',
        day: 'numeric',
        timeZone: 'UTC'
      };

      if (includeYear) {
        options.year = 'numeric';
      }

      return new Intl.DateTimeFormat('en-US', options).format(date);
    } catch (error) {
      console.error('Error formatting date:', error);
      return 'Date error';
    }
  }

  onAmountChange() {
    // Ensure minimum amount
    if (this.editedInvestment.investmentAmount && this.editedInvestment.investmentAmount < 1) {
      this.editedInvestment.investmentAmount = 1;
    }
  }

  async toggleInvestmentStatus() {
    if (!this.currentInvestment) return;

    const isPausing = !this.currentInvestment.isPaused;

    // If pausing and streak > 0, show confirmation alert first
    if (isPausing && this.currentInvestment.monthlyStreak > 0) {
      const streak = this.currentInvestment.monthlyStreak;
      const alert = await this.alertController.create({
        header: 'Pause Investments?',
        message: `This will reset your ${streak}-month investing streak to 0. Investing less is better than investing nothing. Consider reducing your amount instead.`,
        buttons: [
          {
            text: 'Cancel',
            role: 'cancel'
          },
          {
            text: 'Pause Anyway',
            role: 'destructive',
            handler: () => {
              this.executePauseOrResume(isPausing);
            }
          }
        ]
      });
      await alert.present();
      return;
    }

    // No streak or resuming — proceed immediately
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
    if (!this.editedInvestment.investmentAmount || this.editedInvestment.investmentAmount < 1) {
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

      // Determine what start date to use - ALWAYS use existing start date to preserve history
      let startDateString: string;
      if (Array.isArray(this.currentInvestment.startDate)) {
        const [year, month, day] = this.currentInvestment.startDate;
        startDateString = `${year}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}`;
      } else {
        startDateString = this.currentInvestment.startDate;
      }

      // Determine next investment date
      let nextInvestmentDateString: string | undefined;

      // Use the edited next investment date (which defaults to current if not changed)
      if (this.editedInvestment.nextInvestmentDate) {
        let dateToAdjust: Date;

        if (typeof this.editedInvestment.nextInvestmentDate === 'string') {
          // Handle ISO string or YYYY-MM-DD
          const datePart = this.editedInvestment.nextInvestmentDate.includes('T') ?
            this.editedInvestment.nextInvestmentDate.split('T')[0] :
            this.editedInvestment.nextInvestmentDate;

          const parts = datePart.split('-');
          const year = parseInt(parts[0]);
          const month = parseInt(parts[1]);
          const day = parseInt(parts[2]);
          dateToAdjust = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
        } else if (Array.isArray(this.editedInvestment.nextInvestmentDate)) {
          const [year, month, day] = this.editedInvestment.nextInvestmentDate;
          dateToAdjust = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
        } else {
          // Fallback if type is unknown, though it should be string or array
          dateToAdjust = new Date();
        }

        const year = dateToAdjust.getUTCFullYear();
        const month = (dateToAdjust.getUTCMonth() + 1).toString().padStart(2, '0');
        const day = dateToAdjust.getUTCDate().toString().padStart(2, '0');
        nextInvestmentDateString = `${year}-${month}-${day}`;
      }

      // Create update request
      const updateRequest: CreateInvestmentScheduleRequest = {
        investmentAmount: this.editedInvestment.investmentAmount!,
        frequency: this.editedInvestment.frequency || this.currentInvestment.frequency,
        startDate: startDateString,
        nextInvestmentDate: nextInvestmentDateString
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
    if (this.currentInvestment.investmentAmount !== this.editedInvestment.investmentAmount) {
      return true;
    }

    // Check frequency
    if (this.currentInvestment.frequency !== this.editedInvestment.frequency) {
      return true;
    }

    // Check next investment date
    // Normalize both dates to YYYY-MM-DD string for comparison
    const currentDate = this.getDateForPicker(this.currentInvestment.nextInvestmentDate);
    const editedDate = this.getDateForPicker(this.editedInvestment.nextInvestmentDate);

    if (currentDate !== editedDate) {
      return true;
    }

    return false;
  }

  /**
   * Calculate estimated trade execution window (1-3 business days after transfer date)
   */
  getEstimatedTradeWindow(): string {
    const transferDate = this.editedInvestment.nextInvestmentDate || this.currentInvestment?.nextInvestmentDate;
    if (!transferDate) return '';

    try {
      let date: Date;

      // Handle array format
      if (Array.isArray(transferDate) && transferDate.length === 3) {
        const [year, month, day] = transferDate;
        date = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
      } else if (typeof transferDate === 'string') {
        const datePart = transferDate.includes('T') ? transferDate.split('T')[0] : transferDate;
        const parts = datePart.split('-');
        const year = parseInt(parts[0]);
        const month = parseInt(parts[1]);
        const day = parseInt(parts[2]);
        date = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
      } else {
        return '';
      }

      if (isNaN(date.getTime())) return '';

      // Calculate trade window: 1-3 business days after transfer
      // "so if someones weekly schedule is friday, they will see the dates for the next monday - wednesday"
      // If today is Friday -> +1 business day is Monday (Day 1). +3 business days is Wednesday (Day 3).
      // Logic: addBusinessDays(1) to addBusinessDays(3)
      const minDate = this.addBusinessDays(date, 1);
      const maxDate = this.addBusinessDays(date, 3);

      return `${this.formatDate(this.dateToArray(minDate), false)} - ${this.formatDate(this.dateToArray(maxDate), false)}`;
    } catch (error) {
      console.error('Error calculating trade window:', error);
      return '';
    }
  }

  /**
   * Add business days to a date (skipping weekends)
   */
  private addBusinessDays(date: Date, days: number): Date {
    const result = new Date(date);
    let added = 0;

    while (added < days) {
      result.setUTCDate(result.getUTCDate() + 1);
      // Skip weekends (0 = Sunday, 6 = Saturday)
      if (result.getUTCDay() !== 0 && result.getUTCDay() !== 6) {
        added++;
      }
    }

    return result;
  }

  /**
   * Convert Date to array format for formatDate method
   */
  private dateToArray(date: Date): number[] {
    return [
      date.getUTCFullYear(),
      date.getUTCMonth() + 1,
      date.getUTCDate()
    ];
  }
}
