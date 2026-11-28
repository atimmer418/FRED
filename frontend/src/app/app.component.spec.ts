import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
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
import { LastScreenUtils } from './utils/last-screen.utils';
import { AccountClosedService } from './services/account-closed.service';

// ---------------------------------------------------------------------------
// Lightweight stubs — only the surface AppComponent uses
// ---------------------------------------------------------------------------

function makeAuthServiceStub(loggedIn = false, progress: UserProgress | null = null) {
  const authSucceededSubject = new Subject<void>();
  return {
    isLoggedIn$: new BehaviorSubject<boolean>(loggedIn).asObservable(),
    userProgress$: new BehaviorSubject<UserProgress | null>(progress).asObservable(),
    reAuthInProgress$: new BehaviorSubject<boolean>(false).asObservable(),
    authSucceeded$: authSucceededSubject.asObservable(),
    _authSucceededSubject: authSucceededSubject,
    getCurrentProgress: jasmine.createSpy('getCurrentProgress').and.returnValue(progress),
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
  transit$: new BehaviorSubject(false).asObservable(),
  hasSeenTour: jasmine.createSpy('hasSeenTour').and.returnValue(true),
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

/** Let an in-flight landing (a chain of resolved promises) run to completion. */
function settle(ms = 0): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
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
    await settle(); // the cold-start landing is single-flight: let it finish first
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
    await settle(); // the cold-start landing is single-flight: let it finish first
    (router.navigateByUrl as jasmine.Spy).calls.reset();

    // Post-reauth resume: userProgress$ was reset to null and loadUserProgress() is in flight.
    authStub.getCurrentProgress.and.returnValue(null);
    fixture.componentInstance.checkSurveyStatusAndNavigate();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', jasmine.objectContaining({ replaceUrl: true }));
  });
});

// ---------------------------------------------------------------------------
// Reopen where you left off: the onboarded landing on a cold start
// ---------------------------------------------------------------------------

describe('AppComponent — onboarded landing restores the last screen on a cold start', () => {
  const USER = 'restore-user';
  const HOUR = 60 * 60 * 1000;
  const COMPLETE: UserProgress = {
    getStartedCompleted: true, surveyInitialCompleted: true, fiPlanResultsCompleted: true,
    authFinalizeCompleted: true, kycVerificationCompleted: true, linkPlaidCompleted: true,
    investmentScheduleCompleted: true, investmentConfirmationCompleted: true,
    selectedTier: 'core',
  } as UserProgress;

  let router: Router;
  let navCtrl: NavController;
  let navigateByUrl: jasmine.Spy;
  let navigateForward: jasmine.Spy;
  let lockStub: ReturnType<typeof makeAppLockStub>;

  function seedRecord(url: string, ageMs = HOUR): void {
    localStorage.setItem('userId', USER);
    localStorage.setItem(
      LastScreenUtils.storageKey(USER),
      JSON.stringify({ userId: USER, url, savedAt: Date.now() - ageMs }),
    );
  }

  async function setup(authStub = makeAuthServiceStub()) {
    lockStub = makeAppLockStub(false, false);
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
    navCtrl = TestBed.inject(NavController);
    navigateByUrl = spyOn(router, 'navigateByUrl').and.returnValue(Promise.resolve(true));
    navigateForward = spyOn(navCtrl, 'navigateForward').and.returnValue(Promise.resolve(true));
  }

  /** Boot the component the way a cold start with a still-valid, fully onboarded JWT does. */
  function boot() {
    const fixture = TestBed.createComponent(AppComponent);
    const component = fixture.componentInstance as any;
    // The cover gates wait on fonts and layout; neither exists in the test page.
    spyOn(component, 'preloadAssetsForRoute').and.returnValue(Promise.resolve());
    spyOn(component, 'waitForRoutePainted').and.returnValue(Promise.resolve());
    spyOn(component, 'waitForTab1DataPainted').and.returnValue(Promise.resolve());
    fixture.detectChanges();
    return component;
  }

  beforeEach(() => {
    firstTimeTourStub.hasSeenTour.and.returnValue(true);
    seedToken({ sub: 'u@fred.com', exp: 9999999999, ns: 'complete' });
  });

  afterEach(() => {
    clearToken();
    localStorage.removeItem(LastScreenUtils.storageKey(USER));
    localStorage.removeItem('userId');
  });

  it('lands on the tab the user left', async () => {
    seedRecord('/tabs/tab2');
    await setup();
    boot();
    await settle();

    expect(navigateByUrl.calls.allArgs().map(args => args[0])).toEqual(['/tabs/tab2']);
    expect(navigateForward).not.toHaveBeenCalled();
  });

  it('rebuilds the Profile tab and My Profile under a restored goal, in order', async () => {
    seedRecord('/goals/7');
    await setup();
    const order: string[] = [];
    navigateByUrl.and.callFake((url: any) => { order.push(url); return Promise.resolve(true); });
    navigateForward.and.callFake((url: any) => { order.push(url); return Promise.resolve(true); });
    boot();
    await settle();

    expect(order).toEqual(['/tabs/tab3', '/my-profile', '/goals/7']);
    expect(navigateByUrl).toHaveBeenCalledWith('/tabs/tab3', jasmine.objectContaining({ replaceUrl: true }));
    // A push with replaceUrl would make Ionic drop the page underneath.
    for (const args of navigateForward.calls.allArgs()) {
      expect(args[1]).toEqual({ animated: false });
    }
  });

  it('keeps the launch cover up until the whole stack is built', async () => {
    seedRecord('/my-profile');
    await setup();
    let finishPush!: (ok: boolean) => void;
    navigateForward.and.returnValue(new Promise<boolean>(resolve => { finishPush = resolve; }));
    const component = boot();
    await settle();

    // The tab is up and My Profile is still being pushed. Anything asking to lift the cover
    // now (the DB result arriving, a resume) must be ignored.
    component.hideCoversWhenReady('/tabs/tab3');
    await settle();
    expect(lockStub.hideAllCovers).not.toHaveBeenCalled();

    finishPush(true);
    await settle();
    expect(component.waitForRoutePainted).toHaveBeenCalledWith(true);
    expect(lockStub.hideAllCovers).toHaveBeenCalled();
  });

  // With App Lock on, a resume can start a cover wait on '/' before the landing exists. When
  // that wait resolves mid-landing it must not lift the cover onto the tab.
  it('ignores a cover wait that started before the landing did', async () => {
    seedRecord('/my-profile');
    await setup();
    let finishPush!: (ok: boolean) => void;
    navigateForward.and.returnValue(new Promise<boolean>(resolve => { finishPush = resolve; }));
    const fixture = TestBed.createComponent(AppComponent);
    const component = fixture.componentInstance as any;
    let finishStaleWait!: () => void;
    spyOn(component, 'preloadAssetsForRoute').and.returnValues(
      new Promise<void>(resolve => { finishStaleWait = resolve; }), Promise.resolve());
    spyOn(component, 'waitForRoutePainted').and.returnValue(Promise.resolve());
    spyOn(component, 'waitForTab1DataPainted').and.returnValue(Promise.resolve());

    component.hideCoversWhenReady('/'); // before ngOnInit starts the landing
    fixture.detectChanges();
    await settle();
    finishStaleWait();
    await settle();
    expect(lockStub.hideAllCovers).not.toHaveBeenCalled();

    finishPush(true);
    await settle();
    expect(lockStub.hideAllCovers).toHaveBeenCalledTimes(1);
  });

  it('stops at the page it reached when a push fails, and still lifts the cover', async () => {
    seedRecord('/goals/7');
    await setup();
    navigateForward.and.callFake((url: any) =>
      url === '/my-profile' ? Promise.resolve(true) : Promise.reject(new Error('chunk failed')));
    boot();
    await settle();

    expect(navigateForward.calls.allArgs().map(args => args[0])).toEqual(['/my-profile', '/goals/7']);
    expect(lockStub.hideAllCovers).toHaveBeenCalled();
  });

  it('joins a second landing request instead of navigating twice', async () => {
    seedRecord('/tabs/tab2');
    await setup();
    const component = boot();
    component.checkSurveyStatusAndNavigate(); // progress still null: re-enters the optimistic nav
    await settle();

    expect(navigateByUrl).toHaveBeenCalledTimes(1);
  });

  it('lands on tab 1 when the record is older than 4 hours', async () => {
    seedRecord('/tabs/tab2', 4 * HOUR + 1000);
    await setup();
    boot();
    await settle();

    expect(navigateByUrl.calls.allArgs().map(args => args[0])).toEqual(['/tabs/tab1']);
  });

  it('lands on tab 1 until the first-time tour has been seen', async () => {
    seedRecord('/tabs/tab2');
    firstTimeTourStub.hasSeenTour.and.returnValue(false);
    await setup();
    boot();
    await settle();

    expect(navigateByUrl.calls.allArgs().map(args => args[0])).toEqual(['/tabs/tab1']);
  });

  it('lands on tab 1 when this is not the first navigation of the launch', async () => {
    seedRecord('/goals/7');
    await setup();
    router.navigated = true; // e.g. finishing onboarding, or signing in again
    boot();
    await settle();

    expect(navigateByUrl.calls.allArgs().map(args => args[0])).toEqual(['/tabs/tab1']);
    expect(navigateForward).not.toHaveBeenCalled();
  });

  it('stops when another navigation takes over the first step', async () => {
    seedRecord('/goals/7');
    await setup();
    navigateByUrl.and.returnValue(Promise.resolve(false)); // superseded by an onboarding redirect
    boot();
    await settle();

    expect(navigateByUrl).toHaveBeenCalledTimes(1);
    expect(navigateForward).not.toHaveBeenCalled();
  });

  it('falls back to tab 1 when the restored tab fails to load', async () => {
    seedRecord('/tabs/tab2');
    await setup();
    navigateByUrl.and.callFake((url: any) =>
      url === '/tabs/tab2' ? Promise.reject(new Error('chunk failed')) : Promise.resolve(true));
    boot();
    await settle();

    expect(navigateByUrl.calls.allArgs().map(args => args[0])).toEqual(['/tabs/tab2', '/tabs/tab1']);
  });

  // The path taken after more than a few minutes away: no valid token at boot, so the landing
  // is decided when the DB progress arrives (after the passkey), not by the optimistic nav.
  it('restores on the DB-progress path too', async () => {
    clearToken();
    seedRecord('/tabs/chat');
    await setup(makeAuthServiceStub(true, { ...COMPLETE, _source: 'db' } as UserProgress));
    boot();
    await settle(350); // setupNavigationLogic debounces by 300ms

    expect(navigateByUrl.calls.allArgs().map(args => args[0])).toEqual(['/tabs/chat']);
  });

  it('lands on tab 1 when the subscription has expired', async () => {
    clearToken();
    seedRecord('/tabs/chat');
    const expired = { ...COMPLETE, selectedTier: undefined, _source: 'db' } as UserProgress;
    await setup(makeAuthServiceStub(true, expired));
    boot();
    await settle(350);

    expect(navigateByUrl.calls.allArgs().map(args => args[0])).toEqual(['/tabs/tab1']);
  });

  // Progress could not be loaded, so the backend is unreachable: only tab 1 has a snapshot and
  // an error card to show.
  it('lands on tab 1 when progress came from the offline fallback', async () => {
    clearToken();
    seedRecord('/goals/7');
    await setup(makeAuthServiceStub(true, { ...COMPLETE, _source: 'localStorageFallback' } as UserProgress));
    boot();
    await settle(350);

    expect(navigateByUrl.calls.allArgs().map(args => args[0])).toEqual(['/tabs/tab1']);
    expect(navigateForward).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Baseline smoke test (keep original)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// A closed account stays on the closed screen
// ---------------------------------------------------------------------------

describe('AppComponent: a closed account is not routed back into the app', () => {
  const ONBOARDED = {
    getStartedCompleted: true, surveyInitialCompleted: true, fiPlanResultsCompleted: true,
    authFinalizeCompleted: true, kycVerificationCompleted: true, linkPlaidCompleted: true,
    investmentScheduleCompleted: true, investmentConfirmationCompleted: true,
  } as UserProgress;

  let router: Router;
  let loggedIn: BehaviorSubject<boolean>;
  let progress: BehaviorSubject<UserProgress | null>;
  let authStub: ReturnType<typeof makeAuthServiceStub>;
  let navigate: jasmine.Spy;

  async function setup(startLoggedIn: boolean, startProgress: UserProgress | null, url: string) {
    loggedIn = new BehaviorSubject<boolean>(startLoggedIn);
    progress = new BehaviorSubject<UserProgress | null>(startProgress);
    authStub = makeAuthServiceStub(startLoggedIn, startProgress);
    authStub.isLoggedIn$ = loggedIn.asObservable();
    authStub.userProgress$ = progress.asObservable();

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authStub },
        { provide: AppLockService, useValue: makeAppLockStub() },
        { provide: PasskeyService, useValue: passkeyServiceStub },
        { provide: PushNotificationService, useValue: pushNotificationServiceStub },
        { provide: PortfolioStoreService, useValue: portfolioStoreStub },
        { provide: FirstTimeTourService, useValue: firstTimeTourStub },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    Object.defineProperty(router, 'url', { get: () => url, configurable: true });
    spyOn(router, 'navigateByUrl').and.returnValue(Promise.resolve(true));
    navigate = spyOn(router, 'navigate').and.returnValue(Promise.resolve(true));
  }

  afterEach(() => clearToken());

  it('signing out on the closed screen does not bounce the user to Get Started', async () => {
    await setup(false, null, '/account-closed');

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    await settle(400); // past the navigation stream's 300ms debounce

    expect(navigate).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('a signed-out user anywhere else still goes to Get Started (unchanged)', async () => {
    await setup(false, null, '/');

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    await settle(400);

    expect(navigate).toHaveBeenCalledWith(['/get-started'], jasmine.objectContaining({ replaceUrl: true }));
  });

  it('while the sign-out is still running, the signed-out rule does not get to Get Started first', async () => {
    // The page the user closed the account from; the closed screen has not been reached yet.
    await setup(false, null, '/sell-withdraw');
    spyOnProperty(TestBed.inject(AccountClosedService), 'isLeaving', 'get').and.returnValue(true);

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    await settle(400);

    expect(navigate).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('saved onboarding progress never pulls a signed-out user off the closed screen', async () => {
    await setup(false, null, '/account-closed');
    authStub.getUnifiedProgress.and.returnValue(ONBOARDED);

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    await settle(400);

    expect(navigate).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('a resume on the closed screen never sends the user to the tabs, even on stale progress', async () => {
    await setup(false, null, '/account-closed');
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    await settle(400);
    (router.navigateByUrl as jasmine.Spy).calls.reset();

    // The exact stale state the old close-account left behind: memory still says fully set up.
    authStub.getCurrentProgress.and.returnValue(ONBOARDED);
    fixture.componentInstance.checkSurveyStatusAndNavigate();
    await settle();

    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});

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
