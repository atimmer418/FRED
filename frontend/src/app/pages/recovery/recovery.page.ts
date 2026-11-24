import { Component } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  IonHeader, IonToolbar, IonContent, IonInput, IonButton,
  IonIcon, IonSpinner
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { shieldCheckmarkOutline, keyOutline, mailOutline } from 'ionicons/icons';
import { RecoveryService } from '../../services/recovery.service';
import { ToastService } from '../../services/toast.service';
import { PasskeyService } from '../../services/passkey.service';
import { AuthService } from '../../services/auth.service';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { create } from '@github/webauthn-json';

@Component({
  selector: 'app-recovery',
  templateUrl: './recovery.page.html',
  styleUrls: ['./recovery.page.scss'],
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonHeader, IonToolbar, IonContent, IonInput, IonButton,
    IonIcon, IonSpinner,
    KeyboardAvoidDirective
  ]
})
export class RecoveryPage {
  step: 'email' | 'otp' | 'passkey' = 'email';
  email: string = '';
  otp: string = '';
  isLoading = false;

  /**
   * From the server once the code is accepted. It is the only thing that lets this device register
   * the new passkey. Kept in memory only: it is good for 15 minutes and one passkey.
   */
  private recoveryToken: string | null = null;

  constructor(
    private recoveryService: RecoveryService,
    private router: Router,
    private toastService: ToastService,
    private location: Location,
    private passkeyService: PasskeyService,
    private authService: AuthService
  ) {
    addIcons({ shieldCheckmarkOutline, keyOutline, mailOutline });
  }

  goBack() {
    this.location.back();
  }

  get isEmailValid(): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.email || '');
  }

  /** The server's own words when it refuses on purpose (too many tries, wrong code, recovery off). */
  private refusalMessage(error: any): string | null {
    const message = error?.error?.message;
    const refused = [401, 429, 503].includes(error?.status);
    return refused && typeof message === 'string' && message ? message : null;
  }

  async sendCode() {
    if (!this.email || this.isLoading) return;

    this.isLoading = true;
    try {
      const response = await this.recoveryService.initiateRecovery(this.email);
      if (response.success) {
        this.step = 'otp';
        this.toastService.showToast('Recovery code sent. Check your email.');
      } else {
        this.toastService.showToast(response.message || 'Failed to initiate recovery.', 'danger');
      }
    } catch (e) {
      this.toastService.showToast(this.refusalMessage(e) ?? 'An error occurred. Please try again.', 'danger');
    } finally {
      this.isLoading = false;
    }
  }

  async verifyCode() {
    if (!this.otp || this.isLoading) return;

    this.isLoading = true;
    try {
      const response = await this.recoveryService.verifyRecovery(this.email, this.otp);
      if (response.success && response.recoveryToken) {
        // The code is used up now. From here on only the recovery token matters.
        this.recoveryToken = response.recoveryToken;
        this.otp = '';
        this.step = 'passkey';
        this.isLoading = false;
        this.createPasskey();
      } else {
        this.toastService.showToast(response.message || 'Verification failed.', 'danger');
        this.isLoading = false;
      }
    } catch (e) {
      this.toastService.showToast(this.refusalMessage(e) ?? 'Invalid code or error occurred.', 'danger');
      this.isLoading = false;
    }
  }

  /**
   * Registers the new passkey. Runs once on its own after the code is accepted, and again from the
   * button if the passkey prompt was cancelled or failed. isLoading stays true through the OS prompt.
   */
  createPasskey() {
    if (this.isLoading) return;
    const recoveryToken = this.recoveryToken;
    if (!recoveryToken) {
      this.startOver();
      return;
    }

    this.isLoading = true;
    this.passkeyService.startRecoveryRegistration(recoveryToken).subscribe({
      next: async (startResponse) => {
        try {
          const optionsAsObject = JSON.parse(startResponse.options);
          const credential = await this.promptForPasskey(optionsAsObject);

          this.passkeyService.finishRegistration({ email: this.email, credential, recoveryToken }).subscribe({
            next: (finishResponse) => {
              if (finishResponse.success) {
                this.recoveryToken = null;
                this.authService.handleSuccessfulAuthentication(finishResponse.jwtToken!, finishResponse.userId!, finishResponse.email!);
                this.isLoading = false;
                this.router.navigate(['/tabs/tab3']);
              } else {
                this.toastService.showToast(finishResponse.message || 'Passkey registration failed. Please try again.', 'danger');
                this.isLoading = false;
              }
            },
            error: () => {
              this.toastService.showToast('Passkey registration failed. Please try again.', 'danger');
              this.isLoading = false;
            }
          });
        } catch {
          // User cancelled the OS passkey prompt or WebAuthn failed
          this.toastService.showToast('Passkey registration was cancelled. Please try again.', 'danger');
          this.isLoading = false;
        }
      },
      error: (e) => {
        this.isLoading = false;
        if (e?.status === 401) {
          // The recovery token ran out (15 minutes, or too many tries). Nothing to retry with.
          this.toastService.showToast('Your recovery session has ended. Please start again.', 'danger');
          this.startOver();
        } else if (e?.status === 403) {
          // The account already has its new passkey (an earlier try went through after all).
          // Starting over would wipe it, so say so and leave things as they are.
          this.toastService.showToast('This account already has a passkey. Go back and sign in with it.', 'danger');
        } else {
          this.toastService.showToast('Could not start passkey registration. Try again.', 'danger');
        }
      }
    });
  }

  /** The OS passkey prompt. Its own method so a test can stand in for the device. */
  protected promptForPasskey(publicKey: any): Promise<any> {
    return create({ publicKey });
  }

  /** Back to the first step with nothing kept. */
  startOver() {
    this.recoveryToken = null;
    this.otp = '';
    this.step = 'email';
  }
}
