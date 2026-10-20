import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { PLAID_LINK_RETURN_KEY, PLAID_LINK_TOKEN_KEY } from '../../utils/plaid-link-session';
import { LinkPlaidComponent } from './linkplaid.component';

/** The onboarding route is /link-bank (app.routes.ts:38); the OAuth return lands there with oauth_state_id. */
const RETURN_HREF = 'https://fredvested.com/link-bank?oauth_state_id=abc123';

describe('LinkPlaidComponent OAuth round trip', () => {
  let post: jasmine.Spy;
  let open: jasmine.Spy;
  let navCtrl: jasmine.SpyObj<NavController>;
  let component: LinkPlaidComponent;
  let plaidConfig: any;

  beforeEach(() => {
    plaidConfig = null;
    open = jasmine.createSpy('open');
    (window as any).Plaid = { create: (config: any) => { plaidConfig = config; return { open }; } };
    sessionStorage.clear();
    post = jasmine.createSpy('post').and.returnValue(of({ link_token: 'link-sandbox-token', expiration: '' }));
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    component = new LinkPlaidComponent(
      { post } as unknown as HttpClient,
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      navCtrl,
      { completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})) } as unknown as AuthService,
      jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['trackOnboardingStepCompleted', 'trackErrorShown']),
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

  it('on the OAuth return it resumes Link with the stored token and receivedRedirectUri', async () => {
    sessionStorage.setItem(PLAID_LINK_TOKEN_KEY, 'link-stored-token');
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).not.toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-stored-token');
    expect(plaidConfig.receivedRedirectUri).toBe(RETURN_HREF);
    expect(open).toHaveBeenCalled();
  });

  it('an OAuth return with no stored token starts Link over with a fresh token', async () => {
    spyOn(component as any, 'oauthReturnHref').and.returnValue(RETURN_HREF);

    await component.initializePlaidLink();

    expect(post).toHaveBeenCalled();
    expect(plaidConfig.token).toBe('link-sandbox-token');
    expect(plaidConfig.receivedRedirectUri).toBeUndefined();
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
