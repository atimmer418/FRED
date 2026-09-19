import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { RegistrationStartRequest } from '../models/passkey/registration-start-request.model';
import { RegistrationStartResponse } from '../models/passkey/registration-start-response.model';
import { RegistrationFinishRequest } from '../models/passkey/registration-finish-request.model';
import { RegistrationFinishResponse } from '../models/passkey/registration-finish-response.model';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { resolveDevToolsKey } from '../utils/dev-tools-key.utils';
import { DeviceIdService } from './device-id.service';

const BACKEND_API_URL = environment.backendApiUrl;

@Injectable({
  providedIn: 'root'
})
export class PasskeyService {

  constructor(private http: HttpClient, private deviceIdService: DeviceIdService) { }

  private async getHeadersAsync(): Promise<HttpHeaders> {
    let headers = new HttpHeaders();
    
    // Add device ID
    const deviceId = this.deviceIdService.getDeviceId();
    headers = headers.set('X-Device-ID', deviceId);

    // Add friendly device name
    const deviceName = await this.deviceIdService.getDeviceName();
    headers = headers.set('X-Device-Name', deviceName);
    
    // Dev-tools key, set only in CI builds or after ?devKey= on a dev device. The backend needs it
    // for the simulated-attestation bypass; it is ignored on every real passkey call.
    const devToolsKey = resolveDevToolsKey();
    if (devToolsKey) {
      headers = headers.set('X-Dev-Tools-Key', devToolsKey);
    }

    // Add JWT token if available
    const token = JwtTokenUtils.getValidJwtToken();
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    
    return headers;
  }

  // Helper to wrap async headers in Observable flow if needed, 
  // but for now we'll just use from() in the methods
  
  startRegistration(data: RegistrationStartRequest): Observable<RegistrationStartResponse> {
    // We need to convert the async header retrieval to an Observable
    return new Observable(observer => {
      this.getHeadersAsync().then(headers => {
        this.http.post<RegistrationStartResponse>(`${BACKEND_API_URL}/passkey/register/start`, data, { headers })
          .subscribe({
            next: res => { observer.next(res); observer.complete(); },
            error: err => observer.error(err)
          });
      });
    });
  }

  finishRegistration(data: RegistrationFinishRequest): Observable<RegistrationFinishResponse> {
    const finishUrl = `${BACKEND_API_URL}/passkey/register/finish`;
    return new Observable(observer => {
      this.getHeadersAsync().then(headers => {
        this.http.post<RegistrationFinishResponse>(finishUrl, data, { headers })
          .pipe(
            tap(response => {
              if (response.success && response.jwtToken) {
                JwtTokenUtils.storeJwtToken(response.jwtToken, response.userId, response.email);
                console.log('User registered and logged in. JWT stored with expiration info.');
              }
            })
          )
          .subscribe({
            next: res => { observer.next(res); observer.complete(); },
            error: err => observer.error(err)
          });
      });
    });
  }

  /**
   * Start passkey authentication using discoverable credentials (usernameless).
   * Used for initial login when we don't know who the user is.
   */
  startAuthentication(): Observable<{requestOptions: string, sessionId: string}> {
    return new Observable(observer => {
      this.getHeadersAsync().then(headers => {
        this.http.post<{requestOptions: string, sessionId: string}>(`${BACKEND_API_URL}/passkey/authenticate/start`, {}, { headers })
          .subscribe({
            next: res => { observer.next(res); observer.complete(); },
            error: err => observer.error(err)
          });
      });
    });
  }

  /**
   * Start account-specific passkey authentication.
   * Restricts allowCredentials to the given user's registered passkeys,
   * ensuring only that account can re-authenticate.
   */
  startAuthenticationForUser(email: string): Observable<{requestOptions: string, sessionId: string}> {
    return new Observable(observer => {
      this.getHeadersAsync().then(headers => {
        this.http.post<{requestOptions: string, sessionId: string}>(`${BACKEND_API_URL}/passkey/authenticate/start`, { email }, { headers })
          .subscribe({
            next: res => { observer.next(res); observer.complete(); },
            error: err => observer.error(err)
          });
      });
    });
  }

  startRecoveryRegistration(): Observable<RegistrationStartResponse> {
    return new Observable(observer => {
      this.getHeadersAsync().then(headers => {
        this.http.post<RegistrationStartResponse>(
          `${BACKEND_API_URL}/passkey/register/recovery/start`,
          {},
          { headers }
        ).subscribe({
          next: res => { observer.next(res); observer.complete(); },
          error: err => observer.error(err)
        });
      });
    });
  }

  /**
   * Finish passkey authentication using the credential response and session ID
   */
  finishAuthentication(credentialResponse: any, sessionId: string): Observable<any> {
    const finishUrl = `${BACKEND_API_URL}/passkey/authenticate/finish`;
    return new Observable(observer => {
      this.getHeadersAsync().then(headers => {
        this.http.post<any>(finishUrl, {
          credential: credentialResponse,
          sessionId: sessionId
        }, { headers })
        .pipe(
          tap(response => {
            if (response.success && response.jwtToken) {
              JwtTokenUtils.storeJwtToken(response.jwtToken, response.userId, response.email);
              console.log('User authenticated with passkey. JWT stored with expiration info.');
            }
          })
        )
        .subscribe({
          next: res => { observer.next(res); observer.complete(); },
          error: err => observer.error(err)
        });
      });
    });
  }

  // Helper methods for WebAuthn
  public base64urlToArrayBuffer(base64url: string): ArrayBuffer {
    let base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
    while (base64.length % 4) { base64 += '='; }
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) { bytes[i] = binary.charCodeAt(i); }
    return bytes.buffer;
  }

  public arrayBufferToBase64url(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) { binary += String.fromCharCode(bytes[i]); }
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
  }

  public credentialToJson(credential: any): any {
    return {
      id: credential.id,
      rawId: this.arrayBufferToBase64url(credential.rawId),
      response: {
        authenticatorData: this.arrayBufferToBase64url(credential.response.authenticatorData),
        clientDataJSON: this.arrayBufferToBase64url(credential.response.clientDataJSON),
        signature: this.arrayBufferToBase64url(credential.response.signature),
        userHandle: credential.response.userHandle ? this.arrayBufferToBase64url(credential.response.userHandle) : null
      },
      type: credential.type,
      clientExtensionResults: credential.getClientExtensionResults()
    };
  }
}