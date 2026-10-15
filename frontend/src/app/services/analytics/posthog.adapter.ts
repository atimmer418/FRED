import posthog from 'posthog-js';
import { AnalyticsAdapter, AnalyticsAdapterConfig } from './analytics-adapter';
import { PersonProperties, ScalarProps } from './analytics-events';

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
 */
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
      sanitize_properties: (properties) => config.sanitize(properties as ScalarProps),
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
    } else {
      posthog.opt_in_capturing();
    }
  }
}
