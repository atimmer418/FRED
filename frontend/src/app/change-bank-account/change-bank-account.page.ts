import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Subject, takeUntil, catchError, tap, of } from 'rxjs';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { clearPlaidLinkSession, readPlaidLinkToken, storePlaidLinkSession } from '../utils/plaid-link-session';
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
      const returnHref = this.oauthReturnHref();
      const storedToken = returnHref ? readPlaidLinkToken() : null;
      const resuming = returnHref !== null && storedToken !== null;
      let linkToken: string;
      if (resuming) {
        linkToken = storedToken as string;
      } else {
        const linkTokenData = await this.getLinkTokenAuthenticated().toPromise();
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
          this.exchangePublicTokenAuthenticated(public_token);
        },
        onLoad: () => {
          console.log('Plaid Link UI loaded.');
          this.isLoading = false;
          this.isPlaidReady = true;
          this.statusMessage = 'Ready to change your bank account.';
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
        storePlaidLinkSession(this.currentLinkToken, 'change-bank');
      }
      this.plaidHandler.open();
    } else {
      this.analytics.trackErrorShown('bank_link', 'portfolio', 'inline_banner', null, false);
      this.statusMessage = 'Plaid is not ready. Please try again.';
    }
  }

  private getLinkTokenAuthenticated() {
    return this.http.post<any>(`${BACKEND_API_URL}/plaid/create_link_token`, { mode: 'link' }, {
      headers: this.getAuthHeaders()
    });
  }

  /** The current URL when it carries Plaid's oauth_state_id, else null. Stubbed in specs. */
  private oauthReturnHref(): string | null {
    return window.location.search.includes('oauth_state_id') ? window.location.href : null;
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
