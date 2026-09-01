import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject, BehaviorSubject } from 'rxjs';
import { AppComponent } from './app.component';
import { AppLockService } from './services/app-lock.service';
import { AuthService, UserProgress } from './services/auth.service';
import { PasskeyService } from './services/passkey.service';
import { PushNotificationService } from './services/push-notification.service';
import { PortfolioStoreService } from './services/portfolio-store.service';
import { FirstTimeTourService } from './services/first-time-tour.service';
import { JwtTokenUtils } from './utils/jwt-token.utils';

// ---------------------------------------------------------------------------
// Lightweight stubs — only the surface AppComponent uses
// ---------------------------------------------------------------------------

function makeAuthServiceStub() {
  const authSucceededSubject = new Subject<void>();
  const resumeNoLockSubject = new Subject<void>();
  return {
    isLoggedIn$: new BehaviorSubject<boolean>(false).asObservable(),
    userProgress$: new BehaviorSubject<UserProgress | null>(null).asObservable(),
    reAuthInProgress$: new BehaviorSubject<boolean>(false).asObservable(),
    authSucceeded$: authSucceededSubject.asObservable(),
    _authSucceededSubject: authSucceededSubject,
    getCurrentProgress: jasmine.createSpy('getCurrentProgress').and.returnValue(null),
    getUnifiedProgress: jasmine.createSpy('getUnifiedProgress').and.returnValue({
      getStartedCompleted: false, surveyInitialCompleted: false, fiPlanResultsCompleted: false,
      authFinalizeCompleted: false, kycVerificationCompleted: false, linkPlaidCompleted: false,
      investmentScheduleCompleted: false, investmentConfirmationCompleted: false,
    } as UserProgress),
    loadUserProgress: jasmine.createSpy('loadUserProgress'),
    logout: jasmine.createSpy('logout'),
    isAuthenticated: jasmine.createSpy('isAuthenticated').and.returnValue(false),
  };
}

function makeAppLockStub(enabled = false, locked = false) {
  return {
    isEnabled: jasmine.createSpy('isEnabled').and.returnValue(enabled),
    isCurrentlyLocked: jasmine.createSpy('isCurrentlyLocked').and.returnValue(locked),
    resumeNoLock$: new Subject<void>().asObservable(),
    isLocked$: new BehaviorSubject<boolean>(locked).asObservable(),
    hideAllCovers: jasmine.createSpy('hideAllCovers'),
  };
}

// Minimal stubs for injected services AppComponent doesn't exercise in these tests
const passkeyServiceStub = {};
const pushNotificationServiceStub = {
  registerDeviceToken: jasmine.createSpy('registerDeviceToken').and.returnValue(Promise.resolve()),
};
const portfolioStoreStub = {
  prime: jasmine.createSpy('prime'),
};
const firstTimeTourStub = {
  step$: new BehaviorSubject(null).asObservable(),
  suspended$: new BehaviorSubject(false).asObservable(),
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function seedToken(payload: object): void {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify(payload));
  const token = `${header}.${body}.fake-signature`;
  localStorage.setItem('jwtToken', token);
  localStorage.setItem('jwtExpiration', String(Math.floor(Date.now() / 1000) + 3600));
}

function clearToken(): void {
  localStorage.removeItem('jwtToken');
  localStorage.removeItem('jwtExpiration');
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('AppComponent — tryOptimisticNav (cold-start + post-reauth)', () => {
  let router: Router;
  let authStub: ReturnType<typeof makeAuthServiceStub>;
  let lockStub: ReturnType<typeof makeAppLockStub>;

  async function setup(lockEnabled = false, lockLocked = false) {
    authStub = makeAuthServiceStub();
    lockStub = makeAppLockStub(lockEnabled, lockLocked);

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authStub },
        { provide: AppLockService, useValue: lockStub },
        { provide: PasskeyService, useValue: passkeyServiceStub },
        { provide: PushNotificationService, useValue: pushNotificationServiceStub },
        { provide: PortfolioStoreService, useValue: portfolioStoreStub },
        { provide: FirstTimeTourService, useValue: firstTimeTourStub },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.returnValue(Promise.resolve(true));
  }

  afterEach(() => clearToken());

  // (i) authed + App Lock OFF + ns='complete' from '/' → navigates to /tabs/tab1
  it('(i) navigates to /tabs/tab1 when ns="complete", lock off, on entry route', async () => {
    seedToken({ sub: 'u@fred.com', exp: 9999999999, ns: 'complete' });
    await setup(false, false);

    // Clear any navigation calls from setup, then create the component.
    // tryOptimisticNav reads router.url; the default TestBed router.url is '/'.
    (router.navigateByUrl as jasmine.Spy).calls.reset();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges(); // triggers ngOnInit → tryOptimisticNav

    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', jasmine.objectContaining({ replaceUrl: true }));
  });

  // (ii) ns='complete' but already on /my-profile → NO nav to tab1
  it('(ii) does NOT navigate when already on /my-profile (preservation rule)', async () => {
    seedToken({ sub: 'u@fred.com', exp: 9999999999, ns: 'complete' });
    await setup(false, false);

    // Patch router.url to simulate being on /my-profile
    Object.defineProperty(router, 'url', { get: () => '/my-profile', configurable: true });
    (router.navigateByUrl as jasmine.Spy).calls.reset();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/tabs/tab1', jasmine.anything());
  });

  // (iii) ns in-progress (e.g. kyc-verification) → NO early nav
  it('(iii) does NOT navigate for in-progress ns slug (v1 acts only on complete)', async () => {
    seedToken({ sub: 'u@fred.com', exp: 9999999999, ns: 'kyc-verification' });
    await setup(false, false);

    (router.navigateByUrl as jasmine.Spy).calls.reset();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/tabs/tab1', jasmine.anything());
  });

  // (iv) appLockService.isEnabled()===true → no early nav
  it('(iv) does NOT navigate when App Lock is enabled', async () => {
    seedToken({ sub: 'u@fred.com', exp: 9999999999, ns: 'complete' });
    await setup(true, false); // lock ENABLED

    (router.navigateByUrl as jasmine.Spy).calls.reset();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/tabs/tab1', jasmine.anything());
  });

  // (v) ns=null → nothing
  it('(v) does nothing when ns claim is absent', async () => {
    seedToken({ sub: 'u@fred.com', exp: 9999999999 }); // no ns
    await setup(false, false);

    (router.navigateByUrl as jasmine.Spy).calls.reset();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/tabs/tab1', jasmine.anything());
  });

  // (vi) post-reauth path + ns='complete' from entry route → optimistic nav to tab1
  it('(vi) navigates to /tabs/tab1 on post-reauth authSucceeded$ emission with ns="complete"', async () => {
    seedToken({ sub: 'u@fred.com', exp: 9999999999, ns: 'complete' });
    await setup(false, false);

    (router.navigateByUrl as jasmine.Spy).calls.reset();

    // Create the component but spy BEFORE detectChanges so ngOnInit cold-start
    // nav doesn't count — we want to isolate the post-reauth path.
    // Reset again after detectChanges to isolate the authSucceeded$ emission.
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    (router.navigateByUrl as jasmine.Spy).calls.reset();

    // Simulate Face ID / passkey auth completion
    authStub._authSucceededSubject.next();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', jasmine.objectContaining({ replaceUrl: true }));
  });

  // (vii) REAUTH RACE (FRED-205): on cold-start reauth, iOS resume fires
  // checkSurveyStatusAndNavigate() while userProgress$ is still null (loadUserProgress in flight).
  // A fully-onboarded user (ns="complete") must be routed to /tabs/tab1 — whose cover-hide is
  // data-gated — rather than having the cover lifted on the current non-tab1 route (which would
  // reveal tab1's "Loading your portfolio…" spinner).
  it('(vii) checkSurveyStatusAndNavigate with null progress + ns="complete" drives the tab1 data-gated nav', async () => {
    seedToken({ sub: 'u@fred.com', exp: 9999999999, ns: 'complete' });
    await setup(false, false);

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    (router.navigateByUrl as jasmine.Spy).calls.reset();

    // Post-reauth resume: userProgress$ was reset to null and loadUserProgress() is in flight.
    authStub.getCurrentProgress.and.returnValue(null);
    fixture.componentInstance.checkSurveyStatusAndNavigate();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', jasmine.objectContaining({ replaceUrl: true }));
  });
});

// ---------------------------------------------------------------------------
// Baseline smoke test (keep original)
// ---------------------------------------------------------------------------

describe('AppComponent', () => {
  it('should create the app', async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: makeAuthServiceStub() },
        { provide: AppLockService, useValue: makeAppLockStub() },
        { provide: PasskeyService, useValue: passkeyServiceStub },
        { provide: PushNotificationService, useValue: pushNotificationServiceStub },
        { provide: PortfolioStoreService, useValue: portfolioStoreStub },
        { provide: FirstTimeTourService, useValue: firstTimeTourStub },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });
});
