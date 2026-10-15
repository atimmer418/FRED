import { AnalyticsAdapter, AnalyticsAdapterConfig } from './analytics-adapter';
import { LazyPostHogAdapter, PostHogAdapterModule } from './lazy-posthog.adapter';
import { PersonProperties, ScalarProps } from './analytics-events';

class FakeVendorAdapter implements AnalyticsAdapter {
  static instances: FakeVendorAdapter[] = [];
  inits: AnalyticsAdapterConfig[] = [];
  captured: string[] = [];
  identified: string[] = [];
  screens: string[] = [];
  resets = 0;
  optOuts: boolean[] = [];

  constructor() {
    FakeVendorAdapter.instances.push(this);
  }
  init(config: AnalyticsAdapterConfig): void {
    this.inits.push(config);
  }
  isReady(): boolean {
    return true;
  }
  capture(name: string, _props: ScalarProps): void {
    this.captured.push(name);
  }
  identify(id: string, _props?: PersonProperties): void {
    this.identified.push(id);
  }
  screen(name: string, _props: ScalarProps): void {
    this.screens.push(name);
  }
  reset(): void {
    this.resets++;
  }
  setOptOut(optedOut: boolean): void {
    this.optOuts.push(optedOut);
  }
}

const CONFIG: AnalyticsAdapterConfig = {
  key: 'phc_test',
  apiHost: 'https://m.fredvested.com',
  sanitize: (props) => props,
  debug: false,
};

describe('LazyPostHogAdapter', () => {
  beforeEach(() => {
    FakeVendorAdapter.instances = [];
  });

  /** Resolves the fake module on demand, so the real posthog-js is never loaded in a spec. */
  function deferredLoader(): {
    loader: () => Promise<PostHogAdapterModule>;
    resolve: () => Promise<void>;
    reject: () => Promise<void>;
  } {
    let resolveFn: (value: PostHogAdapterModule) => void = () => undefined;
    let rejectFn: (reason: unknown) => void = () => undefined;
    const promise = new Promise<PostHogAdapterModule>((resolve, reject) => {
      resolveFn = resolve;
      rejectFn = reject;
    });
    return {
      loader: () => promise,
      resolve: async () => {
        resolveFn({ PostHogAdapter: FakeVendorAdapter });
        await promise.catch(() => undefined);
        await Promise.resolve();
        await Promise.resolve();
      },
      reject: async () => {
        rejectFn(new Error('chunk blocked'));
        await promise.catch(() => undefined);
        await Promise.resolve();
        await Promise.resolve();
      },
    };
  }

  it('loads nothing until init is called', () => {
    const { loader } = deferredLoader();
    const adapter = new LazyPostHogAdapter(loader);

    adapter.capture('app_opened', {});

    expect(FakeVendorAdapter.instances.length).toBe(0);
    expect(adapter.isReady()).toBeFalse();
  });

  it('queues calls made while the chunk is in flight and replays them in order', async () => {
    const { loader, resolve } = deferredLoader();
    const adapter = new LazyPostHogAdapter(loader);

    adapter.init(CONFIG);
    adapter.capture('app_opened', {});
    adapter.screen('/tabs/:tab', {});
    adapter.identify('11111111-2222-4333-8444-555555555555');

    expect(adapter.isReady()).toBeTrue();
    expect(FakeVendorAdapter.instances.length).toBe(0);

    await resolve();

    const vendor = FakeVendorAdapter.instances[0];
    expect(vendor.inits.length).toBe(1);
    expect(vendor.captured).toEqual(['app_opened']);
    expect(vendor.screens).toEqual(['/tabs/:tab']);
    expect(vendor.identified).toEqual(['11111111-2222-4333-8444-555555555555']);
  });

  it('passes calls straight through once loaded', async () => {
    const { loader, resolve } = deferredLoader();
    const adapter = new LazyPostHogAdapter(loader);
    adapter.init(CONFIG);
    await resolve();

    adapter.capture('app_opened', {});
    adapter.reset();
    adapter.setOptOut(true);

    const vendor = FakeVendorAdapter.instances[0];
    expect(vendor.captured).toEqual(['app_opened']);
    expect(vendor.resets).toBe(1);
    expect(vendor.optOuts).toEqual([true]);
  });

  it('loads the vendor once however many times init is called', async () => {
    const { loader, resolve } = deferredLoader();
    const adapter = new LazyPostHogAdapter(loader);

    adapter.init(CONFIG);
    adapter.init(CONFIG);
    await resolve();
    adapter.init(CONFIG);

    expect(FakeVendorAdapter.instances.length).toBe(1);
  });

  it('gives up quietly when the chunk never arrives', async () => {
    const { loader, reject } = deferredLoader();
    const adapter = new LazyPostHogAdapter(loader);

    adapter.init(CONFIG);
    adapter.capture('app_opened', {});
    await reject();

    expect(FakeVendorAdapter.instances.length).toBe(0);
    expect(adapter.isReady()).toBeFalse();
    expect(() => adapter.capture('app_opened', {})).not.toThrow();
  });

  it('bounds the queue so a session that never loads cannot grow memory', async () => {
    const { loader, resolve } = deferredLoader();
    const adapter = new LazyPostHogAdapter(loader);
    adapter.init(CONFIG);

    for (let i = 0; i < 120; i++) {
      adapter.capture(`app_opened`, { i });
    }
    await resolve();

    expect(FakeVendorAdapter.instances[0].captured.length).toBe(50);
  });
});
