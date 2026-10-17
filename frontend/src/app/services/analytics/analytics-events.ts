/**
 * The one place an analytics event is defined.
 *
 * NAMING: `object_action`, snake_case, past tense. `bank_link_completed`, never
 * `completeBankLink` or `Bank Linked`. Renaming an event later breaks every saved insight and
 * every dashboard built on it, so the name is chosen once, here.
 *
 * RULES:
 * - Every event carries a typed payload. Nothing is fired with an ad-hoc object.
 * - Property values are scalars only ({@link ScalarValue}). Objects and arrays are rejected by the
 *   type system, because nested data is where PII hides.
 * - Never a raw dollar amount, never free text, never an identifier from a vendor. Bands and fixed
 *   vocabularies only. The guard in Checkpoint C enforces this at runtime as well; the types are
 *   the first line, not the only one.
 *
 * SCOPE: Checkpoint B defines only the smoke event, so the pipeline can be proven end to end
 * without committing to names that the funnel work has not reviewed yet. The activation,
 * engagement, lifecycle and email events arrive with the taxonomy in Checkpoint C.
 */

/** The only value shape allowed in an event payload. */
export type ScalarValue = string | number | boolean | null;

/** A payload whose values are all scalars. Used by `screen()`, which has no fixed vocabulary. */
export type ScalarProps = Record<string, ScalarValue>;

/**
 * Which build is reporting, taken from environment.name. Only 'dev' and 'prod' may send at all;
 * the property exists so the two can be told apart inside one project.
 */
export type AppEnvironment = 'local' | 'dev' | 'test' | 'ci' | 'prod';

/**
 * Events this app can fire. Add a member here before adding a call site; there is no untyped
 * escape hatch.
 */
export type AnalyticsEvent =
  /**
   * The very first launch after an install. Fires once; every launch after it is `app_opened`.
   * Install detection is storage-based, so a cleared store or a reinstall reads as a new install
   * (see AnalyticsService.trackAppOpened).
   */
  | { name: 'app_first_opened'; props: { platform: 'ios' | 'web'; app_environment: AppEnvironment } }
  /** Every launch after the first. Carries nothing that could identify anyone. */
  | { name: 'app_opened'; props: { platform: 'ios' | 'web'; app_environment: AppEnvironment } };

// TODO: not yet implemented — a later checkpoint adds the rest of the Phase 4 taxonomy here:
//   4a activation funnel: onboarding_started, account_created, kyc_started,
//      kyc_step_completed, kyc_submitted, kyc_approved, kyc_rejected, kyc_action_required,
//      bank_link_started, bank_link_completed, bank_link_failed, recurring_created,
//      deposit_initiated, deposit_settled, deposit_failed, deposit_returned, order_filled
//   4b engagement: freedom_date_viewed, freedom_date_changed, mfu_shown, mfu_dismissed, mfu_acted,
//      education_opened, education_question_asked, referral_link_shared, referral_signup_attributed
//   4c lifecycle: recurring_paused, recurring_resumed, recurring_canceled (not yet implemented in
//      the app), recurring_amount_changed, bank_unlinked (not yet implemented in the app),
//      withdrawal_requested, account_closed
//   4d email (server side, from the Resend webhook): email_sent, email_delivered, email_bounced,
//      email_complained, email_clicked. Deliberately NOT email_opened.
// Subscription and billing events are out of scope for this workstream entirely.

/** Event names, derived from the union so the two can never drift apart. */
export type AnalyticsEventName = AnalyticsEvent['name'];

/**
 * Person properties. Safe, low-cardinality facts about a user, set at identify time.
 *
 * Never on this list, and rejected by the Checkpoint C guard if anyone tries: name, email, phone,
 * address, date of birth, SSN or any part of it, account numbers, bank name, balance, portfolio
 * value, contribution amount, income.
 *
 * Checkpoint C populates these; the type exists now because `identify()` is part of the service
 * contract from the start.
 */
export interface PersonProperties {
  /** Month the account was created, e.g. '2026-11'. Month, not day: day is near-unique. */
  signup_month?: string;
  acquisition_source?: string;
  acquisition_campaign?: string;
  acquisition_content?: string;
  waitlist_member?: boolean;
  founder_access?: boolean;
  kyc_status?: 'not_started' | 'pending' | 'approved' | 'rejected';
  bank_linked?: boolean;
  has_active_recurring?: boolean;
}
