import { ElementRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { InvestmentService } from '../../services/investment.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { ToastService } from '../../services/toast.service';
import { InvestmentScheduleComponent } from './investment-schedule.component';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { PlanAmountTrayComponent } from '../plan-amount-tray/plan-amount-tray.component';

const ACATS_KEY = 'pendingAcats';

describe('InvestmentScheduleComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let toast: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let createOrUpdate: jasmine.Spy;
  let authService: AuthService;
  let investmentService: InvestmentService;
  let http: HttpClient;
  let component: InvestmentScheduleComponent;

  function build(): InvestmentScheduleComponent {
    return new InvestmentScheduleComponent(
      router, navCtrl, authService, investmentService, http, analytics, toast,
      new ElementRef(document.createElement('div')),
    );
  }

  beforeEach(() => {
    localStorage.removeItem(ACATS_KEY);

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show', 'showToast']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateForward']);
    createOrUpdate = jasmine.createSpy('createOrUpdateInvestmentSchedule').and.returnValue(of({}));
    authService = {
      userProgress$: of(null),
      getCurrentInvestmentSchedule: () => of(null),
      getCurrentProgress: () => null,
      getUserProgress: () => of({ monthlyInvestment: 2000 }),
      createOrUpdateInvestmentSchedule: createOrUpdate,
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
    } as unknown as AuthService;
    investmentService = {
      pendingAcatsRequest: null,
      initiateAcatsTransfer: jasmine.createSpy('initiateAcatsTransfer').and.returnValue(of({})),
    } as unknown as InvestmentService;
    http = { post: jasmine.createSpy('post').and.returnValue(of({})) } as unknown as HttpClient;

    component = build();
  });

  afterEach(() => {
    localStorage.removeItem(ACATS_KEY);
  });

  it('reports the step as viewed and not resumed with no saved transfer draft', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('investment_schedule', null, false);
  });

  it('reports resumed when a transfer draft was restored from storage', () => {
    localStorage.setItem(ACATS_KEY, JSON.stringify({ dtc: '0226', account: '12345678' }));

    component = build();
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('investment_schedule', null, true);
  });

  it('sends nothing while the user is still picking a frequency or an amount', () => {
    component.ngOnInit();
    analytics.track.calls.reset();
    analytics.trackOnboardingStepCompleted.calls.reset();

    component.onFrequencyChange('WEEKLY');
    component.investmentAmount.set(80);

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });

  it('reports the step completed at the tap, before the schedule is saved', () => {
    component.ngOnInit();

    component.goToStockPreferences();

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('investment_schedule', null);
    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledBefore(createOrUpdate);
  });

  it('reports a failed schedule save as a toast, with the status and no message', () => {
    const alert = spyOn(window, 'alert');
    component.ngOnInit();
    createOrUpdate.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500, error: { message: 'ACH relationship missing' } })),
    );

    component.goToStockPreferences();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('save_failed', 'onboarding', 'toast', 500, true);
    expect(JSON.stringify(analytics.trackErrorShown.calls.allArgs())).not.toContain('ACH');
    expect(toast.show).toHaveBeenCalledWith({ severity: 'error', message: 'Error saving investment schedule. Please try again.' });
    expect(alert).not.toHaveBeenCalled();
  });

  it('reports a connection that never reached the server as a network error', () => {
    const alert = spyOn(window, 'alert');
    component.ngOnInit();
    createOrUpdate.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    component.goToStockPreferences();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('network', 'onboarding', 'toast', null, true);
    expect(toast.show).toHaveBeenCalledWith({ severity: 'error', message: 'Error saving investment schedule. Please try again.' });
    expect(alert).not.toHaveBeenCalled();
  });

  it('never sends the account number the user typed for a transfer', () => {
    component.ngOnInit();
    component.showTransferOptions = true;
    component.transferBrokerageDtc = '0226';
    component.transferAccountNumber = '98765432';

    component.goToStockPreferences();

    const everySentArgument = JSON.stringify([
      analytics.track.calls.allArgs(),
      analytics.trackOnboardingStepViewed.calls.allArgs(),
      analytics.trackOnboardingStepCompleted.calls.allArgs(),
      analytics.trackErrorShown.calls.allArgs(),
    ]);
    expect(everySentArgument).not.toContain('98765432');
  });
});

/**
 * The amount's slide tray on the onboarding screen. The recommended amount shows in a value button,
 * not an input: a tap opens the tray under the date-and-amount pair, a detent lands on the signal
 * the projections read, and Done, a tap outside, Review & Finalize and leaving the screen close it.
 * The date and the account number still type, so the keyboard-avoid directive stays.
 */
describe('InvestmentScheduleComponent: amount tray', () => {
  let fixture: ComponentFixture<InvestmentScheduleComponent>;
  let component: InvestmentScheduleComponent;
  let createOrUpdate: jasmine.Spy;

  beforeEach(async () => {
    localStorage.removeItem(ACATS_KEY);
    createOrUpdate = jasmine.createSpy('createOrUpdateInvestmentSchedule').and.returnValue(of({}));
    await TestBed.configureTestingModule({
      imports: [InvestmentScheduleComponent],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigateByUrl']) },
        { provide: NavController, useValue: jasmine.createSpyObj<NavController>('NavController', ['navigateForward']) },
        { provide: AuthService, useValue: {
            userProgress$: of(null),
            getCurrentInvestmentSchedule: () => of(null),
            getCurrentProgress: () => null,
            getUserProgress: () => of({ monthlyInvestment: 2000 }),
            createOrUpdateInvestmentSchedule: createOrUpdate,
            completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
        } },
        { provide: InvestmentService, useValue: { pendingAcatsRequest: null } },
        { provide: HttpClient, useValue: { post: jasmine.createSpy('post').and.returnValue(of({})) } },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
            'track', 'trackOnboardingStepViewed', 'trackOnboardingStepCompleted', 'trackErrorShown', 'screen',
        ]) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(InvestmentScheduleComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    localStorage.removeItem(ACATS_KEY);
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const valueBtn = () => el().querySelector('.field-value-btn') as HTMLButtonElement;
  const shownValue = () => valueBtn().textContent!.replace(/\s+/g, '');
  const label = () => el().querySelector('.field-label-row .field-label') as HTMLElement;
  const slot = () => el().querySelector('.field-tray') as HTMLElement | null;
  const done = () => el().querySelector('.field-tray-done') as HTMLButtonElement | null;
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

  it('the recommended amount shows in a value button, not an input; the typed fields keep the keyboard-avoid directive', () => {
    // $2,000 a month over 2.17 bi-weekly paychecks.
    expect(component.investmentAmount()).toBe(922);
    expect(shownValue()).toBe('$922');
    expect(label().textContent).toBe('Amount');
    expect(el().querySelector('input[type="number"]')).toBeNull();
    expect(el().querySelector('input[type="date"]')).not.toBeNull();
    expect(fixture.debugElement.query(By.directive(KeyboardAvoidDirective))).not.toBeNull();
    expect(slot()).toBeNull();
  });

  it('a tap opens the tray under the pair on the per-paycheck scale, speaking the value per paycheck', () => {
    tap();
    expect(component.tray.open()).toBe('amount');
    expect(valueBtn().classList.contains('active')).toBeTrue();
    expect(label().classList.contains('active')).toBeTrue();
    expect(done()!.parentElement).toBe(label().parentElement);
    expect(el().querySelector('.form-row-pair')!.nextElementSibling).toBe(slot());
    expect(tray()!.scale()).toEqual({
      min: 5, max: 10_000, fine: 5,
      segments: [{ to: 500, step: 5 }, { to: 2000, step: 50 }, { to: 5000, step: 250 }, { to: 10_000, step: 500 }],
    });
    expect(Array.from(el().querySelectorAll('.ps-ends span')).map(s => s.textContent)).toEqual(['$5', '$10,000']);
    expect(el().querySelectorAll('.ps-mark').length).toBe(3);
    expect(el().querySelector('.ps')!.getAttribute('aria-label')).toBe('Amount');
    expect(el().querySelector('.ps')!.getAttribute('aria-valuetext')).toBe('$922 per paycheck');
  });

  it('a detent lands on the amount: the button, the projections and the CTA follow', () => {
    tap();
    tray()!.valueChange.emit(1000);
    fixture.detectChanges();
    expect(component.investmentAmount()).toBe(1000);
    expect(shownValue()).toBe('$1,000');
    expect(component.getMonthlyProjection()).toBe(2170);
    expect(el().querySelector('.projection-grid')).not.toBeNull();
    expect(component.canProceed()).toBeTrue();
    expect((el().querySelector('.cta-btn') as HTMLButtonElement).disabled).toBeFalse();
  });

  it('a frequency change while the tray is open re-recommends the amount, and the tray follows it', () => {
    tap();
    component.onFrequencyChange('WEEKLY');
    fixture.detectChanges();
    // $2,000 a month over 4.33 weekly paychecks.
    expect(component.investmentAmount()).toBe(462);
    expect(tray()!.value()).toBe(462);
    expect(shownValue()).toBe('$462');
    expect(component.tray.open()).toBe('amount');
  });

  it('the label reads the step in force while the finger is down, and the name again on release', () => {
    tap();
    component.tray.onDragging(true);
    fixture.detectChanges();
    expect(label().textContent).toBe('± $50');
    component.investmentAmount.set(200);
    fixture.detectChanges();
    expect(label().textContent).toBe('± $5');
    component.tray.onDragging(false);
    fixture.detectChanges();
    expect(label().textContent).toBe('Amount');
  });

  it('Done closes the tray; the slot leaves when the collapse lands', async () => {
    tap();
    done()!.click();
    fixture.detectChanges();
    expect(component.tray.open()).toBeNull();
    await settle();
    expect(component.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
  });

  it('a tap outside the field and the tray closes it on release; a tap on the slider does not', async () => {
    tap();
    const slider = el().querySelector('.ps')!;
    pointer('pointerdown', slider);
    pointer('pointerup', slider);
    expect(component.tray.open()).toBe('amount');
    const hero = el().querySelector('.hero-title')!;
    pointer('pointerdown', hero);
    expect(component.tray.open()).toBe('amount');
    pointer('pointerup', hero);
    expect(component.tray.open()).toBeNull();
    await settle();
    expect(slot()).toBeNull();
  });

  it('Review & Finalize closes the tray and saves the amount the tray set', () => {
    tap();
    tray()!.valueChange.emit(1000);
    fixture.detectChanges();
    component.goToStockPreferences();
    expect(component.tray.open()).toBeNull();
    expect(createOrUpdate).toHaveBeenCalledWith(jasmine.objectContaining({ investmentAmount: 1000, frequency: 'BIWEEKLY' }));
  });

  it('leaving the screen snaps the tray shut at once', () => {
    tap();
    component.ionViewWillLeave();
    fixture.detectChanges();
    expect(component.tray.open()).toBeNull();
    expect(component.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
  });
});
