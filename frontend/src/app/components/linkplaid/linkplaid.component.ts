import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient, HttpHeaders, HttpErrorResponse } from '@angular/common/http';
import {
  IonHeader, IonToolbar, IonContent, IonFooter, IonSpinner, NavController
} from '@ionic/angular/standalone';
import { Observable, throwError, Subscription } from 'rxjs';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';
import { catchError, tap } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { httpStatusOf } from '../../services/analytics/analytics-error.utils';
import { clearPlaidLinkSession, readPlaidLinkToken, storePlaidLinkSession } from '../../utils/plaid-link-session';

// Make sure Plaid global object is available (from script in index.html)
declare var Plaid: any;

interface LinkTokenAuthenticatedResponse {
  link_token: string;
  expiration: string;
}

const BACKEND_API_URL = environment.backendApiUrl;

@Component({
  selector: 'app-linkplaid-component',
  templateUrl: './linkplaid.component.html',
  styleUrls: ['./linkplaid.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonHeader, IonToolbar, IonContent, IonFooter, IonSpinner
  ],
})
export class LinkPlaidComponent implements OnInit, OnDestroy {
  isLoading: boolean = false;
  isReady: boolean = false;
  statusMessage: string | null = null;
  isPlaidReady: boolean = false;
  plaidHandler: any = null;

  private isUserAuthenticated: boolean = false; // Determine this based on JWT presence
  private plaidSubscription: Subscription | undefined;

  constructor(
    private http: HttpClient,
    private router: Router,
    private navCtrl: NavController,
    private authService: AuthService,
    private analytics: AnalyticsService
  ) {}

  ngOnInit() {
    // Reveal page content after font rendering (~50ms), independent of Plaid SDK loading
    setTimeout(() => this.isReady = true, 50);

    const jwtToken = JwtTokenUtils.getValidJwtToken();
    this.isUserAuthenticated = !!jwtToken; // Convert to boolean

    console.log(`LinkPlaidComponent ngOnInit - User Authenticated: ${this.isUserAuthenticated}`);
    // Nothing is drafted on this screen, so there is no saved state to resume from.
    this.analytics.trackOnboardingStepViewed('link_plaid', null, false);
    this.initializePlaidLink();
  }

  async initializePlaidLink() {
    this.isLoading = true;
    this.isPlaidReady = false;
    this.statusMessage = 'Initializing secure connection...';

    try {
      // Back from the bank's OAuth page: Link must resume with the SAME link_token it left with.
      // Without a stored token (storage cleared, new browser) Link simply starts over.
      const returnHref = this.oauthReturnHref();
      const storedToken = returnHref ? readPlaidLinkToken() : null;
      const resuming = returnHref !== null && storedToken !== null;
      let linkToken: string;

      if (this.isUserAuthenticated) {
        if (resuming) {
          linkToken = storedToken as string;
        } else {
          const linkTokenData = await this.getLinkTokenAuthenticated().toPromise() as LinkTokenAuthenticatedResponse;
          linkToken = linkTokenData.link_token;
          if (!linkToken) {
            throw new Error('Failed to retrieve link_token from backend.');
          }
          storePlaidLinkSession(linkToken, 'onboarding');
        }
      } else {
        // JWT is not available — the AppLockService will handle re-authentication.
        // Do not force a page reload or redirect here; let the auth flow resolve it.
        console.error('User is not authenticated. Cannot proceed with Plaid Link.');
        this.statusMessage = 'Authentication required. Please sign in to continue.';
        this.isLoading = false;
        return;
      }

      const config: any = {
        token: linkToken,
        onSuccess: (public_token: string, metadata: any) => {
          clearPlaidLinkSession();
          // The step finishes when the user comes back out of the Plaid sheet having picked an
          // account, not when the Connect button was tapped: the sheet can be abandoned invisibly.
          // Nothing from public_token or metadata is ever read here.
          this.analytics.trackOnboardingStepCompleted('link_plaid', null);
          console.log('Plaid Link success! Public Token:', public_token, "Metadata:", metadata);
          this.statusMessage = 'Processing...';
          this.isLoading = true;
          if (this.isUserAuthenticated) {
            this.exchangePublicTokenAuthenticated(public_token);
          } else {
            console.error("Critical error: user is not authenticated when trying to link bank.");
            this.statusMessage = "Error: User is not authenticated. Please restart the process.";
            this.isLoading = false;
            return;
          }
        },
        onLoad: () => {
          console.log('Plaid Link UI loaded.');
          this.isLoading = false;
          this.isPlaidReady = true;
          this.statusMessage = 'Ready to link your bank.';
        },
        onExit: (err: any, metadata: any) => {
          clearPlaidLinkSession();
          this.isLoading = false;
          this.isPlaidReady = true;
          if (err != null) {
            // The only thing taken from the exit is that an error was present. Not the code, not
            // the message, and nothing at all out of metadata.
            this.analytics.trackErrorShown('bank_link', 'onboarding', 'inline_banner', null, true);
            console.error('Plaid Link exited with error:', err, metadata);
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
        // Plaid requires open() straight after an OAuth return so Link picks up where it left off.
        this.plaidHandler.open();
      }
    } catch (error: any) {
      // The link token could not be fetched or the SDK refused it; the user sees an error and can
      // retry from the same button.
      this.analytics.trackErrorShown('bank_link', 'onboarding', 'inline_banner', httpStatusOf(error), true);
      console.error('Error initializing Plaid Link:', error);
      this.statusMessage = `Error: ${error.message || 'Could not initialize bank linking.'}`;
      this.isLoading = false;
    }
  }

  private getAuthHeaders(): HttpHeaders {
    return new HttpHeaders({
      'Authorization': `Bearer ${JwtTokenUtils.getValidJwtToken()}`,
      'Content-Type': 'application/json'
    });
  }

  private getLinkTokenAuthenticated(): Observable<LinkTokenAuthenticatedResponse> {
    console.log('Requesting authenticated link_token...');
    return this.http.post<LinkTokenAuthenticatedResponse>(`${BACKEND_API_URL}/plaid/create_link_token`, { mode: 'link' }, { headers: this.getAuthHeaders() })
      .pipe(
        tap(response => console.log('Received authenticated link_token response:', response)),
        catchError(this.handleError.bind(this))
      );
  }

  private exchangePublicTokenAuthenticated(publicToken: string): void {
    console.log('Exchanging authenticated public_token...');
    this.plaidSubscription = this.http.post<any>(`${BACKEND_API_URL}/plaid/exchange_public_token`, { public_token: publicToken }, { headers: this.getAuthHeaders() })
      .pipe(
        tap(response => {
          console.log('Authenticated public token exchanged successfully:', response);
          this.statusMessage = 'Bank account linked successfully!';
          // Complete the linkPlaid step using the unified method
          this.authService.completeStep('linkPlaid').subscribe({
            next: () => {
              console.log('LinkPlaid step completed successfully');
              // Navigate to the investment schedule setup
              this.navCtrl.navigateForward('/investment-schedule', { replaceUrl: true });
            },
            error: (err) => {
              console.error('Failed to complete LinkPlaid step:', err);
              // Still navigate even if progress update fails
              this.navCtrl.navigateForward('/investment-schedule', { replaceUrl: true });
            }
          });
        }),
        catchError(err => {
          this.isLoading = false;
          this.analytics.trackErrorShown('bank_link', 'onboarding', 'inline_banner', httpStatusOf(err), true);
          this.statusMessage = `Error: ${err.error?.message || 'Failed to link bank account.'}`;
          return this.handleError(err);
        })
      ).subscribe();
  }

  openPlaid() {
    if (this.plaidHandler && this.isPlaidReady) {
      this.statusMessage = 'Opening Plaid...';
      this.plaidHandler.open();
    } else if (!this.isPlaidReady && !this.isLoading) {
        this.statusMessage = 'Initializing, please wait...';
        this.initializePlaidLink();
    } else if (this.isLoading) {
        this.statusMessage = 'Still loading, please wait...';
    } else {
      this.statusMessage = 'Plaid Link is not ready. Please try refreshing.';
    }
  }

  goBack() {
    this.navCtrl.navigateBack('/kyc-verification');
  }

  /** The current URL when it carries Plaid's oauth_state_id, else null. Stubbed in specs. */
  private oauthReturnHref(): string | null {
    return window.location.search.includes('oauth_state_id') ? window.location.href : null;
  }

  private handleError(error: HttpErrorResponse) {
    console.error('API Error:', error);
    let userMessage = 'An unexpected error occurred. Please try again.';
    if (error.error && typeof error.error.message === 'string') {
      userMessage = error.error.message;
    } else if (typeof error.message === 'string') {
      userMessage = error.message;
    }
    // Potentially update this.statusMessage here as well, or ensure calling code does
    return throwError(() => new Error(userMessage));
  }

  ngOnDestroy() {
    if (this.plaidSubscription) {
      this.plaidSubscription.unsubscribe();
    }
    // Plaid Link SDK does not have a standard 'destroy' method for the handler instance.
    // It manages its own iframe lifecycle.
  }
}