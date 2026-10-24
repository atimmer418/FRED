import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { AnalyticsAdapter, AnalyticsAdapterConfig } from './analytics-adapter';
import { ANALYTICS_ADAPTER } from './analytics-adapter.token';
import { AnalyticsService } from './analytics.service';
import { AnalyticsEvent, CLIENT_EVENT_NAMES, PersonProperties, ScalarProps } from './analytics-events';
import { buildBeforeSend } from './posthog-before-send';

/**
 * Phase 7's test: fire every event with a payload that must never leave, through the real dispatch
 * path, and prove nothing identifying reaches the vendor boundary.
 *
 * The point is the PATH, not the patterns. analytics-privacy-guard.spec.ts pins which shapes are
 * forbidden; this file pins that the guard is actually installed in front of the vendor, for every
 * event, on every one of the three payloads the SDK sends (`properties`, `$set` and `$set_once`).
 * A guard that exists and is not wired in is the failure mode this catches.
 *
 * The payload is injected through a cast, because the taxonomy's types already make these
 * properties unwritable in real code. That is the first line of defence and it holds at compile
 * time; this is the second, for the day someone widens a type or sends a payload built at runtime.
 */

/** Records calls instead of reaching a vendor, so a spec can never send real traffic. */
class RecordingAdapter implements AnalyticsAdapter {
  initCalls: AnalyticsAdapterConfig[] = [];
  captured: Array<{ name: string; props: ScalarProps }> = [];
  identified: Array<{ id: string; props?: PersonProperties }> = [];
  screens: Array<{ name: string; props: ScalarProps }> = [];

  init(config: AnalyticsAdapterConfig): void {
    this.initCalls.push(config);
  }
  isReady(): boolean {
    return this.initCalls.length > 0;
  }
  capture(name: string, props: ScalarProps): void {
    this.captured.push({ name, props });
  }
  identify(id: string, props?: PersonProperties): void {
    this.identified.push({ id, props });
  }
  screen(name: string, props: ScalarProps): void {
    this.screens.push({ name, props });
  }
  reset(): void {
    /* not exercised here */
  }
  setOptOut(): void {
    /* not exercised here */
  }
}

/** Every kind of thing the rules forbid, in one payload, with one legitimate property. */
const FORBIDDEN_PAYLOAD: Record<string, string | number> = {
  email: 'someone@example.com',
  user_email: 'someone@example.com',
  phone_number: '(555) 123-4567',
  ssn: '123-45-6789',
  date_of_birth: '1990-04-12',
  first_name: 'Andrew',
  institution_name: 'Chase',
  street_address: '1 Main St',
  account_number: '000123456789',
  access_token: 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0',
  contribution_amount: 2400,
  ip_address: '203.0.113.7',
  user_comment: 'please help me with my account',
  // Innocently named, forbidden by value. These are the ones a key deny-list alone would miss.
  detail: 'someone@example.com',
  ref: '123-45-6789',
  code: '987654321',
  // The one property that must survive, so a passing test cannot be an empty payload.
  step: 'kyc',
};

const FORBIDDEN_VALUES = [
  'someone@example.com',
  '(555) 123-4567',
  '123-45-6789',
  'Andrew',
  'Chase',
  '1 Main St',
  '000123456789',
  'eyJhbGciOiJIUzI1NiJ9',
  '987654321',
  'please help me with my account',
];

/** Fails on anything forbidden, by key or by value, at any depth of the payload. */
function expectNothingForbidden(where: string, payload: Record<string, unknown> | undefined): void {
  const serialised = JSON.stringify(payload ?? {});
  for (const key of Object.keys(FORBIDDEN_PAYLOAD)) {
    if (key === 'step') {
      continue;
    }
    expect(Object.keys(payload ?? {}))
      .withContext(`${where} still carries the key '${key}'`)
      .not.toContain(key);
  }
  for (const value of FORBIDDEN_VALUES) {
    expect(serialised).withContext(`${where} still carries the value for ${value}`).not.toContain(value);
  }
}

describe('a forbidden payload never leaves the device', () => {
  let adapter: RecordingAdapter;
  let service: AnalyticsService;
  const realKey = environment.posthogKey;
  const realName = environment.name;
  const FIRST_OPEN_KEY = 'fred.analytics.firstOpenSeen';

  beforeEach(() => {
    adapter = new RecordingAdapter();
    TestBed.configureTestingModule({
      providers: [AnalyticsService, { provide: ANALYTICS_ADAPTER, useValue: adapter }],
    });
    service = TestBed.inject(AnalyticsService);

    (environment as { posthogKey: string }).posthogKey = 'phc_test_key_not_real';
    (environment as { name: typeof realName }).name = 'dev';
    spyOn(service as unknown as { isUnderTest: () => boolean }, 'isUnderTest').and.returnValue(false);
    localStorage.setItem(FIRST_OPEN_KEY, 'seen');
    // The dev build reports each stripped property on the console. Silence it so a spec that
    // deliberately sends twenty forbidden properties does not bury the rest of the run.
    spyOn(console, 'error');
    service.setOptOut(false);
  });

  afterEach(() => {
    (environment as { posthogKey: string }).posthogKey = realKey;
    (environment as { name: typeof realName }).name = realName;
    localStorage.removeItem(FIRST_OPEN_KEY);
  });

  CLIENT_EVENT_NAMES.forEach((name) => {
    it(`strips everything forbidden from '${name}' and still sends the event`, () => {
      service.track({ name, props: { ...FORBIDDEN_PAYLOAD } } as unknown as AnalyticsEvent);

      expect(adapter.captured.length).withContext('the event itself must still be sent').toBe(1);
      const sent = adapter.captured[0];
      expect(sent.name).toBe(name);
      expectNothingForbidden(`${name}'s properties`, sent.props);
      // A funnel with one dimension missing is still a funnel. Dropping the event would hide the
      // mistake rather than report it, so the legitimate property has to survive.
      expect(sent.props['step']).toBe('kyc');
    });
  });

  it('strips a forbidden payload from a screen view', () => {
    service.screen('/beneficiaries/edit/:id', { ...FORBIDDEN_PAYLOAD });

    expect(adapter.screens.length).toBe(1);
    expectNothingForbidden('the screen view', adapter.screens[0].props);
  });

  it('reports every stripped property on a dev console, so the mistake is loud', () => {
    service.track({ name: 'app_opened', props: { email: 'someone@example.com' } } as unknown as AnalyticsEvent);

    expect(console.error).toHaveBeenCalled();
    const message = (console.error as jasmine.Spy).calls.mostRecent().args.join(' ');
    expect(message).toContain('email');
    // The report names the rule. It must never print the value it just stripped.
    expect(message).not.toContain('someone@example.com');
  });

  /**
   * The vendor's own hook. `$set` and `$set_once` are the dangerous half: a route id or an email
   * that lands on a PERSON record outlives the event it arrived with.
   */
  describe('through the vendor hook the SDK actually calls', () => {
    function vendorHook() {
      service.track({ name: 'app_opened', props: { platform: 'ios', app_environment: 'dev' } } as AnalyticsEvent);
      expect(adapter.initCalls.length).withContext('the adapter must have been initialised').toBe(1);
      return buildBeforeSend(adapter.initCalls[0].sanitize);
    }

    it('scrubs properties, $set and $set_once alike', () => {
      const result = vendorHook()({
        properties: { ...FORBIDDEN_PAYLOAD },
        $set: { ...FORBIDDEN_PAYLOAD },
        $set_once: { ...FORBIDDEN_PAYLOAD },
      });

      expect(result).not.toBeNull();
      expectNothingForbidden('properties', result!.properties);
      expectNothingForbidden('$set', result!.$set);
      expectNothingForbidden('$set_once', result!.$set_once);
    });

    it('keeps scrubbing the URL properties it already scrubbed, and does not strip them', () => {
      const result = vendorHook()({
        properties: {
          $current_url: 'https://app.fredvested.com/beneficiaries/edit/41?devKey=secret',
          $pathname: '/beneficiaries/edit/41',
        },
      });

      // The id and the query string go, and the screen survives: the URL pass runs before the
      // deny-list, so a long digit run in a route cannot cost us the property.
      expect(result!.properties!['$current_url']).toBe(
        'https://app.fredvested.com/beneficiaries/edit/:id',
      );
      expect(result!.properties!['$pathname']).toBe('/beneficiaries/edit/:id');
    });
  });
});
