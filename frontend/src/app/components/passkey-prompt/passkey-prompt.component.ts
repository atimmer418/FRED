import { Component, Input, AfterViewInit, OnDestroy, ViewChild, ElementRef, NgZone } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ModalController } from '@ionic/angular/standalone';
import { PasskeyService } from '../../services/passkey.service';
import { get } from '@github/webauthn-json';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { NativePasskeyService } from '../../services/native-passkey.service';
import { timeout } from 'rxjs';
import { AccountClosedService } from '../../services/account-closed.service';

@Component({
  selector: 'app-passkey-prompt',
  templateUrl: './passkey-prompt.component.html',
  styleUrls: ['./passkey-prompt.component.scss'],
  standalone: true,
  imports: [CommonModule]
})
export class PasskeyPromptComponent implements AfterViewInit, OnDestroy {

  @Input() userEmail?: string;
  @Input() jwtExpired = false;
  /** True only when this is the app lock screen (not a step-up prompt inside the app). */
  @Input() lockScreen = false;
  /** How many times running the server said it has no record of the passkey offered. */
  private unknownPasskeyTries = 0;
  @Input() onLottieReady?: () => void;
  @Input() onReauthSuccess?: () => void;

  @ViewChild('lottieContainer') lottieContainer!: ElementRef<HTMLElement>;

  isUnlocking = false;
  isFounder = false;
  private unlockButton: HTMLButtonElement | null = null;
  // Bumped whenever a new fade takes over the button; in-flight rAF fade loops
  // check it and stop, so opposing fades can never fight over opacity.
  private fadeEpoch = 0;
  private static readonly FADE_MS = 350;
  // How long the cancelled passkey half-sheet takes to clear the button's
  // position. A fade-in started before this plays underneath the sheet and is
  // invisible. On-device (2026-07-03): 450 fades ✓, 350 and 0 don't ✗ — the
  // real dismissal is somewhere in (350, 450]. Don't trim without retesting.
  private static readonly SHEET_DISMISS_MS = 450;

  constructor(
    private modalController: ModalController,
    private passkeyService: PasskeyService,
    private authService: AuthService,
    private toastService: ToastService,
    private nativePasskeyService: NativePasskeyService,
    private zone: NgZone,
    private accountClosed: AccountClosedService
  ) {
    this.authService.userProgress$.subscribe(progress => {
      this.isFounder = progress?.privateBeta === true;
    });
  }

  ngOnDestroy() {
    this.destroyUnlockButton();
  }

  ngAfterViewInit() {
    const lottie = (window as any).lottie;

    // Called exactly once regardless of which path triggers it (DOMLoaded, error, or timeout).
    let fired = false;
    const startAuth = () => {
      if (fired) return;
      fired = true;
      this.zone.run(() => {
        this.onLottieReady?.();
        this.authenticate();
      });
    };

    if (!lottie) {
      // Lottie library not yet loaded — unblock native overlay and proceed with auth.
      setTimeout(startAuth, 400);
      return;
    }

    try {
      const anim = lottie.loadAnimation({
        container: this.lottieContainer.nativeElement,
        path: 'assets/lottie/coin-drop.json',
        renderer: 'svg',
        loop: true,
        autoplay: true,
        rendererSettings: { preserveAspectRatio: 'xMidYMid slice' },
      });

      // RAF runs inside Angular's zone because Zone.js patches requestAnimationFrame.
      anim.addEventListener('DOMLoaded', () => requestAnimationFrame(startAuth));
      // On iOS, DOMLoaded can silently fail to fire if the container has zero dimensions
      // at animation-creation time (modal not yet presented). Fire startAuth on any
      // Lottie error so authenticate() is never permanently blocked.
      anim.addEventListener('data_failed', startAuth);
      anim.addEventListener('error', startAuth);
    } catch (e) {
      console.error('[PasskeyPrompt] lottie.loadAnimation failed', e);
    }

    // Hard safety net: if neither DOMLoaded nor an error fires within 2 s, start auth
    // anyway. Protects against any Lottie/WebKit rendering edge case.
    setTimeout(startAuth, 2000);
  }

  /**
   * Appends the Unlock pill to document.body so it is completely outside the
   * Ionic modal's stacking/compositing context. Lottie's GPU-composited SVG
   * layers can sit above any z-index inside the modal; appending to body
   * sidesteps that entirely.
   *
   * On subsequent calls (retry after another failed biometric), the existing
   * button is re-enabled and faded back in rather than re-created.
   *
   * All fades go through the Web Animations API, not CSS transitions: WebKit
   * won't arm a transition on an element that was never rendered at the start
   * value, and the global reduced-motion rule (global.scss `transition-duration:
   * 0.01ms !important`) zeroes CSS transitions outright. animate() has
   * programmatic timing that neither can touch — and an opacity cross-fade is
   * the reduced-motion-safe effect anyway.
   */
  private revealUnlockButton(): void {
    this.zone.run(() => {
      this.isUnlocking = false;
      const btn = this.unlockButton ?? this.createUnlockButton();
      const epoch = ++this.fadeEpoch;
      const reveal = () => {
        if (this.unlockButton !== btn || this.fadeEpoch !== epoch) return;
        btn.style.pointerEvents = 'auto';
        btn.disabled = false;
        console.log('[UnlockFade] reveal fired, starting rAF fade-in');
        // Manual rAF-driven fade, NOT WAAPI/CSS: accelerated opacity animations
        // that start from a never-painted opacity:0 state render nothing on
        // this WKWebView intermittently — the animation "plays" but the button
        // pops. Per-frame inline style writes cannot be skipped, and anchoring
        // t=0 on the first delivered frame self-corrects if rendering is still
        // paused while the passkey sheet dismisses.
        this.zone.runOutsideAngular(() => {
          let start: number | null = null;
          let frames = 0;
          const step = (ts: number) => {
            if (this.unlockButton !== btn || this.fadeEpoch !== epoch) return;
            if (start === null) {
              start = ts;
              console.log('[UnlockFade] first frame delivered');
            }
            frames++;
            const t = Math.min((ts - start) / PasskeyPromptComponent.FADE_MS, 1);
            btn.style.opacity = String(t * (2 - t)); // ease-out
            if (t < 1) {
              requestAnimationFrame(step);
            } else {
              console.log(`[UnlockFade] fade-in complete (${frames} frames)`);
            }
          };
          requestAnimationFrame(step);
        });
      };
      // The fade-in duration is FADE_MS, identical to the fade-out — but on
      // native it cannot START until the sheet has uncovered the button's
      // position (top:70%), or the whole animation plays hidden behind it
      // (verified on-device 2026-07-03: no-delay reads as "no fade at all").
      console.log('[UnlockFade] reveal scheduled, delay:', this.nativePasskeyService.isAvailable ? PasskeyPromptComponent.SHEET_DISMISS_MS : 0);
      setTimeout(reveal, this.nativePasskeyService.isAvailable ? PasskeyPromptComponent.SHEET_DISMISS_MS : 0);
    });
  }

  private createUnlockButton(): HTMLButtonElement {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = 'Unlock';
    btn.style.cssText = [
      'position:fixed',
      'left:50%',
      'top:70%',
      'transform:translate(-50%,-50%)',
      'z-index:2147483647',
      'min-width:200px',
      'height:52px',
      'padding:0 32px',
      'border:none',
      'border-radius:999px',
      'background:#2563EB',
      'color:#ffffff',
      "font-family:'Manrope',sans-serif",
      'font-weight:600',
      'font-size:1rem',
      'display:inline-flex',
      'align-items:center',
      'justify-content:center',
      'cursor:pointer',
      'box-shadow:0 4px 12px rgba(37,99,235,0.25)',
      '-webkit-tap-highlight-color:transparent',
      '-webkit-user-select:none',
      'user-select:none',
      'opacity:0',
      'pointer-events:none',
    ].join(';');
    btn.addEventListener('click', () => this.zone.run(() => this.authenticate()));
    document.body.appendChild(btn);
    this.unlockButton = btn;
    return btn;
  }

  private destroyUnlockButton(): void {
    const btn = this.unlockButton;
    this.unlockButton = null;
    this.fadeEpoch++; // halt any in-flight fade-in loop
    if (!btn) return;
    btn.disabled = true;
    btn.style.pointerEvents = 'none';
    const from = parseFloat(getComputedStyle(btn).opacity) || 0;
    btn.style.opacity = '0';
    if (from === 0) {
      // Not visible (e.g. success right after tapping Unlock) — nothing to fade.
      btn.remove();
      return;
    }
    const remove = () => btn.remove();
    btn.animate([{ opacity: from }, { opacity: 0 }], { duration: PasskeyPromptComponent.FADE_MS, easing: 'ease' }).onfinish = remove;
    // onfinish can be swallowed (webview backgrounded mid-fade); the button
    // outlives this component on document.body, so guarantee removal either way.
    setTimeout(remove, PasskeyPromptComponent.FADE_MS + 250);
  }

  async authenticate() {
    if (this.isUnlocking) return;
    this.isUnlocking = true;
    if (this.unlockButton) {
      // Fade the button out while the passkey sheet is up. A cancel fades it
      // back in via revealUnlockButton(); success removes it while invisible.
      this.fadeEpoch++; // halt any in-flight fade-in loop
      const btn = this.unlockButton;
      btn.disabled = true;
      btn.style.pointerEvents = 'none';
      const from = parseFloat(getComputedStyle(btn).opacity) || 1;
      btn.style.opacity = '0';
      console.log('[UnlockFade] tap: WAAPI fade-out from', from);
      btn.animate([{ opacity: from }, { opacity: 0 }], { duration: PasskeyPromptComponent.FADE_MS, easing: 'ease' });
    }

    // Always use the full FIDO2 ceremony (ASAuthorizationController on iOS, WebAuthn on web).
    // This guarantees the "Use passkey" sheet always appears, avoids the LAContext biometric
    // session cache (which can silently succeed with no visible UI), and always issues a fresh
    // JWT from the backend regardless of whether the current token is expired or still valid.
    const start$ = this.userEmail
      ? this.passkeyService.startAuthenticationForUser(this.userEmail)
      : this.passkeyService.startAuthentication();

    // 30-second hard cap on the challenge-fetch HTTP call. Without this, a slow or
    // unreachable backend (e.g. Cloudflare tunnel from a remote device, dev server
    // restart) causes the request to hang indefinitely — isUnlocking stays true, the
    // Lottie plays forever, and the Unlock button never appears.
    start$.pipe(timeout(30_000)).subscribe({
      next: async (response) => {
        try {
          let requestOptions;
          if (typeof response.requestOptions === 'string') {
            requestOptions = JSON.parse(response.requestOptions);
          } else {
            requestOptions = response.requestOptions;
          }

          // Handle structure where challenge is inside publicKey
          const challenge = requestOptions.challenge || (requestOptions.publicKey && requestOptions.publicKey.challenge);

          // Ensure challenge exists
          if (!challenge) {
            throw new Error('Missing key: challenge in request options');
          }

          const publicKey = requestOptions.publicKey ?? requestOptions;
          const rpId = publicKey.rpId ?? publicKey.rp?.id;
          const userVerification = publicKey.userVerification;
          const allowedCredentials = (publicKey.allowCredentials ?? []).map((c: any) => ({ id: c.id }));

          let credential: any;
          if (this.nativePasskeyService.isAvailable) {
            credential = await this.nativePasskeyService.authenticate({
              challenge,
              rpId,
              userVerification,
              allowedCredentials
            });
          } else {
            // Race the WebAuthn get() against a 90-second timeout.  On Safari,
            // get() called without a prior user gesture can hang indefinitely
            // (never resolves, never rejects), keeping isUnlocking=true and
            // silently blocking every subsequent button click.  The timeout
            // aborts the hang and reveals the unlock button so the user can retry
            // with a real click (which satisfies Safari's user-gesture requirement).
            const getOptions = requestOptions.publicKey ? requestOptions : { publicKey: requestOptions };
            credential = await Promise.race([
              get(getOptions as any),
              new Promise<never>((_, reject) =>
                setTimeout(() => reject(new Error('AUTH_TIMEOUT')), 90_000)
              )
            ]);
          }

          // Send the credential back to the server
          this.passkeyService.finishAuthentication(credential, response.sessionId).subscribe({
            next: (finishResponse) => {
              if (finishResponse.success) {
                // Always update auth state — stores fresh JWT, sets isLoggedIn, loads DB progress.
                // Needed whether JWT was expired (resumes navigation) or still valid (refreshes token).
                this.destroyUnlockButton();
                this.authService.handleSuccessfulAuthentication(
                  finishResponse.jwtToken,
                  finishResponse.userId,
                  finishResponse.email
                );
                // Re-raise the launch cover WHILE this modal still covers the screen, so there is
                // no blank frame between the (instant, animated:false) modal dismiss and the
                // destination route painting. lockApp() hid the launch cover to present this modal
                // and nothing else restores it; the FRED-205 cover-gate then lifts it once the
                // destination (tab1's portfolio) actually paints.
                this.onReauthSuccess?.();
                this.modalController.dismiss({ authenticated: true });
              } else {
                // this.showToast('Authentication failed: ' + finishResponse.message, 'danger');
                this.revealUnlockButton();
              }
            },
            error: (err) => {
              console.error('Passkey finish error', err);
              if (this.lockScreen && err?.status === 401 && err?.error?.code === 'PASSKEY_NOT_REGISTERED') {
                // The server has no record of this passkey. Once could be a slip: a phone can
                // hold an old FRED passkey next to the current one, so the user gets Unlock back
                // and can pick the other. Twice running means nothing here will unlock, and
                // showing Unlock for ever would trap them, so the device is signed out and shown
                // the screen that explains it. (A step-up prompt inside the app is left alone:
                // there the user is signed in and simply picked the wrong passkey.)
                this.unknownPasskeyTries++;
                if (this.unknownPasskeyTries >= 2) {
                  void this.accountClosed.leave('passkey');
                  return;
                }
              } else {
                this.unknownPasskeyTries = 0;
              }
              // this.showToast('Authentication failed. Please try again.', 'danger');
              this.revealUnlockButton();
            }
          });
        } catch (error: any) {
          if (error?.message !== 'AUTH_TIMEOUT') {
            // this.showToast('Authentication failed. Please try again.', 'danger');
          }
          this.revealUnlockButton();
        }
      },
      error: (err) => {
        console.error('Passkey start error', err);
        this.showToast('Could not start authentication.', 'danger');
        this.revealUnlockButton();
      }
    });
  }

  async showToast(message: string, color: string) {
    this.toastService.showToast(message, color);
  }
}
