/**
 * AuthService.refreshToken(): what the app does with the server's answer.
 *
 * A passkey sign-in lasts 12 hours on the server, and a session can be revoked or replaced. The
 * app must (1) never let a late refresh answer overwrite a newer token, and (2) drop a token the
 * server has refused, so the passkey prompt appears instead of every request failing.
 */
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { DeviceIdService } from './device-id.service';
import { KeychainSyncService } from './keychain-sync.service';
import { NativePasskeyService } from './native-passkey.service';
import { environment } from '../../environments/environment';

const REFRESH_URL = `${environment.backendApiUrl}/auth/refresh`;

function makeToken(tag: string, secondsFromNow = 3600): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify({ sub: 'a@example.com', tag, exp: Math.floor(Date.now() / 1000) + secondsFromNow }));
  return `${header}.${body}.sig`;
}

function storeToken(token: string): void {
  localStorage.setItem('jwtToken', token);
  localStorage.setItem('jwtExpiration', String(Math.floor(Date.now() / 1000) + 3600));
}

describe('AuthService.refreshToken()', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  const current = makeToken('current');

  beforeEach(() => {
    localStorage.clear(); // no token during construction, so checkExistingSession does nothing
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        AuthService,
        { provide: DeviceIdService, useValue: { getDeviceId: () => 'test-device-id' } },
        { provide: KeychainSyncService, useValue: { getAccountEmail: () => Promise.resolve(null), storeAccountEmail: () => Promise.resolve() } },
        { provide: NativePasskeyService, useValue: { isPasskeyAvailable: () => Promise.resolve(false) } },
      ],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    storeToken(current);
    localStorage.setItem('userId', '7');
    localStorage.setItem('userEmail', 'a@example.com');
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('stores the refreshed token', () => {
    const refreshed = makeToken('refreshed');
    service.refreshToken().subscribe();
    httpMock.expectOne(REFRESH_URL).flush({ success: true, jwtToken: refreshed, id: 7, email: 'a@example.com' });

    expect(localStorage.getItem('jwtToken')).toBe(refreshed);
  });

  it('does not let a late answer replace a newer token from a passkey sign-in', () => {
    const fromSignIn = makeToken('from-passkey-sign-in');
    service.refreshToken().subscribe();
    const pending = httpMock.expectOne(REFRESH_URL);

    storeToken(fromSignIn); // the passkey sign-in finished while the refresh was in flight
    pending.flush({ success: true, jwtToken: makeToken('stale-refresh'), id: 7, email: 'a@example.com' });

    expect(localStorage.getItem('jwtToken')).toBe(fromSignIn);
  });

  it('drops the token when the server refuses it, and keeps who the user is', () => {
    service.refreshToken().subscribe();
    httpMock.expectOne(REFRESH_URL).flush({ message: 'Session has ended. Sign in again.' }, { status: 401, statusText: 'Unauthorized' });

    expect(localStorage.getItem('jwtToken')).toBeNull();
    expect(localStorage.getItem('jwtExpiration')).toBeNull();
    expect(localStorage.getItem('userId')).toBe('7');
    expect(localStorage.getItem('userEmail')).toBe('a@example.com');
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('a late 401 does not drop a newer token from a passkey sign-in', () => {
    const fromSignIn = makeToken('from-passkey-sign-in');
    service.refreshToken().subscribe();
    const pending = httpMock.expectOne(REFRESH_URL);

    storeToken(fromSignIn);
    pending.flush({ message: 'Session has been revoked' }, { status: 401, statusText: 'Unauthorized' });

    expect(localStorage.getItem('jwtToken')).toBe(fromSignIn);
  });

  it('keeps the token when the refresh fails for any other reason', () => {
    service.refreshToken().subscribe();
    httpMock.expectOne(REFRESH_URL).flush('boom', { status: 503, statusText: 'Service Unavailable' });
    expect(localStorage.getItem('jwtToken')).toBe(current);

    service.refreshToken().subscribe();
    httpMock.expectOne(REFRESH_URL).error(new ProgressEvent('error'));
    expect(localStorage.getItem('jwtToken')).toBe(current);
  });

  it('can refresh again after a failure', () => {
    service.refreshToken().subscribe();
    httpMock.expectOne(REFRESH_URL).flush('boom', { status: 500, statusText: 'Server Error' });

    const refreshed = makeToken('second-try');
    service.refreshToken().subscribe();
    httpMock.expectOne(REFRESH_URL).flush({ success: true, jwtToken: refreshed, id: 7, email: 'a@example.com' });
    expect(localStorage.getItem('jwtToken')).toBe(refreshed);
  });
});
