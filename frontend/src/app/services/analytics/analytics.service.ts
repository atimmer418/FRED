import { Inject, Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { environment } from '../../../environments/environment';
import { AnalyticsAdapter } from './analytics-adapter';
import { ANALYTICS_ADAPTER } from './analytics-adapter.token';
import { AnalyticsEvent, AppEnvironment, PersonProperties, ScalarProps } from './analytics-events';

/**
 * The single dispatch point for product analytics.
 *
 * Everything that must hold for every event is enforced here rather than left to the caller:
 *
 * - **Analytics never breaks a flow.** Every public method is wrapped in try/catch. A vendor
 *   outage, a blocked script or a storage exception must never surface to a user or fail a
 *   deposit, so failures are swallowed (logged in dev only).
 * - **Opt-out wins.** While opted out, nothing is dispatched. The default is opted OUT until the
 *   consent plumbing lands in Checkpoint C, so this checkpoint sends nothing in practice.
 * - **No key, no traffic.** With no configured key the service is a clean no-op and says so once
 *   at startup, so a build that was never given a project cannot silently look healthy.
 * - **Silent under test.** Karma runs never touch the vendor.
 *
 * Callers do not get a vendor handle, so there is no way to bypass these rules except by importing
 * posthog-js directly, which the lint rule forbids.
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsService {

  /**
   * Default DENY. Checkpoint C wires the real consent source (a server-side
   * users.analytics_opt_out, mirrored locally) and flips the default to on-with-opt-out, which is
   * what the funnel work needs. Until then this service is inert by construction.
   */
  private optedOut = true;

  private initialized = false;
  private initFailed = false;
  private warnedNoKey = false;

  constructor(@Inject(ANALYTICS_ADAPTER) private adapter: AnalyticsAdapter) {}

  /**
   * Records an event. The payload is typed per event, so there is no ad-hoc shape to review.
   */
  track(event: AnalyticsEvent): void {
    this.safely(() => {
      if (!this.ensureReady()) {
        return;
      }
      this.adapter.capture(event.name, this.sanitize(event.props as ScalarProps));
    });
  }

  /** Binds subsequent events to a user. The id must be opaque: never an email, never a row id. */
  identify(userId: string, personProps?: PersonProperties): void {
    this.safely(() => {
      if (!userId || !this.ensureReady()) {
        return;
      }
      this.adapter.identify(userId, personProps);
    });
  }

  /** Screen view. The name must already be parameter-free: '/account/:id', never '/account/12345'. */
  screen(screenName: string, props: ScalarProps = {}): void {
    this.safely(() => {
      if (!this.ensureReady()) {
        return;
      }
      this.adapter.screen(screenName, this.sanitize(props));
    });
  }

  /**
   * Drops the current identity. Must run on every sign-out, and BEFORE anything that clears
   * localStorage, or the vendor's own stored state outlives the session it belonged to.
   */
  reset(): void {
    this.safely(() => {
      if (!this.initialized) {
        return;
      }
      this.adapter.reset();
    });
  }

  /** Honours a user's choice. While opted out, nothing is dispatched at all. */
  setOptOut(optedOut: boolean): void {
    this.optedOut = optedOut;
    this.safely(() => {
      if (!this.initialized) {
        return;
      }
      this.adapter.setOptOut(optedOut);
    });
  }

  /** Current consent state, for the settings UI and for tests. */
  isOptedOut(): boolean {
    return this.optedOut;
  }

  /**
   * The one smoke event of Checkpoint B: proves the client, the proxy and the project are wired
   * together without carrying anything about anyone. Gated like every other event, so with the
   * default opt-out it sends nothing.
   */
  trackAppOpened(): void {
    this.track({
      name: 'app_opened',
      props: {
        platform: Capacitor.isNativePlatform() ? 'ios' : 'web',
        app_environment: this.appEnvironment(),
      },
    });
  }

  /**
   * Lazy init: nothing is loaded until the first real call, and never while opted out, under
   * Karma, or without a key.
   */
  private ensureReady(): boolean {
    if (this.optedOut || this.isUnderTest() || this.initFailed) {
      return false;
    }

    if (!environment.posthogKey) {
      if (!this.warnedNoKey) {
        this.warnedNoKey = true;
        // Not an error: a build without a project key is a valid state (CI, and every build until
        // the production project exists). Said once so it cannot be mistaken for working.
        console.info('[analytics] No project key configured; analytics is a no-op in this build.');
      }
      return false;
    }

    if (!this.initialized) {
      this.adapter.init({
        key: environment.posthogKey,
        apiHost: environment.posthogApiHost,
        sanitize: (props) => this.sanitize(props),
        debug: !environment.production && this.appEnvironment() === 'local',
      });
      this.initialized = true;
      // The SDK keeps its own opt-out state; keep it in step with ours.
      this.adapter.setOptOut(this.optedOut);
    }

    return this.adapter.isReady();
  }

  /**
   * Checkpoint C replaces this with the Phase 7 guard (forbidden key patterns plus value-shape
   * checks, with a test that fires every event with a hostile payload). It is a seam, not a
   * safeguard, today: the type system is what keeps payloads scalar for now.
   */
  private sanitize(props: ScalarProps): ScalarProps {
    return props;
  }

  private appEnvironment(): AppEnvironment {
    if (environment.production) {
      return 'prod';
    }
    if (environment.local) {
      // Both the default (local) and ci configs set local:true; the backend URL tells them apart.
      return environment.backendApiUrl.includes('localhost') ? 'ci' : 'local';
    }
    return environment.backendApiUrl.includes('api-dev') ? 'dev' : 'test';
  }

  /** Karma sets this global. Specs must never reach a vendor. */
  private isUnderTest(): boolean {
    return typeof window !== 'undefined' && !!(window as unknown as { __karma__?: unknown }).__karma__;
  }

  /** Analytics must never be the reason a user-facing flow fails. */
  private safely(action: () => void): void {
    try {
      action();
    } catch (error) {
      if (!environment.production) {
        console.warn('[analytics] suppressed', error);
      }
    }
  }
}
