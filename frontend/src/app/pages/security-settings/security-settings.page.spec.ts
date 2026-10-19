import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Router } from '@angular/router';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NavController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import { SecuritySettingsPage } from './security-settings.page';
import { AppLockService } from '../../services/app-lock.service';
import { PinService, PinResponse } from '../../services/pin.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { AnalyticsConsentService } from '../../services/analytics/analytics-consent.service';

/**
 * Spec for SecuritySettingsPage three-state gating logic.
 *
 * AC-3: checking / verify / settings state machine — no content leak either direction.
 * AC-4: loadSessions() + syncAlpacaAccountNumber() still called after gate passes.
 * AC-5: authenticateUser() is gone; no dead code.
 */
describe('SecuritySettingsPage — three-state gating (AC-3, AC-4, AC-5)', () => {
  let component: SecuritySettingsPage;
  let fixture: ComponentFixture<SecuritySettingsPage>;

  let pinServiceSpy: jasmine.SpyObj<PinService>;
  let routerSpy: jasmine.SpyObj<Router>;
  let loadSessionsSpy: jasmine.Spy;
  let syncAlpacaSpy: jasmine.Spy;

  function createComponent(hasPinResult: boolean, promptResult: boolean = true) {
    pinServiceSpy.hasPin.and.returnValue(Promise.resolve(hasPinResult));
    pinServiceSpy.promptPin.and.returnValue(Promise.resolve(promptResult));

    TestBed.configureTestingModule({
      imports: [
        SecuritySettingsPage,
        HttpClientTestingModule
      ],
      providers: [
        { provide: Router, useValue: routerSpy },
        { provide: NavController, useValue: { navigateBack: jasmine.createSpy() } },
        {
          provide: AppLockService,
          useValue: { isEnabled: () => false, setEnabled: jasmine.createSpy() }
        },
        { provide: PinService, useValue: pinServiceSpy },
        { provide: ToastService, useValue: { showToast: jasmine.createSpy() } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SecuritySettingsPage);
    component = fixture.componentInstance;

    // Spy on the data-loading methods so we can assert they're called without
    // making real HTTP requests.
    loadSessionsSpy = spyOn(component, 'loadSessions');
    syncAlpacaSpy = spyOn(component, 'syncAlpacaAccountNumber');
  }

  beforeEach(() => {
    pinServiceSpy = jasmine.createSpyObj<PinService>(
      'PinService',
      ['hasPin', 'promptPin', 'deletePin', 'markStepUpEnabled', 'clearStepUpMarker']
    );
    routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']);
  });

  // ── AC-3: Distinct checking state ────────────────────────────────────────

  it('starts with checkingStepUp=true, isAuthenticated=false (neutral loading state)', () => {
    // Construct component but do NOT call ngOnInit — inspect the initial state.
    pinServiceSpy.hasPin.and.returnValue(new Promise(() => {})); // never resolves
    pinServiceSpy.promptPin.and.returnValue(Promise.resolve(true));

    TestBed.configureTestingModule({
      imports: [SecuritySettingsPage, HttpClientTestingModule],
      providers: [
        { provide: Router, useValue: routerSpy },
        { provide: NavController, useValue: { navigateBack: jasmine.createSpy() } },
        { provide: AppLockService, useValue: { isEnabled: () => false } },
        { provide: PinService, useValue: pinServiceSpy },
        { provide: ToastService, useValue: { showToast: jasmine.createSpy() } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SecuritySettingsPage);
    component = fixture.componentInstance;

    // Before ngOnInit runs, checkingStepUp should be true and isAuthenticated false.
    expect(component.checkingStepUp).toBeTrue();
    expect(component.isAuthenticated).toBeFalse();
  });

  // ── AC-3: No-PIN path — never sees verify overlay ────────────────────────

  it('no-PIN user: checkingStepUp goes false, isAuthenticated goes true, promptPin never called', fakeAsync(() => {
    createComponent(false); // hasPin → false
    fixture.detectChanges(); // triggers ngOnInit

    tick(); // flush all microtasks / promises

    expect(component.checkingStepUp).toBeFalse();
    expect(component.isAuthenticated).toBeTrue();
    expect(pinServiceSpy.promptPin).not.toHaveBeenCalled();
    // Verify overlay must NOT show (sensitiveAuthEnabled=false, isAuthenticated=true)
    expect(component.sensitiveAuthEnabled).toBeFalse();
  }));

  // ── AC-3: PIN path — verify overlay shown, settings NOT shown until verified ─

  it('step-up user: checkingStepUp goes false, promptPin called, isAuthenticated true after success', fakeAsync(() => {
    createComponent(true, true); // hasPin → true, promptPin → success
    fixture.detectChanges();

    tick();

    expect(component.checkingStepUp).toBeFalse();
    expect(pinServiceSpy.promptPin).toHaveBeenCalledWith('verify');
    expect(component.isAuthenticated).toBeTrue();
  }));

  it('step-up user: PIN cancel → router navigates back, isAuthenticated stays false', fakeAsync(() => {
    createComponent(true, false); // hasPin → true, promptPin → cancelled
    fixture.detectChanges();

    tick();

    expect(routerSpy.navigate).toHaveBeenCalledWith(['/tabs/tab3']);
    expect(component.isAuthenticated).toBeFalse();
  }));

  // ── AC-3: content leak prevention — template *ngIf gating ────────────────

  it('while checkingStepUp=true, neither the overlay card nor settings content is rendered', () => {
    pinServiceSpy.hasPin.and.returnValue(new Promise(() => {})); // never resolves
    pinServiceSpy.promptPin.and.returnValue(Promise.resolve(true));

    TestBed.configureTestingModule({
      imports: [SecuritySettingsPage, HttpClientTestingModule],
      providers: [
        { provide: Router, useValue: routerSpy },
        { provide: NavController, useValue: { navigateBack: jasmine.createSpy() } },
        { provide: AppLockService, useValue: { isEnabled: () => false } },
        { provide: PinService, useValue: pinServiceSpy },
        { provide: ToastService, useValue: { showToast: jasmine.createSpy() } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SecuritySettingsPage);
    component = fixture.componentInstance;
    spyOn(component, 'loadSessions');
    spyOn(component, 'syncAlpacaAccountNumber');

    // Manually freeze in checking state
    component.checkingStepUp = true;
    component.isAuthenticated = false;
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    // auth-overlay must NOT be visible (gated by !checkingStepUp && !isAuthenticated)
    expect(el.querySelector('.auth-overlay')).toBeNull();
    // settings content must NOT be visible (gated by isAuthenticated)
    expect(el.querySelector('.content-inner')).toBeNull();
    // neutral checking-overlay should be present
    expect(el.querySelector('.checking-overlay')).not.toBeNull();
  });

  it('after no-PIN resolution, auth-overlay is absent and content-inner is present', fakeAsync(() => {
    createComponent(false);
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.auth-overlay')).toBeNull();
    expect(el.querySelector('.checking-overlay')).toBeNull();
    expect(el.querySelector('.content-inner')).not.toBeNull();
  }));

  // ── AC-4: Post-gate data loading ─────────────────────────────────────────

  it('no-PIN user: loadSessions() and syncAlpacaAccountNumber() are called after gate passes', fakeAsync(() => {
    createComponent(false);
    fixture.detectChanges();
    tick();

    expect(loadSessionsSpy).toHaveBeenCalledTimes(1);
    expect(syncAlpacaSpy).toHaveBeenCalledTimes(1);
  }));

  it('step-up user after successful PIN: loadSessions() and syncAlpacaAccountNumber() are called', fakeAsync(() => {
    createComponent(true, true);
    fixture.detectChanges();
    tick();

    expect(loadSessionsSpy).toHaveBeenCalledTimes(1);
    expect(syncAlpacaSpy).toHaveBeenCalledTimes(1);
  }));

  it('step-up user after PIN cancel: loadSessions() and syncAlpacaAccountNumber() are NOT called', fakeAsync(() => {
    createComponent(true, false);
    fixture.detectChanges();
    tick();

    expect(loadSessionsSpy).not.toHaveBeenCalled();
    expect(syncAlpacaSpy).not.toHaveBeenCalled();
  }));

  // ── AC-5: No dead code ────────────────────────────────────────────────────

  it('authenticateUser() method does NOT exist on the component', () => {
    createComponent(false);
    fixture.detectChanges();
    expect((component as any).authenticateUser).toBeUndefined();
  });

  it('isAuthenticating property does NOT exist on the component', () => {
    createComponent(false);
    fixture.detectChanges();
    expect((component as any).isAuthenticating).toBeUndefined();
  });
});

// =================================================================================================
// Analytics call sites.
//
// Built by constructing the page directly with stubs rather than through TestBed: these assertions
// are about which payload leaves which handler, and the three-state template gating above already
// covers the rendering. Every expectation asserts the whole props object, so adding, renaming or
// re-valuing a property fails the spec rather than silently changing the data.
// =================================================================================================
describe('SecuritySettingsPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let consent: jasmine.SpyObj<AnalyticsConsentService>;
  let appLock: jasmine.SpyObj<AppLockService>;
  let pin: jasmine.SpyObj<PinService>;
  let toast: jasmine.SpyObj<ToastService>;
  let page: SecuritySettingsPage;

  const pinOk: PinResponse = { success: true, message: '', lockedOut: false, lockoutDurationSeconds: 0 };

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    consent = jasmine.createSpyObj<AnalyticsConsentService>('AnalyticsConsentService', [
      'setOptOut',
      'isOptedOut',
    ]);
    appLock = jasmine.createSpyObj<AppLockService>('AppLockService', ['isEnabled', 'setEnabled']);
    pin = jasmine.createSpyObj<PinService>('PinService', [
      'hasPin',
      'promptPin',
      'deletePin',
      'markStepUpEnabled',
      'clearStepUpMarker',
    ]);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);

    page = new SecuritySettingsPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']),
      appLock,
      pin,
      toast,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      consent,
      analytics,
    );
  });

  // ── app_lock ───────────────────────────────────────────────────────────────

  it('app lock on: reports the state the switch now holds', () => {
    page.appLockEnabled = true;

    page.toggleAppLock();

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'app_lock', enabled: true, value: null, outcome: 'applied' },
    });
  });

  it('app lock off: same event with enabled false', () => {
    page.appLockEnabled = false;

    page.toggleAppLock();

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'app_lock', enabled: false, value: null, outcome: 'applied' },
    });
  });

  // ── step_up_auth: three outcomes ───────────────────────────────────────────

  it('step-up on, PIN created: applied', async () => {
    page.sensitiveAuthEnabled = true;
    pin.promptPin.and.resolveTo(true);

    await page.toggleSensitiveAuth();

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'step_up_auth', enabled: true, value: null, outcome: 'applied' },
    });
  });

  it('step-up on, PIN prompt backed out of: cancelled, and enabled still reports what was wanted', async () => {
    page.sensitiveAuthEnabled = true;
    pin.promptPin.and.resolveTo(false);

    await page.toggleSensitiveAuth();

    // The switch has reverted, but `enabled` is the state the user was reaching for: a cancelled
    // event with enabled:false would be indistinguishable from turning the setting off.
    expect(page.sensitiveAuthEnabled).toBeFalse();
    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'step_up_auth', enabled: true, value: null, outcome: 'cancelled' },
    });
  });

  it('step-up off, PIN deleted: applied', async () => {
    page.sensitiveAuthEnabled = false;
    pin.deletePin.and.resolveTo(pinOk);

    await page.toggleSensitiveAuth();

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'step_up_auth', enabled: false, value: null, outcome: 'applied' },
    });
  });

  it('step-up off, delete fails: failed, and the rejection still propagates', async () => {
    page.sensitiveAuthEnabled = false;
    pin.deletePin.and.rejectWith(new HttpErrorResponse({ status: 500 }));

    await expectAsync(page.toggleSensitiveAuth()).toBeRejected();

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'step_up_auth', enabled: false, value: null, outcome: 'failed' },
    });
    // Behaviour is unchanged: the toast was skipped before this event existed and still is.
    expect(toast.showToast).not.toHaveBeenCalled();
  });

  // ── analytics_consent: the ordering trap ───────────────────────────────────

  it('opting OUT dispatches before the write, because applied consent would suppress it', () => {
    const order: string[] = [];
    analytics.track.and.callFake(() => order.push('track'));
    consent.setOptOut.and.callFake(() => {
      order.push('setOptOut');
      return of(true);
    });
    page.usageDataEnabled = false; // the user has just switched it off

    page.toggleUsageData();

    expect(order).toEqual(['track', 'setOptOut']);
    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'analytics_consent', enabled: false, value: null, outcome: 'applied' },
    });
  });

  it('opting IN dispatches after the write, because nothing may be sent until consent is applied', () => {
    const order: string[] = [];
    analytics.track.and.callFake(() => order.push('track'));
    // The real service applies consent inside its own tap, before this value is handed over.
    consent.setOptOut.and.callFake(() => {
      order.push('setOptOut');
      return of(false);
    });
    page.usageDataEnabled = true; // the user has just switched it on

    page.toggleUsageData();

    expect(order).toEqual(['setOptOut', 'track']);
    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'analytics_consent', enabled: true, value: null, outcome: 'applied' },
    });
  });

  it('opting IN reports what the server stored when the two disagree', () => {
    consent.setOptOut.and.returnValue(of(true));
    page.usageDataEnabled = true;

    page.toggleUsageData();

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'analytics_consent', enabled: false, value: null, outcome: 'applied' },
    });
  });

  it('a failed consent write reports failed plus the error the user saw', () => {
    consent.setOptOut.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    consent.isOptedOut.and.returnValue(false);
    page.usageDataEnabled = true;

    page.toggleUsageData();

    expect(analytics.track).toHaveBeenCalledOnceWith({
      name: 'setting_changed',
      props: { setting: 'analytics_consent', enabled: true, value: null, outcome: 'failed' },
    });
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'settings', 'toast', 503, false);
  });

  it('a consent write that never reached the server is network, not save_failed', () => {
    consent.setOptOut.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    consent.isOptedOut.and.returnValue(true);
    page.usageDataEnabled = false;

    page.toggleUsageData();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'settings', 'toast', null, false);
  });
});
