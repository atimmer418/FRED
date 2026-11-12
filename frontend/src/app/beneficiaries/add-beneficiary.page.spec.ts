import { DestroyRef, ElementRef } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import { AddBeneficiaryPage } from './add-beneficiary.page';
import { PlanAmountTrayComponent } from '../components/plan-amount-tray/plan-amount-tray.component';
import { KeyboardAvoidDirective } from '../directives/keyboard-avoid.directive';
import { BeneficiaryService, Beneficiary } from '../services/beneficiary.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';

/**
 * Analytics call sites on add-beneficiary.
 *
 * This form holds a name, a date of birth and an SSN, and its toasts quote the server's words
 * about the allocation. None of that may be sent, so the events here carry a category, a surface
 * and an HTTP status and nothing else.
 */
describe('AddBeneficiaryPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let beneficiaryService: jasmine.SpyObj<BeneficiaryService>;
  let pin: jasmine.SpyObj<PinService>;
  let page: AddBeneficiaryPage;

  /** A form the validator passes, so the specs below reach the paths they are about. */
  const complete: Partial<Beneficiary> = {
    beneficiaryType: 'PRIMARY',
    country: 'US',
    firstName: 'Dana',
    lastName: 'Example',
    dateOfBirth: '1990-04-18',
    socialSecurityNumber: '123-45-6789',
    relationship: 'SPOUSE',
    percentageAllocation: 50,
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
      'createBeneficiary',
      'updateBeneficiary',
    ]);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);

    page = new AddBeneficiaryPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      { params: of({}), queryParams: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      beneficiaryService,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
      new ElementRef(document.createElement('div')),
      { onDestroy: () => () => {} } as unknown as DestroyRef,
    );
  });

  // ── error_shown: loading an existing row for edit ──────────────────────────

  it('a row that is not in the response reports load_failed with no status', fakeAsync(() => {
    page.beneficiaryId = 99;
    beneficiaryService.getBeneficiaries.and.returnValue(of([]));

    page['loadBeneficiaryForEdit']();
    tick();

    // The read itself succeeded, so there is no status to report.
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('load_failed', 'portfolio', 'toast', null, false);
  }));

  it('a failed read reports load_failed with its status', fakeAsync(() => {
    page.beneficiaryId = 99;
    beneficiaryService.getBeneficiaries.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page['loadBeneficiaryForEdit']();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('load_failed', 'portfolio', 'toast', 500, false);
  }));

  it('a read that never reached the server reports network', fakeAsync(() => {
    page.beneficiaryId = 99;
    beneficiaryService.getBeneficiaries.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page['loadBeneficiaryForEdit']();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  }));

  // ── error_shown: saving ────────────────────────────────────────────────────

  it('a form the validator rejects reports validation exactly once, whichever field it was', fakeAsync(() => {
    page.beneficiary = { ...complete, firstName: '' };

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
    expect(beneficiaryService.createBeneficiary).not.toHaveBeenCalled();
  }));

  it('a badly formatted SSN is the same single validation event, and the value never leaves', fakeAsync(() => {
    page.beneficiary = { ...complete, socialSecurityNumber: '123456789' };

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('validation', 'portfolio', 'toast', null, false);
  }));

  it('a dismissed step-up before saving reports auth_required', fakeAsync(() => {
    page.beneficiary = { ...complete };
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(false);

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
    expect(beneficiaryService.createBeneficiary).not.toHaveBeenCalled();
  }));

  it('a server rejection reports save_failed with its status, not the allocation message', fakeAsync(() => {
    page.beneficiary = { ...complete };
    beneficiaryService.createBeneficiary.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { message: 'Total allocation cannot exceed 100%. Current total: 100.00%' },
          }),
      ),
    );

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'portfolio', 'toast', 400, false);
  }));

  it('a save that never reached the server reports network', fakeAsync(() => {
    page.beneficiary = { ...complete };
    beneficiaryService.createBeneficiary.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  }));

  it('a successful save fires nothing from the device', fakeAsync(() => {
    page.beneficiary = { ...complete };
    beneficiaryService.createBeneficiary.and.returnValue(of({ ...complete } as Beneficiary));

    page.onSave();
    tick();

    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
    expect(analytics.track).not.toHaveBeenCalled();
  }));
});

/**
 * The allocation field opens the slide tray instead of the keyboard: whole percents, 1 to 100,
 * the value landing on `beneficiary.percentageAllocation` exactly as typing did, so the validator
 * and the save payload see the same number. Karma's context has no layout, so the tray's motion
 * is driven to its end by hand where a spec needs the slot gone.
 */
describe('AddBeneficiaryPage: allocation tray', () => {
  let fixture: ComponentFixture<AddBeneficiaryPage>;
  let page: AddBeneficiaryPage;
  let showToast: jasmine.Spy;

  beforeEach(() => {
    showToast = jasmine.createSpy('showToast');
    const pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    TestBed.configureTestingModule({
      imports: [AddBeneficiaryPage],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { params: of({}), queryParams: of({}), snapshot: { queryParams: {} } } },
        { provide: BeneficiaryService, useValue: jasmine.createSpyObj<BeneficiaryService>('BeneficiaryService', ['getBeneficiaries', 'createBeneficiary', 'updateBeneficiary']) },
        { provide: PasskeyService, useValue: {} },
        { provide: PinService, useValue: pin },
        { provide: ToastService, useValue: { showToast } },
        { provide: NavController, useValue: jasmine.createSpyObj<NavController>('NavController', ['navigateBack']) },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown', 'screen']) },
      ],
    })
      // The Capacitor Keyboard plugin rejects on web, and the directive's listener would surface
      // that as an uncaught error in whichever spec is running when ion-content hydrates.
      .overrideComponent(AddBeneficiaryPage, { remove: { imports: [KeyboardAvoidDirective] } });
    fixture = TestBed.createComponent(AddBeneficiaryPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
  });

  const root = () => fixture.nativeElement as HTMLElement;
  const valueBtn = () => root().querySelector('.field-value-btn') as HTMLButtonElement;
  const label = () => root().querySelector('.field-label-row .field-label') as HTMLElement;
  const slot = () => root().querySelector('.field-tray') as HTMLElement | null;
  const done = () => root().querySelector('.field-tray-done') as HTMLButtonElement | null;
  const tray = () => fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;
  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  function open(): void {
    valueBtn().click();
    fixture.detectChanges();
  }

  function slide(value: number): void {
    tray()!.valueChange.emit(value);
    fixture.detectChanges();
  }

  /** Runs the collapse to its end, so the slot and Done leave as they would on the device. */
  async function settleClose(): Promise<void> {
    for (const anim of slot()?.getAnimations() ?? []) {
      const landed = new Promise<void>(resolve => anim.addEventListener('finish', () => resolve()));
      anim.finish();
      await landed;
    }
    fixture.detectChanges();
  }

  function tapOutside(target: Element): void {
    target.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 7 }));
    target.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 7 }));
    fixture.detectChanges();
  }

  it('the allocation is a value button with a % affix and a placeholder, not a number input', () => {
    expect(root().querySelector('input[type="number"]')).toBeNull();
    expect(valueBtn()).not.toBeNull();
    expect(valueBtn().querySelector('.field-placeholder')?.textContent).toBe('0');
    expect(valueBtn().querySelector('.field-affix')?.textContent).toBe('%');
    expect(text(label())).toBe('Allocation Percentage*');
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
  });

  it('a tap opens the tray under the field on a 1 to 100 percent scale, with Done in the label row', () => {
    open();
    expect(page.tray.open()).toBe('allocation');
    expect(valueBtn().classList.contains('active')).toBeTrue();
    expect(valueBtn().getAttribute('aria-expanded')).toBe('true');
    expect(label().classList.contains('active')).toBeTrue();
    expect(valueBtn().nextElementSibling).toBe(slot());
    expect(done()).not.toBeNull();
    expect(label().parentElement!.contains(done())).toBeTrue();

    const t = tray()!;
    expect(t.scale()).toEqual({ min: 1, max: 100, step: 1, fine: 1 });
    expect(t.unit()).toBe('percent');
    const slider = root().querySelector('.ps')!;
    expect(slider.getAttribute('aria-label')).toBe('Allocation Percentage');
    expect(slider.getAttribute('aria-valuemin')).toBe('1');
    expect(slider.getAttribute('aria-valuemax')).toBe('100');
    const ends = Array.from(root().querySelectorAll('.ps-ends span')).map(e => e.textContent);
    expect(ends).toEqual(['1%', '100%']);
  });

  it('a detent lands on beneficiary.percentageAllocation and the button shows it with %', () => {
    open();
    slide(35);
    expect(page.beneficiary.percentageAllocation).toBe(35);
    expect(text(valueBtn())).toBe('35 %');
    expect(valueBtn().querySelector('.field-placeholder')).toBeNull();
    expect(root().querySelector('.ps')!.getAttribute('aria-valuetext')).toBe('35%');
  });

  it('an edit load that lands after the tray opened reaches the tray: the thumb and the nudge base follow the loaded row', () => {
    open();
    expect(root().querySelector('.ps')!.getAttribute('aria-valuenow')).toBe('1');
    // The load replaces the whole object, as loadBeneficiaryForEdit does, with no signal in sight.
    page.beneficiary = { ...page.beneficiary, percentageAllocation: 50 };
    fixture.detectChanges();
    expect(text(valueBtn())).toBe('50 %');
    expect(tray()!.value()).toBe(50);
    expect(root().querySelector('.ps')!.getAttribute('aria-valuenow')).toBe('50');
    (root().querySelector('.nudge[aria-label="More"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(page.beneficiary.percentageAllocation).toBe(51);
  });

  it('a seeded allocation with decimals shows exactly as saved until the first touch', () => {
    page.beneficiary.percentageAllocation = 33.33;
    fixture.detectChanges();
    expect(text(valueBtn())).toBe('33.33 %');
    open();
    expect(root().querySelector('.ps')!.getAttribute('aria-valuenow')).toBe('33.33');
  });

  it('while the finger is down the label reads the step and drops its star', () => {
    open();
    tray()!.dragging.emit(true);
    fixture.detectChanges();
    expect(text(label())).toBe('± 1%');
    tray()!.dragging.emit(false);
    fixture.detectChanges();
    expect(text(label())).toBe('Allocation Percentage*');
  });

  it('Done closes the tray; the slot and Done leave when the collapse lands', async () => {
    open();
    done()!.click();
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(valueBtn().classList.contains('active')).toBeFalse();
    await settleClose();
    expect(page.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
  });

  it('tapping the open field again closes it', () => {
    open();
    open();
    expect(page.tray.open()).toBeNull();
  });

  it('a tap elsewhere on the form closes the tray; a tap on the field or the tray does not', () => {
    open();
    tapOutside(root().querySelector('.ps')!);
    expect(page.tray.open()).toBe('allocation');
    tapOutside(root().querySelector('input.field-input')!);
    expect(page.tray.open()).toBeNull();
  });

  it('Save closes the tray before validating', async () => {
    open();
    await page.onSave();
    expect(page.tray.open()).toBeNull();
    expect(showToast).toHaveBeenCalledWith('First name is required', 'danger');
  });

  it('leaving the page snaps the tray shut', () => {
    open();
    page.ionViewWillLeave();
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(page.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
  });

  it('the validator still rejects an allocation of 0 and still passes a tray value', async () => {
    page.beneficiary = {
      beneficiaryType: 'PRIMARY', country: 'US', firstName: 'Dana', lastName: 'Example',
      dateOfBirth: '1990-04-18', socialSecurityNumber: '123-45-6789', relationship: 'SPOUSE', status: 'PENDING',
    };
    fixture.detectChanges();
    expect(page.isFormComplete).toBeFalse();
    open();
    slide(50);
    expect(page.isFormComplete).toBeTrue();
    expect(page['validateForm']()).toBeTrue();
  });
});
