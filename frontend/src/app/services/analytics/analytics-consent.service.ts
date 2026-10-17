import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, map, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';
import { AnalyticsService } from './analytics.service';

const BACKEND_API_URL = environment.backendApiUrl;

/**
 * Writes the user's analytics consent to the server and applies the stored value locally.
 *
 * Consent lives on the server (users.analytics_opt_out) rather than in this device's storage, so
 * it follows the user to every device they sign in on and can be honoured by server-side emission
 * too. The client never decides it; it only reads it and offers the toggle.
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsConsentService {

  constructor(private http: HttpClient, private analytics: AnalyticsService) {}

  /**
   * @param optOut true to stop sending anything for this user
   * @returns the value the server stored, which is what the local gate then uses
   */
  setOptOut(optOut: boolean): Observable<boolean> {
    return this.http
      .put<{ analyticsOptOut: boolean }>(
        `${BACKEND_API_URL}/user/analytics-consent`,
        { optOut },
        { headers: this.authHeaders() },
      )
      .pipe(
        // Apply what the server stored, not what was asked for: if the two ever disagree, the
        // server is right.
        tap((response) => this.analytics.applyServerConsent(response.analyticsOptOut)),
        map((response) => response.analyticsOptOut),
      );
  }

  /** Current consent, as last applied from the server. true means opted out. */
  isOptedOut(): boolean {
    return this.analytics.isOptedOut();
  }

  private authHeaders(): HttpHeaders {
    return new HttpHeaders({
      Authorization: `Bearer ${JwtTokenUtils.getValidJwtToken()}`,
      'Content-Type': 'application/json',
    });
  }
}
