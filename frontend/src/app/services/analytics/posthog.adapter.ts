import posthog from 'posthog-js';
import { AnalyticsAdapter, AnalyticsAdapterConfig } from './analytics-adapter';
import { PersonProperties, ScalarProps } from './analytics-events';
import { buildBeforeSend } from './posthog-before-send';

/**
 * The ONLY file in the app allowed to import posthog-js. The ESLint rule
 * `no-restricted-imports` fails the build on an import anywhere else, and
 * scripts/lint/assert-posthog-restriction.mjs proves the rule actually fires.
 *
 * Why the init options are what they are:
 *
 * - `autocapture: false` — autocapture records the text of elements the user clicks. In this app
 *   that would mean dollar amounts, account nicknames and partial account numbers landing in a
 *   third-party tool. Everything we send is chosen deliberately.
 * - `capture_pageview: false` — Ionic routes on the client, and tab changes here do not even go
 *   through the Angular router: the pager rewrites the URL with history.replaceState, which
 *   PostHog's history-based pageview capture would see as navigation. Screen events are sent by
 *   hand instead.
 * - `disable_session_recording: true` — replay would capture the KYC form, which holds an SSN.
 * - `capture_performance: false` — no network timing payloads; our URLs carry ids.
 * - `persistence: 'localStorage'` — no cookies to consent-manage. Note the app is served from a
 *   remote origin in the iOS build, so storage is per-origin.
 * - `person_profiles: 'identified_only'` — no person records for anonymous traffic.
 * - `before_send` — the last-line scrub, applied by the SDK to every payload including the ones
 *   it builds itself. This replaced `sanitize_properties`, which posthog-js deprecated. The old
 *   option was called twice per capture, once for the event properties and once for the person
 *   properties, so the hook has to scrub both; buildBeforeSend spells out why.
 */
/**
 * posthog-js accepts options on `opt_in_capturing` at runtime, but @posthog/types declares it as
 * taking none. Declared here, in the one file allowed to know about the vendor.
 */
interface OptInCapturing {
  opt_in_capturing(options?: { captureEventName?: string | null }): void;
}

export class PostHogAdapter implements AnalyticsAdapter {

  private ready = false;

  init(config: AnalyticsAdapterConfig): void {
    posthog.init(config.key, {
      api_host: config.apiHost,
      person_profiles: 'identified_only',
      capture_pageview: false,
      autocapture: false,
      disable_session_recording: true,
      capture_performance: false,
      persistence: 'localStorage',
      debug: config.debug,
      before_send: buildBeforeSend(config.sanitize),
    });
    this.ready = true;
  }

  isReady(): boolean {
    return this.ready;
  }

  capture(eventName: string, props: ScalarProps): void {
    posthog.capture(eventName, props);
  }

  identify(distinctId: string, personProps?: PersonProperties): void {
    posthog.identify(distinctId, personProps as Record<string, unknown> | undefined);
  }

  screen(screenName: string, props: ScalarProps): void {
    // PostHog models a screen view as $pageview plus the screen name. capture_pageview is off, so
    // this is the only source of them.
    posthog.capture('$pageview', { ...props, $screen_name: screenName });
  }

  reset(): void {
    posthog.reset();
  }

  setOptOut(optedOut: boolean): void {
    if (optedOut) {
      posthog.opt_out_capturing();
      return;
    }

    // The SDK is opted in by default, and every launch told it so again. Each of those calls
    // captured a billable `$opt_in`, once per launch per user, for a state that had not changed.
    // Nothing is opted in here unless the SDK says it is currently opted OUT.
    if (!posthog.has_opted_out_capturing()) {
      return;
    }

    // And when it genuinely does change, the vendor's own event is suppressed: a consent change
    // is already reported as setting_changed{analytics_consent}, which carries the outcome and
    // the surface. `captureEventName` is honoured at runtime but missing from the published
    // types, hence the narrow cast rather than a blanket `any` on posthog.
    (posthog as unknown as OptInCapturing).opt_in_capturing({ captureEventName: null });
  }
}
