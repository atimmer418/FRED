import { ChangeDetectorRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { FiPlanResultsComponent } from './fi-plan-results.component';

const SAVED_STRATEGY_KEY = 'fiPlanSelectedStrategy';

describe('FiPlanResultsComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let cdr: jasmine.SpyObj<ChangeDetectorRef>;
  let authService: AuthService;
  let component: FiPlanResultsComponent;

  function build(): FiPlanResultsComponent {
    const route = {
      queryParams: of({ t: '23.5', rI: 90000, mI: 2000 }),
    } as unknown as ActivatedRoute;
    return new FiPlanResultsComponent(route, router, authService, cdr, navCtrl, analytics);
  }

  beforeEach(() => {
    localStorage.removeItem(SAVED_STRATEGY_KEY);
    localStorage.removeItem('fiPlanTimeToFI');
    localStorage.removeItem('fiPlanTargetPortfolio');

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack']);
    cdr = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['detectChanges']);
    authService = {
      userProgress$: of(null),
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
    } as unknown as AuthService;

    component = build();
  });

  afterEach(() => {
    localStorage.removeItem(SAVED_STRATEGY_KEY);
    localStorage.removeItem('fiPlanTimeToFI');
    localStorage.removeItem('fiPlanTargetPortfolio');
  });

  it('reports the step as viewed and not resumed with no saved strategy', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('fi_plan_results', null, false);
  });

  it('reports resumed when a strategy was already chosen on this device', () => {
    localStorage.setItem(SAVED_STRATEGY_KEY, 'guardrails');

    component = build();
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('fi_plan_results', null, true);
  });

  it('sends nothing when a card is merely tapped', () => {
    component.ngOnInit();
    analytics.track.calls.reset();

    component.selectStrategy('sbloc');

    expect(analytics.track).not.toHaveBeenCalled();
  });

  it('reports the chosen strategy and the completed step at confirm', () => {
    component.ngOnInit();
    component.selectStrategy('annuity');
    analytics.track.calls.reset();

    component.confirmSelection();

    expect(analytics.track).toHaveBeenCalledTimes(1);
    expect(analytics.track).toHaveBeenCalledWith({
      name: 'plan_strategy_selected',
      props: { strategy: 'annuity' },
    });
    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('fi_plan_results', null);
  });

  it('sends no strategy for an id outside the taxonomy, but still completes the step', () => {
    component.ngOnInit();
    component.selectedStrategyId = 'balanced';
    analytics.track.calls.reset();

    component.confirmSelection();

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('fi_plan_results', null);
  });
});
