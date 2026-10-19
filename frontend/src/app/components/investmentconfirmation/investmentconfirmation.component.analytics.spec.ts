import { ChangeDetectorRef } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { InvestmentService } from '../../services/investment.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { InvestmentConfirmationComponent } from './investmentconfirmation.component';

const STEP_KEY = 'fred.investmentConfirmation.step';

describe('InvestmentConfirmationComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let toastService: jasmine.SpyObj<ToastService>;
  let cdr: jasmine.SpyObj<ChangeDetectorRef>;
  let completeStep: jasmine.Spy;
  let authService: AuthService;
  let investmentService: InvestmentService;
  let http: HttpClient;
  let component: InvestmentConfirmationComponent;

  function build(): InvestmentConfirmationComponent {
    return new InvestmentConfirmationComponent(
      router,
      authService,
      investmentService,
      navCtrl,
      http,
      toastService,
      cdr,
      analytics,
    );
  }

  beforeEach(() => {
    localStorage.removeItem(STEP_KEY);
    localStorage.removeItem('pendingAcats');
    localStorage.removeItem('fred.billingPeriod');

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    cdr = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['detectChanges']);
    completeStep = jasmine.createSpy('completeStep').and.returnValue(of({}));
    authService = {
      getCurrentProgress: () => null,
      getUserProgress: () => of({ monthlyInvestment: 2000 }),
      getCurrentInvestmentSchedule: () => of(null),
      getCurrentUserEmail: () => null,
      updateUserProfile: jasmine.createSpy('updateUserProfile').and.returnValue(of({})),
      completeStep,
    } as unknown as AuthService;
    investmentService = {
      pendingAcatsRequest: null,
      initiateAcatsTransfer: jasmine.createSpy('initiateAcatsTransfer').and.returnValue(of({})),
    } as unknown as InvestmentService;
    http = { get: jasmine.createSpy('get').and.returnValue(of(null)) } as unknown as HttpClient;

    component = build();
  });

  afterEach(() => {
    localStorage.removeItem(STEP_KEY);
    localStorage.removeItem('pendingAcats');
    localStorage.removeItem('fred.billingPeriod');
  });

  it('reports the first internal page as viewed and not resumed', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('investment_confirmation', 1, false);
  });

  it('reports the trust page as viewed and resumed when the saved step puts the user there', () => {
    localStorage.setItem(STEP_KEY, '2');

    component = build();
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('investment_confirmation', 2, true);
  });

  it('reports a second viewed event when the user moves to the trust page', async () => {
    component.ngOnInit();
    component.agreedToTerms = true;
    analytics.trackOnboardingStepViewed.calls.reset();

    await component.proceedToTrustStep();

    expect(component.currentStep).toBe(2);
    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledOnceWith('investment_confirmation', 2, false);
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });

  it('reports the marketing opt-in as a settings change', () => {
    component.ngOnInit();

    component.onMarketingPreferenceChange(true);

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'marketing_emails', enabled: true, value: null, outcome: 'applied' },
    });
  });

  it('reports the marketing opt-out with the same shape', () => {
    component.ngOnInit();

    component.onMarketingPreferenceChange(false);

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'marketing_emails', enabled: false, value: null, outcome: 'applied' },
    });
  });

  it('reports the step completed at the authorize action, carrying the sub-step', async () => {
    component.ngOnInit();
    component.agreedToTerms = true;
    component.currentStep = 2;

    await component.authorizeRecurringInvestment();

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('investment_confirmation', 2);
    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledBefore(completeStep);
  });

  it('reports the terms warning as a validation error and completes nothing', async () => {
    component.ngOnInit();
    component.agreedToTerms = false;

    await component.authorizeRecurringInvestment();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('validation', 'onboarding', 'toast', null, false);
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
    expect(toastService.showToast).toHaveBeenCalled();
  });

  it('sends nothing when a tier card is merely expanded or the billing toggle is flipped', () => {
    component.ngOnInit();
    analytics.track.calls.reset();

    component.toggleFeature('plus', 'montecarlo');
    component.setBillingPeriod('yearly');

    expect(analytics.track).not.toHaveBeenCalled();
  });
});
