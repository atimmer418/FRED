import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY, PLAID_OAUTH_RETURN_HREF_KEY } from '../../utils/plaid-link-session';
import { LinkPlaidComponent } from './linkplaid.component';

/**
 * The onboarding route is /link-bank (app.routes.ts:38). It is also the one redirect URI every link
 * token names (R9; local https://local.fredvested.com/link-bank), so every OAuth return lands here,
 * including one that started on /change-bank-account.
 */
const RETURN_HREF = 'https://local.fredvested.com/link-bank?oauth_state_id=abc123';

describe('LinkPlaidComponent OAuth round trip', () => {
  let post: jasmine.Spy;
  let open: jasmine.Spy;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let component: LinkPlaidComponent;
  let plaidConfig: any;

  function storeSession(token: string, marker: string): void {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, token);
    sessionStorage.setItem(PLAID_LINK_RETURN_KEY, marker);
  }

  beforeEach(() => {
    plaidConfig = null;
    open = jasmine.createSpy('open');
    (window as any).Plaid = { create: (config: any) => { plaidConfig = config; return { open }; } };
    sessionStorage.clear();
    post = jasmine.createSpy('post').and.returnValue(of({ link_token: 'link-sandbox-token', expiration: '' }));
    router = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']);
    router.navigateByUrl.and.returnValue(Promise.resolve(true));
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
    ]);
    component = new LinkPlaidComponent(
      { post } as unknown as HttpClient,
      router,
      navCtrl,
      { completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})) } as unknown as AuthService,
      analytics,
    );
    (component as any).isUserAuthenticated = true;
  });

  afterEach(() => {
    delete (window as any).Plaid;
    sessionStorage.clear();
  });

  it('posts mode link and stores the token for the round trip', async () => {
    await component.initializePlaidLink();

    expect(post.calls.mostRecent().args[1]).toEqual({ mode: 'link' });
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('onboarding');
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
  });

  it('resumes an onboarding return in place with the stored token and receivedRedirectUri (onboardingReturnStillResumesInPlace)', async () => {
    storeSession('link-stored-token', 'onboarding');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).not.toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-stored-token');
    expect(plaidConfig.receivedRedirectUri).toBe(RETURN_HREF);
    expect(open).toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('consumes the stored session as soon as it resumes, so a stale oauth_state_id cannot replay it', async () => {
    storeSession('link-stored-token', 'onboarding');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });

  it('forwards a change-bank return to /change-bank-account with the query string kept (forwardsChangeBankReturnWithQueryStringPreserved)', async () => {
    storeSession('link-change-bank-token', 'change-bank');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/change-bank-account?oauth_state_id=abc123', { replaceUrl: true });
  });

  it('leaves the session to the change-bank page and never builds Link here (forwardNeverClearsSessionOrCreatesLink)', async () => {
    storeSession('link-change-bank-token', 'change-bank');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).not.toHaveBeenCalled();
    expect(plaidConfig).toBeNull();
    expect(open).not.toHaveBeenCalled();
    expect(navCtrl.navigateForward).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-change-bank-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('change-bank');
  });

  it('stores the /link-bank href Plaid redirected to before forwarding (forwardStoresOriginalLinkBankHref)', async () => {
    storeSession('link-change-bank-token', 'change-bank');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(sessionStorage.getItem(PLAID_OAUTH_RETURN_HREF_KEY)).toBe(RETURN_HREF);
  });

  it('records no onboarding step view for a change-bank return passing through', () => {
    storeSession('link-change-bank-token', 'change-bank');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).not.toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/change-bank-account?oauth_state_id=abc123', { replaceUrl: true });
  });

  it('a corrupted return marker does not resume here and is discarded', async () => {
    storeSession('link-corrupted-token', 'settings');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(open).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBe('onboarding');
  });

  it('an OAuth return with no stored token starts Link over with a fresh token', async () => {
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('success and exit both clear the stored link session', async () => {
    await component.initializePlaidLink();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onExit(null, {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();

    await component.initializePlaidLink();
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBe('link-sandbox-token');
    plaidConfig.onSuccess('public-sandbox-token', {});
    expect(sessionStorage.getItem(PLAID_LINK_TOKEN_KEY)).toBeNull();
    expect(sessionStorage.getItem(PLAID_LINK_RETURN_KEY)).toBeNull();
  });

  it('goBack returns to KYC verification', () => {
    component.goBack();

    expect(navCtrl.navigateBack).toHaveBeenCalledWith('/kyc-verification');
  });
});
