import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, of, shareReplay } from 'rxjs';
import { tap, finalize } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';

export interface CreateAlpacaAccountRequest {
  emailAddress: string;
  phoneNumber: string;
  streetAddress: string;
  city: string;
  state: string;
  postalCode: string;
  givenName: string;
  familyName: string;
  dateOfBirth: string; // YYYY-MM-DD
  taxId: string;
  taxIdType: string; // "USA_SSN"
  countryOfTaxResidence: string; // "USA"
  fundingSource: string[];
  isControlPerson: boolean;
  isAffiliatedExchangeOrFinra: boolean;
  isPoliticallyExposed: boolean;
  immediateFamilyExposed: boolean;
}

export interface AlpacaAsset {
  id: string;
  class: string;
  exchange: string;
  symbol: string;
  name: string;
  status: string;
  tradable: boolean;
  marginable: boolean;
  shortable: boolean;
  easy_to_borrow: boolean;
  fractionable: boolean;
}

export interface AlpacaAccountResponse {
  account_id: string;
  status: string;
  created_at: string;
  error?: string;
}

export interface AccountStatusResponse {
  accountStatus: string;
  hasActionRequired: boolean;
}

export interface UpdateKycRequest {
  emailAddress?: string;
  phoneNumber?: string;
  streetAddress?: string;
  city?: string;
  state?: string;
  postalCode?: string;
  givenName?: string;
  familyName?: string;
  dateOfBirth?: string; // YYYY-MM-DD
  fundingSource?: string[];
  isControlPerson?: boolean;
  isAffiliatedExchangeOrFinra?: boolean;
  isPoliticallyExposed?: boolean;
  immediateFamilyExposed?: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class AlpacaService {
  private baseUrl = environment.backendApiUrl;

  // Single-flight cache for KYC data — prevents multiple concurrent callers
  // (e.g. FreedomStatsService constructor + ionViewWillEnter refresh) from
  // each firing their own HTTP request.
  private static readonly KYC_TTL_MS = 60_000;
  private kycEntry: { value: any; fetchedAt: number; userId: string } | null = null;
  private kycInFlight$: Observable<any> | null = null;

  constructor(private http: HttpClient) {}

  private getAuthHeaders(): HttpHeaders {
    let headers = new HttpHeaders().set('Content-Type', 'application/json');
    const token = JwtTokenUtils.getValidJwtToken();
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    return headers;
  }

  /**
   * Create an Alpaca trading account for the user with full KYC data
   */
  createAlpacaAccount(request: CreateAlpacaAccountRequest): Observable<AlpacaAccountResponse> {
    return this.http.post<AlpacaAccountResponse>(
      `${this.baseUrl}/alpaca/create-account`,
      request,
      { headers: this.getAuthHeaders() }
    );
  }

  /**
   * Get current user's Alpaca account status
   */
  getMyAccountStatus(): Observable<AccountStatusResponse> {
    return this.http.get<AccountStatusResponse>(
      `${this.baseUrl}/alpaca/my-account-status`,
      { headers: this.getAuthHeaders() }
    );
  }

  /**
   * Upload a verification document to Alpaca
   */
  uploadDocument(request: { documentType: string; mimeType: string; content: string }): Observable<any> {
    return this.http.post<any>(
      `${this.baseUrl}/alpaca/upload-document`,
      request,
      { headers: this.getAuthHeaders() }
    );
  }

  /**
   * Get tradeable assets from Alpaca (public endpoint, but include auth if available)
   */
  getAssets(status: string = 'active', assetClass: string = 'us_equity'): Observable<any> {
    const params = new URLSearchParams();
    if (status) params.append('status', status);
    if (assetClass) params.append('asset_class', assetClass);

    const headers = this.getAuthHeaders();

    return this.http.get(
      `${this.baseUrl}/alpaca/assets?${params.toString()}`,
      { headers }
    );
  }

  /**
   * Fetch current user's KYC data from Alpaca (for pre-filling the edit form).
   *
   * Single-flight: concurrent callers within TTL_MS share one in-flight HTTP
   * request (or receive the cached value). Errors are never cached — a failed
   * request clears the in-flight reference so the next caller retries fresh.
   * Cache is user-scoped; a different userId invalidates the entry.
   */
  getKycData(): Observable<any> {
    const userId = localStorage.getItem('userId') ?? '';
    if (
      this.kycEntry &&
      this.kycEntry.userId === userId &&
      Date.now() - this.kycEntry.fetchedAt < AlpacaService.KYC_TTL_MS
    ) {
      return of(this.kycEntry.value);
    }
    if (this.kycInFlight$) {
      return this.kycInFlight$;
    }
    const obs: Observable<any> = this.http.get(
      `${this.baseUrl}/alpaca/account/kyc`,
      { headers: this.getAuthHeaders() }
    ).pipe(
      tap(value => {
        this.kycEntry = { value, fetchedAt: Date.now(), userId: localStorage.getItem('userId') ?? '' };
      }),
      finalize(() => {
        if (this.kycInFlight$ === obs) {
          this.kycInFlight$ = null;
        }
      }),
      shareReplay(1)
    );
    this.kycInFlight$ = obs;
    return obs;
  }

  /** Clear the KYC single-flight cache. Call after a successful updateKyc() so the next fetch is fresh. */
  clearKycCache(): void {
    this.kycEntry = null;
    this.kycInFlight$ = null;
  }

  /**
   * Update current user's KYC data via Alpaca PATCH API.
   * Clears the getKycData() single-flight cache on success so the next read
   * fetches the updated values rather than replaying the pre-mutation cache.
   */
  updateKyc(request: UpdateKycRequest): Observable<any> {
    return this.http.patch(
      `${this.baseUrl}/alpaca/account/kyc`,
      request,
      { headers: this.getAuthHeaders() }
    ).pipe(
      tap(() => this.clearKycCache())
    );
  }
}
