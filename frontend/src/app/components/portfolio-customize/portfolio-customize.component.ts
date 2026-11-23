import { Component, DestroyRef, ElementRef, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { NavController, ViewWillLeave } from '@ionic/angular';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { trigger, style, animate, transition } from '@angular/animations';
import { environment } from '../../../environments/environment';
import { ToastService } from '../../services/toast.service';
import { PasskeyService } from '../../services/passkey.service';
import { PinService } from '../../services/pin.service';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';
import { Subject, debounceTime, distinctUntilChanged, switchMap, forkJoin, of, takeUntil } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import {
  IonHeader, IonToolbar, IonContent, IonButton, IonIcon,
  IonList, IonItem, IonLabel, IonReorderGroup,
  IonItemSliding, IonItemOptions, IonItemOption, IonSpinner, IonSearchbar, IonBadge
} from '@ionic/angular/standalone';
import { AmountTrayHost, TrayHostField } from '../plan-amount-tray/amount-tray-host';
import { AmountTraySlotComponent } from '../plan-amount-tray/amount-tray-slot.component';
import { PlanAmountScale } from '../../utils/plan-amount-scale';
import { SkeletonComponent } from '../skeleton/skeleton.component';

/** Every row's allocation: whole percents, 0 to 100; the minus and plus move by one. */
const ALLOCATION_SCALE: PlanAmountScale = { min: 0, max: 100, step: 1, fine: 1 };

interface PortfolioItem {
  symbol: string;
  name: string;
  percentage: number;
  assetType?: string;
}

interface PortfolioResponse {
  id: number;
  name: string;
  totalPercentage: number;
  isDefault: boolean;
  portfolioItems: PortfolioItemResponse[];
  createdAt: string;
  updatedAt: string;
}

interface PortfolioItemResponse {
  id: number;
  symbol: string;
  name: string;
  percentage: number;
  assetType: string;
}

interface UpdatePortfolioRequest {
  portfolioItems: PortfolioItem[];
}

interface AlpacaAsset {
  id: string;
  class: string;
  exchange: string;
  symbol: string;
  name: string;
  status: string;
  tradable: boolean;
  marginable: boolean;
  shortable: boolean;
  easy_to_borrow: boolean;
}

interface Stock {
  symbol: string;
  name: string;
  percentage: number | null;
  description?: string;
  isDefault?: boolean;
  tradable?: boolean;
  assetType?: string;
}

@Component({
  selector: 'app-portfolio-customize',
  templateUrl: './portfolio-customize.component.html',
  styleUrls: ['./portfolio-customize.component.scss'],
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonHeader, IonToolbar, IonContent, IonButton, IonIcon,
    IonList, IonItem, IonLabel, IonReorderGroup,
    IonItemSliding, IonItemOptions, IonItemOption, IonSpinner, IonSearchbar, IonBadge,
    KeyboardAvoidDirective,
    AmountTraySlotComponent,
    SkeletonComponent
  ],
  animations: [
    trigger('deleteAnimation', [
      transition(':leave', [
        style({ height: '*', opacity: 1, overflow: 'hidden' }),
        animate('300ms ease-out', style({ height: '0', opacity: 0, padding: 0, margin: 0 }))
      ])
    ])
  ]
})
export class PortfolioCustomizeComponent implements OnInit, OnDestroy, ViewWillLeave {
  private destroy$ = new Subject<void>();
  searchInput$ = new Subject<string>();

  /**
   * One tray for all the rows, keyed by symbol: tapping a row opens it under that row, tapping
   * another switches. Rows come and go, so a row's field is registered the first time it is
   * tapped; each one reads and writes the stock by symbol, so a reset that rebuilds the list
   * leaves them valid.
   */
  private readonly rowFields: Record<string, TrayHostField> = {};
  readonly tray = new AmountTrayHost<string>({
    root: inject(ElementRef),
    destroyRef: inject(DestroyRef),
    fields: this.rowFields,
  });

  // Current portfolio
  portfolio: Stock[] = [];
  
  // Track original portfolio for change detection
  originalPortfolio: Stock[] = [];
  
  // Loading states
  isLoading = false;
  isSaving = false;
  isSearching = false;
  
  // Stock search
  searchTerm = '';
  searchResults: AlpacaAsset[] = [];
  
  // UI State
  showExplanation = false;
  
  // New stock form
  newStock = {
    symbol: '',
    percentage: 10
  };

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private http: HttpClient,
    private toastService: ToastService,
    private passkeyService: PasskeyService,
    private pinService: PinService,
    private navCtrl: NavController
  ) {}

  ngOnInit() {
    console.log('[PortfolioCustomizeComponent] Initializing portfolio customization');
    // Force recompile

    // Check if this is the initial setup flow
    this.route.queryParamMap.subscribe(params => {
      this.showExplanation = params.get('initial') === 'true';
    });

    this.loadCurrentPortfolio();

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
          (asset.symbol.includes(termUpper) || asset.name.toUpperCase().includes(termUpper))
        );
        filteredResults.sort((a, b) => {
          const aExactSymbol = a.symbol === termUpper ? 1 : 0;
          const bExactSymbol = b.symbol === termUpper ? 1 : 0;
          if (aExactSymbol !== bExactSymbol) return bExactSymbol - aExactSymbol;
          const aSymbolStart = a.symbol.startsWith(termUpper) ? 1 : 0;
          const bSymbolStart = b.symbol.startsWith(termUpper) ? 1 : 0;
          if (aSymbolStart !== bSymbolStart) return bSymbolStart - aSymbolStart;
          const aSymbolContains = a.symbol.includes(termUpper) ? 1 : 0;
          const bSymbolContains = b.symbol.includes(termUpper) ? 1 : 0;
          if (aSymbolContains !== bSymbolContains) return bSymbolContains - aSymbolContains;
          const aNameContains = a.name.toLowerCase().includes(term.toLowerCase()) ? 1 : 0;
          const bNameContains = b.name.toLowerCase().includes(term.toLowerCase()) ? 1 : 0;
          if (aNameContains !== bNameContains) return bNameContains - aNameContains;
          return a.symbol.localeCompare(b.symbol);
        });
        this.searchResults = filteredResults.slice(0, 50);
      } else {
        this.searchResults = [];
      }
      console.log(`[PortfolioCustomizeComponent] Search for "${this.searchTerm}" returned ${this.searchResults.length} results`);
    });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /** Leaving the page: the tray snaps shut rather than collapsing into a view that is going. */
  ionViewWillLeave(): void {
    this.tray.close(true);
  }

  /** A row's allocation tap: open the tray under it, switch to it, or close it. */
  toggleRow(stock: Stock): void {
    this.rowFields[stock.symbol] ??= {
      label: `${stock.symbol} allocation`,
      scale: ALLOCATION_SCALE,
      unit: 'percent',
      value: () => this.stockOf(stock.symbol)?.percentage ?? 0,
      set: v => {
        const row = this.stockOf(stock.symbol);
        if (row) row.percentage = v;
      },
    };
    this.tray.toggle(stock.symbol);
  }

  private stockOf(symbol: string): Stock | undefined {
    return this.portfolio.find(s => s.symbol === symbol);
  }

  private getAuthHeaders(): HttpHeaders {
    let headers = new HttpHeaders();
    const token = JwtTokenUtils.getValidJwtToken();
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    headers = headers.set('Content-Type', 'application/json');
    return headers;
  }

  // Load current portfolio from backend
  async loadCurrentPortfolio(): Promise<void> {
    this.isLoading = true;
    try {
      const response = await this.http.get<PortfolioResponse>(
        `${environment.backendApiUrl}/portfolio/current`, 
        { headers: this.getAuthHeaders() }
      ).toPromise();
      
      if (response) {
        this.portfolio = response.portfolioItems.map(item => ({
          symbol: item.symbol,
          name: item.name,
          percentage: item.percentage,
          assetType: item.assetType,
          description: `${item.assetType}: ${item.name}`,
          isDefault: response.isDefault
        }));
        
        // Save original state for change detection
        this.originalPortfolio = JSON.parse(JSON.stringify(this.portfolio));
      }
      
      console.log('[PortfolioCustomizeComponent] Loaded portfolio:', this.portfolio);
    } catch (error) {
      console.error('[PortfolioCustomizeComponent] Error loading portfolio:', error);
      // Fall back to default portfolio
      this.setDefaultPortfolio();
    } finally {
      this.isLoading = false;
    }
  }

  // Set default portfolio
  private setDefaultPortfolio(): void {
    this.portfolio = [
      {
        symbol: 'VTI',
        name: 'Vanguard Total Stock Market ETF',
        percentage: 75,
        assetType: 'ETF',
        description: 'Tracks the entire U.S. stock market',
        isDefault: true
      },
      {
        symbol: 'VXUS',
        name: 'Vanguard Total International Stock ETF',
        percentage: 20,
        assetType: 'ETF',
        description: 'International diversification outside the U.S.',
        isDefault: true
      },
      {
        symbol: 'VBR',
        name: 'Vanguard Small-Cap Value ETF',
        percentage: 5,
        assetType: 'ETF',
        description: 'Exposure to small-cap value stocks',
        isDefault: true
      }
    ];
    
    // Save original state for change detection
    this.originalPortfolio = JSON.parse(JSON.stringify(this.portfolio));
  }

  // Input handler: feed the debounced search Subject
  onSearchInput(event: any): void {
    const value: string = event.target?.value ?? '';
    this.searchInput$.next(value);
  }

  // Pin formatter for ion-range
  pinFormatter = (value: number): string => {
    return `${value}%`;
  };

  // Helper methods for template
  isStockInPortfolio(asset: AlpacaAsset): boolean {
    return this.portfolio.some(s => s.symbol === asset.symbol);
  }

  getStockIconName(asset: AlpacaAsset): string {
    return this.isStockInPortfolio(asset) ? 'checkmark-circle' : 'add-circle-outline';
  }

  getStockIconColor(asset: AlpacaAsset): string {
    return this.isStockInPortfolio(asset) ? 'success' : 'primary';
  }

  updateNewStockPercentage(event: any): void {
    this.newStock.percentage = event.detail.value;
  }

  isNewStockValid(): boolean {
    return this.newStock.symbol.trim().length > 0;
  }

  // Get total allocation percentage
  getTotalAllocation(): number {
    const total = this.portfolio.reduce((total, stock) => total + (stock.percentage ? stock.percentage : 0), 0);
    // Round to 2 decimal places to handle floating point errors
    return Math.round(total * 100) / 100;
  }

  // Check if allocations are valid
  isValidAllocation(): boolean {
    const total = this.getTotalAllocation();
    return total === 100 && this.portfolio.length > 0;
  }

  // Check if portfolio has been modified from original
  hasPortfolioChanged(): boolean {
    if (this.portfolio.length !== this.originalPortfolio.length) {
      return true;
    }
    
    // Check if any stock symbol, percentage, or order has changed
    for (let i = 0; i < this.portfolio.length; i++) {
      const current = this.portfolio[i];
      const original = this.originalPortfolio[i];
      
      if (current.symbol !== original.symbol || 
          current.percentage !== original.percentage) {
        return true;
      }
    }
    
    return false;
  }

  // Check if current portfolio differs from default portfolio
  isPortfolioDifferentFromDefault(): boolean {
    const defaultPortfolio = this.getDefaultPortfolioStructure();
    
    if (this.portfolio.length !== defaultPortfolio.length) {
      return true;
    }
    
    // Check if any stock symbol or percentage differs from default
    for (let i = 0; i < this.portfolio.length; i++) {
      const current = this.portfolio[i];
      const defaultStock = defaultPortfolio.find(stock => stock.symbol === current.symbol);
      
      if (!defaultStock || current.percentage !== defaultStock.percentage) {
        return true;
      }
    }
    
    return false;
  }

  // Get the default portfolio structure for comparison
  private getDefaultPortfolioStructure(): Stock[] {
    return [
      {
        symbol: 'VTI',
        name: 'Vanguard Total Stock Market ETF',
        percentage: 75,
        assetType: 'ETF',
        description: 'Tracks the entire U.S. stock market',
        isDefault: true
      },
      {
        symbol: 'VXUS',
        name: 'Vanguard Total International Stock ETF',
        percentage: 20,
        assetType: 'ETF',
        description: 'International diversification outside the U.S.',
        isDefault: true
      },
      {
        symbol: 'VBR',
        name: 'Vanguard Small-Cap Value ETF',
        percentage: 5,
        assetType: 'ETF',
        description: 'Exposure to small-cap value stocks',
        isDefault: true
      }
    ];
  }

  // Normalize allocations to ensure they add up to 100%
  normalizeAllocations() {
    const total = this.getTotalAllocation();
    if (total !== 100 && total > 0) {
      // Proportionally adjust allocations
      this.portfolio.forEach(stock => {
        stock.percentage = Math.round(((stock.percentage || 0) / total) * 100);
      });
      
      // Handle rounding errors
      const newTotal = this.getTotalAllocation();
      if (newTotal !== 100) {
        const diff = 100 - newTotal;
        if (this.portfolio.length > 0) {
          this.portfolio[0].percentage = (this.portfolio[0].percentage || 0) + diff;
        }
      }
    }
  }

  // Remove stock from portfolio
  removeStock(stock: Stock) {
    // The row leaves with its slot, so there is nothing left to collapse.
    if (this.tray.shown() === stock.symbol) this.tray.close(true);
    const index = this.portfolio.findIndex(s => s.symbol === stock.symbol);
    if (index > -1) {
      this.portfolio.splice(index, 1);
      this.normalizeAllocations();
    }
  }

  // Add asset from search results to portfolio
  addAssetToPortfolio(asset: AlpacaAsset) {
    // Check if already in portfolio
    const exists = this.portfolio.find(s => s.symbol === asset.symbol);
    if (exists) return;

    // Determine asset type (check class or if name contains "ETF")
    const isEtf = asset.class === 'etf' || asset.name.toUpperCase().includes('ETF');

    // Add with 10% allocation
    const newStock: Stock = {
      symbol: asset.symbol,
      name: asset.name,
      percentage: 10,
      assetType: isEtf ? 'ETF' : 'STOCK',
      description: `${isEtf ? 'ETF' : 'STOCK'}: ${asset.name}`,
      tradable: asset.tradable
    };
    
    this.portfolio.push(newStock);
    this.normalizeAllocations();
    
    // Clear search
    this.searchTerm = '';
    this.searchResults = [];
  }

  // Add custom stock
  addCustomStock() {
    if (!this.newStock.symbol.trim()) return;

    // Check if already exists
    const exists = this.portfolio.find(s => s.symbol.toLowerCase() === this.newStock.symbol.toLowerCase());
    if (exists) {
      this.toastService.show({ severity: 'warning', message: 'This stock is already in your portfolio' });
      return;
    }

    // Add the stock
    const stock: Stock = {
      symbol: this.newStock.symbol.toUpperCase(),
      name: this.newStock.symbol.toUpperCase(),
      percentage: this.newStock.percentage,
      assetType: 'STOCK',
      description: 'Custom stock selection'
    };

    this.portfolio.push(stock);
    this.normalizeAllocations();
    
    // Reset form
    this.newStock = { symbol: '', percentage: 10 };
  }

  // Reorder stocks
  reorderStocks(event: any) {
    const itemMove = this.portfolio.splice(event.detail.from, 1)[0];
    this.portfolio.splice(event.detail.to, 0, itemMove);
    event.detail.complete();
  }

  // Reset to default portfolio
  resetToDefault() {
    // The rows are about to be replaced under the tray.
    this.tray.close(true);
    // If the user's saved portfolio is already the default, just show success toast
    if (!this.isSavedPortfolioDifferentFromDefault()) {
      this.portfolio = this.getDefaultPortfolioStructure();
      this.toastService.showToast('Portfolio reset to default.', 'success');
      return;
    }

    // Reset local state only - changes are not persisted until "Save" is clicked
    this.portfolio = this.getDefaultPortfolioStructure();
    console.log('[PortfolioCustomizeComponent] Reset to default portfolio (frontend only)');
    this.toastService.showToast('Portfolio reset to default. Click Save to apply.', 'warning');
  }

  // Check if the user's saved (original) portfolio differs from the default portfolio
  private isSavedPortfolioDifferentFromDefault(): boolean {
    const defaultPortfolio = this.getDefaultPortfolioStructure();

    if (this.originalPortfolio.length !== defaultPortfolio.length) {
      return true;
    }

    for (let i = 0; i < this.originalPortfolio.length; i++) {
      const saved = this.originalPortfolio[i];
      const defaultStock = defaultPortfolio.find(stock => stock.symbol === saved.symbol);

      if (!defaultStock || saved.percentage !== defaultStock.percentage) {
        return true;
      }
    }

    return false;
  }

  // Save portfolio and return to confirmation
  async savePortfolio() {
    this.tray.close();
    if (!this.isValidAllocation()) {
      this.toastService.showToast('Portfolio allocations must add up to 100%', 'danger');
      return;
    }

    if (!this.hasPortfolioChanged()) {
      this.toastService.showToast('No changes to save', 'warning');
      return;
    }

    this.isSaving = true;
    console.log('[PortfolioCustomizeComponent] Saving portfolio:', this.portfolio);
    
    try {
      // Step-up authentication check
      const hasPin = await this.pinService.hasPin();

      if (hasPin) {
        const pinVerified = await this.pinService.promptPin('verify');
        if (!pinVerified) {
          this.toastService.showToast('Authentication required to save portfolio.', 'warning');
          this.isSaving = false;
          return;
        }
      }

      const updateRequest: UpdatePortfolioRequest = {
        portfolioItems: this.portfolio
          .filter(stock => (stock.percentage ?? 0) > 0) // Filter out 0% or null items
          .map(stock => ({
            symbol: stock.symbol,
            name: stock.name,
            percentage: stock.percentage || 0, // Ensure strictly number for API
            assetType: stock.assetType || 'STOCK'
        }))
      };

      const response = await this.http.put<PortfolioResponse>(
        `${environment.backendApiUrl}/portfolio/update`,
        updateRequest,
        { headers: this.getAuthHeaders() }
      ).toPromise();
      
      if (response) {
        console.log('[PortfolioCustomizeComponent] Portfolio saved successfully');
        
        // Remove 0% items from local state to match backend
        this.portfolio = this.portfolio.filter(stock => (stock.percentage || 0) > 0);
        
        // Update original portfolio to match current state
        this.originalPortfolio = JSON.parse(JSON.stringify(this.portfolio));

        if (this.showExplanation) {
          // If in initial flow, navigate to confirmation
          this.router.navigate(['/investment-confirmation']);
        } else {
          // Otherwise stay on page and show success
          this.toastService.showToast('Portfolio saved successfully', 'success');
        }
      }
    } catch (error) {
      console.error('[PortfolioCustomizeComponent] Error saving portfolio:', error);
      this.toastService.showToast('Failed to save portfolio. Please try again.', 'danger');
    } finally {
      this.isSaving = false;
    }
  }

  goBack() {
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  // Cancel and return to confirmation
  cancel() {
    this.router.navigate(['/investment-confirmation']);
  }
}