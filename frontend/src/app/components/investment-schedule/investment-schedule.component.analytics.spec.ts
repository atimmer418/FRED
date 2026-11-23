import { ElementRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { ModalController, NavController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { InvestmentService } from '../../services/investment.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { ToastService } from '../../services/toast.service';
import { InvestmentScheduleComponent, PAY_DATE_LOCKED_ECHO } from './investment-schedule.component';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { PlanAmountTrayComponent } from '../plan-amount-tray/plan-amount-tray.component';
import { FRED_DATE_SHEET_ROLE, FredDateSheetComponent, FredDateSheetOptions } from '../fred-date-sheet/fred-date-sheet.component';
import { addDays, formatShort, isWeekend, parseYmd, todayYmd, ymd } from '../../utils/local-date';
import { nextBusinessDay } from '../../utils/business-days';
import { quickPicks } from '../../utils/quick-picks';

const ACATS_KEY = 'pendingAcats';

describe('InvestmentScheduleComponent: analytics', () => {
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
      jasmine.createSpyObj<ModalController>('ModalController', ['create']),
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
 * The account number still types, so the keyboard-avoid directive stays.
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
        { provide: ModalController, useValue: jasmine.createSpyObj<ModalController>('ModalController', ['create']) },
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

  it('the recommended amount shows in a value button, not an input; the account number keeps the keyboard-avoid directive', () => {
    // $2,000 a month over 2.17 bi-weekly paychecks.
    expect(component.investmentAmount()).toBe(922);
    expect(shownValue()).toBe('$922');
    expect(label().textContent).toBe('Amount');
    expect(el().querySelector('input[type="number"]')).toBeNull();
    expect(el().querySelector('input[type="date"]')).toBeNull();
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

/**
 * The next pay date on the onboarding screen: a value button with the calendar glyph that opens
 * the FRED date sheet in upcoming mode (tomorrow onward, business days only, quick picks for the
 * chosen frequency). Done writes the day; any other dismissal writes nothing. SEMI_MONTHLY locks
 * the button with the "set by your pay schedule" echo, since the backend owns the 1st and 15th.
 */
describe('InvestmentScheduleComponent: pay date sheet', () => {
  let fixture: ComponentFixture<InvestmentScheduleComponent>;
  let component: InvestmentScheduleComponent;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let modalEl: { present: jasmine.Spy; onDidDismiss: jasmine.Spy };
  let createOrUpdate: jasmine.Spy;

  const today = todayYmd();
  const tomorrow = addDays(today, 1);
  /** A business day well ahead of every seed, so the write is visible. */
  const picked = nextBusinessDay(addDays(today, 40));

  beforeEach(async () => {
    localStorage.removeItem(ACATS_KEY);
    createOrUpdate = jasmine.createSpy('createOrUpdateInvestmentSchedule').and.returnValue(of({}));
    modalEl = {
      present: jasmine.createSpy('present').and.resolveTo(),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role: undefined }),
    };
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    modalCtrl.create.and.resolveTo(modalEl as unknown as HTMLIonModalElement);
    await TestBed.configureTestingModule({
      imports: [InvestmentScheduleComponent],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigateByUrl']) },
        { provide: NavController, useValue: jasmine.createSpyObj<NavController>('NavController', ['navigateForward']) },
        { provide: ModalController, useValue: modalCtrl },
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
  const dateBtn = () => el().querySelector('.field-date-btn') as HTMLButtonElement;
  const dateText = () => dateBtn().querySelector('.field-date-text')?.textContent?.trim();
  const echo = () => el().querySelector('.field-echo--pay-date') as HTMLElement | null;
  const sheetOpts = () => modalCtrl.create.calls.mostRecent().args[0].componentProps as FredDateSheetOptions;

  function dismissWith(role: string | undefined, value?: string): void {
    modalEl.onDidDismiss.and.resolveTo({ role, data: value === undefined ? undefined : { value } });
  }

  async function tap(): Promise<void> {
    dateBtn().click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('the seeded date shows in a value button with the calendar glyph; no date input is left', () => {
    expect(el().querySelector('input[type="date"]')).toBeNull();
    expect(el().querySelector('ion-datetime')).toBeNull();
    expect(component.schedule.startDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(dateText()).toBe(formatShort(component.schedule.startDate));
    expect(dateBtn().querySelector('.field-date-icon')!.textContent!.trim()).toBe('calendar_month');
    expect(dateBtn().getAttribute('aria-haspopup')).toBe('dialog');
    expect(dateBtn().disabled).toBeFalse();
    expect(echo()).toBeNull();
  });

  it('every seed but the semi-monthly one lands on a business day; the monthly seed is the 1st of next month moved forward', () => {
    const { y, m } = parseYmd(today);
    const firstOfNextMonth = m === 12 ? ymd(y + 1, 1, 1) : ymd(y, m + 1, 1);

    for (const frequency of ['WEEKLY', 'BIWEEKLY', 'MONTHLY'] as const) {
      component.onFrequencyChange(frequency);
      expect(isWeekend(component.schedule.startDate)).withContext(frequency).toBeFalse();
      expect(component.schedule.startDate > today).withContext(frequency).toBeTrue();
    }
    expect(component.schedule.startDate).toBe(nextBusinessDay(firstOfNextMonth));
  });

  it('a tap opens the sheet in upcoming mode: tomorrow onward, business days only, the frequency\'s quick picks, no subline', async () => {
    const seeded = component.schedule.startDate;
    await tap();

    const createArgs = modalCtrl.create.calls.mostRecent().args[0];
    expect(createArgs.component).toBe(FredDateSheetComponent);
    expect(createArgs.cssClass).toBe('mc-bottom-sheet-modal');
    expect(modalEl.present).toHaveBeenCalled();
    const opts = sheetOpts();
    expect(opts.title).toBe('Next pay date');
    expect(opts.mode).toBe('upcoming');
    expect(opts.value).toBe(seeded);
    expect(opts.min).toBe(tomorrow);
    expect(opts.max).toBeUndefined();
    expect(opts.businessDaysOnly).toBeTrue();
    expect(opts.quickPicks).toEqual(quickPicks('BIWEEKLY', tomorrow, today, true));
    expect(opts.quickPicks!.length).toBeGreaterThan(0);
    expect(opts.subline).toBeUndefined();
    expect(opts.doneLabel).toBeUndefined();
  });

  it('the quick picks follow the chosen frequency', async () => {
    component.onFrequencyChange('MONTHLY');
    fixture.detectChanges();
    await tap();
    expect(sheetOpts().quickPicks).toEqual(quickPicks('MONTHLY', tomorrow, today, true));
    expect(sheetOpts().value).toBe(component.schedule.startDate);
  });

  it('Done writes the day as YYYY-MM-DD, the button follows, and Review & Finalize sends it', async () => {
    dismissWith(FRED_DATE_SHEET_ROLE, picked);
    await tap();

    expect(component.schedule.startDate).toBe(picked);
    expect(dateText()).toBe(formatShort(picked));
    expect(component.canProceed()).toBeTrue();

    component.goToStockPreferences();
    expect(createOrUpdate).toHaveBeenCalledWith(jasmine.objectContaining({ startDate: picked, frequency: 'BIWEEKLY' }));
  });

  it('a drag, the backdrop or a Done with no value writes nothing', async () => {
    const seeded = component.schedule.startDate;
    for (const role of [undefined, 'gesture', 'backdrop']) {
      dismissWith(role);
      await tap();
      expect(component.schedule.startDate).withContext(String(role)).toBe(seeded);
    }
    dismissWith(FRED_DATE_SHEET_ROLE);
    await tap();
    expect(component.schedule.startDate).toBe(seeded);
    expect(dateText()).toBe(formatShort(seeded));
  });

  it('semi-monthly locks the button with the pay-schedule echo and never opens the sheet; another frequency unlocks it', async () => {
    component.onFrequencyChange('SEMI_MONTHLY');
    fixture.detectChanges();

    expect(component.payDateLocked()).toBeTrue();
    expect(dateBtn().disabled).toBeTrue();
    expect(dateBtn().getAttribute('aria-label')).toContain('set by your pay schedule');
    expect(echo()!.querySelector('.material-symbols-outlined')!.textContent!.trim()).toBe('lock');
    expect(echo()!.lastElementChild!.textContent).toBe(PAY_DATE_LOCKED_ECHO);
    expect(PAY_DATE_LOCKED_ECHO).toBe('Set by your pay schedule: the 1st and 15th.');
    // The echo is the pair's own second-row child (the pair has no row gap, so only its 6px margin sits under the field).
    expect(echo()!.parentElement!.classList).toContain('form-row-pair');
    // The 1st or 15th the backend will use still shows, so the row never reads empty.
    expect(dateText()).toBe(formatShort(component.schedule.startDate));

    await component.openPayDateSheet();
    expect(modalCtrl.create).not.toHaveBeenCalled();

    component.onFrequencyChange('WEEKLY');
    fixture.detectChanges();
    expect(dateBtn().disabled).toBeFalse();
    expect(dateBtn().getAttribute('aria-label')).toContain('Opens a date picker');
    expect(echo()).toBeNull();
  });
});
