import { fakeAsync, tick } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import { LumpSumInvestmentPage } from './lump-sum-investment.page';
import { InvestmentService } from '../services/investment.service';
import { PortfolioService } from '../services/portfolio.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';

/**
 * Analytics call sites on lump-sum-investment.
 *
 * The investment and the ACATS transfer are both money moving, so neither is tracked from the
 * device. Only the failures are, and the payload is deliberately thin: one of the validation
 * toasts quotes the user's buying power and another quotes an Alpaca transfer reference, so a
 * single `validation` event covers the whole validator rather than one per message.
 */
describe('LumpSumInvestmentPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let investments: jasmine.SpyObj<InvestmentService>;
  let http: jasmine.SpyObj<HttpClient>;
  let pin: jasmine.SpyObj<PinService>;
  let page: LumpSumInvestmentPage;

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    investments = jasmine.createSpyObj<InvestmentService>('InvestmentService', ['initiateAcatsTransfer']);
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);

    page = new LumpSumInvestmentPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      { queryParams: of({}), params: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      investments,
      jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getPortfolioDashboard']),
      http,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
  });

  // ── error_shown: ACATS transfer ────────────────────────────────────────────

  it('a transfer with no brokerage or account number reports validation', fakeAsync(() => {
    page.submitAcatsTransfer();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(investments.initiateAcatsTransfer).not.toHaveBeenCalled();
  }));

  it('a failed transfer reports save_failed with its status', fakeAsync(() => {
    page.transferBrokerageDtc = '0226';
    page.transferAccountNumber = '12345678';
    investments.initiateAcatsTransfer.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.submitAcatsTransfer();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a transfer that never reached the server reports network', fakeAsync(() => {
    page.transferBrokerageDtc = '0226';
    page.transferAccountNumber = '12345678';
    investments.initiateAcatsTransfer.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.submitAcatsTransfer();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  }));

  it('a submitted transfer fires nothing: the reference id is the server’s to report', fakeAsync(() => {
    page.transferBrokerageDtc = '0226';
    page.transferAccountNumber = '12345678';
    investments.initiateAcatsTransfer.and.returnValue(of({ transferId: 'acats-1', message: 'ok' }));

    page.submitAcatsTransfer();
    tick();
    tick(1000); // the deferred form reset the success path schedules

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));

  // ── error_shown: investing ─────────────────────────────────────────────────

  it('an amount the validator rejects reports validation exactly once', fakeAsync(() => {
    page.investmentAmount = 0;

    page.makeInvestment();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(http.post).not.toHaveBeenCalled();
  }));

  it('insufficient buying power is the same single validation event, and the balance never leaves', fakeAsync(() => {
    page.investmentAmount = 5000;
    page.fundingSource = 'buying_power';
    page.buyingPower = 12.34;

    page.makeInvestment();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
  }));

  it('a dismissed step-up before investing reports auth_required', fakeAsync(() => {
    page.investmentAmount = 500;
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(false);

    page.makeInvestment();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(http.post).not.toHaveBeenCalled();
  }));

  it('an investment rejected in the response body reports save_failed with no status', fakeAsync(() => {
    page.investmentAmount = 500;
    http.post.and.returnValue(of({ success: false, message: 'Market is closed' }));

    page.makeInvestment();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', null, false);
  }));

  it('an investment that fails over HTTP reports save_failed with its status', fakeAsync(() => {
    page.investmentAmount = 500;
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 502 })));

    page.makeInvestment();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 502, false);
  }));

  it('an investment request that never reached the server reports network', fakeAsync(() => {
    page.investmentAmount = 500;
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.makeInvestment();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  }));

  it('a failure before the request is unknown rather than a guess', fakeAsync(() => {
    page.investmentAmount = 500;
    pin.hasPin.and.rejectWith(new Error('step-up blew up'));

    page.makeInvestment();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('unknown', 'portfolio', 'toast', null, false);
  }));

  it('a successful investment fires nothing from the device', fakeAsync(() => {
    page.investmentAmount = 500;
    http.post.and.returnValue(of({ success: true, message: 'Investment initiated successfully!' }));

    page.makeInvestment();
    tick();
    tick(2000); // the deferred form reset the success path schedules

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));
});
