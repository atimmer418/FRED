import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';

export interface RecoveryResponse {
  success: boolean;
  message: string;
  /** Set when the code was accepted. Good for registering one new passkey, nothing else. */
  recoveryToken?: string;
}

@Injectable({
  providedIn: 'root'
})
export class RecoveryService {
  private apiUrl = `${environment.backendApiUrl}/auth/recovery`;

  constructor(private http: HttpClient) {}

  async initiateRecovery(email: string): Promise<RecoveryResponse> {
    return await firstValueFrom(
      this.http.post<RecoveryResponse>(`${this.apiUrl}/initiate`, { email })
    );
  }

  async verifyRecovery(email: string, otp: string): Promise<RecoveryResponse> {
    return await firstValueFrom(
      this.http.post<RecoveryResponse>(`${this.apiUrl}/verify`, { email, otp })
    );
  }
}
