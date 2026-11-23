import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import {
  IonHeader, IonToolbar, IonContent,
  IonSpinner
} from '@ionic/angular/standalone';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { environment } from '../../../environments/environment';
import { PasskeyService } from '../../services/passkey.service';
import { NativePasskeyService } from '../../services/native-passkey.service';
import { KeychainSyncService } from '../../services/keychain-sync.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../../services/analytics/analytics-error.utils';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';
import { addIcons } from 'ionicons';
import { mailOutline, alertCircleOutline, shieldCheckmarkOutline } from 'ionicons/icons';
import { get } from '@github/webauthn-json';
import { firstValueFrom, timeout } from 'rxjs';

/** No request on this page may hang forever: there is no global HTTP timeout in the app. */
const REQUEST_TIMEOUT_MS = 30_000;
/** Safari's WebAuthn get() can hang without settling when it is not happy with the gesture. */
const PASSKEY_PROMPT_TIMEOUT_MS = 90_000;

/** Thrown when the passkey challenge could not be fetched, as opposed to the prompt being refused. */
class ChallengeUnavailableError extends Error {
  constructor(readonly cause: unknown) {
    super('Passkey challenge unavailable');
  }
}

/** What the passkey prompt hands back: the signed assertion and the challenge it answers. */
interface PasskeyProof {
  passkeySessionId: string;
  credential: unknown;
}

/**
 * Change of email, in two steps.
 *
 *   1. The user types the new address and passes the passkey prompt. The signed result of that
 *      prompt goes to the server WITH the request, and the server checks it. The prompt on its own
 *      proves nothing to the server, so it is never enough just to have shown it.
 *   2. The server sends a code to the new address. The email only changes once that code is
 *      entered here.
 */
@Component({
  selector: 'app-change-email',
  templateUrl: './change-email.page.html',
  styleUrls: ['./change-email.page.scss'],
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonHeader, IonToolbar, IonContent,
    IonSpinner,
    KeyboardAvoidDirective
  ]
})
export class ChangeEmailPage implements OnInit {
  currentEmail: string = '';
  newEmail: string = '';
  code: string = '';

  /** 'email' = typing the new address. 'code' = the code has been sent and is being entered. */
  step: 'email' | 'code' = 'email';

  isLoading = false;
  /** True once the change has gone through, so nothing can be tapped while the page leaves. */
  isDone = false;
  errorMessage: string = '';

  constructor(
    private router: Router,
    private http: HttpClient,
    private passkeyService: PasskeyService,
    private nativePasskeyService: NativePasskeyService,
    private keychainSyncService: KeychainSyncService,
    private toastService: ToastService,
    private navCtrl: NavController,
    private analytics: AnalyticsService
  ) {
    addIcons({ mailOutline, alertCircleOutline, shieldCheckmarkOutline });
  }

  goBack() {
    this.navCtrl.navigateBack('/security-settings');
  }

  ngOnInit() {
    const storedEmail = localStorage.getItem('userEmail');
    if (storedEmail) {
      this.currentEmail = storedEmail;
    }
  }

  isValidEmail(email: string): boolean {
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return emailRegex.test(email);
  }

  canSave(): boolean {
    return this.newEmail.length > 0 &&
           this.isValidEmail(this.newEmail) &&
           this.newEmail !== this.currentEmail;
  }

  canConfirm(): boolean {
    return /^\d{6}$/.test(this.code.trim());
  }

  /** Step 1: passkey prompt, then ask the server to send a code to the new address. */
  async initiateEmailChange() {
    if (!this.canSave() || this.isLoading) return;

    this.isLoading = true;
    this.errorMessage = '';

    let proof: PasskeyProof;
    try {
      proof = await this.getPasskeyProof();
    } catch (error: any) {
      console.error('Passkey check failed:', error);
      if (error instanceof ChallengeUnavailableError) {
        // The server or the network failed before any prompt was shown. That is not the user
        // declining to confirm who they are.
        this.showServerError(error.cause, 'We could not start the email change.');
        return;
      }
      this.isLoading = false;
      this.errorMessage = 'Confirm it is you with your passkey to change your email.';
      this.analytics.trackErrorShown('auth_required', 'my_profile', 'toast', null, false);
      this.toastService.showToast(this.errorMessage, 'danger');
      return;
    }

    try {
      await firstValueFrom(this.http.post<any>(
        `${environment.backendApiUrl}/user/email-change/start`,
        { newEmail: this.newEmail.trim(), ...proof },
        { headers: this.authHeaders() },
      ).pipe(timeout(REQUEST_TIMEOUT_MS)));
      this.isLoading = false;
      this.code = '';
      this.step = 'code';
    } catch (error: any) {
      this.showServerError(error, 'We could not start the email change.');
    }
  }

  /** Step 2: send the code back. The email changes only if it is right. */
  async confirmCode() {
    if (!this.canConfirm() || this.isLoading || this.isDone) return;

    this.isLoading = true;
    this.errorMessage = '';

    try {
      const response = await firstValueFrom(this.http.post<any>(
        `${environment.backendApiUrl}/user/email-change/confirm`,
        { code: this.code.trim() },
        { headers: this.authHeaders() },
      ).pipe(timeout(REQUEST_TIMEOUT_MS)));
      this.isDone = true;
      this.isLoading = false;

      // The session token names the user by email, so the server hands back a new one.
      localStorage.setItem('userEmail', response.newEmail);
      if (response.jwtToken) {
        JwtTokenUtils.storeJwtToken(response.jwtToken, undefined, response.newEmail);
      }
      this.currentEmail = response.newEmail;
      // The keychain copy is what a reinstall or a new phone uses to find the account.
      void this.keychainSyncService.storeAccountEmail(response.newEmail).catch(() => false);

      this.toastService.showToast('Email updated successfully', 'success');
      setTimeout(() => {
        this.router.navigate(['/security-settings']);
      }, 1500);
    } catch (error: any) {
      const serverCode = error?.error?.code;
      this.showServerError(error, 'We could not confirm that code.');
      // These mean the pending change is gone, so the only way on is to start over.
      if (['CODE_EXPIRED', 'TOO_MANY_ATTEMPTS', 'NO_PENDING_CHANGE', 'EMAIL_IN_USE'].includes(serverCode)) {
        this.startOver();
      }
    }
  }

  /** Back to typing the address. Any pending code is replaced when a new one is asked for. */
  startOver() {
    this.step = 'email';
    this.code = '';
  }

  private showServerError(error: any, fallback: string) {
    this.isLoading = false;
    console.error('Email change request failed:', error);
    this.errorMessage = error?.error?.message || fallback;
    this.analytics.trackErrorShown(
      isNetworkFailure(error) ? 'network' : 'save_failed',
      'my_profile',
      'toast',
      httpStatusOf(error),
      false,
    );
    this.toastService.showToast(this.errorMessage, 'danger');
  }

  private authHeaders(): HttpHeaders {
    const token = JwtTokenUtils.getValidJwtToken();
    let headers = new HttpHeaders();
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    return headers;
  }

  /**
   * Runs the passkey prompt (the native sheet on iOS, WebAuthn in a browser) and returns the
   * signed assertion WITHOUT finishing a sign-in. The server verifies it as part of the email
   * change request, which is what makes the check count.
   */
  private async getPasskeyProof(): Promise<PasskeyProof> {
    let startResponse: { requestOptions: string; sessionId: string };
    try {
      startResponse = await firstValueFrom((
        this.currentEmail
          ? this.passkeyService.startAuthenticationForUser(this.currentEmail)
          : this.passkeyService.startAuthentication()
      ).pipe(timeout(REQUEST_TIMEOUT_MS)));
    } catch (error) {
      throw new ChallengeUnavailableError(error);
    }

    const requestOptions: any = typeof startResponse.requestOptions === 'string'
      ? JSON.parse(startResponse.requestOptions)
      : startResponse.requestOptions;

    const publicKey = requestOptions.publicKey ?? requestOptions;
    if (!publicKey?.challenge) {
      throw new Error('Missing challenge in passkey options');
    }

    let credential: unknown;
    if (this.nativePasskeyService.isAvailable) {
      credential = await this.nativePasskeyService.authenticate({
        challenge: publicKey.challenge,
        rpId: publicKey.rpId ?? publicKey.rp?.id,
        userVerification: publicKey.userVerification,
        allowedCredentials: (publicKey.allowCredentials ?? []).map((c: any) => ({ id: c.id })),
      });
    } else {
      credential = await Promise.race([
        get(requestOptions.publicKey ? requestOptions : { publicKey: requestOptions }),
        new Promise<never>((_, reject) =>
          setTimeout(() => reject(new Error('PASSKEY_PROMPT_TIMEOUT')), PASSKEY_PROMPT_TIMEOUT_MS)),
      ]);
    }

    return { passkeySessionId: startResponse.sessionId, credential };
  }
}
