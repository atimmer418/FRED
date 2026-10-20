# Private beta QA checklist

Things that must be confirmed against the **first real beta account**, on the real deployment, before
more users are admitted. Everything here is something that cannot be proven by a test suite: it
needs a real person, real money, or a real third party.

Tick an item only when you have seen the evidence yourself. "The code looks right" is not a tick.

## Analytics: the four events no test can finish proving

These four are the money-and-compliance spine of the funnel. Each one is covered by unit tests and
by rollback tests that run against a real database, but none of them has ever been observed
end to end on a real account, because each needs a real broker submission to exist at all.

Before admitting a second beta user, confirm for the **first real beta account** that each of the
following appears **both** as a row in `analytics_outbox` **and** as an event in PostHog:

- [ ] `kyc_submitted` — written when the submission is persisted and sent to the broker. Check
      `attempt` is `1`.
- [ ] `kyc_decided` — written when the broker decides. Check `outcome` and that
      `days_to_decision_band` is not `unknown`, which would mean the submission stamp was missing.
- [ ] `first_ach_transfer_completed` — written when the user's **first** transfer settles, not when
      it is initiated.
- [ ] `ach_transfer_completed` — written for the **second** settled transfer, with
      `transfer_number_band`.

For each: the outbox row reaches `status = 'SENT'`, and the event is visible in PostHog with
`distinct_id` equal to that user's `public_id` and nothing identifying in the payload.

Why this is a gate rather than a nice-to-have: if any of these is silently missing, the funnel
under-reports activation and the retention maths is wrong from the first cohort onward, and no
amount of later analysis can reconstruct events that were never written. Finding it on one account
costs an afternoon; finding it on fifty costs the cohort.

Related: `FREDdocs/analytics-events.md` (the taxonomy and the outbox design).
