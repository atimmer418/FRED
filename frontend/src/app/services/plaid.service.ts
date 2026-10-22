import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { BehaviorSubject, Observable, catchError, of, tap } from 'rxjs';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';

const BACKEND_API_URL = environment.backendApiUrl;

export interface BankAccount {
  id: string;
  name: string;
  type: 'checking' | 'savings' | 'credit';
  mask: string; // Last 4 digits
  institutionName: string;
  isLinked: boolean;
  linkDate: Date;
  status: 'active' | 'inactive' | 'error';
}

export interface PlaidLinkResult {
  success: boolean;
  publicToken?: string;
  metadata?: any;
  error?: string;
}

@Injectable({
  providedIn: 'root'
})
export class PlaidService {
  private bankAccountsSubject = new BehaviorSubject<BankAccount[]>([]);
  private currentAccountSubject = new BehaviorSubject<BankAccount | null>(null);

  constructor(private http: HttpClient) {
    // Don't eagerly load bank accounts — consumers call refreshBankAccountData() when needed
  }

  // Observable for bank accounts
  getBankAccounts(): Observable<BankAccount[]> {
    return this.bankAccountsSubject.asObservable();
  }

  // Observable for current active account
  getCurrentAccount(): Observable<BankAccount | null> {
    return this.currentAccountSubject.asObservable();
  }

  private getAuthHeaders(): HttpHeaders {
    let headers = new HttpHeaders();
    const token = JwtTokenUtils.getValidJwtToken();
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    return headers;
  }

  // Load bank accounts - try backend first, then fall back to localStorage
  private loadBankAccounts(): void {
    // Check if user is authenticated
    const token = JwtTokenUtils.getValidJwtToken();
    if (token && !JwtTokenUtils.isJwtExpired()) {
      // User is authenticated - fetch from backend
      this.loadBankAccountFromBackend();
    } else {
      // User not authenticated - load from localStorage
      this.loadBankAccountsFromLocalStorage();
    }
  }

  // Fetch bank account from backend
  private loadBankAccountFromBackend(): void {
    this.http.get<any>(`${BACKEND_API_URL}/plaid/primary-bank-account`, {
      headers: this.getAuthHeaders()
    }).pipe(
      tap(response => {
        if (response && response.institutionName) {
          // Convert backend response to BankAccount format
          const bankAccount: BankAccount = {
            id: response.accountId || 'backend-account',
            name: response.accountName || `${response.institutionName} ${response.accountSubtype || 'Account'}`,
            type: response.accountSubtype === 'checking' ? 'checking' : 
                  response.accountSubtype === 'savings' ? 'savings' : 'checking',
            mask: response.mask ?? '****', // null for links made before the mask was stored (T09)
            institutionName: response.institutionName,
            isLinked: true,
            linkDate: new Date(), // Could be enhanced to store actual link date
            status: 'active'
          };

          this.bankAccountsSubject.next([bankAccount]);
          this.currentAccountSubject.next(bankAccount);
          console.log('[PlaidService] Loaded bank account from backend:', bankAccount);
        } else {
          console.log('[PlaidService] No bank account found in backend, falling back to localStorage');
          this.loadBankAccountsFromLocalStorage();
        }
      }),
      catchError(error => {
        console.error('[PlaidService] Error loading bank account from backend:', error);
        console.log('[PlaidService] Falling back to localStorage');
        this.loadBankAccountsFromLocalStorage();
        return of(null);
      })
    ).subscribe();
  }

  // Load bank accounts from localStorage (fallback)
  private loadBankAccountsFromLocalStorage(): void {
    try {
      const stored = localStorage.getItem('plaidBankAccounts');
      if (stored) {
        const accounts = JSON.parse(stored).map((acc: any) => ({
          ...acc,
          linkDate: new Date(acc.linkDate)
        }));
        this.bankAccountsSubject.next(accounts);
        
        // Set the first active account as current
        const currentAccount = accounts.find((acc: BankAccount) => acc.status === 'active');
        if (currentAccount) {
          this.currentAccountSubject.next(currentAccount);
        }
      } else {
        // Create demo account if none exists
        this.createDemoAccount();
      }
    } catch (error) {
      console.error('Error loading bank accounts from localStorage:', error);
      this.createDemoAccount();
    }
  }

  // Create a demo bank account for testing
  private createDemoAccount(): void {
    const demoAccount: BankAccount = {
      id: 'demo-account-1',
      name: 'Main Checking',
      type: 'checking',
      mask: '1234',
      institutionName: 'Chase Bank',
      isLinked: true,
      linkDate: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000), // 30 days ago
      status: 'active'
    };

    this.addBankAccount(demoAccount);
  }

  // Add a new bank account
  addBankAccount(account: BankAccount): void {
    const currentAccounts = this.bankAccountsSubject.value;
    const updatedAccounts = [...currentAccounts, account];
    this.bankAccountsSubject.next(updatedAccounts);
    this.saveBankAccounts(updatedAccounts);

    // Set as current account if it's the first active one
    if (account.status === 'active' && !this.currentAccountSubject.value) {
      this.currentAccountSubject.next(account);
    }
  }

  // Update bank account status
  updateAccountStatus(accountId: string, status: BankAccount['status']): void {
    const accounts = this.bankAccountsSubject.value.map(acc => 
      acc.id === accountId ? { ...acc, status } : acc
    );
    this.bankAccountsSubject.next(accounts);
    this.saveBankAccounts(accounts);

    // Update current account if needed
    const currentAccount = this.currentAccountSubject.value;
    if (currentAccount && currentAccount.id === accountId) {
      this.currentAccountSubject.next({ ...currentAccount, status });
    }
  }

  // Set the active bank account
  setCurrentAccount(accountId: string): void {
    const account = this.bankAccountsSubject.value.find(acc => acc.id === accountId);
    if (account) {
      this.currentAccountSubject.next(account);
      localStorage.setItem('currentPlaidAccount', JSON.stringify(account));
    }
  }

  // Remove a bank account
  removeBankAccount(accountId: string): void {
    const accounts = this.bankAccountsSubject.value.filter(acc => acc.id !== accountId);
    this.bankAccountsSubject.next(accounts);
    this.saveBankAccounts(accounts);

    // Clear current account if it was removed
    const currentAccount = this.currentAccountSubject.value;
    if (currentAccount && currentAccount.id === accountId) {
      const newCurrentAccount = accounts.find(acc => acc.status === 'active') || null;
      this.currentAccountSubject.next(newCurrentAccount);
    }
  }

  // Simulate Plaid Link process
  async initiatePlaidLink(): Promise<PlaidLinkResult> {
    return new Promise((resolve) => {
      setTimeout(() => {
        const success = Math.random() > 0.1; // 90% success rate for demo
        
        if (success) {
          const newAccount: BankAccount = {
            id: `account-${Date.now()}`,
            name: 'New Checking Account',
            type: 'checking',
            mask: Math.floor(Math.random() * 9999).toString().padStart(4, '0'),
            institutionName: this.getRandomBank(),
            isLinked: true,
            linkDate: new Date(),
            status: 'active'
          };
          
          this.addBankAccount(newAccount);
          
          resolve({
            success: true,
            publicToken: 'public-token-' + Date.now(),
            metadata: {
              institution: newAccount.institutionName,
              account: newAccount
            }
          });
        } else {
          resolve({
            success: false,
            error: 'Failed to link bank account. Please try again.'
          });
        }
      }, 2000);
    });
  }

  // Get account summary for display
  getAccountSummary(): string {
    const currentAccount = this.currentAccountSubject.value;
    if (!currentAccount) {
      return 'No bank account linked';
    }
    
    return `${currentAccount.institutionName} •••• ${currentAccount.mask}`;
  }

  // Check if user has any linked accounts
  hasLinkedAccounts(): boolean {
    return this.bankAccountsSubject.value.some(acc => acc.status === 'active');
  }

  // Method to refresh data after user authentication
  refreshBankAccountData(): void {
    this.loadBankAccounts();
  }

  // Private helper methods
  private saveBankAccounts(accounts: BankAccount[]): void {
    try {
      localStorage.setItem('plaidBankAccounts', JSON.stringify(accounts));
    } catch (error) {
      console.error('Error saving bank accounts:', error);
    }
  }

  private getRandomBank(): string {
    const banks = [
      'Chase Bank',
      'Bank of America',
      'Wells Fargo',
      'Citibank',
      'Capital One',
      'TD Bank',
      'PNC Bank',
      'US Bank'
    ];
    return banks[Math.floor(Math.random() * banks.length)];
  }

  // Format date for display
  formatLinkDate(date: Date): string {
    return new Intl.DateTimeFormat('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric'
    }).format(date);
  }

  // Get status display text
  getStatusText(status: BankAccount['status']): string {
    switch (status) {
      case 'active':
        return 'Connected';
      case 'inactive':
        return 'Disconnected';
      case 'error':
        return 'Connection Error';
      default:
        return 'Unknown';
    }
  }

  // Get status color for UI
  getStatusColor(status: BankAccount['status']): string {
    switch (status) {
      case 'active':
        return 'success';
      case 'inactive':
        return 'medium';
      case 'error':
        return 'danger';
      default:
        return 'medium';
    }
  }
}
