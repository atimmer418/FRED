import { fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { ActionSheetController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { BeneficiariesPage } from './beneficiaries.page';
import { BeneficiaryService, Beneficiary, BeneficiaryAllocationSummary } from '../services/beneficiary.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';

/** The one part of the action-sheet config these specs drive. */
interface SheetButton {
  text: string;
  handler?: () => unknown;
}

/**
 * Analytics call sites on beneficiaries.
 *
 * Every toast on this page interpolates a beneficiary's full name, so the category is the only
 * thing that may be sent. Submitting to Alpaca is a compliance fact the server reports, so only
 * the failures are tracked here.
 */
describe('BeneficiariesPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let beneficiaryService: jasmine.SpyObj<BeneficiaryService>;
  let actionSheet: jasmine.SpyObj<ActionSheetController>;
  let sheetButtons: SheetButton[];
  let page: BeneficiariesPage;

  const summary: BeneficiaryAllocationSummary = {
    primaryTotalAllocation: 100,
    contingentTotalAllocation: 0,
    primaryCount: 1,
    contingentCount: 0,
    maxPrimaryAllowed: 5,
    maxContingentAllowed: 5,
    primaryRemainingAllocation: 0,
    contingentRemainingAllocation: 100,
    primaryAllocationComplete: true,
    contingentAllocationComplete: false,
  };

  const beneficiary: Beneficiary = {
    id: 42,
    beneficiaryType: 'PRIMARY',
    firstName: 'Dana',
    lastName: 'Example',
    relationship: 'SPOUSE',
    percentageAllocation: 100,
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
      'getAllocationSummary',
      'submitBeneficiary',
      'submitAllBeneficiaries',
      'deleteBeneficiary',
    ]);
    beneficiaryService.getBeneficiaries.and.returnValue(of([beneficiary]));
    beneficiaryService.getAllocationSummary.and.returnValue(of(summary));

    sheetButtons = [];
    actionSheet = jasmine.createSpyObj<ActionSheetController>('ActionSheetController', ['create']);
    actionSheet.create.and.callFake((opts) => {
      sheetButtons = (opts?.buttons ?? []) as unknown as SheetButton[];
      return Promise.resolve({ present: () => Promise.resolve() } as unknown as HTMLIonActionSheetElement);
    });

    page = new BeneficiariesPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      beneficiaryService,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      actionSheet,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
  });

  // ── error_shown: loading ───────────────────────────────────────────────────

  it('a failed load reports load_failed with its status', fakeAsync(() => {
    beneficiaryService.getBeneficiaries.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.ionViewWillEnter();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('load_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a load that never reached the server reports network', fakeAsync(() => {
    beneficiaryService.getBeneficiaries.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.ionViewWillEnter();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  }));

  it('a successful load fires nothing', fakeAsync(() => {
    page.ionViewWillEnter();
    tick();

    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
    expect(analytics.track).not.toHaveBeenCalled();
  }));

  // ── error_shown: submitting one ────────────────────────────────────────────

  it('submitting a row with no id reports validation', fakeAsync(() => {
    page.onSubmitBeneficiary({ ...beneficiary, id: undefined });
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(beneficiaryService.submitBeneficiary).not.toHaveBeenCalled();
  }));

  it('a failed submit reports save_failed with its status, never the server message', fakeAsync(() => {
    beneficiaryService.submitBeneficiary.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 422, error: { message: 'Dana Example was rejected' } })),
    );

    page.onSubmitBeneficiary(beneficiary);
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 422, false);
  }));

  // ── error_shown: submitting all ────────────────────────────────────────────

  it('submitting all with nothing pending reports validation', fakeAsync(() => {
    page.beneficiaries = [{ ...beneficiary, status: 'APPROVED' }];

    page.onSubmitAllBeneficiaries();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(beneficiaryService.submitAllBeneficiaries).not.toHaveBeenCalled();
  }));

  it('a failed submit-all reports save_failed with its status', fakeAsync(() => {
    page.beneficiaries = [beneficiary];
    beneficiaryService.submitAllBeneficiaries.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 503 })),
    );

    page.onSubmitAllBeneficiaries();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 503, false);
  }));

  // ── error_shown: deleting ──────────────────────────────────────────────────

  it('deleting a row with no id reports validation', fakeAsync(() => {
    page.onDeleteBeneficiary({ ...beneficiary, id: undefined });
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(actionSheet.create).not.toHaveBeenCalled();
  }));

  it('a failed delete, confirmed through the action sheet, reports save_failed', fakeAsync(() => {
    beneficiaryService.deleteBeneficiary.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.onDeleteBeneficiary(beneficiary);
    tick();

    const deleteButton = sheetButtons.find((button) => button.text === 'Delete');
    deleteButton?.handler?.();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a successful delete fires nothing from the device', fakeAsync(() => {
    beneficiaryService.deleteBeneficiary.and.returnValue(of(undefined));

    page.onDeleteBeneficiary(beneficiary);
    tick();

    sheetButtons.find((button) => button.text === 'Delete')?.handler?.();
    tick();

    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
    expect(analytics.track).not.toHaveBeenCalled();
  }));
});
