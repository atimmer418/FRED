# Client-side analytics events

Every event the app sends **from the device**, with the properties it is allowed to carry. The
typed source of truth is `frontend/src/app/services/analytics/analytics-events.ts`; this document
explains each one and why it exists. `npm run test:events-documented` fails the build if an event,
one of its properties, or a value from a closed vocabulary is defined there and missing here, or
documented here and gone from there.

## The rules, in one place

- **No money amounts.** Dollars are banded through `analytics-bands.ts` before they are sent.
- **No free text.** Not a goal name, not a toast message, not a server error string. Where a
  message would be informative, a slug from a fixed vocabulary is sent instead.
- **No identifiers.** Not a goal id, session id, account number, email or name. The only identifier
  in the whole system is `public_id`, the opaque UUID used as the distinct id.
- **Scalars only.** Objects and arrays are rejected by the type system, because nested data is
  where personal data hides.
- **Past tense, `object_action`, snake_case.** Renaming an event breaks every insight built on it.

## What is deliberately not here

Anything that represents **money actually moving** (deposits, orders, transfers, withdrawals) or a
**compliance state** (KYC decisions, account status) is emitted by the server, not the device. The
client can only report what it tried to do; the server reports what actually happened, which is the
thing worth counting. Those names are reserved at the bottom of `analytics-events.ts`.

Subscriptions and billing are a separate workstream and appear in neither taxonomy.

## Screens

Not an event in the taxonomy: a screen view is sent through `AnalyticsService.screen()` and arrives
as PostHog's `$pageview` with a `$screen_name`.

Every routed page reports one, through `ScreenTrackingService`, which subscribes to the router
once rather than adding a call to each page: a per-page call is the line someone forgets on the
next page they add, and the silence looks exactly like "nobody visited". The name is built from
the route **config**, so a parameterised route reports `/beneficiaries/edit/:id` and never the id.

Tabs are the exception and belong to the pager. Switching tabs does not navigate (the pager
rewrites the URL with `replaceState`), so the router would miss real tab changes and report
`/tabs/:tab` for the one navigation it does see. `TabActivationService` sends those.

## Launch

| Event | Properties | When |
|---|---|---|
| `app_first_opened` | `platform`, `app_environment` | The first launch after an install. Install detection is a stored flag, so clearing site data or the close-account flow makes the next launch look like a new install. |
| `app_opened` | `platform`, `app_environment` | Every launch after the first. |

`platform` is `ios` or `web`. `app_environment` is the build: `local`, `dev`, `test`, `ci`,
`prod`. Only `dev` and `prod` builds send anything at all, so the other three should never appear
in the data; if one does, a gate has been bypassed.

## Onboarding

The funnel is eight steps. The names mirror `AuthService.completeStep(...)`, which is what the
`user_progress` columns are derived from, **not** the routes: they differ on one step, where
`completeStep('linkPlaid')` lives at the route `/link-bank`.

`get_started`, `survey_initial`, `fi_plan_results`, `auth_finalize`, `kyc_verification`,
`link_plaid`, `investment_schedule`, `investment_confirmation`.

| Event | Properties | When |
|---|---|---|
| `onboarding_step_viewed` | `step`, `sub_step`, `resumed` | A step was shown. A returning user is routed to their first incomplete step on every launch, so this fires repeatedly for the same step across sessions. `resumed` separates a return from a fresh arrival. `sub_step` is the internal page where a step has one (KYC is a two-part form), else null. |
| `onboarding_step_completed` | `step`, `sub_step` | The user finished a step. Fired at the user's action, not in the save callback: the progress write is fire-and-forget and the app navigates on both success and failure, so waiting for it would undercount. |
| `plan_strategy_selected` | `strategy` (`yield` / `guardrails` / `sbloc` / `annuity`) | The drawdown strategy chosen on the plan screen. |

The first three steps happen before an account exists, so they land on an anonymous distinct id and
are stitched to the person when `identify()` runs at `auth_finalize`. Two steps hand control to
something outside the app and can be abandoned invisibly: the OS passkey sheet and the Plaid sheet.
The viewed/completed pair is the only way to see those drops.

## Freedom plan

| Event | Properties | When |
|---|---|---|
| `freedom_plan_changed` | `surface`, `income_changed`, `invest_changed`, `preset_changed`, `monthly_income_band`, `monthly_invest_band`, `return_preset`, `years_to_freedom` | The plan was saved. |

Fired at the save point, never per keystroke: the sheet's inputs fire on every character, and the
onboarding sliders write to the server on every tick. The two amounts are different things, so they
are named separately: income is the monthly target to live on, investing is the monthly
contribution goal. The return preset is one of exactly three values (`conservative` 8%, `moderate`
10%, `optimistic` 12%), so it is sent as itself.

There is no withdrawal-rate event: free-form customization was removed and the rate is pinned at
4%. A rate-only change does not write to the server, so the event has to fire where the rates are
applied, not only where the profile is saved.

The three `*_changed` flags are what make a save readable. A rates-only save and a save where the
user touched nothing carry identical bands, so `preset_changed` is the only thing that tells them
apart.

The two surfaces establish the flags differently, and the difference is worth knowing before
comparing them. The freedom sheet reports a **net change**: each flag compares the value at save
against the value the sheet opened with, so moving a control and putting it back reports false.
Onboarding reports **the control having been operated**: the saved plan arrives asynchronously and
rewrites both amounts after the screen has opened, so a snapshot comparison there would report
edits the user never made; the flags are set in the slider and input handlers instead, and stay
set. `preset_changed` is always false on the onboarding surface, which has no rate control, and
the sheet's snap of a legacy free-form rate to the nearest chip does not count as a change.

## Strategy explainers

| Event | Properties | When |
|---|---|---|
| `strategy_viewed` | `strategy`, `surface`, `max_slide_reached`, `slide_count`, `exit` | Fired once when a deck closes. |

One event per deck session rather than one per slide: `max_slide_reached` carries the drop-off and
`exit` carries the intent. The exits mirror the deck's own dismiss roles: `cancel`, `done`,
`stress_test` (jumped to the simulator), `drawdown` (opened the Pro deck), `upgrade`.

The four education decks are ungated. `optimal_drawdown` is Pro only.

## Goals

| Event | Properties | When |
|---|---|---|
| `goal_changed` | `action`, `icon`, `target_band`, `years_to_target`, `progress`, `goal_count` | A goal was created, edited, completed, reopened or removed. |

Actions: `created`, `updated`, `completed`, `reopened`, `removed`. Icons: `home`,
`health_and_safety`, `flag`, `balance`, `savings`, `school`, `directions_car`.

A goal's **name is free text the user typed** and its **id is a UUID**: neither ever leaves the
device. The icon is the safe descriptor. `completed` and `reopened` are the two sides of one toggle
and are separate values so they can be told apart. Goals are stored in localStorage only, so the
single save point is where the sheet is dismissed.

The sheet is its own `surface` (`goals`), not the page that presented it. It is reachable from the
goals list and from a goal row, and "the goal form was rejected" is a different fact from "the
profile page failed to save".

## Settings

| Event | Properties | When |
|---|---|---|
| `setting_changed` | `setting`, `enabled`, `value`, `outcome` | A setting the user can actually change was changed. |

Settings in the vocabulary: `app_lock`, `step_up_auth`, `analytics_consent`, `drip`,
`rebalance_schedule`, `marketing_emails`. `value` carries the chosen option where a setting is not
a simple switch (the rebalance schedule) and is null otherwise.

Outcomes: `applied`, `cancelled`, `failed`.

`outcome` exists because not every tap saves: turning on step-up authentication opens a PIN prompt
the user can cancel, and a failed save reverts the switch. Theme, notification preferences, risk
tolerance and auto-rebalance are **not** here: they are declared in `SettingsService` but no UI can
change them.

One ordering trap worth repeating: an **opt-out** event has to be dispatched before consent is
applied, or the gate suppresses it. An opt-in event can only be sent after.

## Monte Carlo

| Event | Properties | When |
|---|---|---|
| `monte_carlo_opened` | `source` (`orb` / `history`), `tier` | The simulator was opened. A history re-run skips the input steps, so run durations are not comparable across the two. |
| `monte_carlo_step_completed` | `step`, `input_method` | An input step was completed. `input_method` distinguishes a dragged slider from a typed number from the live-portfolio chip. |
| `monte_carlo_result_viewed` | `outcome`, `scenario`, `strategies_held`, `is_rerun`, `included_outside_accounts` | Results were shown. |
| `monte_carlo_result_acted` | `action`, `direction`, `scenario` | Something was done with a finished result: an adjuster, a scenario flip, a share, or opening the assumptions sheet. |

Input steps: `portfolio`, `outside`, `spend`, `duration`, where `outside` only exists when the
outside-accounts toggle is on. Input methods: `slider`, `typed`, `fetched` (the live-portfolio
chip). Scenarios: `conservative`, `expected`, `aggressive`.

Nothing on this surface moves money: every number is a hypothetical typed into a simulator. The
success percentage is never sent raw; `outcome` uses the same thresholds the UI badge already
applies (`strong` ≥ 90, `moderate` ≥ 75, else `risk`). Scenario keys are the internal ones, not the
labels, which differ: `expected` renders as "Moderate (10%)".

The adjusters re-run the simulation on every tap, so those events must be collapsed rather than
fired per frame. Sharing produces a PNG handed to the native share sheet; whether the user
completed the share is not observable and is not guessed at.

## Paywalls

| Event | Properties | When |
|---|---|---|
| `paywall_nudge_shown` | `feature`, `tier`, `action` (`shown` / `upgrade_tapped` / `dismissed`) | A gated feature was hit. |

Gated features: `goals`, `rebalancing`, `monte_carlo`, `market_scenarios`, `optimal_drawdown`,
`tax_loss_harvesting`. Tiers, also carried by the Monte Carlo events: `core`, `plus`, `pro`, or
null when the user has none. The legacy stored value `piggy` is mapped to `core` before sending.

This is the product question "who wants what they cannot have". It carries no price, no billing
period and no purchase: those belong to the billing workstream.

## Errors

| Event | Properties | When |
|---|---|---|
| `error_shown` | `category`, `surface`, `display`, `http_status`, `retry_available` | An error was shown to the user. |

Categories: `network`, `auth_required`, `passkey`, `step_up_pin`, `bank_link`, `validation`,
`save_failed`, `load_failed`, `document`, `chat`, `unavailable`, `unknown`.

Surfaces, shared with every other event that carries one: `onboarding`, `freedom_sheet`,
`my_profile`, `goals`, `retirement_planning`, `monte_carlo`, `strategy_deck`, `settings`,
`portfolio`, `chat`, `other`.

Displays: `toast`, `inline_field`, `inline_banner`, `alert`.

**The message is never sent, in any form.** App copy interpolates names, bank names, amounts and
vendor ids, and server messages are unbounded. A few real examples of why: one toast carries a
beneficiary's full name, another carries the linked bank's name, another a transfer reference id,
and a dozen pass `err.error.message` straight through. The category plus an HTTP status says more
and risks nothing.

`display` matters because the surfaces behave differently: a toast is missable, an inline banner
persists, and some surfaces offer a retry. There is no global error handler or HTTP interceptor in
the app, so this event has to be fired at each call site, and it is, at every visible failure.

## Server-side events

Everything above is sent **from the device**. The events below are sent **by the backend**, and
they are the ones the funnel and the retention maths actually depend on: money moving, and
compliance decisions. A phone cannot be trusted to report those. It can be offline, be closed
mid-flow, or simply be wrong about what the broker decided.

### How they get out: a transactional outbox

Each event is written to `analytics_outbox` **inside the same database transaction as the state
change it describes**. If the KYC submission commits, its event commits with it; if the submission
rolls back, there is no event claiming something that never happened. A scheduled publisher drains
the table afterwards.

The table, not PostHog, is the source of truth. Rows are never deleted, and Checkpoint G's SQL
metrics read this same table, so the money funnel can be recomputed from our own database whatever
happens to the vendor's copy.

Every row carries its own `event_id` uuid, assigned when it is written and sent to PostHog as the
event's uuid. A resend after a timeout that actually succeeded is de-duplicated there rather than
counted twice here. A failure backs off (2, 4, 8 … minutes, capped at an hour) and a row that
cannot be sent as it stands is parked for a human rather than retried forever.

**Consent.** The row is always written: it is our own ledger. Immediately before publishing, the
publisher reads the live `users.analytics_opt_out`. An opted-out user's row is kept, marked
`SUPPRESSED`, and never sent. One case cannot be read live: a rejected KYC hard-deletes the user in
the same transaction that records the decision, so the row also carries the consent that applied
when it was written, used only when the user is gone.

### The events

| Event | Properties | When |
|---|---|---|
| `freedom_plan_created` | `strategy`, `monthly_income_band`, `monthly_invest_band`, `years_to_freedom` | The plan and its strategy were first persisted. |
| `passkey_created` | `is_first_passkey` | A passkey was registered. |
| `kyc_submitted` | `attempt` | A KYC submission was persisted and sent to the broker. |
| `plaid_linked` | `is_first_link` | A bank was linked through Plaid. |
| `investment_schedule_created` | `frequency`, `amount_band`, `days_since_signup_band` | A recurring schedule was created. |
| `trial_started` | `tier`, `trial_length_days` | A trial began. **Not emitted yet: billing owns it.** |
| `kyc_decided` | `outcome`, `days_to_decision_band` | The broker decided on a submission. |
| `first_ach_transfer_completed` | `amount_band`, `days_since_schedule_band` | The user's first ACH transfer **settled**. |
| `ach_transfer_completed` | `transfer_number_band` | Every transfer after the first, once settled. |
| `subscription_billed` | `tier`, `is_first_billing` | A fee was collected. **Not emitted yet: billing owns it.** |
| `subscription_cancelled` | `tier`, `days_since_trial_start_band` | A subscription was cancelled. **Not emitted yet: billing owns it.** |

Outcomes for `kyc_decided`: `approved`, `rejected`, `needs_more_info`. Attempts for
`kyc_submitted`: `1`, `2`, `3_plus`, because the question is "did this take more than one go" and a
long tail of exact counts identifies people. Day spans are banded: `same_day`, `1_3`, `4_7`,
`8_14`, `15_30`, `31_90`, `over_90`, `unknown`.

`strategy` is the drawdown strategy from the onboarding plan screen, narrowed to the same four
values the client uses; the column it comes from is unvalidated client text, so anything else is
sent as null rather than as a new dimension nobody can interpret.

`monthly_income_band` deserves a note: the server stores the retirement income **annually** (the
client sends monthly × 12), while the client's own `freedom_plan_changed` bands the **monthly**
figure. The server divides by twelve before banding, so the two sides are comparable.

### Person properties

Every server event carries a `$set` with `tier`, `kyc_status`, `has_linked_bank`, `has_schedule`
and `has_funded`. Booleans and enums only. `tier` is narrowed to `core`, `plus`, `pro` or null (the
legacy stored value `piggy` maps to `core`). `kyc_status` collapses the broker's richer statuses to
`not_started`, `pending`, `approved`, `rejected`, `action_required` or `other`.

### What is deliberately missing, and why

- **`trial_started`, `subscription_billed`, `subscription_cancelled` are defined but emitted by
  nothing.** Billing does not exist in this repository: the advisory fee is collected outside
  Apple, by ACH from the Plaid-linked bank, and a separate billing session owns it. The event
  names, their exact properties and an emitter interface exist, with tests against a fake, so that
  work has a contract to call. A test fails if anything other than the emitter references them.
- **`kyc_decided` fires on `ACTIVE`, not on the broker's intermediate `APPROVED`.** Accounts pass
  through `APPROVED` on the way to `ACTIVE`, which is the state where the account is actually
  usable, and only `ACTIVE`, `REJECTED` and `ACTION_REQUIRED` reach a handler today. An account
  that stalled at `APPROVED` would be a late event, not a lost one.
- **A repeated `needs_more_info` is possible.** The status poller re-selects `ACTION_REQUIRED`
  users every five minutes; the only thing suppressing a second event is the equality check that
  skips a status which has not changed. A document upload that leads to a second review can
  legitimately produce a second event.
- **ACH ordinals start from the outbox.** A user whose transfers settled before this table existed
  will have their next settled transfer reported as their first. `transfer_number_band` counts
  reported transfers, not historical ones.
- **Buying-power funding emits nothing.** It completes an investment with cash already at the
  broker and no bank transfer at all, so counting it would inflate the ACH funnel.

## Bands

All money goes through `toAmountBand()`. One vocabulary across the whole app, because two screens
using different buckets cannot be analysed together.

`under_50`, `50_99`, `100_249`, `250_499`, `500_999`, `1k_2499`, `2500_4999`, `5k_9999`,
`10k_49k`, `50k_249k`, `250k_999k`, `1m_plus`.

Counts (`toCountBand`): `0`, `1`, `2_3`, `4_9`, `10_plus`. Years (`toYearsBand`): `under_1`,
`1_2`, `3_5`, `6_10`, `11_20`, `over_20`, `never`. Percentages (`toPercentBand`): `0_9`, `10_24`,
`25_49`, `50_74`, `75_99`, `100`.

## Known gaps

- **The runtime guard is partial.** `AnalyticsService.sanitize()` scrubs URL properties today; the
  full key and value deny-list is still to come. Until then the type union is the main guard, which
  is why no event here has a free-text property. The same sanitizer runs a second time inside the
  SDK, through posthog-js's `before_send` hook, which also catches the properties the SDK adds
  itself. If that scrub ever throws, the event is dropped rather than sent unscrubbed.
- **`validation` collapses distinct rejections.** Nine different field errors on one form all
  report as one category, because the only thing that separates them is the message, which is
  never sent. A bounded `field` slug would fix it if the funnel ever needs that detail.
- **One nudge, two affordances.** `goals` covers both the frosted veil and the add-goal button,
  and they share a modal, so a `shown` event cannot say which one the user hit.
- **No event describes a plan saved from the my-profile page itself.** `freedom_plan_changed` is
  restricted to `onboarding` and `freedom_sheet`. Harmless today, since the page's tiles live only
  in the sheet.
