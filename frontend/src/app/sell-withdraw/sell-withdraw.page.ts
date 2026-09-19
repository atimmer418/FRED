import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { Subject, takeUntil } from 'rxjs';
import {
  IonHeader,
  IonToolbar,
  IonContent,
  IonSpinner,
  IonRange,
  IonToggle,
  IonPopover,
  AlertController,
  ModalController
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  pieChartOutline,
  checkmarkCircle,
  checkmarkCircleOutline,
  radioButtonOff,
  cashOutline,
  trendingUpOutline,
  trendingDownOutline,
  walletOutline,
  timeOutline,
  warningOutline,
  informationCircleOutline,
  arrowForwardOutline,
  pricetagOutline,
  closeCircleOutline,
  trashOutline,
  alertCircleOutline
} from 'ionicons/icons';

import { TradingService, SellRequest, WithdrawRequest } from '../services/trading.service';
import { PortfolioService, PortfolioDashboardData, Position, AccountSummary } from '../services/portfolio.service';
import { ToastService } from '../services/toast.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { PasskeyPromptComponent } from '../components/passkey-prompt/passkey-prompt.component';
import { KeyboardAvoidDirective } from '../directives/keyboard-avoid.directive';

@Component({
  selector: 'app-sell-withdraw',
  templateUrl: './sell-withdraw.page.html',
  styleUrls: ['./sell-withdraw.page.scss'],
  imports: [
    CommonModule,
    FormsModule,
    IonHeader,
    IonToolbar,
    IonContent,
    IonSpinner,
    IonRange,
    IonToggle,
    IonPopover,
    KeyboardAvoidDirective
  ]
})
export class SellWithdrawPage implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // State
  public isLoading = true;
  public positions: Position[] = [];
  public balance: AccountSummary | null = null;
  public dashboardData: PortfolioDashboardData | null = null;
  public selectedPosition: Position | null = null;
  public sellPercentage = 25;
  public withdrawAmount: number | null = null;

  // UI state
  public isSelling = false;
  public isWithdrawing = false;

  // Quick select options
  public quickPercentages = [25, 50, 75, 100];
  public quickWithdrawAmounts = [100, 500, 1000, 2500];

  public maxSellPercentage = 100;

  // DRIP state
  public dripEnabled = true;
  public dripLoading = false;
  public dripToggling = false;

  // Close account state
  public isClosingAccount = false;
  public showCloseConfirmation = false;

  constructor(
    private router: Router,
    private tradingService: TradingService,
    private portfolioService: PortfolioService,
    private alertController: AlertController,
    private modalController: ModalController,
    private toastService: ToastService,
    private passkeyService: PasskeyService,
    private pinService: PinService,
    private navCtrl: NavController
  ) {
    addIcons({
      pieChartOutline,
      checkmarkCircle,
      checkmarkCircleOutline,
      radioButtonOff,
      cashOutline,
      trendingUpOutline,
      trendingDownOutline,
      walletOutline,
      timeOutline,
      warningOutline,
      informationCircleOutline,
      arrowForwardOutline,
      pricetagOutline,
      closeCircleOutline,
      trashOutline,
      alertCircleOutline
    });
  }

  ngOnInit() {
    this.loadData();
  }

  ionViewWillEnter() {
    this.loadData();
  }

  goBack() {
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private async loadData() {
    try {
      this.isLoading = true;

      // Load dashboard data from PortfolioService
      const dashboardData = await this.portfolioService.getPortfolioDashboard().toPromise();

      if (dashboardData) {
        this.dashboardData = dashboardData;
        // Filter out dust positions (negligible value or quantity)
        this.positions = (dashboardData.positions || []).filter(p => {
          const hasValue = p.marketValue >= 0.01;
          const hasQty = p.quantity >= 0.0001;
          return hasValue && hasQty;
        });
        this.balance = dashboardData.summary || null;
      }

      // Load DRIP status
      this.loadDripStatus();

    } catch (error) {
      console.error('Error loading trading data:', error);
      this.showToast('Failed to load portfolio data', 'danger');
    } finally {
      this.isLoading = false;
    }
  }

  private loadDripStatus() {
    this.dripLoading = true;
    this.tradingService.getDripStatus().subscribe({
      next: (response) => {
        this.dripEnabled = response.dripEnabled;
        this.dripLoading = false;
      },
      error: (err) => {
        console.error('Error loading DRIP status:', err);
        this.dripLoading = false;
      }
    });
  }

  selectPosition(position: Position) {
    // Calculate pending percentage first to check for 100% pending (rounding issues)
    let pendingPercent = 0;
    if (position.quantityAvailable !== undefined && position.quantity > 0) {
      pendingPercent = Math.round(((position.quantity - position.quantityAvailable) / position.quantity) * 100);
    }

    // Check if position has available quantity or is effectively 100% pending
    if ((position.quantityAvailable !== undefined && position.quantityAvailable <= 0 && position.quantity > 0) || pendingPercent === 100) {
      this.showToast('This full position is pending sale.', 'warning');
      return;
    }

    this.selectedPosition = position;

    // Calculate max sell percentage based on available quantity
    if (position.quantityAvailable !== undefined && position.quantity > 0) {
      // Use Math.round to handle floating point precision issues (e.g. 74.99999% should be 75%)
      this.maxSellPercentage = Math.round((position.quantityAvailable / position.quantity) * 100);
    } else {
      this.maxSellPercentage = 100;
    }

    // Reset to default (25%) or max if max is lower than 25%
    this.sellPercentage = Math.min(25, this.maxSellPercentage);

    // If partially pending, warn the user but allow selection
    if (position.quantityAvailable !== undefined && position.quantityAvailable < position.quantity) {
      this.showToast(`Note: ${pendingPercent}% of your position is currently pending sale.`, 'warning');
    }
  }

  setSellPercentage(percentage: number) {
    this.sellPercentage = percentage;
  }

  setWithdrawAmount(amount: number) {
    this.withdrawAmount = amount;
  }

  calculateSellAmount(): number {
    if (!this.selectedPosition) return 0;
    const marketValue = this.selectedPosition.marketValue;
    return (marketValue * this.sellPercentage) / 100;
  }



  get maxWithdrawAmount(): number {
    if (this.balance) {
      // Use withdrawableCash if available (it's the most accurate for withdrawals)
      if (typeof this.balance.withdrawableCash !== 'undefined') {
        return this.balance.withdrawableCash;
      }
      // Fallback to cash if withdrawableCash is not available
      if (typeof this.balance.cash !== 'undefined') {
        return this.balance.cash;
      }
      // Fallback to buyingPower if cash is not available
      if (this.balance.buyingPower) return this.balance.buyingPower;
    }
    return 0;
  }

  async sellPosition() {
    if (!this.selectedPosition || this.isSelling) return;

    try {
      // Step-up authentication check
      const hasPin = await this.pinService.hasPin();
      
      if (hasPin) {
        const pinVerified = await this.pinService.promptPin('verify');
        if (!pinVerified) {
          this.showToast('Authentication required to sell positions.', 'warning');
          return;
        }
      }

      this.isSelling = true;
      const symbolToSell = this.selectedPosition.symbol;
      const isFullSell = this.sellPercentage === 100;
      const estimatedProceeds = this.calculateSellAmount();

      const sellRequest = {
        symbol: symbolToSell,
        percentage: this.sellPercentage
      };

      const response = await this.tradingService.sellByPercentage(sellRequest).toPromise();

      if (response?.success) {
        this.showToast(`Successfully placed sell order for ${this.sellPercentage}% of ${symbolToSell}`, 'success');
        this.selectedPosition = null; // Clear selection

        // Optimistic update: if 100% sell, remove from list immediately
        if (isFullSell) {
          this.positions = this.positions.filter(p => p.symbol !== symbolToSell);
        }

        // Optimistic update: update buying power immediately
        if (this.balance) {
          this.balance.buyingPower += estimatedProceeds;
        }

        // Add a delay before reloading to allow order to fill
        setTimeout(async () => {
          await this.loadData();
        }, 1000);

      } else {
        // Gentle error message instead of raw backend error
        console.error('Sell error:', response?.error);
        this.showToast('Unable to process sale. You may have pending orders for this position.', 'danger');
      }

    } catch (error) {
      console.error('Error selling position:', error);
      this.showToast('Unable to process sale. Please try again later.', 'danger');
    } finally {
      this.isSelling = false;
    }
  }



  async withdrawCash() {
    if (!this.withdrawAmount || this.isWithdrawing || this.withdrawAmount > this.maxWithdrawAmount) return;

    try {
      // Step-up authentication check
      const hasPin = await this.pinService.hasPin();

      if (hasPin) {
        const pinVerified = await this.pinService.promptPin('verify');
        if (!pinVerified) {
          this.showToast('Authentication required to withdraw funds.', 'warning');
          return;
        }
      }

      this.isWithdrawing = true;

      const withdrawRequest = {
        amount: this.withdrawAmount
      };

      const response = await this.tradingService.withdrawCash(withdrawRequest).toPromise();

      if (response?.success) {
        this.showToast(response.message || `Withdrawal of ${this.formatCurrency(this.withdrawAmount)} initiated`, 'success');
        this.withdrawAmount = null; // Clear input
        await this.loadData(); // Refresh data
      } else {
        this.showToast(response?.error || 'Failed to initiate withdrawal', 'danger');
      }

    } catch (error) {
      console.error('Error withdrawing cash:', error);
      this.showToast('Failed to initiate withdrawal', 'danger');
    } finally {
      this.isWithdrawing = false;
    }
  }

  toggleDrip(event: any) {
    // Ignore ionChange events fired during programmatic [checked] updates
    if (this.dripLoading || this.dripToggling) return;

    const newValue: boolean = event?.detail?.checked ?? !this.dripEnabled;

    // No-op if the value hasn't actually changed
    if (newValue === this.dripEnabled) return;

    this.dripToggling = true;

    this.tradingService.setDripEnabled(newValue).subscribe({
      next: (response) => {
        this.dripEnabled = response.dripEnabled;
        this.showToast(
          response.dripEnabled
            ? 'DRIP enabled. Dividends will be reinvested automatically.'
            : 'DRIP disabled. Dividends will stay as cash.',
          'success'
        );
        this.dripToggling = false;
      },
      error: (err) => {
        console.error('Error toggling DRIP:', err);
        // Revert the toggle visually
        this.dripEnabled = !newValue;
        this.showToast('Failed to update DRIP setting.', 'danger');
        this.dripToggling = false;
      }
    });
  }

  formatCurrency(amount: string | number | null | undefined): string {
    if (!amount) return '$0.00';
    return this.tradingService.formatCurrency(amount);
  }

  formatPercentage(percentage: string | number): string {
    return this.tradingService.formatPercentage(percentage);
  }

  isPositiveGain(percentage: string | number): boolean {
    const num = typeof percentage === 'string' ? parseFloat(percentage) : percentage;
    return num >= 0;
  }

  isFullyPending(position: Position): boolean {
    if (position.quantityAvailable === undefined) return false;
    if (position.quantityAvailable <= 0) return true;
    if (position.quantity <= 0) return false;

    const pendingPercent = Math.round(((position.quantity - position.quantityAvailable) / position.quantity) * 100);
    return pendingPercent === 100;
  }

  getWithdrawableCash(): number {
    if (this.balance?.withdrawableCash !== undefined) {
      return this.balance.withdrawableCash;
    }
    return this.balance?.cash || 0;
  }

  getSettledCash(): number {
    return this.balance?.cash || 0;
  }

  getEstimatedHoldReleaseDate(): string | null {
    if (!this.dashboardData?.recentTransactions) return null;
    
    // Find recent deposits (positive amount and type is CSD/ACH)
    // We look for transactions with positive amount and type 'CSD' (Cash Settlement) or 'ACH'
    // which indicate deposits. 'FILL' are trades.
    const deposits = this.dashboardData.recentTransactions.filter(t => 
      t.amount > 0 && 
      (t.type === 'CSD' || t.type === 'ACH' || t.type === 'JNLS') &&
      t.date && !isNaN(new Date(t.date).getTime()) // Ensure date is valid
    );
    
    if (deposits.length > 0) {
      // Sort by date descending to get the latest
      deposits.sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime());
      const lastDeposit = deposits[0];
      
      const depositDate = new Date(lastDeposit.date);
      
      // Double check validity
      if (isNaN(depositDate.getTime())) return null;

      // Add 6 business days to be safe (Alpaca states 4-6 business days)
      const releaseDate = this.addBusinessDays(depositDate, 6);
      
      return releaseDate.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    }
    return null;
  }

  private addBusinessDays(date: Date, days: number): Date {
    const result = new Date(date);
    let addedDays = 0;
    while (addedDays < days) {
      result.setDate(result.getDate() + 1);
      const dayOfWeek = result.getDay();
      // 0 is Sunday, 6 is Saturday
      if (dayOfWeek !== 0 && dayOfWeek !== 6) {
        addedDays++;
      }
    }
    return result;
  }

  // ==================== Close Account ====================

  get canCloseAccount(): boolean {
    return this.positions.length === 0 && this.getWithdrawableCash() <= 0;
  }

  async initiateCloseAccount() {
    if (!this.canCloseAccount || this.isClosingAccount) return;

    try {
      // Step-up authentication via passkey (WebAuthn)
      const userEmail = localStorage.getItem('userEmail') || undefined;

      const modal = await this.modalController.create({
        component: PasskeyPromptComponent,
        componentProps: { userEmail },
        backdropDismiss: true,
        cssClass: 'full-screen-modal'
      });

      await modal.present();
      const { data } = await modal.onDidDismiss();

      if (!data?.authenticated) {
        this.showToast('Authentication required to close your account.', 'warning');
        return;
      }

      // Show the inline confirmation
      this.showCloseConfirmation = true;
    } catch (error) {
      console.error('Error during close account auth:', error);
      this.showToast('Authentication failed. Please try again.', 'danger');
    }
  }

  cancelCloseAccount() {
    this.showCloseConfirmation = false;
  }

  async confirmCloseAccount() {
    if (this.isClosingAccount) return;

    try {
      this.isClosingAccount = true;

      const response = await this.tradingService.closeAccount().toPromise();

      if (response?.success) {
        this.showToast('Your account has been permanently closed.', 'success');
        // Clear any stored auth tokens and navigate to root
        localStorage.clear();
        sessionStorage.clear();
        this.router.navigateByUrl('/', { replaceUrl: true });
      } else {
        this.showToast(response?.error || 'Failed to close account. Please try again.', 'danger');
      }
    } catch (error) {
      console.error('Error closing account:', error);
      this.showToast('Failed to close account. Please ensure all positions are sold and all cash is withdrawn.', 'danger');
    } finally {
      this.isClosingAccount = false;
      this.showCloseConfirmation = false;
    }
  }

  private showToast(message: string, color: string = 'primary') {
    this.toastService.showToast(message, color);
  }

  private async showConfirmation(header: string, message: string): Promise<boolean> {
    return new Promise((resolve) => {
      const confirmed = confirm(`${header}\n\n${message}`);
      resolve(confirmed);
    });
  }

  // Helper methods for WebAuthn
}