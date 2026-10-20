import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, BehaviorSubject, Subject, tap, catchError, of, lastValueFrom, timeout, retry, timer, throwError, finalize, shareReplay, defer, switchMap } from 'rxjs';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { resolveDevToolsKey } from '../utils/dev-tools-key.utils';
import { AnalyticsService } from './analytics/analytics.service';
import { DeviceIdService } from './device-id.service';
import { KeychainSyncService } from './keychain-sync.service';
import { NativePasskeyService } from './native-passkey.service';
import { ACTIVE_MOCK_SUBSCRIPTION_TIER, tierMockProfilePatch } from './dev/tier-mock';

const BACKEND_API_URL = environment.backendApiUrl;

export interface UserProgress {
  getStartedCompleted: boolean;
  surveyInitialCompleted: boolean;
  fiPlanResultsCompleted: boolean;
  authFinalizeCompleted: boolean;
  kycVerificationCompleted: boolean;
  linkPlaidCompleted: boolean;
  investmentScheduleCompleted: boolean;
  investmentConfirmationCompleted: boolean;
  monthlyInvestment?: number; // User's monthly investment capacity
  retirementIncome?: number; // User's desired retirement income
  firstName?: string;
  lastName?: string;
  referralCode?: string;
  referralCount?: number;
  hasAppliedReferral?: boolean;
  selectedTier?: 'core' | 'plus' | 'pro';
  billingPeriod?: string;
  privateBeta?: boolean;
  /** Opaque analytics identity. Never the email or the numeric id. */
  publicId?: string;
  /** Server-owned analytics consent. true means opted out. */
  analyticsOptOut?: boolean;
  referralRewardTriggered?: boolean;
  currentFreedomEstimate?: number;
  timeToFI?: string;
  _source?: 'db' | 'localStorageFallback';
}

export interface PasskeyAuthRequest {
  email: string;
  // No password needed - passkey handles authentication
}

export interface AuthResponse {
  success: boolean;
  message: string;
  jwtToken: string;
  id: number;
  email: string;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private isLoggedInSubject = new BehaviorSubject<boolean>(false);
  public isLoggedIn$ = this.isLoggedInSubject.asObservable();

  private userProgressSubject = new BehaviorSubject<UserProgress | null>(null);
  public userProgress$ = this.userProgressSubject.asObservable();

  private reAuthInProgressSubject = new BehaviorSubject<boolean>(false);
  public reAuthInProgress$ = this.reAuthInProgressSubject.asObservable();
  private tokenRefreshInProgress = false;

  // In-flight join for GET /user/progress. While a request is pending, concurrent callers
  // share this observable (shareReplay(1)). Cleared by finalize on settle — success AND error —
  // so the next call after settlement always issues a fresh request. No TTL cache.
  private progressInFlight$: Observable<UserProgress> | null = null;

  /**
   * Emits once at the END of handleSuccessfulAuthentication so app.component
   * can perform hint-based optimistic navigation after a successful auth/reauth.
   * The Subject carries no payload — app.component reads getNextStepHint() itself.
   */
  private authSucceededSubject = new Subject<void>();
  public authSucceeded$ = this.authSucceededSubject.asObservable();

  constructor(
    private http: HttpClient,
    private deviceIdService: DeviceIdService,
    private keychainSyncService: KeychainSyncService,
    private nativePasskeyService: NativePasskeyService,
    private analytics: AnalyticsService
  ) {
    // Check if user is already logged in on app start
    this.checkExistingSession();
  }

  private getAuthHeaders(): HttpHeaders {
    let headers = new HttpHeaders();
    const token = JwtTokenUtils.getValidJwtToken(); // Use utility to get valid token
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }

    // Add device ID to all requests for better device tracking
    const deviceId = this.deviceIdService.getDeviceId();
    headers = headers.set('X-Device-ID', deviceId);

    return headers;
  }

  private checkExistingSession(): void {
    const token = JwtTokenUtils.getValidJwtToken();

    if (token) {
      // Valid token exists — restore session and load progress from backend
      this.isLoggedInSubject.next(true);
      // A returning user never passes through handleSuccessfulAuthentication, so identity is
      // applied here as well. Consent still gates everything, and it arrives with the progress
      // payload a moment later.
      this.identifyFromStoredToken();
      this.loadUserProgress();
    } else {
      // No valid token — check if there's a known user whose session expired
      const existingUserId = localStorage.getItem('userId');
      const willPromptReauth = !!(existingUserId && this.supportsPasskeys());

      if (willPromptReauth) {
        // Returning user with expired session: preserve userId/userEmail so reauth
        // can use them, and so AppLockService.isUserLoggedIn() stays accurate.
        // Only remove the expired token data itself.
        localStorage.removeItem('jwtToken');
        localStorage.removeItem('jwtExpiration');
      } else {
        JwtTokenUtils.clearJwtData();
      }

      const localStorageProgress = this.buildProgressFromLocalStorage();
      this.userProgressSubject.next(localStorageProgress);

      if (this.supportsPasskeys()) {
        if (existingUserId) {
          // Returning user — set reAuthInProgress synchronously so navigation defers
          // until AppLockService shows the passkey modal via the appStateChange event.
          // PasskeyPromptComponent calls handleSuccessfulAuthentication() on success,
          // which resets this flag and triggers correct navigation.
          this.reAuthInProgressSubject.next(true);
        } else if (!localStorage.getItem('userEmail')) {
          // Fresh install or phone upgrade: no known user on this device.
          // Attempt discoverable passkey auth — iOS will find any passkeys for this
          // app's RP in iCloud Keychain and show the picker. Silently fails if none.
          this.promptForPasskeyReauth();
        }
      }
    }
  }

  /**
   * Called after successful authentication (passkey, dev login, etc.).
   * Stores JWT once, syncs localStorage progress if needed, then loads backend progress.
   * Also persists the user's email to iCloud Keychain for cross-device recognition.
   */
  handleSuccessfulAuthentication(jwtToken: string, userId: number, email: string): void {
    JwtTokenUtils.storeJwtToken(jwtToken, userId, email);
    this.isLoggedInSubject.next(true);
    this.reAuthInProgressSubject.next(false); // Clear any pending reauth gate
    // Reset progress to null so the navigation logic waits for fresh DB data
    // rather than briefly routing based on stale localStorage values.
    this.userProgressSubject.next(null);

    // Persist email to iCloud Keychain so new devices can auto-prompt reauth
    // Only write if not already stored (avoids redundant Keychain writes)
    this.keychainSyncService.getAccountEmail().then(existing => {
      if (existing !== email) {
        this.keychainSyncService.storeAccountEmail(email);
      }
    });

    // Check if there's pre-auth localStorage progress to sync
    const hasLocalProgress = parseInt(localStorage.getItem('onboardingStep') || '0', 10) >= 1;

    if (hasLocalProgress) {
      this.syncLocalStorageProgressToDatabase().subscribe({
        next: () => this.loadUserProgress(),
        error: () => this.loadUserProgress()
      });
    } else {
      this.loadUserProgress();
    }

    // Analytics identity. The pid claim is on the token we just stored; the progress payload that
    // follows carries the server's consent value, which is what actually opens the gate.
    this.identifyFromStoredToken();

    // Notify app.component so it can perform optimistic nav from the JWT hint.
    // This fires AFTER the load chain is kicked off (additive — does not replace it).
    this.authSucceededSubject.next();
  }

  logout(): void {
    // Before the token goes: the next user on this device must not inherit this identity.
    this.analytics.reset();
    JwtTokenUtils.clearJwtData();
    this.isLoggedInSubject.next(false);
    this.reAuthInProgressSubject.next(false);
    this.userProgressSubject.next(null);
    this.progressInFlight$ = null;
  }

  isAuthenticated(): boolean {
    return !JwtTokenUtils.isJwtExpired();
  }

  /**
   * Identifies the current user to analytics from the stored token's pid claim. Silent when the
   * claim is absent (a token issued before the claim existed): the progress payload carries the
   * public id too, and that path runs moments later.
   */
  private identifyFromStoredToken(): void {
    const publicId = JwtTokenUtils.getPublicId();
    if (publicId) {
      this.analytics.identify(publicId);
    }
  }

  /** DEV ONLY: true once dev/tier-mock.ts has written its tier to the server this launch. */
  private tierMockSynced = false;

  /**
   * DEV ONLY: write the dev/tier-mock.ts tier to the user row, once per app launch. Emits
   * (with null) whether or not the write succeeded so the progress read always proceeds.
   * Gated on environment.local like the portfolio/MFU mocks; the toggle is null under karma,
   * so specs see exactly one GET and no PUT.
   */
  private syncTierMock(): Observable<null> {
    const tier = environment.local ? ACTIVE_MOCK_SUBSCRIPTION_TIER : null;
    if (!tier || this.tierMockSynced) return of(null);
    return this.http.put(`${BACKEND_API_URL}/user/profile`, tierMockProfilePatch(tier),
      { headers: this.getAuthHeaders() }).pipe(
      tap(() => {
        this.tierMockSynced = true;
        console.info(`[tier-mock] Wrote selectedTier=${tier} to the server for this launch.`);
      }),
      catchError((err) => {
        console.warn('[tier-mock] Could not write the mocked tier; progress will show the real one.', err);
        return of(null);
      }),
      switchMap(() => of(null))
    );
  }

  getUserProgress(): Observable<UserProgress> {
    if (this.progressInFlight$) {
      return this.progressInFlight$;
    }
    const obs: Observable<UserProgress> = this.syncTierMock().pipe(
      switchMap(() => this.http.get<UserProgress>(
        `${BACKEND_API_URL}/user/progress`,
        { headers: this.getAuthHeaders() }
      )),
      finalize(() => {
        if (this.progressInFlight$ === obs) {
          this.progressInFlight$ = null;
        }
      }),
      shareReplay(1)
    );
    this.progressInFlight$ = obs;
    return obs;
  }

  /**
   * Sync localStorage progress to database (called after authfinalize completion)
   * This migrates anonymous pre-auth progress to the authenticated user's database record
   */
  syncLocalStorageProgressToDatabase(): Observable<any> {
    const ordinal = parseInt(localStorage.getItem('onboardingStep') || '0', 10);
    const monthlyInvestment = localStorage.getItem('surveyMonthlyInvestment');
    const retirementIncome = localStorage.getItem('surveyRetirementIncome');

    const localProgress: Partial<UserProgress> = {
      getStartedCompleted: ordinal >= 1,
      surveyInitialCompleted: ordinal >= 2,
      fiPlanResultsCompleted: ordinal >= 3,
      monthlyInvestment: monthlyInvestment ? parseInt(monthlyInvestment, 10) : undefined,
      retirementIncome: retirementIncome ? parseInt(retirementIncome, 10) : undefined
      // authFinalizeCompleted is set to true by the backend when this sync happens
    };

    return this.updateProgress(localProgress).pipe(
      tap(() => {
        // Don't clear localStorage flags immediately - wait until after authFinalize is complete
      })
    );
  }

  /**
   * Build UserProgress object from localStorage (for non-authenticated users)
   */
  private buildProgressFromLocalStorage(): UserProgress {
    const ordinal = parseInt(localStorage.getItem('onboardingStep') || '0', 10);
    const monthlyInvestment = localStorage.getItem('surveyMonthlyInvestment');
    const retirementIncome = localStorage.getItem('surveyRetirementIncome');
    const tier = localStorage.getItem('fred.selectedTier') as 'core' | 'plus' | 'pro' | null;
    return {
      getStartedCompleted: ordinal >= 1,
      surveyInitialCompleted: ordinal >= 2,
      fiPlanResultsCompleted: ordinal >= 3,
      authFinalizeCompleted: ordinal >= 4,
      kycVerificationCompleted: ordinal >= 5,
      linkPlaidCompleted: ordinal >= 6,
      investmentScheduleCompleted: ordinal >= 7,
      investmentConfirmationCompleted: ordinal >= 8,
      monthlyInvestment: monthlyInvestment ? parseInt(monthlyInvestment, 10) : undefined,
      retirementIncome: retirementIncome ? parseInt(retirementIncome, 10) : undefined,
      selectedTier: tier ?? undefined,
      privateBeta: false
    };
  }

  /**
   * Refresh localStorage progress in the subject (for non-authenticated users)
   * Call this when localStorage progress is updated before authentication
   */
  refreshLocalStorageProgress(): void {
    if (!this.isAuthenticated()) {
      const updatedProgress = this.buildProgressFromLocalStorage();
      this.userProgressSubject.next(updatedProgress);
    }
  }

  /**
   * Clear localStorage progress flags after successful sync to database
   * This prevents confusion between localStorage and database as source of truth
   */
  clearLocalStorageProgressFlags(): void {
    localStorage.removeItem('onboardingStep');
    localStorage.removeItem('surveyMonthlyInvestment');
    localStorage.removeItem('surveyRetirementIncome');
  }

  loadUserProgress(): void {
    // defer re-evaluates getUserProgress() on each subscription attempt (including retries).
    // After an error, finalize() has already cleared progressInFlight$, so each retry calls
    // getUserProgress() fresh — creating a new in-flight observable and a real HTTP request.
    // Without defer, retry would resubscribe to the same shareReplay(1) instance, which
    // replays the cached error instantly and makes no new HTTP request.
    defer(() => this.getUserProgress()).pipe(
      // Per-attempt 10s cap (unchanged) — guards against a hung request.
      timeout(10_000),
      // Retry transient failures so a single network blip / cold proxy / 5xx self-heals
      // into real DB data instead of the all-false localStorage fallback. NEVER retry
      // 401/403/4xx — those won't heal by retrying.
      retry({
        count: 2,
        delay: (err: any, attempt: number) => {
          const status = err?.status ?? 0;
          const isTimeout = err?.name === 'TimeoutError';
          const transient = isTimeout || status === 0 || status >= 500;
          if (!transient) return throwError(() => err);
          return timer(400 * Math.pow(2, attempt - 1)); // 400ms, 800ms
        }
      })
    ).subscribe({
      next: (progress) => {
        // Real DB data — safe to drive navigation.
        // The server owns consent; this is where the client learns it.
        this.analytics.applyServerConsent(progress.analyticsOptOut);
        if (progress.publicId) {
          this.analytics.identify(progress.publicId);
        }
        this.userProgressSubject.next({ ...progress, _source: 'db' });
      },
      error: (err) => {
        console.error('[AuthService] Failed to load user progress (after retries):', err);
        // Tag the fallback so the navigation layer can refuse to route an authenticated
        // user by it (an all-false fallback would eject a real user to /get-started).
        // Still emit a non-null value so userProgress$ never stays null and the cover hides.
        this.userProgressSubject.next({ ...this.buildProgressFromLocalStorage(), _source: 'localStorageFallback' });
      }
    });
  }

  updateProgress(progressUpdate: Partial<UserProgress>): Observable<any> {
    return this.http.put(`${BACKEND_API_URL}/user/progress`, progressUpdate,
      { headers: this.getAuthHeaders() });
  }

  /**
   * Update user profile data directly (User object), bypassing UserProgress logic.
   * Use this for profile page updates like name, retirement income, etc.
   */
  updateUserProfile(profileUpdate: { monthlyInvestment?: number, retirementIncome?: number, firstName?: string, lastName?: string, selectedTier?: string, billingPeriod?: string, agreedToMarketing?: boolean }): Observable<any> {
    return this.http.put(`${BACKEND_API_URL}/user/profile`, profileUpdate,
      { headers: this.getAuthHeaders() });
  }

  private stepToOrdinal(step: string): number {
    const map: Record<string, number> = {
      getStarted: 1, surveyInitial: 2, fiPlanResults: 3, authFinalize: 4,
      kycVerification: 5, linkPlaid: 6, investmentSchedule: 7, investmentConfirmation: 8
    };
    return map[step] ?? 0;
  }

  /**
   * Comprehensive method to update both localStorage and backend progress
   * Use this method whenever a user completes or revisits a step
   */
  updateStepProgress(step: string, completed: boolean): Observable<any> {
    // Always update localStorage first
    if (completed) {
      const newOrdinal = this.stepToOrdinal(step);
      const current = parseInt(localStorage.getItem('onboardingStep') || '0', 10);
      if (newOrdinal > current) {
        localStorage.setItem('onboardingStep', String(newOrdinal));
      }
    }

    // Check if user is authenticated before trying to update backend
    if (!this.isAuthenticated()) {
      // Update the userProgressSubject with the latest localStorage data
      const updatedProgress = this.buildProgressFromLocalStorage();
      this.userProgressSubject.next(updatedProgress);
      return of({ success: true, message: 'localStorage updated' });
    }

    // Create the progress update object
    const progressUpdate: any = {};
    progressUpdate[`${step}Completed`] = completed;

    // Update backend only if authenticated
    return this.updateProgress(progressUpdate).pipe(
      tap(() => {
        // Reload user progress to keep it in sync
        this.loadUserProgress();
      }),
      catchError((err: any) => {
        console.error(`[AuthService] Failed to update backend ${step} progress:`, err);
        return of({ success: true, message: 'localStorage updated, backend update failed' });
      })
    );
  }

  /**
   * Mark a step as completed (user finished the step)
   */
  completeStep(step: string): Observable<any> {
    return this.updateStepProgress(step, true);
  }

  /**
   * Proactively refresh the JWT token before it expires.
   * Called by AppLockService while the user is on the app and the token is within
   * its 30-min refresh window. Self-healing: a hung or failed request clears the
   * in-progress latch (via finalize) so the next tick can retry cleanly.
   */
  refreshToken(): Observable<boolean> {
    if (this.tokenRefreshInProgress || !this.isAuthenticated()) {
      return of(false);
    }

    this.tokenRefreshInProgress = true;

    return this.http.post<any>(`${BACKEND_API_URL}/auth/refresh`, {}, {
      headers: this.getAuthHeaders()
    }).pipe(
      // Cap the request so a hung /auth/refresh can't wedge tokenRefreshInProgress
      // forever (which would permanently disable all future refreshes). 15s is well
      // under the 60s refresh tick, so the latch always clears before the next attempt.
      timeout(15000),
      tap(response => {
        if (response?.success && response.jwtToken) {
          JwtTokenUtils.storeJwtToken(response.jwtToken, response.id, response.email);
        }
      }),
      catchError(err => {
        console.error('[AuthService] Token refresh failed:', err);
        return of(false);
      }),
      // finalize is the single guaranteed reset point — it runs on success, error,
      // timeout, and unsubscribe, so the latch can never get permanently stuck.
      finalize(() => { this.tokenRefreshInProgress = false; })
    ) as Observable<boolean>;
  }

  /**
   * Check if re-authentication is currently in progress
   */
  isReAuthInProgress(): boolean {
    return this.reAuthInProgressSubject.value;
  }

  /**
   * Check if browser supports passkeys/WebAuthn
   */
  supportsPasskeys(): boolean {
    return !!(navigator.credentials && window.PublicKeyCredential && typeof window.PublicKeyCredential === 'function');
  }

  /**
   * Check with backend if current device/IP should be prompted for passkey re-authentication
   * Only devices that have previous users who completed auth-finalize will be prompted
   */
  shouldPromptForReauth(): Observable<{ shouldPromptReauth: boolean, message: string }> {
    return this.http.get<{ shouldPromptReauth: boolean, message: string }>(`${BACKEND_API_URL}/user/should-prompt-reauth`,
      { headers: this.getAuthHeaders() });
  }

  /**
   * Prompt user for passkey re-authentication.
   * Only runs on fresh install / phone upgrade (no localStorage email).
   * Uses iCloud Keychain to identify the account, then checks the backend for
   * registered passkeys before invoking iOS — so iOS is never called if the
   * list is empty, preventing the "Scan QR Code" sheet from appearing.
   */
  async promptForPasskeyReauth(): Promise<boolean> {
    // Known-user re-auth is owned by AppLockService → PasskeyPromptComponent (account-specific).
    // This discovery flow is ONLY for devices that don't know the user. Leave reAuthInProgress
    // untouched here so the known-user path keeps ownership.
    if (localStorage.getItem('userEmail')) return false;

    // Set the defer flag SYNCHRONOUSLY, before the first await, so AppComponent's
    // setupNavigationLogic() can't race ahead to /get-started while the keychain read +
    // /authenticate/start call are still in flight. Every early-return below resets it.
    this.reAuthInProgressSubject.next(true);

    // Identify the account via iCloud Keychain (survives reinstalls/upgrades).
    // If nothing is in the Keychain this is a genuinely first-time user — skip.
    const keychainEmail = await this.keychainSyncService.getAccountEmail();
    if (!keychainEmail) {
      this.reAuthInProgressSubject.next(false); // genuine first-timer → allow get-started
      return false;
    }

    try {
      // Ask the backend for this account's passkeys.
      let startResponse: { requestOptions: string, sessionId: string };
      try {
        startResponse = await lastValueFrom(
          this.http.post<{ requestOptions: string, sessionId: string }>(
            `${BACKEND_API_URL}/passkey/authenticate/start`,
            { email: keychainEmail },
            { headers: this.getAuthHeaders() }
          ).pipe(timeout(10_000))
        );
      } catch (err) {
        // A failed start is almost always TRANSIENT (backend down, tunnel/network error,
        // timeout, 5xx). The backend can't cleanly distinguish "unknown email" from an
        // outage here, so do NOT clear the Keychain recovery anchor — wrongly clearing it
        // permanently disarms cross-device recovery after a brief blip. Keep the anchor,
        // abort, and let the next launch/resume retry.
        console.warn('[AuthService] Passkey reauth start failed; keeping Keychain anchor:', err);
        this.reAuthInProgressSubject.next(false);
        return false;
      }

      const credentialRequestOptions = JSON.parse(startResponse.requestOptions);
      const publicKey = credentialRequestOptions.publicKey ?? credentialRequestOptions;
      const allowedCreds = publicKey.allowCredentials ?? [];

      // Only invoke iOS if there is at least one passkey to offer.
      // An empty list means no passkeys are registered — go straight to onboarding.
      if (allowedCreds.length === 0) {
        this.reAuthInProgressSubject.next(false);
        return false;
      }

      const challenge = credentialRequestOptions.challenge || publicKey.challenge;
      const rpId = publicKey.rpId ?? publicKey.rp?.id;
      const userVerification = publicKey.userVerification;
      const allowedCredentials = allowedCreds.map((c: any) => ({ id: c.id }));

      let credentialForFinish: any;

      if (this.nativePasskeyService.isAvailable) {
        credentialForFinish = await this.nativePasskeyService.authenticate({
          challenge,
          rpId,
          userVerification,
          allowedCredentials
        });
      } else {
        if (publicKey.challenge) {
          publicKey.challenge = this.base64urlToArrayBuffer(publicKey.challenge);
        }
        allowedCreds.forEach((cred: any) => {
          if (cred.id) cred.id = this.base64urlToArrayBuffer(cred.id);
        });
        const credential = await navigator.credentials.get(
          credentialRequestOptions.publicKey ? credentialRequestOptions : { publicKey: credentialRequestOptions }
        );
        if (!credential) throw new Error('User cancelled passkey authentication');
        credentialForFinish = this.credentialToJson(credential as PublicKeyCredential);
      }

      const authResponse = await lastValueFrom(
        this.http.post<any>(`${BACKEND_API_URL}/passkey/authenticate/finish`, {
          credential: credentialForFinish,
          sessionId: startResponse.sessionId
        }, { headers: this.getAuthHeaders() })
      );

      if (authResponse?.success) {
        this.handleSuccessfulAuthentication(authResponse.jwtToken, authResponse.userId, authResponse.email);
        this.reAuthInProgressSubject.next(false);
        return true;
      } else {
        this.reAuthInProgressSubject.next(false);
        return false;
      }

    } catch (error) {
      this.reAuthInProgressSubject.next(false);
      console.error('[AuthService] Passkey re-authentication failed:', error);
      return false;
    }
  }

  // Investment Schedule methods (one-to-one mapping per user)
  createOrUpdateInvestmentSchedule(schedule: any): Observable<any> {
    console.log('[AuthService] Creating/updating investment schedule (upsert):', schedule);
    return this.http.post(`${BACKEND_API_URL}/investment-schedule/create`, schedule,
      { headers: this.getAuthHeaders() }).pipe(
      tap(() => this.clearScheduleCache())
    );
  }

  // Backward compatibility method - delegates to createOrUpdateInvestmentSchedule
  createInvestmentSchedule(schedule: any): Observable<any> {
    console.log('[AuthService] Creating investment schedule (delegates to upsert method):', schedule);
    return this.createOrUpdateInvestmentSchedule(schedule);
  }

  // Single-flight cache for investment-schedule/current.
  // Keyed by userId so logout/login as a different user gets a fresh fetch.
  private static readonly SCHEDULE_TTL_MS = 60_000;
  private scheduleEntry: { value: any; fetchedAt: number; userId: string } | null = null;
  private scheduleInFlight$: Observable<any> | null = null;

  getCurrentInvestmentSchedule(): Observable<any> {
    const userId = localStorage.getItem('userId') ?? '';
    if (
      this.scheduleEntry &&
      this.scheduleEntry.userId === userId &&
      Date.now() - this.scheduleEntry.fetchedAt < AuthService.SCHEDULE_TTL_MS
    ) {
      return of(this.scheduleEntry.value);
    }
    if (this.scheduleInFlight$) {
      return this.scheduleInFlight$;
    }
    const obs: Observable<any> = this.http.get(
      `${BACKEND_API_URL}/investment-schedule/current`,
      { headers: this.getAuthHeaders() }
    ).pipe(
      tap(value => {
        this.scheduleEntry = { value, fetchedAt: Date.now(), userId: localStorage.getItem('userId') ?? '' };
      }),
      finalize(() => {
        if (this.scheduleInFlight$ === obs) {
          this.scheduleInFlight$ = null;
        }
      }),
      shareReplay(1)
    );
    this.scheduleInFlight$ = obs;
    return obs;
  }

  /** Clear the schedule single-flight cache. Call after creating or updating a schedule. */
  clearScheduleCache(): void {
    this.scheduleEntry = null;
    this.scheduleInFlight$ = null;
  }

  getAllInvestmentSchedules(): Observable<any> {
    console.log('[AuthService] Retrieving all user investment schedules...');
    // GET all user's investment schedules
    return this.http.get(`${BACKEND_API_URL}/investment-schedule/all`,
      { headers: this.getAuthHeaders() });
  }

  pauseInvestmentSchedule(scheduleId: number): Observable<any> {
    console.log('[AuthService] Pausing investment schedule:', scheduleId);
    return this.http.post(`${BACKEND_API_URL}/investment-schedule/${scheduleId}/pause`, {},
      { headers: this.getAuthHeaders() }).pipe(
      tap(() => this.clearScheduleCache())
    );
  }

  resumeInvestmentSchedule(scheduleId: number): Observable<any> {
    console.log('[AuthService] Resuming investment schedule:', scheduleId);
    return this.http.post(`${BACKEND_API_URL}/investment-schedule/${scheduleId}/resume`, {},
      { headers: this.getAuthHeaders() }).pipe(
      tap(() => this.clearScheduleCache())
    );
  }

  // Helper method to get current progress synchronously
  getCurrentProgress(): UserProgress | null {
    return this.userProgressSubject.value;
  }

  /**
   * Get progress for current user, checking both database (if authenticated) and localStorage (if not)
   * This provides a unified way to check progress regardless of authentication state
   */
  getUnifiedProgress(): UserProgress {
    const databaseProgress = this.getCurrentProgress();

    if (databaseProgress) {
      // User is authenticated - use database progress
      return databaseProgress;
    } else {
      // User not authenticated - check if we have localStorage progress loaded in subject
      const subjectProgress = this.userProgressSubject.value;
      if (subjectProgress) {
        // We already loaded localStorage progress into the subject
        return subjectProgress;
      } else {
        // Fallback: build progress from localStorage directly
        return this.buildProgressFromLocalStorage();
      }
    }
  }

  // Session management methods
  getMinutesUntilExpiration(): number | null {
    return JwtTokenUtils.getMinutesUntilExpiration();
  }

  isSessionExpiringSoon(): boolean {
    const minutesLeft = this.getMinutesUntilExpiration();
    return minutesLeft !== null && minutesLeft <= 10; // Warn when 10 minutes left
  }

  /**
   * Get current user's email from stored JWT data
   */
  getCurrentUserEmail(): string | null {
    return localStorage.getItem('userEmail');
  }

  /**
   * Get current user's ID from stored JWT data
   */
  getCurrentUserId(): number | null {
    const userId = localStorage.getItem('userId');
    return userId ? parseInt(userId) : null;
  }

  /**
   * Get current user information
   */
  getCurrentUser(): { id: number | null; email: string | null } | null {
    const email = this.getCurrentUserEmail();
    const id = this.getCurrentUserId();

    if (!email && !id) {
      return null;
    }

    return { id, email };
  }

  /**
   * DEVELOPMENT ONLY: Authenticate as an existing user from the database
   */
  authenticateAsUser(email?: string, userHandle?: string): Observable<AuthResponse> {
    const payload = {
      email: email || null,
      userHandle: userHandle || null
    };

    // The backend only registers /api/dev/** in local/dev/test/ci, and requires this header there.
    // Builds pointed at a deployed backend ship an empty key, so dev auth simply 404s.
    const headers = this.getAuthHeaders().set('X-Dev-Tools-Key', resolveDevToolsKey());

    return this.http.post<AuthResponse>(`${BACKEND_API_URL}/dev/authenticate-as-user`, payload, {
      headers
    }).pipe(
      catchError(error => {
        console.error('[AuthService] Dev auth failed:', error?.status, error?.message);
        throw error;
      })
    );
  }

  /**
   * Convert base64url string to ArrayBuffer for WebAuthn API
   */
  private base64urlToArrayBuffer(base64url: string): ArrayBuffer {
    // Add padding if needed
    let base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');

    // Add padding
    while (base64.length % 4) {
      base64 += '=';
    }

    const binaryString = atob(base64);
    const bytes = new Uint8Array(binaryString.length);

    for (let i = 0; i < binaryString.length; i++) {
      bytes[i] = binaryString.charCodeAt(i);
    }

    return bytes.buffer;
  }

  private arrayBufferToBase64url(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    let binary = '';

    for (let i = 0; i < bytes.byteLength; i++) {
      binary += String.fromCharCode(bytes[i]);
    }

    return btoa(binary)
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=/g, '');
  }

  private credentialToJson(credential: PublicKeyCredential): any {
    const response = credential.response as AuthenticatorAssertionResponse;

    return {
      id: credential.id,
      rawId: this.arrayBufferToBase64url(credential.rawId),
      response: {
        authenticatorData: this.arrayBufferToBase64url(response.authenticatorData),
        clientDataJSON: this.arrayBufferToBase64url(response.clientDataJSON),
        signature: this.arrayBufferToBase64url(response.signature),
        userHandle: response.userHandle ? this.arrayBufferToBase64url(response.userHandle) : null
      },
      clientExtensionResults: credential.getClientExtensionResults(),
      type: credential.type
    };
  }

  applyReferralCode(code: string): Observable<any> {
    const url = `${BACKEND_API_URL}/user/referral/apply`;
    return this.http.post(url, { code }, { headers: this.getAuthHeaders() });
  }

  validateReferralCode(code: string): Observable<{ valid: boolean }> {
    const url = `${BACKEND_API_URL}/user/referral/validate?code=${encodeURIComponent(code)}`;
    return this.http.get<{ valid: boolean }>(url, { headers: this.getAuthHeaders() });
  }
}
