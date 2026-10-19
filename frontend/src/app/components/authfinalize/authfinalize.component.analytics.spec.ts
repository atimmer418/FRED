import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { PasskeyService } from '../../services/passkey.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { AuthFinalizeComponent } from './authfinalize.component';

const PLAN_KEYS = [
  'fiPlanSelectedStrategy',
  'fiPlanTimeToFI',
  'fiPlanTargetPortfolio',
  'surveyRetirementIncome',
  'surveyMonthlyInvestment',
  'authFinalizeData',
];

describe('AuthFinalizeComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let passkeyService: jasmine.SpyObj<PasskeyService>;
  let authService: AuthService;
  let jwtBefore: string | null;

  /** Builds the component against the given query params; `{}` forces the saved-data fallback. */
  function build(queryParams: Record<string, string>): AuthFinalizeComponent {
    const route = {
      queryParamMap: of(convertToParamMap(queryParams)),
      snapshot: { queryParams: {} },
    } as unknown as ActivatedRoute;
    return new AuthFinalizeComponent(route, router, navCtrl, passkeyService, authService, analytics);
  }

  const FULL_PARAMS = { plan: 'yield', t: '23.5', p: '2250000', rI: '90000', mI: '2000' };

  beforeEach(() => {
    jwtBefore = localStorage.getItem('jwtToken');
    PLAN_KEYS.forEach((key) => localStorage.removeItem(key));

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack']);
    passkeyService = jasmine.createSpyObj<PasskeyService>('PasskeyService', [
      'startRegistration',
      'finishRegistration',
    ]);
    authService = {
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
      handleSuccessfulAuthentication: jasmine.createSpy('handleSuccessfulAuthentication'),
      clearLocalStorageProgressFlags: jasmine.createSpy('clearLocalStorageProgressFlags'),
    } as unknown as AuthService;
  });

  afterEach(() => {
    PLAN_KEYS.forEach((key) => localStorage.removeItem(key));
    if (jwtBefore === null) {
      localStorage.removeItem('jwtToken');
    } else {
      localStorage.setItem('jwtToken', jwtBefore);
    }
  });

  it('reports the step as viewed and not resumed when the plan arrived in the query params', () => {
    const component = build(FULL_PARAMS);

    component.ngOnInit();

    expect(component.hasRequiredParams).toBeTrue();
    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('auth_finalize', null, false);
  });

  it('reports resumed when the plan had to be rebuilt from storage', () => {
    localStorage.setItem('fiPlanSelectedStrategy', 'yield');
    localStorage.setItem('fiPlanTimeToFI', '23.5');
    localStorage.setItem('fiPlanTargetPortfolio', '2250000');
    localStorage.setItem('surveyRetirementIncome', '90000');
    localStorage.setItem('surveyMonthlyInvestment', '2000');
    const component = build({});

    component.ngOnInit();

    expect(component.hasRequiredParams).toBeTrue();
    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('auth_finalize', null, true);
  });

  it('reports a cancelled or failed passkey as an error, with no message and no completion', async () => {
    const component = build(FULL_PARAMS);
    component.ngOnInit();
    component.registerForm.get('email')!.setValue('someone@example.com');
    // Unparseable options take the same path a cancelled OS sheet does: the try/catch around
    // credential creation.
    passkeyService.startRegistration.and.returnValue(of({ options: '{not-json' }));

    await component.createPasskey();

    expect(component.errorMessage).toBe('Passkey creation was cancelled or failed.');
    expect(analytics.trackErrorShown).toHaveBeenCalledWith('passkey', 'onboarding', 'inline_banner', null, true);
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });

  it('reports the step completed once the credential is registered, not at the tap', async () => {
    const component = build(FULL_PARAMS);
    component.ngOnInit();
    component.registerForm.get('email')!.setValue('someone@example.com');
    passkeyService.startRegistration.and.returnValue(of({ options: '{}' }));
    passkeyService.finishRegistration.and.returnValue(
      of({ success: true, message: 'ok', jwtToken: 'header.payload.signature', userId: 1 }),
    );

    // The dev bypass drives the same finishRegistration path without the OS sheet.
    await component.bypassAuthID();

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('auth_finalize', null);
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  });

  it('never sends the email the user typed', async () => {
    const component = build(FULL_PARAMS);
    component.ngOnInit();
    component.registerForm.get('email')!.setValue('someone@example.com');
    passkeyService.startRegistration.and.returnValue(of({ options: '{not-json' }));

    await component.createPasskey();

    const everySentArgument = JSON.stringify([
      analytics.track.calls.allArgs(),
      analytics.trackOnboardingStepViewed.calls.allArgs(),
      analytics.trackOnboardingStepCompleted.calls.allArgs(),
      analytics.trackErrorShown.calls.allArgs(),
    ]);
    expect(everySentArgument).not.toContain('someone@example.com');
  });
});
