# Paycheck Autopilot Spike (FRED-187): Recommendation

**Date:** 2026-09-12

**Status:** Superseded by 2026-10-19-paycheck-autopilot-design.md (kept as the origin of the locked decisions)

**Origin:** Andy asked whether Plaid can detect when the user is paid and trigger the investment with zero questions, including employer switches.

---

# Paycheck-triggered investing with Plaid: final recommendation

## 1. The answer

Yes, with limits. Plaid's Recurring Transactions add-on can identify the user's paycheck stream from bank history alone, so FRED can invest the night a paycheck actually posts instead of on a date the user typed. It is same-night, not same-second: Plaid checks each bank one to four times a day (some institutions only once), so the ACH goes out in the existing 11:59 PM batch that night. FRED needs two on-cadence deposits before it trusts any stream, so a brand-new job or bank costs one skipped paycheck. Zero questions in the normal case; a handful of rare confirmations are listed in section 3. Payroll-provider linking (Pinwheel, Atomic, Argyle) is rejected: all three still require the user to enter payroll credentials in an SDK, and a new employer always needs a fresh user session, even though Argyle can skip the employer search and refresh connected accounts on its own.

## 2. How detection works

**Data.** Link with `transactions` in `additional_consented_products` (consent collected, no extraction, no institution filtering, no billing until the first Transactions call). Later, one initializing `/transactions/sync` with `days_requested` (default 90, max 730, production floor 30; Plaid recommends at least 180 for Recurring; the value is locked once Transactions is added to the Item). Wait for `historical_update_complete` on `SYNC_UPDATES_AVAILABLE` (typically about a minute, sometimes two or more), then call `/transactions/recurring/get` across every account on the Item. `RECURRING_TRANSACTIONS_UPDATE` fires on new streams, new deposits, and attribute changes; it carries only `webhook_type`, `webhook_code`, `item_id`, `account_ids`, `environment`, so always re-read the streams.

**Hard gate (reject outright).** Not an inflow. Status TOMBSTONED or UNKNOWN. Frequency outside WEEKLY / BIWEEKLY / SEMI_MONTHLY / MONTHLY (reject 4-weekly rather than coercing it to MONTHLY). Non-depository account. Benefits and support income: Plaid categories for unemployment, pension, child support, disability, plus descriptor tokens UI BENEFIT, UNEMPLOY, EDD, TREAS 310, SSA, VA, DISAB, PFL, WORKERS COMP. Refunds, dividends, interest. Trailing median under roughly $400 to $600 per month (normalized to cadence, not a flat per-deposit floor; per-deposit floor about $50 so a tipped worker's small check cannot disqualify a healthy stream). Last deposit older than two and a half periods.

**Cadence.** Derive from observed spacing of the last six deposits; use Plaid's `frequency` only as a tiebreaker. Semi-monthly (alternating ~13/17 days, two day-of-month anchors) is routinely confused with biweekly (constant 14), and the 4.33 / 2.17 / 2 / 1 factors are load-bearing in four places, so an 8.5 percent error propagates into the Freedom estimate, MFU and Goals v2.

**Score (0 to 100).** Category up to 40 (v2 `INCOME_SALARY`; note v2 narrowed v1's `INCOME_WAGES`, so gig and contractor income now sit in separate enums and score lower). MATURE 20, EARLY_DETECTION 5. Amount tier 15. Cadence fit 10. Stability 10 as a **plateau**, not monotonic: penny-identical deposits score no higher than low-variance ones, and penny-identical round numbers take a small penalty, because fixed-dollar direct-deposit splits and standing transfers are the most stable inflows in any account. Payroll tokens +10 (ADP, GUSTO, PAYCHEX, WORKDAY, DFAS, PAYROLL, DIR DEP). Anti-tokens −25 (VENMO, ZELLE, PAYPAL, CASH APP, INSTANT PAY, CASHOUT, MOBILE DEPOSIT, ATM). Do **not** anti-token TRANSFER or XFER: many credit unions prefix every ACH credit that way. Use Plaid's `counterparties[].type = payment_app` for P2P instead of `TRANSFER_IN_TRANSFER_IN_FROM_APPS`, which the docs define only as "money transferred into the account from another application".

CONFIRMED = 70+ and MATURE. PROVISIONAL = 55 to 69, or an early stream with two on-cadence deposits. Nothing under 55 moves money. **No stream ever triggers an investment on a single deposit.**

**Same-payer rule (runs before joint-account):** when two streams share a payer token, the more frequent and more stable one is primary regardless of size, so a monthly commission run never displaces a semi-monthly base.

**Direct-deposit splits:** sum same-day inflows across accounts on the Item into one logical paycheck. If the total is implausibly small against the survey's stated income, drop to low confidence rather than sizing the contribution off one leg.

## 3. What the user experiences

Onboarding is unchanged up to the Plaid sheet, which now lists Transactions. "One last look" gains three states: *Finding your paycheck* (skeleton, up to 60 seconds, Authorize enabled throughout), *Found it* ("Every other Friday, from ACME PAYROLL, next expected Fri Sep 25"), *Still looking*. The recap shows detected cadence, per-paycheck amount, and resulting monthly total **next to what the survey said**, so a silent clamp never rewrites the Freedom Date behind the user's back. The Authorize tap is the single human check before the first pull.

If the historical pull shows no ACH credits at all (paper checks, cash, P2P), skip "Still looking" and present the manual schedule immediately with honest copy. Do not promise something that cannot happen and then wait 21 days.

Every question FRED may ever ask, and only these:
- "Which paycheck is yours?" (two mature salary streams, different payers, both 70+).
- "Is this your whole paycheck?" (detected pay implausibly small against survey income; usually a split deposit).
- "Your paycheck looked smaller than usual, want to adjust?" (after two consecutive reduced cycles).
- "Your paycheck went up, invest $X more?" (trailing median up 5 percent held for three deposits).
- "Prefer to set your own schedule?" (undetectable, or opt-out at any time).
- "Reconnect your bank so FRED can spot your paycheck" (users linked before Phase 0 only).
- "I'm between paychecks, check back in a month" (snooze on the interruption card).

Notifications: found-it, D-1 ("should land tomorrow", suppressed when date variance exceeds two business days), one skip notice per interruption (never per cycle), new paycheck source, cadence change, three-paycheck-month heads-up, low-balance skip. Fix the current MANUAL D-1 copy now: "FRED will invest $X tomorrow", not "Your paycheck hits tomorrow".

## 4. Employer switches

Same bank, new employer: the new stream is ignored at one deposit (indistinguishable from severance or a signing bonus). On the second on-cadence deposit, if it scores 55+ and the incumbent has missed a cycle, gone inactive, or been quiet for 1.5 periods, FRED switches and that deposit confirms a cycle immediately. Add a recency exception so a parallel severance run cannot hold the incumbent slot for two extra misses. Add **stream resurrection**: keep retired streams with payer token, cadence and anchors, and if a matching stream returns (teacher in August, leave ending, payroll processor drift), resume it with no warm-up and no "new employer" copy. If the new stream's trailing average is more than 20 to 25 percent below the old one, **downscale the contribution proportionally and say so**; never preserve the old monthly goal against a smaller paycheck. New bank goes through the existing change-bank flow, which needs the ACH cleanup fix in Phase 0.

## 5. Edge cases

| Scenario | FRED's behavior |
|---|---|
| Months of history at link | CONFIRMED in about a minute; ACH the night of next paycheck |
| Two weeks of history | Second deposit makes it PROVISIONAL and confirms a cycle |
| Benefits start after wage stream stops | Hard-rejected; income-interruption state, no pull, one notice, snooze offered |
| Paycheck smaller than usual (hours cut, garnishment, partial first check) | Refuse to confirm if amount exceeds 30 to 40 percent of **this** deposit; skip or pull reduced, tell the user |
| Paycheck much larger (OT, PTO payout, retro) | Matcher is asymmetric: accept at or above ~60 percent of trailing median, no upper bound |
| Bonus, reimbursement, second job offset | Needs max(0.6 period, 5 business days) since last confirmed **and** inside the predicted window **and** a payer-specific token match; hard cap one cycle per pay period |
| Payroll reversal or re-post | Process the `removed` array, void the pending cycle, re-verify the confirming transaction inside the 11:55 cron |
| Outage, two cycles confirmable at once | At most one execution per user per night; surplus recorded SKIPPED with a notice, never summed into one doubled ACH |
| Three-paycheck month (biweekly) | Say it in copy ("about 26 times a year"), advance heads-up, optional skip |
| Teacher summer, FMLA, strike | Dormancy state, not "searching": one notice, streaks preserved, resurrection on return |
| Semi-monthly on 15th and last business day | Store the second anchor semantically; add a backward business-day adjuster (payroll moves early, never late) |
| Joint account, two salaries | Pick the larger, store the other, one-tap chooser |
| Plaid Item breaks | Stop pulling, reconnect card after first miss |
| Paycheck lands in savings, FRED pulls checking | Detect Item-wide; confirm only if cached checking balance covers it |
| Webhook lost or late | 22:30 nightly re-read; unique transaction id makes replays harmless; worst case one night late |
| Deposit first seen more than 7 days after posting | Recorded, never confirms a cycle |
| KYC or ACH not yet approved, or schedule paused | Existing gates block confirmation; no catch-up pulls on resume |

## 6. Money safety and consent

Nothing new creates an ACH transfer. The 11:55 PM cron stays the only creator of `InvestmentExecution` rows and the 11:59 batch is untouched; only the eligibility test branches (DETECTED needs a confirmed, still-valid cycle; MANUAL is byte-for-byte today's). Never pull on a prediction. Verify every webhook: `Plaid-Verification` is an ES256 JWT, `kid` goes to `/webhook_verification_key/get` as `key_id`, reject `iat` older than 5 minutes, compare SHA-256 of the raw body to `request_body_sha256` in constant time (whitespace-sensitive, 2-space indentation). Answer 200 fast; Plaid retries for 24 hours on a non-200 or a 10-second timeout, backing off 4x from 30 seconds, and stops retrying if you reject more than 90 percent over 24 hours. Consent: Transactions in `additional_consented_products` is collected at Link time; for an already-linked Item without it, the Transactions call fails and the user must go through Link update mode. Billing is a per-Item monthly subscription for both Transactions and the Recurring add-on, on calendar months, not pro-rated, charged even for broken Items, ending only via `/item/remove`. Alpaca has no `ach_return` account flag and no liquidation-only state; a returned transfer shows status RETURNED with a `reason` field, which the existing FUNDING_FAILED path already handles.

## 7. What changes in the repo

- `PlaidService`: Transactions in `additional_consented_products`, webhook URL, update-mode link token, initializing sync, `/item/remove` on bank change. Retire the three endpoints that hand the access token to the client.
- New `PlaidWebhookController` at `POST /api/webhooks/plaid`, permitAll in `SecurityConfig`, ES256 verification, inbox table, async processing. Do not copy the RevenueCat webhook, which verifies nothing.
- New `PaycheckDetectionService` plus entities `PaycheckStream`, `PaycheckEvent` (unique Plaid transaction id), `PaycheckCycle`, `PlaidWebhookEvent`.
- `InvestmentSchedule`: `mode` (default MANUAL), detection state, stream id, cycle pointer queue, missed count, `amountSource`, `secondDayOfMonth`; `calculateNextInvestmentDate` honors it.
- `InvestmentExecutionService`: mode branch in the 11:55 eligibility test, per-night single-execution assertion, next date from the stream.
- Two jobs: 22:30 reconcile (stream refresh, miss detection, `removed` handling) and a 5-minute history poller.
- `PaydayNotificationService` and the D-1 repository query: gate on detection state, soften copy, suppress repeats. `StreakService`: dormancy keeps `hasActiveSchedules` true.
- `AccountStatusService` / `AlpacaApiService`: bank-change fix (delete old relationship, track cancellation on the `/v2/events/funding/status` SSE stream since REST never reports CANCELED and CANCEL_FAILED is a real branch, then create the new one and write the id to both `User` and the schedule).
- `User`: consent flag, sync cursor, historical-complete flag; legacy `payFrequency` / `nextInvestmentDate` become mirrors so FRED chat stops saying "monthly" to everyone.
- Frontend: three states on investmentconfirmation, linkplaid routes to it, `/recurring-investments` DETECTED mode, tab3 cards. Delete `plaid-data.service.ts` and `models/plaid/*`.
- Bump `plaid-java` to 45.0.0. `predicted_next_date` has existed since 28.0.0 and `days_requested` exists on `TransactionsSyncRequestOptions` and `LinkTokenTransactions`; 18.0.0 has neither, so a raw Retrofit shim is no longer worth it.

## 8. Rollout for a solo dev

**Phase 0, about 3 days, before beta, nothing visible.** Plaid product-access requests for Transactions and Recurring (US, Canada, UK only), get the per-Item price from the Production access form, confirm Production. Add `additional_consented_products` and the webhook URL to new link tokens so beta users never need a re-consent. Ship the ACH relationship cleanup fix (live bug today). Stand up the verified webhook endpoint, logging only.

**Phase 1, during beta, about 7 days, shadow mode, no money impact.** Sync, recurring client, scoring, tables, reconcile job, history poller. A dev-profile endpoint comparing each beta user's detected stream against what they typed, which is how the 55/70 thresholds and the gap rules get tuned. Pre-fill the existing schedule page when confident. Reconnect card for pre-Phase-0 users.

**Phase 2, after the beta stabilizes, about 8 days.** Schedule columns, cycle table, confirm-then-pull branch, both affordability gates, `removed` handling, catch-up cap, One last look states, route change, DETECTED mode on `/recurring-investments`.

**Phase 3, about 4 days.** Succession with recency and resurrection, joint-account chooser, split-deposit summing, semantic semi-monthly anchor, raise prompt, retire legacy endpoints.

Roughly 22 to 26 days total, only Phase 0 before the beta. Plaid approvals, pricing and LEGAL review of storing employer descriptors run in parallel and are the real schedule risk.

## 9. Open decisions

| # | Decision | Recommendation |
|---|---|---|
| 1 | Per-Item Transactions + Recurring price acceptable? | Get the quote before Phase 1 |
| 2 | Beta on Plaid Production or sandbox? | Production, or Phase 1 only proves plumbing |
| 3 | `days_requested` 180 or 365? Locked forever | 365 if the historical wait is tolerable |
| 4 | Keep the Authorize tap as the human check? | Keep it |
| 5 | Fixed dollars or percent of paycheck? | Fixed dollars, with the per-cycle affordability cap |
| 6 | Affordability cap: 30 or 40 percent of the deposit? | 35 percent, tune in shadow mode |
| 7 | Third paycheck in a month: invest or skip? | Invest by default, heads-up notification |
| 8 | Broken Item: stop pulling or fall back to cadence? | Stop pulling |
| 9 | Retiree on pension or Social Security: manual opt-in? | Yes, manual only |
| 10 | Why was the Sept 2025 detection branch deleted? | Find out before Phase 1 |

## 10. Sources

- Recurring endpoint, stream fields, statuses, webhook, `days_requested`, refresh limits: https://plaid.com/docs/api/products/transactions/
- Update cadence and Recurring add-on availability: https://plaid.com/docs/transactions/
- Initial and historical update timing: https://plaid.com/docs/transactions/webhooks/
- Consent without extraction or billing: https://plaid.com/docs/link/initializing-products/
- `transactions.days_requested`: https://plaid.com/docs/api/link/
- Webhook verification: https://plaid.com/docs/api/webhooks/webhook-verification/
- Retries: https://plaid.com/docs/api/webhooks/#webhook-retries
- Subscription billing: https://plaid.com/docs/account/billing/#subscription-fee
- Rate limits: https://plaid.com/docs/errors/rate-limit-exceeded/
- PFC v2 taxonomy: https://plaid.com/documents/pfc-taxonomy-all.csv and https://plaid.com/docs/transactions/pfc-migration/
- Alpaca ACH relationship create (409) and delete: https://docs.alpaca.markets/us/reference/createachrelationshipforaccount.md, https://docs.alpaca.markets/us/reference/deleteachrelationshipfromaccount.md
- Funding status SSE (BankRelationship and Transfer statuses): https://docs.alpaca.markets/us/reference/subscribetofundingstatussse.md
- Transfer statuses including RETURNED: https://docs.alpaca.markets/us/reference/createtransferforaccount.md
- Bank holidays and ACH: https://docs.alpaca.markets/us/docs/holiday-funding-processes.md
- plaid-java releases: https://github.com/plaid/plaid-java/blob/master/CHANGELOG.md
- Argyle Link parameters and ongoing refresh: https://docs.argyle.com/link/initialization/overview.md, https://docs.argyle.com/overview/ongoing-refresh
- Atomic Transact deeplink: https://docs.atomicfi.com/reference/transact-sdk

Unverified, do not cite as fact: the "1 to 4 business day" ACH settlement figure (Alpaca publishes no production settlement time, cut-off, or per-account ACH limit), Argyle's "90 percent of payroll accounts require MFA", and Plaid sandbox behavior of `user_transactions_dynamic`.