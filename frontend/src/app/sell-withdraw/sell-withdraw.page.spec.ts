import { fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { AlertController, ModalController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { SellWithdrawPage } from './sell-withdraw.page';
import { TradingService } from '../services/trading.service';
import { PortfolioService, Position, AccountSummary, PortfolioDashboardData } from '../services/portfolio.service';
import { ToastService } from '../services/toast.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { AnalyticsService } from '../services/analytics/analytics.service';

/**
 * Analytics call sites on sell-withdraw.
 *
 * Two kinds of event live here: the DRIP switch (`setting_changed`) and the error paths
 * (`error_shown`). The sell, withdraw and close-account actions themselves are deliberately NOT
 * tracked from the device — those are facts the server owns — so these specs assert only that the
 * failures the user was shown were reported, with a category and a status and nothing else. Every
 * expectation is on the whole payload, so a property change fails the spec.
 */
describe('SellWithdrawPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let trading: jasmine.SpyObj<TradingService>;
  let portfolio: jasmine.SpyObj<PortfolioService>;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let page: SellWithdrawPage;

  const position: Position = {
    symbol: 'VTI',
    name: 'Vanguard Total Stock Market ETF',
    quantity: 10,
    quantityAvailable: 10,
    marketValue: 2500,
    costBasis: 2000,
    unrealizedPL: 500,
    unrealizedPLPercent: 25,
    currentPrice: 250,
    averageCostBasis: 200,
    percentOfAccount: 100,
    todayGainLoss: 5,
    todayGainLossPercent: 0.2,
  };

  const balance: AccountSummary = {
    portfolioValue: 2500,
    todayChange: 5,
    todayChangePercent: 0.2,
    buyingPower: 1000,
    cash: 1000,
    withdrawableCash: 1000,
    equity: 2500,
  };

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    trading = jasmine.createSpyObj<TradingService>('TradingService', [
      'getDripStatus',
      'setDripEnabled',
      'sellByPercentage',
      'withdrawCash',
      'closeAccount',
      'formatCurrency',
      'formatPercentage',
    ]);
    trading.getDripStatus.and.returnValue(of({ dripEnabled: true }));
    trading.formatCurrency.and.returnValue('$0.00');

    portfolio = jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getPortfolioDashboard']);
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);

    page = new SellWithdrawPage(
      jasmine.createSpyObj<Router>('Router', ['navigateByUrl']),
      trading,
      portfolio,
      jasmine.createSpyObj<AlertController>('AlertController', ['create']),
      modalCtrl,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
  });

  /** The PIN service, as a step-up gate that either passes or is dismissed. */
  function stepUp(hasPin: boolean, verified = true) {
    const pin = page['pinService'] as jasmine.SpyObj<PinService>;
    pin.hasPin.and.resolveTo(hasPin);
    pin.promptPin.and.resolveTo(verified);
  }

  /** A modal whose dismissal reports whether the passkey prompt authenticated. */
  function passkeyModal(authenticated: boolean) {
    modalCtrl.create.and.resolveTo({
      present: () => Promise.resolve(),
      onDidDismiss: () => Promise.resolve({ data: { authenticated } }),
    } as unknown as HTMLIonModalElement);
  }

  // ── setting_changed: drip ──────────────────────────────────────────────────

  it('DRIP on: reports what the server stored, not what was tapped', () => {
    page.dripEnabled = false;
    trading.setDripEnabled.and.returnValue(of({ dripEnabled: true, message: 'ok' }));

    page.toggleDrip({ detail: { checked: true } });

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'drip', enabled: true, value: null, outcome: 'applied' },
    });
  });

  it('DRIP off: same event with enabled false', () => {
    page.dripEnabled = true;
    trading.setDripEnabled.and.returnValue(of({ dripEnabled: false, message: 'ok' }));

    page.toggleDrip({ detail: { checked: false } });

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'drip', enabled: false, value: null, outcome: 'applied' },
    });
  });

  it('DRIP save failure: failed carries the state the user was reaching for, plus the error', () => {
    page.dripEnabled = false;
    trading.setDripEnabled.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.toggleDrip({ detail: { checked: true } });

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'drip', enabled: true, value: null, outcome: 'failed' },
    });
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
    // The switch has been put back; the event above is the only record of the intent.
    expect(page.dripEnabled).toBeFalse();
  });

  it('a no-op toggle fires nothing', () => {
    page.dripEnabled = true;

    page.toggleDrip({ detail: { checked: true } });

    expect(analytics.track).not.toHaveBeenCalled();
    expect(trading.setDripEnabled).not.toHaveBeenCalled();
  });

  // ── error_shown: load ──────────────────────────────────────────────────────

  it('a failed dashboard load reports load_failed with its status', fakeAsync(() => {
    portfolio.getPortfolioDashboard.and.returnValue(throwError(() => new HttpErrorResponse({ status: 502 })));

    page.ionViewWillEnter();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('load_failed', 'portfolio', 'toast', 502, false);
  }));

  it('a dashboard load that never reached the server reports network with no status', fakeAsync(() => {
    portfolio.getPortfolioDashboard.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.ionViewWillEnter();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  }));

  // ── error_shown: selling ───────────────────────────────────────────────────

  it('tapping a fully pending position reports validation', () => {
    page.selectPosition({ ...position, quantityAvailable: 0 });

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
  });

  it('a dismissed step-up before selling reports auth_required', fakeAsync(() => {
    page.selectedPosition = position;
    stepUp(true, false);

    page.sellPosition();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(trading.sellByPercentage).not.toHaveBeenCalled();
  }));

  it('a sell rejected in the response body reports save_failed with no status', fakeAsync(() => {
    page.selectedPosition = position;
    stepUp(false);
    trading.sellByPercentage.and.returnValue(
      of({ order_id: '', symbol: 'VTI', status: 'rejected', percentage: 25, submitted_at: '', success: false, error: 'nope' }),
    );

    page.sellPosition();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', null, false);
  }));

  it('a sell that throws reports save_failed with its status', fakeAsync(() => {
    page.selectedPosition = position;
    stepUp(false);
    trading.sellByPercentage.and.returnValue(throwError(() => new HttpErrorResponse({ status: 422 })));

    page.sellPosition();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 422, false);
  }));

  // ── error_shown: withdrawing ───────────────────────────────────────────────

  it('a dismissed step-up before withdrawing reports auth_required', fakeAsync(() => {
    page.balance = balance;
    page.withdrawAmount = 100;
    stepUp(true, false);

    page.withdrawCash();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(trading.withdrawCash).not.toHaveBeenCalled();
  }));

  it('a withdrawal rejected in the response body reports save_failed with no status', fakeAsync(() => {
    page.balance = balance;
    page.withdrawAmount = 100;
    stepUp(false);
    trading.withdrawCash.and.returnValue(
      of({ transfer_id: '', status: 'rejected', amount: 100, initiated_at: '', success: false, message: '', error: 'nope' }),
    );

    page.withdrawCash();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', null, false);
  }));

  it('a withdrawal that throws reports save_failed with its status', fakeAsync(() => {
    page.balance = balance;
    page.withdrawAmount = 100;
    stepUp(false);
    trading.withdrawCash.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409 })));

    page.withdrawCash();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 409, false);
  }));

  // ── error_shown: closing the account ───────────────────────────────────────

  it('a dismissed passkey prompt before closing reports auth_required', fakeAsync(() => {
    passkeyModal(false);

    page.initiateCloseAccount();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(page.showCloseConfirmation).toBeFalse();
  }));

  it('a passkey prompt that throws reports passkey', fakeAsync(() => {
    modalCtrl.create.and.rejectWith(new Error('prompt exploded'));

    page.initiateCloseAccount();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('passkey', 'portfolio', 'toast', null, false);
  }));

  it('a close rejected in the response body reports save_failed with no status', fakeAsync(() => {
    trading.closeAccount.and.returnValue(of({ success: false, message: '', error: 'nope' }));

    page.confirmCloseAccount();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', null, false);
  }));

  it('a close that throws reports save_failed with its status', fakeAsync(() => {
    trading.closeAccount.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.confirmCloseAccount();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
  }));

  // ── the money actions themselves stay server-side ──────────────────────────

  it('a successful sell fires no client event: the fill is the server’s to report', fakeAsync(() => {
    page.selectedPosition = position;
    page.balance = { ...balance };
    stepUp(false);
    trading.sellByPercentage.and.returnValue(
      of({ order_id: 'o1', symbol: 'VTI', status: 'accepted', percentage: 25, submitted_at: '', success: true }),
    );
    // An empty dashboard is enough: the success path reloads on a timer and this spec only needs
    // that reload to be quiet.
    portfolio.getPortfolioDashboard.and.returnValue(of(undefined as unknown as PortfolioDashboardData));

    page.sellPosition();
    tick();
    tick(1000); // the deferred reload the success path schedules
    tick();

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));
});
