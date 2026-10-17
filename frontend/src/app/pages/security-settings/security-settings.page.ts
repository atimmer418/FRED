import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { NavController } from '@ionic/angular';
import { environment } from '../../../environments/environment';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';
import {
  IonHeader, IonToolbar, IonContent,
  IonList, IonItem, IonToggle,
  IonSpinner
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  shieldCheckmarkOutline, phonePortraitOutline,
  lockClosedOutline, mailOutline, personOutline, trashOutline,
  addCircleOutline, logOutOutline, alertCircleOutline, checkmarkCircle,
  cardOutline, copyOutline
} from 'ionicons/icons';

interface UserSession {
  id: number;
  deviceInfo: string;
  location: string;
  active: boolean;
  lastActive: string;
  createdAt: string;
}

interface SessionsResponse {
  currentSessionId: number;
  sessions: UserSession[];
}

import { AppLockService } from '../../services/app-lock.service';
import { PinService } from '../../services/pin.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsConsentService } from '../../services/analytics/analytics-consent.service';

@Component({
  selector: 'app-security-settings',
  templateUrl: './security-settings.page.html',
  styleUrls: ['./security-settings.page.scss'],
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    IonHeader, IonToolbar, IonContent,
    IonList, IonItem, IonToggle,
    IonSpinner
  ]
})
export class SecuritySettingsPage implements OnInit {
  // Three distinct states:
  //   checkingStepUp=true  → neutral loading spinner (neither overlay nor settings)
  //   checkingStepUp=false, isAuthenticated=false → step-up verify overlay
  //   checkingStepUp=false, isAuthenticated=true  → loaded settings
  checkingStepUp = true;
  isAuthenticated = false;

  // Settings State
  appLockEnabled = false;
  sensitiveAuthEnabled = false; // Default to false until loaded
  /** Mirrors users.analytics_opt_out. The toggle is phrased positively, so it is the inverse. */
  usageDataEnabled = false;
  savingUsageData = false;
  userEmail: string = '';

  // Real Data
  sessions: UserSession[] = [];
  currentSessionId: number = -1;
  alpacaAccountNumber: string | null = null;

  constructor(
    private router: Router,
    private http: HttpClient,
    private appLockService: AppLockService,
    private pinService: PinService,
    private toastService: ToastService,
    private navCtrl: NavController,
    private analyticsConsent: AnalyticsConsentService
  ) {
    addIcons({
      shieldCheckmarkOutline, phonePortraitOutline,
      lockClosedOutline, mailOutline, personOutline, trashOutline,
      addCircleOutline, logOutOutline, alertCircleOutline, checkmarkCircle,
      cardOutline, copyOutline
    });
  }

  async ngOnInit() {
    const storedEmail = localStorage.getItem('userEmail');
    if (storedEmail) {
      this.userEmail = storedEmail;
    }

    // Sync App Lock state
    this.appLockEnabled = this.appLockService.isEnabled();

    // Analytics consent comes from the server with the session payload; AuthService has already
    // applied it by the time this page is reachable.
    this.usageDataEnabled = !this.analyticsConsent.isOptedOut();

    // Check if PIN is set (Sensitive Auth / step-up enabled).
    // checkingStepUp=true while this resolves — the template shows only a
    // neutral spinner during this window, preventing any flash of either the
    // verify overlay or the settings content.
    this.sensitiveAuthEnabled = await this.pinService.hasPin();

    if (this.sensitiveAuthEnabled) {
      // Step-up is enabled: show verify overlay + prompt for PIN.
      this.checkingStepUp = false;
      const verified = await this.pinService.promptPin('verify');
      if (!verified) {
        // User cancelled or failed PIN — route back without loading settings.
        this.router.navigate(['/tabs/tab3']);
        return;
      }
    } else {
      // No step-up PIN — go straight to settings, never showing the overlay.
      this.checkingStepUp = false;
    }

    this.isAuthenticated = true;
    this.loadSessions();
    this.syncAlpacaAccountNumber();
  }

  loadSessions() {
    const token = JwtTokenUtils.getValidJwtToken();
    if (!token) return;

    const headers = new HttpHeaders({
      'Authorization': `Bearer ${token}`
    });

    this.http.get<SessionsResponse>(`${environment.backendApiUrl}/user/sessions`, { headers }).subscribe({
      next: (data) => {
        this.sessions = data.sessions;
        this.currentSessionId = data.currentSessionId;
      },
      error: (err) => {
        console.error('Failed to load sessions', err);
      }
    });
  }

  syncAlpacaAccountNumber() {
    const token = JwtTokenUtils.getValidJwtToken();
    if (!token) return;

    const headers = new HttpHeaders({
      'Authorization': `Bearer ${token}`
    });

    this.http.post<any>(`${environment.backendApiUrl}/alpaca/sync-account-number`, {}, { headers }).subscribe({
      next: (response) => {
        if (response && response.account_number) {
          this.alpacaAccountNumber = response.account_number;
          console.log('Synced Alpaca Account Number:', this.alpacaAccountNumber);
        }
      },
      error: (err) => {
        console.warn('Failed to sync Alpaca account number', err);
        // Silent fail - user might not have an account yet
      }
    });
  }

  async copyAccountNumber() {
    if (this.alpacaAccountNumber) {
      await navigator.clipboard.writeText(this.alpacaAccountNumber);
      this.toastService.showToast('Account number copied to clipboard', 'success');
    }
  }

  /**
   * Writes consent to the server. On failure the switch goes back to what the server still holds,
   * so the control never shows a state that was not saved.
   */
  toggleUsageData() {
    const requested = this.usageDataEnabled;
    this.savingUsageData = true;

    this.analyticsConsent.setOptOut(!requested).subscribe({
      next: (optOut) => {
        this.usageDataEnabled = !optOut;
        this.savingUsageData = false;
      },
      error: () => {
        this.usageDataEnabled = !this.analyticsConsent.isOptedOut();
        this.savingUsageData = false;
        this.toastService.showToast('Could not save that. Please try again.', 'danger');
      },
    });
  }

  toggleAppLock() {
    this.appLockService.setEnabled(this.appLockEnabled);
    console.log('App lock toggled:', this.appLockEnabled);
  }

  async toggleSensitiveAuth() {
    if (this.sensitiveAuthEnabled) {
      // User turned it ON
      const success = await this.pinService.promptPin('create');
      if (success) {
        // PIN was created — set the localStorage marker so hasPin() is
        // fail-closed even if the next network call errors.
        this.pinService.markStepUpEnabled();
        this.presentToast('Sensitive Action PIN enabled.');
      } else {
        // Creation cancelled
        this.sensitiveAuthEnabled = false;
      }
    } else {
      // User turned it OFF
      await this.pinService.deletePin();
      // deletePin() already clears the marker internally.
      this.presentToast('Sensitive Action PIN disabled.');
    }
  }

  async presentToast(message: string) {
    this.toastService.showToast(message);
  }

  revokeSession(id: number) {
    const token = JwtTokenUtils.getValidJwtToken();
    if (!token) return;

    const headers = new HttpHeaders({
      'Authorization': `Bearer ${token}`
    });

    this.http.post(`${environment.backendApiUrl}/user/sessions/${id}/revoke`, {}, { headers }).subscribe({
      next: () => {
        this.sessions = this.sessions.filter(s => s.id !== id);
      },
      error: (err) => {
        console.error('Failed to revoke session', err);
      }
    });
  }

  goBack() {
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  changeEmail() {
    this.router.navigate(['/change-email']);
  }

  editKyc() {
    this.router.navigate(['/kyc-verification'], { queryParams: { edit: 'true' } });
  }
}
