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

/** The shape the page reads off the Plaid handler config, narrowed to what these specs drive. */
interface PlaidConfig {
  onExit: (err: unknown, metadata: unknown) => void;
}

/**
 * Analytics call sites on change-bank-account.
 *
 * Only errors are tracked here. The exchange that actually re-points the bank is a server event
 * (`bank_link_completed`), so nothing on the success path fires. The failures split three ways:
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
