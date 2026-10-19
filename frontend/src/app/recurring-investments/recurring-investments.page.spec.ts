import { fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { AlertController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { RecurringInvestmentsPage } from './recurring-investments.page';
import { InvestmentService, InvestmentSchedule } from '../services/investment.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AccountStatusService } from '../services/account-status.service';
import { AnalyticsService } from '../services/analytics/analytics.service';

/**
 * Analytics call sites on recurring-investments.
 *
 * Nothing about the schedule itself is tracked from the device: creating, pausing, resuming and
 * changing the amount are all server events, and the amount is money. What is tracked is the
 * errors the user was shown, including the two spellings of the account-not-active gate (client
 * and server), which are one category.
 */
describe('RecurringInvestmentsPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let investments: jasmine.SpyObj<InvestmentService>;
  let accountStatus: jasmine.SpyObj<AccountStatusService>;
  let pin: jasmine.SpyObj<PinService>;
  let page: RecurringInvestmentsPage;

  const schedule: InvestmentSchedule = {
    id: 7,
    monthlyAmount: 400,
    investmentAmount: 100,
    frequency: 'WEEKLY',
    startDate: '2026-01-05',
    nextInvestmentDate: '2026-10-26',
    isPaused: false,
    scheduleDescription: 'Every week',
    monthlyStreak: 0,
    createdAt: '2026-01-05T00:00:00Z',
    updatedAt: '2026-01-05T00:00:00Z',
  };

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    investments = jasmine.createSpyObj<InvestmentService>('InvestmentService', [
      'getCurrentSchedule',
      'pauseSchedule',
      'resumeSchedule',
      'createSchedule',
    ]);
    accountStatus = jasmine.createSpyObj<AccountStatusService>('AccountStatusService', ['isAccountActive']);
    accountStatus.isAccountActive.and.returnValue(true);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);

    page = new RecurringInvestmentsPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      { queryParams: of({}), params: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      investments,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      accountStatus,
      jasmine.createSpyObj<AlertController>('AlertController', ['create']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
  });

  // ── error_shown: loading the schedule ──────────────────────────────────────

  it('a failed schedule load reports load_failed with its status', () => {
    investments.getCurrentSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.ngOnInit();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('load_failed', 'portfolio', 'toast', 500, false);
  });

  it('a 404 is the normal "no schedule yet" state and fires nothing', () => {
    investments.getCurrentSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

    page.ngOnInit();

    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  });

  it('a schedule load that never reached the server reports network', () => {
    investments.getCurrentSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.ngOnInit();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  });

  // ── error_shown: pausing and resuming ──────────────────────────────────────

  it('a dismissed step-up before resuming reports auth_required', fakeAsync(() => {
    page.currentInvestment = { ...schedule, isPaused: true };
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(false);

    page.toggleInvestmentStatus();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(investments.resumeSchedule).not.toHaveBeenCalled();
  }));

  it('resuming on an account that is not active yet reports unavailable', fakeAsync(() => {
    page.currentInvestment = { ...schedule, isPaused: true };
    accountStatus.isAccountActive.and.returnValue(false);

    page.toggleInvestmentStatus();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('unavailable', 'portfolio', 'toast', null, false);
    expect(investments.resumeSchedule).not.toHaveBeenCalled();
  }));

  it('a 403 from the server is the same gate, so the same category, with the status', fakeAsync(() => {
    page.currentInvestment = { ...schedule, isPaused: true };
    investments.resumeSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 403 })));

    page.toggleInvestmentStatus();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('unavailable', 'portfolio', 'toast', 403, false);
  }));

  it('any other pause failure reports save_failed with its status', fakeAsync(() => {
    page.currentInvestment = { ...schedule, isPaused: false, monthlyStreak: 0 };
    investments.pauseSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.toggleInvestmentStatus();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a successful pause fires no client event: the schedule is the server’s to report', fakeAsync(() => {
    page.currentInvestment = { ...schedule, isPaused: false, monthlyStreak: 0 };
    investments.pauseSchedule.and.returnValue(of({ ...schedule, isPaused: true }));

    page.toggleInvestmentStatus();
    tick();

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));

  // ── error_shown: saving changes ────────────────────────────────────────────

  it('an amount under the minimum reports validation and never reaches the server', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule, investmentAmount: 0 };

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(investments.createSchedule).not.toHaveBeenCalled();
  }));

  it('a dismissed step-up before saving reports auth_required', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule, investmentAmount: 250 };
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(false);

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(investments.createSchedule).not.toHaveBeenCalled();
  }));

  it('a failed save reports save_failed with its status', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule, investmentAmount: 250 };
    investments.createSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a save that never reached the server reports network, and no amount is sent either way', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule, investmentAmount: 250 };
    investments.createSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
    expect(analytics.track).not.toHaveBeenCalled();
  }));
});
