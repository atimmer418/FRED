import { Component, OnInit, HostListener } from '@angular/core';
import { Router } from '@angular/router';
import { IonApp, IonRouterOutlet } from '@ionic/angular/standalone';
import { CommonModule } from '@angular/common';
import { Platform } from '@ionic/angular/standalone';
import { SplashScreen } from '@capacitor/splash-screen';
import { Keyboard } from '@capacitor/keyboard';
import { Capacitor } from '@capacitor/core';
import { register } from 'swiper/element/bundle';
import { AuthService, UserProgress } from './services/auth.service';
import { AppLockService } from './services/app-lock.service';
import { PasskeyService } from './services/passkey.service';
import { PushNotificationService } from './services/push-notification.service';
import { JwtTokenUtils } from './utils/jwt-token.utils';
import { PortfolioStoreService } from './services/portfolio-store.service';
import { FirstTimeTourComponent } from './components/first-time-tour/first-time-tour.component';
import { environment } from '../environments/environment';
import { captureDevToolsKeyFromUrl } from './utils/dev-tools-key.utils';
import { AnalyticsService } from './services/analytics/analytics.service';
import { combineLatest, debounceTime, distinctUntilChanged, filter, Subject, throttleTime } from 'rxjs';
import { addIcons } from 'ionicons';
import { lockClosedOutline, fingerPrintOutline } from 'ionicons/icons';

register();

const ROUTE_IMAGE_PRELOADS: Record<string, string[]> = {
  '/survey-initial': ['/assets/images/whenPiggybanksFly-3.jpg'],
};

const CRITICAL_FONT_SPECS = [
  '700 16px "Manrope"',
  '400 24px "Material Symbols Outlined"',
];

@Component({
  selector: 'app-root',
  templateUrl: 'app.component.html',
  styleUrls: ['app.component.scss'],
  standalone: true,
  imports: [IonApp, IonRouterOutlet, CommonModule, FirstTimeTourComponent],
})
export class AppComponent implements OnInit {

  private userActivity$ = new Subject<void>();

  constructor(
    private router: Router,
    private platform: Platform,
    private authService: AuthService,
    private appLockService: AppLockService,
    private passkeyService: PasskeyService,
    private pushNotificationService: PushNotificationService,
    private portfolioStore: PortfolioStoreService,
    private analytics: AnalyticsService
  ) {
    addIcons({ lockClosedOutline, fingerPrintOutline });

    // Setup activity tracker with throttling to avoid performance issues
    this.userActivity$.pipe(
      throttleTime(60000) // limit to once every 60 seconds
    ).subscribe(() => {
      this.appLockService.registerUserInteraction();
    });

    this.initializeApp();
  }

  @HostListener('window:touchstart')
  @HostListener('window:touchmove')
  @HostListener('window:scroll')
  @HostListener('window:click')
  @HostListener('window:keydown')
  onUserActivity() {
    this.userActivity$.next();
  }

  initializeApp() {
    this.platform.ready().then(async () => {
      if (this.platform.is('capacitor')) {
        try {
          await this.waitForCoverImageReady();
          await new Promise<void>(r => requestAnimationFrame(() => requestAnimationFrame(() => r())));
          await SplashScreen.hide({ fadeOutDuration: 0 });
        } catch (error) {
          console.error('[AppComponent] Error hiding splash screen:', error);
        }
      }
      this.pushNotificationService.registerDeviceToken().catch(() => {});
    });
  }

  private waitForCoverImageReady(): Promise<void> {
    return new Promise(resolve => {
      const cover = document.getElementById('app-resume-cover');
      const img = cover?.querySelector('img') as HTMLImageElement | null;
      if (!img) { resolve(); return; }
      if (img.complete && img.naturalWidth > 0) { resolve(); return; }

      let done = false;
      const finish = () => { if (!done) { done = true; resolve(); } };

      img.addEventListener('load', finish, { once: true });
      img.addEventListener('error', finish, { once: true });
      setTimeout(finish, 300);
    });
  }

  ngOnInit(): void {
    document.addEventListener('focusin', (event: FocusEvent) => {
      const target = event.target as HTMLElement;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT')) {
        setTimeout(() => {
          target.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }, 300);
      }
    });

    // WKWebView keyboard-displacement restore (native only). With
    // Keyboard.resize:'none', iOS scrolls the webview's NATIVE scroll view to
    // reveal a focused input inside fixed-position surfaces (bottom sheets).
    // If the input is torn down while the keyboard is up (saving a sheet
    // form), that offset is never restored — the page renders normally but
    // every tap hit-tests against displaced coordinates, so the whole screen
    // reads as frozen. Snap the scroll offset back on every keyboard hide.
    if (Capacitor.isNativePlatform()) {
      Keyboard.addListener('keyboardDidHide', () => {
        window.scrollTo(0, 0);
        const scroller = document.scrollingElement as HTMLElement | null;
        if (scroller) scroller.scrollTop = 0;
      });
    }

    // Skip navigation logic for testing (add ?testing=true to URL)
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('testing') === 'true') {
      return;
    }

    // One event per app start. Until consent is wired (Checkpoint C) the service is opted out by
    // default, so this is a no-op in practice; it exists to prove the client, proxy and project
    // line up once a key is configured. It carries no user data and cannot throw.
    this.analytics.trackAppOpened();

    // In dev mode, simulate login as an existing user BEFORE setting up navigation
    // This prevents the race condition where navigation fires before auth completes
    if (!environment.production) {
      // this.simulateUserLogin('facebook@gmail.com');
      // this.simulateUserLogin('', 'user_handle_123');
      // this.simulateUserLogin('test@test.com');
    }

    // DEV ONLY: ?devPage=/some-route logs in as the default dev user and then navigates
    // directly to the specified page (bypasses navigateBasedOnProgress).
    // e.g. http://localhost:8100?devPage=/survey-initial&devKey=<the backend's DEV_TOOLS_KEY>
    // The key is only needed once per device: it is remembered after the first load.
    if (!environment.production) {
      captureDevToolsKeyFromUrl(urlParams);
    }
    const devPage = !environment.production ? urlParams.get('devPage') : null;
    if (devPage) {
      this.simulateUserLoginToPage('facebook@gmail.com', devPage);
      return;
    }

    // COLD-START OPTIMISTIC NAV: if the stored JWT says this user is fully onboarded,
    // navigate to /tabs/tab1 immediately — before GET /user/progress returns.
    // GET /user/progress still runs and remains the sole routing authority (_source:'db').
    // This only paints faster; it never sets isLoggedIn, calls completeStep, or gates KYC/bank/money.
    this.tryOptimisticNav();

    // POST-REAUTH optimistic nav: same logic after a successful passkey / auth ceremony.
    // authSucceeded$ fires at the end of handleSuccessfulAuthentication, after the DB load
    // chain is already in flight. The DB result will reconcile (same target → no flash).
    this.authService.authSucceeded$.subscribe(() => {
      // Warm the portfolio fetch the INSTANT reauth succeeds — this is the earliest point a
      // valid JWT exists. Do NOT wait for tryOptimisticNav: it is blocked by isCurrentlyLocked()
      // during the ceremony, and the iOS app-state resume that would otherwise kick the fetch can
      // lag 1-2s after auth finishes. prime() is a token-gated read-only GET; single-flight means
      // the tab1 component later joins this same in-flight request (no double fetch). Gated on the
      // tab1-bound hint so a mid-onboarding reauth doesn't fetch a portfolio the user won't see.
      if (JwtTokenUtils.getNextStepHint() === 'complete') {
        this.portfolioStore.prime();
      }
      this.tryOptimisticNav();
    });

    // On background resume with no lock needed: re-check current route so
    // navigateBasedOnProgress() can hide the cover (same-route early return or navigation).
    this.appLockService.resumeNoLock$.subscribe(() => {
      requestAnimationFrame(() => this.checkSurveyStatusAndNavigate());
    });

    // Angular has booted, so hand cover-ownership over to it: cancel the pre-bootstrap
    // backstop timer in index.html so it can't strip the cover while Angular is in charge.
    (window as any)._cancelCoverHideTimer?.();

    // Last-resort un-trap. The cover is normally removed by navigateBasedOnProgress() once
    // progress loads — guaranteed within ~31s by loadUserProgress's timeout+retry+fallback
    // chain — so it rides the Lottie until the real screen is ready, never a blank flash.
    // This 40s net only fires if navigation somehow never happened despite progress having
    // emitted; it RE-DRIVES navigation (rather than blind-hiding) so we still land on a real
    // screen, not white. Skip if locked (the passkey modal owns the screen intentionally).
    setTimeout(() => {
      const cover = document.getElementById('app-resume-cover');
      const coverVisible = !!cover && cover.style.display !== 'none';
      if (coverVisible && !this.appLockService.isCurrentlyLocked()) {
        this.checkSurveyStatusAndNavigate();
      }
    }, 40_000);

    // Set up the single navigation subscription
    this.setupNavigationLogic();
  }

  /**
   * DEV ONLY: Simulate logging in as an existing user.
   * Only calls handleSuccessfulAuthentication (which stores JWT once and loads progress once).
   */
  /** DEV ONLY: Log in as a dev user and navigate directly to a specific page. */
  private simulateUserLoginToPage(email: string, page: string): void {
    this.authService.logout();
    this.authService.authenticateAsUser(email).subscribe({
      next: (response) => {
        if (response?.success && response.jwtToken) {
          this.authService.handleSuccessfulAuthentication(response.jwtToken, response.id!, response.email!);
          this.router.navigateByUrl(page, { replaceUrl: true }).finally(() => {
            this.appLockService.hideAllCovers();
          });
        }
      },
      error: () => this.router.navigate(['/get-started'], { replaceUrl: true })
    });
  }

  private simulateUserLogin(email?: string, userHandle?: string): void {
    // Clear any stale session so AppLockService doesn't race ahead and show the passkey modal
    // while the HTTP request is in-flight.
    this.authService.logout();
    this.authService.authenticateAsUser(email, userHandle).subscribe({
      next: (response) => {
        if (response?.success && response.jwtToken) {
          // Single call — stores JWT and loads progress in one flow
          this.authService.handleSuccessfulAuthentication(response.jwtToken, response.id!, response.email!);
        } else {
          console.error('[AppComponent] Dev auth failed:', response?.message);
          this.router.navigate(['/get-started'], { replaceUrl: true });
        }
      },
      error: () => {
        this.router.navigate(['/get-started'], { replaceUrl: true });
      }
    });
  }

  /**
   * Single unified navigation subscription.
   * Combines auth state + progress into one stream, navigates exactly once per state change.
   */
  private setupNavigationLogic(): void {
    combineLatest([
      this.authService.isLoggedIn$,
      this.authService.userProgress$,
      this.authService.reAuthInProgress$
    ]).pipe(
      debounceTime(300),
      distinctUntilChanged((prev, curr) => {
        return prev[0] === curr[0] &&
          JSON.stringify(prev[1]) === JSON.stringify(curr[1]) &&
          prev[2] === curr[2];
      }),
      filter(([isLoggedIn, progress, reAuthInProgress]) => {
        // Defer while a reauth ceremony is in flight
        if (!isLoggedIn && reAuthInProgress) return false;
        // Always process when not logged in (and no reauth pending)
        if (!isLoggedIn) return true;
        // When logged in, only process if we have progress data
        return progress !== null;
      })
    ).subscribe(([isLoggedIn, progress]) => {
      if (isLoggedIn && progress) {
        // A logged-in user has passed auth-finalize; their real progress lives in the DB.
        // If we only have the localStorage FALLBACK (DB load failed after retries), do NOT
        // route by it — an all-false fallback would eject a real user to /get-started.
        if (progress._source === 'localStorageFallback') {
          this.holdAuthenticatedUserOnFallback();
        } else {
          this.navigateBasedOnProgress(progress);
        }
      } else if (!isLoggedIn) {
        const unifiedProgress = this.authService.getUnifiedProgress();
        const hasAnyProgress = unifiedProgress.getStartedCompleted ||
          unifiedProgress.surveyInitialCompleted ||
          unifiedProgress.fiPlanResultsCompleted;

        if (hasAnyProgress) {
          this.navigateBasedOnProgress(unifiedProgress);
        } else {
          this.router.navigate(['/get-started'], { replaceUrl: true }).finally(() => {
            this.hideCoversWhenReady('/get-started');
          });
        }
      }
    });
  }

  navigateBasedOnProgress(progress: UserProgress): void {
    let targetRoute: string;

    // Follow the exact user flow order
    if (!progress.getStartedCompleted) {
      targetRoute = '/get-started';
    } else if (!progress.surveyInitialCompleted) {
      targetRoute = '/survey-initial';
    } else if (!progress.fiPlanResultsCompleted) {
      targetRoute = '/fi-plan-results';
    } else if (!progress.authFinalizeCompleted) {
      targetRoute = '/auth-finalize';
    } else if (!progress.kycVerificationCompleted) {
      targetRoute = '/kyc-verification';
    } else if (!progress.linkPlaidCompleted) {
      targetRoute = '/link-bank';
    } else if (!progress.investmentScheduleCompleted) {
      targetRoute = '/investment-schedule';
    } else if (!progress.investmentConfirmationCompleted) {
      targetRoute = '/investment-confirmation';
    } else {
      targetRoute = '/tabs/tab1';
    }

    const currentBaseUrl = this.router.url.split('?')[0].split('#')[0];

    // Don't navigate if already on the target route
    if (currentBaseUrl === targetRoute) {
      this.hideCoversWhenReady(targetRoute);
      return;
    }

    // Don't interrupt mid-recovery — user is unauthenticated by design on this route
    if (currentBaseUrl === '/recovery') {
      this.hideCoversWhenReady('/recovery');
      return;
    }

    // If fully onboarded, only redirect away from onboarding pages (let user stay on /my-profile, /tabs/tab2, etc.)
    if (targetRoute === '/tabs/tab1') {
      const onboardingRoutes = [
        '/get-started', '/survey-initial', '/fi-plan-results',
        '/auth-finalize', '/kyc-verification', '/link-bank',
        '/investment-schedule', '/investment-confirmation', '/'
      ];
      if (!onboardingRoutes.includes(currentBaseUrl)) {
        this.hideCoversWhenReady(currentBaseUrl);
        return;
      }
    }

    this.router.navigateByUrl(targetRoute, { replaceUrl: true }).finally(() => {
      this.hideCoversWhenReady(targetRoute);
    });
  }

  private hideCoversWhenReady(targetRoute?: string): void {
    const route = (targetRoute ?? this.router.url).split('?')[0].split('#')[0];
    const painted = this.preloadAssetsForRoute(route).then(() => this.waitForRoutePainted());
    const dataReady = route === '/tabs/tab1' ? this.waitForTab1DataPainted() : Promise.resolve();
    Promise.all([painted, dataReady]).then(() => this.appLockService.hideAllCovers());
  }

  /**
   * tab1-only data-readiness gate. Resolves when tab1 has painted FRESH (revalidated) data
   * — the `.portfolio-dashboard[data-fresh]` ready block — or the error card. It deliberately
   * does NOT resolve on the last-known snapshot (`.portfolio-dashboard` without `data-fresh`),
   * which is painted behind the cover: on a fast network the cover holds until the current
   * numbers arrive, so the snapshot is never seen.
   *
   * Bounded by a 2s hard cap so the cover can never hang:
   *  - slow network WITH a snapshot → cap fires → cover lifts onto the last-known snapshot (+ marker)
   *  - slow network with NO snapshot → cap fires → cover lifts onto the existing spinner
   *  - backend down → cover lifts onto the error card
   */
  private waitForTab1DataPainted(): Promise<void> {
    return new Promise<void>(resolve => {
      let settled = false;
      const finish = () => { if (!settled) { settled = true; resolve(); } };
      const check = () => {
        if (settled) return;
        // Scope to tab1's OWN portfolio content — <app-portfolio-dashboard> only exists on tab1,
        // so this can never match the OUTGOING page's still-present ion-content during a cross-page
        // transition (e.g. get-started → tab1 on reauth), which a generic 'ion-router-outlet
        // ion-content' selector would, resolving the gate before tab1 actually paints.
        const c = document.querySelector('app-portfolio-dashboard ion-content.portfolio-content') as HTMLElement | null;
        // Lift only for FRESH data ([data-fresh]) or the error card — never the spinner, never the
        // snapshot-only block (which holds behind the cover until fresh arrives or the 2s cap fires).
        if (c && c.offsetHeight > 0 && c.querySelector('.portfolio-dashboard[data-fresh], .portfolio-error')) {
          finish();
          return;
        }
        requestAnimationFrame(check);
      };
      requestAnimationFrame(check);
      setTimeout(finish, 2000);
    });
  }

  /**
   * Resolve once the destination route has actually laid out its first frame, so the
   * cover is never removed over a not-yet-painted lazy route. Routes are lazy-loaded
   * (loadComponent) and their Ionic web-components (ion-content etc.) upgrade
   * asynchronously AFTER navigation resolves — without this wait the cover lifts a beat
   * before the page paints, showing a blank-white flash (worse on slow devices/links,
   * e.g. tab1 → portfolio-dashboard). Bounded by a safety timeout so the cover can never
   * hang waiting for a route that never paints.
   */
  private waitForRoutePainted(): Promise<void> {
    return new Promise<void>(resolve => {
      let settled = false;
      const finish = () => { if (!settled) { settled = true; resolve(); } };
      const check = () => {
        if (settled) return;
        // The route's ion-content exists and has a non-zero layout box => it has upgraded
        // and painted. The launch cover lives outside ion-router-outlet, so it's excluded.
        const content = document.querySelector('ion-router-outlet ion-content') as HTMLElement | null;
        if (content && content.offsetHeight > 0) { finish(); return; }
        requestAnimationFrame(check);
      };
      requestAnimationFrame(check);
      setTimeout(finish, 2000); // safety bound — never hang the cover on a route that won't paint
    });
  }

  private preloadAssetsForRoute(route: string): Promise<void> {
    const fontPromises = CRITICAL_FONT_SPECS.map(spec =>
      document.fonts.load(spec).catch(() => undefined)
    );

    const imagePromises = (ROUTE_IMAGE_PRELOADS[route] ?? []).map(src =>
      new Promise<void>(resolve => {
        const img = new Image();
        img.src = src;
        const finish = () => resolve();
        if (typeof img.decode === 'function') {
          img.decode().then(finish, finish);
        } else {
          img.addEventListener('load', finish, { once: true });
          img.addEventListener('error', finish, { once: true });
        }
      })
    );

    const allReady = Promise.all([...fontPromises, ...imagePromises]).then(() => {});
    const safetyTimeout = new Promise<void>(r => setTimeout(r, 1500));
    return Promise.race([allReady, safetyTimeout]);
  }

  checkSurveyStatusAndNavigate(): void {
    const progress = this.authService.getCurrentProgress();
    if (!progress) {
      // Progress is still loading from backend (userProgress$ was reset to null by
      // handleSuccessfulAuthentication). resumeNoLock$ already confirmed no lock is needed.
      //
      // If the JWT says this user is fully onboarded, they are heading to /tabs/tab1 — whose
      // cover-hide is DATA-GATED. Route through tryOptimisticNav so the cover WAITS for tab1's
      // portfolio data instead of lifting on the current (non-tab1) route and revealing the
      // "Loading your portfolio…" spinner (the cold-start-reauth race — FRED-205).
      if (this.tryOptimisticNav()) return;
      // Otherwise (mid-onboarding / not tab1-bound): hide the cover now on the current route —
      // there is no tab1 data to wait for, and this prevents the cover getting stuck.
      this.hideCoversWhenReady();
      return;
    }
    this.navigateBasedOnProgress(progress);
  }

  /**
   * Optimistic navigation based on the "ns" (next-step) hint embedded in the JWT.
   *
   * Gate conditions (ALL must hold):
   *  - A valid JWT is present in localStorage
   *  - App Lock is not enabled (user opted-in to Face ID lock)
   *  - App is not currently showing the lock screen
   *  - The current route is an onboarding / entry route (never yank a user off /my-profile)
   *
   * v1: acts only when ns === 'complete' (fully-onboarded user) → /tabs/tab1.
   * The full slug→route map is defined in JwtTokenUtils.NEXT_STEP_ROUTE_MAP for v2.
   *
   * GET /user/progress still runs and remains the sole routing authority.
   * This method only PAINTS FASTER — it is purely additive.
   *
   * @returns true when it navigated to /tabs/tab1 (and therefore OWNS the data-gated
   *          cover-hide); false when no optimistic nav happened.
   */
  private tryOptimisticNav(): boolean {
    if (!JwtTokenUtils.getValidJwtToken()) return false;
    if (this.appLockService.isEnabled()) return false;
    if (this.appLockService.isCurrentlyLocked()) return false;

    const currentBaseUrl = this.router.url.split('?')[0].split('#')[0];
    const onboardingEntryRoutes = [
      '', '/', '/get-started', '/survey-initial', '/fi-plan-results',
      '/auth-finalize', '/kyc-verification', '/link-bank',
      '/investment-schedule', '/investment-confirmation',
    ];
    if (!onboardingEntryRoutes.includes(currentBaseUrl)) return false;

    const hint = JwtTokenUtils.getNextStepHint();
    if (hint === 'complete') {
      this.portfolioStore.prime();
      this.router.navigateByUrl('/tabs/tab1', { replaceUrl: true })
        .finally(() => this.hideCoversWhenReady('/tabs/tab1'));
      return true;
    }
    // Non-'complete' slugs: no optimistic nav in v1 — fall through to setupNavigationLogic.
    return false;
  }

  /**
   * An authenticated user whose progress could only be resolved from the all-false
   * localStorage FALLBACK (DB load failed after retries) must never be routed by it.
   * A valid token means they are past auth-finalize, so they belong in the app — keep
   * them where they are if already on a real route, else default to the app home.
   */
  private holdAuthenticatedUserOnFallback(): void {
    const currentBaseUrl = this.router.url.split('?')[0].split('#')[0];
    const onboardingRoutes = [
      '/get-started', '/survey-initial', '/fi-plan-results',
      '/auth-finalize', '/kyc-verification', '/link-bank',
      '/investment-schedule', '/investment-confirmation', '/', ''
    ];
    if (onboardingRoutes.includes(currentBaseUrl)) {
      this.router.navigateByUrl('/tabs/tab1', { replaceUrl: true })
        .finally(() => this.hideCoversWhenReady('/tabs/tab1'));
    } else {
      this.hideCoversWhenReady(currentBaseUrl);
    }
  }
}