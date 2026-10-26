import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { AnalyticsAdapter, AnalyticsAdapterConfig } from './analytics-adapter';
import { ANALYTICS_ADAPTER } from './analytics-adapter.token';
import { AnalyticsService } from './analytics.service';
import {
  AnalyticsEvent,
  CLIENT_EVENT_NAMES,
  DECLARED_PROPERTY_KEYS,
  PERSON_PROPERTY_KEYS,
  PersonProperties,
  ScalarProps,
} from './analytics-events';
import { applyPrivacyGuard } from './analytics-privacy-guard';
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

/**
 * The other direction, and the one that costs data rather than leaking it.
 *
 * The value-shape checks run over EVERY property, declared or not: decision 12's exemption covers
 * a key's name and nothing else. So a declared property whose legitimate value happened to be a
 * long run of digits, an e.164 number or a `###-###-####` shape would be stripped in silence, and
 * the first sign of it would be a funnel with a hole in it.
 *
 * This table is every key the client taxonomy declares, with its COMPLETE legitimate value domain:
 * the whole closed vocabulary for an enum, every band for a band, and the real range for a number.
 * The last test refuses to let the table fall behind the taxonomy, so a new property cannot be
 * added without someone stating here what it can hold.
 */
describe('the guard keeps every legitimate declared value', () => {
  const AMOUNT_BANDS = [
    'under_50', '50_99', '100_249', '250_499', '500_999', '1k_2499',
    '2500_4999', '5k_9999', '10k_49k', '50k_249k', '250k_999k', '1m_plus',
  ];
  const COUNT_BANDS = ['0', '1', '2_3', '4_9', '10_plus'];
  const YEARS_BANDS = ['under_1', '1_2', '3_5', '6_10', '11_20', 'over_20', 'never'];
  const PERCENT_BANDS = ['0_9', '10_24', '25_49', '50_74', '75_99', '100'];
  const TIERS = ['core', 'plus', 'pro', null];
  const BOOLEANS = [true, false, null];

  /** Every declared key against every value it is allowed to carry. */
  const DOMAINS: Record<string, ReadonlyArray<string | number | boolean | null>> = {
    action: [
      'created', 'updated', 'completed', 'reopened', 'removed',
      'adjusted_contribution', 'adjusted_retirement', 'changed_scenario', 'shared',
      'opened_assumptions', 'shown', 'upgrade_tapped', 'dismissed',
    ],
    app_environment: ['local', 'dev', 'test', 'ci', 'prod'],
    category: [
      'network', 'auth_required', 'passkey', 'step_up_pin', 'bank_link', 'validation',
      'save_failed', 'load_failed', 'document', 'chat', 'unavailable', 'unknown',
    ],
    direction: ['increase', 'decrease', null],
    display: ['toast', 'inline_field', 'inline_banner', 'alert'],
    enabled: BOOLEANS,
    exit: ['cancel', 'done', 'stress_test', 'drawdown', 'upgrade'],
    feature: [
      'goals', 'rebalancing', 'monte_carlo', 'market_scenarios', 'optimal_drawdown',
      'tax_loss_harvesting',
    ],
    goal_count: COUNT_BANDS,
    // Every status an HTTP client can hand us, plus the null that means "no response at all".
    http_status: [null, 0, 100, 204, 301, 400, 401, 403, 404, 409, 418, 422, 429, 499, 500, 502, 503, 504, 599],
    icon: [
      'home', 'health_and_safety', 'flag', 'balance', 'savings', 'school', 'directions_car',
    ],
    included_outside_accounts: BOOLEANS,
    income_changed: BOOLEANS,
    input_method: ['slider', 'typed', 'fetched', null],
    invest_changed: BOOLEANS,
    is_rerun: BOOLEANS,
    // A deck has single digits of slides. The upper bound is deliberately absurd: if a deck ever
    // had ten million slides the number would be stripped, and that is worth knowing here.
    max_slide_reached: [0, 1, 7, 50, 9999999],
    monthly_income_band: [...AMOUNT_BANDS, null],
    monthly_invest_band: [...AMOUNT_BANDS, null],
    outcome: ['applied', 'cancelled', 'failed', 'strong', 'moderate', 'risk'],
    platform: ['ios', 'web'],
    preset_changed: BOOLEANS,
    progress: [...PERCENT_BANDS, null],
    resumed: BOOLEANS,
    retry_available: BOOLEANS,
    return_preset: ['conservative', 'moderate', 'optimistic', null],
    scenario: ['conservative', 'expected', 'aggressive', null],
    setting: [
      'app_lock', 'step_up_auth', 'analytics_consent', 'drip', 'rebalance_schedule',
      'marketing_emails',
    ],
    slide_count: [1, 7, 50, 9999999],
    source: ['orb', 'history'],
    step: [
      'get_started', 'survey_initial', 'fi_plan_results', 'auth_finalize', 'kyc_verification',
      'link_plaid', 'investment_schedule', 'investment_confirmation',
      'portfolio', 'outside', 'spend', 'duration',
    ],
    strategies_held: COUNT_BANDS,
    strategy: ['yield', 'guardrails', 'sbloc', 'annuity', 'optimal_drawdown'],
    sub_step: [null, 1, 2, 9999999],
    surface: [
      'onboarding', 'freedom_sheet', 'my_profile', 'goals', 'retirement_planning', 'monte_carlo',
      'strategy_deck', 'settings', 'portfolio', 'chat', 'other',
    ],
    target_band: AMOUNT_BANDS,
    tier: TIERS,
    // The rebalance schedule slug, which is the only thing `value` carries today, plus the null
    // every switch sends.
    value: ['monthly', 'quarterly', 'semi_annual', 'annual', null],
    years_to_freedom: YEARS_BANDS,
    years_to_target: YEARS_BANDS,
    // The five person properties the server sets.
    kyc_status: ['not_started', 'pending', 'approved', 'rejected', 'action_required', 'other'],
    has_linked_bank: BOOLEANS,
    has_schedule: BOOLEANS,
    has_funded: BOOLEANS,
  };

  Object.entries(DOMAINS).forEach(([key, values]) => {
    it(`keeps every value '${key}' can carry`, () => {
      for (const value of values) {
        const { props, violations } = applyPrivacyGuard({ [key]: value });
        expect(violations)
          .withContext(`${key}=${String(value)} was stripped as ${violations[0]?.rule}`)
          .toEqual([]);
        expect(props[key]).toBe(value as never);
      }
    });
  });

  it('covers every declared key, so a new property cannot be added without declaring its domain', () => {
    const covered = Object.keys(DOMAINS).sort();
    const declared = [...DECLARED_PROPERTY_KEYS, ...PERSON_PROPERTY_KEYS].sort();
    expect(covered).toEqual([...new Set(declared)].sort());
  });
});
