import { Component, OnInit, HostBinding } from '@angular/core';
import { ViewWillEnter } from '@ionic/angular';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { toAmountBand, toYearsBand } from '../../services/analytics/analytics-bands';
import {
  IonContent,
  IonHeader,
  IonToolbar,
  IonFooter,
  IonSpinner,
  NavController,
} from '@ionic/angular/standalone';

@Component({
  selector: 'app-surveyinitial',
  templateUrl: './surveyinitial.component.html',
  styleUrls: ['./surveyinitial.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonContent,
    IonHeader,
    IonToolbar,
    IonFooter,
    IonSpinner,
  ],
})
export class SurveyInitialComponent implements OnInit, ViewWillEnter {
  @HostBinding('class.page-ready') isReady = false;

  // --- User Input Properties ---
  monthlyInvestment: number = 2000;
  retirementIncome: number = 7500;

  formattedMonthlyInvestment: string = '2,000';
  formattedRetirementIncome: string = '7,500';

  // --- Display Properties ---
  timeToFI: string = '';
  isCalculationExpanded: boolean = false;
  isLoading: boolean = false;

  // --- Economic Assumptions for the SWR/FIRE calculation ---
  private readonly AVG_MARKET_YIELD = 0.10;     // ~10% average annual return
  private readonly SAFE_WITHDRAWAL_RATE = 0.04; // 4% safe withdrawal rate

  /**
   * Whether the USER moved each control, recorded at the handlers rather than inferred by comparing
   * against an opening snapshot. The subscription in loadSavedValues() lives for the lifetime of the
   * screen and rewrites both values whenever progress re-emits, so a snapshot comparison would
   * report a change the user never made (and, after a save round-trip, miss one they did).
   */
  private userChangedInvest = false;
  private userChangedIncome = false;

  // Last values this component applied itself, so a handler can tell a real edit from an echo.
  private lastKnownMonthlyInvestment = 0;
  private lastKnownRetirementIncome = 0;

  /** True when saved values (user progress or localStorage) were found for this step. */
  private resumedFromSavedValues = false;

  constructor(
    private router: Router,
    private authService: AuthService,
    private navCtrl: NavController,
    private analytics: AnalyticsService
  ) {
  }

  ngOnInit() {
    this.loadSavedValues();

    // Clamp to the new slider ranges after loading
    if (this.monthlyInvestment < 100) this.monthlyInvestment = 100;
    if (this.monthlyInvestment > 5000) this.monthlyInvestment = 5000;
    if (this.retirementIncome < 3000) this.retirementIncome = 3000;
    if (this.retirementIncome > 15000) this.retirementIncome = 15000;

    this.updateFormattedValues();
    this.calculateFITimeline();

    this.rememberCurrentValues();
    this.analytics.trackOnboardingStepViewed('survey_initial', null, this.resumedFromSavedValues);

    this.preloadAssets();
  }

  ionViewWillEnter() {
    this.isLoading = false;
  }

  private preloadAssets() {
    const img = new Image();
    img.src = '/assets/images/whenPiggybanksFly-3.jpg';

    const imageAlreadyCached = img.complete;
    const fontsAlreadyLoaded =
      document.fonts.check('700 16px "Manrope"') &&
      document.fonts.check('400 24px "Material Symbols Outlined"');

    if (imageAlreadyCached && fontsAlreadyLoaded) {
      this.isReady = true;
      return;
    }

    const imageReady = imageAlreadyCached
      ? Promise.resolve()
      : new Promise<void>(resolve => {
          img.onload = () => resolve();
          img.onerror = () => resolve();
        });

    const fontsReady = Promise.all([
      document.fonts.load('700 16px "Manrope"').catch(() => []),
      document.fonts.load('400 24px "Material Symbols Outlined"').catch(() => []),
    ]);

    const timeout = new Promise<void>(resolve => setTimeout(resolve, 2000));

    Promise.race([Promise.all([fontsReady, imageReady]), timeout]).then(() => {
      this.isReady = true;
    });
  }

  goBack() {
    this.navCtrl.navigateBack('/get-started');
  }

  get formattedTargetPortfolio(): string {
    const target = (this.retirementIncome * 12) / this.SAFE_WITHDRAWAL_RATE;
    const rounded = Math.round(target / 100_000) * 100_000;
    if (rounded >= 1_000_000) {
      return '$' + (rounded / 1_000_000).toFixed(1) + 'M';
    }
    return '$' + (rounded / 1_000).toFixed(0) + 'K';
  }

  getSliderGradient(value: number, min: number, max: number): string {
    const pct = ((value - min) / (max - min)) * 100;
    return `linear-gradient(to right, #2563EB ${pct}%, #e5e7eb ${pct}%)`;
  }

  // Load saved values from localStorage or user progress
  private loadSavedValues() {
    // First try to get values from user progress (for authenticated users)
    this.authService.userProgress$.subscribe(progress => {
      if (progress && progress.monthlyInvestment !== undefined) {
        this.monthlyInvestment = progress.monthlyInvestment;
        this.resumedFromSavedValues = true;
      }
      if (progress && progress.retirementIncome !== undefined) {
        this.retirementIncome = progress.retirementIncome / 12;
        this.resumedFromSavedValues = true;
      }
      // This subscription stays alive for the life of the screen and fires again after every
      // save. Re-baseline so the server's echo of our own write is never read as a user edit.
      this.rememberCurrentValues();
      this.updateFormattedValues();
    });

    // Fallback to localStorage for any missing values (for non-authenticated users)
    const savedMonthlyInvestment = localStorage.getItem('surveyMonthlyInvestment');
    const savedRetirementIncome = localStorage.getItem('surveyRetirementIncome');

    if (savedMonthlyInvestment && this.monthlyInvestment === 2000) {
      this.monthlyInvestment = parseInt(savedMonthlyInvestment, 10);
      this.resumedFromSavedValues = true;
    }

    if (savedRetirementIncome && this.retirementIncome === 7500) {
      this.retirementIncome = parseInt(savedRetirementIncome, 10) / 12;
      this.resumedFromSavedValues = true;
    }

    this.updateFormattedValues();
    console.log('[SurveyInitial] Loaded saved values:', {
      monthlyInvestment: this.monthlyInvestment,
      retirementIncome: this.retirementIncome
    });
  }

  // Save values to localStorage and update user progress
  private saveValues() {
    localStorage.setItem('surveyMonthlyInvestment', this.monthlyInvestment.toString());
    localStorage.setItem('surveyRetirementIncome', (this.retirementIncome * 12).toString());

    this.authService.updateProgress({
      monthlyInvestment: this.monthlyInvestment,
      retirementIncome: this.retirementIncome * 12
    }).subscribe({
      next: (_) => {
        console.log('[SurveyInitial] Updated user progress with values:', {
          monthlyInvestment: this.monthlyInvestment,
          retirementIncome: this.retirementIncome
        });
      },
      error: (error) => {
        console.log('[SurveyInitial] Could not update user progress (user may not be authenticated):', error);
      }
    });
  }

  // --- Event Handlers ---
  onSliderChange() {
    // Both sliders share this handler, so which one moved is decided by comparing against the
    // values this component last applied. Recording only, never a dispatch: this runs per tick.
    this.noteUserEdits();
    this.updateFormattedValues();
    this.calculateFITimeline();
    this.saveValues();
  }

  unformatMonthlyInvestment() {
    this.formattedMonthlyInvestment = this.monthlyInvestment.toString();
  }

  formatAndSetMonthlyInvestment() {
    const numericValue = parseInt(this.formattedMonthlyInvestment.replace(/,/g, ''), 10);
    this.monthlyInvestment = isNaN(numericValue) ? 100 : numericValue;
    this.noteUserEdits();
    this.updateFormattedValues();
    this.calculateFITimeline();
    this.saveValues();
  }

  unformatRetirementIncome() {
    this.formattedRetirementIncome = this.retirementIncome.toString();
  }

  formatAndSetRetirementIncome() {
    const numericValue = parseInt(this.formattedRetirementIncome.replace(/,/g, ''), 10);
    this.retirementIncome = isNaN(numericValue) ? 3000 : numericValue;
    this.noteUserEdits();
    this.updateFormattedValues();
    this.calculateFITimeline();
    this.saveValues();
  }

  /** Flags the controls whose value differs from what this component last applied. */
  private noteUserEdits() {
    if (this.monthlyInvestment !== this.lastKnownMonthlyInvestment) {
      this.userChangedInvest = true;
    }
    if (this.retirementIncome !== this.lastKnownRetirementIncome) {
      this.userChangedIncome = true;
    }
    this.rememberCurrentValues();
  }

  private rememberCurrentValues() {
    this.lastKnownMonthlyInvestment = this.monthlyInvestment;
    this.lastKnownRetirementIncome = this.retirementIncome;
  }

  private updateFormattedValues() {
    this.formattedMonthlyInvestment = this.monthlyInvestment.toLocaleString('en-US');
    this.formattedRetirementIncome = this.retirementIncome.toLocaleString('en-US');
  }

  /**
   * Calculates the timeline to reach the FIRE number based on the 4% rule.
   */
  private calculateFITimeline() {
    const targetPortfolio = (this.retirementIncome * 12) / this.SAFE_WITHDRAWAL_RATE;
    const monthlyRate = this.AVG_MARKET_YIELD / 12;
    if (this.monthlyInvestment <= 0) {
      this.timeToFI = '∞';
      return;
    }

    const numberOfMonths = Math.log((targetPortfolio * monthlyRate / this.monthlyInvestment) + 1) / Math.log(1 + monthlyRate);
    const years = numberOfMonths / 12;
    this.timeToFI = isFinite(years) ? years.toFixed(1) : '∞';
  }

  toggleCalculationInfo() {
    this.isCalculationExpanded = !this.isCalculationExpanded;
  }

  /**
   * Navigates to the results page where the SWR, SBLOC, and Annuity options will be shown.
   */
  viewMyPlan() {
    this.isLoading = true;

    // The one save point for this screen. The sliders write to the server on every tick, so the
    // plan event fires here and nowhere else, carrying bands plus what-actually-changed flags.
    this.analytics.trackOnboardingStepCompleted('survey_initial', null);
    this.analytics.track({
      name: 'freedom_plan_changed',
      props: {
        surface: 'onboarding',
        income_changed: this.userChangedIncome,
        invest_changed: this.userChangedInvest,
        // No rate control on this screen, so the preset cannot have moved. Kept explicit rather
        // than optional so a future rate control on onboarding has to decide what to send.
        preset_changed: false,
        // retirementIncome is held MONTHLY on this screen, which is the unit the property names.
        monthly_income_band: toAmountBand(this.retirementIncome),
        monthly_invest_band: toAmountBand(this.monthlyInvestment),
        // No rate control on this screen; the presets live on the freedom sheet.
        return_preset: null,
        // timeToFI is a formatted string, and the unreachable case is the literal infinity glyph,
        // which parses to NaN and bands as 'never'.
        years_to_freedom: toYearsBand(parseFloat(this.timeToFI)),
      },
    });

    console.log('Navigating to results with:', {
      monthly: this.monthlyInvestment,
      annual: this.retirementIncome,
      years: this.timeToFI
    });

    this.authService.completeStep('surveyInitial').subscribe({
      next: (response) => {
        console.log('SurveyInitial step completed successfully:', response);

        const localStorageValue = localStorage.getItem('surveyInitialCompleted');
        console.log('SurveyInitial localStorage value after completion:', localStorageValue);

        this.authService.userProgress$.subscribe(progress => {
          if (progress) {
            console.log('SurveyInitial current progress after completion:', progress.surveyInitialCompleted);
          }
        });

        this.router.navigate(['/fi-plan-results'], {
          queryParams: {
            mI: this.monthlyInvestment,
            rI: this.retirementIncome * 12,
            t: this.timeToFI
          }
        });
      },
      error: (err) => {
        console.log('SurveyInitial step could not be completed (likely not authenticated yet):', err);

        const localStorageValue = localStorage.getItem('surveyInitialCompleted');
        console.log('SurveyInitial localStorage value after failed completion:', localStorageValue);

        this.router.navigate(['/fi-plan-results'], {
          queryParams: {
            mI: this.monthlyInvestment,
            rI: this.retirementIncome * 12,
            t: this.timeToFI
          }
        });
      }
    });
  }
}
