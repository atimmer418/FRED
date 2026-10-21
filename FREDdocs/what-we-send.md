# What we send, and where it goes

One page covering every piece of data FRED sends to a third party, what identifies it, what is
deliberately blunted, and how long it lives.

This is the document to hand to counsel and to an App Store reviewer. It is written to be read by
someone who has never seen the code, and it is kept honest mechanically: a check in CI fails the
build if the event list here and the event list in the code ever disagree.

**Last reviewed:** 2026-10-20. **Owner:** Andrew Timmer.

---

## The short version

- We send **product analytics to one destination, PostHog**, and nothing else today.
- We never send a **name, email address, phone number, postal address, date of birth, Social
  Security number or any part of one, bank account number, routing number, bank or institution
  name, or any free text the user typed**.
- We never send a **dollar amount**. Money is always replaced by a bucket such as `1k_2499`.
- The only identifier that leaves the system is an **opaque UUID** (`public_id`) that exists for
  no purpose other than analytics and cannot be used to look anyone up outside our database.
- A user can **turn it off** in Settings. Turning it off stops everything, on every device, and is
  honoured by the server as well as the app.

---

## Destinations

| Destination | What it receives | Where it is | Status |
|---|---|---|---|
| **PostHog** (US cloud) | product analytics events, described below | United States | **Live** |
| **Sentry** | crash and error reports | — | **Not integrated.** When it is, this document gets a row describing exactly what a crash report contains before any key is configured. |

Nothing else receives analytics. In particular we do **not** use advertising SDKs, attribution
SDKs, session replay, or any third-party tracker beyond the single destination above.

Requests to PostHog from the app go through **our own domain** (`m.fredvested.com`, a Cloudflare
Worker that forwards to PostHog). That exists so the analytics is not blocked by ad-block lists and
so cookies stay first-party. It does not change what is sent, and the Worker stores nothing.

---

## The identifier

Every event is attached to **`public_id`**, a random UUID (RFC 4122 version 4) generated when the
account is created.

- It is **not** the database row id, **not** the email address, **not** the broker account id, and
  **not** derived from any of them. It cannot be reversed into anything.
- The code **refuses to send anything that is not a UUID** as the identifier, so a mistake that
  substituted an email address would send nothing rather than leak it.
- Before a user has an account, events are attached to an anonymous id generated on the device and
  are joined to the person when they register.

### Person properties

A small set of facts is attached to the person record in PostHog, refreshed on each server event.
These are booleans and fixed words only:

| Property | Values |
|---|---|
| `tier` | `core`, `plus`, `pro`, or none |
| `kyc_status` | `not_started`, `pending`, `approved`, `rejected`, `action_required`, `other` |
| `has_linked_bank` | true / false |
| `has_schedule` | true / false |
| `has_funded` | true / false |

---

## What is sent from the device

These describe what someone did in the app. The full list with per-event properties is in
[analytics-events.md](analytics-events.md); the properties are summarised here by kind.

| Event | What it records |
|---|---|
| `app_first_opened`, `app_opened` | the app was launched, plus platform (`ios`/`web`) and build (`dev`/`prod`) |
| `onboarding_step_viewed`, `onboarding_step_completed` | which onboarding step was reached, and whether it was resumed |
| `plan_strategy_selected` | which of four drawdown strategies was chosen |
| `strategy_viewed` | an explainer deck was read, how far, and how it was closed |
| `freedom_plan_changed` | the plan was saved, with **banded** amounts and which controls moved |
| `goal_changed` | a goal was created, edited, completed, reopened or removed, with a **banded** target and an icon name |
| `setting_changed` | which setting, the new on/off state, and whether it saved |
| `monte_carlo_opened`, `_step_completed`, `_result_viewed`, `_acted` | use of the retirement simulator, with a **verdict word** rather than the percentage |
| `paywall_nudge_shown` | which locked feature was hit, and what the user did about it |
| `error_shown` | that an error appeared, as a **category** such as `network`, plus an HTTP status code |
| screen views | the name of the screen, with ids replaced (`/beneficiaries/edit/:id`) |

**What these deliberately do not contain:** the goal's name, the error message, the amount typed
into any field, the simulator's input figures, the success percentage, or any identifier in a URL.

---

## What is sent from our servers

These describe money moving and compliance decisions. The server sends them, not the phone,
because the phone can be wrong or absent.

| Event | What it records |
|---|---|
| `freedom_plan_created` | a plan was first saved, with **banded** amounts and the chosen strategy |
| `passkey_created` | a passkey was registered, and whether it was the first |
| `kyc_submitted` | an identity verification was submitted, and whether it was attempt 1, 2 or 3+ |
| `plaid_linked` | a bank was linked, and whether it was the first. **Never which bank.** |
| `investment_schedule_created` | a recurring schedule was set up, with a **banded** amount and frequency |
| `schedule_paused`, `schedule_resumed`, `schedule_cancelled` | a schedule stopped or restarted, and why |
| `kyc_decided` | the broker approved, rejected, or asked for more information, and a **banded** time taken |
| `first_ach_transfer_completed`, `ach_transfer_completed` | a bank transfer **settled**, with a **banded** amount |
| `trial_started`, `subscription_billed`, `subscription_cancelled` | defined but **not yet sent**: billing does not exist yet |

**What these deliberately do not contain:** the bank or institution name, any account or routing
number, the broker's account identifier, the transfer reference, the rejection reason, or any
dollar figure.

---

## Banding: why no number is ever exact

Every amount is replaced by a bucket before it is sent. A $2,400 contribution is sent as
`1k_2499`. The buckets are deliberately coarse at the top, where few people are and precision
would identify them.

> `under_50`, `50_99`, `100_249`, `250_499`, `500_999`, `1k_2499`, `2500_4999`, `5k_9999`,
> `10k_49k`, `50k_249k`, `250k_999k`, `1m_plus`

Counts, spans of years, spans of days and percentages are bucketed the same way. The app and the
server use **identical** buckets, enforced by a test that reads both definitions and fails if they
drift, so no combination of the two can narrow a value further than one bucket.

---

## Turning it off

Settings contains **"Share anonymous usage data"**, on by default.

Switching it off:

- stops the app sending anything immediately, and
- is stored **on our servers**, not on the device, so it applies on every device the person signs
  in on, and
- is checked by the server **immediately before** anything is sent, so events recorded before the
  change are never sent either.

Server-side events are still **written to our own database** when someone has opted out, and
marked as suppressed. They are never sent to PostHog. We keep them because they are our own record
of money moving and of compliance decisions, which we are required to be able to account for; they
are not used for product analytics and contain the same non-identifying fields described above.

There is no separate "do not sell" category because **we do not sell data and no destination
receives it for advertising**.

---

## Retention

| Where | How long | Notes |
|---|---|---|
| PostHog events | **1 year** | the retention on PostHog's free plan. Events older than a year are deleted by PostHog; our own `analytics_outbox` is what the long-run money and compliance history is rebuilt from. |
| PostHog person records | until the account is deleted or the person record is removed | deleting a person in PostHog removes the link between the UUID and their events |
| `analytics_outbox` (our database) | **indefinitely** | our own ledger of money and compliance events; rows are never deleted, which is deliberate, because it is what the funnel and the regulatory account are rebuilt from |
| AI chat insight records (our database) | 90 days | separate from product analytics; not sent to any third party |

**Session replay, log capture and tracing are switched off**, so there is nothing of that kind to
retain or disclose. The app explicitly disables session recording (it would otherwise film the KYC
form, which holds a Social Security number), and performance and log capture are off too. The only
thing PostHog holds is the event list above.

**Deletion.** When an account is closed, the user row and its personal data are deleted from our
database, **and the deletion propagates to PostHog**: a deletion request is recorded in the same
transaction as the closure, and a scheduled job then asks PostHog to delete that person record and
the events attached to it. The request is retried with backoff until PostHog confirms it, and if it
cannot be completed it is parked for a human rather than dropped. Because the request commits with
the closure, a closure that fails leaves no request behind, and a request that exists is one we
really owe.

The analytics ledger rows in our own database survive that deletion by design: they hold only the
opaque UUID and the non-identifying fields above, and they are what the money and compliance
history is rebuilt from.

A **rejected KYC application** also deletes the user row, and currently does **not** request a
PostHog deletion. See the open items.

---

## Open items before launch

These are the gaps in this document, listed rather than quietly omitted:

1. **Sentry is not integrated.** When it is, a row describing exactly what a crash report contains
   must be added here *before* any key is configured, since crash reports can carry more than
   intended by default.
2. **A rejected KYC application does not request a PostHog deletion**, only a closed account does.
   A rejected applicant's user row is deleted from our database, but their person record at the
   vendor remains. Whether a rejection should carry the same deletion obligation as a closure is a
   decision for counsel, and the mechanism is already built if the answer is yes: it is one call
   on that path.
3. **The deletion job needs a credential that is not yet provisioned.** It uses a PostHog
   *personal* API key with `person:read` and `person:write`, a far more powerful credential than
   the public project key the events use. Until it is set in the environment, deletion requests
   are recorded and retried but never complete, and they will sit visibly as PENDING rather than
   being silently marked done.

---

## How this document is kept true

`npm run test:events-documented` fails the build when the taxonomy in the code and the event list
in `analytics-events.md` disagree, in either direction, including each event's properties. This
document summarises that one by kind; if you are checking a specific property, that file is the
authority and the code is the authority over it.
