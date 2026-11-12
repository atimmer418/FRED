import { Injectable, Injector } from '@angular/core';
import { App, AppState } from '@capacitor/app';
import { registerPlugin } from '@capacitor/core';
import { BehaviorSubject, Subject } from 'rxjs';
import { Platform, ModalController } from '@ionic/angular/standalone';
import { Router } from '@angular/router';
import { PasskeyPromptComponent } from '../components/passkey-prompt/passkey-prompt.component';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { LastScreenUtils } from '../utils/last-screen.utils';
import { AuthService } from './auth.service';

const LoadingOverlay = registerPlugin<{ hide(): Promise<void> }>('LoadingOverlay');

@Injectable({
  providedIn: 'root'
})
export class AppLockService {
  private isLockedSubject = new BehaviorSubject<boolean>(false);
  public isLocked$ = this.isLockedSubject.asObservable();

  /** Emits when app resumes from background without needing a lock screen. */
  private resumeNoLockSubject = new Subject<void>();
  public resumeNoLock$ = this.resumeNoLockSubject.asObservable();

  /** Set inside lockApp() so handleAppStateChange can detect whether a lock was shown. */
  private lockWasShownThisResume = false;
  
  private readonly LOCK_ENABLED_KEY = 'app_lock_enabled';

  /**
   * Hard idle cap: how long the user may go with ZERO physical interaction before
   * we stop refreshing and let the JWT expire (which triggers the lock). While the
   * app is foreground and the user has interacted within this window, the token keeps
   * refreshing — so reading a screen without tapping keeps the session alive up to the
   * cap. Measured against lastInteractionTime (physical input only, never HTTP traffic).
   */
  private readonly REFRESH_IDLE_CAP_MS = 30 * 60 * 1000; // 30 minutes
  
  /** Updated ONLY by genuine physical interaction (see registerUserInteraction). */
  private lastInteractionTime: number = Date.now();
  private lastUnlockTime: number = 0;
  /** True while the app is in the foreground; gates token refresh to "app is open". */
  private isForeground = true;
  private isModalOpen = false;
  private checkInterval: any;
  private failedLockRetries = 0;
  private readonly MAX_LOCK_RETRIES = 2;
  private initWatchdog: any;

  // Use Injector to break circular dependency (AppLockService ↔ AuthService)
  private _authService: any;

  constructor(
    private platform: Platform,
    private modalController: ModalController,
    private injector: Injector,
    private router: Router
  ) {
    this.init();
  }

  private getAuthService(): AuthService {
    if (!this._authService) {
      this._authService = this.injector.get(AuthService);
    }
    return this._authService;
  }

  private init() {
    // Watchdog: if platform.ready() is very slow or never resolves and reAuthInProgress
    // is blocking navigation, call lockApp directly so the cover gets hidden.
    this.initWatchdog = setTimeout(async () => {
      const auth = this.getAuthService();
      if (!this.isModalOpen && auth.isReAuthInProgress() && this.isUserLoggedIn()) {
        console.warn('[AppLockService] Init watchdog fired — lockApp was never reached');
        try {
          await this.lockApp(true);
        } catch (e) {
          console.error('[AppLockService] Watchdog lockApp failed, falling back to recovery', e);
          this.forceRecovery();
        }
      }
    }, 10_000);

    this.platform.ready().then(async () => {
      App.addListener('appStateChange', (state: AppState) => {
        this.handleAppStateChange(state);
      });
      this.startPeriodicCheck();
      // Cold start: appStateChange never fires on fresh launch, so check immediately.
      await this.checkLockOnResume();
    }).catch(async (err) => {
      console.error('[AppLockService] platform.ready() failed', err);
      clearTimeout(this.initWatchdog);
      try {
        await this.lockApp(true);
      } catch (e) {
        console.error('[AppLockService] lockApp after platform.ready() failure failed', e);
        this.forceRecovery();
      }
    });
  }

  private forceRecovery(): void {
    this.getAuthService().logout();
    this.hideAllCovers();
    this.router.navigateByUrl('/auth-finalize', { replaceUrl: true });
  }

  /**
   * Every 60 seconds:
   * 1. If user is active and token is expiring soon → refresh token
   * 2. If token has expired → lock app (passkey reauth will issue a new JWT)
   */
  private startPeriodicCheck() {
    this.checkInterval = setInterval(() => {
      this.checkTokenRefresh();
      this.checkTokenExpiry();
    }, 60 * 1000);
  }

  /**
   * Keep the JWT alive while the user is on the app. Refreshes when ALL hold:
   *  - the app is in the foreground ("app is open"), AND
   *  - the user has physically interacted within the idle cap — reading without
   *    tapping still counts as alive up to REFRESH_IDLE_CAP_MS, AND
   *  - the token has entered its refresh window (≤30 min to expiry).
   *
   * After the idle cap of zero interaction, refreshing stops and the token
   * eventually expires — which triggers the lock screen.
   */
  private checkTokenRefresh() {
    if (!JwtTokenUtils.getValidJwtToken()) return;

    // On native, only refresh while foreground. On web there is no app-state signal
    // (and the timer only runs in a live tab anyway), so treat as always foreground.
    const foreground = this.platform.is('capacitor') ? this.isForeground : true;
    const withinIdleCap = (Date.now() - this.lastInteractionTime) < this.REFRESH_IDLE_CAP_MS;

    if (foreground && withinIdleCap && JwtTokenUtils.shouldRefreshToken()) {
      this.getAuthService().refreshToken().subscribe({ error: () => {} });
    }
  }

  /**
   * If the JWT has expired and the user was previously logged in, lock the app.
   * This is the primary lock trigger — the JWT expiry IS the session timeout.
   * Passkey reauth endpoints are permitAll, so they work without a valid JWT.
   */
  private async checkTokenExpiry() {
    if (!this.isUserLoggedIn()) return;

    if (!JwtTokenUtils.getValidJwtToken()) {
      await this.lockApp(true);
    }
  }

  /** Check if the user has an active session (regardless of token validity) */
  private isUserLoggedIn(): boolean {
    return !!localStorage.getItem('userId');
  }

  /** Call on genuine physical interaction only (taps/scroll/keys), never on HTTP. */
  public registerUserInteraction() {
    this.lastInteractionTime = Date.now();
  }

  private async handleAppStateChange(state: AppState) {
    if (!state.isActive) {
      // Skip the cover if:
      // 1. The auth modal is open — the passkey/FaceID sheet briefly resigns active state,
      //    and covering here would bury the modal.
      // 2. We just unlocked within 5 s — iOS fires a cleanup appStateChange(false) 1-2 s
      //    after ASAuthorizationController finishes, by which time isModalOpen is already
      //    false but the passkey handoff is still completing. Without this guard the cover
      //    appears over the live app and never gets hidden (checkSurveyStatusAndNavigate
      //    bails early because userProgress$ is still null during loadUserProgress()).
      const withinUnlockGrace = Date.now() - this.lastUnlockTime < 5000;
      if (!this.isModalOpen && !withinUnlockGrace) {
        this.showAppCover();
        // The user is genuinely leaving: start the "reopen where you left off" clock here. The
        // two guards above matter for it too, since a stamp during the passkey sheet would make
        // a stale screen look fresh. router.navigated keeps it off until this launch has landed.
        if (this.router.navigated) {
          LastScreenUtils.touch();
        }
      }
      this.isForeground = false;
    } else {
      this.isForeground = true;
      this.lockWasShownThisResume = false;
      await this.checkLockOnResume();
      if (!this.lockWasShownThisResume) {
        // No lock was needed — signal AppComponent to check the current route.
        // The cover will be hidden inside navigateBasedOnProgress() (same route
        // early-return or after navigateByUrl resolves).
        this.resumeNoLockSubject.next();
      }
      // If lock was shown: lockApp() already hid the cover, and auth +
      // handleSuccessfulAuthentication() will drive navigation which hides it.
    }
  }

  showAppCover() {
    if (!this.platform.is('capacitor')) return;
    (document.activeElement as HTMLElement)?.blur();
    let cover = document.getElementById('app-resume-cover');
    if (!cover) {
      cover = document.createElement('div');
      cover.id = 'app-resume-cover';
      cover.style.cssText = 'position:fixed;inset:0;background:#ffffff;z-index:99999;pointer-events:none;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-user-drag:none;-webkit-tap-highlight-color:transparent;display:flex;align-items:center;justify-content:center;';
      const img = document.createElement('img');
      img.src = 'assets/images/fred-logo.svg';
      img.draggable = false;
      img.style.cssText = 'width:200px;height:200px;object-fit:contain;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-user-drag:none;pointer-events:none;';
      cover.appendChild(img);
      document.body.appendChild(cover);
    }
    cover.style.display = 'flex';
    // lottie-web pauses the coin-drop animation when display:none hides its container.
    // iOS's IntersectionObserver callback fires asynchronously, so the animation can
    // appear frozen (stuck on the first frame, which looks like a static piggybank)
    // when the cover is shown again. Explicitly calling play() here ensures the
    // animation always restarts regardless of whether the IO fired yet.
    const coverAnim = (window as any)._fredCoverAnim;
    if (coverAnim && typeof coverAnim.play === 'function') {
      coverAnim.play();
    }
  }

  hideAppCover() {
    const cover = document.getElementById('app-resume-cover');
    if (cover) cover.style.display = 'none';
  }

  /** Hides the native iOS loading overlay. No-op on non-iOS platforms. */
  hideNativeOverlay(): void {
    if (this.platform.is('capacitor') && this.platform.is('ios')) {
      LoadingOverlay.hide().catch(() => {});
    }
  }

  /** Hides both the JS cover div and the native iOS overlay. Use this instead of hideAppCover() when content is ready to show. */
  hideAllCovers(): void {
    this.hideAppCover();
    this.hideNativeOverlay();
  }

  /**
   * When app returns to foreground:
   * - If App Lock is enabled: always lock (security setting)
   * - Otherwise: lock if JWT expired while in background
   */
  private async checkLockOnResume() {
    if (!this.isUserLoggedIn()) return;

    // Face ID briefly backgrounds the app while the prompt is shown.
    // Ignore the foreground event if we just unlocked within the last 3 seconds.
    if (Date.now() - this.lastUnlockTime < 3000) return;

    const jwtExpired = !JwtTokenUtils.getValidJwtToken();

    if (this.isEnabled()) {
      // App Lock setting ON: always require reauth on resume.
      // If JWT also expired, run the full FIDO2 ceremony so a fresh token is issued.
      await this.lockApp(jwtExpired);
    } else if (jwtExpired) {
      // Token expired while backgrounded → full passkey reauth to issue a new JWT.
      await this.lockApp(true);
    } else {
      // Token still valid on resume. iOS suspends setInterval while backgrounded, so
      // refresh immediately (rather than waiting up to 60s for the next tick) when the
      // token is near expiry and the user is still within the idle cap. Fire-and-forget:
      // this branch never locks, so it can't race the modal/cover choreography.
      const withinIdleCap = (Date.now() - this.lastInteractionTime) < this.REFRESH_IDLE_CAP_MS;
      if (withinIdleCap && JwtTokenUtils.shouldRefreshToken()) {
        this.getAuthService().refreshToken().subscribe({ error: () => {} });
      }
    }
  }

  /** Synchronous check for current lock state */
  isCurrentlyLocked(): boolean {
    return this.isLockedSubject.getValue();
  }

  async lockApp(jwtExpired = false) {
    if (this.isModalOpen) {
      this.lockWasShownThisResume = true;
      return;
    }

    // If lockApp is running, the init watchdog is no longer needed
    clearTimeout(this.initWatchdog);

    this.lockWasShownThisResume = true;
    this.hideAppCover(); // Always clear the cover so the modal isn't blocked
    this.isLockedSubject.next(true);
    this.isModalOpen = true;

    // Safety net: if the modal's Lottie never fires its ready callback (asset 404,
    // lottie-web unavailable), remove the native overlay after 1500ms so the
    // app isn't permanently blocked behind it.
    const overlayFallback = setTimeout(() => this.hideNativeOverlay(), 1500);

    const modal = await this.modalController.create({
      component: PasskeyPromptComponent,
      componentProps: {
        jwtExpired,
        userEmail: localStorage.getItem('userEmail') ?? undefined,
        onLottieReady: () => {
          clearTimeout(overlayFallback);
          this.hideNativeOverlay();
        },
        // Re-show the launch cover the instant reauth succeeds (before the modal dismisses),
        // bridging the gap until the destination route paints. Without this, lockApp()'s
        // hideAppCover() above leaves no cover to hold and tab1 flashes blank (FRED-205 reauth fix).
        onReauthSuccess: () => this.showAppCover()
      },
      backdropDismiss: false,
      keyboardClose: false,
      animated: false,
      cssClass: 'full-screen-modal'
    });

    await modal.present();
    // Native overlay is now hidden by the onLottieReady callback once the modal's
    // Lottie has visibly rendered, guaranteeing a seamless native → web handoff.

    const { data } = await modal.onDidDismiss();

    this.isModalOpen = false;

    if (data && data.authenticated) {
      this.failedLockRetries = 0;
      this.unlockApp();
    } else {
      // Modal closed without a successful auth (abnormal — PasskeyPromptComponent never
      // dismisses on failure; it leaves the modal open with a retry button). Retry the
      // lock flow to give the user another chance before we give up on their session.
      if (this.failedLockRetries < this.MAX_LOCK_RETRIES) {
        this.failedLockRetries++;
        await this.lockApp(jwtExpired);
      } else {
        // Retry budget exhausted — clear session so the cold-start trap doesn't re-arm
        this.failedLockRetries = 0;
        this.forceRecovery();
      }
    }
  }

  private unlockApp() {
    this.isLockedSubject.next(false);
    // A completed reauth is genuine presence — reset the idle cap.
    this.lastInteractionTime = Date.now();
    this.lastUnlockTime = Date.now();
  }

  isEnabled(): boolean {
    // Default to false if not set — app lock is opt-in
    const val = localStorage.getItem(this.LOCK_ENABLED_KEY);
    return val === null ? false : val === 'true';
  }

  setEnabled(enabled: boolean) {
    localStorage.setItem(this.LOCK_ENABLED_KEY, String(enabled));
  }
}
