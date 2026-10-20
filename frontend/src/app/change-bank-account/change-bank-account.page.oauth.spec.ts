import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of } from 'rxjs';
import { ChangeBankAccountPage } from './change-bank-account.page';
import { PlaidService } from '../services/plaid.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY } from '../utils/plaid-link-session';

/** The route is /change-bank-account (app.routes.ts:82); the OAuth return lands there with oauth_state_id. */
const RETURN_HREF = 'https://fredvested.com/change-bank-account?oauth_state_id=abc123';

describe('ChangeBankAccountPage OAuth round trip', () => {
  let http: jasmine.SpyObj<HttpClient>;
  let open: jasmine.Spy;
  let page: ChangeBankAccountPage;
  let plaidConfig: any;

  beforeEach(() => {
    plaidConfig = null;
    open = jasmine.createSpy('open');
    (window as any).Plaid = { create: (config: any) => { plaidConfig = config; return { open }; } };
    sessionStorage.clear();
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    http.post.and.returnValue(of({ link_token: 'link-sandbox-token' }));
    http.get.and.returnValue(of({}));
    page = new ChangeBankAccountPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      http,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<PlaidService>('PlaidService', ['refreshBankAccountData']),
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown', 'screen']),
    );
  });

  afterEach(() => {
    delete (window as any).Plaid;
    sessionStorage.clear();
  });

  it('posts mode link and stores the token with the change-bank marker', async () => {
    await page['initializePlaidLink']();

    expect(http.post.calls.mostRecent().args[1]).toEqual({ mode: 'link' });
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('change-bank');
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
  });

  it('on the OAuth return it resumes Link with the stored token and opens it without a new token request', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    spyOn(page as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await page['initializePlaidLink']();

    expect(http.post).not.toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-stored-token');
    expect(plaidConfig.receivedRedirectUri).toBe(RETURN_HREF);
    expect(open).toHaveBeenCalled();
  });

  it('success and exit both clear the stored link session', async () => {
    await page['initializePlaidLink']();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onExit(null, {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();

    await page['initializePlaidLink']();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onSuccess('public-sandbox-token', {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });
});
