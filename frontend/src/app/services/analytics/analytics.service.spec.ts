import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { AnalyticsAdapter, AnalyticsAdapterConfig } from './analytics-adapter';
import { ANALYTICS_ADAPTER } from './analytics-adapter.token';
import { AnalyticsService } from './analytics.service';
import { PersonProperties, ScalarProps } from './analytics-events';
import { buildBeforeSend } from './posthog-before-send';

/** Records calls instead of reaching a vendor, so a spec can never send real traffic. */
class RecordingAdapter implements AnalyticsAdapter {
  initCalls: AnalyticsAdapterConfig[] = [];
  captured: Array<{ name: string; props: ScalarProps }> = [];
  identified: Array<{ id: string; props?: PersonProperties }> = [];
  screens: Array<{ name: string; props: ScalarProps }> = [];
  resets = 0;
  optOutCalls: boolean[] = [];
  throwOnCapture = false;

  init(config: AnalyticsAdapterConfig): void {
    this.initCalls.push(config);
  }
  isReady(): boolean {
    return this.initCalls.length > 0;
  }
  capture(name: string, props: ScalarProps): void {
    if (this.throwOnCapture) {
      throw new Error('vendor exploded');
    }
    this.captured.push({ name, props });
  }
  identify(id: string, props?: PersonProperties): void {
    this.identified.push({ id, props });
  }
  screen(name: string, props: ScalarProps): void {
    this.screens.push({ name, props });
  }
  reset(): void {
    this.resets++;
  }
  setOptOut(optedOut: boolean): void {
    this.optOutCalls.push(optedOut);
  }
}

describe('AnalyticsService', () => {
  let adapter: RecordingAdapter;
  let service: AnalyticsService;
  const realKey = environment.posthogKey;
  const realName = environment.name;
  const FIRST_OPEN_KEY = 'fred.analytics.firstOpenSeen';
  const UUID = '11111111-2222-4333-8444-555555555555';

  beforeEach(() => {
    adapter = new RecordingAdapter();
    TestBed.configureTestingModule({
      providers: [AnalyticsService, { provide: ANALYTICS_ADAPTER, useValue: adapter }],
    });
    service = TestBed.inject(AnalyticsService);
  });

  afterEach(() => {
    (environment as { posthogKey: string }).posthogKey = realKey;
    (environment as { name: typeof realName }).name = realName;
    localStorage.removeItem(FIRST_OPEN_KEY);
  });

  /**
   * The service deliberately no-ops under Karma so no spec can reach a vendor. These two helpers
   * are the only sanctioned way past that, and they are what let the dispatch path be tested.
   */
  function allowDispatch(): void {
    (environment as { posthogKey: string }).posthogKey = 'phc_test_key_not_real';
    (environment as { name: typeof realName }).name = 'dev';
    spyOn(service as unknown as { isUnderTest: () => boolean }, 'isUnderTest').and.returnValue(false);
    // Treat each spec as a launch that is not the install, unless it says otherwise.
    localStorage.setItem(FIRST_OPEN_KEY, 'seen');
  }

  it('treats consent as unknown, and so opted out, until the server answers', () => {
    // Server-side the default is now opted IN for a new user, but the client must not assume that:
    // until the session payload arrives it has no idea, and unknown has to mean silent.
    expect(service.isOptedOut()).toBeTrue();
  });

  it('sends nothing while opted out, even with a key configured', () => {
    allowDispatch();

    service.trackAppOpened();
    service.screen('/tabs/:tab');
    service.identify('11111111-2222-4333-8444-555555555555');

    expect(adapter.initCalls.length).toBe(0);
    expect(adapter.captured).toEqual([]);
    expect(adapter.screens).toEqual([]);
    expect(adapter.identified).toEqual([]);
  });

  it('sends nothing from a local build, even with consent and a key', () => {
    (environment as { posthogKey: string }).posthogKey = 'phc_test_key_not_real';
    (environment as { name: typeof realName }).name = 'local';
    spyOn(service as unknown as { isUnderTest: () => boolean }, 'isUnderTest').and.returnValue(false);
    service.setOptOut(false);

    service.trackAppOpened();
    service.screen('/tabs/tab1');

    expect(adapter.captured).toEqual([]);
    expect(adapter.screens).toEqual([]);
  });

  it('sends nothing from a ci build', () => {
    (environment as { posthogKey: string }).posthogKey = 'phc_test_key_not_real';
    (environment as { name: typeof realName }).name = 'ci';
    spyOn(service as unknown as { isUnderTest: () => boolean }, 'isUnderTest').and.returnValue(false);
    service.setOptOut(false);

    service.trackAppOpened();

    expect(adapter.captured).toEqual([]);
  });

  it('sends from a prod build', () => {
    (environment as { posthogKey: string }).posthogKey = 'phc_test_key_not_real';
    (environment as { name: typeof realName }).name = 'prod';
    spyOn(service as unknown as { isUnderTest: () => boolean }, 'isUnderTest').and.returnValue(false);
    localStorage.setItem(FIRST_OPEN_KEY, 'seen');
    service.setOptOut(false);

    service.trackAppOpened();

    expect(adapter.captured.map((c) => c.name)).toEqual(['app_opened']);
  });

  it('takes consent from the server, not from a local default', () => {
    allowDispatch();

    service.applyServerConsent(false);
    service.trackAppOpened();
    expect(adapter.captured.length).toBe(1);

    service.applyServerConsent(true);
    service.screen('/tabs/tab2');
    expect(adapter.screens).toEqual([]);
  });

  it('treats a missing or null server consent value as opted out', () => {
    allowDispatch();

    service.applyServerConsent(undefined);
    expect(service.isOptedOut()).toBeTrue();

    service.applyServerConsent(null);
    expect(service.isOptedOut()).toBeTrue();

    service.trackAppOpened();
    expect(adapter.captured).toEqual([]);
  });

  it('fires app_first_opened once per install, then app_opened', () => {
    allowDispatch();
    localStorage.removeItem(FIRST_OPEN_KEY);
    service.applyServerConsent(false);

    service.trackAppOpened();
    service.trackAppOpened();
    service.trackAppOpened();

    expect(adapter.captured.map((c) => c.name)).toEqual(['app_first_opened', 'app_opened', 'app_opened']);
  });

  it('holds the launch event until the server consent arrives, then sends it', () => {
    // Startup order: trackAppOpened runs before the session payload comes back. Without the hold,
    // a consenting user would never record a launch at all.
    allowDispatch();
    localStorage.removeItem(FIRST_OPEN_KEY);

    service.trackAppOpened();
    expect(adapter.captured).toEqual([]);

    service.applyServerConsent(false);

    expect(adapter.captured.map((c) => c.name)).toEqual(['app_first_opened']);
  });

  it('drops the held launch event when the server says opted out', () => {
    allowDispatch();
    localStorage.removeItem(FIRST_OPEN_KEY);

    service.trackAppOpened();
    service.applyServerConsent(true);

    expect(adapter.captured).toEqual([]);

    // and it is not replayed if consent is granted later in the same session
    service.applyServerConsent(false);
    expect(adapter.captured).toEqual([]);
  });

  it('claims the install even when the launch event is dropped, so the next launch is not an install', () => {
    allowDispatch();
    localStorage.removeItem(FIRST_OPEN_KEY);

    service.trackAppOpened();
    expect(localStorage.getItem(FIRST_OPEN_KEY)).toBeTruthy();
  });

  it('does not re-notify the vendor when consent is re-applied unchanged', () => {
    allowDispatch();
    service.applyServerConsent(false);
    service.trackAppOpened();
    const afterFirst = adapter.optOutCalls.length;

    service.applyServerConsent(false);
    service.applyServerConsent(false);

    expect(adapter.optOutCalls.length).toBe(afterFirst);
  });

  it('refuses to identify with anything that is not a UUID', () => {
    allowDispatch();
    service.setOptOut(false);

    service.identify('andrew@fredvested.com');
    service.identify('7');
    service.identify('user_7');
    service.identify('');
    service.identify('not-a-uuid-at-all');
    service.identify('11111111-2222-4333-8444-55555555555');  // one char short

    expect(adapter.identified).toEqual([]);
  });

  it('identifies with the public_id UUID', () => {
    allowDispatch();
    service.setOptOut(false);

    service.identify(UUID);

    expect(adapter.identified.length).toBe(1);
    expect(adapter.identified[0].id).toBe(UUID);
    expect(adapter.identified[0].props).toBeUndefined();
  });

  it('does not identify while opted out', () => {
    allowDispatch();

    service.identify(UUID);

    expect(adapter.identified).toEqual([]);
  });

  it('scrubs ids and query strings out of the URL properties the SDK adds', () => {
    // The SDK fills these in by itself, and a route like /beneficiaries/edit/123 would otherwise
    // put a real row id in them.
    expect(AnalyticsService.scrubUrl('http://app.fredvested.com/beneficiaries/edit/123'))
      .toBe('http://app.fredvested.com/beneficiaries/edit/:id');
    expect(AnalyticsService.scrubUrl('/beneficiaries/edit/123')).toBe('/beneficiaries/edit/:id');
    expect(AnalyticsService.scrubUrl('/u/2eced352-de27-4d0b-a04d-f8f2d43c06b3')).toBe('/u/:id');
    expect(AnalyticsService.scrubUrl('http://localhost:4200/?devPage=/tabs/tab1&devKey=secret'))
      .toBe('http://localhost:4200/');
    expect(AnalyticsService.scrubUrl('/tabs/tab1')).toBe('/tabs/tab1');
    expect(AnalyticsService.scrubUrl('$direct')).toBe('$direct');
  });

  it('applies the scrub through the sanitizer handed to the vendor', () => {
    allowDispatch();
    service.setOptOut(false);
    service.trackAppOpened();

    const sanitize = adapter.initCalls[0].sanitize;
    const cleaned = sanitize({
      $current_url: 'http://localhost:4200/beneficiaries/edit/42?token=abc',
      $pathname: '/beneficiaries/edit/42',
      platform: 'web',
    });

    expect(cleaned['$current_url']).toBe('http://localhost:4200/beneficiaries/edit/:id');
    expect(cleaned['$pathname']).toBe('/beneficiaries/edit/:id');
    expect(cleaned['platform']).toBe('web');
  });

  it('drops a repeated screen name but keeps a real change', () => {
    allowDispatch();
    service.setOptOut(false);

    service.screen('/tabs/tab1');
    service.screen('/tabs/tab1');
    service.screen('/tabs/tab3');
    service.screen('/tabs/tab3');
    service.screen('/tabs/tab1');

    expect(adapter.screens.map((s) => s.name)).toEqual(['/tabs/tab1', '/tabs/tab3', '/tabs/tab1']);
  });

  it('dispatches app_opened once consent is given', () => {
    allowDispatch();
    service.setOptOut(false);

    service.trackAppOpened();

    expect(adapter.captured.length).toBe(1);
    expect(adapter.captured[0].name).toBe('app_opened');
    expect(Object.keys(adapter.captured[0].props).sort()).toEqual(['app_environment', 'platform']);
    expect(adapter.captured[0].props['app_environment']).toBe('dev');
  });

  it('initializes the vendor lazily, exactly once, and never while opted out', () => {
    allowDispatch();

    service.trackAppOpened();
    expect(adapter.initCalls.length).toBe(0);

    // Granting consent flushes the held launch event, so three events in total.
    service.setOptOut(false);
    service.trackAppOpened();
    service.trackAppOpened();

    expect(adapter.initCalls.length).toBe(1);
    expect(adapter.captured.length).toBe(3);
  });

  it('stays a no-op when no key is configured, however consent is set', () => {
    (environment as { posthogKey: string }).posthogKey = '';
    spyOn(service as unknown as { isUnderTest: () => boolean }, 'isUnderTest').and.returnValue(false);
    service.setOptOut(false);

    service.trackAppOpened();

    expect(adapter.initCalls.length).toBe(0);
    expect(adapter.captured).toEqual([]);
  });

  it('is silent under Karma even with consent and a key', () => {
    (environment as { posthogKey: string }).posthogKey = 'phc_test_key_not_real';
    service.setOptOut(false);

    service.trackAppOpened();

    expect(adapter.captured).toEqual([]);
  });

  it('never lets a vendor failure reach the caller', () => {
    allowDispatch();
    service.setOptOut(false);
    adapter.throwOnCapture = true;

    expect(() => service.trackAppOpened()).not.toThrow();
  });

  it('passes the key, proxy host and a sanitizer to the adapter', () => {
    allowDispatch();
    service.setOptOut(false);

    service.trackAppOpened();

    const config = adapter.initCalls[0];
    expect(config.key).toBe('phc_test_key_not_real');
    expect(config.apiHost).toBe(environment.posthogApiHost);
    expect(typeof config.sanitize).toBe('function');
  });

  it('keeps the vendor opt-out state in step with its own', () => {
    allowDispatch();
    service.setOptOut(false);
    service.trackAppOpened();

    service.setOptOut(true);

    expect(adapter.optOutCalls).toContain(true);
    expect(service.isOptedOut()).toBeTrue();
  });

  it('forwards a screen name and its props', () => {
    allowDispatch();
    service.setOptOut(false);

    service.screen('/beneficiaries/edit/:id', { surface: 'settings' });

    expect(adapter.screens.length).toBe(1);
    expect(adapter.screens[0].name).toBe('/beneficiaries/edit/:id');
    expect(adapter.screens[0].props).toEqual({ surface: 'settings' });
  });

  it('reports an onboarding step view, defaulting sub_step and resumed', () => {
    allowDispatch();
    service.setOptOut(false);

    service.trackOnboardingStepViewed('get_started');

    expect(adapter.captured).toEqual([
      { name: 'onboarding_step_viewed', props: { step: 'get_started', sub_step: null, resumed: false } },
    ]);
  });

  it('carries the sub step and the resumed flag when a step has them', () => {
    allowDispatch();
    service.setOptOut(false);

    service.trackOnboardingStepViewed('kyc_verification', 2, true);

    expect(adapter.captured[0].props).toEqual({ step: 'kyc_verification', sub_step: 2, resumed: true });
  });

  it('reports an onboarding step completion', () => {
    allowDispatch();
    service.setOptOut(false);

    service.trackOnboardingStepCompleted('link_plaid');

    expect(adapter.captured).toEqual([
      { name: 'onboarding_step_completed', props: { step: 'link_plaid', sub_step: null } },
    ]);
  });

  it('reports a shown error as a category, never a message', () => {
    allowDispatch();
    service.setOptOut(false);

    service.trackErrorShown('save_failed', 'my_profile', 'toast', 500, true);

    expect(adapter.captured).toEqual([
      {
        name: 'error_shown',
        props: {
          category: 'save_failed',
          surface: 'my_profile',
          display: 'toast',
          http_status: 500,
          retry_available: true,
        },
      },
    ]);
  });

  it('defaults a shown error to a toast with no status and no retry', () => {
    allowDispatch();
    service.setOptOut(false);

    service.trackErrorShown('network', 'portfolio');

    expect(adapter.captured[0].props).toEqual({
      category: 'network',
      surface: 'portfolio',
      display: 'toast',
      http_status: null,
      retry_available: false,
    });
  });

  it('claims the install exactly once across many launches on the same device', () => {
    // A launch is a fresh service against the SAME storage. The flag, not the instance, is what
    // makes an install an install, so this is the shape that proves it: the earlier spec only
    // covered two launches through one service.
    localStorage.removeItem(FIRST_OPEN_KEY);
    const launches: string[] = [];

    for (let launch = 0; launch < 5; launch++) {
      const freshAdapter = new RecordingAdapter();
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [AnalyticsService, { provide: ANALYTICS_ADAPTER, useValue: freshAdapter }],
      });
      const freshService = TestBed.inject(AnalyticsService);
      (environment as { posthogKey: string }).posthogKey = 'phc_test_key_not_real';
      (environment as { name: typeof realName }).name = 'dev';
      spyOn(
        freshService as unknown as { isUnderTest: () => boolean },
        'isUnderTest',
      ).and.returnValue(false);

      freshService.trackAppOpened();
      freshService.applyServerConsent(false);

      launches.push(...freshAdapter.captured.map((c) => c.name));
    }

    expect(launches).toEqual([
      'app_first_opened',
      'app_opened',
      'app_opened',
      'app_opened',
      'app_opened',
    ]);
  });

  it('never claims a second install when storage is unreadable', () => {
    // Blocked site data makes the flag unreadable AND unwritable. Treating that as an install
    // would report one on every single launch, which is the worst possible answer.
    allowDispatch();
    localStorage.removeItem(FIRST_OPEN_KEY);
    spyOn(Storage.prototype, 'getItem').and.throwError('site data blocked');
    service.setOptOut(false);

    service.trackAppOpened();

    expect(adapter.captured.map((c) => c.name)).toEqual(['app_opened']);
  });

  it('scrubs the session-entry URL the SDK attaches to every event', () => {
    // The SDK stamps the first URL of the session onto every event. It is a full href, query
    // string included, so it leaks exactly what $current_url would have.
    allowDispatch();
    service.setOptOut(false);
    service.trackAppOpened();

    const cleaned = adapter.initCalls[0].sanitize({
      $session_entry_url: 'http://localhost:4200/beneficiaries/edit/42?token=abc',
      $session_entry_pathname: '/beneficiaries/edit/42',
    });

    expect(cleaned['$session_entry_url']).toBe('http://localhost:4200/beneficiaries/edit/:id');
    expect(cleaned['$session_entry_pathname']).toBe('/beneficiaries/edit/:id');
  });

  it('scrubs an initial person property through the hook the vendor is given', () => {
    // The end of the chain: the service's own sanitizer, wired into the SDK hook exactly as the
    // adapter wires it, applied to the person properties posthog builds for itself. A route id
    // landing here would be permanent, because a person property outlives the event.
    allowDispatch();
    service.setOptOut(false);
    service.trackAppOpened();

    const beforeSend = buildBeforeSend(adapter.initCalls[0].sanitize);

    const result = beforeSend({
      properties: {},
      $set_once: {
        $initial_current_url: 'http://localhost:4200/beneficiaries/edit/42?token=abc',
        $initial_pathname: '/beneficiaries/edit/42',
      },
    });

    expect(result!.$set_once).toEqual({
      $initial_current_url: 'http://localhost:4200/beneficiaries/edit/:id',
      $initial_pathname: '/beneficiaries/edit/:id',
    });
  });

  it('does not reset a vendor it never initialized', () => {
    service.reset();

    expect(adapter.resets).toBe(0);
  });

  it('resets once initialized, so the next user is not merged into this one', () => {
    allowDispatch();
    service.setOptOut(false);
    service.trackAppOpened();

    service.reset();

    expect(adapter.resets).toBe(1);
  });
});
