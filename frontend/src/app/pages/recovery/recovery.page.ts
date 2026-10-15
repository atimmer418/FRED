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
import { JwtTokenUtils } from '../../utils/jwt-token.utils';
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
  step: 'email' | 'otp' = 'email';
  email: string = '';
  otp: string = '';
  isLoading = false;

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

  async sendCode() {
    if (!this.email) return;

    this.isLoading = true;
    try {
      const response = await this.recoveryService.initiateRecovery(this.email);
      if (response.success) {
        this.step = 'otp';
        this.toastService.showToast('Recovery code sent. Check your email.');
      } else {
        this.toastService.showToast(response.message || 'Failed to initiate recovery.');
      }
    } catch (e) {
      this.toastService.showToast('An error occurred. Please try again.');
    } finally {
      this.isLoading = false;
    }
  }

  async verifyCode() {
    if (!this.otp) return;

    this.isLoading = true;
    try {
      const response = await this.recoveryService.verifyRecovery(this.email, this.otp);
      if (response.success && response.token) {
        JwtTokenUtils.storeJwtToken(response.token);

        // Kick off passkey registration — isLoading stays true through the OS prompt
        this.passkeyService.startRecoveryRegistration().subscribe({
          next: async (startResponse) => {
            try {
              const optionsAsObject = JSON.parse(startResponse.options);
              const credential = await create({ publicKey: optionsAsObject });

              this.passkeyService.finishRegistration({ email: this.email, credential }).subscribe({
                next: (finishResponse) => {
                  if (finishResponse.success) {
                    this.authService.handleSuccessfulAuthentication(finishResponse.jwtToken!, finishResponse.userId!, finishResponse.email!);
                    this.isLoading = false;
                    this.router.navigate(['/tabs/tab3']);
                  } else {
                    this.toastService.showToast(finishResponse.message || 'Passkey registration failed. Please try again.');
                    this.isLoading = false;
                  }
                },
                error: () => {
                  this.toastService.showToast('Passkey registration failed. Please try again.');
                  this.isLoading = false;
                }
              });
            } catch {
              // User cancelled the OS passkey prompt or WebAuthn failed
              this.toastService.showToast('Passkey registration was cancelled. Please try again.');
              this.isLoading = false;
            }
          },
          error: () => {
            this.toastService.showToast('Could not start passkey registration. Try again.');
            this.isLoading = false;
          }
        });
      } else {
        this.toastService.showToast(response.message || 'Verification failed.');
        this.isLoading = false;
      }
    } catch {
      this.toastService.showToast('Invalid code or error occurred.', 'danger');
      this.isLoading = false;
    }
  }
}
