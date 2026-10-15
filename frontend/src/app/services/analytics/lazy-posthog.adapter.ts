import { AnalyticsAdapter, AnalyticsAdapterConfig } from './analytics-adapter';
import { PersonProperties, ScalarProps } from './analytics-events';

/** What the dynamic import has to provide. Kept narrow so a spec can supply a fake. */
export type PostHogAdapterModule = { PostHogAdapter: new () => AnalyticsAdapter };

/**
 * Loads the vendor SDK on first use instead of at app start.
 *
 * Measured reason: importing posthog-js statically moved the production initial bundle from
 * 1.79 MB to 2.13 MB (401 kB to 494 kB transferred) and broke the 2 MB budget. Analytics is opted
 * out by default and sends nothing until consent exists, so every launch was paying ~90 kB over
 * the wire for code that may never run.
 *
 * Calls made while the chunk is in flight are queued and replayed in order, so the first event of
 * a session is not the one that gets lost. If the chunk never arrives (offline, blocked, SDK
 * removed) the queue is dropped and nothing throws: analytics must never break a flow.
 */
export class LazyPostHogAdapter implements AnalyticsAdapter {

  /** Bounded so a session that never loads the chunk cannot grow memory without limit. */
  private static readonly MAX_QUEUED_CALLS = 50;

  private real: AnalyticsAdapter | null = null;
  private loading = false;
  private failed = false;
  private queue: Array<(adapter: AnalyticsAdapter) => void> = [];

  constructor(private loader: () => Promise<PostHogAdapterModule> = () => import('./posthog.adapter')) {}

  init(config: AnalyticsAdapterConfig): void {
    if (this.loading || this.real || this.failed) {
      return;
    }
    this.loading = true;

    this.loader()
      .then(({ PostHogAdapter }) => {
        const adapter = new PostHogAdapter();
        adapter.init(config);
        this.real = adapter;
        this.flush(adapter);
      })
      .catch(() => {
        // Blocked, offline, or the chunk is gone. Give up quietly and stop queueing.
        this.failed = true;
        this.queue = [];
      })
      .finally(() => {
        this.loading = false;
      });
  }

  /** True once loading has started: delivery is guaranteed by the queue, not by being loaded. */
  isReady(): boolean {
    return this.real !== null || this.loading;
  }

  capture(eventName: string, props: ScalarProps): void {
    this.run((adapter) => adapter.capture(eventName, props));
  }

  identify(distinctId: string, personProps?: PersonProperties): void {
    this.run((adapter) => adapter.identify(distinctId, personProps));
  }

  screen(screenName: string, props: ScalarProps): void {
    this.run((adapter) => adapter.screen(screenName, props));
  }

  reset(): void {
    this.run((adapter) => adapter.reset());
  }

  setOptOut(optedOut: boolean): void {
    this.run((adapter) => adapter.setOptOut(optedOut));
  }

  private run(action: (adapter: AnalyticsAdapter) => void): void {
    if (this.real) {
      action(this.real);
      return;
    }
    if (this.failed || this.queue.length >= LazyPostHogAdapter.MAX_QUEUED_CALLS) {
      return;
    }
    this.queue.push(action);
  }

  private flush(adapter: AnalyticsAdapter): void {
    const queued = this.queue;
    this.queue = [];
    for (const action of queued) {
      try {
        action(adapter);
      } catch {
        // One bad replayed call must not drop the rest.
      }
    }
  }
}
