import { ChangeDetectorRef } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ModalController, NavController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';

import { KycVerificationComponent } from './kyc-verification.component';
import { AlpacaService } from '../../services/alpaca.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';

/**
 * The KYC form saves a draft on the phone so someone who leaves halfway does not start over.
 * The draft is plain text in localStorage, so the applicant's Social Security Number must never be
 * in it: not when the draft is written, not after a failed submit, and not left over from a build
 * that used to save it. A resumed applicant gets everything back except the number, and retypes it.
 */
describe('KycVerificationComponent: the saved draft never holds the SSN', () => {
  const SSN = '512-34-6789';
  const SSN_DIGITS = '512346789';

  let analytics: jasmine.SpyObj<AnalyticsService>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let alpacaService: jasmine.SpyObj<AlpacaService>;
  let draftKey: string;
  let savedUserId: string | null;

  function build(queryParams: Record<string, string> = {}): KycVerificationComponent {
    const route = { snapshot: { queryParamMap: convertToParamMap(queryParams) } } as unknown as ActivatedRoute;
    const authService = {
      getUnifiedProgress: () => ({}),
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
    } as unknown as AuthService;
    return new KycVerificationComponent(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      route,
      navCtrl,
      authService,
      alpacaService,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['detectChanges']),
      analytics,
      jasmine.createSpyObj<ModalController>('ModalController', ['create']),
    );
  }

  const step1 = {
    givenName: 'Test',
    familyName: 'Person',
    emailAddress: 'test@example.com',
    dateOfBirth: '01/15/1990',
    phoneNumber: '2025550147',
    streetAddress: '1 Main St',
    city: 'Rockville',
    state: 'MD',
    postalCode: '20852',
    taxId: SSN,
    fundingSource: 'employment_income',
  };

  /** Everything this origin has stored, as one string. */
  function everythingStored(): string {
    let all = '';
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)!;
      all += `${key}=${localStorage.getItem(key)}\n`;
    }
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i)!;
      all += `${key}=${sessionStorage.getItem(key)}\n`;
    }
    return all;
  }

  function expectNoSsnStored(): void {
    const all = everythingStored();
    expect(all).not.toContain(SSN);
    expect(all).not.toContain(SSN_DIGITS);
  }

  beforeEach(() => {
    savedUserId = localStorage.getItem('userId');
    localStorage.setItem('userId', '4242');
    draftKey = 'fred_kyc_step1_4242';
    localStorage.removeItem(draftKey);

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track', 'trackOnboardingStepViewed', 'trackOnboardingStepCompleted', 'trackErrorShown', 'screen',
    ]);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    alpacaService = jasmine.createSpyObj<AlpacaService>('AlpacaService', [
      'createAlpacaAccount', 'getKycData', 'updateKyc',
    ]);
    alpacaService.getKycData.and.returnValue(of({}));
  });

  afterEach(() => {
    localStorage.removeItem(draftKey);
    if (savedUserId === null) {
      localStorage.removeItem('userId');
    } else {
      localStorage.setItem('userId', savedUserId);
    }
  });

  it('moving on to part 2 saves the draft without the number, and keeps the rest', async () => {
    const component = build();
    component.ngOnInit();
    component.step1Form.patchValue(step1);

    await component.proceedToStep2();

    const draft = JSON.parse(localStorage.getItem(draftKey)!);
    expect('taxId' in draft).toBeFalse();
    expect(draft.givenName).toBe('Test');
    expect(draft.dateOfBirth).toBe('01/15/1990');
    expect(draft.streetAddress).toBe('1 Main St');
    expectNoSsnStored();
    // The form in memory still has it: the submit on part 2 needs it.
    expect(component.step1Form.value.taxId).toBe(SSN);
  });

  it('a submit the server refuses leaves no number behind on the phone', async () => {
    alpacaService.createAlpacaAccount.and.returnValue(throwError(() => ({ status: 500 })));
    const component = build();
    component.ngOnInit();
    component.step1Form.patchValue(step1);
    await component.proceedToStep2();
    component.step2Form.patchValue({ agreementsChecked: true });

    await component.submitKyc();

    expect(alpacaService.createAlpacaAccount.calls.mostRecent().args[0].taxId).toBe(SSN);
    expect(localStorage.getItem(draftKey)).not.toBeNull();
    expectNoSsnStored();
  });

  it('a resumed applicant lands on part 1 with everything back except the number', () => {
    const { taxId, ...withoutSsn } = step1;
    localStorage.setItem(draftKey, JSON.stringify(withoutSsn));

    const component = build();
    component.ngOnInit();

    expect(component.currentStep).toBe(1);
    expect(component.step1Form.value.givenName).toBe('Test');
    expect(component.step1Form.value.postalCode).toBe('20852');
    expect(component.step1Form.value.taxId).toBe('');
    expect(component.step1Valid).toBeFalse();
    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('kyc_verification', 1, true);
  });

  it('a draft saved by an older build loses its number the moment it is read', () => {
    localStorage.setItem(draftKey, JSON.stringify(step1));

    const component = build();
    component.ngOnInit();

    expect(component.step1Form.value.taxId).toBe('');
    expect(component.step1Form.value.givenName).toBe('Test');
    expect(component.currentStep).toBe(1);
    expectNoSsnStored();
    expect(JSON.parse(localStorage.getItem(draftKey)!).givenName).toBe('Test');
  });

  it('the note about retyping shows only for a restored draft, and goes once the number is typed', () => {
    const fresh = build();
    fresh.ngOnInit();
    expect(fresh.showSsnNotSavedHint).toBeFalse();

    localStorage.setItem(draftKey, JSON.stringify({ givenName: 'Test' }));
    const resumed = build();
    resumed.ngOnInit();
    expect(resumed.showSsnNotSavedHint).toBeTrue();
    resumed.step1Form.patchValue({ taxId: SSN });
    expect(resumed.showSsnNotSavedHint).toBeFalse();
  });

  it('a damaged draft cannot stop the screen from opening', () => {
    localStorage.setItem(draftKey, JSON.stringify({ givenName: 'Test', dateOfBirth: 1151990, phoneNumber: {} }));

    const component = build();
    expect(() => component.ngOnInit()).not.toThrow();

    expect(component.step1Form.value.givenName).toBe('Test');
    expect(component.step1Form.value.dateOfBirth).toBe('');
  });

  it('opening the settings form removes a draft an older build left behind', () => {
    localStorage.setItem(draftKey, JSON.stringify(step1));

    build({ edit: 'true' }).ngOnInit();

    expect(localStorage.getItem(draftKey)).toBeNull();
  });

  it('the settings form (edit mode) writes no draft at all', async () => {
    const component = build({ edit: 'true' });
    component.ngOnInit();
    component.step1Form.patchValue({ ...step1, city: 'Bethesda' });

    await component.proceedToStep2();

    expect(localStorage.getItem(draftKey)).toBeNull();
    expectNoSsnStored();
  });
});
