import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, of, throwError } from 'rxjs';
import { PlaidService } from './plaid.service';

/**
 * The typed reader behind the reconnect card. It must never invent a bank: the display cache's
 * localStorage and demo fallback would show a healthy "Chase Bank 1234" over a broken Item.
 */
describe('PlaidService.getPrimaryBankAccount', () => {
  let http: jasmine.SpyObj<HttpClient>;
  let service: PlaidService;

  beforeEach(() => {
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get']);
    service = new PlaidService(http);
  });

  it('types the response with itemStatus, the consent flag and the expiry', async () => {
    http.get.and.returnValue(of({
      accountId: 'acc-1', institutionName: 'Chase', accountName: 'Plaid Checking', accountType: 'depository',
      accountSubtype: 'checking', mask: '0000', itemStatus: 'PENDING_DISCONNECT', transactionsConsented: false,
      consentExpiresAt: '2026-11-20T09:30-05:00',
    }));

    expect(await firstValueFrom(service.getPrimaryBankAccount())).toEqual({
      accountId: 'acc-1', institutionName: 'Chase', accountName: 'Plaid Checking', accountType: 'depository',
      accountSubtype: 'checking', mask: '0000', itemStatus: 'PENDING_DISCONNECT', transactionsConsented: false,
      consentExpiresAt: '2026-11-20T09:30-05:00',
    });
    expect(http.get.calls.mostRecent().args[0]).toMatch(/\/plaid\/primary-bank-account$/);
  });

  it('turns a Jackson date array into an ISO string and missing keys into null', async () => {
    http.get.and.returnValue(of({ accountId: 'acc-1', institutionName: 'Chase', itemStatus: 'OK', consentExpiresAt: [2026, 11, 20, 9, 30] }));

    expect(await firstValueFrom(service.getPrimaryBankAccount())).toEqual({
      accountId: 'acc-1', institutionName: 'Chase', accountName: null, accountType: null, accountSubtype: null,
      mask: null, itemStatus: 'OK', transactionsConsented: null, consentExpiresAt: '2026-11-20T09:30',
    });
  });

  it('is null when unlinked or on any error, and never touches the display cache', async () => {
    http.get.and.returnValue(of({ accountId: null, institutionName: null, itemStatus: 'OK' }));
    expect(await firstValueFrom(service.getPrimaryBankAccount())).toBeNull();

    http.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    expect(await firstValueFrom(service.getPrimaryBankAccount())).toBeNull();

    expect(await firstValueFrom(service.getCurrentAccount())).toBeNull();
  });
});
