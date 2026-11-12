import { CUSTOM_ELEMENTS_SCHEMA, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { By } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { ModalController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { SellWithdrawPage } from './sell-withdraw.page';
import { FredConfirmSheetComponent, FredConfirmSheetOptions } from '../components/fred-confirm-sheet/fred-confirm-sheet.component';
import { PasskeyPromptComponent } from '../components/passkey-prompt/passkey-prompt.component';
import { AmountTraySlotComponent } from '../components/plan-amount-tray/amount-tray-slot.component';
import { PlanAmountTrayComponent } from '../components/plan-amount-tray/plan-amount-tray.component';
import { detents } from '../utils/plan-amount-scale';
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
  let toast: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
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
      'reset',
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
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);

    page = new SellWithdrawPage(
      router,
      trading,
      portfolio,
      modalCtrl,
      toast,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
      new ElementRef(document.createElement('div')),
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

  /**
   * The passkey prompt authenticates, then the confirm sheet opens. 'close' runs onConfirm inside
   * the sheet (busy) and dismisses with role 'confirm' once it settles; 'cancel' dismisses with no
   * role and never runs it. Returns the sheet's options for the copy assertions.
   */
  function passkeyThenConfirmSheet(tap: 'close' | 'cancel'): { opts: FredConfirmSheetOptions | null } {
    const seen: { opts: FredConfirmSheetOptions | null } = { opts: null };
    modalCtrl.create.and.callFake(async (createOpts) => {
      if (createOpts.component === PasskeyPromptComponent) {
        return {
          present: () => Promise.resolve(),
          onDidDismiss: () => Promise.resolve({ data: { authenticated: true } }),
        } as unknown as HTMLIonModalElement;
      }
      seen.opts = createOpts.componentProps as FredConfirmSheetOptions;
      let role: string | undefined;
      if (tap === 'close') {
        await seen.opts.onConfirm!();
        role = 'confirm';
      }
      return {
        present: () => Promise.resolve(),
        onDidDismiss: () => Promise.resolve({ role }),
      } as unknown as HTMLIonModalElement;
    });
    return seen;
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
    // Only the passkey prompt was presented: no confirm sheet, no close.
    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    expect(trading.closeAccount).not.toHaveBeenCalled();
  }));

  it('an authenticated passkey opens the destructive confirm sheet with its copy; Cancel runs no close', fakeAsync(() => {
    const sheet = passkeyThenConfirmSheet('cancel');

    page.initiateCloseAccount();
    tick();

    expect(modalCtrl.create).toHaveBeenCalledTimes(2);
    const createArgs = modalCtrl.create.calls.mostRecent().args[0];
    expect(createArgs.component).toBe(FredConfirmSheetComponent);
    expect(createArgs.cssClass).toBe('mc-bottom-sheet-modal');
    expect(sheet.opts).toEqual(jasmine.objectContaining({
      tone: 'destructive',
      icon: 'warning',
      title: 'Close your account?',
      confirmLabel: 'Close account',
      cancelLabel: 'Cancel',
      busyLabel: 'Closing...',
    }));
    expect(sheet.opts!.body).toContain('This action is irreversible');
    expect(trading.closeAccount).not.toHaveBeenCalled();
    expect(toast.showToast).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));

  it('confirming through the sheet closes the account, then toasts, resets analytics and goes home', fakeAsync(() => {
    passkeyThenConfirmSheet('close');
    trading.closeAccount.and.returnValue(of({ success: true, message: 'closed' }));
    spyOn(Storage.prototype, 'clear');

    page.initiateCloseAccount();
    tick();

    expect(trading.closeAccount).toHaveBeenCalledTimes(1);
    expect(toast.showToast).toHaveBeenCalledOnceWith('Your account has been permanently closed.', 'success');
    expect(analytics.reset).toHaveBeenCalledTimes(1);
    expect(Storage.prototype.clear).toHaveBeenCalledTimes(2);
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/', { replaceUrl: true });
    expect(page.isClosingAccount).toBeFalse();
    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));

  it('a close that fails inside the sheet reports save_failed and toasts, and the sheet still settles', fakeAsync(() => {
    passkeyThenConfirmSheet('close');
    trading.closeAccount.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.initiateCloseAccount();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
    expect(toast.showToast).toHaveBeenCalledOnceWith(
      'Failed to close account. Please ensure all positions are sold and all cash is withdrawn.',
      'danger',
    );
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(page.isClosingAccount).toBeFalse();
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

  it('a close deferred by the 60-day settlement hold shows that copy, not the generic failure', fakeAsync(() => {
    trading.closeAccount.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409,
      error: { code: 'DELETION_DEFERRED', message: 'SETTLEMENT_WINDOW' },
    })));

    page.confirmCloseAccount();
    tick();

    expect(toast.showToast).toHaveBeenCalledOnceWith(
      'Your account can close 60 days after your last transfer settles. Everything stays safe until then.',
      'danger',
    );
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

/**
 * The withdraw amount's slide tray (components/plan-amount-tray, hosted through AmountTrayHost):
 * the field is a value button, the tray grows under it, its cap is the withdrawable cash and
 * follows it live, the quick chips set the same amount, and Done, a tap outside, submit and
 * leaving the page close it. Rendered under reduced motion so open and close are synchronous;
 * the motion itself is the host's spec.
 */
describe('SellWithdrawPage: withdraw tray', () => {
  let fixture: ComponentFixture<SellWithdrawPage>;
  let page: SellWithdrawPage;
  let trading: jasmine.SpyObj<TradingService>;
  let portfolio: jasmine.SpyObj<PortfolioService>;
  let pin: jasmine.SpyObj<PinService>;

  const summary: AccountSummary = {
    portfolioValue: 2500,
    todayChange: 5,
    todayChangePercent: 0.2,
    buyingPower: 1234.56,
    cash: 1234.56,
    withdrawableCash: 1234.56,
    equity: 2500,
  };

  async function mount(withdrawable = 1234.56): Promise<void> {
    trading = jasmine.createSpyObj<TradingService>('TradingService', [
      'getDripStatus', 'withdrawCash', 'formatCurrency', 'formatPercentage',
    ]);
    trading.getDripStatus.and.returnValue(of({ dripEnabled: true }));
    trading.formatCurrency.and.callFake((v: string | number) => `$${Number(v).toLocaleString('en-US', { minimumFractionDigits: 2 })}`);
    portfolio = jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getPortfolioDashboard']);
    portfolio.getPortfolioDashboard.and.returnValue(of({
      positions: [],
      summary: { ...summary, cash: withdrawable, withdrawableCash: withdrawable },
      recentTransactions: [],
    } as unknown as PortfolioDashboardData));
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    if (!jasmine.isSpy(window.matchMedia)) {
      spyOn(window, 'matchMedia').and.returnValue({ matches: true } as MediaQueryList);
    }

    TestBed.configureTestingModule({
      imports: [SellWithdrawPage],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigateByUrl']) },
        { provide: TradingService, useValue: trading },
        { provide: PortfolioService, useValue: portfolio },
        { provide: ModalController, useValue: jasmine.createSpyObj<ModalController>('ModalController', ['create']) },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['showToast']) },
        { provide: PasskeyService, useValue: jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']) },
        { provide: PinService, useValue: pin },
        { provide: NavController, useValue: jasmine.createSpyObj<NavController>('NavController', ['navigateBack']) },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown']) },
      ],
    })
      // Ionic's elements render as plain custom elements; the tray's slot is the real thing.
      .overrideComponent(SellWithdrawPage, {
        set: { imports: [CommonModule, AmountTraySlotComponent], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
      });
    fixture = TestBed.createComponent(SellWithdrawPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const field = () => root().querySelector('.withdraw-value') as HTMLButtonElement;
  const label = () => root().querySelector('.amount-label') as HTMLElement;
  const done = () => root().querySelector('.field-tray-done') as HTMLButtonElement | null;
  const slot = () => root().querySelector('.withdraw-field .field-tray') as HTMLElement | null;
  const tray = () => fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;
  const chips = () => Array.from(root().querySelectorAll('.quick-amounts .quick-btn')) as HTMLButtonElement[];

  function click(el: HTMLElement): void {
    el.click();
    fixture.detectChanges();
  }

  /** A tap as the host's outside-tap listener sees it: a press and a release on the same element. */
  function press(el: Element): void {
    el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
    el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 }));
    fixture.detectChanges();
  }

  function slideTo(value: number): void {
    tray()!.valueChange.emit(value);
    fixture.detectChanges();
  }

  it('the amount is a value button with the input\'s placeholder, not an input, and the page has no keyboard-avoid directive', async () => {
    await mount();
    expect(field()).not.toBeNull();
    expect(root().querySelector('.withdraw-controls input')).toBeNull();
    expect(field().querySelector('.field-affix')!.textContent).toBe('$');
    expect(field().querySelector('.field-placeholder')!.textContent).toBe('0.00');
    expect(root().querySelector('ion-content')!.hasAttribute('appkeyboardavoid')).toBeFalse();
    expect(page.tray.open()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
  });

  it('tapping the amount opens the tray under it, from $5 to the withdrawable cash, whose last detent is the cash exactly', async () => {
    await mount(1234.56);
    click(field());
    expect(page.tray.open()).toBe('amount');
    expect(field().classList).toContain('active');
    expect(label().classList).toContain('active');
    expect(label().textContent).toBe('Amount');
    expect(slot()).not.toBeNull();
    expect(slot()!.previousElementSibling).toBe(field());
    expect(done()).not.toBeNull();
    expect(root().querySelector('.amount-header .field-tray-done')).toBe(done());

    const scale = tray()!.scale();
    expect(scale.min).toBe(5);
    expect(scale.max).toBe(1234.56);
    expect(scale.fine).toBe(5);
    expect(scale).toBe(page.withdrawScale());
    const list = detents(scale);
    expect(list[0]).toBe(5);
    expect(list[list.length - 1]).toBe(1234.56);
    expect(list.length).toBeGreaterThanOrEqual(80);
    expect(list.length).toBeLessThanOrEqual(160);
    const ends = Array.from(root().querySelectorAll('.ps-ends span')).map(e => e.textContent);
    expect(ends).toEqual(['$5', '$1,234.56']);
    expect(root().querySelector('.ps')!.getAttribute('aria-label')).toBe('Amount');
  });

  it('a detent lands on the amount, the button shows it (cents when the cash has them) and the matching chip lights', async () => {
    await mount(1234.56);
    click(field());
    slideTo(500);
    expect(page.withdrawAmount).toBe(500);
    expect(field().textContent!.replace(/\s+/g, '')).toBe('$500');
    expect(chips().filter(c => c.classList.contains('quick-btn--active')).map(c => c.textContent!.trim())).toEqual(['$500.00']);
    slideTo(1234.56);
    expect(page.withdrawAmount).toBe(1234.56);
    expect(field().textContent!.replace(/\s+/g, '')).toBe('$1,234.56');
    expect(chips().some(c => c.classList.contains('quick-btn--active'))).toBeFalse();
  });

  it('a quick chip sets the amount, moves the thumb and leaves the tray open', async () => {
    await mount(5000);
    click(field());
    const chip = chips().find(c => c.textContent!.trim() === '$1,000.00')!;
    press(chip);
    click(chip);
    expect(page.withdrawAmount).toBe(1000);
    expect(page.tray.open()).toBe('amount');
    expect(tray()!.value()).toBe(1000);
    expect(chip.classList).toContain('quick-btn--active');
  });

  it('Done, a tap outside, the open field again, and submit close the tray', async () => {
    await mount(5000);
    click(field());
    click(done()!);
    expect(page.tray.open()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();

    click(field());
    press(root().querySelector('.card-title')!);
    expect(page.tray.open()).toBeNull();

    click(field());
    press(field());
    expect(page.tray.open()).toBe('amount');
    click(field());
    expect(page.tray.open()).toBeNull();

    click(field());
    slideTo(100);
    trading.withdrawCash.and.returnValue(of({ transfer_id: 't1', status: 'queued', amount: 100, initiated_at: '', success: true, message: 'ok' }));
    const submit = page.withdrawCash();
    expect(page.tray.open()).toBeNull();
    await submit;
    expect(trading.withdrawCash).toHaveBeenCalledWith({ amount: 100 });
  });

  it('the scale follows the withdrawable cash live, so a refreshed balance re-caps the open tray', async () => {
    await mount(1234.56);
    click(field());
    const before = tray()!.scale();
    page.balance = { ...summary, cash: 2500, withdrawableCash: 2500 };
    fixture.detectChanges();
    const after = tray()!.scale();
    expect(after).not.toBe(before);
    expect(after.max).toBe(2500);
    expect(detents(after)[detents(after).length - 1]).toBe(2500);
    expect(root().querySelector('.available-text')!.textContent).toBe('Available: $2,500.00');
  });

  it('under $5 of cash the tray runs from $0 to the cash, so the last dollars can leave and the account can close', async () => {
    await mount(3.25);
    expect(field().disabled).toBeFalse();
    click(field());
    expect(page.tray.open()).toBe('amount');
    const scale = tray()!.scale();
    expect(scale.min).toBe(0);
    expect(scale.max).toBe(3.25);
    expect(detents(scale)[detents(scale).length - 1]).toBe(3.25);
    expect(Array.from(root().querySelectorAll('.ps-ends span')).map(e => e.textContent)).toEqual(['$0', '$3.25']);
    slideTo(3.25);
    expect(page.withdrawAmount).toBe(3.25);
    const cta = Array.from(root().querySelectorAll('.cta-btn')).find(b => b.textContent!.includes('Initiate Transfer')) as HTMLButtonElement;
    expect(cta.disabled).toBeFalse();
    for (const chip of chips()) expect(chip.disabled).toBeTrue();
  });

  it('with nothing to withdraw the field is disabled and the tray stays shut', async () => {
    await mount(0);
    expect(field().disabled).toBeTrue();
    click(field());
    expect(page.tray.open()).toBeNull();
    expect(slot()).toBeNull();
  });

  it('the label reads the step in force while the finger is down, and Amount again on release', async () => {
    await mount(1234.56);
    click(field());
    slideTo(600);
    page.tray.onDragging(true);
    fixture.detectChanges();
    expect(label().textContent).toBe('± $25');
    page.tray.onDragging(false);
    fixture.detectChanges();
    expect(label().textContent).toBe('Amount');
  });

  it('leaving the page snaps the tray shut, and destroying it hands the document listener back', async () => {
    await mount(5000);
    click(field());
    page.ionViewWillLeave();
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(slot()).toBeNull();

    click(field());
    const remove = spyOn(document, 'removeEventListener').and.callThrough();
    fixture.destroy();
    expect(remove.calls.allArgs().map(a => a[0])).toEqual(jasmine.arrayContaining(['pointerdown', 'pointerup', 'pointercancel']));
  });
});
