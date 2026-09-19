import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { ACTIVE_MOCK_TIER, MockTier, mockDashboard, mockHistory, mockPerformance } from './dev/portfolio-mock';

// Portfolio Dashboard Types
export interface AccountSummary {
  portfolioValue: number;
  todayChange: number;
  todayChangePercent: number;
  buyingPower: number;
  cash: number;
  withdrawableCash?: number;
  equity: number;
}

export interface Position {
  symbol: string;
  name: string;
  quantity: number;
  quantityAvailable: number;
  marketValue: number;
  costBasis: number;
  unrealizedPL: number;
  unrealizedPLPercent: number;
  currentPrice: number;
  averageCostBasis: number;
  percentOfAccount: number;
  todayGainLoss: number;
  todayGainLossPercent: number;
}

export interface PortfolioHistory {
  timestamps: string[];
  values: number[];
  profitLoss?: number[];
  profitLossPercent?: number[];
}

export interface Transaction {
  id: string;
  type: string;
  symbol: string;
  quantity: number;
  price: number;
  amount: number;
  date: string;
  status: string;
}

export interface PortfolioDashboardData {
  summary: AccountSummary;
  positions: Position[];
  history: PortfolioHistory;
  recentTransactions: Transaction[];
  totalInvested: number;
  totalGainLoss: number;
  totalGainLossPercent: number;
}

export interface PerformanceData {
  period: string;
  startValue: number;
  endValue: number;
  totalReturn: number;
  totalReturnPercent: number;
  bestDay?: number;
  worstDay?: number;
}

@Injectable({
  providedIn: 'root'
})
export class PortfolioService {
  private apiUrl = `${environment.backendApiUrl}/portfolio`;

  constructor(private http: HttpClient) { }

  /**
   * DEV ONLY: the active mock portfolio tier, or null for real data.
   * Gated on environment.local — NOT just !production — because the deployed dev/test
   * builds also have production:false but read live Alpaca; only the local dev loop
   * (environment.ts / ci) has local:true. ACTIVE_MOCK_TIER itself is additionally
   * forced null under karma (see portfolio-mock.ts) so unit tests always hit HTTP.
   */
  private activeTier(): MockTier | null {
    return environment.local ? ACTIVE_MOCK_TIER : null;
  }

  private getAuthHeaders(): HttpHeaders {
    const token = localStorage.getItem('jwtToken');
    return new HttpHeaders({
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    });
  }

  /**
   * Get comprehensive portfolio dashboard data
   */
  getPortfolioDashboard(): Observable<PortfolioDashboardData> {
    const tier = this.activeTier();
    if (tier) return of(mockDashboard(tier));
    return this.http.get<PortfolioDashboardData>(`${this.apiUrl}/dashboard`, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * Get portfolio history data for charting
   */
  getPortfolioHistory(period: string = 'ALL'): Observable<PortfolioHistory> {
    const tier = this.activeTier();
    if (tier) return of(mockHistory(tier, period));
    return this.http.get<any>(`${this.apiUrl}/history`, {
      headers: this.getAuthHeaders(),
      params: { period }
    }).pipe(
      map(response => {
        // Backend returns: { history: {...}, period: "ALL" }
        // Extract the history object from the response
        const historyData = response.history || response;

        // Backend PortfolioHistory class has: timestamps, values, profitLoss
        // Backend sends ISO date strings (YYYY-MM-DD), convert to full ISO datetime for Chart.js
        const timestamps = (historyData.timestamps || []).map((dateStr: string) => {
          // Convert ISO date (YYYY-MM-DD) to full ISO datetime (YYYY-MM-DDTHH:mm:ss.sssZ)
          return new Date(dateStr + 'T00:00:00.000Z').toISOString();
        });

        return {
          timestamps,
          values: historyData.values || [],
          profitLoss: historyData.profitLoss || [],
          profitLossPercent: historyData.profitLossPercent || []
        } as PortfolioHistory;
      })
    );
  }

  /**
   * Get current portfolio positions
   */
  getPositions(): Observable<Position[]> {
    return this.http.get<Position[]>(`${this.apiUrl}/positions`, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * Get portfolio performance analytics
   */
  getPerformance(): Observable<PerformanceData[]> {
    const tier = this.activeTier();
    if (tier) return of(mockPerformance(tier));
    return this.http.get<PerformanceData[]>(`${this.apiUrl}/performance`, {
      headers: this.getAuthHeaders()
    });
  }

  /**
   * Get tax documents
   */
  getTaxDocuments(start?: string, end?: string): Observable<any[]> {
    let params: any = {};
    if (start) params.start = start;
    if (end) params.end = end;

    return this.http.get<any[]>(`${environment.backendApiUrl}/documents/tax`, {
      headers: this.getAuthHeaders(),
      params: params
    });
  }

  /**
   * Download a document
   */
  downloadDocument(documentId: string): Observable<Blob> {
    return this.http.get(`${environment.backendApiUrl}/documents/tax/${documentId}/download`, {
      headers: this.getAuthHeaders(),
      responseType: 'blob'
    });
  }
}
