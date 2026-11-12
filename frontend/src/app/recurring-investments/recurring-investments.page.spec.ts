import { ElementRef } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { ModalController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { RecurringInvestmentsPage } from './recurring-investments.page';
import { InvestmentService, InvestmentSchedule } from '../services/investment.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AccountStatusService } from '../services/account-status.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { PlanAmountTrayComponent } from '../components/plan-amount-tray/plan-amount-tray.component';

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
    accountStatus = jasmine.createSpyObj<AccountStatusService>('AccountStatusService', ['isAccountActive']);
    accountStatus.isAccountActive.and.returnValue(true);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);

    page = new RecurringInvestmentsPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      { queryParams: of({}), params: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      investments,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      accountStatus,
      modalCtrl,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
      new ElementRef(document.createElement('div')),
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
    // The amount lives apart from the draft now: the tray drives it.
    page.editedInvestment = { ...schedule };
    page.editedAmount.set(0);

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(investments.createSchedule).not.toHaveBeenCalled();
  }));

  it('a dismissed step-up before saving reports auth_required', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule };
    page.editedAmount.set(250);
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
    page.editedAmount.set(250);
    investments.createSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a save that never reached the server reports network, and no amount is sent either way', fakeAsync(() => {
    page.currentInvestment = { ...schedule };
    page.editedInvestment = { ...schedule };
    page.editedAmount.set(250);
    investments.createSchedule.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.saveChanges();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
    expect(analytics.track).not.toHaveBeenCalled();
  }));
});

/**
 * The amount's slide tray. The amount is a value button, not an input: a tap opens the tray under
 * the edit row, a detent lands on `editedAmount`, and Done, a tap outside, Save and leaving the page
 * close it. Rendered through TestBed; Karma's context document has no layout, so measured heights
 * are whatever scrollHeight says.
 */
describe('RecurringInvestmentsPage: amount tray', () => {
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
        { provide: AccountStatusService, useValue: { isAccountActive: () => true } },
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

  it('the amount is a value button at rest, not an input, showing the schedule amount; nothing on the page types', () => {
    expect(el().querySelector('input')).toBeNull();
    expect(shownValue()).toBe('$100');
    expect(label().textContent).toBe('Amount');
    expect(el().querySelector('ion-content')!.hasAttribute('appkeyboardavoid')).toBeFalse();
    expect(page.tray.open()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
  });

  it('a tap opens the tray under the edit row on the per-period scale, with Done in the label row', () => {
    tap();
    expect(page.tray.open()).toBe('amount');
    expect(valueBtn().classList.contains('active')).toBeTrue();
    expect(valueBtn().getAttribute('aria-expanded')).toBe('true');
    expect(label().classList.contains('active')).toBeTrue();
    expect(done()).not.toBeNull();
    expect(done()!.parentElement).toBe(label().parentElement);
    expect(el().querySelector('.edit-row')!.nextElementSibling).toBe(slot());
    expect(tray()!.scale()).toEqual({
      min: 5, max: 10_000, fine: 5,
      segments: [{ to: 500, step: 5 }, { to: 2000, step: 50 }, { to: 5000, step: 250 }, { to: 10_000, step: 500 }],
    });
    expect(Array.from(el().querySelectorAll('.ps-ends span')).map(s => s.textContent)).toEqual(['$5', '$10,000']);
    expect(el().querySelectorAll('.ps-mark').length).toBe(3);
    expect(el().querySelector('.ps')!.getAttribute('aria-label')).toBe('Amount');
    expect(el().querySelector('.ps')!.getAttribute('aria-valuetext')).toBe('$100');
  });

  it('a detent lands on the edited amount: the button shows it and Save lights up', () => {
    expect(page.hasChanges()).toBeFalse();
    tap();
    tray()!.valueChange.emit(2050);
    fixture.detectChanges();
    expect(page.editedAmount()).toBe(2050);
    expect(tray()!.value()).toBe(2050);
    expect(shownValue()).toBe('$2,050');
    expect(page.hasChanges()).toBeTrue();
    expect((el().querySelector('.cta-btn') as HTMLButtonElement).disabled).toBeFalse();
  });

  it('the label reads the step in force while the finger is down, and the name again on release', () => {
    tap();
    page.tray.onDragging(true);
    fixture.detectChanges();
    expect(label().textContent).toBe('± $5');
    page.editedAmount.set(3000);
    fixture.detectChanges();
    expect(label().textContent).toBe('± $250');
    page.tray.onDragging(false);
    fixture.detectChanges();
    expect(label().textContent).toBe('Amount');
  });

  it('Done closes the tray; the slot leaves when the collapse lands', async () => {
    tap();
    done()!.click();
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(valueBtn().classList.contains('active')).toBeFalse();
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
    const hint = el().querySelector('.helper-hint')!;
    pointer('pointerdown', hint);
    expect(page.tray.open()).toBe('amount');
    pointer('pointerup', hint);
    expect(page.tray.open()).toBeNull();
    await settle();
    expect(slot()).toBeNull();
  });

  it('Save closes the tray and sends the amount the tray set', fakeAsync(() => {
    tap();
    tray()!.valueChange.emit(250);
    fixture.detectChanges();
    page.saveChanges();
    expect(page.tray.open()).toBeNull();
    tick();
    expect(investments.createSchedule).toHaveBeenCalledWith(jasmine.objectContaining({ investmentAmount: 250 }));
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
