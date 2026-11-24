import { fakeAsync, tick } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import { ChangeBankAccountPage } from './change-bank-account.page';
import { PlaidService } from '../services/plaid.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { PLAID_LINK_MODE_KEY, PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY } from '../utils/plaid-link-session';

/** The shape the page reads off the Plaid handler config, narrowed to what these specs drive. */
interface PlaidConfig {
  onExit: (err: unknown, metadata: unknown) => void;
}

/**
 * Analytics call sites on change-bank-account.
 *
 * Only errors are tracked here. The exchange that actually re-points the bank is a server event
 * (`plaid_linked`), so nothing on the success path fires. The failures split three ways:
 * the CDN script (network), the Plaid flow itself (bank_link) and the step-up gate
 * (auth_required), and the display differs because some of them are an inline banner rather than
 * a toast.
 */
describe('ChangeBankAccountPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let http: jasmine.SpyObj<HttpClient>;
  let pin: jasmine.SpyObj<PinService>;
  let page: ChangeBankAccountPage;
  let plaidGlobal: { Plaid?: unknown };
  let createdConfig: PlaidConfig | null;

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);

    page = new ChangeBankAccountPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      http,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<PlaidService>('PlaidService', ['refreshBankAccountData']),
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );

    createdConfig = null;
    plaidGlobal = window as unknown as { Plaid?: unknown };
  });

  afterEach(() => {
    delete plaidGlobal.Plaid;
  });

  /** Stands in for the vendor global the page talks to through `declare var Plaid`. */
  function installPlaid() {
    plaidGlobal.Plaid = {
      create: (config: PlaidConfig) => {
        createdConfig = config;
        return { open: () => undefined };
      },
    };
  }

  // ── error_shown: the CDN script ────────────────────────────────────────────

  it('a Plaid script that will not load reports network on an inline banner, with no retry', () => {
    const appended = spyOn(document.head, 'appendChild').and.callFake(<T extends Node>(node: T): T => node);

    page['loadPlaidScript']();
    const script = appended.calls.mostRecent().args[0] as HTMLScriptElement;
    script.onerror?.(new Event('error'));

    // The banner does carry a Try again button, but it is disabled until Plaid is ready, which it
    // never became here.
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'inline_banner', null, false);
  });

  // ── error_shown: initialising the link ─────────────────────────────────────

  it('a link token the server refuses reports bank_link with its status', fakeAsync(() => {
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    page['initializePlaidLink']();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('bank_link', 'portfolio', 'inline_banner', 500, false);
  }));

  it('a link token request that never reached the server reports network', fakeAsync(() => {
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    page['initializePlaidLink']();
    tick();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'inline_banner', null, false);
  }));

  // ── error_shown: the Plaid flow ────────────────────────────────────────────

  it('an error exit from Plaid reports bank_link; a plain close reports nothing', fakeAsync(() => {
    installPlaid();
    http.post.and.returnValue(of({ link_token: 'link-sandbox-token' }));

    page['initializePlaidLink']();
    tick();

    createdConfig!.onExit(null, {});
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();

    createdConfig!.onExit({ error_message: 'INSTITUTION_DOWN' }, {});
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('bank_link', 'portfolio', 'inline_banner', null, false);
  }));

  it('tapping connect before Plaid is ready reports bank_link', async () => {
    await page.openPlaid();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('bank_link', 'portfolio', 'inline_banner', null, false);
  });

  it('a dismissed step-up before opening Plaid reports auth_required on a toast', async () => {
    page['plaidHandler'] = { open: () => undefined };
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(false);

    await page.openPlaid();

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('auth_required', 'portfolio', 'toast', null, false);
  });

  it('a passed step-up fires nothing and opens Plaid', async () => {
    const handler = { open: jasmine.createSpy('open') };
    page['plaidHandler'] = handler;
    pin.hasPin.and.resolveTo(true);
    pin.promptPin.and.resolveTo(true);

    await page.openPlaid();

    expect(handler.open).toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  });

  // ── error_shown: the exchange ──────────────────────────────────────────────

  it('a failed public-token exchange reports bank_link with its status', () => {
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400 })));

    page['exchangePublicTokenAuthenticated']('public-sandbox-token');

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('bank_link', 'portfolio', 'toast', 400, false);
  });

  it('a successful exchange fires no client event: the link is the server’s to report', () => {
    http.post.and.returnValue(of({ ok: true }));

    page['exchangePublicTokenAuthenticated']('public-sandbox-token');

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  });
});

/**
 * Update mode, reached from the reconnect card as /change-bank-account?mode=update (spec 2.3, 8.8).
 * The same Item and access token are kept, so success is update-mode-complete, never an exchange.
 */
describe('ChangeBankAccountPage update mode', () => {
  const RETURN_HREF = 'https://local.fredvested.com/change-bank-account?oauth_state_id=abc123';
  let http: jasmine.SpyObj<HttpClient>;
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let toast: jasmine.SpyObj<ToastService>;
  let page: ChangeBankAccountPage;
  let plaidConfig: any;
  let open: jasmine.Spy;

  const unavailable = () => throwError(() => new HttpErrorResponse({
    status: 409,
    error: { code: 'UPDATE_MODE_UNAVAILABLE', message: 'Plaid could not reopen this bank connection. Link the bank again.' },
  }));
  const postedUrls = () => http.post.calls.allArgs().map(args => args[0] as string);

  beforeEach(() => {
    plaidConfig = null;
    open = jasmine.createSpy('open');
    (window as any).Plaid = { create: (config: any) => { plaidConfig = config; return { open }; } };
    sessionStorage.clear();
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    http.get.and.returnValue(of({}));
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown', 'screen']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    const pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    page = new ChangeBankAccountPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      http,
      toast,
      jasmine.createSpyObj<PlaidService>('PlaidService', ['refreshBankAccountData']),
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
    spyOn(page as any, 'requestedLinkMode').and.returnValue('update');
  });

  afterEach(() => {
    delete (window as any).Plaid;
    sessionStorage.clear();
  });

  it('posts mode update and stores the mode with the session only after the step-up', async () => {
    http.post.and.returnValue(of({ link_token: 'link-update-token' }));

    await page['initializePlaidLink']();

    expect(http.post.calls.mostRecent().args[1]).toEqual({ mode: 'update' });
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBeNull();

    await page.openPlaid();

    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('change-bank');
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBe('update');
    expect(open).toHaveBeenCalled();
  });

  it('updateModeFallsBackToLinkOn409: UPDATE_MODE_UNAVAILABLE retries with link, silently', async () => {
    http.post.and.returnValues(unavailable(), of({ link_token: 'link-fresh-token' }), of({ message: 'ok' }));

    await page['initializePlaidLink']();

    expect(http.post.calls.allArgs().map(args => args[1])).toEqual([{ mode: 'update' }, { mode: 'link' }]);
    expect(plaidConfig.token).toBe('link-fresh-token');
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();

    await page.openPlaid();
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBe('link');

    plaidConfig.onSuccess('public-sandbox-token', {});
    expect(postedUrls().some(url => url.endsWith('/plaid/exchange_public_token'))).toBeTrue();
    expect(postedUrls().some(url => url.endsWith('/plaid/update-mode-complete'))).toBeFalse();
  });

  it('a 409 for any other reason is not retried and reports bank_link', async () => {
    http.post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'LINK_IN_PROGRESS', message: 'busy' } })));

    await page['initializePlaidLink']();

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('bank_link', 'portfolio', 'inline_banner', 409, false);
  });

  it('updateSuccessCallsCompleteNotExchange: onSuccess posts update-mode-complete with no body', async () => {
    http.post.and.returnValues(of({ link_token: 'link-update-token' }), of({ result: 'REPAIRED' }));

    await page['initializePlaidLink']();
    await page.openPlaid();
    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});

    expect(postedUrls().filter(url => url.endsWith('/plaid/update-mode-complete')).length).toBe(1);
    expect(postedUrls().some(url => url.endsWith('/plaid/exchange_public_token'))).toBeFalse();
    expect(http.post.calls.mostRecent().args[1]).toEqual({});
    expect(toast.showToast).toHaveBeenCalledWith('Your bank is reconnected.', 'success', 3000);
    expect(sessionStorage.getItem(PLAID_LINK_MODE_KEY)).toBeNull();
  });

  it('CONSENT_STILL_MISSING says so instead of claiming success', async () => {
    http.post.and.returnValues(of({ link_token: 'link-update-token' }), of({ result: 'CONSENT_STILL_MISSING' }));

    await page['initializePlaidLink']();
    await page.openPlaid();
    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});

    expect(toast.showToast).toHaveBeenCalledWith('FRED still needs permission to read deposits at your bank.', 'warning', 5000);
  });

  it('a failed completion reports bank_link on a toast with its status', async () => {
    http.post.and.returnValues(of({ link_token: 'link-update-token' }), throwError(() => new HttpErrorResponse({ status: 502 })));

    await page['initializePlaidLink']();
    await page.openPlaid();
    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('bank_link', 'portfolio', 'toast', 502, false);
    expect(page.statusMessage).toBe('Could not finish reconnecting your bank. Please try again.');
  });

  it('an OAuth return resumes in the mode stored with the session, not the URL', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, 'change-bank');
    sessionStorage.setItem(PLAID_LINK_MODE_KEY, 'update');
    // The return URL carries oauth_state_id, not ?mode=update.
    (page as any).requestedLinkMode.and.returnValue('link');
    spyOn(page as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);
    http.post.and.returnValue(of({ result: 'CONSENT_GRANTED' }));

    await page['initializePlaidLink']();
    expect(http.post).not.toHaveBeenCalled();

    plaidConfig.onSuccess('public-token-unused-in-update-mode', {});
    expect(postedUrls()).toEqual([jasmine.stringMatching(/\/plaid\/update-mode-complete$/)]);
  });
});
