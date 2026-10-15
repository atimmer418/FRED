import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import {
  IonContent, IonHeader, IonToolbar, IonFooter, IonSpinner, NavController
} from '@ionic/angular/standalone';
import { AuthService } from '../../services/auth.service';
import { InvestmentService } from '../../services/investment.service'; // Import this
import { environment } from '../../../environments/environment';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';

export interface InvestmentSchedule {
  payFrequency: string;
  investmentAmount: number;
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

@Component({
  selector: 'app-investment-schedule',
  templateUrl: './investment-schedule.component.html',
  styleUrls: ['./investment-schedule.component.scss'],
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonContent, IonHeader, IonToolbar, IonFooter, IonSpinner,
    KeyboardAvoidDirective
  ]
})
export class InvestmentScheduleComponent implements OnInit {
  // User's financial data from initial survey
  monthlyGoal: number = 0;

  isSubmitting: boolean = false;
  isReady: boolean = false;
  
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
    private http: HttpClient
  ) {}

  // Investment schedule configuration
  schedule: InvestmentSchedule = {
    payFrequency: 'BIWEEKLY',
    investmentAmount: 0,
    startDate: '', // This will be the next pay date
    isEnabled: true
  };

  // Frequency options with descriptions and calculation details
  frequencyOptions = [
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
    this.loadUserFinancialData();

    // Let Manrope render before revealing content
    setTimeout(() => this.isReady = true, 50);

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
          this.schedule.investmentAmount = savedSchedule.investmentAmount;
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
    console.log('[InvestmentScheduleComponent] setFinancialData — monthlyInvestment:', progress?.monthlyInvestment);

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

  onFrequencyChange(frequency: string) {
    this.schedule.payFrequency = frequency;
    this.calculateRecommendedAmount();
    // Update the suggested pay date based on the new frequency
    this.schedule.startDate = this.getInvestmentDate();
    // Angular's change detection will automatically update the description when these properties change
    console.log('[InvestmentScheduleComponent] Frequency changed to:', frequency, 'Start date set to:', this.schedule.startDate);
  }

  onAmountChange() {
    // Amount changes don't affect the schedule description
  }

  onStartDateChange(date: string) {
    // Check if the selected date is a weekend
    const selectedDate = new Date(date);
    const dayOfWeek = selectedDate.getDay(); // 0 = Sunday, 6 = Saturday
    
    if (dayOfWeek === 0 || dayOfWeek === 6) {
      // Find next business day
      const nextBusinessDay = this.findNextBusinessDay(selectedDate);
      const adjustedDate = nextBusinessDay.toISOString().split('T')[0];
      
      console.warn('[InvestmentSchedule] Weekend date selected, adjusting to next business day:', date, '->', adjustedDate);
      this.schedule.startDate = adjustedDate;
      
      // Show warning to user
      this.showDateAdjustmentWarning(selectedDate, nextBusinessDay);
    } else {
      this.schedule.startDate = date;
    }
  }

  private findNextBusinessDay(date: Date): Date {
    const nextDay = new Date(date);
    
    while (this.isWeekend(nextDay)) {
      nextDay.setDate(nextDay.getDate() + 1);
    }
    
    return nextDay;
  }

  private isWeekend(date: Date): boolean {
    const dayOfWeek = date.getDay();
    return dayOfWeek === 0 || dayOfWeek === 6; // Sunday or Saturday
  }

  private showDateAdjustmentWarning(originalDate: Date, adjustedDate: Date): void {
    // You can implement a toast or alert here if available in your Ionic setup
    // For now, just console warn
    const originalStr = originalDate.toLocaleDateString();
    const adjustedStr = adjustedDate.toLocaleDateString();
    console.warn(`Date adjusted from ${originalStr} to ${adjustedStr} (weekend dates are not allowed for investments)`);
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

  getMinDate(): string {
    // Set minimum date to tomorrow
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    return tomorrow.toISOString().split('T')[0];
  }

  calculateRecommendedAmount() {
    if (this.monthlyGoal <= 0) return;

    const selectedFrequency = this.getSelectedFrequencyDetails();

    if (selectedFrequency) {
      this.schedule.investmentAmount = Math.round(
        this.monthlyGoal / selectedFrequency.paychecksPerMonth
      );
    }
  }

  getSelectedFrequencyDetails() {
    return this.frequencyOptions.find(f => f.value === this.schedule.payFrequency);
  }

  getMonthlyProjection(): number {
    const frequencyDetails = this.getSelectedFrequencyDetails();
    if (!frequencyDetails) return 0;
    return this.schedule.investmentAmount * frequencyDetails.paychecksPerMonth;
  }

  getMonthlyShortfall(): number {
    return Math.max(0, Math.round(this.monthlyGoal - this.getMonthlyProjection()));
  }

  getAnnualProjection(): number {
    return this.getMonthlyProjection() * 12;
  }

  private getNextMonday(): string {
    const today = new Date();
    const daysUntilMonday = (8 - today.getDay()) % 7 || 7;
    const nextMonday = new Date(today);
    nextMonday.setDate(today.getDate() + daysUntilMonday);
    
    // Format date as YYYY-MM-DD in local time to avoid timezone issues
    const year = nextMonday.getFullYear();
    const month = String(nextMonday.getMonth() + 1).padStart(2, '0');
    const day = String(nextMonday.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private getNextNextMonday(): string {
    const today = new Date();
    const daysUntilMonday = (8 - today.getDay()) % 7 || 7;
    const nextNextMonday = new Date(today);
    nextNextMonday.setDate(today.getDate() + daysUntilMonday + 7);
    
    // Format date as YYYY-MM-DD in local time to avoid timezone issues
    const year = nextNextMonday.getFullYear();
    const month = String(nextNextMonday.getMonth() + 1).padStart(2, '0');
    const day = String(nextNextMonday.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private getInvestmentDate(): string {
    const today = new Date();
    const frequency = this.schedule.payFrequency;

    switch (frequency) {
      case 'WEEKLY':
        // Next Monday
        return this.getNextMonday();
      
      case 'BIWEEKLY':
        // Monday after next Monday (2 weeks out)
        return this.getNextNextMonday();
      
      case 'SEMI_MONTHLY':
        // Next 1st or 15th (whichever comes first)
        return this.getNextSemiMonthlyDate();
      
      case 'MONTHLY':
        // Next 1st of the month
        return this.getNextFirstOfMonth();
      
      default:
        // Fallback to next business day
        const tomorrow = new Date(today);
        tomorrow.setDate(tomorrow.getDate() + 1);
        const nextBusinessDay = this.findNextBusinessDay(tomorrow);
        
        // Format date as YYYY-MM-DD in local time to avoid timezone issues
        const year = nextBusinessDay.getFullYear();
        const month = String(nextBusinessDay.getMonth() + 1).padStart(2, '0');
        const day = String(nextBusinessDay.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
    }
  }

  private getNextSemiMonthlyDate(): string {
    const today = new Date();
    const currentDay = today.getDate();
    const currentMonth = today.getMonth();
    const currentYear = today.getFullYear();

    let nextDate: Date;

    if (currentDay < 15) {
      // Before 15th — next date is 15th of this month
      nextDate = new Date(currentYear, currentMonth, 15);
    } else {
      // On or after 15th — next date is 1st of next month
      nextDate = new Date(currentYear, currentMonth + 1, 1);
    }

    // Format date as YYYY-MM-DD in local time to avoid timezone issues
    const year = nextDate.getFullYear();
    const month = String(nextDate.getMonth() + 1).padStart(2, '0');
    const day = String(nextDate.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private getNextFirstOfMonth(): string {
    const today = new Date();
    const currentMonth = today.getMonth();
    const currentYear = today.getFullYear();
    
    // If today is the 1st, go to next month's 1st. Otherwise, go to next month's 1st
    const nextFirstOfMonth = new Date(currentYear, currentMonth + 1, 1);
    
    // Format date as YYYY-MM-DD in local time to avoid timezone issues
    const year = nextFirstOfMonth.getFullYear();
    const month = String(nextFirstOfMonth.getMonth() + 1).padStart(2, '0');
    const day = String(nextFirstOfMonth.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  /**
   * Calculate the next investment dates based on the pay frequency and start date
   */
  getNextInvestmentDates(count: number = 3): string[] {
    if (!this.schedule.startDate) return [];

    const startDate = new Date(this.schedule.startDate);
    const dates: string[] = [];
    const frequency = this.getSelectedFrequencyDetails();

    if (!frequency) return [];

    for (let i = 0; i < count; i++) {
      let nextDate = new Date(startDate);

      if (frequency.value === 'WEEKLY' || frequency.value === 'BIWEEKLY') {
        // Add days for weekly/biweekly
        const daysToAdd = i * (frequency.daysBetween || 14);
        nextDate.setDate(startDate.getDate() + daysToAdd);
      } else if (frequency.value === 'SEMI_MONTHLY') {
        // For semi-monthly, alternate between 1st and 15th
        const monthsToAdd = Math.floor(i / 2);
        nextDate.setMonth(startDate.getMonth() + monthsToAdd);
        
        if (i % 2 === 0) {
          // Keep original date (could be 1st or 15th)
          nextDate.setDate(startDate.getDate());
        } else {
          // Switch between 1st and 15th
          nextDate.setDate(startDate.getDate() === 1 ? 15 : 1);
        }
      } else if (frequency.value === 'MONTHLY') {
        // Add months for monthly
        nextDate.setMonth(startDate.getMonth() + i);
      }

      dates.push(nextDate.toISOString().split('T')[0]);
    }

    return dates;
  }

  /**
   * Get a user-friendly description of when investments will happen
   */
  getInvestmentScheduleDescription(): string {
    const frequency = this.getSelectedFrequencyDetails();
    if (!frequency || !this.schedule.startDate) {
      console.log('[InvestmentScheduleComponent] Missing data for description - frequency:', frequency, 'startDate:', this.schedule.startDate);
      return '';
    }

    // Parse the date string ensuring it's treated as local time, not UTC
    const dateParts = this.schedule.startDate.split('-');
    const startDate = new Date(parseInt(dateParts[0]), parseInt(dateParts[1]) - 1, parseInt(dateParts[2]));
    
    console.log('[InvestmentScheduleComponent] Calculating description - frequency:', frequency.value, 'startDate:', this.schedule.startDate, 'parsed date:', startDate, 'day of week:', startDate.getDay());
    
    switch (frequency.value) {
      case 'WEEKLY':
        const weekday = startDate.toLocaleDateString('en-US', { weekday: 'long' });
        return `Every ${weekday}`;
      
      case 'BIWEEKLY':
        const biweeklyDay = startDate.toLocaleDateString('en-US', { weekday: 'long' });
        return `Every other ${biweeklyDay}`;
      
      case 'SEMI_MONTHLY':
        return `Every 1st and 15th of the month`;
      
      case 'MONTHLY':
        const monthlyDay = startDate.getDate();
        const monthlyDayWithSuffix = this.getDayWithOrdinalSuffix(monthlyDay);
        return `Every ${monthlyDayWithSuffix} of the month`;
      
      default:
        return '';
    }
  }

  /**
   * Helper function to add ordinal suffix to day numbers (1st, 2nd, 3rd, etc.)
   */
  private getDayWithOrdinalSuffix(day: number): string {
    const lastDigit = day % 10;
    const lastTwoDigits = day % 100;
    
    if (lastTwoDigits >= 11 && lastTwoDigits <= 13) {
      return `${day}th`;
    }
    
    switch (lastDigit) {
      case 1:
        return `${day}st`;
      case 2:
        return `${day}nd`;
      case 3:
        return `${day}rd`;
      default:
        return `${day}th`;
    }
  }

  canProceed(): boolean {
    if (this.schedule.investmentAmount <= 0 || this.schedule.startDate === '') return false;
    if (this.showTransferOptions && (!this.transferBrokerageDtc || !this.transferAccountNumber)) return false;
    return true;
  }

  goToStockPreferences(): void {
    console.log('[InvestmentScheduleComponent] Proceeding to investment confirmation with schedule:', this.schedule);
    this.isSubmitting = true;

    // Prepare data for backend - match CreateInvestmentScheduleRequest DTO
    const investmentScheduleData = {
      investmentAmount: this.schedule.investmentAmount,
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
          // Show error message to user
          alert('Error saving investment schedule. Please try again.');
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