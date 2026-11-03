# Analytics: the roadmap, and what triggers each step

**Written 2026-11-02. Owner: Andrew Timmer.** Read this first if you are picking up anything to do
with analytics or metrics in FRED. It is the sequence, not the backlog: every other analytics
document tells you what exists, and this one tells you what happens next and what has to be true
before it can.

Assume no prior context. If you want the item-by-item account of what was built, deviated on and
deferred, that is [analytics-implementation-review.md](analytics-implementation-review.md). If you
want what leaves the building, that is [what-we-send.md](what-we-send.md).

---

## 1. Two analytics stacks. Only one of them is this repository's.

| | App analytics | Landing page analytics |
| --- | --- | --- |
| Where the code lives | **this repository** | a **separate** repository |
| Tool | PostHog | Plausible |
| Who works on it | sessions in this repo | handled elsewhere, not here |

**This repository never touches the landing page.** Do not read, write, commit, branch, stash or
push anything in the landing page repository, and do not send requests to any `lpapi` host, dev or
prod. That is a standing scope boundary, not a suggestion, and it holds even when a question would
be easier to answer by crossing it. If something seems to need the landing page, say so and stop.

**The one concept the two stacks share** is that a landing page signup and an app account are
matched **later, by email, on the server**. That matching never happens in PostHog and never in
Plausible. Neither tool is told about the other, no identifier is passed between them, and no
session should try to join them inside a vendor. The acquisition work in 3(d) is what would build
that server-side match, and it has not started.

---

## 2. Where things stand today

Checkpoints B through J are built, merged to `develop` and pushed. The client has a closed, typed
event taxonomy of 15 events whose properties are compile-time checked against a documented table;
the server emits 14 events through a transactional outbox, so an event commits with the state change
it describes or not at all. The dev PostHog project is **667874**, with four dashboards
(onboarding funnel, activation, contribution retention, data health) synced from `infra/posthog` so
they are code rather than clicks. The outbox publisher is **live on the dev Railway service** as of
2026-11-02, with both keys and the project id set. Seven MySQL views carry the metrics PostHog
structurally cannot answer. An outbound privacy guard runs on both the client and the server: a key
deny-list plus value-shape checks, with every rule held identical across the two languages by a test
that reads the TypeScript from Java. The iOS privacy manifest exists and is proven to reach the
bundle, but it **declares analytics only**, which is the largest single gap below.

---

## 3. The sequence from here

Each step says what has to be true before it starts. Nothing here is urgent except (a), and nothing
here should be started early just because it is possible.

### (a) Now, blocked on counsel

Two questions are with counsel. Neither is an engineering decision and neither should be guessed at
by a session.

1. **SSN retention for rejected applicants.** A rejected KYC applicant now keeps their user row,
   marked `REJECTED`, because an approval rate cannot be computed from users who were erased. That
   means keeping that row's identity data. **The SSN column is deliberately untouched** until there
   is a retention rule. Do not add a scrub, a delete, a re-encryption or an ageing job to it.
2. **The manifest purposes for KYC, auth and banking data.** The app collects a name, date of birth,
   Social Security number, address, email, phone and bank account details. **None of it is declared
   in the privacy manifest or the App Store label**, and Apple rejects a build whose manifest is
   incomplete. The analytics work declared what analytics sends and deliberately did not guess at
   the rest.

**When counsel answers:** a session adds the manifest and label entries.
[privacy-manifest-and-label.md](privacy-manifest-and-label.md) is the answer sheet: it lists every
missing data type, who owns it, what the label answers have to say, and the procedure for keeping
the manifest, the label and the policy in agreement. Edit the manifest at
`frontend/ios/App/App/PrivacyInfo.xcprivacy`, which is already registered as a bundle resource.

### (b) Beta week

**Trigger: the first TestFlight build goes to a real beta user.**

1. **Create a production PostHog project.** This requires moving off the free plan to
   pay-as-you-go. Dev data must not be mixed with real users' data in one project.
2. **Set the production Railway variables:** `ANALYTICS_PUBLISHER_ENABLED=true`,
   `POSTHOG_PROJECT_KEY` (the public `phc_` key), `POSTHOG_PROJECT_ID` and
   `POSTHOG_PERSONAL_API_KEY`. The publisher defaults to **false**, so without this the outbox fills
   and nothing is sent.
3. **Re-run the dashboard sync against the new project:**
   `POSTHOG_PERSONAL_API_KEY=phx_... node infra/posthog/sync-dashboards.mjs --project=<prod id>`.
   It matches dashboards by name, so it creates them in the new project rather than duplicating
   anything. The key needs exactly four scopes: `dashboard:read`, `dashboard:write`, `insight:read`,
   `insight:write`. `infra/posthog/README.md` explains why it needs no more than that.
4. **Confirm the four never-seen server events.** `kyc_submitted`, `kyc_decided`,
   `first_ach_transfer_completed` and `ach_transfer_completed` have unit and rollback coverage and
   have **never been observed on a real account**. Each must be seen **in the outbox table and in
   PostHog** from the first real beta account **before more users are admitted**. That gate is
   written into [beta-qa-checklist.md](beta-qa-checklist.md); check them off there.

Until step 4 passes, treat every funnel number involving money as unverified.

### (c) After real beta events are flowing

**Trigger: (b) step 4 passed, so there is real data to read.**

Add the PostHog MCP server to Claude Code so a session can read metrics directly instead of asking
Andy to screenshot a dashboard. Scope it to reads for insights, dashboards and events, plus
dashboard and insight **write**, and **no person write**: a session should be able to build a chart
and never able to alter or delete a person record.

**Do not do this before (b).** With no real events there is nothing to read, and a tool that returns
empty results teaches a session the wrong thing about the product.

### (d) Month 1 of beta

**Trigger: beta is running and (b) is done.** In this order unless Andy says otherwise. The order is
deliberate: the first item is what tells you the rest are broken.

1. **Alerts.** Nothing currently tells anyone that activation fell, that rejections spiked, or that
   the publisher has stopped. Add the four named conditions to `infra/posthog/sync-dashboards.mjs`
   so they are code like the dashboards rather than clicks that drift.
2. **Sentry.** Not integrated. A crash reporter collects crash data and often more by default, so
   **write its disclosure row into [what-we-send.md](what-we-send.md) before a key is configured**,
   not after. Key it to the same `public_id` the analytics identity uses.
3. **The transfer tables.** `deposit_failed`, `deposit_returned`, `withdrawal_requested` and
   `v_contribution_continuity` all need tables that do not exist: a `transfer` table with status
   history, a `contribution_attempt` table and a withdrawal record. The brief's own rule was to
   instrument nothing that has no data behind it, which is why these were not built.
4. **Acquisition attribution.** Needs a spec for the landing page's waitlist lookup first, which is
   a different repository and out of scope here, then FRED's half: `acquisition_*` columns on
   `users`, a short-timeout lookup at registration with a retry job, and the properties onto the
   person record. This is the work that would build the email match described in section 1.
5. **LTV/CAC.** **Deferred until paid acquisition starts.** With no ad spend there is no CAC, and a
   definition written now would be guesswork. When it starts it needs a recorded cost input from
   Andy and revenue from the billing session, which is a separate piece of work in a separate
   session.

### (e) Anytime

The **14 FRED-226 frontend spec failures**: eight `should create` smoke specs,
`FreedomDetailSheetComponent`, `SecuritySettingsPage`, the `TabsPage` pager, and two `swipe-settle`
velocity specs. They predate the analytics work, none of them is an analytics spec, and they are
documented so that a session can tell them apart from a failure it caused. Anything beyond those 14
is new.

---

## 4. Standing rules, so nobody has to relearn them

These were each decided once, usually after something went wrong. A session that breaks one has
almost certainly not understood the reason.

1. **No PII and no raw amounts to PostHog.** No name, email, phone, address, date of birth, Social
   Security number or part of one, no account or routing number, no institution or employer name, no
   free text, and **no raw dollar amount ever**. Every amount goes as a band (`under_50`,
   `1k_2499`, `1m_plus`); every date goes as a day band (`same_day`, `1_3`, ... `over_90`). The only
   identifier sent is the opaque analytics `public_id`.
2. **Band anything that can grow, and never raise the eight-digit threshold.** The value-shape guard
   strips any value containing a run of eight or more digits, because that is the shape of an
   unbanded SSN, an account number and a broker id. Every declared property was audited against its
   complete legitimate value domain and none is at risk, with the tables that prove it in
   `analytics-forbidden-payload.spec.ts` and `AnalyticsOutboxForbiddenPayloadTest`. If a new property
   could exceed seven digits, **band it**; widening the threshold would quietly reopen the hole.
3. **Declare a property in both taxonomies or it is stripped.** The guard exempts declared property
   names from the key deny-list and **nothing else**, which is why `amount_band` is allowed and
   `amount` is not. A new client property goes in `DECLARED_PROPERTY_KEYS`
   (`frontend/src/app/services/analytics/analytics-events.ts`); a new server property goes in
   `ServerAnalyticsEvent.PROPERTY_KEYS`. Both must also go into
   [analytics-events.md](analytics-events.md) in the same change, or
   `npm run test:events-documented` fails and names what is missing. **Declaring the name never
   exempts the value.**
4. **A schedule's `status` and its `hold_reason` are orthogonal, and neither ever writes the other.**
   `status` is `PENDING_BANK`, `ACTIVE` or `PAUSED_BY_USER` and belongs to the user and bank
   lifecycle. `hold_reason` is the system's, with a reason. Money moves only when the status is
   `ACTIVE` **and** no blocking hold is set, and that rule lives in
   `InvestmentSchedule.isReadyForInvestment(todayET)` and nowhere else: call it, do not restate it.
   `FREDdocs/handoff-paycheck-autopilot.md` is the full account.
5. **Never run `flyway repair` on dev.** The analytics migrations were renumbered behind Paycheck
   Autopilot's `V6`, so a database that applied them under the old numbers fails Flyway's checksum
   check at boot on versions 6 and 7. Repair rewrites the stored checksum to match the file, which
   would record a migration as applied when its tables were never created, leaving the schema
   silently short. The correct fix is two `DELETE` statements against
   `flyway_schema_history`, with a `mysqldump` first; they are in
   [flyway-baseline.md](flyway-baseline.md) and in section 5.1 of the Autopilot handoff. **`api-dev`
   may still need this repair**; nobody has been able to check it from a local session.
6. **Sessions never push without asking.** Commit freely, then ask. Never force-push, never rewrite
   history, never `git reset --hard`, and never `git checkout`, `restore` or `stash` on a dirty tree:
   concurrent sessions and Andy's own work-in-progress share this checkout. Andy fast-forwards
   `develop` himself, or tells you to.
7. **Analytics never blocks or breaks a user flow.** Every guard, every emit and every adapter call
   is written to fail quietly rather than throw into a screen. Do not add an analytics call that can
   fail a request, and do not open a transaction just for an event.
8. **Instrument only flows that exist, and tell Andy before adding an event.** Half of what is
   deferred above is deferred because the data behind it does not exist yet. An event with nothing
   behind it reads, in a document, exactly like one that works.

---

## Related reading

| If you want | Read |
| --- | --- |
| What was built, deviated on, deferred, and what is still owed, item by item | [analytics-implementation-review.md](analytics-implementation-review.md) |
| What leaves the building and what never does, written for counsel | [what-we-send.md](what-we-send.md) |
| Every event and property, and the table the build check pins the code to | [analytics-events.md](analytics-events.md) |
| The MySQL views, column by column, and the weekly reconciliation | [metrics.md](metrics.md) |
| The iOS privacy manifest and the App Store label answers | [privacy-manifest-and-label.md](privacy-manifest-and-label.md) |
| The dashboards as code, and the exact API key scopes | `infra/posthog/README.md` |
| What must be seen working on the first real beta account | [beta-qa-checklist.md](beta-qa-checklist.md) |
| Picking up Paycheck Autopilot, including everything analytics changed underneath it | [handoff-paycheck-autopilot.md](handoff-paycheck-autopilot.md) |
| Migration conventions, and the renumbering repair | [flyway-baseline.md](flyway-baseline.md) |
