import { ElementRef } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
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
import { KeyboardAvoidDirective } from '../directives/keyboard-avoid.directive';
import { PlanAmountTrayComponent } from '../components/plan-amount-tray/plan-amount-tray.component';

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
      new ElementRef(document.createElement('div')),
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

/**
 * The amount's slide tray. The amount is a value button, not an input: a tap opens the tray under
 * the field on the lump-sum scale, a detent lands on `investmentAmount`, and Done, a tap outside,
 * Make Investment and leaving the page close it. The stock search and the account number still
 * type, so the keyboard-avoid directive stays. The validator is not the tray's business and is
 * checked here only to show it still runs.
 */
describe('LumpSumInvestmentPage: amount tray', () => {
  let fixture: ComponentFixture<LumpSumInvestmentPage>;
  let page: LumpSumInvestmentPage;
  let http: jasmine.SpyObj<HttpClient>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    const pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);

    await TestBed.configureTestingModule({
      imports: [LumpSumInvestmentPage],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { queryParams: of({}) } },
        { provide: InvestmentService, useValue: {} },
        { provide: PortfolioService, useValue: { getPortfolioDashboard: () => of({ summary: { buyingPower: 0 } }) } },
        { provide: HttpClient, useValue: http },
        { provide: PasskeyService, useValue: {} },
        { provide: PinService, useValue: pin },
        { provide: ToastService, useValue: toast },
        { provide: NavController, useValue: { navigateBack: () => {} } },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(LumpSumInvestmentPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const valueBtn = () => el().querySelector('.field-value-btn') as HTMLButtonElement;
  const shownValue = () => valueBtn().textContent!.replace(/\s+/g, '');
  const label = () => el().querySelector('.field-label-row .field-label') as HTMLElement;
  const slot = () => el().querySelector('.field-tray') as HTMLElement | null;
  const done = () => el().querySelector('.field-tray-done') as HTMLButtonElement | null;
  const cta = () => el().querySelector('.cta-btn') as HTMLButtonElement;
  const tray = () => fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;

  function tap(): void {
    valueBtn().click();
    fixture.detectChanges();
  }

  function pointer(type: string, target: Element, pointerId = 1): void {
    target.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId }));
    fixture.detectChanges();
  }

  /** Lets the WAAPI collapse land: the slot leaves the DOM only then. */
  async function settle(): Promise<void> {
    const anims: Animation[] = [];
    for (const node of [slot(), el().querySelector('.field-tray-in'), done()]) {
      anims.push(...(node?.getAnimations() ?? []).filter(a => a.playState !== 'finished'));
    }
    await Promise.all(anims.map(anim => {
      const landed = new Promise<void>(resolve => anim.addEventListener('finish', () => resolve()));
      anim.finish();
      return landed;
    }));
    fixture.detectChanges();
  }

  it('the amount is an empty value button at rest, not an input; the CTA waits; the typed fields keep the keyboard-avoid directive', () => {
    expect(page.investmentAmount).toBeNull();
    expect(el().querySelector('.field-value-btn .field-placeholder')!.textContent).toBe('0');
    expect(shownValue()).toBe('$0');
    expect(label().textContent).toBe('Investment Amount');
    expect(el().querySelector('input[type="number"]')).toBeNull();
    expect(cta().disabled).toBeTrue();
    expect(fixture.debugElement.query(By.directive(KeyboardAvoidDirective))).not.toBeNull();
    expect(slot()).toBeNull();
  });

  it('a tap opens the tray under the field on the lump-sum scale, capped at $100,000 under the validator\'s $1,000,000', () => {
    tap();
    expect(page.tray.open()).toBe('amount');
    expect(valueBtn().classList.contains('active')).toBeTrue();
    expect(label().classList.contains('active')).toBeTrue();
    expect(done()!.parentElement).toBe(label().parentElement);
    expect(valueBtn().nextElementSibling).toBe(slot());
    expect(tray()!.scale()).toEqual({
      min: 25, max: 100_000, fine: 25,
      segments: [{ to: 1000, step: 25 }, { to: 5000, step: 100 }, { to: 25_000, step: 500 }, { to: 100_000, step: 2500 }],
    });
    expect(Array.from(el().querySelectorAll('.ps-ends span')).map(s => s.textContent)).toEqual(['$25', '$100,000']);
    expect(el().querySelectorAll('.ps-mark').length).toBe(3);
    expect(el().querySelector('.ps')!.getAttribute('aria-label')).toBe('Investment Amount');
  });

  it('a detent lands on the amount: the button, the projection card and the CTA follow', () => {
    tap();
    tray()!.valueChange.emit(2500);
    fixture.detectChanges();
    expect(page.investmentAmount).toBe(2500);
    expect(tray()!.value()).toBe(2500);
    expect(shownValue()).toBe('$2,500');
    expect(el().querySelector('.projection-card')).not.toBeNull();
    expect(cta().disabled).toBeFalse();
  });

  it('the label reads the step in force while the finger is down, and the name again on release', () => {
    tap();
    page.tray.onDragging(true);
    fixture.detectChanges();
    expect(label().textContent).toBe('± $25');
    page.investmentAmount = 2500;
    fixture.detectChanges();
    expect(label().textContent).toBe('± $100');
    page.investmentAmount = 30_000;
    fixture.detectChanges();
    expect(label().textContent).toBe('± $2,500');
    page.tray.onDragging(false);
    fixture.detectChanges();
    expect(label().textContent).toBe('Investment Amount');
  });

  it('Done closes the tray; the slot leaves when the collapse lands', async () => {
    tap();
    done()!.click();
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    await settle();
    expect(page.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
  });

  it('a tap outside the field and the tray closes it on release; a tap on the slider does not', async () => {
    tap();
    const slider = el().querySelector('.ps')!;
    pointer('pointerdown', slider);
    pointer('pointerup', slider);
    expect(page.tray.open()).toBe('amount');
    const subtitle = el().querySelector('.card-subtitle')!;
    pointer('pointerdown', subtitle);
    expect(page.tray.open()).toBe('amount');
    pointer('pointerup', subtitle);
    expect(page.tray.open()).toBeNull();
    await settle();
    expect(slot()).toBeNull();
  });

  it('Make Investment closes the tray, posts the amount the tray set, and the success reset empties the button again', fakeAsync(() => {
    http.post.and.returnValue(of({ success: true, message: 'Investment initiated successfully!' }));
    tap();
    tray()!.valueChange.emit(500);
    fixture.detectChanges();
    page.makeInvestment();
    expect(page.tray.open()).toBeNull();
    tick();
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post.calls.mostRecent().args[1]).toEqual(jasmine.objectContaining({ amount: 500 }));
    tick(2000); // the deferred form reset the success path schedules
    fixture.detectChanges();
    expect(page.investmentAmount).toBeNull();
    expect(shownValue()).toBe('$0');
    expect(cta().disabled).toBeTrue();
  }));

  it('the validator still runs as before: an amount over its $1,000,000 ceiling is refused', fakeAsync(() => {
    page.investmentAmount = 1_000_001;
    page.makeInvestment();
    tick();
    expect(toast.showToast).toHaveBeenCalledWith('Maximum investment amount is $1,000,000', 'danger');
    expect(http.post).not.toHaveBeenCalled();
  }));

  it('leaving the page snaps the tray shut at once', () => {
    tap();
    page.ionViewWillLeave();
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(page.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
  });
});
