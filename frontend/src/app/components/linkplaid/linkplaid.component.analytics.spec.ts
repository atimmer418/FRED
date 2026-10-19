import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { config as rxjsConfig, of, throwError } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { LinkPlaidComponent } from './linkplaid.component';

/** The config object the component hands to Plaid.create, captured so its callbacks can be fired. */
interface CapturedPlaidConfig {
  onSuccess: (publicToken: string, metadata: unknown) => void;
  onLoad: () => void;
  onExit: (err: unknown, metadata: unknown) => void;
  onEvent: (name: string, metadata: unknown) => void;
}

describe('LinkPlaidComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: jasmine.SpyObj<Router>;
  let navCtrl: jasmine.SpyObj<NavController>;
  let post: jasmine.Spy;
  let http: HttpClient;
  let authService: AuthService;
  let component: LinkPlaidComponent;
  let plaidConfig: CapturedPlaidConfig | null;
  let plaidBefore: unknown;
  let unhandledErrorHook: ((err: unknown) => void) | undefined;

  /** Skips the JWT read in ngOnInit so the Plaid handler can be built without a stored token. */
  async function openPlaidHandler(): Promise<void> {
    (component as any).isUserAuthenticated = true;
    await component.initializePlaidLink();
  }

  // The component's token exchange rethrows into a bare subscribe(), so a failed exchange ends up
  // as an rxjs unhandled error on a TIMER. The hook has to outlive the spec that caused it:
  // restoring per spec let the timer fire after afterEach, which killed the whole browser run with
  // "An error was thrown in afterAll". Installed for this file only, so other files keep the real
  // reporter and a genuine unhandled error there is still loud.
  beforeAll(() => {
    unhandledErrorHook = rxjsConfig.onUnhandledError ?? undefined;
    rxjsConfig.onUnhandledError = () => undefined;
  });

  afterAll(() => {
    rxjsConfig.onUnhandledError = unhandledErrorHook ?? null;
  });

  beforeEach(() => {
    plaidConfig = null;
    plaidBefore = (window as any).Plaid;
    (window as any).Plaid = {
      create: (config: CapturedPlaidConfig) => {
        plaidConfig = config;
        return { open: () => undefined };
      },
    };

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack', 'navigateForward']);
    post = jasmine.createSpy('post').and.returnValue(of({ link_token: 'link-sandbox-token', expiration: '' }));
    http = { post } as unknown as HttpClient;
    authService = {
      completeStep: jasmine.createSpy('completeStep').and.returnValue(of({})),
    } as unknown as AuthService;

    component = new LinkPlaidComponent(http, router, navCtrl, authService, analytics);
  });

  afterEach(() => {
    (window as any).Plaid = plaidBefore;
  });

  it('reports the step as viewed, never resumed: nothing is drafted on this screen', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('link_plaid', null, false);
  });

  it('reports the step completed when the user comes back out of the Plaid sheet', async () => {
    await openPlaidHandler();

    plaidConfig!.onSuccess('public-sandbox-should-never-be-sent', {
      institution: { name: 'Chase' },
      accounts: [{ mask: '4321' }],
    });

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('link_plaid', null);
  });

  it('sends neither the public token nor anything out of the Plaid metadata', async () => {
    await openPlaidHandler();

    plaidConfig!.onSuccess('public-sandbox-should-never-be-sent', {
      institution: { name: 'Chase' },
      accounts: [{ mask: '4321' }],
    });

    const everySentArgument = JSON.stringify([
      analytics.track.calls.allArgs(),
      analytics.trackOnboardingStepViewed.calls.allArgs(),
      analytics.trackOnboardingStepCompleted.calls.allArgs(),
      analytics.trackErrorShown.calls.allArgs(),
    ]);
    expect(everySentArgument).not.toContain('public-sandbox');
    expect(everySentArgument).not.toContain('Chase');
    expect(everySentArgument).not.toContain('4321');
  });

  it('reports a Plaid exit that carried an error as a bank-link error', async () => {
    await openPlaidHandler();

    plaidConfig!.onExit(
      { error_code: 'INVALID_CREDENTIALS', display_message: 'The username or password was incorrect' },
      { institution: { name: 'Chase' } },
    );

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('bank_link', 'onboarding', 'inline_banner', null, true);
    expect(JSON.stringify(analytics.trackErrorShown.calls.allArgs())).not.toContain('password');
  });

  it('reports nothing when the user simply closed the Plaid sheet', async () => {
    await openPlaidHandler();

    plaidConfig!.onExit(null, { status: 'requires_credentials' });

    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
    expect(analytics.trackOnboardingStepCompleted).not.toHaveBeenCalled();
  });

  it('reports a failure to prepare the link as a bank-link error', async () => {
    post.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));

    await openPlaidHandler();

    expect(plaidConfig).toBeNull();
    expect(analytics.trackErrorShown).toHaveBeenCalledWith('bank_link', 'onboarding', 'inline_banner', null, true);
  });

  it('reports a failed token exchange with the status the server returned', async () => {
    await openPlaidHandler();
    post.and.callFake((url: string) =>
      url.includes('exchange_public_token')
        ? throwError(() => new HttpErrorResponse({ status: 502, error: { message: 'Plaid is unavailable' } }))
        : of({ link_token: 'link-sandbox-token', expiration: '' }),
    );
    analytics.trackErrorShown.calls.reset();

    plaidConfig!.onSuccess('public-sandbox-should-never-be-sent', {});

    expect(analytics.trackErrorShown).toHaveBeenCalledWith('bank_link', 'onboarding', 'inline_banner', 502, true);

    // Give rxjs's unhandled-error timer its turn before afterAll puts the real reporter back.
    await new Promise(resolve => setTimeout(resolve, 0));
  });
});
