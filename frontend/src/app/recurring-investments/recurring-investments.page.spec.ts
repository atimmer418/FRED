import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { ModalController } from '@ionic/angular/standalone';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { RecurringInvestmentsPage } from './recurring-investments.page';
import { InvestmentService, InvestmentSchedule } from '../services/investment.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AccountStatusService } from '../services/account-status.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { PlaidService } from '../services/plaid.service';
import { PrimaryBankAccount } from '../models/primary-bank-account';
import { reconnectCardFor } from '../components/reconnect-card/reconnect-card.component';
import { KeyboardAvoidDirective } from '../directives/keyboard-avoid.directive';
import { FRED_DATE_SHEET_ROLE, FredDateSheetComponent, FredDateSheetOptions } from '../components/fred-date-sheet/fred-date-sheet.component';
import { tradeWindow } from '../utils/business-days';
import { formatShort, todayYmd } from '../utils/local-date';
import { quickPicks } from '../utils/quick-picks';

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
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let page: RecurringInvestmentsPage;
  let plaid: jasmine.SpyObj<PlaidService>;
  let router: jasmine.SpyObj<Router>;
  let status$: BehaviorSubject<string | null>;

  /** The confirm sheet, presented and then dismissed with the given role (none = drag, backdrop or Keep investing). */
  function sheetDismissedWith(role: string | undefined): void {
    modalCtrl.create.and.resolveTo({
      present: jasmine.createSpy('present').and.resolveTo(),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role }),
    } as unknown as HTMLIonModalElement);
  }

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
    status$ = new BehaviorSubject<string | null>('ACTIVE');
    accountStatus = jasmine.createSpyObj<AccountStatusService>('AccountStatusService', ['isAccountActive'], {
      accountStatus$: status$.asObservable(),
    });
    accountStatus.isAccountActive.and.returnValue(true);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    plaid = jasmine.createSpyObj<PlaidService>('PlaidService', ['getPrimaryBankAccount']);
    plaid.getPrimaryBankAccount.and.returnValue(of(null));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);

    page = new RecurringInvestmentsPage(
      router,
      { queryParams: of({}), params: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      investments,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      accountStatus,
      modalCtrl,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
      plaid,
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

  // ── the pause confirm sheet (streak > 0) ───────────────────────────────────

  it('pausing with a streak asks first, and Pause anyway pauses', fakeAsync(() => {
    page.currentInvestment = { ...schedule, isPaused: false, monthlyStreak: 4 };
    investments.pauseSchedule.and.returnValue(of({ ...schedule, isPaused: true }));
    sheetDismissedWith('confirm');

    page.toggleInvestmentStatus();
    tick();

    const props = modalCtrl.create.calls.mostRecent().args[0].componentProps!;
    expect(props['tone']).toBe('destructive');
    expect(props['icon']).toBe('pause_circle');
    expect(props['title']).toBe('Pause investments?');
    expect(props['body']).toContain('4-month investing streak');
    expect(props['confirmLabel']).toBe('Pause anyway');
    expect(props['cancelLabel']).toBe('Keep investing');
    expect(investments.pauseSchedule).toHaveBeenCalledOnceWith(schedule.id);
    expect(page.currentInvestment!.isPaused).toBeTrue();
  }));

  it('a bare dismiss of the pause sheet pauses nothing', fakeAsync(() => {
    page.currentInvestment = { ...schedule, isPaused: false, monthlyStreak: 4 };
    sheetDismissedWith(undefined);

    page.toggleInvestmentStatus();
    tick();

    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    expect(investments.pauseSchedule).not.toHaveBeenCalled();
    expect(pin.hasPin).not.toHaveBeenCalled();
    expect(page.currentInvestment!.isPaused).toBeFalse();
  }));

  it('resuming never asks', fakeAsync(() => {
    page.currentInvestment = { ...schedule, isPaused: true, monthlyStreak: 4 };
    investments.resumeSchedule.and.returnValue(of({ ...schedule, isPaused: false }));

    page.toggleInvestmentStatus();
    tick();

    expect(modalCtrl.create).not.toHaveBeenCalled();
    expect(investments.resumeSchedule).toHaveBeenCalledOnceWith(schedule.id);
  }));

  // ── error_shown: saving changes ────────────────────────────────────────────

  it('an amount under the minimum reports validation and never reaches the server', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule };
    page.amountInput = '0';

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(investments.createSchedule).not.toHaveBeenCalled();
  }));

  it('a dismissed step-up before saving reports auth_required', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule };
    page.amountInput = '250';
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(false);

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(investments.createSchedule).not.toHaveBeenCalled();
  }));

  it('a failed save reports save_failed with its status', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule };
    page.amountInput = '250';
    investments.createSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a save that never reached the server reports network, and no amount is sent either way', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule };
    page.amountInput = '250';
    investments.createSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
    expect(analytics.track).not.toHaveBeenCalled();
  }));

  // ── the reconnect card (R10, R18) ──────────────────────────────────────────

  const brokenBank: PrimaryBankAccount = {
    accountId: 'acc-1',
    institutionName: 'Chase',
    accountName: 'Plaid Checking',
    accountType: 'depository',
    accountSubtype: 'checking',
    mask: '0000',
    itemStatus: 'LOGIN_REQUIRED',
    transactionsConsented: true,
    consentExpiresAt: null,
  };

  it('cardRendersWithoutSchedule: a 404 schedule still loads the bank and yields a card', () => {
    investments.getCurrentSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    plaid.getPrimaryBankAccount.and.returnValue(of(brokenBank));

    page.ngOnInit();

    expect(page.currentInvestment).toBeNull();
    expect(page.reconnectAccount).toEqual(brokenBank);
    expect(reconnectCardFor(page.reconnectAccount)).not.toBeNull();
    expect(analytics.track).not.toHaveBeenCalled();
  });

  it('reconnectGoesToChangeBankInUpdateMode', () => {
    page.onReconnect();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/change-bank-account'], { queryParams: { mode: 'update' } });
  });

  it('hiddenForRejectedApplicant: R18, the card is for users who can invest', () => {
    investments.getCurrentSchedule.and.returnValue(of(schedule));
    plaid.getPrimaryBankAccount.and.returnValue(of(brokenBank));
    status$.next('REJECTED');

    page.ngOnInit();

    expect(page.reconnectAccount).toBeNull();
  });
});

/**
 * The amount field. This is the one amount in the app that is typed, not slid: Andy's call, so the
 * page keeps the keyboard. A text input on the decimal keypad, the $ prefix sharing its underline,
 * with keyboard avoidance on the content. Rendered through TestBed.
 */
describe('RecurringInvestmentsPage: typed amount', () => {
  let fixture: ComponentFixture<RecurringInvestmentsPage>;
  let page: RecurringInvestmentsPage;
  let investments: jasmine.SpyObj<InvestmentService>;

  beforeEach(async () => {
    investments = jasmine.createSpyObj<InvestmentService>('InvestmentService', [
      'getCurrentSchedule',
      'pauseSchedule',
      'resumeSchedule',
      'createSchedule',
    ]);
    investments.getCurrentSchedule.and.returnValue(of(schedule));
    investments.createSchedule.and.returnValue(of(schedule));
    const pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);

    await TestBed.configureTestingModule({
      imports: [RecurringInvestmentsPage],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { queryParams: of({}) } },
        { provide: InvestmentService, useValue: investments },
        { provide: PasskeyService, useValue: {} },
        { provide: PinService, useValue: pin },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['showToast']) },
        { provide: AccountStatusService, useValue: { isAccountActive: () => true, accountStatus$: of('ACTIVE') } },
        { provide: PlaidService, useValue: { getPrimaryBankAccount: () => of(null) } },
        { provide: ModalController, useValue: {} },
        { provide: NavController, useValue: { navigateBack: () => {} } },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(RecurringInvestmentsPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
    // ngModel writes the loaded amount into the input a microtask later.
    await fixture.whenStable();
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const input = () => el().querySelector('input.currency-field-input') as HTMLInputElement;
  const save = () => el().querySelector('.cta-btn') as HTMLButtonElement;

  /** What a user types: the field's value, then the input event ngModel listens for. */
  function type(text: string): void {
    input().value = text;
    input().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('the amount is a text input on the decimal keypad, showing the schedule amount; no tray anywhere', () => {
    expect(input()).not.toBeNull();
    expect(input().type).toBe('text');
    expect(input().getAttribute('inputmode')).toBe('decimal');
    expect(input().value).toBe('100');
    expect(input().previousElementSibling!.textContent).toBe('$');
    // The one .field-value-btn on the page is the transfer date, which opens a sheet, not a tray.
    expect(el().querySelector('.field-value-btn:not(.transfer-date-btn)')).toBeNull();
    expect(el().querySelector('.field-tray')).toBeNull();
    expect(el().querySelector('.field-tray-done')).toBeNull();
  });

  it('the content avoids the keyboard, since a field types again', () => {
    const content = fixture.debugElement.query(By.directive(KeyboardAvoidDirective));
    expect(content).not.toBeNull();
    expect(content.nativeElement.tagName.toLowerCase()).toBe('ion-content');
  });

  it('typing a new amount updates the edited amount and lights Save', () => {
    expect(page.hasChanges()).toBeFalse();
    expect(save().disabled).toBeTrue();
    type('250');
    expect(page.amountInput).toBe('250');
    expect(page.editedAmount()).toBe(250);
    expect(page.hasChanges()).toBeTrue();
    expect(save().disabled).toBeFalse();
  });

  it('cents type through: the decimal keypad is there for them', () => {
    type('12.50');
    expect(page.editedAmount()).toBe(12.5);
    expect(save().disabled).toBeFalse();
  });

  it('a decimal comma from a non-US keypad reads as cents', () => {
    type('12,50');
    expect(page.editedAmount()).toBe(12.5);
    expect(page.isAmountValid()).toBeTrue();
    expect(save().disabled).toBeFalse();
  });

  it('a thousands comma is dropped, with or without cents after it', () => {
    type('1,000');
    expect(page.editedAmount()).toBe(1000);
    expect(save().disabled).toBeFalse();
    type('1,000.50');
    expect(page.editedAmount()).toBe(1000.5);
    type('1,250,000');
    expect(page.editedAmount()).toBe(1250000);
  });

  it('a comma that is neither grouping nor a single decimal still reads as not a number', () => {
    type('1,2,3');
    expect(page.editedAmount()).toBe(0);
    expect(save().disabled).toBeTrue();
  });

  it('an empty field, a non-number or under a dollar disables Save', () => {
    type('');
    expect(page.editedAmount()).toBe(0);
    expect(page.isAmountValid()).toBeFalse();
    expect(save().disabled).toBeTrue();
    type('abc');
    expect(page.editedAmount()).toBe(0);
    expect(save().disabled).toBeTrue();
    type('0.5');
    expect(page.editedAmount()).toBe(0.5);
    expect(save().disabled).toBeTrue();
  });

  it('leaving the field under a dollar lifts it to the $1 floor; a whole amount is left alone', async () => {
    type('0.5');
    input().dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.amountInput).toBe('1');
    expect(input().value).toBe('1');
    type('250');
    input().dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(page.amountInput).toBe('250');
  });

  it('Save sends the typed amount as a number', fakeAsync(() => {
    type('250');
    page.saveChanges();
    tick();
    expect(investments.createSchedule).toHaveBeenCalledWith(jasmine.objectContaining({ investmentAmount: 250 }));
  }));
});

/**
 * The loading skeleton: both cards in outline while the schedule is fetched, with the data's shapes
 * where the data will land, gone the moment it does. A reload behind a schedule that is already up
 * (after a save) keeps the real cards.
 */
describe('RecurringInvestmentsPage: loading skeleton', () => {
  let fixture: ComponentFixture<RecurringInvestmentsPage>;
  let page: RecurringInvestmentsPage;
  let load$: Subject<InvestmentSchedule>;

  beforeEach(async () => {
    load$ = new Subject<InvestmentSchedule>();
    const investments = jasmine.createSpyObj<InvestmentService>('InvestmentService', [
      'getCurrentSchedule',
      'pauseSchedule',
      'resumeSchedule',
      'createSchedule',
    ]);
    investments.getCurrentSchedule.and.returnValue(load$.asObservable());
    const pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);

    await TestBed.configureTestingModule({
      imports: [RecurringInvestmentsPage],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { queryParams: of({}) } },
        { provide: InvestmentService, useValue: investments },
        { provide: PasskeyService, useValue: {} },
        { provide: PinService, useValue: pin },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['showToast']) },
        { provide: AccountStatusService, useValue: { isAccountActive: () => true, accountStatus$: of('ACTIVE') } },
        { provide: PlaidService, useValue: { getPrimaryBankAccount: () => of(null) } },
        { provide: ModalController, useValue: {} },
        { provide: NavController, useValue: { navigateBack: () => {} } },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(RecurringInvestmentsPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const skeletons = () => el().querySelectorAll('app-skeleton').length;

  it('shows the skeleton while the schedule loads: banner, stats, two fields, date and CTA, and no real content', () => {
    expect(page.isLoading).toBeTrue();
    expect(el().querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(skeletons()).toBeGreaterThan(0);
    expect(el().querySelector('.status-banner--sk')).not.toBeNull();
    expect(el().querySelectorAll('.schedule-stats .stat-col app-skeleton').length).toBe(3);
    // No streak placeholder: the real pill only shows with a streak, so a stand-in would shift
    // the cards for every first-month user when it left.
    expect(el().querySelector('.streak-badge-row')).toBeNull();
    expect(el().querySelectorAll('.edit-row .edit-field app-skeleton').length).toBe(2);
    expect(el().querySelector('.date-btn-sk')).not.toBeNull();
    // The date field is one underline button and one echo line, so its placeholder is too.
    expect(el().querySelectorAll('.edit-field--full app-skeleton').length).toBe(2);
    expect(el().querySelector('.edit-field--full .field-echo app-skeleton')).not.toBeNull();
    expect(el().querySelector('.cta-sk')).not.toBeNull();
    // Nothing real in the skeleton's place.
    expect(el().querySelector('.status-active, .status-paused')).toBeNull();
    expect(el().querySelector('.stat-value')).toBeNull();
    expect(el().querySelector('.currency-field-input')).toBeNull();
    expect(el().querySelector('.transfer-date-btn')).toBeNull();
    expect(el().querySelector('.cta-btn')).toBeNull();
    expect(el().querySelector('.empty-card')).toBeNull();
  });

  it('swaps to the real cards when the schedule lands, with nothing left of the skeleton', () => {
    load$.next(schedule);
    fixture.detectChanges();

    expect(skeletons()).toBe(0);
    expect(el().querySelector('[aria-busy="true"]')).toBeNull();
    expect(el().querySelector('.status-banner.status-active')).not.toBeNull();
    expect(el().querySelectorAll('.stat-value').length).toBe(3);
    expect(el().querySelector('.currency-field-input')).not.toBeNull();
    expect(el().querySelector('.transfer-date-btn')).not.toBeNull();
    expect(el().querySelector('.cta-btn')).not.toBeNull();
  });

  it('a 404 lands on the empty state, not the skeleton', () => {
    load$.error(new HttpErrorResponse({ status: 404 }));
    fixture.detectChanges();

    expect(skeletons()).toBe(0);
    expect(el().querySelector('[aria-busy="true"]')).toBeNull();
    expect(el().querySelector('.empty-card')).not.toBeNull();
  });

  it('a reload behind a schedule that is already up keeps the real cards', () => {
    load$.next(schedule);
    fixture.detectChanges();
    page.isLoading = true;            // what a save does before it refetches
    fixture.detectChanges();

    expect(skeletons()).toBe(0);
    expect(el().querySelector('.currency-field-input')).not.toBeNull();
  });
});

/**
 * The transfer date: an underline button that opens the FRED date sheet in upcoming mode, with
 * one echo line under it for the trade window. Nothing is written until the sheet's Done; a
 * semi-monthly schedule locks the field, since the backend owns the 1st and 15th. Rendered through
 * TestBed with the modal controller spied, so the options the page sends are read off create().
 */
describe('RecurringInvestmentsPage: transfer date', () => {
  let fixture: ComponentFixture<RecurringInvestmentsPage>;
  let page: RecurringInvestmentsPage;
  let investments: jasmine.SpyObj<InvestmentService>;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let modalEl: { present: jasmine.Spy; onDidDismiss: jasmine.Spy };

  /** The date sheet, presented and then dismissed: Done with a value, or any other way with nothing. */
  function sheetReturns(value: string | null): void {
    modalEl.onDidDismiss.and.resolveTo(value === null ? { role: undefined } : { role: FRED_DATE_SHEET_ROLE, data: { value } });
  }

  async function setUp(loaded: InvestmentSchedule): Promise<void> {
    investments = jasmine.createSpyObj<InvestmentService>('InvestmentService', [
      'getCurrentSchedule',
      'pauseSchedule',
      'resumeSchedule',
      'createSchedule',
    ]);
    investments.getCurrentSchedule.and.returnValue(of(loaded));
    investments.createSchedule.and.returnValue(of(loaded));
    const pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    modalEl = {
      present: jasmine.createSpy('present').and.resolveTo(),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role: undefined }),
    };
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    modalCtrl.create.and.resolveTo(modalEl as unknown as HTMLIonModalElement);

    await TestBed.configureTestingModule({
      imports: [RecurringInvestmentsPage],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { queryParams: of({}) } },
        { provide: InvestmentService, useValue: investments },
        { provide: PasskeyService, useValue: {} },
        { provide: PinService, useValue: pin },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['showToast']) },
        { provide: AccountStatusService, useValue: { isAccountActive: () => true, accountStatus$: of('ACTIVE') } },
        { provide: PlaidService, useValue: { getPrimaryBankAccount: () => of(null) } },
        { provide: ModalController, useValue: modalCtrl },
        { provide: NavController, useValue: { navigateBack: () => {} } },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(RecurringInvestmentsPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const button = () => el().querySelector('.transfer-date-btn') as HTMLButtonElement;
  const echo = () => el().querySelector('.edit-field--full .field-echo') as HTMLElement;
  const echoGlyph = () => echo().querySelector('.material-symbols-outlined')!.textContent!.trim();
  const echoGlyphHidden = () => echo().querySelector('.material-symbols-outlined')!.getAttribute('aria-hidden');
  const save = () => el().querySelector('.cta-btn') as HTMLButtonElement;
  const sheetOptions = () => modalCtrl.create.calls.mostRecent().args[0].componentProps as FredDateSheetOptions;

  /** The page's `:host .field-value-btn.transfer-date-btn:disabled` rule, from the stylesheet Angular attached for it. */
  function disabledRule(): CSSStyleRule | undefined {
    const rules: CSSStyleRule[] = [];
    for (const sheet of Array.from(document.styleSheets)) {
      // A cross-origin sheet (the Manrope link) throws on read and holds nothing of ours anyway.
      let own: CSSRule[];
      try { own = Array.from(sheet.cssRules); } catch { own = []; }
      rules.push(...own.filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule));
    }
    return rules.find(rule => /\.transfer-date-btn\S*:disabled$/.test(rule.selectorText));
  }

  /** Taps the field and lets the sheet's present-and-await settle. */
  async function tapDate(): Promise<void> {
    button().click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Picks a frequency in the select, as a user would. */
  function chooseFrequency(value: InvestmentSchedule['frequency']): void {
    const select = el().querySelector('.field-select') as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  describe('with a weekly schedule', () => {
    beforeEach(() => setUp(schedule));

    it('shows the saved date in the underline button with the calendar glyph, and nothing of the old picker', () => {
      expect(button()).not.toBeNull();
      expect(button().classList).toContain('field-value-btn');
      expect(button().getAttribute('type')).toBe('button');
      expect(button().getAttribute('aria-haspopup')).toBe('dialog');
      expect(button().getAttribute('aria-label')).toBe('Transfer date, Monday, October 26, 2026. Opens a date picker');
      expect(button().disabled).toBeFalse();
      expect(button().querySelector('span')!.textContent).toBe(formatShort('2026-10-26'));
      expect(button().querySelector('.field-placeholder')).toBeNull();
      expect(button().querySelector('.material-symbols-outlined')!.textContent!.trim()).toBe('calendar_month');
      expect(el().querySelector('ion-datetime')).toBeNull();
      expect(el().querySelector('ion-modal')).toBeNull();
      expect(el().querySelector('.date-btn')).toBeNull();
    });

    it('one echo line under the field says when the trades land, in blue with the schedule glyph', () => {
      expect(el().querySelectorAll('.edit-field--full .field-echo').length).toBe(1);
      expect(el().querySelectorAll('.edit-field--full .helper-hint').length).toBe(0);
      expect(echo().classList).toContain('primary');
      expect(echoGlyph()).toBe('schedule');
      expect(echo().textContent!.replace('schedule', '').trim()).toBe(`Est. investment ${tradeWindow('2026-10-26')}`);
      // The glyph is decoration: VoiceOver reads the echo text, not "schedule".
      expect(echoGlyphHidden()).toBe('true');
      expect(tradeWindow('2026-10-26')).toBe('Oct 27 to Oct 29');
    });

    it('tapping the field opens the date sheet in upcoming mode with the saved date, today as the floor, business days only, the weekly quick picks and the trade-window subline', async () => {
      await tapDate();

      expect(modalCtrl.create).toHaveBeenCalledTimes(1);
      const createArgs = modalCtrl.create.calls.mostRecent().args[0];
      expect(createArgs.component).toBe(FredDateSheetComponent);
      expect(createArgs.cssClass).toBe('mc-bottom-sheet-modal');
      expect(modalEl.present).toHaveBeenCalled();

      const today = todayYmd();
      const opts = sheetOptions();
      expect(opts.title).toBe('Transfer date');
      expect(opts.mode).toBe('upcoming');
      expect(opts.value).toBe('2026-10-26');
      expect(opts.min).toBe(today);
      expect(opts.max).toBeUndefined();
      expect(opts.businessDaysOnly).toBeTrue();
      expect(opts.quickPicks).toEqual(quickPicks('WEEKLY', today, today, true));
      expect(opts.quickPicks!.length).toBeGreaterThan(0);
      expect(opts.subline!('2026-11-20')).toBe('Trades land Nov 23 to Nov 25');
      expect(opts.doneLabel).toBeUndefined();
    });

    it('Done writes the date to the edit, as YYYY-MM-DD: the button, the echo and Save all follow', async () => {
      sheetReturns('2026-12-04');
      expect(page.hasChanges()).toBeFalse();

      await tapDate();

      expect(page.editedInvestment.nextInvestmentDate).toBe('2026-12-04');
      expect(button().querySelector('span')!.textContent).toBe(formatShort('2026-12-04'));
      expect(button().getAttribute('aria-label')).toBe('Transfer date, Friday, December 4, 2026. Opens a date picker');
      expect(echo().textContent).toContain(`Est. investment ${tradeWindow('2026-12-04')}`);
      expect(page.hasChanges()).toBeTrue();
      expect(save().disabled).toBeFalse();
      // The saved schedule is untouched until Save.
      expect(page.currentInvestment!.nextInvestmentDate).toBe('2026-10-26');
    });

    it('a bare dismiss (drag, backdrop) changes nothing', async () => {
      sheetReturns(null);

      await tapDate();

      expect(modalCtrl.create).toHaveBeenCalledTimes(1);
      expect(page.editedInvestment.nextInvestmentDate).toBe('2026-10-26');
      expect(button().querySelector('span')!.textContent).toBe(formatShort('2026-10-26'));
      expect(page.hasChanges()).toBeFalse();
      expect(save().disabled).toBeTrue();
    });

    it('Save sends the picked date and the original start date as plain YYYY-MM-DD', fakeAsync(() => {
      page.editedInvestment.nextInvestmentDate = '2026-12-04';

      page.saveChanges();
      tick();

      expect(investments.createSchedule).toHaveBeenCalledOnceWith(jasmine.objectContaining({
        investmentAmount: 100,
        frequency: 'WEEKLY',
        startDate: '2026-01-05',
        nextInvestmentDate: '2026-12-04',
      }));
    }));

    it('the sheet opens with the edited date once there is one', async () => {
      page.editedInvestment.nextInvestmentDate = '2026-12-04';
      fixture.detectChanges();

      await tapDate();

      expect(sheetOptions().value).toBe('2026-12-04');
    });

    it('the quick picks follow the frequency being edited, not the saved one', async () => {
      chooseFrequency('MONTHLY');

      await tapDate();

      const today = todayYmd();
      expect(sheetOptions().quickPicks).toEqual(quickPicks('MONTHLY', today, today, true));
      expect(sheetOptions().quickPicks!.map(p => p.label)).toContain('Month end');
    });

    it('semi-monthly locks the field: disabled at opacity .4, the lock echo in grey, and a tap opens nothing', async () => {
      chooseFrequency('SEMI_MONTHLY');

      expect(button().disabled).toBeTrue();
      expect(button().matches(':disabled')).toBeTrue();
      expect(button().getAttribute('aria-label')).toContain(', set by your pay schedule');
      // The .4 is read from the page's own stylesheet, not computed: the button is light DOM inside
      // ion-content, and until Stencil has rendered that slot, getComputedStyle returns '' for everything.
      expect(disabledRule()?.style.opacity).toBe('0.4');
      expect(echo().classList).not.toContain('primary');
      expect(echoGlyph()).toBe('lock');
      expect(echoGlyphHidden()).toBe('true');
      expect(echo().textContent!.replace('lock', '').trim()).toBe('Set by your pay schedule: the 1st and 15th.');
      // The seed is the backend's 1st or 15th, as before.
      expect(page.editedInvestment.nextInvestmentDate).toMatch(/^\d{4}-\d{2}-(01|15)$/);

      await page.openTransferDate();
      button().click();
      await fixture.whenStable();

      expect(modalCtrl.create).not.toHaveBeenCalled();
    });

    it('switching back from semi-monthly unlocks the field with the trade window again', async () => {
      chooseFrequency('SEMI_MONTHLY');
      chooseFrequency('WEEKLY');

      expect(button().disabled).toBeFalse();
      expect(echo().classList).toContain('primary');
      expect(echoGlyph()).toBe('schedule');
      expect(echo().textContent).toContain('Est. investment ');
    });

    it('leaving semi-monthly for another frequency drops its 1st/15th seed: the saved date is back, so nothing is saved as the date', fakeAsync(() => {
      chooseFrequency('SEMI_MONTHLY');
      chooseFrequency('MONTHLY');

      // The seed can be a weekend, which the sheet never lets a monthly schedule pick.
      expect(page.editedInvestment.nextInvestmentDate).toBe('2026-10-26');
      expect(button().querySelector('span')!.textContent).toBe(formatShort('2026-10-26'));
      expect(echo().textContent).toContain(`Est. investment ${tradeWindow('2026-10-26')}`);

      // Back on the saved frequency there is nothing left to save.
      chooseFrequency('WEEKLY');
      expect(page.hasChanges()).toBeFalse();

      chooseFrequency('MONTHLY');
      page.saveChanges();
      tick();
      expect(investments.createSchedule).toHaveBeenCalledOnceWith(jasmine.objectContaining({
        frequency: 'MONTHLY',
        nextInvestmentDate: '2026-10-26',
      }));
    }));

    it('a date picked in the sheet survives a frequency change that never went through semi-monthly', async () => {
      sheetReturns('2026-12-04');
      await tapDate();
      chooseFrequency('MONTHLY');

      expect(page.editedInvestment.nextInvestmentDate).toBe('2026-12-04');
      expect(button().querySelector('span')!.textContent).toBe(formatShort('2026-12-04'));
    });
  });

  describe('with dates the backend sent as LocalDate arrays', () => {
    beforeEach(() => setUp({ ...schedule, startDate: [2026, 1, 5], nextInvestmentDate: [2026, 10, 26] }));

    it('reads them as the same local day and shows no change until the sheet picks one', () => {
      expect(button().querySelector('span')!.textContent).toBe(formatShort('2026-10-26'));
      expect(echo().textContent).toContain(`Est. investment ${tradeWindow('2026-10-26')}`);
      expect(el().querySelector('.stat-col--end .stat-value')!.textContent).toBe('Oct 26');
      expect(page.hasChanges()).toBeFalse();
    });

    it('opens the sheet on that day and saves both dates as plain YYYY-MM-DD', fakeAsync(() => {
      page.openTransferDate();
      tick();
      expect(sheetOptions().value).toBe('2026-10-26');

      page.saveChanges();
      tick();

      expect(investments.createSchedule).toHaveBeenCalledOnceWith(jasmine.objectContaining({
        startDate: '2026-01-05',
        nextInvestmentDate: '2026-10-26',
      }));
    }));
  });

  describe('with no next date yet', () => {
    beforeEach(() => setUp({ ...schedule, nextInvestmentDate: '' }));

    it('shows the placeholder, an empty echo, and opens the sheet with no value', async () => {
      expect(button().querySelector('.field-placeholder')!.textContent).toBe('Select date');
      expect(button().getAttribute('aria-label')).toBe('Transfer date, not set. Opens a date picker');
      expect(echo().textContent!.replace('schedule', '').trim()).toBe('');

      await tapDate();

      expect(sheetOptions().value).toBeNull();
    });
  });
});
