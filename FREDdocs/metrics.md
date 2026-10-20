# The metrics layer

PostHog shows behaviour. **This layer shows money, and it is the source of truth for the numbers
decisions get made on.**

The difference matters. PostHog only sees users who consented, only sees what a device managed to
report, and keeps its own copy of events it was handed. This database has everyone, including the
people who opted out and the people whose phone never came back online. When the two disagree,
this one is right.

Everything here is a SQL view over `analytics_outbox`, `users`, `investment_schedules` and
`investment_executions`. There is no endpoint and no UI by design; dashboards are a later
checkpoint. You read these with a SQL client.

## What these views will not tell you

**No identities and no amounts.** No view selects an email, a name, a `public_id` or a raw dollar
figure. They return counts, rates, medians and day counts. That is enforced by the read-only role
below granting on the views only: a reports account cannot reach the base tables at all.

**Rejected users are invisible in most of them.** A rejected KYC hard-deletes the user row, so
rejected users are absent from `users` and therefore from every cohort built on it, including the
funnel's `signed_up`. This makes the funnel's approval rate look better than reality.
`v_kyc_outcomes` reads the outbox instead, whose rows survive the deletion, and is the only honest
place to see rejections. **Never compute an approval rate from `v_activation_funnel`.**

**Cohorts are signup weeks**, keyed on the Monday of the week containing `users.create_date`. The
two month-keyed views (`v_schedule_churn`, `v_kyc_outcomes`) use the first of the calendar month
instead, because the thing they count is not a property of a cohort.

---

## v_activation_funnel

**The question:** where do people stop, and which step is worth fixing?

**"Funded" means a settled ACH transfer**, here and in `v_time_to_funded` and
`v_contribution_retention`. An investment paid from cash already sitting at the broker (the
buying-power path) also sets `funding_completed_at`, but no ACH ran and no money left a bank. The
`first_ach_transfer_completed` event excludes that case, so these views do too: if they disagreed,
the weekly reconciliation would show a gap that is not a delivery failure.

**The definition:** one row per signup week. Each step is "ever reached", not "reached in order" —
a user who linked a bank before approval counts for both, because the real flow does let steps
overlap. Every step reads the operational table rather than the event ledger, so cohorts that
predate the analytics work are still correct.

| Step | True when |
|---|---|
| `signed_up` | the user row exists with a `create_date` |
| `created_plan` | `users.plan_id` is set (written at registration start) |
| `created_passkey` | a `passkey_credentials` row exists |
| `submitted_kyc` | `kyc_submitted_at` or `alpaca_account_id` is set |
| `approved` | `account_status` is `APPROVED` or `ACTIVE` |
| `linked_bank` | `plaid_access_token` is set |
| `created_schedule` | an `investment_schedules` row exists |
| `funded` | an execution has a `funding_completed_at` **and** an `alpaca_transfer_id` |

Each `pct_*` is against the **previous** step, which is what "where do they drop" means. A rate
whose denominator is zero reads `0.0`, never NULL; the counts beside it tell you whether that zero
means "nobody converted" or "nobody arrived".

```sql
SELECT cohort_week, signed_up, funded, pct_funded_of_signup, pct_kyc_of_passkey
FROM v_activation_funnel
WHERE cohort_week >= '2026-09-01'
ORDER BY cohort_week;
```

---

## v_time_to_funded

**The question:** how long does it take a cohort to get money in, and is that getting better?

**The definition:** days from `users.create_date` to the **first settled ACH transfer** (see the
note on "funded" above). A transfer timestamped before the signup is excluded as a data repair
rather than treated as a user who funded before they existed: left in, its negative day count
satisfies every `<=` bound and silently drags the median down. Users who
never funded are excluded from the medians entirely rather than counted as a large number: someone
who signed up yesterday is not evidence of a slow funnel. The `within_7d` / `14d` / `30d` shares
are of *everyone who signed up*, so they compare across cohorts of any size.

The median is the middle value, or the mean of the two middle values for an even count. MySQL has
no percentile function, so this is done by ranking.

**`p75` is nearest-rank and does not interpolate**, unlike the median beside it. At private-beta
cohort sizes that matters: with three or fewer funded users, `p75` is simply the slowest of them.
Read it as "the slow end" rather than as a smooth percentile until cohorts are bigger.

**The `within_7d`/`14d`/`30d` denominators are `signed_up`, not `funded`**, despite the column
names reading like a share of funded users. Share-of-cohort is the number worth tracking; the
counts beside it let you compute the other if you want it.

`median_days_to_funded` is **NULL when nobody in the cohort funded**, deliberately: an empty set
has no middle value, and a `0` there would read as "they funded the same day". The percentage
columns are `0.0` in that case, because "none of them funded within a week" is a real answer.

```sql
SELECT cohort_week, signed_up, funded, median_days_to_funded, p75_days_to_funded,
       pct_funded_within_7d, pct_funded_within_30d
FROM v_time_to_funded
ORDER BY cohort_week DESC
LIMIT 12;
```

---

## v_contribution_retention

**The question — the headline one:** do people keep contributing after the first time?

**The definition:** for each signup-week cohort, the share of **funded** users with at least one
settled contribution in month 1, 2, 3 and 6 after **their own** first transfer. A month is a 30-day
window from that user's first transfer, not a calendar month, so everyone is measured on the same
clock no matter when they joined.

```
month 1 = (first, first + 30 days]      month 3 = (first + 60, first + 90]
month 2 = (first + 30, first + 60]      month 6 = (first + 150, first + 180]
```

The first transfer sits at offset 0 and is **outside every window**: retention means coming back,
and counting the transfer that defines the cohort would report 100% in month one forever.

**The trap this view avoids, and why you can trust it.** A user who funded ten days ago cannot have
a month-6 contribution yet. Counting them in the denominator would drag every recent cohort toward
zero and look exactly like churn. So each month has **its own denominator**: only users whose
window has fully elapsed are eligible. `eligible_mN = 0` is how you know a cohort is simply too
young to answer, as distinct from one that churned.

Contributions are counted per **transfer**, not per execution row: one ACH can fund several
investments at once, and counting rows would turn one contribution into several.

```sql
SELECT cohort_week, funded_users,
       eligible_m1, retained_m1, pct_retained_m1,
       eligible_m3, retained_m3, pct_retained_m3,
       eligible_m6, retained_m6, pct_retained_m6
FROM v_contribution_retention
ORDER BY cohort_week;
```

Read `pct_retained_m3 = 0.0` with `eligible_m3 = 0` as "ask again later", not as a disaster.

---

## v_schedule_churn

**The question:** are recurring schedules accumulating or leaking?

**The definition, and an honest gap.** This view reports `schedules_created` per calendar month,
`active_at_month_end` and `schedules_existing_at_month_end`. It does **not** report paused, resumed
or cancelled, because the schema cannot answer those:

- `investment_schedules` holds **one current row per user** (unique on `user_id`) with a flag, not
  a history. Nothing records when a schedule was paused or how often.
- A schedule is **created paused on purpose** ("always start paused until ACH is confirmed"), so
  `is_paused = 1` mostly means "not funded yet", not "the user paused it".
- A cancellation is a row **delete**, which leaves nothing to count. Deletion also happens for an
  unrelated reason: a rejected KYC deletes the user's schedules along with the user.

Closing this needs either three new server events (`schedule_paused`, `schedule_resumed`,
`schedule_cancelled`) written to the outbox at the transitions, or a state-transition table. Both
are taxonomy decisions, not something to infer inside a view.

**`schedules_created` is rewritten by cancellation.** Because cancelling deletes the row, the
creation it represents disappears from its own creation month. The column therefore means "created
that month and not since cancelled", so a historical month's number can go down over time.

**One caveat on `active_at_month_end`:** it reads the *current* pause flag, so it is accurate for
the month in progress and approximate for history ("created by then and still unpaused today"). It
cannot reconstruct what the flag was in a past month. A consequence worth knowing: re-running the
same query next month gives a different history, so this series is not reproducible for past
months.

```sql
SELECT month_start, schedules_created, active_at_month_end, schedules_existing_at_month_end
FROM v_schedule_churn
ORDER BY month_start;
```

---

## v_kyc_outcomes

**The question:** how many applications are we losing at the broker, and how long do decisions take?

**The definition:** one row per calendar month, read from `analytics_outbox`. Submissions are
counted in the month they were submitted and decisions in the month they were decided, so the two
will not balance within a month — a submission late in a month is usually decided in the next one.
That is correct, not a bug.

`median_days_to_decision` is computed from the outbox's own `occurred_at` timestamps, pairing each
decision with that user's most recent prior submission. It is **not** derived from the banded day
property on the event, because a band cannot be turned back into a median.

Two deliberate inclusions: rows for **deleted users** still count (that is the entire reason this
view reads the outbox), and rows marked **SUPPRESSED** still count. A user who opted out of
analytics still had their KYC decided, and the compliance funnel must not develop holes because of
a marketing preference. Nothing identifying is exposed either way.

`other_outcome` catches a decision whose outcome is missing or is a value we have not seen before,
so that `approved + rejected + needs_more_info + other_outcome` always equals `decisions`. A
non-zero value there means the broker started sending something new.

`median_days_to_decision` is per **decision**, not per applicant: a user who gets
`needs_more_info` and is later approved contributes two decisions to the median.

```sql
SELECT month_start, submissions, decisions, approved, rejected, needs_more_info, other_outcome,
       median_days_to_decision
FROM v_kyc_outcomes
ORDER BY month_start;
```

---

## v_consent

**The question:** how much of PostHog is missing?

**The definition:** per signup week, the share of users with `analytics_opt_out = 1`.

**This is the CURRENT toggle, not consent during the window.** A user measured for three months who
opted out yesterday reads as opted out for their whole cohort, and the opposite is equally true.
For "how much of last quarter is missing" this will understate or overstate; it is accurate as a
statement about today. Consent history would need its own table.

It also counts every row in `users`, including staff and test accounts. The schema has a
`private_beta` flag that this view deliberately does not use, because excluding people would make
the denominator disagree with every other view here. This is what
turns "the funnel says 40%" into "the funnel says 40% of the 85% we can see". Consent defaults to
opted **in**, so a high opt-out share is a signal worth acting on rather than background noise.

```sql
SELECT cohort_week, users, opted_out, pct_opted_out
FROM v_consent
ORDER BY cohort_week DESC;
```

---

## v_outbox_reconciliation

**The question:** has our copy and PostHog's copy drifted, and is the publisher keeping up?

**The definition:** per week and per event name, how many rows we **wrote** and how many reached
the vendor, split by why the rest did not — `suppressed` (opted out, correct and expected),
`parked` (needs a human), `pending` (in flight, or the publisher is off or broken).

**Two delivery rates, and the difference matters.** `pct_of_sendable` excludes suppressed rows and
is the real health signal: an opted-out user is a row we were never going to send, and without
this a cohort with 20% opt-out reads as 80% delivery with nothing wrong at all. `pct_of_recorded`
keeps the raw share for anyone reconciling against the total. `other_count` exists so the buckets
always add up: a status outside the four known ones would otherwise sit in `recorded_count`,
depress the rate, and appear in no column.

`pending_count` is the number to watch day to day. If it grows week on week the publisher is not
draining, and every hour it stays up is latency on every number above.

### The weekly reconciliation

Run both halves for the same window and compare `sent_count` against PostHog's count.

**Ours:**

```sql
SELECT week_start, event_name, recorded_count, sent_count, suppressed_count,
       parked_count, pending_count, other_count, pct_of_sendable, pct_of_recorded
FROM v_outbox_reconciliation
WHERE week_start >= DATE_SUB(CURDATE(), INTERVAL 8 WEEK)
ORDER BY week_start DESC, event_name;
```

**Theirs**, as a PostHog HogQL query over the same window:

```sql
SELECT toStartOfWeek(timestamp) AS week_start,
       event,
       count() AS posthog_count,
       count(DISTINCT uuid) AS distinct_uuids
FROM events
WHERE event IN ('freedom_plan_created', 'passkey_created', 'kyc_submitted', 'plaid_linked',
                'investment_schedule_created', 'kyc_decided',
                'first_ach_transfer_completed', 'ach_transfer_completed')
  AND timestamp >= now() - INTERVAL 8 WEEK
GROUP BY week_start, event
ORDER BY week_start DESC, event;
```

**How to read a gap.** Both directions are real problems:

| Symptom | What it means | What to do |
|---|---|---|
| PostHog **lower** than `sent_count` | events we marked sent did not land, or the vendor dropped them | every row keeps its `event_id` uuid, so a resend is safe: set those rows back to `PENDING` and let the publisher retry. PostHog de-duplicates on the uuid. |
| PostHog **higher** than `sent_count` | something is emitting outside the outbox | a second source of truth has appeared; the two will disagree forever until it is removed. |
| `posthog_count` > `distinct_uuids` | the same event arrived more than once and was **not** de-duplicated | check that the publisher is still sending `uuid`; without it every retry double counts. |
| `pending_count` growing | the publisher is not draining | check `app.analytics.publisher.enabled`, the project key, and `last_error` on the pending rows. |

Expect `sent_count + suppressed_count` to be roughly `recorded_count` in a healthy week, with
`pending_count` near zero and `parked_count` exactly zero.

---

## The read-only role

`backend/src/main/resources/db/roles/metrics_reader_role.sql` creates `fred_metrics_reader`,
granted `SELECT` on these seven views and **nothing else**. It cannot read `users` or
`analytics_outbox`, so it cannot reach an email, a name, a `public_id` or an amount even in
principle.

It is **not** a Flyway migration, deliberately. Creating a role needs `CREATE ROLE` and
`GRANT OPTION`; the application's own database user would have to hold those for a migration to do
it, and an application that can mint database accounts is a worse problem than a manual step. A
managed MySQL may also refuse the statement, and a migration that cannot run is a deployment that
will not boot.

Run it once per environment as an admin, then grant the role to a person:

```sql
CREATE USER 'alice_metrics'@'%' IDENTIFIED BY '<a password you generate>';
GRANT 'fred_metrics_reader' TO 'alice_metrics'@'%';
SET DEFAULT ROLE 'fred_metrics_reader' TO 'alice_metrics'@'%';
```

Verified on a real database: the role reads every view and is denied on `users`,
`analytics_outbox`, `investment_executions`, `investment_schedules` and `passkey_credentials`.

---

## Changing a view

The views are **repeatable** Flyway migrations, `R__v_<name>.sql`, holding `CREATE OR REPLACE
VIEW`. Flyway re-runs one whenever its checksum changes, so editing the file and deploying is the
whole process.

Each view is self-contained and repeats its own cohort CTE rather than sharing a base view. That is
deliberate: Flyway orders repeatable migrations alphabetically, so a shared view would have to sort
before every consumer, and a rename could break the ordering in a way that only shows up on a fresh
database.

Every view has a test in `backend/src/test/java/com/investingapp/backend/metrics/` that seeds a
dataset with known answers and asserts the exact numbers. If you change a definition, change the
expected numbers with it; if the test still passes unchanged, the definition probably did not
actually change.
