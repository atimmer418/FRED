# Analytics layer: completion review

A mapping from what was asked to what exists, so the work can be verified without reading every
diff. It covers the original analytics brief, the 13 decisions and 12 sections that answered the
Phase 0 discovery, the scope change that removed subscriptions, and every checkpoint instruction
from B through H including the follow-ups.

**Branch:** `feature/analytics`, merged to `develop` through `bdb2f42`.
**Written:** 2026-10-23. **Decisions recorded and Checkpoint J built:** 2026-10-24.
**Author:** this session, reviewed against the code as it stands today.

## How to read the status column

| Status | Means |
|---|---|
| **done** | built as asked, and the evidence column says how that was checked |
| **deviated** | built differently from the instruction. Every one has a line in [Deviations](#deviations-and-deferrals) |
| **deferred** | not built. Every one has a line in [Deviations](#deviations-and-deferrals) |
| **withdrawn** | removed from scope by a later instruction, so there is nothing to build |

Several deferrals were given a home on 2026-10-24, and Checkpoint J then closed seven of them.
The rows that are still open say **follow-on plan**, which names when the work happens without
pretending it has happened.

**Where the 236 rows stand after Checkpoint J:** 172 done, 26 deviated, 32 deferred, 2 withdrawn,
and 4 that are partly one and partly another and say so in place. Checkpoint J moved 7.1, 7.2,
7.3, 7.4, 7.6, D12 and D8.2 from deferred or deviated to done, and corrected 1.5 and B.7, which
this review had wrongly called unenforced.

"Done" never means "seen working in production". Nothing here has run against a real beta user
yet, which is why [what to check on the first real beta account](#5-what-to-check-on-the-first-real-beta-account)
is part of this document.

---

## The bottom line: can we answer the four questions?

The brief existed to answer four business questions. Two can be answered today, two cannot.

| # | Question | Answerable? | Where |
|---|---|---|---|
| 1 | Where do new users drop out between install and their second automated investment? | **Yes** | `v_activation_funnel`, `v_time_to_funded`, and the Onboarding funnel and Activation dashboards |
| 2 | Are users staying invested? | **Yes**, and this is the strongest part of the work | `v_contribution_retention` (the authority), `v_schedule_churn`, the Contribution retention dashboard |
| 3 | What is our LTV/CAC? | **No** | needs revenue, which belongs to the billing session, and CAC, which has no home in this repo. No view, no definition |
| 4 | Which acquisition channel produced users who funded? | **No** | needs the waitlist UTM join (Section 8), which was never built or specced. Nothing in the system knows where a user came from |

Questions 3 and 4 are not half-built. They are absent, and both were scheduled into a checkpoint
that was never run. That is the single biggest gap in this work, and it is now the first item of
the [follow-on plan](#the-follow-on-plan).

---

## Decisions taken on this review (2026-10-24)

Andy read the review and closed four of its open questions. They are recorded here because the
rows below still read "deferred": the decision is made, the code is not written yet.

**1. A rejected KYC applicant keeps their user row.** No hard delete. The row stays with status
`REJECTED`, which is what decision 8 asked for. This also fixes the funnel distortion described
under [known gaps](#known-gaps-and-risks): once rejected users are present in `users`, the
activation funnel's `signed_up` stops flattering itself.

**2. The SSN column is left exactly as it is, for now.** Keeping the row means keeping whatever
identity data that row holds, and how long a rejected applicant's Social Security number may be
retained is a question for counsel, not for this session. Andy is taking it to counsel. Until an
answer comes back, nothing in this work reads, clears, moves or re-encrypts that column, and no
migration touches it. **This is the one open legal dependency created by decision 1 above**, and
it belongs in the counsel-facing document when 7.7 is closed.

**3. The Phase 7 guardrails become Checkpoint J**, with `PrivacyInfo.xcprivacy` in the same
checkpoint. **Built the same day**; the scope table below records what each item closed, and the
rows above now read "done (J*)".

**4. Alerts, Sentry, the transfer tables, acquisition attribution and LTV/CAC are deferred to a
follow-on plan**, not abandoned. One line of scope each, below.

### Checkpoint J: the agreed scope

| # | What | Closes |
|---|---|---|
| J1 | A forbidden key-pattern deny-list and a value-shape guard (email, phone, 9-digit, 8-or-more-digit, JWT) in the client's `before_send` path, warning loudly in dev | 7.1, 7.2 |
| J2 | The same two guards on the server, in `AnalyticsOutboxService.record()`, where the brief said the danger is highest | 7.4 |
| J3 | The decision-12 exemption: declared taxonomy keys are not key-pattern matched, so a legitimate property name is never stripped | D12 |
| J4 | A test on each side that sends a forbidden payload through the real dispatch path and proves it never leaves: nothing in the network call, nothing in the outbox row | 7.3 |
| J5 | `PrivacyInfo.xcprivacy` in the app target, declaring analytics with a **non-linked** identifier, plus the matching nutrition-label answers in FREDdocs so the two agree | 7.6, S6.3 |
| J6 | Delete the unset fields from the `PersonProperties` interface so the type stops promising what nothing sets | 3.3 |
| J7 | ~~`npm run test:lint-rule` added to `verify.yml`~~ **Nothing to do: it was already there, and this review was wrong to say otherwise.** It has run in CI since `710667b`, 2026-10-15 | 1.5, B.7 |
| J8 | Keep the rejected applicant's row, status `REJECTED`, in `AccountStatusService.handleRejected`, and leave the SSN column untouched | D8.2 |

Two things were added while building J, both because the agreed scope turned out to imply them:

- **J8 also holds the schedule.** While a rejection deleted the user it deleted their schedule
  too, so `schedule_cancelled` described something that had really gone. Keeping the row would
  have left the event saying cancelled while the table said otherwise, and the only thing stopping
  that schedule from investing would have been an invariant two classes away (`isReadyForInvestment`
  needs an ACH relationship, and a rejected application never got one). So the rejection now sets a
  blocking `MANUAL_REVIEW` hold: a hold rather than a status, because status and holds are
  orthogonal, and a person should decide what happens to a rejected applicant's plan.
- **The server's property list is now pinned to the document.** `ServerAnalyticsEvent.PROPERTY_KEYS`
  is the server's exemption list, and it was hand-written with nothing checking it. It is now
  pinned to the server event table in `analytics-events.md` by `ServerAnalyticsEventParityTest`,
  which also closes the audit gap this review found: the document's server event **names** were
  checked by CI and its **property lists** were checked by nothing.

Note on J5: the manifest declares a **non-linked** identifier, which differs from what this review
originally proposed (product interaction, purchase history and user id, *linked* to the user).
Andy's call. It is defensible because the only identifier that leaves is `public_id`, an opaque
UUID that exists for no purpose but analytics, there is no advertising SDK and no tracking, and
purchase history cannot be collected because billing does not exist. **It stops being true the day
billing lands or any revenue property is attached to a person**, which is a gate on the billing
session, not on this one.

### The follow-on plan

Deferred by decision on 2026-10-24, with the scope each one needs. None is started.

| Item | One-line scope |
|---|---|
| **Acquisition attribution** (question 4) | A spec for the landing page's waitlist lookup, then FRED's half: `acquisition_*` columns on `users`, a short-timeout lookup at registration with a retry job, and the properties onto the person record |
| **LTV/CAC** (question 3) | A recorded cost input from Andy and revenue from the billing session, then one definition written against both and a view or a dashboard tile on top |
| **The transfer tables** (Section 7) | A `transfer` table with status history, a `contribution_attempt` table and a withdrawal record, then `deposit_failed`, `deposit_returned`, `withdrawal_requested` and `v_contribution_continuity` on top of them |
| **Alerts** (9.1e) | The four named conditions added to `sync-dashboards.mjs` so they are code like the dashboards, not clicks that drift |
| **Sentry** (9.3) | A project, the SDK keyed to the same `public_id`, and the disclosure row written into `what-we-send.md` **before** the key is configured |

## Evidence: what was run for this review

Every check below was run against the tree at `f794d62` on 2026-10-23, and re-run after
Checkpoint J on 2026-10-24. The numbers in the table are the Checkpoint J run.

| Check | Command | Result |
|---|---|---|
| Backend suite | `./gradlew cleanTest test` with `SPRING_DATASOURCE_URL` pointed at a scratch MySQL schema | **754 tests, 0 failures, 0 skipped.** The 97 database-gated tests really ran rather than skipping; counted from the JUnit XML reports. Checkpoint I's run was 720 |
| Frontend suite | `ng test --watch=false --browsers=ChromeHeadlessNoSandbox` | **910 specs, 896 pass, 14 fail.** The same 14 FRED-226 failures as Checkpoint I, named at the end of this document; not one is an analytics spec. Checkpoint J added 81 passing specs |
| Taxonomy versus its document | `npm run test:events-documented` | **PASS:** 15 client events and their properties, 14 server events, 18 vocabularies, and the privacy guard's exemption list. Both new checks were negative-tested: a bogus exempt key and a missing one each fail the build |
| Server event properties versus the document | by hand, every emit site read and diffed | All 14 match. This is the part no check covers |
| Person properties versus the document | read `AnalyticsOutboxService.personProperties()` | All five match |
| Dashboard sync | `sync-dashboards.mjs --project=667874` | Idempotent: 0 created, 4 updated, 15 tiles live |
| Migration chain | rehearsed on a database at develop's V6 carrying Autopilot's shape | V7 and V8 applied, five row shapes backfilled correctly, re-ran clean, app booted with `ddl-auto=validate` passing |
| Cross-language guard parity | `AnalyticsPrivacyGuardParityTest` | **PASS**, and negative-tested: changing one Java pattern by a single character fails it |
| Type check and lint | `npx tsc --noEmit`; `npx ng lint`; `npm run test:lint-rule` | All clean. The lint-rule script was already in CI, contrary to what this review first said |
| The privacy manifest reaches the bundle | `xcodebuild -workspace App.xcworkspace -scheme App -sdk iphonesimulator build` | **BUILD SUCCEEDED**, and the log shows `PrivacyInfo.xcprivacy` copied to `App.app/`. `plutil -lint` passes |

Everything marked "deferred" below was confirmed absent by grep or by listing the directory, not
assumed from a file name. Where a claim rests on inspection rather than a test, the row says so.

---

## Part 1: the original brief

### Phase 0: discovery

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 0.1 | Frontend audit: versions, routing, auth state, services convention, Capacitor | done | Checkpoint A report (chat only) | Reported 2026-09-16 and acted on: Angular 19.2.22, Ionic 8.5.7, Capacitor 8.4.1, and the `services/` convention that decision 4 then adopted |
| 0.2 | Backend audit: Spring version, packages, Plaid, Alpaca, user entity and key type, existing event table | done | Checkpoint A report (chat only) | Reported: Spring Boot 3.5.0, `Long` user id, no event or audit table, which is why `analytics_outbox` was created new in V5 |
| 0.3 | Build-state list for the ten named flows | done | Checkpoint A report (chat only) | Each flow marked done / in progress / not started, and the "not started" ones were the ones never instrumented (rule 6) |
| 0.4 | Subscriptions: StoreKit, Stripe, or not built? | done, then withdrawn | n/a | Answered "not built"; the answer drove the scope change that removed Phase 6 entirely |
| 0.5 | Email: audit Resend client, webhook, tables, idempotency, suppression | done | `backend/.../service/EmailSender.java` | Verified by reading: `EmailSender` is a port whose only implementation logs. No Resend client, no webhook, no `email_message` or `email_event` table, no suppression |
| 0.6 | Stop and report before writing code | done | n/a | No code was written until Checkpoint A was approved |

### Phase 1: the analytics abstraction layer

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 1.1 | Event taxonomy as a string-literal union with a typed payload per event | done, path deviated | `frontend/src/app/services/analytics/analytics-events.ts` | 15 client events as a discriminated union; `EVENT_NAMES_ARE_COMPLETE` is a compile-time proof that the runtime name list matches the union in both directions. Path is `services/analytics/`, not `core/analytics/`, per decision 4 |
| 1.2 | `AnalyticsService` exposing track, identify, screen, reset, setOptOut | done | `analytics.service.ts:80,96,117,136,156` | All five exist with the specified signatures, plus `applyServerConsent` for the server-held opt-out |
| 1.3 | Rules in the service, not by convention: try/catch, no-op when opted out, no-op in tests, scalars only | done | `analytics.service.ts`; `analytics.service.spec.ts` | Four gates, each with its own spec: "never lets a vendor failure reach the caller", "sends nothing while opted out", "is silent under Karma", and `ScalarValue` rejecting objects at the type level |
| 1.4 | Vendor adapter behind an interface, service never imports `posthog-js` | done | `analytics-adapter.ts`, `posthog.adapter.ts`, `lazy-posthog.adapter.ts` | The service depends on `ANALYTICS_ADAPTER`; `posthog.adapter.ts` is the only file importing the SDK |
| 1.5 | ESLint `no-restricted-imports` failing the build on any other `posthog-js` import | done | `frontend/.eslintrc.json:47-84`; `.github/workflows/verify.yml:109-110` | The rule, its single-file override, `npx ng lint`, **and** `npm run test:lint-rule` all run in CI. **This review previously said the last of those was missing. That was wrong**: it has been in `verify.yml` since `710667b` on 2026-10-15 |

### Phase 2: PostHog client installation

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 2.1 | `posthog.init` with the exact nine options | done, one deviated | `posthog.adapter.ts:43-53` | `person_profiles: 'identified_only'`, `capture_pageview: false`, `autocapture: false`, `disable_session_recording: true`, `capture_performance: false`, `persistence: 'localStorage'` all verbatim. `sanitize_properties` is `before_send` instead, because PostHog deprecated the former |
| 2.2 | Reverse-proxy PostHog through our own domain | done | `infra/posthog-proxy/worker.js`; `FREDdocs/posthog-proxy-deploy.md` | Deployed by Andy as `fred-metrics-proxy` on `m.fredvested.com`; the hostname carries none of the ad-block trigger words (decision 10) |
| 2.3 | Screen views from the router, route params stripped | done, extended | `services/analytics/screen-tracking.service.ts`; `services/tab-activation.service.ts:31` | `NavigationEnd` for pushed routes plus `TabActivationService` for tab changes, which never fire `NavigationEnd`. `scrubUrl` replaces id-shaped segments with `:id` and drops query strings; specs cover both |
| 2.4 | A different project key per environment | deviated | `environment.ts`, `environment.dev.ts` | One dev project, because PostHog's free plan allows one (decision 1). `local`, `test` and `ci` send nothing at all; `prod` is a clean no-op with no key |

### Phase 3: identity

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 3.1 | Leave the anonymous id alone before sign-in | done | `analytics.service.ts` | Nothing calls `identify` before authentication; the first three onboarding steps land on the SDK's own anonymous id and are stitched at identify time |
| 3.2 | `identify()` with the opaque server UUID, never email, phone or the numeric id | done | `auth.service.ts:219,367`; `V2__user_public_id.sql`; `JwtUtils.java:95` | The service **refuses** a non-UUID (spec: "refuses to identify with anything that is not a UUID"), so a mistake sends nothing rather than leaking. The `pid` JWT claim lets a cold start identify without a round trip |
| 3.3 | Ten person properties including `signup_month` and the acquisition fields | deviated, narrowed by J6 | `AnalyticsOutboxService.personProperties()` (lines 118-127); `PersonProperties` in the taxonomy | Five are set, server-side, refreshed on every event: `tier`, `kyc_status`, `has_linked_bank`, `has_schedule`, `has_funded`. **J6 cut the interface down to exactly those five**, so the type no longer promises `signup_month`, the four `acquisition_*` fields, `waitlist_member`, `founder_access`, or the two duplicates under second names. The acquisition half is on the follow-on plan |
| 3.4 | Never set name, email, phone, address, DOB, SSN, account numbers, bank name, balance, portfolio value, contribution amount or income | done | `what-we-send.md`; `personProperties()` | Verified by reading every `$set` key and every event property map: not one of the forbidden fields appears. Amounts exist only as bands |
| 3.5 | Backfill acquisition properties from the waitlist by email hash, during registration | deferred | nowhere | No `acquisition_*` columns on `User`, no lookup client, no spec. Confirmed by grep |
| 3.6 | `reset()` on sign-out | done | `auth.service.ts:199`; `sell-withdraw.page.ts:566` | Both session-clearing paths call it, and the close-account path calls it **before** `localStorage.clear()`, which is the ordering the reset needs to survive |

### Phase 4: the event taxonomy

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 4.0 | Naming: `object_action`, snake_case, past tense, documented in the file header | done | `analytics-events.ts:5-9` | Every one of the 29 names follows it; the header states the rule and why renaming later is forbidden |
| 4a.1 | `app_first_opened` with `install_source` | done, property deviated | `analytics.service.ts:195-205` | Fires once per install; carries `platform` and `app_environment`. No `install_source`: iOS gives the app no attribution source, so the property would always be null |
| 4a.2 | `onboarding_started` with `entry_point` | deviated | `onboarding_step_viewed` | Replaced by a per-step pair, so the funnel has eight steps rather than one start marker. `entry_point` dropped: the app has no deep links or push today |
| 4a.3 | `account_created` (server) | deviated | `freedom_plan_created`, `passkey_created` | Registration writes the plan and the passkey in one transaction and emits both; a third event for the same moment would be a duplicate step in every funnel |
| 4a.4 | `kyc_started`, `kyc_step_completed` with `step_name`, `step_index` | deviated | `onboarding_step_viewed` / `_completed` with `step: 'kyc_verification'`, `sub_step` | The two-part KYC form is a sub-step of one onboarding step, so it reuses the same pair rather than adding two names |
| 4a.5 | `kyc_submitted` (server) | done | `KycSubmissionService.java:113` | Carries `attempt` banded to `1`, `2`, `3_plus`. Written in the transaction that persists the submission |
| 4a.6 | `kyc_approved` with `hours_to_decision_band`; `kyc_rejected` with `reason_category` | deviated | `kyc_decided` with `outcome`, `days_to_decision_band` (`AccountStatusService.java:301`) | One event with a three-way outcome rather than two names, so approval rate is one query. Days not hours; **no rejection reason at all** (see deviations) |
| 4a.7 | `bank_link_started` | deviated | `onboarding_step_viewed` with `step: 'link_plaid'` | Same reasoning as 4a.4 |
| 4a.8 | `bank_link_completed` with `institution_category` | deviated, stricter | `plaid_linked` with `is_first_link` (`PlaidService.java:253`) | We send **nothing** about the bank, not even the coarse bucket that was asked for |
| 4a.9 | `bank_link_failed` with `failure_stage`, `reason_category` | deviated | `error_shown` with `category: 'bank_link'` | Folded into the one error event, client-side only. No server half |
| 4a.10 | `recurring_created` with `cadence`, `amount_band` | done, renamed | `investment_schedule_created` (`InvestmentScheduleService.java:213`) | Carries `frequency`, `amount_band`, `days_since_signup_band` |
| 4a.11 | `deposit_initiated` with `amount_band`, `sequence_number` | deviated | not emitted | Deliberate: only settlement is emitted, because an initiated transfer that is later returned would inflate every funded number |
| 4a.12 | `deposit_settled` with `amount_band`, `sequence_number`, `days_to_settle` | done, renamed and split | `first_ach_transfer_completed`, `ach_transfer_completed` (`InvestmentExecutionService.java:701-720`) | Split so the activation moment is its own event. First carries `amount_band` and `days_since_schedule_band`; later ones carry `transfer_number_band` only |
| 4a.13 | `deposit_failed` with `reason_category`, `sequence_number` | deferred | nowhere | No failure or return path is instrumented, because no table records one (Section 7) |
| 4a.14 | `order_filled` with `sequence_number` | deferred | nowhere | Flagged partial in the brief itself. Order placement has no idempotency and partial fills get stuck, so the event would faithfully record wrong data |
| 4a.15 | `institution_category` as a coarse bucket, never the bank name | done, stricter | n/a | Nothing about the institution is sent |
| 4a.16 | `amount_band`: the seven named bands | deviated | `analytics-bands.ts`; `AnalyticsBands.java` | Twelve bands, finer below $500 and coarser above $2,500. `AnalyticsBandsParityTest` fails if the two implementations disagree |
| 4a.17 | `sequence_number` 1, 2, ... capped at 10+ | deviated | `transfer_number_band` | Banded rather than exact, because an exact count on a small user base is close to a fingerprint |
| 4b.1 | `freedom_date_viewed` with `surface` | deviated | `onboarding_step_viewed`, screen views | A viewed freedom date is a screen view, and screen views already carry the surface |
| 4b.2 | `freedom_date_changed` with `direction`, `months_delta_band` | deviated | `freedom_plan_changed` | Carries banded income, banded investment, `years_to_freedom`, the chosen preset and three `*_changed` flags, which is more than direction and delta |
| 4b.3 | `mfu_shown`, `mfu_dismissed`, `mfu_acted` | deferred | nowhere | Never instrumented and never in any checkpoint instruction |
| 4b.4 | `education_opened`, `education_question_asked` with `topic` | deferred | nowhere | Same. Chat errors are visible as `error_shown` with `surface: 'chat'`, but engagement is not |
| 4b.5 | `referral_link_shared`, `referral_signup_attributed` | deferred | nowhere | Referral has no backend today |
| 4c.1 | `recurring_paused` with `days_active_before_pause_band`, `initiated_by` | done, renamed, properties deviated | `schedule_paused` (`InvestmentScheduleService.java:265,359`) | Carries `initiator` and `reason`. No day band: the schedules table has no pause history to measure from, which is the whole reason these events exist |
| 4c.2 | `recurring_resumed` with `days_paused_band` | done, renamed | `schedule_resumed` (`:417`, `AchRelationshipService.java:128`) | Carries `initiator` and `reason`; both actors emit it, which is why every churn column filters `initiator = 'user'` |
| 4c.3 | `recurring_canceled` with `days_active_before_cancel_band` | done, renamed | `schedule_cancelled` (`TradingController.java:423`, `AccountStatusService.java:331`) | Emitted only when a schedule really existed, so a rejected user who never set one up does not invent a cancellation |
| 4c.4 | `recurring_amount_changed` with `direction`, `new_amount_band` | deviated | not emitted | The create endpoint doubles as the edit endpoint; an edit deliberately emits nothing rather than inventing a `schedule_updated` the taxonomy does not have |
| 4c.5 | `bank_unlinked` with `initiated_by` | deferred | nowhere | Marked "not yet implemented" at decision 7 and never built. A bank change emits `schedule_paused` with `reason: 'bank_change'`, which is adjacent but not the same fact |
| 4c.6 | `withdrawal_requested` with `portion` | deferred | nowhere | Nothing records a withdrawal (Section 7). This is one of the two churn signals the brief called more important than app opens |
| 4c.7 | `account_closed` with `reason_category` | deviated | `schedule_cancelled` with `reason: 'account_closed'` | A closure is visible through the cancellation it causes and through the person-deletion row, not as its own event |
| 4d.x | The five Resend email events, the `email_opened` exclusion, and suppression carrying across | deferred | nowhere | Section 11, which was explicitly gated on a go-ahead that never came |

### Phase 5: server-side events from Spring Boot

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 5.1 | Add the PostHog Java SDK | deviated | `PostHogAnalyticsTransport.java` | A hand-written `/batch/` POST behind the `AnalyticsTransport` interface instead of the SDK |
| 5.2 | An `AnalyticsPublisher` with a single `capture(distinctId, eventName, props)` | done, shaped differently | `AnalyticsOutboxService.record(...)`, `AnalyticsPublisherService` | One write method and one interface, as asked; split into "record" and "drain" because the outbox needs the two to be separate |
| 5.3 | The same distinct id as the client | done | `AnalyticsPublisherService.toPayload()` | `distinct_id` is `public_id` and nothing else can be: a row with no `public_id` is PARKED rather than sent under a substitute |
| 5.4 | Emit from domain state transitions via `ApplicationEventPublisher` and an async listener | deviated | every emit site is inside the state-change transaction | An async listener cannot be atomic with the transaction, which is the property Checkpoint E was built around. `record()` refuses to write when no transaction is active |
| 5.5 | Outbox inside the transaction, with a deterministic event UUID so retries do not double count | done, one caveat | `V5__analytics_outbox.sql`; `AnalyticsOutboxService.record()` | Each row gets a uuid at write time and PostHog de-duplicates on it, so a **resend** is safe. The uuid is random, not derived from the state change, so two rows written for one state change would be two events (the FRED-224 transition race) |
| 5.6 | Never block on analytics; a PostHog outage must not fail a deposit | done | `record()` try/catch; `recordAchTransferSettled` | Every emit path swallows its own failures and logs. The transport is only ever called by the scheduled publisher, never on a user request |

### Phase 6: subscription revenue

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 6.x | RevenueCat, entitlements, Founder Access, cohort LTV | withdrawn | n/a | Removed in full by the scope change: the advisory fee is collected outside Apple and a separate session owns billing |

### Phase 7: privacy guardrails

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 7.1 | A deny-list of forbidden property key patterns, stripped before dispatch, warned in dev | **done (J1)** | `analytics-privacy-guard.ts` `FORBIDDEN_KEY_PATTERNS`, applied in `analytics.service.ts` `sanitize()` | 13 rules over the normalised key. A stripped property is reported with `console.error` in any non-production build, naming the rule and never the value. `analytics-privacy-guard.spec.ts` |
| 7.2 | A value-shape guard rejecting emails, phone numbers, 9-digit numbers, 8+ digit strings and JWTs | **done (J1)** | `analytics-privacy-guard.ts` `FORBIDDEN_VALUE_PATTERNS` | Six rules: email, formatted SSN, formatted phone, E.164 phone, a run of 8+ digits, and a JWT. Numbers are checked as their decimal text, so a nine-digit number is caught like a string |
| 7.3 | A test firing every event with a forbidden payload and asserting it was stripped | **done (J4)** | `analytics-forbidden-payload.spec.ts`; `AnalyticsOutboxForbiddenPayloadTest.java` | Both sides, through the real dispatch path: all 15 client events plus a screen view and the vendor's own `before_send` hook over `properties`, `$set` and `$set_once`; all 14 server events through the writer, asserting the outbox row's JSON |
| 7.4 | The same guards in the Spring Boot publisher, which is more dangerous than the client | **done (J2)** | `util/AnalyticsPrivacyGuard.java`, called from `AnalyticsOutboxService.record()` on both the event properties and the `$set` | Identical rules, held identical by `AnalyticsPrivacyGuardParityTest`, which reads the TypeScript and fails on any drift. A violation is `logger.error` with the key and the rule, never the value |
| 7.5 | A user-facing opt-out, honoured by client and server, enforced in the publisher | done | `V4__user_analytics_opt_out.sql`; `UserController.java:643`; `AnalyticsPublisherService.loadConsent()` | Stored server-side so it applies on every device; read **live** immediately before sending, so events recorded before the change are suppressed too. `AnalyticsConsentEndpointTest`, `AnalyticsPublisherServiceTest` |
| 7.6 | ATT posture confirmed, privacy nutrition label declarations written into FREDdocs, `PrivacyInfo.xcprivacy` created | **done (J5), with one gap named below** | `frontend/ios/App/App/PrivacyInfo.xcprivacy`; `FREDdocs/privacy-manifest-and-label.md` | `NSPrivacyTracking` false, no tracking domains, product interaction and user id both **not linked**, UserDefaults declared CA92.1. Registered in the Xcode project and proven to reach the bundle: a simulator build copies it to `App.app/PrivacyInfo.xcprivacy`. **It declares analytics only**; the KYC, auth and banking data types are still owed |
| 7.7 | `FREDdocs/analytics-privacy-review.md` listing every property, its purpose and its retention, for counsel | deviated | `FREDdocs/what-we-send.md` | Checkpoint H commissioned a counsel-facing document under a different name. It covers destinations, the identifier, events by kind, banding, opt-out, retention and deletion. It is **not** a per-property purpose and retention table, and the legal flags from decision 8 and decision 9 are not collected in it |

### Phase 8: the MySQL metrics layer

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 8.1a | `v_user_activation` | done, renamed | `R__v_activation_funnel.sql` | Eight steps per signup week with step-to-step conversion. `ActivationFunnelViewTest` asserts exact counts on a seeded dataset |
| 8.1b | `v_contribution_retention` | done | `R__v_contribution_retention.sql` | Months 1, 2, 3 and 6 as 30-day windows from the first settled transfer, each with its own denominator; young cohorts return NULL rather than a misleading zero |
| 8.1c | `v_contribution_continuity`: executed over scheduled | **deferred** | nowhere | There is no `contribution_attempt` table, so the denominator does not exist. The brief called this "silent churn" |
| 8.1d | `v_acquisition_cohort` by source and campaign | **deferred** | nowhere | No acquisition data exists anywhere in the system |
| 8.2 | `metrics_daily` snapshot table plus a scheduled job | deferred | nowhere | Confirmed by grep. Every view recomputes from current state, so history moves when users change state |
| 8.3 | `metrics-definitions.md` with exact SQL for the ten named metrics | deviated | `FREDdocs/metrics.md` | Seven views documented with the question, the exact definition, an example query and the edge cases. Activation rate, time to activation, contribution retention and churn are covered. **LTV, CAC, LTV/CAC, revenue churn, referral coefficient and contribution continuity are not defined at all** |
| 8.4 | Write down that D1/D7/D30 session retention is secondary, and the targets are measured against contribution retention | done, this turn | `FREDdocs/metrics.md`, "Retention means contribution retention, not app opens" | It was missing until today; added as part of this review with the 60%/40% targets named |

### Phase 9: dashboards and alerts

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| 9.1a | `posthog-setup.md` with click-by-click dashboard instructions | deviated | `infra/posthog/dashboards/*.json`; `infra/posthog/sync-dashboards.mjs`; `infra/posthog/README.md` | Checkpoint H replaced clicking with code: four dashboards and 15 tiles defined as JSON and applied idempotently. Live in project 667874 |
| 9.1b | Funnel breakdowns by `acquisition_source`, `signup_month` and device | deferred | nowhere | `acquisition_source` does not exist (3.5), and no breakdown was configured for the other two |
| 9.1c | A path insight from `kyc_started` | deferred | nowhere | No `kyc_started` event, and no path insight. "Where onboarding stops" on the Activation dashboard is the nearest substitute |
| 9.1d | Retention insight on `deposit_settled` as start and return event | done, renamed | `contribution-retention.json`, "Monthly contribution retention" | Built on `first_ach_transfer_completed` returning on `ach_transfer_completed` |
| 9.1e | Alerts on activation dropping 20% week over week, and on KYC rejection, deposit failure and bank-link failure rates | **deferred** | nowhere | No alert exists. Nothing will tell anyone that a number moved |
| 9.2 | Document the App Store Connect funnel check as a weekly checklist | deferred | nowhere | Not written. The store-listing step is invisible to every tool here |
| 9.3 | Sentry or Crashlytics, wired to the same opaque id | **deferred** | nowhere | Not integrated. `what-we-send.md` carries the row describing what has to be disclosed before a key is configured |

### The nine standing rules

| Id | Rule | Status | How it is proven |
|---|---|---|---|
| R1 | Only PostHog and Sentry. No GA, Firebase, Meta or attribution SDK | done | `frontend/package.json` has `posthog-js` and nothing else of the kind |
| R2 | All email through Resend from the backend only; the app never holds a key | n/a today | No email provider is integrated at all, so there is no key anywhere |
| R3 | Money and lifecycle events are server-side, always | done | All 14 money and compliance events are server-emitted; `SERVER_EVENT_NAMES` in the client exists so the client cannot define a competing version, and `ServerAnalyticsEventParityTest` pins the two lists |
| R4 | Never raw amounts, PII or free text. Bands and fixed vocabularies only | done, with a caveat | Verified property by property across all 29 events. The caveat is 7.1 to 7.4: this holds by construction and review, not by a filter |
| R5 | Analytics never blocks or breaks a user flow, and never fails a transaction | done | Four gates on the client plus try/catch everywhere; `record()` returns null rather than throwing; `InvestmentExecutionAchAnalyticsRollbackTest` and the other rollback tests prove the money path survives an analytics failure |
| R6 | Instrument only flows that exist; write specs for the rest, no dead call sites | done | Every deferred event above is absent rather than stubbed. The three billing events are defined, tested against a fake, and have no call site |
| R7 | Tell me before adding an event | done | Every event was proposed at a checkpoint and approved. `paywall_nudge_shown` and the tax-loss-harvesting nudge were explicitly kept after being raised |
| R8 | Commit per phase with clear messages | done | `git log feature/analytics`: one commit per checkpoint, plus the two fixes Andy asked be separate |
| R9 | Do not report complete without the completion review | done | This document |

### The completion-review requirements themselves

| Id | Item | Status | Where |
|---|---|---|---|
| CR1 | A row per numbered requirement, in prompt order, nothing collapsed or omitted | done | Parts 1 to 4 |
| CR2 | Verified, not assumed: say how each claim was checked | done | The "how it is proven" column names a file, a line, a test or a command for every row |
| CR3 | A separate list of every deviation, with reasoning | done | [Deviations and deferrals](#deviations-and-deferrals) |
| CR4 | Manual steps waiting on Andy, written for someone with no context | done | [Open items for Andy](#open-items-for-andy) |
| CR5 | Known gaps and risks, direct rather than clean | done | [Known gaps and risks](#known-gaps-and-risks) |
| CR6 | A runbook: what to see in the dashboard, the database and the network tab | done | [The operating guide](#the-operating-guide) |
| CR7 | Saved to `FREDdocs/app-analytics-implementation-review.md` | deviated | This file is `FREDdocs/analytics-implementation-review.md`, the name given in the Checkpoint I instruction, which is the later of the two |

---

## Part 2: the discovery answers (13 decisions and 12 sections)

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| S1 | Landing page repo out of scope: no writes, no `lpapi` requests, no reads, findings omitted | done | n/a | Nothing in this branch touches it. No request was ever sent to an `lpapi` host |
| S2.1 | Safety-scan the 165 uncommitted files before staging, report anything that must not enter history | done | Checkpoint A2 report | Scanned and reported; nothing was added to `.gitignore` to hide a problem |
| S2.2 | Commit the WIP with an accurate message | done | the pre-branch WIP commit | Message names what is in it |
| S2.3 | Do not push; ask before the first push | done | n/a | Every push in this task was asked for first and approved |
| S2.4 | Create `feature/analytics` from that commit | done | the branch | All subsequent work is on it |
| S3.1 | Gate dev endpoints at registration with `@Profile`, not a runtime check | done | `config/DevToolsConfig.java` | Registration-time gating, so the handlers do not exist outside dev and test |
| S3.2 | Reject `SIMULATED_ATTESTATION` outside dev and test | done | `WebAuthnConfig` / the attestation path | `DevToolsGatingTest` |
| S3.3 | Refuse to boot in prod if a dev endpoint is registered or simulated attestation is on | done | `config/DevToolsProdGuard.java` | `DevToolsGatingTest` asserts the refusal and the non-zero exit |
| S3.4 | A shared-secret header on every `/api/dev/**`, missing or wrong returns 404 | done | `security/DevToolsKeyFilter.java`, `DevToolsKeyValidator.java` | 404 rather than 401, so the endpoints do not advertise themselves. Key read from the environment, never requested from Andy |
| S3.5 | Tests for all four behaviours | done | `config/DevToolsGatingTest.java` | Covers prod 404, boot refusal, attestation rejection and the missing header |
| S3.6 | Confirm from configuration that dev uses Plaid sandbox and Alpaca paper | done | `config/BrokerEnvironmentGuard.java` | Confirmed, and then enforced: the app refuses to boot if a non-prod profile points at a live broker URL or if prod points at a sandbox one. `BrokerEnvironmentGuardTest`, `ProdPlaidPropertiesTest` |
| S3.7 | The other four security findings into the backlog with severities and the must-fix label | done | `FREDdocs/backlog.md:389,397,409` and the Plaid token item | Each carries the label and the discovery date |
| S4.1 | Add Flyway | done | `build.gradle`, `application.properties` | Flyway 11.7.2 |
| S4.2 | Baseline the post-WIP schema as `V1__baseline.sql` | done | `db/migration/V1__baseline.sql` | Generated from the entities after the WIP commit |
| S4.3 | `ddl-auto` to `validate` in every profile | done | `application*.properties` | Verified across every profile file; the boot test in the Checkpoint I rehearsal proves `validate` passes against the migrated schema |
| S4.4 | Verify the baseline against a fresh database and a copy of dev | done | Checkpoint A2 report; re-proven this week | Re-run during the V6 renumbering: a database at develop's V6 was migrated to V8 and the app booted over it with `validate` passing |
| S4.5 | Versioned migrations for tables, repeatable for views; no startup view runner | done | `V2`..`V8`, `R__v_*.sql` (7 views) | Both conventions hold with no exceptions |
| S4.6 | Document how to baseline the dev database in place | done | `FREDdocs/flyway-baseline.md` | Records `baseline-on-migrate` and the baseline version, and the `SPRING_FLYWAY_BASELINE_VERSION` override used to rehearse an upgrade |
| D1 | One "FRED Dev" project; CI off; prod a clean no-op that logs once | done | `analytics.service.ts`; `posthog.adapter.ts` | Specs: "sends nothing from a ci build", "stays a no-op when no key is configured" |
| D2 | `public_id` UUID v4 with a unique index, backfilled, returned in auth responses and as a JWT claim, no refactor of the `Long` id | done | `V2__user_public_id.sql`; `JwtUtils.java:95`; `AuthController.java` | `AuthControllerRefreshPublicIdTest`. NOT NULL deliberately deferred (see deviations) |
| D3 | Views as repeatable migrations | done | `R__v_*.sql` | Seven of them |
| D4 | Use `services/analytics/`, update the lint override, fix the four lint errors, add `ng lint` to CI | done | `.eslintrc.json`; `.github/workflows/verify.yml:108` | Lint runs in CI, so the restricted-import rule is enforced rather than decorative |
| D5 | FRED builds its own Resend pipeline, last, only on a go-ahead | deferred | nowhere | The go-ahead never came. Correctly not started |
| D6 | Acquisition backfill: FRED's half plus a spec | deferred | nowhere | Neither half exists |
| D7.1 | Add `kyc_action_required` | deviated | `kycStatus()` maps the broker's `ACTION_REQUIRED` to `action_required` as a person property | Not a separate event; `kyc_decided` carries `needs_more_info` as an outcome instead |
| D7.2 | Mark `recurring_canceled` and `bank_unlinked` as not yet implemented | deviated | `schedule_cancelled` is implemented; `bank_unlinked` is absent entirely | Cancellation turned out to be reachable (account closure and KYC rejection), so it was built rather than marked. `bank_unlinked` is not even marked |
| D7.3 | Persist Alpaca's rejection reason and map it to a fixed vocabulary | **deferred** | nowhere | The reason is still not persisted, so `kyc_decided` carries the outcome without a reason category |
| D7.4 | Add the Section 10 subscription events and `deposit_returned` | withdrawn / deferred | n/a | Subscription events withdrawn by the scope change. `deposit_returned` deferred with the rest of Section 7 |
| D8.1 | KYC poll: one transaction per user | done | `AccountStatusService` / the poller | Per-user transaction, so one failure cannot roll back another user's approval |
| D8.2 | Stop hard-deleting rejected KYC users; keep the row marked REJECTED | **done (J8)** | `AccountStatusService.handleRejected` | Nothing deletes a rejected applicant. The row is kept with status `REJECTED`, the poller excludes it so the decision cannot fire twice, and the schedule is explicitly held (`MANUAL_REVIEW`) so the `schedule_cancelled` event and the table agree. The SSN column is untouched, pending counsel. `AccountStatusServiceKycDecidedTest`, `AccountStatusServiceKycDecidedRollbackTest`, `ScheduleCancelledOutboxRollbackTest` |
| D8.3 | Account close: ordered and idempotent CLOSING then CLOSED, retry-safe, never hard-delete anyone who has invested | **deviated** | `UserDeletionService.delete()`; `deferralReason()` | There is no CLOSING state. What exists instead: a refusal to delete while money is in flight or within a 60-day settlement window, and one transaction for the deletes |
| D8.4 | Add the no-hard-deletes-without-asking rule to the legal review document | deferred | nowhere | There is no legal review document (7.7) |
| D9.1 | Opt-out default on, stored server-side, honoured on both sides | done | `V4__user_analytics_opt_out.sql` | Default was flipped to on at Andy's instruction, including the migration, the entity default and the specs |
| D9.2 | Report what `shareAnalytics` controls and where its label appears, then rename it honestly | done | Checkpoint C report; `setting_changed` with `setting: 'analytics_consent'` | Reported before changing it; the toggle reads "Share anonymous usage data" |
| D9.3 | Default-on depends on disclosure: privacy policy and App Store label | partly deferred | `what-we-send.md` documents it | The disclosure document exists; the privacy policy and the App Store label are outside this repo and not confirmed |
| D10 | Cloudflare Worker proxy on a neutral hostname with click-by-click deploy steps | done | `infra/posthog-proxy/`; `FREDdocs/posthog-proxy-deploy.md` | `m.fredvested.com` carries none of the blocked words |
| D11 | WIP resolved by Section 2 | done | n/a | |
| D12 | Exempt declared taxonomy keys from key-pattern matching; two tests | **done (J3)** | `DECLARED_PROPERTY_KEYS` and `PERSON_PROPERTY_KEYS` in the taxonomy; `ServerAnalyticsEvent.PROPERTY_KEYS` | The two tests asked for, on each side: a declared key matching a forbidden pattern survives, an undeclared key matching the same pattern is stripped. The exempt lists are pinned to the events themselves by `test:events-documented` and by `ServerAnalyticsEventParityTest` |
| D13 | Security resolved by Section 3 | done | n/a | |
| S6.1 | Screen views through `TabActivationService`, de-duplicated, `NavigationEnd` kept for non-tab routes | done | `screen-tracking.service.ts`; `tab-activation.service.ts:31` | Spec: "drops a repeated screen name but keeps a real change" |
| S6.2 | Call `logout()` and `reset()` in close-account and wherever `logout()` runs; backlog a real sign-out button | done | `auth.service.ts:199`; `sell-withdraw.page.ts:566`; `backlog.md:409` | `reset()` runs before `localStorage.clear()` |
| S6.3 | Create `PrivacyInfo.xcprivacy` declaring the collected data types | **deferred** | nowhere | App Store submission requires it |
| S7.1 | A `transfer` table with Alpaca ids, status and status history | **deferred** | nowhere | |
| S7.2 | Detect returns and emit `deposit_returned` | **deferred** | nowhere | An ACH transfer can settle and come back returned days later; FRED still never sees it |
| S7.3 | A `contribution_attempt` table as the continuity denominator | **deferred** | nowhere | Blocks 8.1c |
| S7.4 | Record withdrawals and sell requests | **deferred** | nowhere | Blocks the churn definition and 4c.6 |
| S7.5 | Fix the UTC versus ET date bug making the first contribution a cycle late | appears done, not by this task | `scheduler/InvestmentScheduler.java:53,150,162,174` | Every due-date path reads `EasternTime.today()`. I did not reproduce the original symptom end to end, so treat this as "looks right on inspection" |
| S7.6 | Set event timestamps from the Alpaca-reported time, not when the poll noticed | **deferred** | `AnalyticsOutboxService` uses `LocalDateTime.now()` | Settlement events are stamped when the poll saw them, so they can be up to 30 minutes late |
| S8.1 | The `waitlist-lookup-api.md` spec | **deferred** | nowhere | `FREDdocs/specs/` has no such file |
| S8.2 | FRED's lookup client, with timeout, retry status and suppression carry-over | **deferred** | nowhere | |
| S8.3 | Normalize emails on write, backfill, report case-only duplicates first | **deferred** | nowhere | |
| S9/S10 | StoreKit 2 subscriptions in full | withdrawn | n/a | |
| S11 | The Resend pipeline, including the "200 and ignore an unknown message id" rule | deferred | nowhere | Gated on a go-ahead |
| S12 | Stop and report at every checkpoint with a traceability table | done | the checkpoint reports | Every one included the table, test counts, deviations and what needed Andy |

## Part 3: the scope change (subscriptions out)

| Id | Item | Status | How it is proven |
|---|---|---|---|
| X1 | Sections 9 and 10 removed in full: no Swift plugin, no paywall, no App Store webhook, no entitlement or ledger tables, no `.storekit`, no `storekit-setup.md` | done | None of these exist anywhere in the repo |
| X2 | No billing or subscription events in the taxonomy | done, with the three defined-only exceptions Andy later asked for | `trial_started`, `subscription_billed` and `subscription_cancelled` are defined, tested against a fake and never emitted, at Andy's explicit Checkpoint E instruction. `ServerAnalyticsEvent.AWAITING_BILLING` names them |
| X3 | Subscription metrics out of Phase 8 | done | No MRR, paid conversion, trial conversion or subscription churn anywhere in `metrics.md` |
| X4 | The 10g legal flags removed | done | Not carried |
| X5 | Checkpoint E removed; other letters unchanged | deviated in labelling | The letters drifted: the instructions used E for the server-side events and G for the metrics layer. Mapping is in the deviations |
| X6 | Drop the StoreKit note from decision 2; keep `public_id` for analytics | done | `public_id` has no purchase role anywhere |
| X7 | Do not add the In-App Purchase capability; still list the bad Release entitlement and the dev-host issue | partly done | Capability not added. **The Release entitlement and dev-host issues are not listed in this review**, because they were reported at Checkpoint A and are outside the analytics layer. They are still unfixed |
| X8 | Delete the unauthenticated RevenueCat-shaped webhook and its tests at A2 | done | `UserController.java:668` carries the tombstone comment where it used to be; the endpoint and its tests are gone |
| X9 | Leave `selected_tier` and the tier gates alone | done | Untouched. `tier()` in the outbox service only *reads* it, narrowing `piggy` to `core` for reporting |
| X10 | Do not edit billing-owned areas | done | No tier or plan screen, settings billing row, or fee, entitlement or payment code was changed |
| X11 | Report as soon as the WIP commit exists | done | Reported at A2 |

---

## Part 4: the checkpoint instructions, B through H

### Checkpoint A2 follow-ups and Checkpoint B

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| B.1 | Startup guard: refuse to boot outside prod unless the Alpaca and Plaid URLs are sandbox or paper, and refuse in prod if they are | done | `config/BrokerEnvironmentGuard.java` | `BrokerEnvironmentGuardTest`; logs the reason and exits non-zero |
| B.2 | Read `DEV_TOOLS_KEY` from the environment, never ask for its value | done | `security/DevToolsKeyValidator.java` | It was never requested |
| B.3 | Strip `?devKey=` immediately with `history.replaceState` | done | the dev-login handoff path | Approved as a B deviation and implemented |
| B.4 | `public_id` stays nullable until F, every read path tolerates null | done | `V2__user_public_id.sql`; `AnalyticsPublisherService` | A row with no `public_id` is PARKED rather than sent under a substitute. **Still nullable**: the F that would have tightened it never ran |
| B.5 | Push and merge protocol: ask before every push, never force-push, never rewrite history, never reset hard | done, with one disclosed exception | n/a | Every push was asked for. One exception: the unpushed merge commit `c3372d6` was amended to include test fixes so it would compile. Disclosed at the time |
| B.6 | PostHog install plus the abstraction layer in `services/analytics/` | done | the folder | |
| B.7 | The ESLint rule and a unit test proving it fires | done | `scripts/lint/assert-posthog-restriction.mjs`; `.github/workflows/verify.yml:110` | Both exist and both run in CI. The earlier claim that the script was not wired in was a mistake in this review, corrected on 2026-10-24 |
| B.8 | One dev project, key from environment config, never in source | done | `environment.ts`, `environment.dev.ts` | |
| B.9 | Nothing leaves the device at the end of B except a smoke event behind the consent gate | done | n/a | Default was opt-out until C wired identity, so in practice nothing left |
| B.10 | Set `posthogApiHost` to `https://m.fredvested.com` in both environment files | done | `environment.ts`, `environment.dev.ts` | |

### Checkpoint C: identity and the opt-out switch

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| C.1 | Fix `contextLoads` so the backend suite is green, without deleting the test | done | the ci profile datasource | 720 backend tests pass with 0 skipped |
| C.2 | `public_id` UUID v4, nullable, idempotent Flyway migration safe on an empty table | done | `V2__user_public_id.sql` | Guarded by `information_schema` so a re-run is a no-op; proven by re-running it in the migration rehearsal |
| C.3 | A `pid` JWT claim carrying it | done | `JwtUtils.java:73-129` | `AuthControllerRefreshPublicIdTest` |
| C.4 | `identify(public_id)` on login, `reset()` on logout, never email, name, phone or numeric id, with a test that fails on a non-UUID | done | `analytics.service.ts:96` | Spec: "refuses to identify with anything that is not a UUID" |
| C.5 | Screen views wired to `TabActivationService`, name only | done | `tab-activation.service.ts:31` | |
| C.6 | Install detection so `app_first_opened` fires once | done | `analytics.service.ts:195` | Specs: "claims the install exactly once across many launches on the same device", and "never claims a second install when storage is unreadable" |
| C.7 | Server-side opt-out column, returned on the session payload, a Settings toggle writing it back, nothing sent while true | done | `V4__user_analytics_opt_out.sql`; `UserController.java:643`; `AuthController.java:190` | `AnalyticsConsentEndpointTest`; specs for "takes consent from the server, not from a local default" |
| C.8 | A fourth gate: no events unless `environment.name` is dev or prod | done | `analytics.service.ts` | Specs for local, ci and prod builds |
| C.9 | End-to-end on dev: real event JSON pasted, then `wrangler tail` proving silence when opted out | done | Checkpoint C report | Both halves were run and pasted with the key redacted |
| C.10 | Flip the consent default to on, in the migration, the entity and the specs | done | `V4__user_analytics_opt_out.sql` | Default 0 for opt-out, so analytics is on unless the user turns it off |
| C.11 | Confirm `anthropic.api-key` in the ci profile is a placeholder | done | `application-ci.properties` | Confirmed a placeholder, and later re-confirmed when Pinecone's placeholder key was the only thing that failed a boot test |

### Checkpoint D: the client taxonomy and its call sites

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| D.1 | Define every client event as a typed union and document it in `analytics-events.md` | done | `analytics-events.ts`; `FREDdocs/analytics-events.md` | `npm run test:events-documented`: "15 client events and their properties, 14 server events, and 18 vocabularies all match" |
| D.2 | Keep it small, and nothing that represents money or compliance | done | the 15 names | The client cannot even name a server event: `SERVER_EVENT_NAMES` is declared so a collision is visible, and the parity test pins it |
| D.3 | Wire every event at its call site: onboarding, freedom sheet, decks, goals, settings, simulator, error toasts | done | 13 `*.analytics.spec.ts` files beside their components | One spec per wired surface, each firing the UI path and asserting the exact payload |
| D.4 | Every pushed page registers a screen view, by hooking the router rather than per page | done | `screen-tracking.service.ts` | One `NavigationEnd` subscription, no per-page calls |
| D.5 | Run on dev and paste one real captured payload per event | done | Checkpoint D report | Pasted with the key redacted |
| D.6 | Keep `paywall_nudge_shown` and the tax-loss-harvesting nudge | done | `GatedFeature` includes `tax_loss_harvesting` | |
| D.7 | Move the scrubber from `sanitize_properties` to `before_send`, same behaviour, same tests | done | `posthog-before-send.ts` | Scrubs `properties`, `$set` **and** `$set_once`, and fails closed by dropping the event. `posthog-before-send.spec.ts` |
| D.8 | Add `goals` to the Surface vocabulary so goal-sheet errors stop reporting as `my_profile` | done | `analytics-events.ts` Surface | In the union with the comment explaining why the sheet is its own surface |
| D.9 | Add `preset_changed` to `freedom_plan_changed` | done | `analytics-events.ts` | So a rates-only save is distinguishable from a no-op save |
| D.10 | Update the doc and the doc-check for each | done | `analytics-events.md` | The doc-check covers properties and vocabulary members, not just names |
| D.11 | Leave the Plaid error handling and the 14 pre-existing failures alone; log both in the backlog | done | `FREDdocs/backlog.md:415` (FRED-226) | Still exactly 14, and none of them is an analytics spec |

### Before Checkpoint E, and Checkpoint E: server-side events

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| E.0a | Stop `$opt_in` firing on every launch | done | `analytics.service.ts` | Spec: "does not re-notify the vendor when consent is re-applied unchanged" |
| E.0b | Prove with a spec that `app_first_opened` cannot fire twice on one install | done | `analytics.service.spec.ts:450,486` | Two specs, including the unreadable-storage case |
| E.1 | The outbox table and its columns, written in the state-change transaction | done | `V5__analytics_outbox.sql`; `AnalyticsOutboxService.record()` | `record()` refuses outside a transaction; six `*AnalyticsTest` / `*RollbackTest` classes prove the row commits and rolls back with the change |
| E.2 | A scheduled publisher: batches, `distinct_id = public_id`, a stable uuid, marks sent, backs off | done | `AnalyticsPublisherService`; `AnalyticsPublisherScheduler` | `AnalyticsPublisherServiceTest` covers retry, the 2/4/8-minute capped backoff, idempotent resend, and parking after 10 attempts |
| E.3 | Rows never deleted, and Checkpoint G reads the same table | done | the views | No delete path exists; four views read `analytics_outbox` |
| E.4 | Consent: still write the row when opted out, mark it suppressed, never send | done | `markSuppressed()` | Consent is read **live** just before sending, so a row written before an opt-out is suppressed too |
| E.5 | Mirror the band vocabulary in Java with a test that the two tables are identical | done | `util/AnalyticsBands.java`; `AnalyticsBandsParityTest` | The test reads both definitions and fails on any drift |
| E.6 | The nine events with their exact properties | done | see Phase 4 rows | All nine, property for property |
| E.7 | Each emitted from the service or webhook handler that commits the change, never a controller | done, one exception | `TradingController.java:423` | One `schedule_cancelled` is emitted from the controller, because that is where the close-account transaction is. Noted at the time |
| E.8 | Person properties by `$set`: tier, kyc_status, has_linked_bank, has_schedule, has_funded | done | `AnalyticsOutboxService.personProperties()` | Booleans and narrowed enums only; `tier()` maps the legacy `piggy` to `core` and anything unknown to null |
| E.9 | Reserved names in `analytics-events.ts` match exactly, and the money-word test stays | done | `SERVER_EVENT_NAMES`; `ServerAnalyticsEventParityTest` | Fails if either list drifts |
| E.10 | Billing: define the three events, their properties and the emitter interface, test against a fake, leave a named TODO, stub nothing | done | `BillingAnalyticsEmitter.java`; `BillingAnalyticsEmitterTest` | `trial_length_days` is a constant (14, every tier) rather than a caller argument. The TODO names the call site: the billing session's trial-creation path, in the same transaction that persists the trial record |
| E.11 | Document all nine under a Server-side section and extend the doc-check | done | `analytics-events.md`; `assert-events-documented.mjs` | The check covers server names and cross-list collisions |
| E.12 | End-to-end on dev including the simulated Alpaca webhooks | **deferred by Andy** | n/a | "I am not running the KYC step on dev; the unit and rollback coverage is enough for now" |
| E.13 | Reject a second `POST /create-account` when a brokerage account exists, with a test | done | `AlpacaController.java:99`; backlog FRED-227 | Refuses with 409 **before** the broker call, because once that call succeeds the duplicate account really exists |
| E.14 | A beta QA line: the four money and compliance events must be seen in the outbox and in PostHog from the first real beta account before more users are admitted | done | `FREDdocs/beta-qa-checklist.md:16-27` | Names all four and requires `status = 'SENT'` plus visibility in PostHog |

### Checkpoint G: the MySQL metrics layer

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| G.1 | Views in an idempotent Flyway migration reading the outbox, users, schedules and transfers | done | seven `R__v_*.sql` | Repeatable migrations, so a checksum change re-applies the view |
| G.2 | Aggregate only: no email, name, `public_id` or raw amount in any view | done | the view bodies | Verified column by column. Enforced twice: the views select no such column, and the read-only role grants on views only |
| G.3 | A read-only MySQL role scoped to these views | done | `db/roles/metrics_reader_role.sql` | `fred_metrics_reader`; grants survive `CREATE OR REPLACE VIEW` |
| G.4 | `v_activation_funnel` with counts and step-to-step conversion | done | `R__v_activation_funnel.sql` | Test asserts exact numbers on a seeded dataset |
| G.5 | `v_time_to_funded`: median, p75, and the 7/14/30-day shares | done | `R__v_time_to_funded.sql` | |
| G.6 | `v_contribution_retention`: the headline, months 1, 2, 3 and 6 as 30-day windows | done | `R__v_contribution_retention.sql` | Young cohorts return NULL rather than 0 |
| G.7 | `v_schedule_churn`: created, paused, cancelled, resumed, and active at month end | done, extended | `R__v_schedule_churn.sql` | Now publishes **both** meanings of active (`active_by_intent`, `active_investing`) plus `held`, at Andy's instruction. `ScheduleChurnViewTest`, 11 tests |
| G.8 | `v_kyc_outcomes` with median days to decision | done | `R__v_kyc_outcomes.sql` | Reads the outbox, so it is the only view that can see rejected users |
| G.9 | `v_consent`: opt-out share per cohort | done, limited | `R__v_consent.sql` | Left as is at Andy's instruction, with the limitation written into `metrics.md` |
| G.10 | A test per view with exact numbers and the three named edge cases | done | seven `*ViewTest` classes | Including "user with no transfers", "transfer before schedule" and "a cohort with zero funded users returns 0, not NULL" |
| G.11 | Document every view in `metrics.md` with its question, exact definition and an example query | done | `FREDdocs/metrics.md` | |
| G.12 | The weekly reconciliation query between outbox sent counts and PostHog counts | done | `R__v_outbox_reconciliation.sql`; `metrics.md` | Both halves, plus how to read a gap in either direction. The HogQL half was missing the three schedule events until this review |
| G.13 | No endpoint and no UI | done | n/a | None was built |
| G.14 | Add the three schedule transition events, same transaction, same rollback tests, then complete `v_schedule_churn` | done | `InvestmentScheduleService`, `AchRelationshipService`, `AccountStatusService`, `TradingController` | `ScheduleTransitionAnalyticsTest`, `ScheduleTransitionOutboxRollbackTest` |

### The two fixes before H, and Checkpoint H

| Id | Item | Status | Where it lives | How it is proven |
|---|---|---|---|---|
| H.0a | Replace `is_paused` with a three-state status, migrated from the flag plus ACH state; ACH only moves pending_bank to active; user pause and resume only move active and paused_by_user; update the three events and the churn view; keep every test green | done | `V7__investment_schedule_status.sql`; `InvestmentSchedule.java` | Rehearsed against a database at develop's V6 carrying Autopilot's shape: all five row shapes backfilled correctly, `is_paused` dropped, re-run clean. 720 tests green |
| H.0b | `closeAccount` must rethrow so a failed delete rolls back the whole transaction including the outbox row | done | `TradingController`; `UserDeletionService` | The rethrow plus an `@ExceptionHandler`; the rollback is the service's `TransactionTemplate`. `TradingControllerScheduleCancelledTest` |
| H.0c | Put the transition race in the backlog | done | `FREDdocs/backlog.md:421` | With both fix options costed |
| H.1 | `what-we-send.md`: destinations, every event and property, the identifier, what is banded, what opt-out suppresses, retention | done | `FREDdocs/what-we-send.md` | Re-verified against the code today; five corrections made (see the audit below) |
| H.2 | Dashboards from code: the PostHog API, a personal key from the environment, JSON under `infra/posthog/`, an idempotent script | done | `infra/posthog/` | Third consecutive run reports 0 created, 4 updated |
| H.3 | Tell Andy the exact scopes needed | done | `infra/posthog/README.md` | Four scopes, project-scoped |
| H.4 | Four dashboards: Onboarding funnel, Activation, Contribution retention, Data health | done | the four JSON files | 15 tiles, verified live in project 667874 |
| H.5 | Document how to re-run the script | done | `infra/posthog/README.md`; and below | |
| H.6 | Run with `--dry-run`, show the plan, then apply and give the four URLs | done | Checkpoint H report | URLs below |
| H.7 | Put the 1-year retention in `what-we-send.md`, and note replay, logs and tracing are off | done | `what-we-send.md` Retention | |
| H.8 | Person deletion on the account-close path: a row in the same transaction, the publisher calling the vendor with the outbox's retry and backoff | done, widened | `V8__analytics_person_deletions.sql`; `AnalyticsPersonDeletionService`; `PostHogPersonGateway`; `AnalyticsPublisherService.publishPendingDeletions()` | Widened in the merge to cover **every** `DeletionReason`, not just a closure, which closed the gap `what-we-send.md` had listed as open. No consent check, deliberately. `AnalyticsPersonDeletionTest`, `UserDeletionServiceTest` |
| H.9 | Merge develop, renumber the migrations behind Autopilot's V6, resolve `closeAccount` into `UserDeletionService`, keep holds orthogonal to status | done | `c3372d6`; `V7`, `V8` | Nine conflicts resolved preserving both designs; rehearsed against a pre-V6 database |
| H.10 | Publish both meanings of active, with churn and net change on intent, the Autopilot test retargeted not rewritten | done | `674a4f3`; `R__v_schedule_churn.sql` | `ScheduleChurnViewTest` restates the blocking-hold list so the view and `BLOCKING_HOLDS` cannot drift apart silently |
| H.11 | `FREDdocs/handoff-paycheck-autopilot.md` written for a session with no context | done | that file | Eight sections, including the migration number the next session must use (V9) |

---

## Deviations and deferrals

One line each: why it is not what was asked, and what would close it.

### Deviations

| Id | Why | What would close it |
|---|---|---|
| 1.1 | `core/` does not exist in this codebase; `services/` is the convention (decision 4) | Nothing. The instruction was superseded |
| 2.1 | PostHog deprecated `sanitize_properties`; it logs an error on every capture and is slated for removal | Nothing. `before_send` is strictly better: it can drop an event, which the old hook could not |
| 2.4 | PostHog's free plan allows one project (decision 1) | A paid plan, then a second key for prod |
| 3.3 | The Checkpoint E instruction gave a different, server-side list, and the original list's acquisition half has no data behind it. J6 cut the type down to the five that are really sent | Acquisition attribution, on the follow-on plan. Then the properties come back with something in them |
| 4a.1 | iOS gives an app no install attribution, so `install_source` would always be null | Nothing |
| 4a.2, 4a.4, 4a.7 | A per-step viewed/completed pair measures drop-off at every step; separate start markers would double-count the same moment | Nothing |
| 4a.3 | Registration persists the plan and the passkey in one transaction; a third event for the same instant would be a phantom funnel step | Nothing |
| 4a.6 | One `kyc_decided` with a three-way outcome makes approval rate a single query. Days rather than hours because the broker takes days | Nothing for the shape. The missing **rejection reason** is D7.3 |
| 4a.8, 4a.15 | Even a coarse institution bucket is identifying in combination with other properties, and we would not act on it | Nothing. This is stricter than asked |
| 4a.9 | One `error_shown` with a category keeps every user-visible failure in one place | A server half, if bank-link failures need to be counted when the phone is gone |
| 4a.11 | An initiated transfer that is later returned would inflate every funded number; only settlement is a fact | Nothing, as long as returns stay uninstrumented. If S7.2 lands, revisit |
| 4a.16 | Twelve bands rather than seven: finer below $500 where most contributions are, coarser above $2,500 where few people are and precision identifies them | Nothing |
| 4a.17, 4c.1, 4c.2 | An exact ordinal or day count on a small user base is close to a fingerprint, so both are banded. The pause day bands additionally have no history to measure from | A pause history table would allow the day bands |
| 4b.1, 4b.2 | `freedom_plan_changed` carries more than direction and delta, and a viewed date is a screen view | Nothing |
| 4c.4 | The create endpoint doubles as the edit endpoint and the table has one row per user, so an edit has no event to emit without inventing `schedule_updated` | Add `schedule_updated` to the taxonomy, if amount changes are worth counting |
| 4c.7 | A closure is visible through the `schedule_cancelled` it causes and the person-deletion row | An `account_closed` event, if closures without a schedule need counting |
| 5.1 | The Java SDK would add a dependency and manage its own queue, which fights the outbox: we need our own uuid, our own retry and our own suppression | Nothing |
| 5.4 | An async `ApplicationEvent` listener cannot be atomic with the transaction, which is the one property Checkpoint E exists to guarantee | Nothing |
| 5.5 | The uuid is assigned per row at write time, so a **resend** cannot double count. It is not derived from the state change, so a double **write** would be two events | FRED-224: `@Version` on `InvestmentSchedule`, or a pessimistic lock on the three user-facing paths |
| 7.7 | Checkpoint H commissioned the same document under a different name and a wider remit | A per-property purpose and retention table, and the decision 8 and 9 legal flags, added to `what-we-send.md` or a companion |
| 8.1a | Renamed to match the funnel it reports | Nothing |
| 8.3 | `metrics.md` documents the seven views that exist. The six metrics it does not define have no data behind them. **LTV/CAC deferred by decision on 2026-10-24 to the follow-on plan** | Revenue from the billing session, a CAC input from Andy, and Section 8 for the cohort split |
| 9.1a | Dashboards as code instead of click-by-click: a dashboard built by hand drifts and nobody can say what changed | Nothing. `infra/posthog/README.md` is the runbook |
| 9.1d | Built on the real event names | Nothing |
| CR7 | The Checkpoint I instruction named the file `analytics-implementation-review.md`, which is later than the brief's `app-analytics-implementation-review.md` | Nothing, unless you want the original name; it is a `git mv` |
| D7.1 | `action_required` is a person-property state and `needs_more_info` is a `kyc_decided` outcome; a separate event would duplicate both | Nothing |
| D7.2 | Cancellation turned out to be reachable (account closure, KYC rejection), so it was built rather than marked not-implemented | Nothing for cancellation. `bank_unlinked` is 4c.5 |
| D8.3 | There is no CLOSING or CLOSED state. What exists is a refusal to delete while money is in flight or inside a 60-day settlement window, and a single transaction for the deletes | A real two-phase close, with the states and a resumable retry |
| E.7 | One `schedule_cancelled` comes from `TradingController`, because that is where the close-account transaction is opened | Nothing material; it is the transaction boundary that matters, not the layer |
| X5 | The checkpoint letters drifted from the revised plan: the plan's F (metrics) was run as G, and the plan's G (dashboards) as H. The plan's "Phase 9 alerts, Sentry, and Section 8 acquisition" half of G was never run at all | See open item 1 |
| X7 | The bad Release entitlement and the dev-host default are real but are not analytics, and the scope change told me not to fix them | A separate story for each. Both are still unfixed |

### Deferrals

| Id | Why | What would close it |
|---|---|---|
| 3.5, 8.1d, 9.1b, S8.1, S8.2, S8.3, D6 | **Acquisition attribution was scheduled into a checkpoint that was never run. Deferred by decision on 2026-10-24 to the follow-on plan.** It needs a spec for the landing page side, and the landing page repo is out of scope | Write `FREDdocs/specs/waitlist-lookup-api.md`, then build FRED's client: columns on `users`, a 2-second-timeout lookup during registration, a retry job, and the properties onto the person record |
| 4a.13, 4a.14, S7.1, S7.2, S7.3, S7.4, S7.6 | **Section 7, the data foundations, was never built. Deferred by decision on 2026-10-24 to the follow-on plan.** Every one of these events needs a table that does not exist, and the brief itself said to instrument nothing that has no data behind it | A `transfer` table with status history, a `contribution_attempt` table, a withdrawal record, then the three events and `v_contribution_continuity` on top |
| 4b.3, 4b.4, 4b.5 | MFU, education and referral engagement were in the original taxonomy but in no checkpoint instruction, and I was told not to add events unasked | Decide whether they are worth the event budget, then one checkpoint's work |
| 4c.5 | `bank_unlinked` has no call site; a bank *change* is visible as `schedule_paused` with `reason: 'bank_change'` | A call site on the unlink path, if unlinking without relinking is a churn signal you want |
| 4c.6 | Nothing records a withdrawal, so the event has nothing to fire from. The brief called full withdrawal one of the two churn signals that matter most | S7.4, then the event |
| 4d.x, S11, D5, R2 | The email pipeline was explicitly gated on a go-ahead that never came. Correctly not started | Your go-ahead, then one checkpoint |
| 7.6, S6.3 | **Built as J5 for the analytics half only.** The manifest and `privacy-manifest-and-label.md` declare what analytics sends; the KYC, auth and banking data types are not declared, and they are collected | The owners of those flows adding their entries to both the manifest and the label. Listed in `privacy-manifest-and-label.md` with who owns each one |
| 8.2 | No `metrics_daily` and no snapshot job, so every view recomputes from current state and history moves when users change state | One table, one scheduled job writing a row per metric per day |
| 9.1c | No `kyc_started` event and no path insight | A path insight from `onboarding_step_viewed` filtered to the KYC step |
| 9.1e | **No alerts.** Nothing will tell anyone that activation fell or rejections spiked. **Deferred by decision on 2026-10-24 to the follow-on plan** | PostHog alerts on the four named conditions, ideally added to the sync script so they are code too |
| 9.2 | The App Store Connect funnel is invisible to every tool in this repo | A short weekly checklist and a row in whatever sheet the numbers go in |
| 9.3 | **Sentry is not integrated. Deferred by decision on 2026-10-24 to the follow-on plan.** A crash during Plaid linking is indistinguishable from "lost interest" in funnel data | A Sentry project, the SDK wired to the same `public_id`, and the disclosure row in `what-we-send.md` added **before** the key is configured |
| D7.3 | Alpaca's rejection reason is still not persisted, so `kyc_decided` cannot carry a reason category | Persist the reason on the poll, map it to a fixed vocabulary, add the property |
| D8.4 | There is no legal review document to add the no-hard-deletes rule to | 7.7 |
| D9.3 | The privacy policy and the App Store label live outside this repo | Confirm both say analytics is on by default and can be turned off |
| B.4 | `users.public_id` is still nullable. The checkpoint that would have tightened it (F) never ran | Verify the backfill in every environment, then one migration to NOT NULL |
| E.12 | Andy deferred it: "the unit and rollback coverage is enough for now" | The beta-QA check below, on the first real account |

---

## Open items for Andy

In priority order. Each one says exactly what to do and where. Checkpoint J is built, so the four
items it answered are gone from this list. What is left needs something only you, or another
flow's owner, can supply.

1. **Get the KYC, auth and banking data types into the privacy manifest and the label.** J5
   declared what analytics sends. The app also collects a name, date of birth, **Social Security
   number**, address, email, phone and bank account details, and none of that is declared yet. A
   manifest that covers one tenth of the collection is a rejected submission at best.
   **Action:** `FREDdocs/privacy-manifest-and-label.md` lists each missing data type and who owns
   it. Tell me to write them if you would rather I drafted it, but it needs whoever knows those
   flows to confirm the purposes.
2. **Take the SSN retention question to counsel.** You are already doing this. It is listed here so
   it is not lost: keeping a rejected applicant's row means keeping that row's identity data, and
   nothing in J touches the column until there is an answer. **Action:** when counsel answers, tell
   me the retention rule and whether anything needs to clear, re-encrypt or age out that column, and
   it goes into the counsel-facing document along with the no-hard-deletes rule (D8.4, 7.7).
3. **Provision the deletion credential.** Until it is set, every PostHog person deletion sits as
   PENDING and we do not keep the promise `what-we-send.md` makes. **Action:** in PostHog, Settings
   then Personal API keys, create a key scoped to project 667874 with `person:read` and
   `person:write`. Set it on the dev Railway service as `POSTHOG_PERSONAL_API_KEY`, and set
   `POSTHOG_PROJECT_ID=667874` beside it. Neither is the public `phc_` key; do not reuse that one.
4. **Turn the publisher on wherever you want events to flow.** `ANALYTICS_PUBLISHER_ENABLED`
   defaults to **false**, so the outbox fills and nothing is sent. **Action:** set
   `ANALYTICS_PUBLISHER_ENABLED=true` and `POSTHOG_PROJECT_KEY=<the phc_ key>` on the dev Railway
   service. Check it worked with the `pending_count` column of `v_outbox_reconciliation`: it should
   stop growing.
5. **Confirm the privacy policy and the App Store privacy label say analytics is on by default and
   can be turned off.** Both live outside this repo, and J5 writes the nutrition-label answers that
   have to agree with them. **Action:** read them, or send them to me.
6. **Set a CAC input, when the follow-on plan comes up.** LTV/CAC has no definition because it has
   no cost input. **Action:** decide where ad spend, tooling cost and an hours-based cost for
   content-driven users are recorded, even if it is a spreadsheet, and I will write the definition
   against it.
7. **Decide when the follow-on plan runs**, and in what order. The five items are listed above with
   their scope. Acquisition attribution is the one with an external dependency: it needs a spec for
   the landing page side, which is a different repo and out of my scope. **Action:** tell me which
   of the five to plan first, and whether to write `FREDdocs/specs/waitlist-lookup-api.md` for the
   other session now or later.

---

## Known gaps and risks

**Instrumented but never seen working end to end.** `kyc_submitted`, `kyc_decided`,
`first_ach_transfer_completed` and `ach_transfer_completed` have unit and rollback coverage and have
never been observed on a real account, at your instruction. The beta-QA check below exists for
exactly this, and it gates admitting more users.

**The publisher is off by default, and that is the loudest failure mode.** With
`ANALYTICS_PUBLISHER_ENABLED` false, or with no project key, every event still lands in the outbox
and nothing reaches PostHog. This was designed to look like an outage rather than a success, so the
rows sit PENDING. If nobody looks at `pending_count`, it looks like silence rather than a problem.

**History moves under the snapshot columns.** Without `metrics_daily` (8.2), every view recomputes
from current state. `v_schedule_churn`'s three month-end columns and `v_activation_funnel`'s step
counts are accurate for the month in progress and approximate for the past: a cancelled schedule
leaves the table taking its creation with it. The event-derived columns do not have this problem.
Prefer them when comparing months.

**A double write would double count.** Idempotency protects a resend, not a double emit. The
transition race (FRED-224) can in principle write two `schedule_paused` rows for one user action,
which `v_schedule_churn` would read as two pauses. It needs a retried request or a double tap to
fire.

**Rejected users are invisible in most views.** Because the row is deleted, rejected users are
absent from `users` and therefore from every cohort built on it, including the funnel's
`signed_up`. This makes the funnel's approval rate look better than reality. `v_kyc_outcomes` reads
the outbox and is the only honest place to see rejections. **Never compute an approval rate from
`v_activation_funnel`.**

**J8 fixed this on 2026-10-24, forward only.** The row is kept now, so new cohorts are complete,
but anyone rejected before that date is already gone from `users` and cannot be recovered. Treat
the funnel's approval rate as unusable for every cohort that starts before 2026-10-24, and read
`v_kyc_outcomes` instead for any period.

**Settlement events can be half an hour late.** They are stamped when the poll noticed, not when
Alpaca says it happened (S7.6), so time-to-funded is accurate to about 30 minutes, not to the
minute.

**Returns are invisible.** An ACH transfer can settle, emit `first_ach_transfer_completed`, and come
back returned days later. Nothing records that, so a returned transfer still counts as funded.

**The dashboards depend on a vendor bug workaround.** `GET /insights/?dashboards=<id>` returns a 500
from PostHog's own server. The sync script reads tiles from the dashboard instead. If PostHog fixes
the endpoint nothing breaks; if they change the dashboard payload shape, the script stops finding
existing tiles and would create duplicates. A `--dry-run` would show that before it happened, which
is why the runbook below says to run one first.

**The guard is a deny-list, not a reviewer.** J1 and J2 catch a property that is *named* for
something forbidden or whose *value* has a recognisable shape. They would not catch a genuinely
new kind of identifying data under an innocent name with no shape: a free-text field called
`label`, say, or a town name. Nothing sends either, and the taxonomy accepts no free text, but the
filter is a backstop for a mistake, not a substitute for reading what a new property carries. The
`$`-prefixed properties the vendor's SDK adds are also exempt from the value-shape half, because
they are epochs and generated ids; the key deny-list still applies to them, which is what stops a
`$email`.

**Fourteen frontend specs fail, and they are not mine.** FRED-226. None is an analytics spec; they
are component smoke tests and two swipe utilities. They were failing before this task and you told
me to leave them alone.

---

## The operating guide

### 1. How to read each dashboard

All four live in PostHog project **667874**. They answer different questions and they disagree on
purpose: the server dashboards are complete, the client ones depend on a phone being awake and on
consent.

**[FRED — Onboarding funnel](https://us.posthog.com/project/667874/dashboard/2243707)** (4 tiles).
Built entirely from server events, so a step cannot be missing because a phone was offline. This is
the funnel to trust. *Signup to first transfer* is the spine; the widest gap is usually
`kyc_submitted` to `plaid_linked`. *KYC outcomes* and *KYC attempts* explain the top of it: a rising
share of attempt `2` and `3_plus` means the form is wrong, not the user. *First bank link vs relink*
separates new users from existing ones changing bank; a rise in relinks usually follows a Plaid
problem.

**[FRED — Activation](https://us.posthog.com/project/667874/dashboard/2243702)** (4 tiles). Mixes
client and server events, which is the only way to see install through funded. Read the drop between
step 3 and step 4 with suspicion: that is the client-to-server boundary, and a gap there can mean
consent or a dead phone rather than a user giving up. *Where onboarding stops* (viewed but not
completed) tells you whether a step is a screen problem. *Errors shown, by category* beside a funnel
drop usually explains it.

**[FRED — Contribution retention](https://us.posthog.com/project/667874/dashboard/2243705)**
(3 tiles). **`v_contribution_retention` in MySQL is the authority for this number, not this
dashboard.** PostHog's retention tile counts every cohort including ones too young to have had the
chance to return, which drags the percentage down. Use the tile to spot a shape change and the view
to quote a number. *Settled transfers per week* is the volume behind it: a flat retention percentage
on falling volume is not good news. *Schedule churn, user-initiated only* is the churn signal;
system transitions are not in it by design.

**[FRED — Data health](https://us.posthog.com/project/667874/dashboard/2243706)** (4 tiles). Is the
measurement itself working. *Events per day, by name* is where a broken emitter shows up first: a
line that goes flat at zero is a wiring regression. *Server events per day* is the money spine, and
a gap there means the publisher stopped. **Read the caveat on this one:** an opted-out user sends
nothing, so PostHog cannot measure the opt-out rate. *Consent changes* is the nearest it can get.
For the real numbers use `v_consent` and `v_outbox_reconciliation` in MySQL.

### 2. How to run the weekly reconciliation

Full detail, including how to read a gap in either direction, is in
[metrics.md](metrics.md#the-weekly-reconciliation). The short version:

1. Connect as `fred_metrics_reader` and run the "Ours" query over the last 8 weeks.
2. In PostHog, open a HogQL query (Product analytics, then SQL) and run the "Theirs" query over the
   same window.
3. Compare `sent_count` against `posthog_count` per week and per event name.
4. `pending_count` is the number to watch day to day. If it grows week on week, the publisher is not
   draining and every number above it is stale.
5. PostHog **lower** than `sent_count` means events we marked sent did not land: set those rows back
   to `PENDING` and let the publisher retry, which is safe because PostHog de-duplicates on the
   row's uuid. PostHog **higher** means something is sending outside the outbox, which should be
   impossible and is worth stopping to understand.

### 3. How to run the dashboard sync

The dashboards are code. Edit the JSON, not PostHog, or the next sync overwrites you.

```bash
export POSTHOG_PERSONAL_API_KEY=phx_...          # the four scopes in infra/posthog/README.md
node infra/posthog/sync-dashboards.mjs --project=667874 --dry-run   # always first
node infra/posthog/sync-dashboards.mjs --project=667874             # then apply
```

`--project` must be passed: the key correctly lacks `project:read`, so the script cannot discover
the id. Matching is **by name**: a dashboard or tile that exists is updated in place and keeps its
id, so shared links keep working; a tile removed from the JSON is removed from the dashboard.
Renaming a tile therefore creates a new one and orphans the old, which is deliberate. A clean re-run
reports `0 dashboard(s) created, 4 updated`.

### 4. What to look for within 24 hours of a deploy

| Where | Correct | Wrong |
|---|---|---|
| Network tab, on device | Requests to `m.fredvested.com`, not to PostHog directly | Requests to `us.i.posthog.com`, or none at all (check consent and that `environment.name` is dev or prod) |
| Database | `SELECT status, COUNT(*) FROM analytics_outbox GROUP BY status` shows rows moving to `SENT` | Everything stuck at `PENDING` (publisher off or no key), or anything `PARKED` (needs a human: read `last_error`) |
| PostHog Activity view | Events arriving with `distinct_id` equal to the user's `public_id`, and no email, name or numeric id in any property | A `distinct_id` that is not a UUID, which should be impossible, or an unexpected property name |
| Data health dashboard | Every expected event name has a line | A line flat at zero is a wiring regression on that event |

### 5. What to check on the first real beta account

This is the gate you set: **do not admit more users until all four have been seen.** The checklist
lives in [beta-qa-checklist.md](beta-qa-checklist.md); this is the same thing with the SQL.

Walk one real account through KYC, bank linking, a schedule, and a first transfer. Then, for each of
`kyc_submitted`, `kyc_decided`, `first_ach_transfer_completed` and `ach_transfer_completed`:

```sql
SELECT event_name, status, attempts, occurred_at, sent_at, last_error
FROM analytics_outbox
WHERE public_id = '<the user public_id>'
ORDER BY occurred_at;
```

1. The row exists, and its `status` reaches `SENT`. `PENDING` for more than a few minutes means the
   publisher is off or cannot reach PostHog; `PARKED` means read `last_error`.
2. The same event is visible in PostHog's Activity view for that `distinct_id`.
3. The properties are banded and anonymous: `attempt` is `1`, `2` or `3_plus`; `amount_band` is a
   bucket name; there is no amount, no bank name, no email.
4. `kyc_decided` carries a `days_to_decision_band`, not a date.
5. Then check the person record in PostHog shows `kyc_status`, `has_linked_bank`, `has_schedule` and
   `has_funded` moving as the account progresses.

Two things worth doing on the same account while you have it: flip the Settings toggle off, repeat
one action, and confirm the new row is `SUPPRESSED` and nothing reached PostHog. And if that account
is ever closed, confirm a row appears in `analytics_person_deletions` and reaches `COMPLETED`, which
needs open item 3 done first.

---

## Audit: does `what-we-send.md` match what the code sends today?

Checked today by extracting every event name and property from the code and diffing against the
documents. **The code is the authority; where they disagreed, the document was wrong and was fixed.**

**Mechanically verified.** `npm run test:events-documented` passes: 15 client events **and their
properties**, 14 server event names, and 18 closed vocabularies all match
`FREDdocs/analytics-events.md`. Server event *properties* are not covered by that check, so I
diffed all 14 by hand against their emit sites: every one matches, property for property.

**Person properties match.** The five keys in `AnalyticsOutboxService.personProperties()` are
exactly the five in the document, including the `action_required` and `other` values of `kyc_status`.

**Five corrections made to `what-we-send.md`:**

1. `ach_transfer_completed` was documented as carrying a banded amount. It does not: only
   `first_ach_transfer_completed` carries an amount, and later transfers carry a banded count and
   nothing else. The two were sharing a row; they now have one each.
2. `investment_schedule_created` also carries `days_since_signup_band`, which was not mentioned.
3. The schedule transitions carry `initiator` as well as `reason`; the document said only "and why".
4. The claim that a CI check keeps *this* document honest was overstated. The check pins
   `analytics-events.md`; this page is a summary kept true by review against it. Now says so.
5. The open item saying a rejected KYC application does **not** request a PostHog deletion is no
   longer true: the merge widened it to every deletion reason. Replaced with the two items that are
   still open, and a third added for the missing deny-list, since this is the document counsel reads.

**One code-side discrepancy, left alone deliberately.** The `PersonProperties` interface in
`analytics-events.ts` declares `signup_month`, the four acquisition fields, `waitlist_member`,
`founder_access`, `bank_linked` and `has_active_recurring`. **Nothing sets any of them**:
`identify()` is called with an id and no properties. The document is right and the type is
aspirational. Two of them (`bank_linked`, `has_active_recurring`) are near-duplicates of the
server's `has_linked_bank` and `has_schedule` under different names, which is how a person record
grows two columns meaning one thing. Removing them is a code change, and this checkpoint was "no new
features", so it is noted rather than done.

**Also fixed in `metrics.md`:** the HogQL half of the weekly reconciliation listed eight event names
and omitted the three schedule transitions, so the two halves were not comparable; the Phase 8.4
note that D1/D7/D30 session retention is never the headline number was missing entirely and has been
added with the 60%/40% targets; and four em dashes in prose I wrote were rewritten.
