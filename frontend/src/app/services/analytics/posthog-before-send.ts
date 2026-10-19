import { ScalarProps } from './analytics-events';

/**
 * The only part of posthog-js's `CaptureResult` this hook touches. Declared structurally rather
 * than imported, so this file stays outside the one-file posthog-js import rule and can be tested
 * without pulling the SDK into the spec bundle. The hook is generic over the real type, so the
 * adapter still type-checks against the SDK's own signature and a payload keeps every other field
 * it arrived with.
 */
export interface VendorCaptureResult {
  properties?: Record<string, unknown>;
  /** Person properties. Top-level on the capture result, NOT inside `properties`. */
  $set?: Record<string, unknown>;
  /** Initial person properties: `$initial_current_url`, `$initial_pathname`, `$initial_referrer`. */
  $set_once?: Record<string, unknown>;
}

/**
 * Builds the `before_send` hook that applies our sanitizer to every outgoing payload.
 *
 * This replaces `sanitize_properties`, which posthog-js deprecated (it logs an error on every
 * capture and is slated for removal). Three things are worth knowing about the new hook:
 *
 * - It must scrub THREE objects, not one. posthog-js called `sanitize_properties` twice per
 *   capture: once over the event properties, and once over `$set_once`, the initial person
 *   properties it builds itself. Those hold `$initial_current_url`, `$initial_pathname` and
 *   `$initial_referrer`, which is exactly why those keys are in the service's URL list. They are
 *   top-level fields on the capture result rather than entries in `properties`, so scrubbing only
 *   `properties` would let a route id or a query string onto a person record permanently, where it
 *   outlives the event. `$set` gets the same treatment, for when identify() starts carrying
 *   person properties.
 *
 * - It can DROP an event by returning null, which `sanitize_properties` could not do. A payload
 *   we failed to scrub is exactly the payload that must not be sent, so a throwing sanitizer
 *   fails closed and the event is dropped. In normal operation this never fires.
 * - It is called with null when an earlier hook already dropped the event. Pass that through
 *   rather than resurrecting it.
 *
 * The result is copied rather than mutated: the SDK keeps its own reference to the payload, and
 * mutating it in place has bitten other integrations.
 */
export function buildBeforeSend<T extends VendorCaptureResult>(
  sanitize: (props: ScalarProps) => ScalarProps,
): (result: T | null) => T | null {
  return (result) => {
    if (!result) {
      return result;
    }
    try {
      const scrubbed = { ...result };
      if (result.properties) {
        scrubbed.properties = sanitize(result.properties as ScalarProps);
      }
      if (result.$set) {
        scrubbed.$set = sanitize(result.$set as ScalarProps);
      }
      if (result.$set_once) {
        scrubbed.$set_once = sanitize(result.$set_once as ScalarProps);
      }
      return scrubbed;
    } catch {
      // Fail closed. Analytics must never break a user flow, and it must never send a payload
      // that skipped the scrub, so the event is dropped and the caller is none the wiser.
      return null;
    }
  };
}
