import { Inject, Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { environment } from '../../../environments/environment';
import { AnalyticsAdapter } from './analytics-adapter';
import { ANALYTICS_ADAPTER } from './analytics-adapter.token';
import { applyPrivacyGuard } from './analytics-privacy-guard';
import {
  AnalyticsEvent,
  AppEnvironment,
  ErrorCategory,
  ErrorDisplay,
  OnboardingStep,
  PersonProperties,
  ScalarProps,
  Surface,
} from './analytics-events';

/**
 * The single dispatch point for product analytics.
 *
 * Everything that must hold for every event is enforced here rather than left to the caller:
 *
 * - **Analytics never breaks a flow.** Every public method is wrapped in try/catch. A vendor
 *   outage, a blocked script or a storage exception must never surface to a user or fail a
 *   deposit, so failures are swallowed (logged in dev only).
 * - **Opt-out wins.** While opted out, nothing is dispatched and identify is never called. The
 *   value comes from the server (users.analytics_opt_out) so it is honoured on every device; until
 *   a server value arrives the answer is opted out.
 * - **Only dev and prod may send.** environment.name has to be 'dev' or 'prod'. Local and CI
 *   traffic would otherwise pollute every funnel, and 'test' has no project.
 * - **No key, no traffic.** With no configured key the service is a clean no-op and says so once
 *   at startup, so a build that was never given a project cannot silently look healthy.
 * - **Silent under test.** Karma runs never touch the vendor.
 *
 * Callers do not get a vendor handle, so there is no way to bypass these rules except by importing
 * posthog-js directly, which the lint rule forbids.
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsService {

  /** Builds allowed to send at all. Local and CI would pollute the funnels; test has no project. */
  private static readonly SENDING_ENVIRONMENTS: readonly string[] = ['dev', 'prod'];

  /** Set once per install, so app_first_opened fires once and app_opened every launch after. */
  private static readonly FIRST_OPEN_KEY = 'fred.analytics.firstOpenSeen';

  /** RFC 4122 shape. An email, a numeric id or a bare string can never satisfy it. */
  private static readonly UUID_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

  /**
   * Default DENY, until the server says otherwise. users.analytics_opt_out defaults to true, so a
   * user who has never been asked is not measured, and a failure to read consent fails closed.
   */
  private optedOut = true;

  private initialized = false;
  private initFailed = false;
  private warnedNoKey = false;

  /** Last screen sent, so a re-pulse of the same screen is not counted twice. */
  private lastScreen: string | null = null;

  /** False until the server's consent value has been applied for this session. */
  private consentKnown = false;

  /**
   * The launch event, held until consent is known.
   *
   * trackAppOpened runs at startup, which is before the session payload has come back, so without
   * this the launch event was always dropped and no consenting user would ever record one. Held
   * rather than sent: a user who turns out to be opted out must produce nothing at all.
   */
  private pendingLaunchEvent: AnalyticsEvent | null = null;

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

  /**
   * Binds subsequent events to a user. The id must be the opaque public_id (a UUID): never an
   * email, never a name, never the numeric row id.
   *
   * A value that is not a UUID is refused rather than sent. Getting this wrong would put PII in a
   * third-party tool permanently, and the shape check is the cheapest way to make that impossible.
   */
  identify(userId: string, personProps?: PersonProperties): void {
    this.safely(() => {
      if (!this.isUuid(userId)) {
        if (!environment.production) {
          console.warn('[analytics] identify refused: the distinct id must be the public_id UUID.');
        }
        return;
      }
      if (!this.ensureReady()) {
        return;
      }
      this.adapter.identify(userId, personProps);
    });
  }

  /** True only for an RFC 4122 UUID. Used to keep anything identifying out of the distinct id. */
  private isUuid(value: string | null | undefined): boolean {
    return !!value && AnalyticsService.UUID_PATTERN.test(value);
  }

  /** Screen view. The name must already be parameter-free: '/account/:id', never '/account/12345'. */
  screen(screenName: string, props: ScalarProps = {}): void {
    this.safely(() => {
      if (!screenName || !this.ensureReady()) {
        return;
      }
      // The tab pager re-pulses activation when returning to a cached tab, which is not a new
      // screen view. Dropping an unchanged name keeps those out of the data.
      if (screenName === this.lastScreen) {
        return;
      }
      this.lastScreen = screenName;
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

  /**
   * Applies the consent value the server returned on the session payload. This is the only place
   * consent is decided in normal operation; the local default exists to fail closed before the
   * first payload arrives.
   */
  applyServerConsent(optOut: boolean | null | undefined): void {
    // Anything other than an explicit false means opted out.
    this.setOptOut(optOut !== false);
  }

  /** Honours a user's choice. While opted out, nothing is dispatched at all. */
  setOptOut(optedOut: boolean): void {
    const changed = this.optedOut !== optedOut;
    const firstAnswer = !this.consentKnown;
    this.optedOut = optedOut;
    // Any explicit decision counts as consent being known, wherever it came from. Otherwise a
    // caller that sets consent directly would silently lose the held launch event.
    this.consentKnown = true;

    this.safely(() => {
      // Only when it actually changed: consent is re-applied on every session load, and the
      // vendor emits an event of its own each time it is told.
      if (!this.initialized || !(changed || firstAnswer)) {
        return;
      }
      this.adapter.setOptOut(optedOut);
    });

    const pending = this.pendingLaunchEvent;
    this.pendingLaunchEvent = null;
    if (pending && !optedOut) {
      this.track(pending);
    }
  }

  /** Current consent state, for the settings UI and for tests. */
  isOptedOut(): boolean {
    return this.optedOut;
  }

  /**
   * One launch event: `app_first_opened` the first time this install runs, `app_opened` every time
   * after. Gated like every other event.
   *
   * Install detection is a stored flag, which is the only signal available to a web-origin app.
   * Its limits are real and worth knowing when reading the funnel: clearing site data or the
   * close-account flow (which clears localStorage) makes the next launch look like a new install,
   * and the flag is per origin. The iOS Keychain would survive a reinstall and could tighten this
   * later; it is not worth a native bridge yet.
   */
  trackAppOpened(): void {
    const props = {
      platform: (Capacitor.isNativePlatform() ? 'ios' : 'web') as 'ios' | 'web',
      app_environment: this.appEnvironment(),
    };

    const event: AnalyticsEvent = this.claimFirstOpen()
      ? { name: 'app_first_opened', props }
      : { name: 'app_opened', props };

    if (!this.consentKnown) {
      // Startup runs ahead of the session payload. Hold it; applyServerConsent decides.
      this.pendingLaunchEvent = event;
      return;
    }

    this.track(event);
  }

  /**
   * Marks this install as seen and reports whether it was the first launch. The flag is written
   * whether or not the event is eventually dispatched, so a launch while opted out does not leave
   * the next launch claiming to be the install.
   */
  private claimFirstOpen(): boolean {
    try {
      if (localStorage.getItem(AnalyticsService.FIRST_OPEN_KEY)) {
        return false;
      }
      localStorage.setItem(AnalyticsService.FIRST_OPEN_KEY, new Date().toISOString());
      return true;
    } catch {
      // Storage blocked: treat as a normal launch rather than claiming an install every time.
      return false;
    }
  }

  /**
   * An onboarding step was shown. `resumed` separates a returning user being routed back to their
   * first incomplete step from a fresh arrival, which is the difference between a drop-off and a
   * normal progression.
   */
  trackOnboardingStepViewed(step: OnboardingStep, subStep: number | null = null, resumed = false): void {
    this.track({ name: 'onboarding_step_viewed', props: { step, sub_step: subStep, resumed } });
  }

  /**
   * The user finished an onboarding step. Call at the user's action, not in the save callback:
   * the progress write is fire-and-forget and the app navigates either way, so waiting for it
   * would undercount.
   */
  trackOnboardingStepCompleted(step: OnboardingStep, subStep: number | null = null): void {
    this.track({ name: 'onboarding_step_completed', props: { step, sub_step: subStep } });
  }

  /**
   * An error the user actually saw. Takes a category, never a message: app copy interpolates
   * names, bank names, amounts and transfer ids, and server messages are unbounded.
   */
  trackErrorShown(
    category: ErrorCategory,
    surface: Surface,
    display: ErrorDisplay = 'toast',
    httpStatus: number | null = null,
    retryAvailable = false,
  ): void {
    this.track({
      name: 'error_shown',
      props: { category, surface, display, http_status: httpStatus, retry_available: retryAvailable },
    });
  }

  /**
   * Lazy init: nothing is loaded until the first real call, and never while opted out, under
   * Karma, or without a key.
   */
  private ensureReady(): boolean {
    if (!AnalyticsService.SENDING_ENVIRONMENTS.includes(environment.name)) {
      return false;
    }

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
        debug: environment.name === 'dev',
      });
      this.initialized = true;
      // The SDK keeps its own opt-out state; keep it in step with ours.
      this.adapter.setOptOut(this.optedOut);
    }

    return this.adapter.isReady();
  }

  /**
   * Last pass before anything leaves the device. Applied to our own payloads and, through the
   * vendor's sanitize hook, to the properties the SDK adds by itself.
   *
   * The URL properties are the reason this is not a no-op yet. The SDK attaches $current_url and
   * $pathname automatically, and routes like /beneficiaries/edit/123 put a real row id in them,
   * which is exactly what "screen name only, no ids" forbids. Query strings go too: they have
   * carried a dev-tools key.
   *
   * The URL pass runs FIRST and the deny-list second, which is the only order that works: a
   * route id and a query string are forbidden content in a legitimate property, so scrubbing has
   * to happen before anything judges the value. Reversed, `$current_url` would be stripped whole
   * for carrying a long digit run, and every event would lose its screen.
   */
  private sanitize(props: ScalarProps): ScalarProps {
    const sanitized: ScalarProps = { ...props };

    for (const key of AnalyticsService.URL_PROPERTIES) {
      const value = sanitized[key];
      if (typeof value === 'string') {
        sanitized[key] = AnalyticsService.scrubUrl(value);
      }
    }

    const guarded = applyPrivacyGuard(sanitized);
    if (guarded.violations.length && !environment.production) {
      for (const violation of guarded.violations) {
        // error, not warn: this is a bug in the calling code, and it is the one kind of analytics
        // problem that must be fixed before the build ships. The value is never printed.
        console.error(
          `[analytics] stripped '${violation.key}': its ${violation.kind} matches the ` +
            `forbidden ${violation.rule} rule. Nothing identifying may be sent; band it, ` +
            'collapse it to a fixed vocabulary, or do not send it.',
        );
      }
    }

    return guarded.props;
  }

  /**
   * Properties the SDK fills with a real URL, which therefore carry our route ids and query
   * strings unless they are scrubbed. The `$initial_*` three reach the vendor as person
   * properties rather than event properties, which is why the scrub has to run over `$set_once`
   * as well as `properties`. `$initial_host` is deliberately absent: it is a bare hostname with
   * no scheme, so scrubUrl cannot parse it as a URL and would mangle it, and a hostname carries
   * nothing of ours anyway.
   */
  private static readonly URL_PROPERTIES = [
    '$current_url',
    '$pathname',
    '$initial_current_url',
    '$initial_pathname',
    '$referrer',
    '$initial_referrer',
    '$session_entry_url',
    '$session_entry_pathname',
  ];

  /** A path segment that is an id rather than a route name. */
  private static readonly ID_SEGMENT =
    /^(\d+|[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|[0-9a-f]{16,})$/i;

  /** Drops the query and fragment, and replaces id-shaped path segments with ':id'. */
  static scrubUrl(value: string): string {
    if (!value || value === '$direct') {
      return value;
    }

    try {
      const hasOrigin = /^[a-z][a-z0-9+.-]*:\/\//i.test(value);
      const url = new URL(value, 'http://placeholder.invalid');
      const path = url.pathname
        .split('/')
        .map((segment) => (AnalyticsService.ID_SEGMENT.test(segment) ? ':id' : segment))
        .join('/');
      return hasOrigin ? `${url.origin}${path}` : path;
    } catch {
      // Unparseable: send nothing rather than guess at what is in it.
      return '';
    }
  }

  private appEnvironment(): AppEnvironment {
    return environment.name;
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
