import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { accountClosedInterceptor } from './account-closed.interceptor';
import { AccountClosedService } from '../services/account-closed.service';
import { environment } from '../../environments/environment';

describe('accountClosedInterceptor', () => {
  let http: HttpClient;
  let server: HttpTestingController;
  let accountClosed: jasmine.SpyObj<AccountClosedService>;
  const api = environment.backendApiUrl;

  beforeEach(() => {
    accountClosed = jasmine.createSpyObj<AccountClosedService>('AccountClosedService', ['leave']);
    accountClosed.leave.and.resolveTo();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([accountClosedInterceptor])),
        provideHttpClientTesting(),
        { provide: AccountClosedService, useValue: accountClosed },
      ],
    });
    http = TestBed.inject(HttpClient);
    server = TestBed.inject(HttpTestingController);
  });

  afterEach(() => server.verify());

  /** Makes a request, answers it, and reports the status the caller saw. */
  function answer(url: string, status: number, body: string | object): number | null {
    let seen: number | null = null as number | null;
    http.get(url).subscribe({ next: () => { seen = 200; }, error: err => { seen = err.status; } });
    server.expectOne(url).flush(body, { status, statusText: status === 200 ? 'OK' : 'Refused' });
    return seen;
  }

  it('a 401 that says the account is closed signs the device out, and the caller still gets the error', () => {
    const seen = answer(`${api}/user/progress`, 401, { status: 401, error: 'Unauthorized', code: 'ACCOUNT_CLOSED' });

    expect(accountClosed.leave).toHaveBeenCalledOnceWith('closed');
    expect(seen).toBe(401);
  });

  it('the token refresh being told the account is closed signs the device out too', () => {
    let seen = 0;
    http.post(`${api}/auth/refresh`, {}).subscribe({ error: err => { seen = err.status; } });
    server.expectOne(`${api}/auth/refresh`).flush(
      { code: 'ACCOUNT_CLOSED', message: 'This account is closed.' }, { status: 401, statusText: 'Refused' });

    expect(accountClosed.leave).toHaveBeenCalledOnceWith('closed');
    expect(seen).toBe(401);
  });

  it('an ordinary 401 (the session ended) is left alone: the app asks for the passkey as before', () => {
    answer(`${api}/user/progress`, 401, { status: 401, error: 'Unauthorized' });
    answer(`${api}/auth/refresh`, 401, { message: 'No valid token to refresh' });
    answer(`${api}/passkey/authenticate/finish`, 401, { success: false, code: 'PASSKEY_NOT_REGISTERED' });

    expect(accountClosed.leave).not.toHaveBeenCalled();
  });

  it('the code on any other status, or a success, does nothing', () => {
    answer(`${api}/trading/account/close`, 409, { code: 'ACCOUNT_CLOSED' });
    answer(`${api}/user/progress`, 200, { code: 'ACCOUNT_CLOSED' });
    answer(`${api}/user/progress`, 401, 'ACCOUNT_CLOSED');

    expect(accountClosed.leave).not.toHaveBeenCalled();
  });

  it('an answer from any other server is never trusted to sign the device out', () => {
    answer('https://example.test/api/thing', 401, { code: 'ACCOUNT_CLOSED' });

    expect(accountClosed.leave).not.toHaveBeenCalled();
  });
});
