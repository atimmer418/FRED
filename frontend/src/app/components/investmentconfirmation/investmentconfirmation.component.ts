import { Component, OnInit, AfterViewInit, OnDestroy, ChangeDetectorRef, CUSTOM_ELEMENTS_SCHEMA, ElementRef, ViewChild, ViewChildren, QueryList } from '@angular/core';
import { catchError, of } from 'rxjs';
import { trigger, transition, style, animate } from '@angular/animations';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { ViewWillEnter } from '@ionic/angular';
import {
  IonHeader, IonToolbar, IonContent,
  IonSpinner, IonFooter, NavController
} from '@ionic/angular/standalone';
import { ToastService } from '../../services/toast.service';
import { AuthService } from '../../services/auth.service';
import { InvestmentService } from '../../services/investment.service';
import { environment } from '../../../environments/environment';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';

// Investment schedule interfaces
interface InvestmentSchedule {
  payFrequency: string;
  investmentAmount: number;
  startDate: string;
  isEnabled: boolean;
  scheduleDescription?: string;
}

// Default portfolio interface
interface DefaultStock {
  symbol: string;
  name: string;
  allocation: number; // percentage
  description: string;
}

// Pricing tier types
type FeatureLockState = 'unlocked' | 'unlocks-sooner' | 'locked' | 'coming-soon';

interface PricingFeature {
  id: string;
  label: string;
  description: string;
  lockState: FeatureLockState;
  threshold?: string;
}

interface PricingTier {
  id: 'core' | 'plus' | 'pro';
  name: string;
  accent: string;
  price: number;
  lifetimeLine: string;
  yearlyPrice: number;
  yearlyLifetimeLine: string;
  monthlySavings: number;
  yearlySavings: number;
  maxYearlySavings: number;
  referralsRequired: number;
  pitch: string;
  ctaLabel: string;
  badgeLabel: string;
  badgeTextColor: string;
  features: PricingFeature[];
}

const PRICING_TIERS: PricingTier[] = [
  {
    id: 'core',
    name: 'Piggy Plan',
    accent: '#FFA1B5',
    price: 8,
    lifetimeLine: '$5/mo lifetime with 3 referrals',
    yearlyPrice: 75,
    yearlyLifetimeLine: '$50/yr lifetime with 3 referrals',
    monthlySavings: 35,
    yearlySavings: 20,
    maxYearlySavings: 45,
    referralsRequired: 3,
    pitch: 'Automated investing. Works while you work.',
    ctaLabel: 'Join The Pig Leagues',
    badgeLabel: 'The Basics',
    badgeTextColor: '#FFFFFF',
    features: [
      {
        id: 'auto-invest',
        label: 'Paycheck-based automated investing',
        description: 'FRED automatically invests your set portion of every paycheck so you never have to think about it.',
        lockState: 'unlocked'
      },
      {
        id: 'portfolio',
        label: 'FRED default portfolio or BYO',
        description: "Invest with FRED's proven default portfolio (75% VTI, 20% VXUS, 5% VBR) or customize your own allocation.",
        lockState: 'unlocked'
      },
      {
        id: 'rebalance',
        label: 'Automatic portfolio rebalancing',
        description: 'FRED periodically realigns your portfolio back to your target allocation; no action needed from you.',
        lockState: 'unlocked'
      },
      {
        id: 'education',
        label: 'Portfolio withdrawal education',
        description: 'Learn the four ways to live off your portfolio: dividends, dynamic spending, borrowing against your portfolio, and converting a portion to guaranteed income.',
        lockState: 'unlocked',
      }
    ]
  },
  {
    id: 'plus',
    name: 'Piggy Plus Plan',
    accent: '#FBC926', // previous color: #FF6B8A
    price: 15,
    lifetimeLine: '$10/mo lifetime with 2 referrals',
    yearlyPrice: 150,
    yearlyLifetimeLine: '$100/yr lifetime with 2 referrals',
    monthlySavings: 60,
    yearlySavings: 30,
    maxYearlySavings: 80,
    referralsRequired: 2,
    pitch: 'Your complete financial picture. Every account, every goal, one plan.',
    ctaLabel: 'Join The Pig Leagues',
    badgeLabel: 'The Full Picture',
    badgeTextColor: '#FFFFFF',
    features: [
      {
        id: 'everything-core',
        label: 'Everything in Core',
        description: 'All Core features included.',
        lockState: 'unlocked'
      },
      {
        id: 'montecarlo',
        label: 'Monte Carlo modeling',
        description: 'Runs thousands of market simulations against your numbers to show the real probability of hitting your Freedom Date.',
        lockState: 'unlocked',
      },
      {
        id: 'multi-goal',
        label: 'Multi-goal tracking',
        description: 'Track multiple financial goals (house down payment, college fund, emergency fund, Freedom Date) each with its own target and timeline. FRED actively invests for your Freedom Date and shows projection timelines for other goals you fund outside of FRED, so you see your full financial picture in one place.',
        lockState: 'unlocked'
      },
      {
        id: 'external-accounts',
        label: 'Add 401(k) & Roth IRA',
        description: 'Include balances from 401(k)s, Roth IRAs, and any other accounts in your projections for a full picture of your Freedom Date.',
        lockState: 'unlocked'
      },
      {
        id: 'custom-rebalance',
        label: 'Custom rebalancing schedule',
        description: 'Choose how often FRED realigns your portfolio back to your target allocation (quarterly, semi-annual, or annual) based on your tax situation and preference.',
        lockState: 'unlocked'
      },
      {
        id: 'market-breakdown',
        label: 'Monthly market breakdown',
        description: 'Plain-English explanation each month of what happened in the markets and why your portfolio went up or down.',
        lockState: 'unlocked'
      }
    ]
  },
  {
    id: 'pro',
    name: 'Piggy Pro Plan',
    accent: '#000000',
    price: 40,
    lifetimeLine: '$20/mo lifetime with 1 referral',
    yearlyPrice: 400,
    yearlyLifetimeLine: '$200/yr lifetime with 1 referral',
    monthlySavings: 240,
    yearlySavings: 80,
    maxYearlySavings: 280,
    referralsRequired: 1,
    pitch: 'Built for your endgame. Tax-optimized and ready.',
    ctaLabel: 'Join The Pig Leagues',
    badgeLabel: 'The Endgame',
    badgeTextColor: '#FFFFFF',
    features: [
      {
        id: 'everything-plus',
        label: 'Everything in Plus',
        description: 'All Plus features included.',
        lockState: 'unlocked'
      },
      {
        id: 'drawdown',
        label: 'Optimal drawdown walkthrough',
        description: 'A year-by-year withdrawal strategy education once you\'re within range of Freedom Date. FRED sequences your accounts in the tax-optimal order (taxable first, tax-deferred second, Roth last) and updates the plan dynamically as your portfolio grows.',
        lockState: 'unlocked'
      },
      {
        id: 'deeper-projections',
        label: 'Deeper projections & income simulation',
        description: 'Run conservative, expected, and aggressive scenarios. Adjust contributions and retirement age on the fly, and see monthly withdrawal estimates in retirement.',
        lockState: 'unlocked'
      },
      {
        id: 'household',
        label: 'Tax loss harvesting',
        description: "Algorithmic tax loss harvesting that turns market dips into tax savings.",
        lockState: 'coming-soon',
        threshold: 'Coming Soon'
      },
      {
        id: 'priority-support',
        label: 'Priority support',
        description: 'A real human responds to your questions within 24 hours.',
        lockState: 'unlocked'
      },
      {
        id: 'early-access',
        label: 'Early access to new FRED features',
        description: 'Be the first to test new portfolio models, tools, and features before they roll out to everyone else.',
        lockState: 'unlocked'
      }
    ]
  }
];

@Component({
  selector: 'app-investmentconfirmation',
  templateUrl: './investmentconfirmation.component.html',
  styleUrls: ['./investmentconfirmation.component.scss'],
  standalone: true,
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  animations: [
    trigger('crossfade', [
      transition('* => *', [
        style({ opacity: 0, transform: 'translateY(-3px)' }),
        animate('180ms ease-out', style({ opacity: 1, transform: 'translateY(0)' }))
      ])
    ])
  ],
  imports: [
    CommonModule, FormsModule, IonHeader, IonToolbar, IonContent,
    IonSpinner, IonFooter
  ]
})

export class InvestmentConfirmationComponent implements OnInit, AfterViewInit, OnDestroy, ViewWillEnter {
  currentStep: 1 | 2 = 1;
  exitingStep1 = false;

  agreedToTerms: boolean = false;
  agreedToMarketing: boolean = false;

  // Investment schedule data
  investmentSchedule: InvestmentSchedule = {
    payFrequency: 'BIWEEKLY',
    investmentAmount: 0,
    startDate: '',
    isEnabled: true
  };

  // Financial projections
  monthlyGoal: number = 0;
  targetPortfolio: number = 0;
  timeToFI: number = 0;

  // Legacy fields (will be replaced)
  investmentPercentage: string | null = null;
  portfolioType: 'custom' | 'auto' = 'auto';

  // Component state
  isAuthorizing: boolean = false;
  isCreatingAccount: boolean = false;
  userEmail: string | null = null;

  // User's portfolio (fetched from backend)
  userPortfolio: DefaultStock[] = [];
  isLoadingPortfolio: boolean = false;
  portfolioError: string | null = null;

  // Pricing tier carousel
  @ViewChild('pricingSwiper', { read: ElementRef }) pricingSwiperRef?: ElementRef;
  @ViewChildren('tierLottie') tierLottieRefs!: QueryList<ElementRef<HTMLElement>>;
  private tierLottieInstances: any[] = [];
  readonly tiers = PRICING_TIERS;
  currentTierIndex = 1;
  selectedTier: 'core' | 'plus' | 'pro' | null = null;
  billingPeriod: 'monthly' | 'yearly' = 'monthly';
  private expandedFeatures = new Set<string>();

  // Frequency options for display
  frequencyOptions = [
    { value: 'WEEKLY', label: 'Weekly', paychecksPerMonth: 4.33 },
    { value: 'BIWEEKLY', label: 'Bi-weekly', paychecksPerMonth: 2.17 },
    { value: 'SEMI_MONTHLY', label: 'Semi-monthly', paychecksPerMonth: 2.0 },
    { value: 'MONTHLY', label: 'Monthly', paychecksPerMonth: 1.0 }
  ];

  constructor(
    private router: Router,
    private authService: AuthService,
    private investmentService: InvestmentService,
    private navCtrl: NavController,
    private http: HttpClient,
    private toastService: ToastService,
    private cdr: ChangeDetectorRef
  ) {}

  goBack(): void {
    if (this.currentStep === 2) {
      this.currentStep = 1;
      try { localStorage.setItem('fred.investmentConfirmation.step', '1'); } catch { /* noop */ }
      return;
    }
    try { localStorage.removeItem('fred.investmentConfirmation.step'); } catch { /* noop */ }
    this.navCtrl.navigateBack('/investment-schedule', { replaceUrl: true });
  }

  async proceedToTrustStep() {
    if (!this.agreedToTerms) return;
    await this.animateStep(2);
  }

  private async animateStep(target: 1 | 2) {
    if (target === 2) {
      this.exitingStep1 = true;
      this.cdr.detectChanges();
      await new Promise(r => setTimeout(r, 270));
      this.exitingStep1 = false;
      this.currentStep = 2;
      try { localStorage.setItem('fred.investmentConfirmation.step', '2'); } catch { /* noop */ }
      this.cdr.detectChanges();
    }
  }

  ngOnInit() {
    console.log('[InvestmentConfirmationComponent] Initializing investment confirmation page');

    // Load user data and investment schedule
    this.loadUserFinancialData();
    this.loadInvestmentSchedule();
    this.loadUserPortfolio();

    // Get user email for display
    const userEmail = this.authService.getCurrentUserEmail();
    if (userEmail) {
      this.userEmail = userEmail;
      console.log('[InvestmentConfirmationComponent] User email:', userEmail);
    }
  }

  ngAfterViewInit(): void {
    if (this.tierLottieRefs.length > 0) {
      this.initTierLottieAnimations();
    }
    this.tierLottieRefs.changes.subscribe((list: QueryList<ElementRef<HTMLElement>>) => {
      if (list.length === 0) {
        this.destroyTierLottieAnimations();
      } else if (this.tierLottieInstances.length === 0) {
        this.initTierLottieAnimations();
      }
    });
  }

  ngOnDestroy(): void {
    this.destroyTierLottieAnimations();
  }

  private initTierLottieAnimations(retries = 30): void {
    const lottie = (window as any).lottie;
    if (!lottie) {
      if (retries > 0) setTimeout(() => this.initTierLottieAnimations(retries - 1), 50);
      return;
    }
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    this.tierLottieRefs.forEach((ref) => {
      const anim = lottie.loadAnimation({
        container: ref.nativeElement,
        path: 'assets/lottie/coin-tier.json',
        renderer: 'svg',
        loop: false,
        autoplay: false,
        rendererSettings: { preserveAspectRatio: 'xMidYMid meet' },
      });
      this.tierLottieInstances.push(anim);
    });
    if (reduced) return;
    const active = this.tierLottieInstances[this.currentTierIndex];
    if (!active) return;
    const fire = () => requestAnimationFrame(() => active.goToAndPlay(0, true));
    if ((active as any).isLoaded) fire();
    else active.addEventListener('DOMLoaded', fire);
  }

  private playTierAnimation(index: number): void {
    const anim = this.tierLottieInstances[index];
    if (!anim) return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    anim.goToAndPlay(0, true);
  }

  private destroyTierLottieAnimations(): void {
    this.tierLottieInstances.forEach((a) => { try { a.destroy(); } catch { /* noop */ } });
    this.tierLottieInstances = [];
  }

  setBillingPeriod(period: 'monthly' | 'yearly'): void {
    if (this.billingPeriod === period) return;
    this.billingPeriod = period;
    try { localStorage.setItem('fred.billingPeriod', period); } catch { /* noop */ }
    this.tierLottieInstances.forEach((_, i) => this.playTierAnimation(i));
  }

  ionViewWillEnter() {
    console.log('[InvestmentConfirmationComponent] View will enter - reloading portfolio');
    this.loadUserPortfolio();
    try {
      if (localStorage.getItem('fred.investmentConfirmation.step') === '2') {
        this.currentStep = 2;
        this.agreedToTerms = true;
      }
      const savedPeriod = localStorage.getItem('fred.billingPeriod');
      if (savedPeriod === 'monthly' || savedPeriod === 'yearly') {
        this.billingPeriod = savedPeriod;
      }
    } catch { /* noop */ }
  }

  loadUserFinancialData(): void {
    console.log('[InvestmentConfirmationComponent] Loading user financial data...');

    const currentProgress = this.authService.getCurrentProgress();
    if (currentProgress && currentProgress.monthlyInvestment) {
      this.monthlyGoal = currentProgress.monthlyInvestment;
      const annualInvestment = this.monthlyGoal * 12;
      this.targetPortfolio = annualInvestment * 25; // 4% rule estimate
      this.timeToFI = 25; // Simplified estimate

      console.log('[InvestmentConfirmationComponent] Loaded financial data:', {
        monthlyGoal: this.monthlyGoal,
        targetPortfolio: this.targetPortfolio,
        timeToFI: this.timeToFI
      });
    } else {
      console.log('[InvestmentConfirmationComponent] No financial data found, fetching from API...');
      this.authService.getUserProgress().subscribe({
        next: (progress) => {
          if (progress && progress.monthlyInvestment) {
            this.monthlyGoal = progress.monthlyInvestment;
            const annualInvestment = this.monthlyGoal * 12;
            this.targetPortfolio = annualInvestment * 25;
            this.timeToFI = 25;
          }
        },
        error: (error) => {
          console.error('[InvestmentConfirmationComponent] Error loading user progress:', error);
        }
      });
    }
  }

  loadInvestmentSchedule(): void {
    console.log('[InvestmentConfirmationComponent] Loading investment schedule from backend...');

    this.authService.getCurrentInvestmentSchedule().subscribe({
      next: (scheduleData) => {
        console.log('[InvestmentConfirmationComponent] ✅ Retrieved investment schedule from backend:', scheduleData);

        if (scheduleData && scheduleData.frequency && scheduleData.monthlyAmount) {
          this.investmentSchedule = {
            payFrequency: scheduleData.frequency,
            investmentAmount: this.calculateInvestmentAmountByFrequency(scheduleData.monthlyAmount, scheduleData.frequency),
            startDate: scheduleData.startDate,
            isEnabled: !scheduleData.isPaused,
            scheduleDescription: scheduleData.scheduleDescription
          };

          if (scheduleData.targetPortfolio) {
            this.targetPortfolio = scheduleData.targetPortfolio;
          }
          if (scheduleData.timeToFI) {
            this.timeToFI = scheduleData.timeToFI;
          }

          console.log('[InvestmentConfirmationComponent] ✅ Investment schedule loaded:', this.investmentSchedule);
        } else {
          console.log('[InvestmentConfirmationComponent] No valid schedule data, using defaults');
          this.setDefaultInvestmentSchedule();
        }
      },
      error: (error) => {
        console.error('[InvestmentConfirmationComponent] ❌ Error loading investment schedule:', error);
        console.log('[InvestmentConfirmationComponent] Using default values...');
        this.setDefaultInvestmentSchedule();
      }
    });
  }

  private setDefaultInvestmentSchedule(): void {
    this.investmentSchedule = {
      payFrequency: 'BIWEEKLY',
      investmentAmount: Math.round(this.monthlyGoal / 2.17),
      startDate: this.getNextInvestmentDate('BIWEEKLY'),
      isEnabled: true
    };
  }

  private calculateInvestmentAmountByFrequency(monthlyAmount: number, frequency: string): number {
    const frequencyMap: { [key: string]: number } = {
      'WEEKLY': 4.33,
      'BIWEEKLY': 2.17,
      'SEMI_MONTHLY': 2.0,
      'MONTHLY': 1.0
    };

    const periodsPerMonth = frequencyMap[frequency] || 2.17;
    return Math.round(monthlyAmount / periodsPerMonth);
  }

  private getNextInvestmentDate(frequency: string): string {
    const today = new Date();
    const tomorrow = new Date(today);
    tomorrow.setDate(today.getDate() + 1);

    const year = tomorrow.getFullYear();
    const month = String(tomorrow.getMonth() + 1).padStart(2, '0');
    const day = String(tomorrow.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  loadUserPortfolio(): void {
    console.log('[InvestmentConfirmationComponent] Loading user portfolio from backend...');
    this.isLoadingPortfolio = true;
    this.portfolioError = null;

    const headers = new HttpHeaders({
      'Authorization': `Bearer ${JwtTokenUtils.getValidJwtToken()}`
    });

    this.http.get<any>(`${environment.backendApiUrl}/portfolio/current`, { headers }).subscribe({
      next: (portfolioResponse) => {
        console.log('[InvestmentConfirmationComponent] ✅ Portfolio loaded:', portfolioResponse);

        if (portfolioResponse && portfolioResponse.portfolioItems) {
          this.userPortfolio = portfolioResponse.portfolioItems.map((item: any) => ({
            symbol: item.symbol,
            name: item.name,
            allocation: item.percentage,
            description: `${item.assetType || 'Investment'}`
          })).sort((a: DefaultStock, b: DefaultStock) => b.allocation - a.allocation);

          this.portfolioType = portfolioResponse.isDefault ? 'auto' : 'custom';
        }

        this.isLoadingPortfolio = false;
      },
      error: (error) => {
        console.error('[InvestmentConfirmationComponent] ❌ Error loading portfolio:', error);
        this.portfolioError = 'Unable to load portfolio. Using default allocation.';
        this.isLoadingPortfolio = false;

        this.userPortfolio = [
          { symbol: 'VTI', name: 'Vanguard Total Stock Market ETF', allocation: 75, description: 'U.S. Stock Market' },
          { symbol: 'VXUS', name: 'Vanguard Total International Stock ETF', allocation: 20, description: 'International Stocks' },
          { symbol: 'VBR', name: 'Vanguard Small-Cap Value ETF', allocation: 5, description: 'Small-Cap Value' }
        ];
      }
    });
  }

  // Helper methods for display
  getSelectedFrequencyDetails() {
    return this.frequencyOptions.find(f => f.value === this.investmentSchedule.payFrequency);
  }

  getInvestmentScheduleDescription(): string {
    if (this.investmentSchedule.scheduleDescription) {
      return this.investmentSchedule.scheduleDescription;
    }

    const frequency = this.getSelectedFrequencyDetails();
    if (!frequency || !this.investmentSchedule.startDate) return '';

    const dateString = this.investmentSchedule.startDate;
    const dateParts = dateString.split('-');
    const startDate = new Date(parseInt(dateParts[0]), parseInt(dateParts[1]) - 1, parseInt(dateParts[2]));

    const dayName = startDate.toLocaleDateString('en-US', { weekday: 'long' });
    const monthDay = startDate.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });

    switch (frequency.value) {
      case 'WEEKLY':
        return `${monthDay}: recurring every week on ${dayName}`;
      case 'BIWEEKLY':
        return `${monthDay}: recurring every 2 weeks on ${dayName}`;
      case 'SEMI_MONTHLY':
        return `${monthDay}: recurring every month on the 1st and 15th`;
      case 'MONTHLY':
        const monthlyDay = startDate.getDate();
        return `${monthDay}: recurring every month on the ${monthlyDay}${this.getOrdinalSuffix(monthlyDay)}`;
      default:
        return '';
    }
  }

  private getOrdinalSuffix(day: number): string {
    const lastDigit = day % 10;
    const lastTwoDigits = day % 100;

    if (lastTwoDigits >= 11 && lastTwoDigits <= 13) {
      return 'th';
    }

    switch (lastDigit) {
      case 1: return 'st';
      case 2: return 'nd';
      case 3: return 'rd';
      default: return 'th';
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

  getMonthlyProjection(): number {
    const frequencyDetails = this.getSelectedFrequencyDetails();
    if (!frequencyDetails) return 0;
    return this.investmentSchedule.investmentAmount * frequencyDetails.paychecksPerMonth;
  }

  getAnnualProjection(): number {
    return this.getMonthlyProjection() * 12;
  }

  onMarketingPreferenceChange(opted: boolean): void {
    // TODO: persist marketing preference to backend
    console.log('[InvestmentConfirmation] Marketing emails opted:', opted);
  }

  // Navigation methods
  editPortfolio(): void {
    console.log('[InvestmentConfirmationComponent] Navigating to portfolio customization');
    this.router.navigate(['/portfolio-customize'], { queryParams: { initial: 'true' } });
  }

  async authorizeRecurringInvestment() {
    if (!this.agreedToTerms) {
      this.toastService.showToast('Please agree to the Terms of Service and Privacy Policy to continue.', 'warning');
      return;
    }

    this.isAuthorizing = true;

    this.authService.completeStep('investmentConfirmation').subscribe({
      next: () => {
        console.log('InvestmentConfirmation step completed successfully');
        this.authService.updateUserProfile({ agreedToMarketing: this.agreedToMarketing }).subscribe();
        const pendingRaw = localStorage.getItem('pendingAcats');
        if (pendingRaw) {
          const pending = JSON.parse(pendingRaw) as { dtc: string; account: string };
          this.investmentService.initiateAcatsTransfer({ dtcNumber: pending.dtc, accountNumber: pending.account }).subscribe({
            next: () => {
              localStorage.removeItem('pendingAcats');
              this.toastService.showToast('Transfer request submitted.', 'success');
              this.router.navigate(['/tabs/tab1'], { replaceUrl: true });
            },
            error: () => {
              this.toastService.showToast('Transfer request failed. Please retry from your profile.', 'danger');
              this.router.navigate(['/tabs/tab1'], { replaceUrl: true });
            }
          });
        } else {
          this.router.navigate(['/tabs/tab1'], { replaceUrl: true });
        }
      },
      error: (err) => {
        console.error('Failed to complete InvestmentConfirmation step:', err);
        this.isAuthorizing = false;
        this.toastService.showToast('Something went wrong. Please try again.', 'danger');
      }
    });
  }

  private getAuthHeaders(): HttpHeaders {
    const token = JwtTokenUtils.getValidJwtToken();
    let headers = new HttpHeaders();
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    return headers;
  }

  isExpanded(tierId: string, featureId: string): boolean {
    return this.expandedFeatures.has(`${tierId}:${featureId}`);
  }

  toggleFeature(tierId: string, featureId: string): void {
    const k = `${tierId}:${featureId}`;
    if (this.expandedFeatures.has(k)) {
      this.expandedFeatures.delete(k);
    } else {
      this.expandedFeatures.clear();
      this.expandedFeatures.add(k);
    }
  }

  pitchHtml(pitch: string): string {
    return pitch.replace(/\. /g, '.<br>');
  }

  savingsChipCopy(tier: PricingTier): string {
    const refs = tier.referralsRequired === 1 ? '1 referral' : `${tier.referralsRequired} referrals`;
    if (this.billingPeriod === 'yearly') {
      return `Save $${tier.yearlySavings}/yr or $${tier.maxYearlySavings}/yr with ${refs}`;
    }
    return `Save $${tier.monthlySavings}/yr with ${refs}`;
  }

  onSlideChange(event: any): void {
    const newIndex = (event.target as any).swiper?.activeIndex ?? this.currentTierIndex;
    this.currentTierIndex = newIndex;
    this.playTierAnimation(newIndex);
  }

  goToSlide(index: number): void {
    (this.pricingSwiperRef?.nativeElement as any)?.swiper?.slideTo(index);
  }

  iconForState(state: FeatureLockState): string {
    switch (state) {
      case 'unlocked':       return 'check';
      case 'unlocks-sooner': return 'arrow_upward';
      case 'locked':         return 'lock';
      case 'coming-soon':    return 'schedule';
    }
  }

  selectTier(tierId: 'core' | 'plus' | 'pro'): void {
    if (this.isAuthorizing) return;
    this.selectedTier = tierId;
    try { localStorage.setItem('fred.selectedTier', tierId); } catch { /* noop */ }

    // TODO: trigger Apple IAP purchase sheet for matching product ID
    // Recommended plugin: @revenuecat/purchases-capacitor
    // On IAP purchase success → proceed to updateUserProfile + authorizeRecurringInvestment
    // On IAP cancel/fail → return early (abort, do nothing)
    // When IAP is wired, this block should call Purchases.purchasePackage(packageForTier(tierId))
    // and only continue to updateUserProfile on successful receipt validation.

    this.authService.updateUserProfile({ selectedTier: tierId, billingPeriod: this.billingPeriod })
      .pipe(catchError(err => {
        console.error('[InvestmentConfirmation] Failed to persist tier selection:', err);
        return of(null);
      }))
      .subscribe(() => {
        this.authorizeRecurringInvestment();
      });
  }
}
