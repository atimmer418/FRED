import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { DeviceIdService } from './device-id.service';

export interface Beneficiary {
  id?: number;
  beneficiaryType: 'PRIMARY' | 'CONTINGENT';
  firstName: string;
  lastName: string;
  email?: string;
  phone?: string;
  dateOfBirth?: string;
  /**
   * Sent to the server, never sent back. A new beneficiary needs it; an edit leaves it out to keep
   * the number on file.
   */
  socialSecurityNumber?: string;
  /** The last four digits of the number on file. All the server ever returns of it. */
  ssnLast4?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  state?: string;
  postalCode?: string;
  country?: string;
  relationship: string;
  percentageAllocation: number;
  status: 'PENDING' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'INACTIVE';
  notes?: string;
  alpacaBeneficiaryId?: string;
  createdAt?: string;
  updatedAt?: string;
  submittedToAlpacaAt?: string;
}

export interface BeneficiaryAllocationSummary {
  primaryTotalAllocation: number;
  contingentTotalAllocation: number;
  primaryCount: number;
  contingentCount: number;
  maxPrimaryAllowed: number;
  maxContingentAllowed: number;
  primaryRemainingAllocation: number;
  contingentRemainingAllocation: number;
  primaryAllocationComplete: boolean;
  contingentAllocationComplete: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class BeneficiaryService {
  private readonly apiUrl = `${environment.backendApiUrl}/beneficiaries`;

  constructor(
    private http: HttpClient,
    private deviceIdService: DeviceIdService
  ) {}

  /**
   * Get auth headers with Bearer token and device ID
   */
  private getAuthHeaders(): HttpHeaders {
    let headers = new HttpHeaders();
    const token = JwtTokenUtils.getValidJwtToken();
    
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    
    const deviceId = this.deviceIdService.getDeviceId();
    headers = headers.set('X-Device-ID', deviceId);
    
    return headers;
  }

  /**
   * Get all beneficiaries for the authenticated user
   */
  getBeneficiaries(): Observable<Beneficiary[]> {
    return this.http.get<Beneficiary[]>(this.apiUrl, { headers: this.getAuthHeaders() });
  }

  /**
   * Get beneficiary allocation summary for the authenticated user
   */
  getAllocationSummary(): Observable<BeneficiaryAllocationSummary> {
    return this.http.get<BeneficiaryAllocationSummary>(`${this.apiUrl}/summary`, { headers: this.getAuthHeaders() });
  }

  /**
   * Create a new beneficiary
   */
  createBeneficiary(beneficiary: Beneficiary): Observable<Beneficiary> {
    return this.http.post<Beneficiary>(this.apiUrl, beneficiary, { headers: this.getAuthHeaders() });
  }

  /**
   * Update an existing beneficiary
   */
  updateBeneficiary(id: number, beneficiary: Beneficiary): Observable<Beneficiary> {
    return this.http.put<Beneficiary>(`${this.apiUrl}/${id}`, beneficiary, { headers: this.getAuthHeaders() });
  }

  /**
   * Delete a beneficiary
   */
  deleteBeneficiary(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`, { headers: this.getAuthHeaders() });
  }

  /**
   * Submit a specific beneficiary to Alpaca
   */
  submitBeneficiary(id: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/${id}/submit`, {}, { headers: this.getAuthHeaders() });
  }

  /**
   * Submit all approved beneficiaries for the user to Alpaca
   */
  submitAllBeneficiaries(): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/submit-all`, {}, { headers: this.getAuthHeaders() });
  }
}
