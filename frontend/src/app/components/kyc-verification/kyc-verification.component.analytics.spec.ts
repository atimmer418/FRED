import { ChangeDetectorRef } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { NavController, ToastController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { AlpacaAccountResponse, AlpacaService } from '../../services/alpaca.service';
import { AuthService, UserProgress } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { KycVerificationComponent } from './kyc-verification.component';

const EMPTY_PROGRESS: UserProgress = {
  getStartedCompleted: true,
  surveyInitialCompleted: true,
  fiPlanResultsCompleted: true,
  authFinalizeCompleted: true,
  kycVerificationCompleted: false,
  linkPlaidCompleted: false,
  investmentScheduleCompleted: false,
  investmentConfirmationCompleted: false,
};

describe('KycVerificationComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let toastController: jasmine.SpyObj<ToastController>;
  let cdr: jasmine.SpyObj<ChangeDetectorRef>;
  let alpacaService: jasmine.SpyObj<AlpacaService>;
  let authService: AuthService;
  let component: KycVerificationComponent;
  let draftKey: string;

  function build(queryParams: Record<string, string> = {}): KycVerificationComponent {
    const route = {
      snapshot: { queryParamMap: convertToParamMap(queryParams) },
    } as unknown as ActivatedRoute;
    return new KycVerificationComponent(
      router,
      route,
      navCtrl,
      authService,
      alpacaService,
      toastController,
      cdr,
      analytics,
    );
  }

  /** A complete, valid part-1 form. Entirely invented data; none of it is ever sent to analytics. */
  function fillStep1(target: KycVerificationComponent): void {
    target.step1Form.patchValue({
      givenName: 'Test',
      familyName: 'User',
      emailAddress: 'test@example.com',
      dateOfBirth: '01/15/1990',
      phoneNumber: '2025550147',
      streetAddress: '1 Main St',
      city: 'Rockville',
      state: 'MD',
      postalCode: '20852',
      taxId: '000-00-0000',
      fundingSource: 'employment_income',
    });
  }

  beforeEach(() => {
    draftKey = `fred_kyc_step1_${localStorage.getItem('userId') || 'anon'}`;
    localStorage.removeItem(draftKey);

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    cdr = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['detectChanges']);
    toastController = jasmine.createSpyObj<ToastController>('ToastController', ['create']);
    toastController.create.and.returnValue(
      Promise.resolve({ present: () => Promise.resolve() } as unknown as HTMLIonToastElement),
    );
    alpacaService = jasmine.createSpyObj<AlpacaService>('AlpacaService', [
      'createAlpacaAccount',
      'getKycData',
      'updateKyc',
    ]);
    alpacaService.getKycData.and.returnValue(of({}));
    authService = {
      getUnifiedProgress: () => EMPTY_PROGRESS,
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
    } as unknown as AuthService;

    component = build();
  });

  afterEach(() => {
    localStorage.removeItem(draftKey);
  });

  it('reports part 1 as viewed and not resumed with no saved draft', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('kyc_verification', 1, false);
  });

  it('reports part 2 as viewed and resumed when a draft put the user back on the second page', () => {
    localStorage.setItem(draftKey, JSON.stringify({ givenName: 'Test' }));

    component = build();
    component.ngOnInit();

    expect(component.currentStep).toBe(2);
    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('kyc_verification', 2, true);
  });

  it('reports the move to part 2 as a second viewed event', async () => {
    component.ngOnInit();
    fillStep1(component);
    analytics.trackOnboardingStepViewed.calls.reset();

    await component.proceedToStep2();

    expect(component.currentStep).toBe(2);
    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledOnceWith('kyc_verification', 2, false);
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });

  it('reports the step completed at submit, carrying the sub-step it was submitted from', async () => {
    component.ngOnInit();
    fillStep1(component);
    component.currentStep = 2;
    component.step2Form.patchValue({ agreementsChecked: true });
    alpacaService.createAlpacaAccount.and.returnValue(of({} as AlpacaAccountResponse));

    await component.submitKyc();

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('kyc_verification', 2);
  });

  it('reports a failed account creation as a save failure, with the status and no message', async () => {
    component.ngOnInit();
    fillStep1(component);
    component.currentStep = 2;
    component.step2Form.patchValue({ agreementsChecked: true });
    alpacaService.createAlpacaAccount.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 422, error: { message: 'Taxpayer id is invalid' } })),
    );

    await component.submitKyc();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('save_failed', 'onboarding', 'toast', 422, true);
    const everySentArgument = JSON.stringify(analytics.trackErrorShown.calls.allArgs());
    expect(everySentArgument).not.toContain('Taxpayer');
  });

  it('reports a connection that never reached the server as a network error', async () => {
    component.ngOnInit();
    fillStep1(component);
    component.currentStep = 2;
    component.step2Form.patchValue({ agreementsChecked: true });
    alpacaService.createAlpacaAccount.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    await component.submitKyc();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('network', 'onboarding', 'toast', null, true);
  });

  it('sends no onboarding event when the same screen is reused as a settings form', async () => {
    component = build({ edit: 'true' });

    component.ngOnInit();
    fillStep1(component);
    await component.proceedToStep2();

    expect(analytics.trackOnboardingStepViewed).not.toHaveBeenCalled();
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });
});
