import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Subject, takeUntil, catchError, tap, of } from 'rxjs';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { clearPlaidLinkSession, PlaidLinkMode, plaidOAuthStateId, readPlaidLinkMode, readPlaidLinkReturn, readPlaidLinkToken, readPlaidOAuthReturnHref, storePlaidLinkSession } from '../utils/plaid-link-session';
import { UpdateModeCompleteResponse } from '../models/primary-bank-account';
import { PlaidService } from '../services/plaid.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import {
  IonHeader,
  IonToolbar,
  IonContent,
  IonSpinner
} from '@ionic/angular/standalone';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../services/analytics/analytics-error.utils';

declare var Plaid: any;

const BACKEND_API_URL = environment.backendApiUrl;

/** The server's answer when Plaid refuses an update-mode token (PlaidController create_link_token, spec 8.8). */
function isUpdateModeUnavailable(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 409 && error.error?.code === 'UPDATE_MODE_UNAVAILABLE';
}

@Component({
  selector: 'app-change-bank-account',
  templateUrl: './change-bank-account.page.html',
  styleUrls: ['./change-bank-account.page.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonHeader,
    IonToolbar,
    IonContent,
    IonSpinner
  ]
})
export class ChangeBankAccountPage implements OnInit, OnDestroy {
  
  private destroy$ = new Subject<void>();
  
  isLoading = false;
  isPlaidReady = false;
  statusMessage = '';
  currentBankAccount: any = null;
  
  private plaidHandler: any;
  private plaidSubscription: any;
  private currentLinkToken: string | null = null;
  /** The mode the current token was created with; decides what onSuccess does (spec 2.3). */
  private linkMode: PlaidLinkMode = 'link';

  constructor(
    private router: Router,
    private http: HttpClient,
    private toastService: ToastService,
    private plaidService: PlaidService,
    private passkeyService: PasskeyService,
    private pinService: PinService,
    private navCtrl: NavController,
    private analytics: AnalyticsService
  ) {}

  ngOnInit() {
    this.loadCurrentBankAccount();
    this.loadPlaidScript();
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
    
    if (this.plaidSubscription) {
      this.plaidSubscription.unsubscribe();
    }
  }

  goBack() {
    // Refresh the PlaidService data to ensure Tab3 gets the latest bank account info
    this.plaidService.refreshBankAccountData();
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  private async loadCurrentBankAccount() {
    try {
      const headers = this.getAuthHeaders();
      const response = await this.http.get(`${BACKEND_API_URL}/plaid/primary-bank-account`, { headers }).toPromise();
      this.currentBankAccount = response;
    } catch (error) {
      console.error('Error loading current bank account:', error);
    }
  }

  private loadPlaidScript(): void {
    if (typeof Plaid !== 'undefined') {
      this.initializePlaidLink();
      return;
    }

    const script = document.createElement('script');
    script.src = 'https://cdn.plaid.com/link/v2/stable/link-initialize.js';
    script.onload = () => {
      this.initializePlaidLink();
    };
    script.onerror = () => {
      // The CDN script never loaded, so this is the network rather than the server. The banner has
      // a Try again button, but it stays disabled while isPlaidReady is false, which it is here.
      this.analytics.trackErrorShown('network', 'portfolio', 'inline_banner', null, false);
      this.statusMessage = 'Failed to load Plaid script. Please check your internet connection.';
    };
    document.head.appendChild(script);
  }

  private async initializePlaidLink(): Promise<void> {
    this.isLoading = true;
    this.isPlaidReady = false;
    this.statusMessage = 'Initializing secure connection...';

    try {
      // Back from the bank's OAuth page: Link must resume with the SAME link_token it left with.
      // onboarding and change-bank share one sessionStorage slot, so a token left behind by the
      // other flow must never be reused here: the return marker has to match this page too.
      const returnHref = this.oauthReturnHref();
      const storedToken = returnHref ? readPlaidLinkToken() : null;
      const storedReturn = returnHref ? readPlaidLinkReturn() : null;
      const resuming = returnHref !== null && storedToken !== null && storedReturn === 'change-bank';
      if (returnHref !== null && storedToken !== null && !resuming) {
        // Stale token from a different flow (or a corrupted marker): discard it and fetch a fresh
        // one below rather than ever treating it as proof this page's step-up gate already passed.
        clearPlaidLinkSession();
      }
      let linkToken: string;
      if (resuming) {
        linkToken = storedToken as string;
        // The OAuth return URL carries oauth_state_id, not ?mode, so the mode comes back with the session.
        this.linkMode = readPlaidLinkMode();
      } else {
        const linkTokenData = await this.requestLinkToken(this.requestedLinkMode());
        linkToken = linkTokenData.link_token;
        if (!linkToken) {
          throw new Error('Failed to retrieve link_token from backend.');
        }
        // Not stored yet: a stored token must only ever exist once openPlaid has passed the PIN
        // step-up, so the OAuth return can trust it and skip the gate.
      }
      this.currentLinkToken = linkToken;

      const config: any = {
        token: linkToken,
        onSuccess: (public_token: string, metadata: any) => {
          clearPlaidLinkSession();
          console.log('Plaid Link success! Public Token:', public_token, 'Metadata:', metadata);
          this.statusMessage = 'Processing...';
          this.isLoading = true;
          if (this.linkMode === 'update') {
            // Same Item, same access token: nothing to exchange. The server re-reads consent (spec 2.3).
            this.completeUpdateMode();
          } else {
            this.exchangePublicTokenAuthenticated(public_token);
          }
        },
        onLoad: () => {
          console.log('Plaid Link UI loaded.');
          this.isLoading = false;
          this.isPlaidReady = true;
          this.statusMessage = this.linkMode === 'update' ? 'Ready to reconnect your bank.' : 'Ready to change your bank account.';
        },
        onExit: (err: any, metadata: any) => {
          clearPlaidLinkSession();
          this.isLoading = false;
          this.isPlaidReady = true;
          if (err != null) {
            console.error('Plaid Link exited with error:', err, metadata);
            // Only the error exit is recorded. A plain close carries no err and is not a failure.
            this.analytics.trackErrorShown('bank_link', 'portfolio', 'inline_banner', null, false);
            this.statusMessage = `Link flow exited: ${err.display_message || err.error_message || 'User closed.'}`;
          } else {
            console.log('Plaid Link exited by user.', metadata);
            this.statusMessage = 'Link flow closed by user.';
          }
        },
        onEvent: (eventName: string, metadata: any) => {
          console.log('Plaid Link event:', eventName, metadata);
        }
      };
      if (resuming) {
        config.receivedRedirectUri = returnHref;
      }
      this.plaidHandler = Plaid.create(config);
      if (resuming) {
        // A stored token only exists because openPlaid already passed the PIN step-up before Link
        // left for the bank, so it is safe to reopen straight away. Consume it now so a stale
        // oauth_state_id still in the URL cannot later pair with a fresh, different token.
        clearPlaidLinkSession();
        this.plaidHandler.open();
      }

    } catch (error: any) {
      console.error('Error initializing Plaid Link:', error);
      this.analytics.trackErrorShown(
        isNetworkFailure(error) ? 'network' : 'bank_link',
        'portfolio',
        'inline_banner',
        httpStatusOf(error),
        false,
      );
      this.statusMessage = `Error: ${error.message || 'Could not initialize bank linking.'}`;
      this.isLoading = false;
    }
  }

  async openPlaid(): Promise<void> {
    if (this.plaidHandler) {
      // Step-up authentication check
      const hasPin = await this.pinService.hasPin();

      if (hasPin) {
        const pinVerified = await this.pinService.promptPin('verify');
        if (!pinVerified) {
          this.analytics.trackErrorShown('auth_required', 'portfolio', 'toast', null, false);
          this.toastService.showToast('Authentication required to change bank account.', 'warning', 3000);
          return;
        }
      }

      // The step-up gate (or its deliberate absence) just passed: only now is it true that a
      // stored token proves Link really left for the bank after a verified step-up.
      if (this.currentLinkToken) {
        storePlaidLinkSession(this.currentLinkToken, 'change-bank', this.linkMode);
      }
      this.plaidHandler.open();
    } else {
      this.analytics.trackErrorShown('bank_link', 'portfolio', 'inline_banner', null, false);
      this.statusMessage = 'Plaid is not ready. Please try again.';
    }
  }

  private getLinkTokenAuthenticated(mode: PlaidLinkMode = 'link') {
    return this.http.post<any>(`${BACKEND_API_URL}/plaid/create_link_token`, { mode }, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * A token in the requested mode. Plaid can refuse update mode (after USER_PERMISSION_REVOKED, or
   * with no Item) and the server answers 409 UPDATE_MODE_UNAVAILABLE; a fresh link is then the way
   * back, and its exchange queues the old Item for removal (spec 2.3, 8.8). Any other error rethrows.
   */
  private async requestLinkToken(mode: PlaidLinkMode): Promise<{ link_token: string }> {
    try {
      const data = await this.getLinkTokenAuthenticated(mode).toPromise();
      this.linkMode = mode;
      return data;
    } catch (error) {
      if (mode === 'update' && isUpdateModeUnavailable(error)) {
        const data = await this.getLinkTokenAuthenticated('link').toPromise();
        this.linkMode = 'link';
        return data;
      }
      throw error;
    }
  }

  /** 'update' only when opened as /change-bank-account?mode=update (the reconnect card). Stubbed in specs. */
  private requestedLinkMode(): PlaidLinkMode {
    return new URLSearchParams(window.location.search).get('mode') === 'update' ? 'update' : 'link';
  }

  /**
   * The URL Plaid redirected to when this load is an OAuth return, else null. Every link token
   * names the one registered redirect URI (/link-bank), so a change-bank return arrives here
   * forwarded, and the /link-bank href parked before the forward is the one Link must resume with.
   * It is used only when it carries this load's oauth_state_id, so a leftover from an earlier round
   * trip never stands in for the current one. Stubbed in specs.
   */
  private oauthReturnHref(): string | null {
    const current = this.currentHref();
    const stateId = plaidOAuthStateId(current);
    if (stateId === null) {
      return null;
    }
    const original = readPlaidOAuthReturnHref();
    return original !== null && plaidOAuthStateId(original) === stateId ? original : current;
  }

  /** window.location.href, behind a method so specs can set the URL. */
  private currentHref(): string {
    return window.location.href;
  }

  private exchangePublicTokenAuthenticated(publicToken: string): void {
    console.log('Exchanging authenticated public_token...');
    
    this.plaidSubscription = this.http.post(
      `${BACKEND_API_URL}/plaid/exchange_public_token`, 
      { public_token: publicToken }, 
      { headers: this.getAuthHeaders() }
    ).pipe(
      tap(async (response) => {
        console.log('Authenticated public token exchanged successfully:', response);
        this.statusMessage = 'Bank account updated successfully!';
        this.isLoading = false;
        
        // Show success toast
        await this.toastService.showToast('Your bank account has been updated successfully.', 'success', 3000);

        // Reload current bank account info locally
        await this.loadCurrentBankAccount();
        
        // Refresh the PlaidService data to ensure Tab3 gets the latest bank account info
        this.plaidService.refreshBankAccountData();
      }),
      catchError(async (err) => {
        this.isLoading = false;
        this.analytics.trackErrorShown(
          isNetworkFailure(err) ? 'network' : 'bank_link',
          'portfolio',
          'toast',
          httpStatusOf(err),
          false,
        );
        this.statusMessage = `Error: ${err.error?.message || 'Failed to update bank account.'}`;
        
        // Show error toast
        await this.toastService.showToast('Failed to update bank account. Please try again.', 'danger', 5000);

        return of(null);
      })
    ).subscribe();
  }

  /** Update mode keeps the Item and its access token, so the server only re-reads it (spec 2.3, 8.8). */
  private completeUpdateMode(): void {
    this.plaidSubscription = this.http.post<UpdateModeCompleteResponse>(
      `${BACKEND_API_URL}/plaid/update-mode-complete`,
      {},
      { headers: this.getAuthHeaders() }
    ).subscribe({
      next: (response) => {
        this.isLoading = false;
        if (response?.result === 'CONSENT_STILL_MISSING') {
          this.statusMessage = 'Your bank is connected. FRED still needs permission to read deposits.';
          this.toastService.showToast('FRED still needs permission to read deposits at your bank.', 'warning', 5000);
        } else {
          this.statusMessage = 'Your bank connection is up to date.';
          this.toastService.showToast('Your bank is reconnected.', 'success', 3000);
        }
        void this.loadCurrentBankAccount();
        this.plaidService.refreshBankAccountData();
      },
      error: (err) => {
        this.isLoading = false;
        this.analytics.trackErrorShown(
          isNetworkFailure(err) ? 'network' : 'bank_link',
          'portfolio',
          'toast',
          httpStatusOf(err),
          false,
        );
        this.statusMessage = 'Could not finish reconnecting your bank. Please try again.';
        this.toastService.showToast('Could not finish reconnecting your bank. Please try again.', 'danger', 5000);
      }
    });
  }

  private getAuthHeaders(): HttpHeaders {
    const token = JwtTokenUtils.getValidJwtToken();
    return new HttpHeaders({
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    });
  }

  formatAccountSubtype(): string {
    const rawSubtype = this.currentBankAccount?.accountSubtype;
    return rawSubtype
      ? rawSubtype.charAt(0).toUpperCase() + rawSubtype.slice(1).toLowerCase()
      : 'Account';
  }
}
