import { fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import { AddBeneficiaryPage } from './add-beneficiary.page';
import { BeneficiaryService, Beneficiary } from '../services/beneficiary.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';

/**
 * Analytics call sites on add-beneficiary.
 *
 * This form holds a name, a date of birth and an SSN, and its toasts quote the server's words
 * about the allocation. None of that may be sent, so the events here carry a category, a surface
 * and an HTTP status and nothing else.
 */
describe('AddBeneficiaryPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let beneficiaryService: jasmine.SpyObj<BeneficiaryService>;
  let pin: jasmine.SpyObj<PinService>;
  let page: AddBeneficiaryPage;

  /** A form the validator passes, so the specs below reach the paths they are about. */
  const complete: Partial<Beneficiary> = {
    beneficiaryType: 'PRIMARY',
    country: 'US',
    firstName: 'Dana',
    lastName: 'Example',
    dateOfBirth: '1990-04-18',
    socialSecurityNumber: '123-45-6789',
    relationship: 'SPOUSE',
    percentageAllocation: 50,
    status: 'PENDING',
  };

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    beneficiaryService = jasmine.createSpyObj<BeneficiaryService>('BeneficiaryService', [
      'getBeneficiaries',
      'createBeneficiary',
      'updateBeneficiary',
    ]);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);

    page = new AddBeneficiaryPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      { params: of({}), queryParams: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      beneficiaryService,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
  });

  // ── error_shown: loading an existing row for edit ──────────────────────────

  it('a row that is not in the response reports load_failed with no status', fakeAsync(() => {
    page.beneficiaryId = 99;
    beneficiaryService.getBeneficiaries.and.returnValue(of([]));

    page['loadBeneficiaryForEdit']();
    tick();

    // The read itself succeeded, so there is no status to report.
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('load_failed', 'portfolio', 'toast', null, false);
  }));

  it('a failed read reports load_failed with its status', fakeAsync(() => {
    page.beneficiaryId = 99;
    beneficiaryService.getBeneficiaries.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page['loadBeneficiaryForEdit']();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('load_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a read that never reached the server reports network', fakeAsync(() => {
    page.beneficiaryId = 99;
    beneficiaryService.getBeneficiaries.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page['loadBeneficiaryForEdit']();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  }));

  // ── error_shown: saving ────────────────────────────────────────────────────

  it('a form the validator rejects reports validation exactly once, whichever field it was', fakeAsync(() => {
    page.beneficiary = { ...complete, firstName: '' };

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(beneficiaryService.createBeneficiary).not.toHaveBeenCalled();
  }));

  it('a badly formatted SSN is the same single validation event, and the value never leaves', fakeAsync(() => {
    page.beneficiary = { ...complete, socialSecurityNumber: '123456789' };

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
  }));

  it('a dismissed step-up before saving reports auth_required', fakeAsync(() => {
    page.beneficiary = { ...complete };
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(false);

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(beneficiaryService.createBeneficiary).not.toHaveBeenCalled();
  }));

  it('a server rejection reports save_failed with its status, not the allocation message', fakeAsync(() => {
    page.beneficiary = { ...complete };
    beneficiaryService.createBeneficiary.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Total allocation cannot exceed 100%. Current total: 100.00%' },
          }),
      ),
    );

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 400, false);
  }));

  it('a save that never reached the server reports network', fakeAsync(() => {
    page.beneficiary = { ...complete };
    beneficiaryService.createBeneficiary.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  }));

  it('a successful save fires nothing from the device', fakeAsync(() => {
    page.beneficiary = { ...complete };
    beneficiaryService.createBeneficiary.and.returnValue(of({ ...complete } as Beneficiary));

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
    expect(analytics.track).not.toHaveBeenCalled();
  }));
});
