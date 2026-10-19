import { fakeAsync, tick } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { throwError } from 'rxjs';
import { ChangeEmailPage } from './change-email.page';
import { AuthService } from '../../services/auth.service';
import { PasskeyService } from '../../services/passkey.service';
import { PinService } from '../../services/pin.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

/**
 * Analytics call sites on change-email.
 *
 * The thing being changed here is an email address, which is the single most identifying value in
 * the app, so no event on this page carries a value at all: only that an error was shown, which
 * category it was, and the status behind it.
 */
describe('ChangeEmailPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let passkey: jasmine.SpyObj<PasskeyService>;
  let pin: jasmine.SpyObj<PinService>;
  let http: jasmine.SpyObj<HttpClient>;
  let page: ChangeEmailPage;

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    passkey = jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication', 'finishAuthentication']);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['post']);

    page = new ChangeEmailPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      http,
      jasmine.createSpyObj<AuthService>('AuthService', ['getCurrentUserId']),
      passkey,
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );

    page.currentEmail = 'old@example.test';
    page.newEmail = 'new@example.test';
    // The passkey challenge never arrives, so every spec below starts from a failed biometric.
    passkey.startAuthentication.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
  });

  it('a failed passkey with no PIN to fall back on reports auth_required once', fakeAsync(() => {
    pin.hasPin.and.resolveTo(false);

    page.initiateEmailChange();
    tick();

    // One event per attempt, not one per factor.
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'my_profile', 'toast', null, false);
    expect(http.post).not.toHaveBeenCalled();
  }));

  it('a dismissed PIN fallback reports the same single auth_required', fakeAsync(() => {
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(false);

    page.initiateEmailChange();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'my_profile', 'toast', null, false);
  }));

  it('a server rejection after the PIN fallback passes reports save_failed with its status', fakeAsync(() => {
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(true);
    http.post.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'new@example.test is taken' } })),
    );

    page.initiateEmailChange();
    tick();

    // The server's message names the address; only the status goes out.
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('save_failed', 'my_profile', 'toast', 409, false);
  }));

  it('a write that never reached the server reports network', fakeAsync(() => {
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(true);
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page.initiateEmailChange();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'my_profile', 'toast', null, false);
  }));

  it('an unchanged address never gets as far as an event', fakeAsync(() => {
    page.newEmail = page.currentEmail;

    page.initiateEmailChange();
    tick();

    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  }));
});
