import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { BehaviorSubject, Subject } from 'rxjs';
import { AppComponent } from './app.component';
import { AppLockService } from './services/app-lock.service';
import { AuthService, UserProgress } from './services/auth.service';
import { PasskeyService } from './services/passkey.service';
import { PushNotificationService } from './services/push-notification.service';
import { PortfolioStoreService } from './services/portfolio-store.service';
import { FirstTimeTourService } from './services/first-time-tour.service';
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY, PLAID_OAUTH_RETURN_HREF_KEY } from './utils/plaid-link-session';

/** The bank sends the user back to the one redirect URI registered for the environment (R9). */
const BOOT_HREF = 'https://local.fredvested.com/link-bank?oauth_state_id=abc123';

const ALL_DONE: UserProgress = {
  getStartedCompleted: true, surveyInitialCompleted: true, fiPlanResultsCompleted: true,
  authFinalizeCompleted: true, kycVerificationCompleted: true, linkPlaidCompleted: true,
  investmentScheduleCompleted: true, investmentConfirmationCompleted: true,
};
const LINKING_NEXT: UserProgress = {
  ...ALL_DONE, linkPlaidCompleted: false, investmentScheduleCompleted: false, investmentConfirmationCompleted: false,
};

function authStub() {
  return {
    isLoggedIn$: new BehaviorSubject<boolean>(false).asObservable(),
    userProgress$: new BehaviorSubject<UserProgress | null>(null).asObservable(),
    reAuthInProgress$: new BehaviorSubject<boolean>(false).asObservable(),
    authSucceeded$: new Subject<void>().asObservable(),
    getCurrentProgress: jasmine.createSpy('getCurrentProgress').and.returnValue(null),
    getUnifiedProgress: jasmine.createSpy('getUnifiedProgress').and.returnValue({ ...ALL_DONE, getStartedCompleted: false }),
    loadUserProgress: jasmine.createSpy('loadUserProgress'),
    logout: jasmine.createSpy('logout'),
    isAuthenticated: jasmine.createSpy('isAuthenticated').and.returnValue(false),
  };
}

function lockStub() {
  return {
    isEnabled: jasmine.createSpy('isEnabled').and.returnValue(false),
    isCurrentlyLocked: jasmine.createSpy('isCurrentlyLocked').and.returnValue(false),
    resumeNoLock$: new Subject<void>().asObservable(),
    isLocked$: new BehaviorSubject<boolean>(false).asObservable(),
    hideAllCovers: jasmine.createSpy('hideAllCovers'),
  };
}

function seedToken(ns: string): void {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify({ sub: 'u@fred.com', exp: 9999999999, ns }));
  localStorage.setItem('jwtToken', `${header}.${body}.fake-signature`);
  localStorage.setItem('jwtExpiration', String(Math.floor(Date.now() / 1000) + 3600));
}

function clearToken(): void {
  localStorage.removeItem('jwtToken');
  localStorage.removeItem('jwtExpiration');
}

function storeSession(marker: string): void {
  sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
  sessionStorage.setItem(PLAID_LINK_RETURN_KEY, marker);
}

describe('AppComponent: a Plaid OAuth return at cold start', () => {
  let router: Router;

  /** Boots AppComponent as if the browser had opened `href`; ngOnInit runs inside detectChanges. */
  async function boot(href: string): Promise<AppComponent> {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authStub() },
        { provide: AppLockService, useValue: lockStub() },
        { provide: PasskeyService, useValue: {} },
        { provide: PushNotificationService, useValue: { registerDeviceToken: jasmine.createSpy('registerDeviceToken').and.returnValue(Promise.resolve()) } },
        { provide: PortfolioStoreService, useValue: { prime: jasmine.createSpy('prime') } },
        {
          provide: FirstTimeTourService,
          useValue: {
            step$: new BehaviorSubject(null).asObservable(),
            suspended$: new BehaviorSubject(false).asObservable(),
            transit$: new BehaviorSubject(false).asObservable(),
          },
        },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.returnValue(Promise.resolve(true));
    spyOn(router, 'navigate').and.returnValue(Promise.resolve(true));
    spyOn(AppComponent.prototype as any, 'bootHref').and.returnValue(href);
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  afterEach(() => {
    clearToken();
    sessionStorage.clear();
  });

  it('stands optimistic navigation aside and parks the href Plaid redirected to', async () => {
    seedToken('complete');
    storeSession('change-bank');

    await boot(BOOT_HREF);

    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/tabs/tab1', jasmine.anything());
    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBe(BOOT_HREF);
  });

  it('routes a change-bank return to /change-bank-account with the query string, exactly once', async () => {
    seedToken('complete');
    storeSession('change-bank');
    const app = await boot(BOOT_HREF);

    app.navigateBasedOnProgress(ALL_DONE);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/change-bank-account?oauth_state_id=abc123', { replaceUrl: true });

    (router.navigateByUrl as jasmine.Spy).calls.reset();
    app.navigateBasedOnProgress(ALL_DONE);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', { replaceUrl: true });
  });

  it('keeps the query string on /link-bank for an onboarding return', async () => {
    seedToken('link-bank');
    storeSession('onboarding');
    const app = await boot(BOOT_HREF);

    app.navigateBasedOnProgress(LINKING_NEXT);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/link-bank?oauth_state_id=abc123', { replaceUrl: true });
  });

  it('drops a change-bank return the progress does not allow and routes as before', async () => {
    seedToken('link-bank');
    storeSession('change-bank');
    const app = await boot(BOOT_HREF);

    app.navigateBasedOnProgress(LINKING_NEXT);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/link-bank', { replaceUrl: true });
    expect(router.navigateByUrl).not.toHaveBeenCalledWith('/change-bank-account?oauth_state_id=abc123', jasmine.anything());
  });

  it('holds the return while no valid JWT exists, so it survives a re-authentication', async () => {
    storeSession('change-bank');
    const app = await boot(BOOT_HREF);

    app.navigateBasedOnProgress(ALL_DONE);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', { replaceUrl: true });

    (router.navigateByUrl as jasmine.Spy).calls.reset();
    seedToken('complete');
    app.navigateBasedOnProgress(ALL_DONE);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/change-bank-account?oauth_state_id=abc123', { replaceUrl: true });
  });

  it('a cold start with oauth_state_id but no stored Link session is not a return', async () => {
    seedToken('complete');

    await boot(BOOT_HREF);

    expect(router.navigateByUrl).toHaveBeenCalledWith('/tabs/tab1', jasmine.objectContaining({ replaceUrl: true }));
    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBeNull();
  });
});
