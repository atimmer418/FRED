import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { BankAccount, PlaidService } from './plaid.service';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { environment } from '../../environments/environment';

/**
 * GET /api/plaid/primary-bank-account now returns the account mask (and itemStatus) instead of the
 * Plaid access token. The service used to hardcode '****' because the backend stored no mask; it
 * must pass the real one through and keep '****' only for links made before the mask was stored.
 */
describe('PlaidService primary bank account mask', () => {
  const backendAccount = {
    accountId: 'plaid-acct-1',
    institutionName: 'Chase',
    accountName: 'Chase Checking',
    accountType: 'depository',
    accountSubtype: 'checking',
    itemStatus: 'OK',
  };

  let get: jasmine.Spy;
  let service: PlaidService;

  beforeEach(() => {
    localStorage.clear();
    spyOn(JwtTokenUtils, 'getValidJwtToken').and.returnValue('jwt-token');
    spyOn(JwtTokenUtils, 'isJwtExpired').and.returnValue(false);
    get = jasmine.createSpy('get');
    service = new PlaidService({ get } as unknown as HttpClient);
  });

  afterEach(() => localStorage.clear());

  /** BehaviorSubject replays synchronously, so one subscribe/unsubscribe reads the current value. */
  function currentAccount(): BankAccount | null {
    const seen: (BankAccount | null)[] = [];
    service.getCurrentAccount().subscribe(account => seen.push(account)).unsubscribe();
    return seen[0] ?? null;
  }

  it('reads the primary bank account from the backend when a valid JWT is present', () => {
    get.and.returnValue(of({ ...backendAccount, mask: '1234' }));

    service.refreshBankAccountData();

    expect(get.calls.mostRecent().args[0]).toBe(`${environment.backendApiUrl}/plaid/primary-bank-account`);
  });

  it('passes the backend mask through to the BankAccount', () => {
    get.and.returnValue(of({ ...backendAccount, mask: '1234' }));

    service.refreshBankAccountData();

    expect(currentAccount()?.mask).toBe('1234');
    expect(service.getAccountSummary()).toBe('Chase •••• 1234');
  });

  it('falls back to **** for a link made before the mask was stored', () => {
    get.and.returnValue(of({ ...backendAccount, mask: null }));

    service.refreshBankAccountData();

    expect(currentAccount()?.mask).toBe('****');
  });

  it('never carries an accessToken field, whatever the backend sends', () => {
    get.and.returnValue(of({ ...backendAccount, mask: '1234', accessToken: 'should-never-be-read' }));

    service.refreshBankAccountData();

    const account = currentAccount() as unknown as Record<string, unknown>;
    expect(account['accessToken']).toBeUndefined();
  });
});
