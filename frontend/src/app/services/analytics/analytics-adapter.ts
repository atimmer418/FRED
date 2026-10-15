import { PersonProperties, ScalarProps } from './analytics-events';

/**
 * What a product-analytics vendor has to provide. `AnalyticsService` depends on this interface and
 * never on a vendor SDK, so swapping vendors is one new adapter file plus one line of wiring.
 *
 * Implementations are dumb on purpose: no opt-out logic, no sanitizing, no environment checks.
 * All of that belongs in the service, where it can be tested once and cannot be bypassed by
 * reaching for the vendor directly.
 */
export interface AnalyticsAdapter {
  /**
   * Starts the SDK. Called at most once, and only when a key is configured.
   * @param config key, host and the sanitizer the service wants applied to every payload
   */
  init(config: AnalyticsAdapterConfig): void;

  /** True once init has run successfully. */
  isReady(): boolean;

  capture(eventName: string, props: ScalarProps): void;

  identify(distinctId: string, personProps?: PersonProperties): void;

  /** Screen or page view. Separate from capture because vendors model it separately. */
  screen(screenName: string, props: ScalarProps): void;

  /** Drops the current identity. Called on sign-out so the next user is not merged into this one. */
  reset(): void;

  /** Stops and starts sending. The service still gates every call itself. */
  setOptOut(optedOut: boolean): void;
}

export interface AnalyticsAdapterConfig {
  key: string;

  /** Our own reverse-proxied host, so the SDK is not blocked and cookies stay first-party. */
  apiHost: string;

  /**
   * Last line of defence, applied by the vendor SDK to every outgoing payload. The service
   * sanitizes before dispatch too; this catches anything the SDK itself adds.
   */
  sanitize: (props: ScalarProps) => ScalarProps;

  /** Dev builds log what would be sent. */
  debug: boolean;
}
