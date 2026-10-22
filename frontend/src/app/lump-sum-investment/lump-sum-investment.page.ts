import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { NavController } from '@ionic/angular';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { Subject, takeUntil, debounceTime, distinctUntilChanged, switchMap, forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  IonHeader,
  IonToolbar,
  IonContent,
  IonLabel,
  IonSearchbar,
  IonList,
  IonItem,
  IonSpinner,
  IonNote,
  IonPopover
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  cashOutline,
  checkmarkCircleOutline,
  alertCircleOutline,
  informationCircleOutline,
  chevronDownOutline,
  chevronUpOutline,
  swapHorizontalOutline
} from 'ionicons/icons';
import { InvestmentService } from '../services/investment.service';
import { KeyboardAvoidDirective } from '../directives/keyboard-avoid.directive';
import { PortfolioService, AccountSummary } from '../services/portfolio.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../services/analytics/analytics-error.utils';

interface AcatsTransferResponse {
  transferId: string;
  message: string;
}

interface AlpacaAsset {
  id: string;
  class: string;
  exchange: string;
  symbol: string;
  name: string;
  status: string;
  tradable: boolean;
}

@Component({
  selector: 'app-lump-sum-investment',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonHeader,
    IonToolbar,
    IonContent,
    IonLabel,
    IonSearchbar,
    IonList,
    IonItem,
    IonSpinner,
    IonNote,
    IonPopover,
    KeyboardAvoidDirective
  ],
  templateUrl: './lump-sum-investment.page.html',
  styleUrls: ['./lump-sum-investment.page.scss']
})
export class LumpSumInvestmentPage implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();
  searchInput$ = new Subject<string>();

  // Investment data
  investmentAmount: number | null = null;
  investmentType: 'portfolio' | 'stock' = 'portfolio';
  fundingSource: 'bank' | 'buying_power' = 'bank';
  buyingPower: number = 0;
  selectedStock: string = '';
  selectedStockInfo: AlpacaAsset | null = null;
  isLoading: boolean = false;

  // Stock search
  searchTerm: string = '';
  searchResults: AlpacaAsset[] = [];
  isSearching: boolean = false;
  showSearch: boolean = false;

  // ACATS Transfer
  showTransferOptions: boolean = false;
  transferBrokerageDtc: string = '';
  transferAccountNumber: string = '';
  isProcessingTransfer: boolean = false;

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
    private route: ActivatedRoute,
    private investmentService: InvestmentService,
    private portfolioService: PortfolioService,
    private http: HttpClient,
    private passkeyService: PasskeyService,
    private pinService: PinService,
    private toastService: ToastService,
    private navCtrl: NavController,
    private analytics: AnalyticsService
  ) {
    addIcons({
      cashOutline,
      checkmarkCircleOutline,
      alertCircleOutline,
      informationCircleOutline,
      chevronDownOutline,
      chevronUpOutline,
      swapHorizontalOutline
    });
  }

  ngOnInit() {
    this.route.queryParams.pipe(takeUntil(this.destroy$)).subscribe(params => {
        if (params['mode'] === 'transfer') {
            this.showTransferOptions = true;
        }
    });

    this.loadPortfolioData();

    // Debounced stock search pipe
    this.searchInput$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(term => {
        if (!term || term.length < 2) {
          this.searchResults = [];
          this.isSearching = false;
          return of(null);
        }
        this.isSearching = true;
        const equity$ = this.http.get<AlpacaAsset[]>(
          `${environment.backendApiUrl}/alpaca/assets`,
          {
            headers: this.getAuthHeaders(),
            params: { search: term, asset_class: 'us_equity', status: 'active' }
          }
        ).pipe(catchError(() => of([] as AlpacaAsset[])));
        const etf$ = this.http.get<AlpacaAsset[]>(
          `${environment.backendApiUrl}/alpaca/assets`,
          {
            headers: this.getAuthHeaders(),
            params: { search: term, asset_class: 'etf', status: 'active' }
          }
        ).pipe(catchError(() => of([] as AlpacaAsset[])));
        return forkJoin([equity$, etf$]).pipe(
          catchError(() => of([[] as AlpacaAsset[], [] as AlpacaAsset[]]))
        );
      }),
      takeUntil(this.destroy$)
    ).subscribe(result => {
      this.isSearching = false;
      if (result === null) {
        return;
      }
      const [stocksResponse, etfsResponse] = result as [AlpacaAsset[], AlpacaAsset[]];
      const allResults = [...stocksResponse, ...etfsResponse];
      if (allResults.length > 0) {
        const term = this.searchTerm;
        const termUpper = term.toUpperCase();
        let filteredResults = allResults.filter(asset =>
          asset.tradable &&
          asset.status === 'active' &&
          (asset.symbol.toUpperCase().includes(termUpper) ||
            asset.name.toUpperCase().includes(termUpper))
        );
        filteredResults.sort((a, b) => {
          const aSymbolExact = a.symbol.toUpperCase() === termUpper ? 1 : 0;
          const bSymbolExact = b.symbol.toUpperCase() === termUpper ? 1 : 0;
          if (aSymbolExact !== bSymbolExact) return bSymbolExact - aSymbolExact;
          const aSymbolPartial = a.symbol.toUpperCase().includes(termUpper) ? 1 : 0;
          const bSymbolPartial = b.symbol.toUpperCase().includes(termUpper) ? 1 : 0;
          if (aSymbolPartial !== bSymbolPartial) return bSymbolPartial - aSymbolPartial;
          return a.symbol.localeCompare(b.symbol);
        });
        this.searchResults = filteredResults.slice(0, 50);
      } else {
        this.searchResults = [];
      }
    });
  }

  loadPortfolioData() {
    this.portfolioService.getPortfolioDashboard()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          if (data && data.summary) {
            this.buyingPower = data.summary.buyingPower;
          }
        },
        error: (err) => {
          console.error('Failed to load portfolio data', err);
        }
      });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  goBack() {
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  onAmountChange() {
    // Validate amount
    if (this.investmentAmount && this.investmentAmount < 0) {
      this.investmentAmount = 0;
    }
  }

  validateInvestmentAmount(event: any) {
    let value = event.target.value;
    
    if (!value) {
      return;
    }
    
    // Convert to string to handle validation
    const strValue = value.toString();
    
    // Check if there's a decimal point
    if (strValue.includes('.')) {
      const parts = strValue.split('.');
      const integerPart = parts[0];
      const decimalPart = parts[1] || '';
      
      // Limit integer part to 5 digits
      if (integerPart.length > 5) {
        const limitedInteger = integerPart.substring(0, 5);
        this.investmentAmount = parseFloat(`${limitedInteger}.${decimalPart}`);
        event.target.value = `${limitedInteger}.${decimalPart}`;
        return;
      }
      
      // Limit decimal part to 2 digits
      if (decimalPart.length > 2) {
        const limitedDecimal = decimalPart.substring(0, 2);
        this.investmentAmount = parseFloat(`${integerPart}.${limitedDecimal}`);
        event.target.value = `${integerPart}.${limitedDecimal}`;
        return;
      }
    } else {
      // No decimal point - just limit to 5 digits
      if (strValue.length > 5) {
        const limited = strValue.substring(0, 5);
        this.investmentAmount = parseFloat(limited);
        event.target.value = limited;
        return;
      }
    }
    
    // Update the model
    this.investmentAmount = value ? parseFloat(value) : null;
  }

  onInvestmentTypeChange() {
    // Reset stock selection when switching to portfolio
    if (this.investmentType === 'portfolio') {
      this.selectedStock = '';
      this.selectedStockInfo = null;
      this.showSearch = false;
    } else {
      this.showSearch = true;
    }
  }

  // Input handler: feed the debounced search Subject
  onSearchInput(event: any): void {
    const value: string = event.target?.value ?? '';
    this.searchInput$.next(value);
  }

  selectStock(asset: AlpacaAsset) {
    this.selectedStock = asset.symbol;
    this.selectedStockInfo = asset;
    this.searchTerm = '';
    this.searchResults = [];
  }

  private getAuthHeaders(): HttpHeaders {
    const token = JwtTokenUtils.getValidJwtToken();
    return new HttpHeaders({
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    });
  }

  async submitAcatsTransfer() {
    if (!this.transferBrokerageDtc || !this.transferAccountNumber) {
       this.analytics.trackErrorShown('validation', 'portfolio', 'toast', null, false);
       this.toastService.showToast('Please select a brokerage and enter your account number.', 'warning');
       return;
    }

    this.isLoading = true;

    try {
        const transferData = {
            dtcNumber: this.transferBrokerageDtc,
            accountNumber: this.transferAccountNumber
        };
        
        console.log('Initiating ACATS transfer:', transferData);
        const transferResponse = await this.investmentService.initiateAcatsTransfer(transferData).toPromise() as AcatsTransferResponse;

        this.toastService.showToast(`Transfer submitted, ref: ${transferResponse.transferId}`, 'success', 3000);
        
        // Reset form after successful transfer initiation
        setTimeout(() => {
            this.transferBrokerageDtc = '';
            this.transferAccountNumber = '';
        }, 1000);

    } catch (error) {
        console.error('Error submitting ACATS transfer:', error);
        this.analytics.trackErrorShown(
          isNetworkFailure(error) ? 'network' : 'save_failed',
          'portfolio',
          'toast',
          httpStatusOf(error),
          false,
        );
        this.toastService.showToast('Transfer request failed. Please try again.', 'danger');
    } finally {
        this.isLoading = false;
    }
  }

  async makeInvestment() {
    // If we have separate buttons, this function should strictly handle investment logic
    if (!this.validateInvestment()) {
      // Fired here rather than inside validateInvestment's five branches: they all show the same
      // kind of toast and would produce an identical payload. One of those messages quotes the
      // user's buying power, which is exactly what must not be sent.
      this.analytics.trackErrorShown('validation', 'portfolio', 'toast', null, false);
      return;
    }

    this.isLoading = true;

    try {
      // Step-up authentication check
      const hasPin = await this.pinService.hasPin();

      if (hasPin) {
        const pinVerified = await this.pinService.promptPin('verify');
        if (!pinVerified) {
          this.analytics.trackErrorShown('auth_required', 'portfolio', 'toast', null, false);
          this.toastService.showToast('Authentication required to make investment.', 'warning');
          this.isLoading = false;
          return;
        }
      }

      // Prepare request payload. clientUuid is minted once per tap (decision 18): a retried
      // request carries the same id and the backend answers with the first row instead of
      // queueing a second investment.
      const requestData: any = {
        amount: this.investmentAmount,
        type: this.investmentType,
        fundingSource: this.fundingSource,
        clientUuid: crypto.randomUUID()
      };

      // Add specific stock symbol if investing in individual stock
      if (this.investmentType === 'stock' && this.selectedStock) {
        requestData.symbol = this.selectedStock;
      }

      // Make actual API call to backend
      this.http.post(`${environment.backendApiUrl}/investments/execute`, requestData, {
        headers: this.getAuthHeaders()
      }).pipe(
        takeUntil(this.destroy$)
      ).subscribe({
        next: (response: any) => {
          this.isLoading = false;

          if (response.success) {
            // Use the message from backend response
            this.toastService.showToast(
              response.message || 'Investment initiated successfully!',
              'success'
            );

            // Reset form after successful investment
            setTimeout(() => {
              this.investmentAmount = null;
              this.selectedStock = '';
              this.selectedStockInfo = null;
              this.investmentType = 'portfolio';
            }, 2000);
          } else {
            // HTTP 200 with a failure in the body, so there is no status to send.
            this.analytics.trackErrorShown('save_failed', 'portfolio', 'toast', null, false);
            this.toastService.showToast(response.message || 'Failed to process investment', 'danger');
          }
        },
        error: (error) => {
          this.isLoading = false;
          console.error('Investment error:', error);
          this.analytics.trackErrorShown(
            isNetworkFailure(error) ? 'network' : 'save_failed',
            'portfolio',
            'toast',
            httpStatusOf(error),
            false,
          );

          let errorMessage = 'Failed to process investment. Please try again.';
          if (error.error?.message) {
            errorMessage = error.error.message;
          } else if (error.message) {
            errorMessage = error.message;
          }

          this.toastService.showToast(errorMessage, 'danger');
        }
      });
    } catch (error) {
      console.error('Error making investment:', error);
      // Nothing reached the network on this path (it is the step-up prompt or payload build), so
      // the category stays 'unknown' rather than pretending to know the cause.
      this.analytics.trackErrorShown('unknown', 'portfolio', 'toast', httpStatusOf(error), false);
      this.toastService.showToast('An unexpected error occurred.', 'danger');
      this.isLoading = false;
    }
  }

  private validateInvestment(): boolean {
    if (!this.investmentAmount || this.investmentAmount <= 0) {
      this.toastService.showToast('Please enter a valid investment amount', 'danger');
      return false;
    }

    if (this.investmentAmount < 1) {
      this.toastService.showToast('Minimum investment amount is $1', 'danger');
      return false;
    }

    if (this.investmentAmount > 1000000) {
      this.toastService.showToast('Maximum investment amount is $1,000,000', 'danger');
      return false;
    }

    if (this.fundingSource === 'buying_power' && this.investmentAmount > this.buyingPower) {
      this.toastService.showToast(`Insufficient buying power. You only have ${this.formatCurrency(this.buyingPower)} available.`, 'danger');
      return false;
    }

    if (this.investmentType === 'stock' && !this.selectedStock) {
      this.toastService.showToast('Please select a stock to invest in', 'danger');
      return false;
    }

    return true;
  }

  getSelectedStockInfo() {
    return this.selectedStockInfo;
  }

  getInvestmentTarget(): string {
    if (this.investmentType === 'portfolio') {
      return 'your portfolio';
    } else if (this.selectedStockInfo) {
      return this.selectedStockInfo.symbol;
    }
    return 'selected investment';
  }

  getSharesCount(): number {
    // For real stock pricing, you'd need to make another API call to get current price
    // For now, we'll show that it would be calculated
    return 0; // This would be calculated with real-time price data
  }

  formatCurrency(amount: number): string {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(amount);
  }

  getProjectedGrowth(): number {
    // 10% annual growth compounded for 10 years, accounting for DRIP
    const annualRate = 0.10;
    const years = 10;
    return (this.investmentAmount || 0) * Math.pow(1 + annualRate, years);
  }
}
