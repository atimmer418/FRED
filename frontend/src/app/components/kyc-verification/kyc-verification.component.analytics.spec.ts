import { ChangeDetectorRef, Directive, Input } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { ModalController, NavController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { AlpacaAccountResponse, AlpacaService } from '../../services/alpaca.service';
import { AuthService, UserProgress } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { ToastService } from '../../services/toast.service';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { FRED_DATE_SHEET_ROLE, FredDateSheetComponent } from '../fred-date-sheet/fred-date-sheet.component';
import { todayYmd } from '../../utils/local-date';
import { KycVerificationComponent } from './kyc-verification.component';

const EMPTY_PROGRESS: UserProgress = {
  getStartedCompleted: true,
  surveyInitialCompleted: true,
  fiPlanResultsCompleted: true,
  authFinalizeCompleted: true,
  kycVerificationCompleted: false,
  linkPlaidCompleted: false,
  investmentScheduleCompleted: false,
  investmentConfirmationCompleted: false,
};

describe('KycVerificationComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let toastService: jasmine.SpyObj<ToastService>;
  let cdr: jasmine.SpyObj<ChangeDetectorRef>;
  let alpacaService: jasmine.SpyObj<AlpacaService>;
  let authService: AuthService;
  let component: KycVerificationComponent;
  let draftKey: string;

  function build(queryParams: Record<string, string> = {}): KycVerificationComponent {
    const route = {
      snapshot: { queryParamMap: convertToParamMap(queryParams) },
    } as unknown as ActivatedRoute;
    return new KycVerificationComponent(
      router,
      route,
      navCtrl,
      authService,
      alpacaService,
      toastService,
      cdr,
      analytics,
      jasmine.createSpyObj<ModalController>('ModalController', ['create']),
    );
  }

  /** A complete, valid part-1 form. Entirely invented data; none of it is ever sent to analytics. */
  function fillStep1(target: KycVerificationComponent): void {
    target.step1Form.patchValue({
      givenName: 'Test',
      familyName: 'User',
      emailAddress: 'test@example.com',
      dateOfBirth: '01/15/1990',
      phoneNumber: '2025550147',
      streetAddress: '1 Main St',
      city: 'Rockville',
      state: 'MD',
      postalCode: '20852',
      taxId: '000-00-0000',
      fundingSource: 'employment_income',
    });
  }

  beforeEach(() => {
    draftKey = `fred_kyc_step1_${localStorage.getItem('userId') || 'anon'}`;
    localStorage.removeItem(draftKey);

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    cdr = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['detectChanges']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    alpacaService = jasmine.createSpyObj<AlpacaService>('AlpacaService', [
      'createAlpacaAccount',
      'getKycData',
      'updateKyc',
    ]);
    alpacaService.getKycData.and.returnValue(of({}));
    authService = {
      getUnifiedProgress: () => EMPTY_PROGRESS,
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
    } as unknown as AuthService;

    component = build();
  });

  afterEach(() => {
    localStorage.removeItem(draftKey);
  });

  it('reports part 1 as viewed and not resumed with no saved draft', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('kyc_verification', 1, false);
  });

  it('reports part 2 as viewed and resumed when a draft put the user back on the second page', () => {
    localStorage.setItem(draftKey, JSON.stringify({ givenName: 'Test' }));

    component = build();
    component.ngOnInit();

    expect(component.currentStep).toBe(2);
    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('kyc_verification', 2, true);
  });

  it('reports the move to part 2 as a second viewed event', async () => {
    component.ngOnInit();
    fillStep1(component);
    analytics.trackOnboardingStepViewed.calls.reset();

    await component.proceedToStep2();

    expect(component.currentStep).toBe(2);
    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledOnceWith('kyc_verification', 2, false);
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });

  it('reports the step completed at submit, carrying the sub-step it was submitted from', async () => {
    component.ngOnInit();
    fillStep1(component);
    component.currentStep = 2;
    component.step2Form.patchValue({ agreementsChecked: true });
    alpacaService.createAlpacaAccount.and.returnValue(of({} as AlpacaAccountResponse));

    await component.submitKyc();

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('kyc_verification', 2);
  });

  it('reports a failed account creation as a save failure, with the status and no message', async () => {
    component.ngOnInit();
    fillStep1(component);
    component.currentStep = 2;
    component.step2Form.patchValue({ agreementsChecked: true });
    alpacaService.createAlpacaAccount.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 422, error: { message: 'Taxpayer id is invalid' } })),
    );

    await component.submitKyc();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('save_failed', 'onboarding', 'toast', 422, true);
    const everySentArgument = JSON.stringify(analytics.trackErrorShown.calls.allArgs());
    expect(everySentArgument).not.toContain('Taxpayer');
    // The toast, unlike analytics, does carry the server message, and reads as danger.
    expect(toastService.showToast).toHaveBeenCalledOnceWith('Taxpayer id is invalid', 'danger');
  });

  it('warns, rather than errors, when the settings form cannot load the saved KYC data', () => {
    alpacaService.getKycData.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    component = build({ edit: 'true' });

    component.ngOnInit();

    expect(toastService.showToast).toHaveBeenCalledOnceWith(
      'Could not load current KYC data. Please fill in your information.',
      'warning',
    );
  });

  it('marks a saved settings update as success', async () => {
    component = build({ edit: 'true' });
    component.ngOnInit();
    fillStep1(component);
    component.currentStep = 2;
    component.step2Form.patchValue({ agreementsChecked: true });
    alpacaService.updateKyc.and.returnValue(of({}));

    await component.submitKyc();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('Your identity information has been updated.', 'success');
  });

  it('reports a connection that never reached the server as a network error', async () => {
    component.ngOnInit();
    fillStep1(component);
    component.currentStep = 2;
    component.step2Form.patchValue({ agreementsChecked: true });
    alpacaService.createAlpacaAccount.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    await component.submitKyc();

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('network', 'onboarding', 'toast', null, true);
  });

  it('sends no onboarding event when the same screen is reused as a settings form', async () => {
    component = build({ edit: 'true' });

    component.ngOnInit();
    fillStep1(component);
    await component.proceedToStep2();

    expect(analytics.trackOnboardingStepViewed).not.toHaveBeenCalled();
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });
});

/**
 * The date of birth on part 1 is picked on the FRED date sheet in birthday mode, not typed. The
 * control keeps its MM/DD/YYYY string, so the validator and the Alpaca payload read what they always
 * did; the sheet speaks YYYY-MM-DD and the component adapts at that boundary. Done hands focus to
 * the phone input, as the eight-digit auto-advance did.
 */
/**
 * Stands in for the keyboard-avoid directive: the Capacitor Keyboard plugin rejects on web and the
 * real directive's listener would surface that as an uncaught error when ion-content hydrates.
 */
@Directive({ selector: '[appKeyboardAvoid]', standalone: true })
class KeyboardAvoidStubDirective {
  @Input() appKeyboardAvoid = '';
}

describe('KycVerificationComponent: date of birth sheet', () => {
  let fixture: ComponentFixture<KycVerificationComponent>;
  let component: KycVerificationComponent;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let present: jasmine.Spy;

  /** The sheet dismisses with this role and data when it is presented. */
  function sheetResolves(role: string | undefined, data?: { value?: string }): void {
    present = jasmine.createSpy('present').and.resolveTo();
    modalCtrl.create.and.resolveTo({
      present,
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role, data }),
    } as unknown as HTMLIonModalElement);
  }

  beforeEach(async () => {
    // Ionic's core CSS hides the body until the app hydrates; no ion-app exists here, and a hidden
    // input cannot take the focus these specs move around.
    document.documentElement.classList.add('hydrated');
    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    await TestBed.configureTestingModule({
      imports: [KycVerificationComponent],
      providers: [
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: NavController, useValue: jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']) },
        { provide: AuthService, useValue: {
            getUnifiedProgress: () => EMPTY_PROGRESS,
            completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
        } },
        { provide: AlpacaService, useValue: jasmine.createSpyObj<AlpacaService>('AlpacaService', ['createAlpacaAccount', 'getKycData', 'updateKyc']) },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['showToast']) },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
            'track', 'trackOnboardingStepViewed', 'trackOnboardingStepCompleted', 'trackErrorShown', 'screen',
        ]) },
        { provide: ModalController, useValue: modalCtrl },
      ],
    })
      .overrideComponent(KycVerificationComponent, {
        remove: { imports: [KeyboardAvoidDirective] },
        add: { imports: [KeyboardAvoidStubDirective] },
      })
      .compileComponents();
    localStorage.removeItem(`fred_kyc_step1_${localStorage.getItem('userId') || 'anon'}`);
    fixture = TestBed.createComponent(KycVerificationComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const dobBtn = () => el().querySelector('#dateOfBirthBtn') as HTMLButtonElement;
  const dobField = () => dobBtn().closest('.form-field') as HTMLElement;
  /** The button's text beside the glyph: the date, or the placeholder span. */
  const dobValue = () => dobBtn().querySelector('span:not(.material-symbols-outlined)') as HTMLElement;
  const phoneInput = () => el().querySelector('#phoneNumberInput') as HTMLInputElement;
  const control = () => component.step1Form.controls.dateOfBirth;
  const text = (node: Element | null) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it('the date of birth is a value button with the calendar glyph; nothing types it and nothing autofills it', () => {
    expect(dobBtn()).not.toBeNull();
    expect(dobBtn().classList).toContain('field-value-btn');
    expect(dobBtn().getAttribute('aria-haspopup')).toBe('dialog');
    expect(dobBtn().getAttribute('aria-label')).toBe('Date of birth, not set. Opens a date picker');
    expect(text(dobBtn().querySelector('.field-placeholder'))).toBe('Select date');
    expect(text(dobBtn().querySelector('.material-symbols-outlined'))).toBe('calendar_month');
    expect(el().querySelector('[formcontrolname="dateOfBirth"]')).toBeNull();
    expect(el().querySelector('[autocomplete="bday"]')).toBeNull();
  });

  it('shows the control\'s MM/DD/YYYY as a long date, and the placeholder for a half-typed draft', () => {
    control().setValue('03/14/1991');
    fixture.detectChanges();
    expect(text(dobValue())).toBe('Mar 14, 1991');
    expect(dobValue().classList).not.toContain('field-placeholder');
    expect(dobBtn().getAttribute('aria-label')).toBe('Date of birth, Mar 14, 1991. Opens a date picker');

    control().setValue('03/1');
    fixture.detectChanges();
    expect(text(dobBtn().querySelector('.field-placeholder'))).toBe('Select date');
  });

  it('a tap opens the birthday sheet on the control\'s date with max today, dropping the keyboard first', async () => {
    control().setValue('03/14/1991');
    sheetResolves(undefined);
    // ion-content stays visibility: hidden until Stencil hydrates it, so nothing in it can really
    // take focus here; the order of the calls is what matters.
    const order: string[] = [];
    spyOn(HTMLElement.prototype, 'blur').and.callFake(() => { order.push('blur'); });
    present.and.callFake(async () => { order.push('present'); });

    dobBtn().click();
    await fixture.whenStable();

    expect(modalCtrl.create).toHaveBeenCalledTimes(1);
    const opts = modalCtrl.create.calls.mostRecent().args[0];
    expect(opts.component).toBe(FredDateSheetComponent);
    expect(opts.componentProps).toEqual({
      title: 'Date of birth',
      mode: 'birthday',
      value: '1991-03-14',
      max: todayYmd(),
    });
    expect(order).toEqual(['blur', 'present']);
  });

  it('opens with no value when the control is empty or holds a date that does not exist', async () => {
    sheetResolves(undefined);
    await component.openDateOfBirth();
    expect(modalCtrl.create.calls.mostRecent().args[0].componentProps!['value']).toBeNull();

    control().setValue('02/30/1990');
    await component.openDateOfBirth();
    expect(modalCtrl.create.calls.mostRecent().args[0].componentProps!['value']).toBeNull();
  });

  it('Done writes MM/DD/YYYY to the control, marks it touched, keeps it valid and focuses the phone input', async () => {
    sheetResolves(FRED_DATE_SHEET_ROLE, { value: '1991-03-05' });
    const focus = spyOn(phoneInput(), 'focus').and.callThrough();

    await component.openDateOfBirth();
    fixture.detectChanges();

    expect(control().value).toBe('03/05/1991');
    expect(control().touched).toBeTrue();
    expect(control().valid).toBeTrue();
    expect(focus).toHaveBeenCalledTimes(1);
    expect(text(dobValue())).toBe('Mar 5, 1991');
    expect(dobField().classList).not.toContain('field-error');
  });

  it('a dismiss without Done leaves the control, its touched state and the focus alone', async () => {
    control().setValue('03/14/1991');
    const focus = spyOn(phoneInput(), 'focus');

    for (const role of [undefined, 'gesture', 'backdrop']) {
      sheetResolves(role);
      await component.openDateOfBirth();
    }
    sheetResolves(FRED_DATE_SHEET_ROLE, {});
    await component.openDateOfBirth();

    expect(control().value).toBe('03/14/1991');
    expect(control().touched).toBeFalse();
    expect(focus).not.toHaveBeenCalled();
  });

  it('an empty date reads Required once touched, in the error colour, and the validator still gates Continue', () => {
    expect(dobField().classList).not.toContain('field-error');
    component.step1Form.markAllAsTouched();
    fixture.detectChanges();
    expect(dobField().classList).toContain('field-error');
    expect(text(dobField().querySelector('.error-msg'))).toBe('Required');
    expect(component.step1Valid).toBeFalse();
  });

  it('a last-name autofill lands on the date of birth button, so the keyboard drops for the tap', fakeAsync(() => {
    const familyName = el().querySelector('#familyNameInput') as HTMLInputElement;
    familyName.value = 'Timmer';
    const focus = spyOn(dobBtn(), 'focus');
    familyName.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertFromPaste', data: 'Timmer' }));
    tick(100);
    expect(focus).toHaveBeenCalledTimes(1);
  }));
});
