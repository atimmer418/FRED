import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { AnalyticsAdapter, AnalyticsAdapterConfig } from './analytics-adapter';
import { ANALYTICS_ADAPTER } from './analytics-adapter.token';
import { AnalyticsService } from './analytics.service';
import { PersonProperties, ScalarProps } from './analytics-events';

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

  beforeEach(() => {
    adapter = new RecordingAdapter();
    TestBed.configureTestingModule({
      providers: [AnalyticsService, { provide: ANALYTICS_ADAPTER, useValue: adapter }],
    });
    service = TestBed.inject(AnalyticsService);
  });

  afterEach(() => {
    (environment as { posthogKey: string }).posthogKey = realKey;
  });

  /**
   * The service deliberately no-ops under Karma so no spec can reach a vendor. These two helpers
   * are the only sanctioned way past that, and they are what let the dispatch path be tested.
   */
  function allowDispatch(): void {
    (environment as { posthogKey: string }).posthogKey = 'phc_test_key_not_real';
    spyOn(service as unknown as { isUnderTest: () => boolean }, 'isUnderTest').and.returnValue(false);
  }

  it('defaults to opted out, so this checkpoint sends nothing', () => {
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

  it('dispatches app_opened once consent is given', () => {
    allowDispatch();
    service.setOptOut(false);

    service.trackAppOpened();

    expect(adapter.captured.length).toBe(1);
    expect(adapter.captured[0].name).toBe('app_opened');
    expect(Object.keys(adapter.captured[0].props).sort()).toEqual(['app_environment', 'platform']);
  });

  it('initializes the vendor lazily, exactly once, and never while opted out', () => {
    allowDispatch();

    service.trackAppOpened();
    expect(adapter.initCalls.length).toBe(0);

    service.setOptOut(false);
    service.trackAppOpened();
    service.trackAppOpened();

    expect(adapter.initCalls.length).toBe(1);
    expect(adapter.captured.length).toBe(2);
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

  it('ignores an empty distinct id rather than creating an anonymous person', () => {
    allowDispatch();
    service.setOptOut(false);

    service.identify('');

    expect(adapter.identified).toEqual([]);
  });

  it('forwards a screen name and its props', () => {
    allowDispatch();
    service.setOptOut(false);

    service.screen('/beneficiaries/edit/:id', { surface: 'settings' });

    expect(adapter.screens.length).toBe(1);
    expect(adapter.screens[0].name).toBe('/beneficiaries/edit/:id');
    expect(adapter.screens[0].props).toEqual({ surface: 'settings' });
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
