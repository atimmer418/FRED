import { AmountBand, CountBand, PercentBand, YearsBand } from './analytics-bands';

/**
 * The one place a client-side analytics event is defined.
 *
 * NAMING: `object_action`, snake_case, past tense. `bank_link_completed`, never
 * `completeBankLink` or `Bank Linked`. Renaming an event later breaks every saved insight and
 * every dashboard built on it, so the name is chosen once, here.
 *
 * RULES:
 * - Every event carries a typed payload. Nothing is fired with an ad-hoc object.
 * - Property values are scalars only ({@link ScalarValue}). Objects and arrays are rejected by the
 *   type system, because nested data is where PII hides.
 * - Never a raw dollar amount: money is banded through analytics-bands.ts.
 * - Never free text. Not a goal name, not a toast message, not a server error string. Where a
 *   message would be informative, send a slug from a fixed vocabulary instead.
 * - Never an identifier. Not a goal id, not a session id, not an account number, not an email.
 *
 * WHAT BELONGS HERE: things the person did on the device. Taps, screens, choices, and errors they
 * were shown.
 *
 * WHAT DOES NOT: anything that represents money actually moving (deposits, orders, transfers,
 * withdrawals) or a compliance state (KYC decisions, account status). Those are facts the server
 * knows and the client only guesses at, so they are emitted server-side. The reserved names are
 * listed at the bottom of this file so the two taxonomies cannot collide.
 *
 * The companion document is FREDdocs/analytics-events.md, and
 * scripts/docs/assert-events-documented.mjs fails the build if an event here is missing from it.
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

// ---------------------------------------------------------------------------------------------
// Shared vocabularies. Every one of these mirrors a closed set that already exists in the app, so
// the analytics names and the code cannot drift apart silently.
// ---------------------------------------------------------------------------------------------

/**
 * The onboarding funnel, in order. These mirror the arguments to AuthService.completeStep, which
 * is what the user_progress columns are derived from, rather than the routes: the two differ on
 * one step, where completeStep('linkPlaid') lives at the route /link-bank.
 *
 * The first three steps happen before an account exists, so they land on an anonymous id and are
 * stitched to the person at identify() time.
 */
export type OnboardingStep =
  | 'get_started'
  | 'survey_initial'
  | 'fi_plan_results'
  | 'auth_finalize'
  | 'kyc_verification'
  | 'link_plaid'
  | 'investment_schedule'
  | 'investment_confirmation';

/** The drawdown strategy a user picks during onboarding (fi-plan-results). */
export type PlanStrategy = 'yield' | 'guardrails' | 'sbloc' | 'annuity';

/**
 * A strategy explainer deck. The first four are the education decks; 'optimal_drawdown' is the
 * Pro-only deck reached from inside them.
 */
export type StrategyDeck = PlanStrategy | 'optimal_drawdown';

/** How a user left a strategy deck. Mirrors the modal dismiss roles. */
export type DeckExit = 'cancel' | 'done' | 'stress_test' | 'drawdown' | 'upgrade';

/** The Monte Carlo input steps. Mirrors FlowStep; 'outside' only exists when the toggle is on. */
export type MonteCarloStep = 'portfolio' | 'outside' | 'spend' | 'duration';

/** How a Monte Carlo input was set. 'fetched' means the live portfolio value chip was used. */
export type InputMethod = 'slider' | 'typed' | 'fetched';

/** Market scenario. Internal keys, not the UI labels, which differ ('expected' renders Moderate). */
export type MarketScenario = 'conservative' | 'expected' | 'aggressive';

/** The three-way verdict on a simulation. Mirrors the existing badge() thresholds: 90 / 75. */
export type SimulationOutcome = 'strong' | 'moderate' | 'risk';

/** Return-rate preset on the freedom plan. The only three values a user can choose. */
export type ReturnPreset = 'conservative' | 'moderate' | 'optimistic';

/** Subscription tier as the client sees it. Null when the user has none. */
export type Tier = 'core' | 'plus' | 'pro' | null;

/** Goal icons. Mirrors GoalIcon; the goal's NAME is free text and never leaves the device. */
export type GoalIcon =
  | 'home'
  | 'health_and_safety'
  | 'flag'
  | 'balance'
  | 'savings'
  | 'school'
  | 'directions_car';

/** What happened to a goal. 'completed' and 'reopened' are the two sides of the same toggle. */
export type GoalAction = 'created' | 'updated' | 'completed' | 'reopened' | 'removed';

/** Settings a user can actually change today. Anything not reachable from the UI is not here. */
export type SettingKey =
  | 'app_lock'
  | 'step_up_auth'
  | 'analytics_consent'
  | 'drip'
  | 'rebalance_schedule'
  | 'marketing_emails';

/** Outcome of a settings change: not every tap results in a saved value. */
export type SettingOutcome = 'applied' | 'cancelled' | 'failed';

/** A paywalled surface. The nudge is a product interaction; the purchase is not ours. */
export type GatedFeature =
  | 'goals'
  | 'rebalancing'
  | 'monte_carlo'
  | 'market_scenarios'
  | 'optimal_drawdown'
  | 'tax_loss_harvesting';

/**
 * Error categories, grounded in the failures that actually exist in the app rather than invented.
 * The message itself is never sent: server messages are unbounded and app copy interpolates names,
 * bank names, amounts and vendor ids.
 */
export type ErrorCategory =
  | 'network'
  | 'auth_required'
  | 'passkey'
  | 'step_up_pin'
  | 'bank_link'
  | 'validation'
  | 'save_failed'
  | 'load_failed'
  | 'document'
  | 'chat'
  | 'unavailable'
  | 'unknown';

/** How the error reached the user, which changes how likely they were to see it. */
export type ErrorDisplay = 'toast' | 'inline_field' | 'inline_banner' | 'alert';

/** Where an event happened, when the same event can fire from more than one place. */
export type Surface =
  | 'onboarding'
  | 'freedom_sheet'
  | 'my_profile'
  // The goal sheet is its own surface, not the page that presents it: it is reachable from more
  // than one place, and an error inside it is a different fact from an error on the profile page.
  | 'goals'
  | 'retirement_planning'
  | 'monte_carlo'
  | 'strategy_deck'
  | 'settings'
  | 'portfolio'
  | 'chat'
  | 'other';

// ---------------------------------------------------------------------------------------------
// The events.
// ---------------------------------------------------------------------------------------------

/**
 * Events this app can fire from the device. Add a member here before adding a call site; there is
 * no untyped escape hatch.
 */
export type AnalyticsEvent =
  /**
   * The very first launch after an install. Fires once; every launch after it is `app_opened`.
   * Install detection is storage-based, so a cleared store or a reinstall reads as a new install.
   */
  | { name: 'app_first_opened'; props: { platform: 'ios' | 'web'; app_environment: AppEnvironment } }

  /** Every launch after the first. Carries nothing that could identify anyone. */
  | { name: 'app_opened'; props: { platform: 'ios' | 'web'; app_environment: AppEnvironment } }

  /**
   * An onboarding step was shown. Fires again when a returning user is routed back to their first
   * incomplete step, which is why `resumed` matters: it separates a fresh arrival from a return.
   * `sub_step` is the step's internal page where it has one (KYC is a two-part form), else null.
   */
  | {
      name: 'onboarding_step_viewed';
      props: { step: OnboardingStep; sub_step: number | null; resumed: boolean };
    }

  /**
   * The user finished a step. Fired at the user's action, not in the save callback: the progress
   * write is fire-and-forget and the app navigates on both success and failure, so waiting for it
   * would undercount.
   */
  | {
      name: 'onboarding_step_completed';
      props: { step: OnboardingStep; sub_step: number | null };
    }

  /** The drawdown strategy chosen on the onboarding plan screen. */
  | { name: 'plan_strategy_selected'; props: { strategy: PlanStrategy } }

  /**
   * A strategy explainer was read. Fired once when the deck closes, carrying how far the user got
   * and how they left, rather than one event per slide.
   */
  | {
      name: 'strategy_viewed';
      props: {
        strategy: StrategyDeck;
        surface: Extract<Surface, 'onboarding' | 'retirement_planning'>;
        max_slide_reached: number;
        slide_count: number;
        exit: DeckExit;
      };
    }

  /**
   * The freedom plan was changed. Fired at the save point, never per keystroke, and carries bands
   * plus what-changed flags. Note the two amounts are in different units on screen: income is the
   * monthly target, investing is the monthly contribution goal.
   *
   * The three *_changed flags are what make the event readable: without them a save where the user
   * touched only the return preset is indistinguishable from a save where they touched nothing,
   * because both carry the same bands.
   *
   * The two surfaces establish them differently, which matters when comparing across them. The
   * freedom sheet reports a net change against what it opened with. Onboarding reports that the
   * control was operated, and cannot use a snapshot: the saved plan arrives asynchronously and
   * rewrites both amounts after the screen opened, so a snapshot would report edits nobody made.
   */
  | {
      name: 'freedom_plan_changed';
      props: {
        surface: Extract<Surface, 'onboarding' | 'freedom_sheet'>;
        income_changed: boolean;
        invest_changed: boolean;
        /** False on a surface with no rate control, where `return_preset` is always null. */
        preset_changed: boolean;
        monthly_income_band: AmountBand | null;
        monthly_invest_band: AmountBand | null;
        return_preset: ReturnPreset | null;
        years_to_freedom: YearsBand;
      };
    }

  /**
   * A goal was created, edited, completed, reopened or removed. The goal's name and id never
   * leave the device; the icon and the bands are what describe it.
   */
  | {
      name: 'goal_changed';
      props: {
        action: GoalAction;
        icon: GoalIcon;
        target_band: AmountBand;
        years_to_target: YearsBand;
        progress: PercentBand | null;
        goal_count: CountBand;
      };
    }

  /**
   * A setting was changed. `value` carries the chosen option for settings that are not a simple
   * switch (the rebalance schedule), and is null otherwise.
   */
  | {
      name: 'setting_changed';
      props: {
        setting: SettingKey;
        enabled: boolean | null;
        value: string | null;
        outcome: SettingOutcome;
      };
    }

  /** The simulator was opened, either fresh or by re-running a saved projection. */
  | { name: 'monte_carlo_opened'; props: { source: 'orb' | 'history'; tier: Tier } }

  /**
   * One input step of the simulator was completed. `input_method` says how the value was set,
   * which is the interesting part: a typed number and a dragged slider mean different confidence.
   */
  | {
      name: 'monte_carlo_step_completed';
      props: { step: MonteCarloStep; input_method: InputMethod | null };
    }

  /**
   * A simulation finished and its results were shown. The success percentage is never sent raw:
   * `outcome` uses the same thresholds the UI badge already applies.
   */
  | {
      name: 'monte_carlo_result_viewed';
      props: {
        outcome: SimulationOutcome;
        scenario: MarketScenario;
        strategies_held: CountBand;
        is_rerun: boolean;
        included_outside_accounts: boolean;
      };
    }

  /**
   * Something was done with a finished result. One event for the whole results screen, because
   * the individual controls are variations of the same intent: trying to move the outcome.
   */
  | {
      name: 'monte_carlo_result_acted';
      props: {
        action: 'adjusted_contribution' | 'adjusted_retirement' | 'changed_scenario' | 'shared' | 'opened_assumptions';
        direction: 'increase' | 'decrease' | null;
        scenario: MarketScenario | null;
      };
    }

  /**
   * A paywalled feature was hit. This is the product question "who wants what they cannot have",
   * not a billing event: no price, no period, and no purchase ever appears here.
   */
  | {
      name: 'paywall_nudge_shown';
      props: { feature: GatedFeature; tier: Tier; action: 'shown' | 'upgrade_tapped' | 'dismissed' };
    }

  /**
   * An error was shown to the user. The category is a slug from a fixed list; the message is not
   * sent, in any form. `http_status` is included where one exists because a status code is bounded
   * and far more useful than any string.
   */
  | {
      name: 'error_shown';
      props: {
        category: ErrorCategory;
        surface: Surface;
        display: ErrorDisplay;
        http_status: number | null;
        retry_available: boolean;
      };
    };

/** Event names, derived from the union so the two can never drift apart. */
export type AnalyticsEventName = AnalyticsEvent['name'];

/**
 * The same names at runtime, for the documentation check and for tests. The assertion below fails
 * to compile if this list and the union above ever disagree, in either direction.
 */
export const CLIENT_EVENT_NAMES = [
  'app_first_opened',
  'app_opened',
  'onboarding_step_viewed',
  'onboarding_step_completed',
  'plan_strategy_selected',
  'strategy_viewed',
  'freedom_plan_changed',
  'goal_changed',
  'setting_changed',
  'monte_carlo_opened',
  'monte_carlo_step_completed',
  'monte_carlo_result_viewed',
  'monte_carlo_result_acted',
  'paywall_nudge_shown',
  'error_shown',
] as const;

type DeclaredEventName = (typeof CLIENT_EVENT_NAMES)[number];
type MissingFromList = Exclude<AnalyticsEventName, DeclaredEventName>;
type ExtraInList = Exclude<DeclaredEventName, AnalyticsEventName>;

/** Compile-time proof that CLIENT_EVENT_NAMES covers the union exactly. */
export const EVENT_NAMES_ARE_COMPLETE: [MissingFromList, ExtraInList] extends [never, never]
  ? true
  : never = true;

// ---------------------------------------------------------------------------------------------
// Reserved for the server.
// ---------------------------------------------------------------------------------------------

/**
 * The events the BACKEND emits, listed here so the client cannot define a second, conflicting
 * version of the same fact, and so a reader of this file can see where the rest of the funnel
 * lives. The client must never send one of these: they are facts about money moving and about
 * compliance, which the server knows and the device can only guess at.
 *
 * Each is written to the `analytics_outbox` table inside the same database transaction as the
 * state change it describes, and published from there. The table, not PostHog, is the source of
 * truth; see FREDdocs/analytics-events.md.
 *
 * Email events (from the Resend webhook) are a later checkpoint and are deliberately absent until
 * that work exists, rather than being promised here.
 */
export const SERVER_EVENT_NAMES = [
  'freedom_plan_created',
  'passkey_created',
  'kyc_submitted',
  'plaid_linked',
  'investment_schedule_created',
  'trial_started',
  'kyc_decided',
  'first_ach_transfer_completed',
  'ach_transfer_completed',
  'subscription_billed',
  'subscription_cancelled',
] as const;

export type ServerEventName = (typeof SERVER_EVENT_NAMES)[number];

/**
 * Person properties. Safe, low-cardinality facts about a user, set at identify time.
 *
 * Never on this list: name, email, phone, address, date of birth, SSN or any part of it, account
 * numbers, bank name, balance, portfolio value, contribution amount, income.
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
