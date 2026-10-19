import { of } from 'rxjs';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { SurveyInitialComponent } from './surveyinitial.component';

const MONTHLY_INVESTMENT_KEY = 'surveyMonthlyInvestment';
const RETIREMENT_INCOME_KEY = 'surveyRetirementIncome';

describe('SurveyInitialComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let authService: AuthService;
  let component: SurveyInitialComponent;

  function build(): SurveyInitialComponent {
    return new SurveyInitialComponent(router, authService, navCtrl, analytics);
  }

  beforeEach(() => {
    localStorage.removeItem(MONTHLY_INVESTMENT_KEY);
    localStorage.removeItem(RETIREMENT_INCOME_KEY);

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack']);
    authService = {
      userProgress$: of(null),
      updateProgress: jasmine.createSpy('updateProgress').and.returnValue(of({})),
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
    } as unknown as AuthService;

    component = build();
  });

  afterEach(() => {
    localStorage.removeItem(MONTHLY_INVESTMENT_KEY);
    localStorage.removeItem(RETIREMENT_INCOME_KEY);
  });

  it('reports the step as viewed and not resumed with nothing saved', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('survey_initial', null, false);
  });

  it('reports resumed when the screen opened with saved survey values', () => {
    localStorage.setItem(MONTHLY_INVESTMENT_KEY, '3000');

    component = build();
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('survey_initial', null, true);
  });

  it('sends nothing while the sliders move', () => {
    component.ngOnInit();
    analytics.track.calls.reset();

    component.retirementIncome = 9000;
    component.onSliderChange();
    component.monthlyInvestment = 3000;
    component.onSliderChange();

    expect(analytics.track).not.toHaveBeenCalled();
  });

  it('reports the plan once at viewMyPlan, with no change flags when nothing moved', () => {
    component.ngOnInit();

    component.viewMyPlan();

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('survey_initial', null);
    expect(analytics.track).toHaveBeenCalledTimes(1);
    expect(analytics.track).toHaveBeenCalledWith({
      name: 'freedom_plan_changed',
      props: {
        surface: 'onboarding',
        income_changed: false,
        invest_changed: false,
        preset_changed: false,
        monthly_income_band: '5k_9999',
        monthly_invest_band: '1k_2499',
        return_preset: null,
        years_to_freedom: 'over_20',
      },
    });
  });

  it('flags the income as changed when the user moved only that slider', () => {
    component.ngOnInit();

    component.retirementIncome = 8000;
    component.onSliderChange();
    component.viewMyPlan();

    expect(analytics.track).toHaveBeenCalledWith({
      name: 'freedom_plan_changed',
      props: {
        surface: 'onboarding',
        income_changed: true,
        invest_changed: false,
        preset_changed: false,
        monthly_income_band: '5k_9999',
        monthly_invest_band: '1k_2499',
        return_preset: null,
        years_to_freedom: 'over_20',
      },
    });
  });

  it("bands an unreachable freedom date as 'never'", () => {
    component.ngOnInit();

    component.monthlyInvestment = 0;
    component.onSliderChange();
    component.viewMyPlan();

    expect(component.timeToFI).toBe('∞');
    expect(analytics.track).toHaveBeenCalledWith({
      name: 'freedom_plan_changed',
      props: {
        surface: 'onboarding',
        income_changed: false,
        invest_changed: true,
        preset_changed: false,
        monthly_income_band: '5k_9999',
        monthly_invest_band: 'under_50',
        return_preset: null,
        years_to_freedom: 'never',
      },
    });
  });
});
