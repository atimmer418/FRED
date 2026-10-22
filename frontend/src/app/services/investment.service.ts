import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, catchError, throwError } from 'rxjs';
import { tap } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';

export interface InvestmentExecution {
  id: number;
  scheduledDate: string;
  executionDate?: string;
  amount: number;
  status: string;
  alpacaTransferId?: string;
  errorMessage?: string;
  fundingCompletedAt?: string;
  tradingCompletedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface InvestmentTrade {
  id: number;
  symbol: string;
  quantity?: number;
  notionalAmount: number;
  filledQuantity: number;
  filledAvgPrice?: number;
  alpacaOrderId?: string;
  status: string;
  errorMessage?: string;
  submittedAt?: string;
  filledAt?: string;
  createdAt: string;
}

export interface InvestmentHistory {
  success: boolean;
  executions: InvestmentExecution[];
  nextInvestmentDate: string;
  monthlyInvestment: number;
  payFrequency: string;
}

export interface InvestmentDashboard {
  success: boolean;
  totalInvested: number;
  pendingCount: number;
  failedCount: number;
  nextInvestmentDate: string;
  monthlyInvestment: number;
  payFrequency: string;
  recentExecutions: InvestmentExecution[];
}

export interface ExecutionDetails {
  success: boolean;
  execution: InvestmentExecution;
  trades: InvestmentTrade[];
}

export interface InvestmentSchedule {
  id: number;
  monthlyAmount: number;
  investmentAmount: number;
  frequency: 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'SEMI_MONTHLY';
  startDate: string | number[]; // Can be ISO string or Java LocalDate array [year, month, day]
  nextInvestmentDate: string | number[]; // Can be ISO string or Java LocalDate array [year, month, day]
  chosenDate?: string | number[]; // The user's preferred anchor date, unaffected by holiday adjustments
  achRequestId?: string;
  isPaused: boolean;
  scheduleDescription: string;
  monthlyStreak: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateInvestmentScheduleRequest {
  investmentAmount: number;
  frequency: string;
  startDate: string;
  nextInvestmentDate?: string;
}

export interface UpdateInvestmentScheduleRequest {
  investmentAmount?: number;
  frequency?: string;
  nextInvestmentDate?: string;
}

export interface AcatsRequestData {
  dtcNumber: string;
  accountNumber: string;
}

@Injectable({
  providedIn: 'root'
})
export class InvestmentService {
  private apiUrl = `${environment.backendApiUrl}/investments`;
  private scheduleApiUrl = `${environment.backendApiUrl}/investment-schedule`;
  private alpacaApiUrl = `${environment.backendApiUrl}/alpaca`; // Add this
  
  // Temporary storage for onboarding flow
  public pendingAcatsRequest: AcatsRequestData | null = null; 

  constructor(private http: HttpClient, private authService: AuthService) { }

  initiateAcatsTransfer(data: AcatsRequestData): Observable<any> {
    const headers = this.getAuthHeaders();
    return this.http.post(`${this.alpacaApiUrl}/acats/transfer`, data, { headers });
  }

  private getAuthHeaders(): HttpHeaders {
    const token = localStorage.getItem('jwtToken');
    return new HttpHeaders({
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    });
  }

  /**
   * Get investment history for the current user
   */
  getInvestmentHistory(): Observable<InvestmentHistory> {
    return this.http.get<InvestmentHistory>(`${this.apiUrl}/history`, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * Get details for a specific investment execution
   */
  getExecutionDetails(executionId: number): Observable<ExecutionDetails> {
    return this.http.get<ExecutionDetails>(`${this.apiUrl}/execution/${executionId}`, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * Update investment schedule settings
   */
  updateInvestmentSchedule(settings: {
    monthlyInvestment?: number;
    payFrequency?: string;
    nextInvestmentDate?: string;
  }): Observable<any> {
    return this.http.post(`${this.apiUrl}/schedule`, settings, {
      headers: this.getAuthHeaders()
    }).pipe(tap(() => this.authService.clearScheduleCache()));
  }

  /**
   * Create a manual investment execution. clientUuid is generated once per call: a retry of this
   * request carries the same UUID and the backend returns the first row instead of queueing a
   * second one (decision 18).
   */
  createManualInvestment(amount: number): Observable<any> {
    const clientUuid = crypto.randomUUID();
    return this.http.post(`${this.apiUrl}/execute`, { amount, clientUuid }, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * Get investment dashboard data
   */
  getDashboard(): Observable<InvestmentDashboard> {
    return this.http.get<InvestmentDashboard>(`${this.apiUrl}/dashboard`, {
      headers: this.getAuthHeaders()
    });
  }

  // Investment Schedule Management Methods

  /**
   * Get current investment schedule for the authenticated user
   */
  getCurrentSchedule(): Observable<InvestmentSchedule> {
    return this.http.get<InvestmentSchedule>(`${this.scheduleApiUrl}/current`, {
      headers: this.getAuthHeaders()
    }).pipe(
      catchError(error => {
        console.error('Error fetching current investment schedule:', error);
        if (error.status === 404) {
          console.log('No investment schedule found for user');
        }
        return throwError(() => error);
      })
    );
  }

  /**
   * Get all investment schedules for the authenticated user
   */
  getAllSchedules(): Observable<InvestmentSchedule[]> {
    return this.http.get<InvestmentSchedule[]>(`${this.scheduleApiUrl}/all`, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * Create or update investment schedule
   */
  createSchedule(request: CreateInvestmentScheduleRequest): Observable<InvestmentSchedule> {
    return this.http.post<InvestmentSchedule>(`${this.scheduleApiUrl}/create`, request, {
      headers: this.getAuthHeaders()
    }).pipe(tap(() => this.authService.clearScheduleCache()));
  }

  /**
   * Pause an investment schedule
   */
  pauseSchedule(scheduleId: number): Observable<InvestmentSchedule> {
    return this.http.post<InvestmentSchedule>(`${this.scheduleApiUrl}/${scheduleId}/pause`, {}, {
      headers: this.getAuthHeaders()
    }).pipe(tap(() => this.authService.clearScheduleCache()));
  }

  /**
   * Resume an investment schedule
   */
  resumeSchedule(scheduleId: number): Observable<InvestmentSchedule> {
    return this.http.post<InvestmentSchedule>(`${this.scheduleApiUrl}/${scheduleId}/resume`, {}, {
      headers: this.getAuthHeaders()
    }).pipe(tap(() => this.authService.clearScheduleCache()));
  }

  /**
   * Helper method to get status display text
   */
  getStatusDisplayText(status: string): string {
    switch (status) {
      case 'SCHEDULED': return 'Scheduled';
      case 'FUNDING_INITIATED': return 'Funding...';
      case 'FUNDING_COMPLETED': return 'Funded';
      case 'FUNDING_FAILED': return 'Funding Failed';
      case 'TRADING_INITIATED': return 'Trading...';
      case 'TRADING_COMPLETED': return 'Trading Complete';
      case 'TRADING_FAILED': return 'Trading Failed';
      case 'COMPLETED': return 'Completed';
      case 'FAILED': return 'Failed';
      default: return status;
    }
  }

  /**
   * Helper method to get status color
   */
  getStatusColor(status: string): string {
    switch (status) {
      case 'SCHEDULED': return 'primary';
      case 'FUNDING_INITIATED':
      case 'TRADING_INITIATED': return 'warning';
      case 'FUNDING_COMPLETED':
      case 'TRADING_COMPLETED':
      case 'COMPLETED': return 'success';
      case 'FUNDING_FAILED':
      case 'TRADING_FAILED':
      case 'FAILED': return 'danger';
      default: return 'medium';
    }
  }

  /**
   * Helper method to check if status is in progress
   */
  isStatusInProgress(status: string): boolean {
    return ['FUNDING_INITIATED', 'TRADING_INITIATED'].includes(status);
  }

  /**
   * Helper method to check if status is failed
   */
  isStatusFailed(status: string): boolean {
    return ['FUNDING_FAILED', 'TRADING_FAILED', 'FAILED'].includes(status);
  }

  /**
   * Helper method to check if status is completed
   */
  isStatusCompleted(status: string): boolean {
    return status === 'COMPLETED';
  }
}
