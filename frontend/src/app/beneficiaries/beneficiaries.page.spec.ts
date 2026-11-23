import { fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { ModalController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { BeneficiariesPage } from './beneficiaries.page';
import { FredConfirmSheetComponent, FredConfirmSheetOptions } from '../components/fred-confirm-sheet/fred-confirm-sheet.component';
import { BeneficiaryService, Beneficiary, BeneficiaryAllocationSummary } from '../services/beneficiary.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';

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
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let toast: jasmine.SpyObj<ToastService>;
  let sheetOpts: FredConfirmSheetOptions | null;
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

    sheetOpts = null;
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);

    page = new BeneficiariesPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      beneficiaryService,
      toast,
      modalCtrl,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
  });

  /**
   * The confirm sheet, as the page sees it: Delete runs onConfirm inside the sheet and dismisses
   * with role 'confirm' once it resolves; a rejection reports to onConfirmError and leaves the
   * sheet open, so here the user then taps Keep (no role). Keep alone never runs onConfirm.
   */
  function confirmSheet(tap: 'delete' | 'keep') {
    modalCtrl.create.and.callFake(async (opts) => {
      sheetOpts = opts.componentProps as FredConfirmSheetOptions;
      let role: string | undefined;
      if (tap === 'delete') {
        try {
          await sheetOpts.onConfirm!();
          role = 'confirm';
        } catch (err) {
          sheetOpts.onConfirmError!(err);
        }
      }
      return {
        present: () => Promise.resolve(),
        onDidDismiss: () => Promise.resolve({ role }),
      } as unknown as HTMLIonModalElement;
    });
  }

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
    expect(modalCtrl.create).not.toHaveBeenCalled();
  }));

  it('deleting opens the destructive confirm sheet with its copy, and nothing runs until Delete', fakeAsync(() => {
    confirmSheet('keep');

    page.onDeleteBeneficiary(beneficiary);
    tick();

    const createArgs = modalCtrl.create.calls.mostRecent().args[0];
    expect(createArgs.component).toBe(FredConfirmSheetComponent);
    expect(createArgs.cssClass).toBe('mc-bottom-sheet-modal');
    expect(sheetOpts).toEqual(jasmine.objectContaining({
      tone: 'destructive',
      title: 'Delete this beneficiary?',
      body: 'They will no longer be listed on your account.',
      confirmLabel: 'Delete',
      cancelLabel: 'Keep',
    }));
    expect(beneficiaryService.deleteBeneficiary).not.toHaveBeenCalled();
    expect(beneficiaryService.getBeneficiaries).not.toHaveBeenCalled();
    expect(toast.showToast).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));

  it('a failed delete, confirmed through the sheet, reports save_failed, toasts and leaves the list alone', fakeAsync(() => {
    beneficiaryService.deleteBeneficiary.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    confirmSheet('delete');

    page.onDeleteBeneficiary(beneficiary);
    tick();

    expect(beneficiaryService.deleteBeneficiary).toHaveBeenCalledOnceWith(42);
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
    expect(toast.showToast).toHaveBeenCalledOnceWith('Failed to delete beneficiary. Please try again.', 'danger');
    expect(beneficiaryService.getBeneficiaries).not.toHaveBeenCalled();
  }));

  it('a successful delete fires nothing from the device, toasts and reloads the list', fakeAsync(() => {
    beneficiaryService.deleteBeneficiary.and.returnValue(of(undefined));
    confirmSheet('delete');

    page.onDeleteBeneficiary(beneficiary);
    tick();

    expect(beneficiaryService.deleteBeneficiary).toHaveBeenCalledOnceWith(42);
    expect(toast.showToast).toHaveBeenCalledOnceWith('Deleted Dana Example and updated Alpaca', 'success');
    expect(beneficiaryService.getBeneficiaries).toHaveBeenCalledTimes(1);
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
    expect(analytics.track).not.toHaveBeenCalled();
  }));
});
