// FIXTURE, not application code. Lives outside src/ so no build or spec picks it up.
//
// assert-posthog-restriction.mjs lints this file and expects the no-restricted-imports rule to
// fire. If this file ever lints clean, the guardrail that keeps vendor calls inside
// services/analytics/posthog.adapter.ts has stopped working.
import posthog from 'posthog-js';

export function bypassTheAbstraction(): void {
  posthog.capture('this_should_never_be_possible_outside_the_adapter', {});
}
