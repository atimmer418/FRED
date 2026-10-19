import { Component, OnInit, OnDestroy, ChangeDetectorRef, HostBinding } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { IonHeader, IonToolbar, IonContent, IonFooter, IonSpinner, NavController } from '@ionic/angular/standalone';
import { Subscription } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { PlanStrategy } from '../../services/analytics/analytics-events';

// Define a type for our strategy for clean code
export interface StrategyTag {
  label: string;
  type: 'blue' | 'green' | 'gray' | 'amber';
}

export interface Strategy {
  id: string;
  title: string;
  description: string;
  tags: StrategyTag[];
  icon: string; // Add icon property
}

@Component({
  selector: 'app-fi-plan-results',
  templateUrl: './fi-plan-results.component.html',
  styleUrls: ['./fi-plan-results.component.scss'],
  standalone: true,
  imports: [CommonModule, IonHeader, IonToolbar, IonContent, IonFooter, IonSpinner],
})
export class FiPlanResultsComponent implements OnInit, OnDestroy {

  strategies: Strategy[] = [
    {
      id: 'yield',
      title: 'Yield-Based Income',
      description: 'Never sell a share. Live off dividends and interest; principal stays invested.',
      icon: 'savings',
      tags: [
        { label: 'No Selling', type: 'blue' },
        { label: 'Always Growing', type: 'green' },
        { label: 'Yield-Dependent', type: 'gray' },
      ]
    },
    {
      id: 'guardrails',
      title: 'Dynamic Guardrails',
      description: 'Spend what your portfolio can afford. More in strong markets, less in weak ones.',
      icon: 'tune',
      tags: [
        { label: 'High Growth', type: 'green' },
        { label: 'Sustainable', type: 'green' },
        { label: 'Flex Spending', type: 'blue' },
      ]
    },
    {
      id: 'sbloc',
      title: 'Sell High, Borrow Low',
      description: 'Sell 4% when markets are up. Borrow against your portfolio when they dip.',
      icon: 'swap_vert',
      tags: [
        { label: 'Protects Gains', type: 'green' },
        { label: 'Shields Bad Years', type: 'green' },
        { label: 'Uses Credit', type: 'blue' },
      ]
    },
    {
      id: 'annuity',
      title: 'Annuity',
      description: 'Zero market risk. Turn your portfolio into a guaranteed paycheck for life.',
      icon: 'lock',
      tags: [
        { label: 'Guaranteed', type: 'green' },
        { label: 'Set & Forget', type: 'blue' },
        { label: 'No Growth', type: 'amber' },
      ]
    }
  ];

  timeToFI: string = '';
  targetPortfolio: number = 0;
  retirementIncome: number = 0;
  monthlyInvestment: number = 0;
  @HostBinding('class.page-ready') isReady = false;
  selectedStrategyId: string = '';
  isLoading: boolean = false;

  private routeSub: Subscription | undefined;
  
  constructor(
    private route: ActivatedRoute,
    public router: Router,
    private authService: AuthService,
    private cdr: ChangeDetectorRef,
    private navCtrl: NavController,
    private analytics: AnalyticsService
  ) {}

  ngOnInit() {
    // Make component accessible from browser console for debugging
    (window as any)['fiPlanComponent'] = this;

    this.routeSub = this.route.queryParams.subscribe(params => {
      // First try to get values from query parameters (fresh navigation from surveyInitial)
      this.timeToFI = params['t'];
      this.retirementIncome = +params['rI'];
      this.monthlyInvestment = +params['mI'];
      
      // If query parameters are missing, try to get from user progress or localStorage
      if (!this.timeToFI || !this.retirementIncome || !this.monthlyInvestment) {
        console.log('Query parameters missing, trying to load from saved data');
        this.loadSavedData();
      }
      
      // Calculate target portfolio based on retirement income (same rate as surveyinitial)
      if (this.retirementIncome) {
        this.targetPortfolio = this.retirementIncome / 0.04;
      }
      
      console.log('FiPlanResults loaded with data:', {
        timeToFI: this.timeToFI,
        retirementIncome: this.retirementIncome,
        monthlyInvestment: this.monthlyInvestment,
        targetPortfolio: this.targetPortfolio,
        selectedStrategy: this.selectedStrategyId
      });
    });
    
    // Load strategy selection FIRST, before saving any data
    setTimeout(() => {
      this.loadSelectedStrategy();

      // Only save data after strategy is loaded to avoid overwriting the selection
      if (this.timeToFI && this.retirementIncome && this.monthlyInvestment) {
        setTimeout(() => {
          this.saveDataToLocalStorage();
        }, 50);
      }
    }, 100);

    this.preloadAssets();

    // A saved strategy means the user has stood on this screen before. Read here rather than
    // waiting for loadSelectedStrategy, which runs on a timer.
    this.analytics.trackOnboardingStepViewed('fi_plan_results', null, this.hasSavedStrategy());

  }

  /** Storage can throw (private mode, blocked site data); analytics must never break the screen. */
  private hasSavedStrategy(): boolean {
    try {
      return !!localStorage.getItem('fiPlanSelectedStrategy');
    } catch {
      return false;
    }
  }

  /**
   * Narrows the card id to the taxonomy's closed set. selectedStrategyId is a plain string, so an
   * unrecognised id sends nothing rather than inventing a value.
   */
  private toPlanStrategy(id: string): PlanStrategy | null {
    return id === 'yield' || id === 'guardrails' || id === 'sbloc' || id === 'annuity' ? id : null;
  }

  private preloadAssets() {
    const fontsReady = Promise.all([
      document.fonts.load('700 16px "Manrope"').catch(() => []),
      document.fonts.load('400 24px "Material Symbols Outlined"').catch(() => []),
    ]);
    const timeout = new Promise<void>(resolve => setTimeout(resolve, 2000));
    Promise.race([fontsReady, timeout]).then(() => { this.isReady = true; });
  }

  goBack() {
    this.navCtrl.navigateBack('/survey-initial');
  }

  private loadSelectedStrategy() {
    const savedStrategy = localStorage.getItem('fiPlanSelectedStrategy');
    console.log('Loading strategy from localStorage:', savedStrategy);
    console.log('Current selectedStrategyId before loading:', this.selectedStrategyId);
    
    if (savedStrategy && savedStrategy !== this.selectedStrategyId) {
      this.selectedStrategyId = savedStrategy;
      console.log('Updated selectedStrategyId to:', this.selectedStrategyId);
      
      // Trigger change detection to update the view
      this.cdr.detectChanges();
      console.log('Change detection triggered');
    } else if (savedStrategy) {
      console.log('Saved strategy matches current selection, no change needed');
    } else {
      console.log('No saved strategy found, using default:', this.selectedStrategyId);
    }
    
    // Double-check what's actually in localStorage and component
    console.log('Final state - localStorage:', localStorage.getItem('fiPlanSelectedStrategy'));
    console.log('Final state - component selectedStrategyId:', this.selectedStrategyId);
    
    // Verify the DOM will show the correct selection
    setTimeout(() => {
      const selectedElements = document.querySelectorAll('.strategy-card.selected');
      console.log('DOM elements with selected class:', selectedElements.length);
      if (selectedElements.length > 0) {
        console.log('Selected strategy in DOM:', selectedElements[0].getAttribute('data-strategy') || 'unknown');
      }
    }, 100);
  }
  
  private loadSavedData() {
    // Try to get from user progress first (for authenticated users)
    this.authService.userProgress$.subscribe(progress => {
      if (progress) {
        if (progress.monthlyInvestment && !this.monthlyInvestment) {
          this.monthlyInvestment = progress.monthlyInvestment;
        }
        if (progress.retirementIncome && !this.retirementIncome) {
          this.retirementIncome = progress.retirementIncome;
        }
      }
    });
    
    // Calculate timeToFI from the saved values
    if (this.monthlyInvestment && this.retirementIncome) {
      this.timeToFI = this.calculateTimeToFI(this.monthlyInvestment, this.retirementIncome);
    }
    
    // Fallback to localStorage if user progress doesn't have the data
    if (!this.timeToFI) {
      this.timeToFI = localStorage.getItem('fiPlanTimeToFI') || '';
    }
    if (!this.retirementIncome) {
      const savedRetirement = localStorage.getItem('surveyRetirementIncome');
      this.retirementIncome = savedRetirement ? parseInt(savedRetirement, 10) : 0;
    }
    if (!this.monthlyInvestment) {
      const savedMonthly = localStorage.getItem('surveyMonthlyInvestment');
      this.monthlyInvestment = savedMonthly ? parseInt(savedMonthly, 10) : 0;
    }
  }
  
  private saveDataToLocalStorage() {
    localStorage.setItem('fiPlanTimeToFI', this.timeToFI);
    localStorage.setItem('fiPlanTargetPortfolio', this.targetPortfolio.toString());
    
    // Only save strategy if it's not the default, or if there's no existing strategy saved
    const existingStrategy = localStorage.getItem('fiPlanSelectedStrategy');
    if (!existingStrategy || this.selectedStrategyId !== '') {
      localStorage.setItem('fiPlanSelectedStrategy', this.selectedStrategyId);
    }
    
    console.log('FiPlanResults: Saved data to localStorage:', {
      timeToFI: this.timeToFI,
      targetPortfolio: this.targetPortfolio,
      selectedStrategy: localStorage.getItem('fiPlanSelectedStrategy'), // Show what's actually saved
      skippedStrategyOverwrite: existingStrategy && this.selectedStrategyId === ''
    });
  }
  
  private calculateTimeToFI(monthlyInvestment: number, retirementIncome: number): string {
    const targetPortfolio = retirementIncome / 0.04;
    const monthlyRate = 0.10 / 12;
    
    if (monthlyInvestment <= 0) {
      return '∞';
    }
    
    const numberOfMonths = Math.log((targetPortfolio * monthlyRate / monthlyInvestment) + 1) / Math.log(1 + monthlyRate);
    const years = numberOfMonths / 12;
    
    return isFinite(years) ? years.toFixed(1) : '∞';
  }
  
  selectStrategy(id: string) {
    console.log('selectStrategy called with id:', id);
    console.log('Previous selectedStrategyId:', this.selectedStrategyId);
    
    this.selectedStrategyId = id;
    console.log('Updated selectedStrategyId to:', this.selectedStrategyId);
    
    // Save selection to localStorage immediately
    localStorage.setItem('fiPlanSelectedStrategy', id);
    console.log('Saved to localStorage:', localStorage.getItem('fiPlanSelectedStrategy'));
    
    // Trigger change detection to ensure view updates
    this.cdr.detectChanges();
    console.log('Change detection triggered after selection');
    
    // Verify the change in the DOM
    setTimeout(() => {
      const selectedElements = document.querySelectorAll('.strategy-card.selected');
      console.log('After selection - DOM elements with selected class:', selectedElements.length);
      selectedElements.forEach((el, index) => {
        console.log(`Selected element ${index}:`, el.getAttribute('data-strategy'));
      });
    }, 50);
  }

  getSelectedStrategyName(): string {
    return this.strategies.find(s => s.id === this.selectedStrategyId)?.title || 'Strategy';
  }

  get formattedTargetPortfolio(): string {
    const rounded = Math.round(this.targetPortfolio / 100_000) * 100_000;
    return '$' + rounded.toLocaleString('en-US');
  }

  getFutureYear(): number {
    const currentYear = new Date().getFullYear();
    const yearsToAdd = parseFloat(this.timeToFI) || 0;
    return currentYear + Math.round(yearsToAdd);
  }
  
  confirmSelection() {
    this.isLoading = true;

    const strategy = this.toPlanStrategy(this.selectedStrategyId);
    if (strategy) {
      this.analytics.track({ name: 'plan_strategy_selected', props: { strategy } });
    }
    this.analytics.trackOnboardingStepCompleted('fi_plan_results', null);

    console.log(`User selected the ${this.getSelectedStrategyName()} plan.`);

    // Complete the fiPlanResults step
    this.authService.completeStep('fiPlanResults').subscribe({
      next: () => {
        console.log('FiPlanResults step completed successfully');
        this.router.navigate(['/auth-finalize'], { 
          queryParams: { 
            plan: this.selectedStrategyId,
            t: this.timeToFI,
            p: this.targetPortfolio,
            rI: this.retirementIncome,
            mI: this.monthlyInvestment
          } 
        });
      },
      error: (err) => {
        console.log('FiPlanResults step could not be completed (likely not authenticated yet):', err);
        // Still navigate even if progress update fails
        this.router.navigate(['/auth-finalize'], { 
          queryParams: { 
            plan: this.selectedStrategyId,
            t: this.timeToFI,
            p: this.targetPortfolio,
            rI: this.retirementIncome,
            mI: this.monthlyInvestment
          } 
        });
      }
    });
  }
  
  ngOnDestroy() {
    if (this.routeSub) this.routeSub.unsubscribe();
  }
  
  // Debug method - call from browser console: window['fiPlanComponent'].testSelectStrategy('balanced')
  testSelectStrategy(id: string) {
    console.log('=== MANUAL TEST: Selecting strategy', id, '===');
    this.selectStrategy(id);
    console.log('=== MANUAL TEST COMPLETE ===');
  }
  
  formatDescription(description: string): string {
    return description.replace(/\. /g, '.<br>');
  }

  // Debug method for template
  debugCardClick(id: string) {
    console.log('Card clicked (mousedown):', id);
  }
}