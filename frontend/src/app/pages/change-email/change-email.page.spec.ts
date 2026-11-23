import { fakeAsync, tick } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import { ChangeEmailPage } from './change-email.page';
import { PasskeyService } from '../../services/passkey.service';
import { NativePasskeyService } from '../../services/native-passkey.service';
import { KeychainSyncService } from '../../services/keychain-sync.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';

/**
 * Change of email: the two-step flow and its analytics call sites.
 *
 * The thing being changed here is an email address, which is the single most identifying value in
 * the app, so no event on this page carries a value at all: only that an error was shown, which
 * category it was, and the status behind it.
 */
describe('ChangeEmailPage', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let passkey: jasmine.SpyObj<PasskeyService>;
  let nativePasskey: { isAvailable: boolean; authenticate: jasmine.Spy };
  let http: jasmine.SpyObj<HttpClient>;
  let keychain: jasmine.SpyObj<KeychainSyncService>;
  let router: jasmine.SpyObj<Router>;
  let page: ChangeEmailPage;

  const ASSERTION = { id: 'cred-1', response: { signature: 'sig' } };

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    passkey = jasmine.createSpyObj<PasskeyService>('PasskeyService', [
      'startAuthentication', 'startAuthenticationForUser', 'finishAuthentication',
    ]);
    // The native path keeps the spec off the real browser WebAuthn API.
    nativePasskey = { isAvailable: true, authenticate: jasmine.createSpy('authenticate').and.resolveTo(ASSERTION) };
    keychain = jasmine.createSpyObj<KeychainSyncService>('KeychainSyncService', ['storeAccountEmail']);
    keychain.storeAccountEmail.and.resolveTo(true);
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['post']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    page = new ChangeEmailPage(
      router,
      http,
      passkey,
      nativePasskey as unknown as NativePasskeyService,
      keychain,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );

    page.currentEmail = 'old@example.test';
    page.newEmail = 'new@example.test';
    passkey.startAuthenticationForUser.and.returnValue(of({
      requestOptions: JSON.stringify({ publicKey: { challenge: 'abc', rpId: 'example.test' } }),
      sessionId: 'challenge-session',
    }));
  });

  it('sends the passkey assertion to the server with the request, and never finishes a sign-in', fakeAsync(() => {
    http.post.and.returnValue(of({ message: 'sent' }));

    page.initiateEmailChange();
    tick();

    expect(http.post).toHaveBeenCalledTimes(1);
    const [url, body] = http.post.calls.mostRecent().args;
    expect(url).toContain('/user/email-change/start');
    expect(body).toEqual({
      newEmail: 'new@example.test',
      passkeySessionId: 'challenge-session',
      credential: ASSERTION,
    });
    // The body names no user: the server takes that from the session.
    expect(Object.keys(body)).not.toContain('currentEmail');
    expect(Object.keys(body)).not.toContain('userId');
    expect(passkey.finishAuthentication).not.toHaveBeenCalled();
    expect(page.step).toBe('code');
  }));

  it('a failed or dismissed passkey prompt sends nothing and reports auth_required once', fakeAsync(() => {
    nativePasskey.authenticate.and.rejectWith(new Error('cancelled'));

    page.initiateEmailChange();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'my_profile', 'toast', null, false);
    expect(http.post).not.toHaveBeenCalled();
    expect(page.step).toBe('email');
  }));

  it('a challenge the server could not give is a server failure, not the user declining', fakeAsync(() => {
    passkey.startAuthenticationForUser.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page.initiateEmailChange();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'my_profile', 'toast', 500, false);
    expect(nativePasskey.authenticate).not.toHaveBeenCalled();
    expect(http.post).not.toHaveBeenCalled();
    expect(page.isLoading).toBeFalse();
  }));

  it('a server rejection reports save_failed with its status and stays on the first step', fakeAsync(() => {
    http.post.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'EMAIL_IN_USE', message: 'That email is already in use.' } })),
    );

    page.initiateEmailChange();
    tick();

    // The server's message may name the address; only the status goes out.
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'my_profile', 'toast', 409, false);
    expect(page.step).toBe('email');
  }));

  it('a request that never reached the server reports network', fakeAsync(() => {
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.initiateEmailChange();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'my_profile', 'toast', null, false);
  }));

  it('an unchanged address never gets as far as a passkey prompt or an event', fakeAsync(() => {
    page.newEmail = page.currentEmail;

    page.initiateEmailChange();
    tick();

    expect(passkey.startAuthenticationForUser).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));

  it('the email is only treated as changed after the code is confirmed', fakeAsync(() => {
    spyOn(localStorage, 'setItem');
    http.post.and.returnValue(of({ message: 'sent' }));
    page.initiateEmailChange();
    tick();
    expect(localStorage.setItem).not.toHaveBeenCalledWith('userEmail', jasmine.anything());

    page.code = '123456';
    const storeToken = spyOn(JwtTokenUtils, 'storeJwtToken');
    http.post.and.returnValue(of({ newEmail: 'new@example.test', jwtToken: 'new-token' }));
    page.confirmCode();
    tick();

    // The new token replaces the old one, which names the old email and no longer works.
    expect(storeToken).toHaveBeenCalledOnceWith('new-token', undefined, 'new@example.test');
    expect(keychain.storeAccountEmail).toHaveBeenCalledOnceWith('new@example.test');

    // A second tap while the page is leaving does nothing.
    page.confirmCode();
    tick(1500);
    expect(http.post).toHaveBeenCalledTimes(2);

    const [url, body] = http.post.calls.mostRecent().args;
    expect(url).toContain('/user/email-change/confirm');
    expect(body).toEqual({ code: '123456' });
    expect(localStorage.setItem).toHaveBeenCalledWith('userEmail', 'new@example.test');
    expect(router.navigate).toHaveBeenCalledWith(['/security-settings']);
  }));

  it('a code that is not six digits is not sent', fakeAsync(() => {
    page.step = 'code';
    page.code = '12345';

    page.confirmCode();
    tick();

    expect(http.post).not.toHaveBeenCalled();
  }));

  it('a wrong code keeps the user on the code step; an expired one sends them back to start over', fakeAsync(() => {
    page.step = 'code';
    page.code = '123456';
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400, error: { code: 'INVALID_CODE' } })));
    page.confirmCode();
    tick();
    expect(page.step).toBe('code');
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'my_profile', 'toast', 400, false);

    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400, error: { code: 'CODE_EXPIRED' } })));
    page.confirmCode();
    tick();
    expect(page.step).toBe('email');

    // The address was taken while the code was in the inbox: that code is dead too.
    page.step = 'code';
    page.code = '123456';
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'EMAIL_IN_USE' } })));
    page.confirmCode();
    tick();
    expect(page.step).toBe('email');
  }));
});
