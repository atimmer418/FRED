# Paycheck Autopilot (Plaid Paycheck Detection): Design

**Date:** 2026-10-19
**Status:** Approved by Andy 2026-10-19 (decisions in section 15); ready for an implementation plan
**Origin:** Andy: FRED should detect when the user is paid and invest off the real paycheck, with zero questions; spike FRED-187 (2026-09) chose Plaid Recurring Transactions; UI locked in FREDdocs/specs/paycheck-autopilot-mockup.html.

## 1. Overview and decision

### 1.1 Problem

The user types a frequency, date, and amount. The 11:55 PM ET cron pulls ACH on that date whether or not a paycheck arrived, so a late paycheck becomes an NSF return. The D-1 email claims "Your paycheck hits tomorrow" from the typed date alone. Employer or bank changes break the schedule silently. Nothing records what the user authorized.

### 1.2 Decision

After the bank link, FRED reads transaction history through Plaid (Transactions plus the Recurring Transactions add-on), finds the primary paycheck stream with zero questions, pre-fills the per-paycheck amount from the survey monthly goal, and asks for one "Looks good" tap and one authenticated "Authorize" tap. On the ET day FRED first sees a POSTED paycheck, the existing 11:55 cron queues one `InvestmentExecution` that night and the untouched 11:59 batch sends the ACH; the investment executes once the ACH clears (1 to 3 business days). Employer switches are followed automatically. The manual page stays as the fallback behind "Not exactly right? Set manually". Plaid "typically checks for new transactions data between one and four times per day, depending on the institution", at no published time, so a deposit that posts after that day's check is pulled the next night; "same night" is FRED's design, not a Plaid guarantee.

Locked: (1) Plaid Transactions plus Recurring; payroll-provider vendors rejected. (2) Trigger only on a posted deposit (`pending=false`) matched to a trusted stream; never a prediction, a pending row, or Plaid's `predicted_next_date`. (3) Two on-cadence deposits before any stream is trusted. (4) The 11:55 cron is the only creator of `InvestmentExecution` rows; the 11:59 batch and its marker `Queued for end-of-day batch processing` are untouched (two mode-neutral hardening changes need Andy's OK, 5.5). (5) One `InvestmentSchedule` row per user with `mode` MANUAL or DETECTED; MANUAL eligibility stays byte-for-byte. (6) Cadence maps onto WEEKLY / BIWEEKLY / SEMI_MONTHLY / MONTHLY with factors 4.33 / 2.17 / 2 / 1 from one `InvestmentFrequency` enum (4.4); anything else is rejected, never coerced. (7) Fixed-amount-or-skip. (8) Section 7's Reg E / E-SIGN requirements are hard.

### 1.3 Locked UI

Screens 1 and 2 plus the pre-detection variant are locked to the mockup, copied to `FREDdocs/specs/paycheck-autopilot-mockup.html` in this spec's commit (hosted: https://claude.ai/artifact/DiQa1ZZGKLeJnUtJGGifyd); section 9 maps every element. The mockup was revised on 2026-10-19 after Andy's review: the How it works panel now has four rows (paycheck lands; that night FRED starts the transfer, it clears in 1 to 3 business days and is then invested; no paycheck, no transfer; new job: the first paycheck from a new employer is skipped while FRED confirms it, from the second on FRED invests automatically). Exactly three copy additions beyond the mockup: the capped-amount tip (4.9), the 90-second line "Still looking. You can set it manually any time.", and the manual-form subtitle "We haven't spotted a direct deposit yet, so let's set this up together." (9.2). The mockup's "usually takes under a minute" rests only on Plaid's legacy-webhook text ("HISTORICAL_UPDATE within 1 minute, although these webhooks may take 2 minutes or more"); Plaid gives no bound for the sync flow, so the 90-second and 5-minute switches are UX choices, never failures.

### 1.4 Repo corrections to the brief

Schema is Flyway-owned (`ddl-auto=validate` everywhere), so everything ships in `V6__paycheck_autopilot.sql`. The RevenueCat webhook is gone; two dead `/api/plaid/*_anonymous` permitAll paths remain. `BrokerEnvironmentGuard` already refuses prod on `plaid.env=sandbox`, yet `application-prod.properties` still hard-codes it and the committed sandbox secret; Phase 0 moves that block to environment variables and rotates the secret. Scheduler pool 4 threads, Hikari 4 connections, no ShedLock, one unversioned encryption key, `investment.scheduler.enabled` read by nothing. Commit 90a8cc1 ("removed unused files") deleted the 2025 `PaycheckDetectionService` with no stated reason; `git show 90a8cc1^:<path>` recovers it (closes decision 21).

## 2. Plaid integration

### 2.1 SDK bump, gateway, error classes

`plaid-java` 18.0.0 to 46.0.0 in its own commit first (`ApiClient` keeps only `Production` and `Sandbox`, so the `development` branch in `PlaidConfig` goes; the ci build is the compile gate). `PlaidGateway` wraps every call on a bounded `plaidExecutor` (2 to 4 threads, never the shared scheduler, never inside a DB transaction), logs `request_id`, maps a non-2xx body to `PlaidApiException{errorType, errorCode, requestId, httpStatus}`, and classifies on the exact `error_code`, never the HTTP status or `error_type`; five consecutive TRANSIENT or RATE_LIMIT results abort a run and raise `plaid.outage`.

| Class | Codes | Handling |
|---|---|---|
| TRANSIENT | INSTITUTION_DOWN, INSTITUTION_NOT_RESPONDING, INSTITUTION_NOT_AVAILABLE (all HTTP 400), INTERNAL_SERVER_ERROR, PLANNED_MAINTENANCE, PRODUCT_NOT_READY | re-queue once after 5 minutes, then the nightly reconcile |
| RATE_LIMIT | any RATE_LIMIT_EXCEEDED code | retry next minute |
| CONSENT | ADDITIONAL_CONSENT_REQUIRED, ACCESS_NOT_GRANTED | consent flag 0, NEEDS_CONSENT, no retry until update mode |
| ITEM_BROKEN | ITEM_LOGIN_REQUIRED, ITEM_NOT_FOUND, USER_PERMISSION_REVOKED, NO_ACCOUNTS | Item-state handler (5.7) |
| NOT_SUPPORTED | PRODUCTS_NOT_SUPPORTED, ITEM_NOT_SUPPORTED, INVALID_PRODUCT | `plaid_item_status=NOT_SUPPORTED`, UNDETECTABLE, no hold, no reconnect card, off the reconcile list |
| SYNC_LIMIT | TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED | never retried; `hold_reason=MANUAL_REVIEW`, UNDETECTABLE, alert |
| PERMANENT | everything else, including other INSTITUTION_* codes | stop, log |

### 2.2 Link token for new Items

Sends `user.client_user_id` (FRED user id), `client_name`, `country_codes:["US"]`, `language:"en"`, `products:["auth","identity"]`, `additional_consented_products:["transactions"]`, `webhook`, `link_customization_name` (DTM-enabled, paycheck-detection use case), `redirect_uri`. `additional_consented_products` collects the DTM scope (it covers Recurring) with no extraction, no institution filtering and no billing until the first Transactions call; it does not initialize Transactions. FRED's first successful `/transactions/sync` initializes it, and that call's `options.days_requested` fixes the history depth for the Item's life; a link-token `transactions.days_requested` is not documented to carry over and is not sent. `redirect_uri` is required on every token, update mode included ("OAuth support is required in all Plaid integrations that connect to financial institutions in the US"), and "Using webviews to present Link is deprecated"; `linkplaid.component.ts` runs the web SDK inside the Capacitor WKWebView, so decision 1 (universal link plus `receivedRedirectUri`, native LinkKit, or Hosted Link) is a Phase 0 gate.

### 2.3 Existing AUTH+IDENTITY Items: consent and update mode

An Item linked through a DTM customization with only `auth` and `identity` lacks the Transactions scope; its first sync returns HTTP 400 `INVALID_INPUT / ADDITIONAL_CONSENT_REQUIRED`. Pre-DTM Items are not gated, and `/item/get consented_products` lists a product only when FRED has Production access for it, so `plaid_transactions_consented` is tri-state: 1 when `transactions` is listed; NULL when absent (one sync is allowed and is the authoritative test); 0 after ADDITIONAL_CONSENT_REQUIRED or ACCESS_NOT_GRANTED.

One contract: `POST /api/plaid/create_link_token { "mode": "link" | "update" }`. `update` sends `{user, client_name, country_codes, language, access_token, link_customization_name, redirect_uri}`, NO `products`, plus `additional_consented_products:["transactions"]` whenever the flag is not 1. On `onSuccess` the client calls `POST /api/plaid/update-mode-complete` (no exchange; the token never changes); the server re-reads `consented_products` (a user can revoke scopes inside update mode), calls `/item/webhook/update {access_token, webhook}` (the link-token `webhook` "will not have an effect" in update mode) and expects `WEBHOOK_UPDATE_ACKNOWLEDGED`, then runs 2.4 step 6 when consent is new, else the LOGIN_REPAIRED path of 5.7. This call is the only success signal; it resets `consent_expiration_time`, clearing a PENDING_DISCONNECT. After USER_PERMISSION_REVOKED update mode "may not be possible" to launch; a failed token creation falls through to `/item/remove` plus a fresh `link`. Phase 0 backfills `/item/webhook/update` for every existing token.

### 2.4 Exchange additions (`exchangePublicTokenAndLinkUser`)

Serialized per user with a row lock; a second exchange within 60 seconds returns 409 `LINK_IN_PROGRESS`. In the transaction: (1) if a token exists, insert a `plaid_item_removals` row for the old Item and copy `alpacaAchRelationshipId` into `users.previous_ach_relationship_id` BEFORE line 114 nulls it. (2) Store the account `mask`. (3) Purge streams, events, cycles; null `plaid_sync_cursor`; `plaid_history_complete=0`. (4) Upsert `InvestmentSchedule`: when `paycheck.autopilot.enabled=true` and `shadow=false`, `mode=DETECTED`, `detection_state=SEARCHING`, `hold_reason=AWAITING_AUTHORIZATION`, placeholders `frequency=BIWEEKLY`, amounts 0, `next_investment_date=today+14`, `detection_started_at=now`; `is_paused` is not written. In shadow mode the row stays MANUAL and detection runs against it. MANUAL relinkers keep MANUAL and get `hold_reason=BANK_CHANGE` (5.7). (5) Enqueue the Alpaca relationship DELETE on the removal drainer (no Alpaca call inside the transaction). After commit (`TransactionSynchronization.afterCommit`): (6) `/item/get` to store `plaid_item_id`, `plaid_transactions_consented`, `plaid_item_linked_at`. The first `syncItem(userId, itemId, INIT)` is NOT run here: it runs when screen 1 first polls `GET /api/paycheck-detection/status` (8.1), because the first sync starts the per-Item Transactions subscription (UTC calendar months, not pro-rated, charged even for errored Items) and running it at exchange would bill users who abandon onboarding between the bank link and screen 1 (decision 7, resolved: screen 1).

### 2.5 Initializing sync, cursor protocol, pagination

`PlaidTransactionsSyncService.syncItem(userId, itemId, reason)` (INIT, WEBHOOK, RECONCILE, MANUAL) is the only caller. (1) Refuse if consent is 0, if `itemId != users.plaid_item_id` (stale call after a relink: log, no writes), or if `plaid_item_status` is not OK or PENDING_DISCONNECT. (2) `originalCursor = plaid_sync_cursor`. (3) Loop `{access_token, cursor, count: 500, options: {include_original_description: true, personal_finance_category_version: "v2", days_requested: 365}}`; `days_requested` goes on EVERY request on every path, because whichever call succeeds first initializes Transactions and locks the depth (decision 2; Plaid recommends at least 180 for Recurring). `TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION`: discard the buffer and restart from `originalCursor` (the first page of this update, not an empty cursor); after 3 restarts raise `SyncRetryLater`. Else buffer `added`, `modified`, `removed`, `accounts`, record `transactions_update_status`, continue while `has_more` (a pending-to-posted pair is "not guaranteed to be in the same page"). (4) Re-read `users.plaid_item_id`; if it changed, drop the buffer. Apply the buffer in ONE DB transaction (4.8), then persist `plaid_sync_cursor`, `plaid_last_sync_at`, `plaid_transactions_update_status`; an empty `next_cursor` means not initialized, persist nothing; never persist mid-pagination. (5) `plaid_history_complete = (transactions_update_status == HISTORICAL_UPDATE_COMPLETE)`; on the flip run `readStreams` then `evaluate(FULL)`, else `evaluate(INCREMENTAL)`. The first call on a fresh Item returns empty arrays but starts the historical pull and arms `SYNC_UPDATES_AVAILABLE`; `transactions_update_status` carries the same readiness, so screen 1's poll never depends on webhook delivery. Never scope by `options.account_id`.

### 2.6 Recurring streams

Needs dashboard product access. `PlaidRecurringService.readStreams(userId, itemId)` sends `{access_token, options: {personal_finance_category_version: "v2"}}`, no `account_ids`, reads `inflow_streams` only (inflow comes from the array, never from the sign of `average_amount.amount`), stores the raw object and the 3.3 `plaid_*` columns, and stamps `users.plaid_streams_read_at`. An empty `inflow_streams` is a valid read and never contributes to UNDETECTABLE. Called at history completion, on every `RECURRING_TRANSACTIONS_UPDATE` (payload `item_id`, `account_ids`, `environment`, no stream data, so re-read and diff on `stream_id`, `transaction_ids`, `last_date`, `status`; bursts coalesce per Item), and in the reconcile when the read is older than 24 hours. Plaid statuses: MATURE ("should have at least 3 transactions and happen on a regular cadence"), EARLY_DETECTION, TOMBSTONED, UNKNOWN; `frequency` may be UNKNOWN or ANNUALLY. Streams are a catalog and tiebreaker only.

### 2.7 `/transactions/refresh`

Not used at launch: a separate per-request add-on with its own product access. Per-client Production limits read 2026-10-19 are 100 per minute, 18,000 per hour, 432,000 per day; the per-Item figures quoted earlier (2 per minute, 120 per hour, 2,880 per day) were not on the page and are unverified. Phase 3 may add one call at 20:00 ET on `next_expected_date` behind `paycheck.autopilot.refresh.enabled` (default false). Never a correctness dependency.

### 2.8 Webhooks

`POST /api/plaid/webhook`, `permitAll`. `PlaidWebhookVerificationFilter` is a `FilterRegistrationBean` on that path at `HIGHEST_PRECEDENCE + 10`, not profile gated. It wraps the request in a custom `HttpServletRequestWrapper` that buffers the body once and serves a fresh `ByteArrayInputStream` on every read; Spring's `ContentCachingRequestWrapper` is not re-readable and would leave the controller an empty body, a 400, and a permanently dropped webhook. The controller reads the verified bytes and JWT `iat` from request attributes, never `@RequestBody`. 401 when `Plaid-Verification` is missing, JWT `alg != "ES256"`, the signature fails under the JWK fetched for `kid` (sent as `key_id` to `/webhook_verification_key/get`; Caffeine, 1-hour TTL; `ECPublicKey` built on `secp256r1` from `x`, `y` because jjwt 0.11.5 has no JWK parser), `iat` is older than 5 minutes, or the SHA-256 of the raw bytes differs from `request_body_sha256` (constant-time). A signature from an expired key is accepted when `iat < expired_at`; `expired_at` only evicts the cache entry. Plaid says "reject the webhook" without naming a status; 401 is FRED's choice because any 4xx other than 408, 425, 429 is permanent. Every other failure (key fetch, Plaid 5xx, the ci stub, JSON mapping, DB insert) returns 503 so Plaid retries (up to 24 hours, 30 seconds then 4x backoff). The ci bypass verifier is a `@Profile("ci")` bean that `BrokerEnvironmentGuard` refuses in prod, as it refuses a non-https or tunnel `plaid.webhook.url`.

`PlaidWebhookController` resolves `user_id` via `findByPlaidItemId`, inserts `plaid_webhook_events` with `dedupe_key = sha256(raw body || ":" || jwt iat)` as RECEIVED, and returns 200 within Plaid's 10 seconds. A Plaid retry carries a fresh `iat` and is a new row (harmless: every handler is idempotent); a body-only key would wrongly drop the byte-identical daily `SYNC_UPDATES_AVAILABLE`. Unknown or removed `item_id`, or `environment != plaid.env`: stored IGNORED, answered 200. `PlaidWebhookProcessor` (`fixedDelay = 15s`) claims rows with `SELECT ... FOR UPDATE SKIP LOCKED` in batches of 50, up to 4 Items concurrently on the Plaid executor, serialized per `item_id`, SYNC and RECURRING coalesced per Item, 3 attempts then ops replay (section 12). Item-state writes apply only when the event's `item_id == users.plaid_item_id`. `ITEM_LOGIN_REQUIRED` is `error.error_code` inside the `ERROR` webhook, never a `webhook_code`; every ITEM webhook carries `item_id`, `user_id`, `environment`.

| Webhook | Action |
|---|---|
| `SYNC_UPDATES_AVAILABLE` | `syncItem(WEBHOOK)`; first `historical_update_complete=true`: streams read plus FULL evaluation, else INCREMENTAL |
| `RECURRING_TRANSACTIONS_UPDATE` | `readStreams`, then `evaluate(STREAMS_CHANGED)` (section 6) |
| `INITIAL_UPDATE`, `HISTORICAL_UPDATE`, `DEFAULT_UPDATE`, `TRANSACTIONS_REMOVED` | PROCESSED, no action (legacy, still sent) |
| ITEM `ERROR` (with `error_code_reason`) | Item-state handler (5.7); codes other than ITEM_LOGIN_REQUIRED the same plus WARN |
| `LOGIN_REPAIRED` (fires only when the Item exits ITEM_LOGIN_REQUIRED outside FRED's update mode) | only from `plaid_item_status=LOGIN_REQUIRED`: Item OK, reconnect card cleared, sync, INCREMENTAL, no catch-up; otherwise PROCESSED with WARN, no writes |
| `PENDING_DISCONNECT` (7 days ahead; `reason`, `disconnect_time`), `PENDING_EXPIRATION` | `plaid_disconnect_at`, reconnect card, no hold until an error arrives |
| `USER_PERMISSION_REVOKED` ("may be fired"; not guaranteed when revoked at the institution) | Item REVOKED, purge streams, events, cycles, descriptors, hold, revoke authorization (7.6), enqueue `/item/remove` |
| `USER_ACCOUNT_REVOKED` (`account_id`; Chase, PNC, Truist) | funding account: as above; another account: drop that account's events only |
| `WEBHOOK_UPDATE_ACKNOWLEDGED`; anything else | `plaid_webhook_acked_at`; PROCESSED, logged |

`/beta/webhook_events/list` (beta, 7 days) excludes TRANSACTIONS webhooks; ITEM payloads are re-processed from it, and a lost SYNC or RECURRING webhook is recovered by the 22:30 reconcile's cursor sync.

### 2.9 Item removal, bank change, account deletion

Transactions and Recurring bill per Item until `/item/remove`, which nothing calls today; depermission at the institution "is not guaranteed" to end billing, so FRED always calls `/item/remove` itself. `PlaidItemLifecycleService` drains `plaid_item_removals` and the queued Alpaca deletes (`ITEM_NOT_FOUND` counts as done; other errors retry every 15 minutes for 7 days, then alert); later inbox events for a removed `item_id` are IGNORED.

Bank change (lifecycle in 7.8): `resetForBankChange` sets `hold_reason=BANK_CHANGE`, revokes the authorization, purges streams and events, SEARCHING, `is_paused` untouched; every QUEUED / APPROVED / PENDING Alpaca relationship is DELETEd (deferred while any `FUNDING_INITIATED` execution exists), `alpacaAchStatus=CANCEL_REQUESTED`, and the 15-minute poll creates the new one; `handleBankAccountChange` no longer calls `attemptAchCreation` synchronously. Two re-adoption paths close, not one: the branches at `AccountStatusService:220-254` are deleted, AND the 409 branch in `AlpacaApiService.createAchRelationship:453-470` (today it returns an existing APPROVED or QUEUED id as if newly created) returns an error. `attemptAchCreation` first lists `ach_relationships?statuses=QUEUED,APPROVED,PENDING,CANCEL_REQUESTED` and waits unless the list is empty or holds only `previous_ach_relationship_id`. `AchRelationshipService.adopt` is the single writer of `User.alpacaAchRelationshipId`, `alpacaAchStatus` and `schedule.achRequestId`; it refuses `previous_ach_relationship_id` and any relationship whose mask does not match `plaid_account_mask` or whose `created_at` precedes `plaid_item_linked_at` (`hold_reason=MANUAL_REVIEW`, alert), and never unpauses under a hold or a user pause. Alpaca's REST relationship enum has no CANCELED, so the poll waits for absence; CANCEL_REQUESTED older than 24 hours is flagged for a person. `POST /api/investment-schedule/update-ach-request-id` is deleted. Account deletion and Alpaca rejection share `UserDeletionService.delete(user, reason)` (7.9), which enqueues `/item/remove` and anonymizes the authorization row (3.7).

### 2.10 Sandbox versus production

Prod reads `PLAID_CLIENT_ID`, `PLAID_SECRET_PRODUCTION`, `PLAID_WEBHOOK_URL`, `PLAID_LINK_CUSTOMIZATION`, `PLAID_REDIRECT_URI` from the environment with `plaid.env=production`; non-prod profiles require `plaid.env=sandbox` and a public HTTPS receiver (the Cloudflare tunnel locally). Read 2026-10-19: `/sandbox/transactions/create` works on any Sandbox Item, up to 10 per call, `date_posted` today or up to 730 days back, never future, so it always creates posted rows; a pending deposit needs a custom-user fixture with a future `date_posted` and `roll_dates_forward:false`; `/sandbox/public_token/create` has no `additional_consented_products` option, so the DTM consent error, update-mode UI and first-sync initialization are proven on a Trial or Production Item (13.3); `/sandbox/item/reset_login` itself fires an ITEM `ERROR` webhook; Sandbox Items break after 30 days; whether streams are computed for custom users is unverified. Alpaca sandbox has no documented way to produce a RETURNED transfer, and the content of `reason` on a production return is undocumented (7.7).

### 2.11 Fields read

From `/transactions/sync`: `transaction_id`, `pending_transaction_id` (nullable, never required), `pending`, `date`, `authorized_date`, `amount` (positive out, negative in), `original_description`, `name`, `merchant_name`, `counterparties[].{name, type, confidence_level}`, `personal_finance_category.{primary, detailed, confidence_level}`, `payment_channel`, `payment_meta.ppd_id`, `account_id`; from `accounts[]`: `type`, `subtype`, `mask`. From `/item/get`: `consented_products`, `consent_expiration_time`, `webhook`, `error.{error_code, error_code_reason}`, `billed_products`, `status.transactions.last_successful_update`. From `/transactions/recurring/get`: `inflow_streams[].{stream_id, account_id, description, merchant_name, frequency, status, is_active, first_date, last_date, predicted_next_date, transaction_ids, average_amount, last_amount, personal_finance_category}`.

## 3. Data model

All DDL ships in `V6__paycheck_autopilot.sql`. MySQL has no `ADD COLUMN IF NOT EXISTS`, so every ALTER on an existing table uses V3's `information_schema` plus `PREPARE` guard; new tables use `CREATE TABLE IF NOT EXISTS`. Under `ddl-auto=validate` every boolean entity field declares `columnDefinition = "BOOLEAN DEFAULT FALSE"` (the `tlh_enabled` convention; a bare `Boolean` maps to `bit(1)` and fails validation) and every enum column is `@Enumerated(STRING)`. Notation: NULL unless NN; `D x` = default; `money` = DECIMAL(12,2); `ts` = DATETIME(6); Plaid ids VARCHAR(80). Encrypted columns carry `enc.v{n}:` (legacy `ENC:` = key 1) so keys can rotate (section 12). `user_id` foreign keys are `ON DELETE CASCADE` unless noted, so `UserDeletionService` can delete the user row.

### 3.1 `users` additions

`plaid_account_mask VARCHAR(4)`; `plaid_sync_cursor TEXT`; `plaid_history_complete TINYINT(1) NN D 0`; `plaid_transactions_update_status VARCHAR(40)`; `plaid_transactions_consented TINYINT(1)` (NULL unknown, 1 yes, 0 refused; 2.3); `plaid_item_status VARCHAR(24) NN D 'OK'` (OK, LOGIN_REQUIRED, PENDING_DISCONNECT, REVOKED, REMOVED, SYNC_LIMIT, NOT_SUPPORTED); `plaid_item_error_code VARCHAR(64)`; `plaid_disconnect_at`, `plaid_webhook_acked_at`, `plaid_last_sync_at`, `plaid_streams_read_at`, `plaid_item_linked_at ts`; `previous_ach_relationship_id VARCHAR(512)` (encrypted); `paycheck_autopilot_opt_out TINYINT(1) NN D 0` (written only when the user rejects a found paycheck, 5.9). Index on `plaid_item_id`. `User.monthlyInvestment` is the survey goal and is never written by this pipeline (4.1).

### 3.2 `investment_schedules` additions

`mode VARCHAR(10) NN D 'MANUAL'`; `detection_state VARCHAR(16)` (SEARCHING, NEEDS_CONSENT, UNDETECTABLE, PROVISIONAL, CONFIRMED, DORMANT, ITEM_BROKEN, REVOKED; null in MANUAL); `hold_reason VARCHAR(32)` (AWAITING_AUTHORIZATION, BANK_CHANGE, ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED, ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, DORMANT, KILL_SWITCH, MANUAL_REVIEW; null = no system hold); `paused_by_user_at ts` (set by `pauseSchedule`, cleared by `resumeSchedule`; the record of a user pause, 5.1); `primary_stream_id BIGINT FK ON DELETE SET NULL`; `authorization_id BIGINT FK ON DELETE SET NULL`; `authorized_amount money` (invariant `investment_amount <= authorized_amount`); `amount_source VARCHAR(20)` (GOAL_DERIVED, USER_EDITED, DOWNSCALED); `next_expected_date DATE` (display and D-1 only, never a trigger); `first_anchor`, `second_anchor VARCHAR(8)` (day number or 'LAST_BD'); `missed_count SMALLINT NN D 0`; `dormant_since DATE`; `snoozed_until DATE`; `last_cadence_check_at`, `detection_started_at ts`. `frequency` validation gains SEMI_MONTHLY. `is_paused` stays the user's stop and `hold_reason` the system's; writers are listed in 5.1.

### 3.3 `paycheck_streams`

One row per inflow stream per user, keyed by FRED on payer token plus account because Plaid's `stream_id` is unstable. `id BIGINT PK`; `user_id BIGINT NN FK`; `plaid_item_id`, `plaid_account_id NN`; `plaid_stream_id`; `payer_token VARCHAR(64) NN`; `payer_display_name VARCHAR(120) NN`; `raw_descriptor VARCHAR(255)` (nulled on Item break); `state VARCHAR(12) NN` (CANDIDATE, PROVISIONAL, CONFIRMED, DORMANT, RETIRED, REJECTED); `role VARCHAR(12) NN` (PRIMARY, SECONDARY, INTERRUPTION, NONE); `cadence VARCHAR(12)`; `cadence_source VARCHAR(10)` (OBSERVED, PLAID); `anchor_dow TINYINT`; `anchor_parity_date DATE`; `anchor_day_1`, `anchor_day_2 VARCHAR(8)`; `score SMALLINT NN D 0`; `score_breakdown_json TEXT`; `reject_reason`, `retired_reason VARCHAR(32)`; `plaid_status`, `plaid_frequency`, `plaid_pfc_detailed`, `plaid_pfc_confidence VARCHAR(32)`; `plaid_is_active TINYINT(1)`; `plaid_predicted_next_date DATE`; `deposits_found SMALLINT NN D 0`; `first_date`, `last_date DATE`; `last_amount`, `trailing_median money` (positive); `trailing_cv DECIMAL(6,4)`; `predicted_next_date DATE`; `missed_count SMALLINT NN D 0`; `retired_at ts`; `plaid_snapshot_json TEXT`; timestamps. Unique `(user_id, plaid_account_id, payer_token)`; index `(user_id, state)`.

### 3.4 `paycheck_events`

One row per Plaid transaction considered an inflow candidate; unique on the Plaid id so replays are no-ops. `id`, `user_id BIGINT NN`; `stream_id BIGINT FK ON DELETE SET NULL`; `plaid_transaction_id NN`; `pending_transaction_id` (nullable in Plaid, never required for matching); `plaid_account_id NN`; `amount money NN` (positive); `raw_amount money NN` (Plaid's `amount` stored unmodified: negative = inflow); `posted_date DATE NN`; `authorized_date DATE`; `pending TINYINT(1) NN`; `status VARCHAR(10) NN` (ACTIVE, REMOVED, MODIFIED, SUPERSEDED); `first_seen_at ts NN`; `removed_at`, `modified_at ts`; `raw_descriptor VARCHAR(255)`; `merchant_name`, `counterparty_name VARCHAR(120)`; `counterparty_type VARCHAR(32)`; `pfc_primary`, `pfc_detailed`, `pfc_confidence VARCHAR(40)`; `payment_channel VARCHAR(12)`; `ppd_id VARCHAR(64)`. Unique `(plaid_transaction_id)`; index `(user_id, posted_date)`, `(stream_id, posted_date)`, `(pending_transaction_id)`. Rows older than 24 months are deleted by the reconcile (7.9).

### 3.5 `paycheck_cycles`

One row per pay period per stream; the hand-off to the 11:55 cron. `id`, `user_id`, `schedule_id`, `stream_id BIGINT NN`; `period_key VARCHAR(16) NN`; `state VARCHAR(10) NN` (OPEN, CONFIRMED, QUEUED, SKIPPED, VOIDED, MISSED); `expected_date DATE NN`; `window_start`, `window_end DATE NN`; `event_id BIGINT FK`; `deposit_amount money`; `confirmed_at ts`; `amount_to_pull money`; `authorization_id BIGINT FK`; `skip_reason VARCHAR(32)` (SHADOW_CONFIRMED, AFFORDABILITY, CATCH_UP_CAP, PAUSED, HOLD, NO_AUTHORIZATION, LATE_SEEN, ACH_NOT_READY, KILL_SWITCH, USER_SKIP_THIRD, STALE); `voided_reason VARCHAR(32)` (DEPOSIT_REMOVED, DEPOSIT_PENDING, AMOUNT_CHANGED); `execution_id BIGINT FK ON DELETE SET NULL`; `queued_night DATE`; timestamps. Unique `(stream_id, period_key)` and `(event_id)`; index `(user_id, state)`. Foreign keys as built in V6 (Phase 0 planning): `event_id` ON DELETE SET NULL, `stream_id` ON DELETE CASCADE, `schedule_id` ON DELETE SET NULL, so a purge of streams or events never fails on a cycle row.

### 3.6 `plaid_webhook_events` (inbox)

`id BIGINT PK`; `dedupe_key CHAR(64) NN UNIQUE`; `webhook_type`, `webhook_code VARCHAR(40) NN`; `plaid_item_id`; `user_id BIGINT` (no FK); `environment VARCHAR(12) NN`; `payload_json TEXT NN`; `status VARCHAR(12) NN` (RECEIVED, PROCESSING, PROCESSED, FAILED, IGNORED); `attempts SMALLINT NN D 0`; `last_error VARCHAR(512)`; `jwt_iat BIGINT NN`; `received_at`, `processed_at ts`. Index `(status, received_at)`, `(plaid_item_id, received_at)`.

### 3.7 `ach_authorizations`

`id`; `user_id BIGINT FK ON DELETE SET NULL` (nullable so the row outlives the user); `user_public_id VARCHAR(64) NN`; `mode VARCHAR(10) NN` (DETECTED, MANUAL); `text_version VARCHAR(16) NN` (`DETECTED-2026.10-v1`); `text_hash CHAR(64) NN`; `rendered_text TEXT NN`; `amount_rule VARCHAR(16) NN` (FIXED_OR_SKIP); `amount money NN`; `timing_rule VARCHAR(32) NN` (ON_POSTED_PAYCHECK, ON_SCHEDULED_DATE); `cadence_label VARCHAR(40)`; `paychecks_per_year SMALLINT`; `payer_display_name VARCHAR(120)`; `plaid_item_id`, `plaid_account_id NN`; `plaid_account_mask VARCHAR(4)`; `funding_account_label VARCHAR(80) NN`; `alpaca_account_id_enc VARCHAR(512) NN`; `alpaca_relationship_id_enc VARCHAR(512)` (back-filled by `adopt` once APPROVED); `passkey_credential_id VARCHAR(512)`; `user_session_id VARCHAR(64) NN`; `auth_method VARCHAR(16)`; `ip_address VARCHAR(45)`; `user_agent VARCHAR(255)`; `authorized_at ts NN`; `revoked_at ts`; `revoke_reason VARCHAR(32)` (USER, BANK_CHANGE, AMOUNT_CHANGE, EMPLOYER_SWITCH_DOWNSCALE, ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, PERMISSION_REVOKED, SET_MANUAL, SUPERSEDED); `superseded_by BIGINT FK`; `email_log_id BIGINT`. Index `(user_id, revoked_at)`. Rows are never deleted (2 years is a floor); deletion anonymizes payer name, descriptor, mask, IP and user agent and keeps hashes, amounts, timestamps and session id.

### 3.8 `investment_executions` additions

`schedule_id BIGINT FK ON DELETE SET NULL`; `authorization_id BIGINT FK`; `cycle_id BIGINT FK ON DELETE SET NULL`; `plaid_transaction_id`; `source_key VARCHAR(120) UNIQUE` (`SCHED:{scheduleId}:{dueDate}` MANUAL, `PAY:{scheduleId}:{plaidTransactionId}` DETECTED, `LUMP:{userId}:{clientUuid}` Phase 3); `night_key VARCHAR(40) UNIQUE` (`{userId}:{queuedNightET}`, recurring only; NULLs allowed); `batch_night DATE`; `transfer_attempted_at ts` (claimed by conditional update, 5.5); `ach_return_code VARCHAR(8)`; `ach_return_class VARCHAR(24)` (NSF, ACCOUNT_INVALID, UNAUTHORIZED, STOP_PAYMENT, OTHER); `returned_after_settlement TINYINT(1) NN D 0`.

### 3.9 Other tables and the notice ledger

`plaid_item_removals`: `id`, `user_id BIGINT` (no FK), `item_id NN`, `access_token_enc VARCHAR(512) NN` (a deliberate second copy; relink overwrites `users.plaid_access_token`), `requested_at ts NN`, `removed_at ts`, `attempts INT NN D 0`, `last_error VARCHAR(500)`. `job_locks`: `name VARCHAR(64) PK`, `locked_until ts NN`, `locked_by VARCHAR(64)`, taken by the 11:55, 11:59, 22:30 and 10:00 jobs (section 12). `user_sessions` gains `passkey_credential_id VARCHAR(512)` and `auth_method VARCHAR(16)`, written at login so Authorize can copy them (7.3). Notice ledger: the earlier drafts' `paycheck_notices` table is dropped; the single suppression ledger is `email_log (email_type, user_id, period_key)` with its existing unique index, `email_type` (max 40) carrying the reason where needed (`SKIP_HOLD_ACH_NOT_READY`) and `period_key` staying VARCHAR(10), so every key in 4.7, 6.4 and section 10 is an ISO date, a `YYYY-MM` bucket or a numeric id; `EmailService.sendOnce` checks and inserts in one transaction before any push or email.

## 4. Detection algorithm

`PaycheckDetectionService.evaluate(userId, trigger)` (FULL, INCREMENTAL, STREAMS_CHANGED, RECONCILE) runs in one transaction per user under a row lock on `investment_schedules`. It is skipped when `paycheck.autopilot.enabled=false` or `paycheck_autopilot_opt_out=true`, and otherwise runs for MANUAL rows too (shadow mode, early "Set manually", UNDETECTABLE users), so a paycheck found later can light the "Turn on" path (5.9). Scoring and cadence are pure functions with fixture tests.

### 4.1 Inputs

ACTIVE `paycheck_events` with `pending=false` and `raw_amount < 0` (Plaid's `amount` is positive for money out, negative for money in; `amount = -raw_amount`); the Plaid stream catalog (tiebreaker only; absence or an empty catalog is never a rejection input); the account map from the last sync; `monthlyGoal = User.monthlyInvestment` (the survey goal; `UserProgress.monthlyInvestment` has no writer), falling back to 500 with `goalSource=default` when null or 0; the current primary.

### 4.2 Hard gates (state REJECTED with `reject_reason`, never scored)

(1) Not an inflow. (2) Account `type != depository`. (3) Plaid status TOMBSTONED or UNKNOWN when a Plaid stream exists for the group. (4) Derived cadence outside the four enums; constant 28-day spacing is rejected, never coerced to MONTHLY (`CADENCE_UNSUPPORTED`). (5) PFC v2 detailed in {INCOME_UNEMPLOYMENT, INCOME_RETIREMENT_PENSION, INCOME_LONG_TERM_DISABILITY, INCOME_CHILD_SUPPORT, INCOME_RENTAL, INCOME_TAX_REFUND, INCOME_DIVIDENDS, INCOME_INTEREST_EARNED}, or primary TRANSFER_IN at confidence HIGH or VERY_HIGH, or `counterparties[].type == payment_app`. (6) Descriptor tokens: benefits (UI BENEFIT, UNEMPLOY, EDD, TREAS 310, SSA, VA BENEFIT, DISAB, PFL, WORKERS COMP, CHILD SUPPORT, IRS) and non-payroll channels (VENMO, ZELLE, PAYPAL, CASH APP, CASHAPP, MOBILE DEPOSIT, ATM, INSTANT PAY, CASHOUT); never TRANSFER or XFER (credit unions prefix every ACH credit that way). (7) Cadence-normalized trailing median below $400 per month: WEEKLY under $92, BIWEEKLY under $184, SEMI_MONTHLY under $200, MONTHLY under $400 (`LOW_MEDIAN`); the $50 floor applies to the median only, never to one deposit. (8) Last deposit older than 2.5 periods (`STALE`; eligible for resurrection, not primary). (9) Fewer than 2 posted deposits: CANDIDATE, not REJECTED. A benefits-signature rejection gets `role=INTERRUPTION`, `reject_reason=BENEFITS_SIGNAL`; its appearance while the primary is missing drives the income-interruption copy (section 6), never succession.

### 4.3 Grouping and payer token

Events group by `(plaid_account_id, payer_token)`. `payer_token` = uppercase `original_description` (fallback `name`), strip digit runs longer than 3, strip PAYROLL, DIR DEP, DIRECT DEP, DIRECT DEPOSIT, DEP, DES, ACH, CREDIT, DEPOSIT, TRANSFER, XFER, PPD, ID, CO, INC, LLC and punctuation, collapse whitespace, keep the first two words ("ACME CORP PAYROLL 2418 PPD" and "ACME CORP DIR DEP" both become `ACME CORP`). `ppd_id` is a secondary same-employer key, never required. `payer_display_name` prefers `counterparties[0].name` when `type == income_source` and confidence is not LOW, then `merchant_name`, then title-cased `payer_token`. `raw_descriptor` is the latest `original_description`. Plaid's `transaction_ids[]` seed a group when FRED's grouping finds fewer deposits than Plaid's stream.

### 4.4 Cadence derivation and the frequency enum

Last 6 posted deposits by `posted_date`, intervals computed. WEEKLY: median 6 to 8 days, all within 5 to 9. BIWEEKLY: median 13 to 15, all within 12 to 16, intervals do NOT alternate. SEMI_MONTHLY: intervals alternate between roughly 13 and 17 (or 14 and 16) AND dates cluster on two day-of-month anchors (each within 2 business days of one); anchors are the modal day per cluster; a cluster at 28 to 31 (or the prior business day of month end) is `LAST_BD`; a cluster at 1 to 3 is `1` with backward business-day adjustment. MONTHLY: median 28 to 31 with one anchor; constant 28-day spacing fails. Fewer than 3 intervals: use what exists; when the gaps fit two buckets (two intervals of 14 fit BIWEEKLY and SEMI_MONTHLY) use Plaid `frequency` as the tiebreak, else prefer BIWEEKLY and set `cadence_source=PLAID` for a re-check after the next deposit; `OBSERVED` once 3 intervals agree. Re-check on every confirmed deposit and at the weekly reconcile: a change remaps `frequency` and rescales `investment_amount` to preserve `monthly_amount` (rounded to the dollar) when the per-paycheck amount falls; when it would rise, the authorized amount keeps pulling at the new cadence and the rise is offered through the raise prompt (section 7), never applied; the "cadence changed" notice goes out; never routed through succession.

One backend `InvestmentFrequency` enum (WEEKLY 4.33 / 52, BIWEEKLY 2.17 / 26, SEMI_MONTHLY 2 / 24, MONTHLY 1 / 12, plus a SEMIMONTHLY alias parser) replaces the three copies in `InvestmentScheduleService`, `InvestmentSchedule` and `MonthlyFreedomUpdateService` (the 2025 `PaycheckDetectionService` recovered to the archive has no factor method; the Phase 1 detection service uses the enum from the start); `FrequencyFactorParityTest` pins it and the client table in `investment-frequency.utils.ts`. Labels: "Every {Weekday}", "Every other {Weekday}", "Twice a month ({a1} and {a2})", "Monthly on the {ordinal}". Predicted next date: anchor arithmetic with a backward business-day adjuster (payroll moves early, never late; `isUSHoliday` reused); Plaid's nullable `predicted_next_date` is used only with fewer than 3 intervals. Display and notification data only; never a trigger.

### 4.5 Score (0 to 100), stream states, primary selection

| Component | Points |
|---|---|
| PFC v2 detailed INCOME_SALARY (legacy INCOME_WAGES; INCOME_MILITARY only with a DFAS token) 40; INCOME_CONTRACTOR, INCOME_GIG_ECONOMY 20; INCOME_OTHER or none 10 | up to 40 |
| Plaid status MATURE 20; EARLY_DETECTION 5; no Plaid stream 0 (never negative) | up to 20 |
| Monthly-normalized median >= $1,500: 15; >= $800: 10; >= $400: 5 | up to 15 |
| Cadence fit: all intervals inside the band 10; exactly one outside by at most 4 days 5; else 0 | up to 10 |
| Stability plateau: CV <= 0.15: 10; <= 0.35: 7; <= 0.6: 3; penny-identical AND round (ends .00, divisible by 50): 10 minus 4 | up to 10 |
| Payroll tokens (ADP, GUSTO, PAYCHEX, WORKDAY, DFAS, PAYROLL, DIR DEP, DIRECT DEP, SALARY, PAYRL, PAYCHK) | +10 |

Thresholds are properties (`paycheck.autopilot.score.provisional=55`, `.confirmed=70`) tuned in shadow mode. States: CANDIDATE (fewer than 2 on-cadence deposits; never money); PROVISIONAL (score 55 to 69 with 3+ deposits, OR exactly 2 on-cadence deposits with score >= 55; money moves on the next confirmed cycle); CONFIRMED (score >= 70 AND 3+ on-cadence deposits AND Plaid MATURE, or 4+ on-cadence deposits when Plaid has no stream for the group); DORMANT (`missed_count >= 1`, section 6); RETIRED (succeeded, or 120 days dormant; keeps token, cadence, anchors, median for resurrection); REJECTED.

Primary selection, in order: (1) same-payer rule: among qualifying streams sharing `payer_token` or `ppd_id`, the more frequent cadence wins, then the lower CV, regardless of amount; the other is SECONDARY. (2) Among different payers, the highest score; ties by trailing median. (3) Two different-payer CONFIRMED streams both >= 70: the larger median is PRIMARY, the other SECONDARY, `needsChooser=true` for the Phase 3 chooser. Split-deposit summing is Phase 3; until then only the funding-account leg counts. A primary whose monthly-normalized median is below 40 percent of `monthlyGoal x 4` still qualifies but carries `lowConfidence=true`; the only launch effect is the affordability cap on the pre-fill. Screen 1 outcome: SEARCHING until history completes; then PROVISIONAL or CONFIRMED when a primary exists; UNDETECTABLE when no group passes (including zero ACH credits, `plaid_item_status=NOT_SUPPORTED`, or SYNC_LIMIT), which drops to the manual form.

### 4.6 Cycles and periods

A cycle is opened (OPEN) for the primary at first qualification and whenever its previous cycle closes. `period_key`: WEEKLY and BIWEEKLY use the ISO date of the expected pay date from `anchor_dow` and `anchor_parity_date`; SEMI_MONTHLY `YYYY-MM-A` and `YYYY-MM-B`; MONTHLY `YYYY-MM`. `expected_date` is the 4.4 prediction; `window_start = expected_date - 5 business days`, `window_end = expected_date + 7 business days`, widened 2 days each side for SEMI_MONTHLY; `schedule.next_expected_date = expected_date` of the OPEN cycle.

### 4.7 Confirming a cycle from a posted deposit (INCREMENTAL evaluation)

A "night" is the ET calendar day on which FRED first saw the posted deposit (`confirmed_at` in ET); Plaid's `date` is the bank's posting date with no zone, so a late-day posting may confirm on ET day 1 and pull that night. For each newly ACTIVE event with `pending=false`, `raw_amount < 0`, on the primary's account, matching the primary's `payer_token` or `ppd_id` (never a generic payroll token):

1. Log only if the stream is not PROVISIONAL, CONFIRMED, or DORMANT (DORMANT resumes on a qualifying deposit).
2. Late-seen: `first_seen_at.atZone(ET).toLocalDate() - posted_date > 7` days is recorded, never confirms; the OPEN cycle becomes SKIPPED `LATE_SEEN`.
3. Asymmetric amount match: `amount >= 0.6 x trailing_median` (0.45 when `trailing_cv > 0.35`), no upper bound; a mismatch alone never counts as a miss.
4. Gap: `posted_date - last confirmed deposit date >= max(0.6 x period_days, 5 business days)`.
5. Window: `posted_date` inside the OPEN cycle's `[window_start, window_end]`; outside every window, a deposit passing 3 and 4 opens a new cycle only if the gap holds, else it is a secondary event (bonus, reimbursement, PTO payout).
6. One cycle per period: a period already CONFIRMED, QUEUED, or SKIPPED records the deposit and ignores it.
7. Stream trust: at least 2 on-cadence deposits counting this one.
8. Shadow mode (`paycheck.autopilot.shadow=true`): the cycle is written SKIPPED `SHADOW_CONFIRMED` here, before any notice gate, so shadow users receive nothing (section 10's `NotificationGate` is the second guard).
9. Affordability, fixed-amount-or-skip: `investment_amount <= share x amount`, `paycheck.autopilot.affordability.share=0.90` (Andy, 2026-10-19: skip only when the amount exceeds 90 percent of the deposit); fail: SKIPPED `AFFORDABILITY`, one notice, never a reduced pull; two consecutive skips raise the "want to adjust?" prompt. 5.3 re-runs this against the event's current amount at 11:55.
10. Remaining gates, each a SKIPPED reason: `is_paused` (`PAUSED`, no notice: the user chose it; the page's `lastSkip` line shows it), `hold_reason != null` (`HOLD`), no unrevoked authorization or `investment_amount > authorized_amount` (`NO_AUTHORIZATION`), `achRequestId == null` or account not ACTIVE (`ACH_NOT_READY`), `paycheck.autopilot.pulls.enabled=false` (`KILL_SWITCH`); one notice per reason per `YYYY-MM` via `email_log` (section 10).
11. Otherwise CONFIRMED with `event_id`, `deposit_amount`, `amount_to_pull = investment_amount`, `authorization_id`, `confirmed_at = now`; stream stats and `missed_count=0` updated, DORMANT cleared; next cycle opened; `schedule.next_expected_date` written; a PROVISIONAL stream meeting CONFIRMED's conditions is promoted.

Catch-up cap: at most one cycle per user sits in CONFIRMED and the cron consumes at most one per user per night (`night_key`). If a second cycle would confirm while one is CONFIRMED, the NEWER deposit becomes CONFIRMED and the older is SKIPPED `CATCH_UP_CAP` with one notice; never summed into one doubled ACH. A cycle whose `window_end` is more than 7 days past is MISSED regardless of a late deposit, and a CONFIRMED cycle older than 7 days is SKIPPED `STALE` by the reconcile. Counter `paycheck.confirm_lag_days{institution_id}` feeds the shadow report.

### 4.8 `added`, `modified`, `removed` (`PaycheckEventService.applySyncBatch`)

Plaid delivers a pending-to-posted transition as the pending id in `removed[]` plus a NEW posted transaction in `added[]`, not as a modification; the posted row's `pending_transaction_id` is set only when Plaid matched the pair and is null at institutions without pending data, so matching never requires it. `added`: upsert by `plaid_transaction_id` (duplicate is a no-op); a posted row whose `pending_transaction_id` matches a stored pending event marks it SUPERSEDED and links the two. `removed`: mark REMOVED with `removed_at`; if it confirms a CONFIRMED cycle, set VOIDED (`DEPOSIT_REMOVED`), clear `confirmed_at`, reopen the period as OPEN; if the same batch holds the posted twin, re-run 4.7 on it. `modified` (posted rows are not immutable: refunds and recategorizations surface here): update amount, date, pending, descriptor, `modified_at`; if it confirms a CONFIRMED cycle, re-run 4.7 rules 3 to 5 and 9; a failure flips the cycle to SKIPPED `AFFORDABILITY` or VOIDED `AMOUNT_CHANGED`, and a flip to `pending=true` voids it (`DEPOSIT_PENDING`). The 11:55 cron re-verifies the confirming event is ACTIVE, `pending=false`, `raw_amount < 0` before creating an execution (5.3), so a reversal before 11:55 never pulls; after 11:59 it is the late-return path (section 7).

### 4.9 Amount pre-fill and bounds (screen 1)

`suggestedAmount = round(monthlyGoal / factor)` with the 4.1 goal and its 500 fallback; `affordabilityCap = floor(share x trailing_median)`; `prefill = max(1, min(suggestedAmount, affordabilityCap))`. Info tip: the mockup's "Set from your ${goal}/mo goal: ${prefill} each payday. Change it anytime." when `prefill == suggestedAmount`, else "Your ${goal}/mo goal works out to ${suggested} each payday. FRED capped it at ${prefill}, 90 percent of your typical paycheck."; the tip is hidden (both screen 1 variants) when `goalSource=default`. Client validation `1 <= amount <= 50000`; the server rejects `amount > affordabilityCap` with 422 `AMOUNT_ABOVE_CAP` carrying `cap`. `amount_source` is GOAL_DERIVED when the saved amount equals the suggestion, else USER_EDITED. Screen 2 shows the resulting monthly total beside the goal, so a cap never silently rewrites the Freedom estimate, and the pipeline never writes `User.monthlyInvestment`.

## 5. Execution integration

### 5.1 Schedule state machine in DETECTED mode

`mode` is MANUAL or DETECTED. DETECTED is driven by `detection_state` (primary stream), `hold_reason` (system stop) and `is_paused` (user stop).

`is_paused` is written only by `pauseSchedule`, `resumeSchedule`, new MANUAL row creation (true, as today) and one system exception: new MANUAL rows start paused and `updateWithAchRequestId`, today's only unpause, is deleted, so `AchRelationshipService.adopt` clears the initial pause exactly once, on the first-ever adoption for the user (`schedule.achRequestId` null AND `users.previous_ach_relationship_id` null). A pause the user chose is never cleared by the system, and a bank change (previous id set) never unpauses (decision 23). The 2.4 placeholder starts `false`; `resetForBankChange` no longer sets it; `updateWithAchRequestId` is deleted (5.9); Authorize and `adopt` never write it. Every system stop is a `hold_reason`; `achRequestId=null` blocks both modes until ACH exists. Overrides 2.4, 3.2 and 11.

Streaks: `User.currentStreak` (monthly `StreakService` cron, zeroed only by pause) and `InvestmentSchedule.monthlyStreak` (queue time; shown on the page); dormancy, breakage and revocation reset neither; the monthly cron skips a month spent under a hold.

| From | Event | To (hold) |
|---|---|---|
| SEARCHING, UNDETECTABLE, PROVISIONAL | primary found or promoted (4.5) | PROVISIONAL or CONFIRMED |
| SEARCHING | history complete, nothing qualifies; `TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED` | UNDETECTABLE (SYNC_LIMIT adds MANUAL_REVIEW) |
| any | `PRODUCTS_NOT_SUPPORTED`, `INVALID_PRODUCT`, `ITEM_NOT_SUPPORTED` | UNDETECTABLE, `plaid_item_status=NOT_SUPPORTED`, no hold, no reconnect card |
| PROVISIONAL, CONFIRMED | `missed_count` reaches 1 (6.4) | DORMANT (DORMANT) |
| DORMANT | stream returns (6.2) or successor wins (6.1) | PROVISIONAL (cleared) |
| any | Item broken (5.7) | ITEM_BROKEN (ITEM_LOGIN_REQUIRED or PERMISSION_REVOKED) |
| ITEM_BROKEN | LOGIN_REPAIRED or update-mode success, only from `plaid_item_status=LOGIN_REQUIRED` | stream's own state, or SEARCHING (cleared) |
| any; REVOKED | authorization revoked (7.6); new AchAuthorization | REVOKED (AWAITING_AUTHORIZATION or the cause); primary's state, or SEARCHING after a bank change (AWAITING_AUTHORIZATION and BANK_CHANGE cleared) |

### 5.2 Eligibility: the mode branch

Hook point: `InvestmentScheduleService.getSchedulesReadyForInvestment`. Keep `findByUser_AccountStatus("ACTIVE")` as the first gate for both modes; replace `filter(InvestmentSchedule::isReadyForInvestment)` with `filter(s -> isReady(s, todayET))`. The model method stays as the MANUAL branch with `LocalDate.now(America/New_York)` and one hold clause.

```
MANUAL:   !isPaused && achRequestId != null
          && holdReason not in (ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, MANUAL_REVIEW)
          && (startDate == today || nextInvestmentDate <= today)

DETECTED: !isPaused && achRequestId != null && holdReason == null
          && authorizationId != null && authorization.revokedAt == null
          && authorization.plaidAccountId == user.plaidAccountId
          && authorization.alpacaRelationshipId == decrypt(user.alpacaAchRelationshipId)
          && authorization.alpacaRelationshipId == schedule.achRequestId
          && detectionState in (PROVISIONAL, CONFIRMED)
          && cycleRepository.existsByUserAndState(user, CONFIRMED)
```

The relationship clause exists because the 11:59 batch POSTs `user.alpacaAchRelationshipId`, not `schedule.achRequestId` (plaintext, no longer in any response).

### 5.3 The 11:55 PM ET cron (`processScheduledInvestments`)

Transaction shape (blocker): `InvestmentExecutionService` is `@Transactional` at class level, so today all schedules share one transaction and one unique-key violation rolls back the whole night. The cron becomes a coordinator: load eligible ids, then `ScheduledInvestmentProcessor.processOne(scheduleId, todayET)` on a separate bean with `REQUIRES_NEW` per schedule. Dedupe is a pre-check (`existsBySourceKey`, `existsByNightKey`); a violation fails one user, never the night.

"Night": Plaid checks an institution one to four times a day with no documented time of day and a Plaid `date` is zoneless, so FRED's night is the ET calendar day on which it first sees the posted deposit; late-seen (4.7) counts whole ET days.

Per schedule, inside `processOne`:

1. Shadow and kill switch. In shadow mode (12.1) the exchange leaves `mode=MANUAL` and cycles are SKIPPED `SHADOW_CONFIRMED` at confirm time, so this branch never runs. With `paycheck.autopilot.pulls.enabled=false` and no allowlist entry, every CONFIRMED cycle becomes SKIPPED `KILL_SWITCH` with one notice per user per 30 days via `NotificationGate`. Every notice in this spec goes through `EmailService.sendOnce(type, userId, key, message)`, which checks `hasBeenSent`, inserts and sends in one transaction; `email_log.period_key` stays VARCHAR(10) (keys are an ISO date, a `YYYY-MM` bucket or a numeric id, per 3.9) and no `paycheck_notices` table is created.
2. `shouldProcessToday`: MANUAL keeps both branches. DETECTED ignores `startDate` and is due only when `PaycheckCycleRepository.findConfirmedForNight(todayET)` returns a cycle with `confirmed_at <= 23:55 ET`; with several, the NEWEST runs and the rest become SKIPPED `CATCH_UP_CAP` with one notice.
3. Pre-flight on the cycle reloaded `FOR UPDATE`; each failure writes the reason and returns: event not ACTIVE, pending, or `raw_amount >= 0` (Plaid inflows are negative) → VOIDED `DEPOSIT_REMOVED`; `amount_to_pull > 0.90 x` the event's current amount → SKIPPED `AFFORDABILITY`; authorization revoked, not the cycle's, amount above `authorization.amount` or not equal to `investmentAmount`, or a 5.2 binding fails → SKIPPED `NO_AUTHORIZATION`; hold or pause → SKIPPED `HOLD` or `PAUSED` (the cutoff); no ACH or account not ACTIVE → SKIPPED `ACH_NOT_READY`.
4. `createInvestmentExecution` stamps the 3.8 columns (`authorization_id`, `source_key`, `night_key` for recurring); amount `cycle.amount_to_pull` or `schedule.investmentAmount`; status SCHEDULED; batch marker unchanged.
5. `updateScheduleAfterExecution`: MANUAL unchanged. DETECTED: cycle QUEUED (`execution_id`, `queued_night`); `missed_count=0`, `dormant_since=null`; next cycle opened (4.6); `next_expected_date` and its mirror updated; `monthlyStreak` block unchanged; never calls `calculateNextInvestmentDate`.
6. Legacy mirrors, both modes: `User.payFrequency` and `User.nextInvestmentDate` only. `User.monthlyInvestment` is the survey goal (written at registration and by my-profile); the pipeline never writes it.

### 5.4 Idempotency keys

`source_key` UNIQUE (never twice for one paycheck or MANUAL due date) and `night_key` UNIQUE, NULL for lump sums (one recurring execution per user per night), both pre-checked before insert; `transfer_attempted_at` is a compare-and-set claim (5.5); `email_log (email_type, user_id, period_key)` UNIQUE is the notice ledger.

### 5.5 The 11:59 PM batch

Untouched in selection, grouping, summing, marker matching and the one-POST-per-user shape. Three mode-neutral hardening changes need Andy's OK (open decision 14):

(a) `createdAt.toLocalDate().equals(today)` becomes `createdAt < batchStart`, so a 23:59:xx row runs the next night.
(b) Claim before POST in `TransactionTemplate(REQUIRES_NEW)`: `UPDATE investment_executions SET transfer_attempted_at=?, batch_night=? WHERE id IN (...) AND transfer_attempted_at IS NULL AND alpaca_transfer_id IS NULL`; a short row count skips that user and alerts. The Alpaca POST has no idempotency key, so this claim is the only guard.
(c) Fail fast (FUNDING_FAILED, user notified) on a null relationship id; for every DETECTED row assert `authorization.alpacaRelationshipId == decrypt(user.alpacaAchRelationshipId)` before the POST, else FUNDING_FAILED `RELATIONSHIP_MISMATCH` and alert.

A claimed row with no transfer id after 30 minutes alerts (12.3). Runbook: deploy freeze 23:45 to 00:10 ET.

### 5.6 Who does what

Every `/transactions/sync` request on every path carries `options.days_requested = paycheck.autopilot.daysRequested`: with `transactions` only in `additional_consented_products`, Plaid documents that the first sync initializes Transactions and this option fixes the history depth for the Item's life.

The webhook processor (2.8) syncs and confirms cycles on SYNC_UPDATES_AVAILABLE and reads streams on RECURRING_TRANSACTIONS_UPDATE. `PaycheckReconcileJob` (`0 30 22 * * *` America/New_York) does the same for every DETECTED or SEARCHING user with `plaid_item_status=OK` and alone owns miss detection (OPEN cycle past `window_end`: MISSED, `missed_count++`, DORMANT, one notice), the Sunday cadence re-check, the `/item/get` fallback for every user with a token (MANUAL included), retries, and CONFIRMED cycles older than 7 days to SKIPPED `STALE`.

Capacity: the scheduler has 4 threads and Hikari 4 connections. Plaid I/O moves to a bounded `plaidExecutor` (2 to 4 threads); the reconcile stops dispatching at 23:20 ET and aborts after 5 consecutive TRANSIENT or RATE_LIMIT results (alert `plaid.outage`); `setPoolSize(6)`, `hikari.maximum-pool-size=10`. Every job takes a `job_locks` row; inbox and removal rows use `SKIP LOCKED`. A lost webhook or a Plaid outage costs one night.

### 5.7 Item state handling

Plaid facts: `ITEM_LOGIN_REQUIRED` is an `error_code` (error_type `ITEM_ERROR`) inside the ITEM/`ERROR` webhook or an API error, never a webhook code. `PENDING_DISCONNECT` and `PENDING_EXPIRATION` are webhooks, not error states: `plaid_disconnect_at` and a RECONNECT notice, no hold until an error arrives. `USER_PERMISSION_REVOKED` is not guaranteed for revocation at the institution. Plaid bills per Item per UTC month even in an error state until `/item/remove`, so every terminal state queues it. Every handler writes only when its `item_id` equals `users.plaid_item_id`, else it logs `plaid.stale_item_event` and marks the row IGNORED. The rows apply to MANUAL schedules too.

| Trigger | Writes, one transaction |
|---|---|
| `ITEM_LOGIN_REQUIRED` (webhook ERROR or API error); `ACCESS_NOT_GRANTED`, `NO_ACCOUNTS` | `plaid_item_status=LOGIN_REQUIRED`; `hold_reason=ITEM_LOGIN_REQUIRED`; ITEM_BROKEN; CONFIRMED cycles SKIPPED `HOLD`; purge events, null descriptors, keep the stream skeleton (open decision 10); one RECONNECT notice |
| `USER_PERMISSION_REVOKED`; `USER_ACCOUNT_REVOKED` on the funding account; `ITEM_NOT_FOUND` | as above plus full purge of streams, cycles and cursor; authorization revoked `PERMISSION_REVOKED`; `plaid_item_status=REVOKED`; a `plaid_item_removals` row (repair is a fresh link, since update mode may not launch after a revoke) |
| `LOGIN_REPAIRED` (fires only on repair outside FRED's update mode) or update-mode success | only from `plaid_item_status=LOGIN_REQUIRED`: status OK; clear `hold_reason` only if ITEM_LOGIN_REQUIRED; state back to the stream's (or SEARCHING); sync, INCREMENTAL evaluation, no catch-up. From any other status: PROCESSED with a WARN, no writes (test `loginRepairedIgnoredUnlessLoginRequired`) |
| `ADDITIONAL_CONSENT_REQUIRED` (HTTP 400, `INVALID_INPUT`, DTM Items only); `TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED` | `plaid_transactions_consented=false`, NEEDS_CONSENT, no retries until update mode succeeds; `plaid_item_status=SYNC_LIMIT`, MANUAL_REVIEW hold, UNDETECTABLE, never retried |

### 5.8 `nextInvestmentDate`, `next_expected_date`, downstream consumers

- `next_expected_date` is FRED's prediction for the OPEN cycle (4.6); Plaid's nullable `predicted_next_date` is used only when non-null and with fewer than 3 intervals; never a trigger. In DETECTED, `nextInvestmentDate` (NOT NULL) mirrors `next_expected_date`, or the last confirmed date plus one period; `monthlyAmount = investmentAmount x factor` keeps every factor reader working. DORMANT, ITEM_BROKEN and REVOKED schedules do NOT contribute (decision 9, Andy 2026-10-19): `InvestmentScheduleResponse` gains `contributing` (false in those states and when paused or held), and the schedule-derived monthly fallback in `FreedomStatsService`, the MFU `recurringInvestmentAmount` and the Goals per-paycheck math read 0 when it is false. The survey goal in `User.monthlyInvestment` still drives the Freedom estimate as today. Readers that present `nextInvestmentDate` as a run date become mode-aware: `FredChatActionService.resumeInvesting` ("on the next paycheck that posts, expected {date}"), `FredChatToolsService`, both `InvestmentController` dashboards, `ChatService`.
- `InvestmentScheduler` computes today in America/New_York once and passes it to `PaydayNotificationService`; `createInvestmentSchedule` and `onCreate` use the same zone. Today `BackendApplication.main` pins the JVM default to UTC (`TimeZone.setDefault` and `user.timezone`, lines 16 and 17); Phase 0 changes both to `America/New_York` (decision 17, Andy wants the server on Eastern time; the zone id covers EST and EDT). DATETIME rows written before the switch are UTC values, so the switch is deployed outside the 23:45 to 00:10 ET freeze, the `createdAt < batchStart` comparison uses the new clock from that night on, and the first night's 11:59 batch selection is checked by hand.
- D-1: the MANUAL query gains `mode='MANUAL'`; new `findDetectedByNextExpectedDate(date)` requires CONFIRMED or PROVISIONAL, no hold, not paused, not snoozed, plus section 10's variance rule.

### 5.9 Pause, resume, revoke, set-manual, amount change, chat

- Resume (user): `resumeSchedule` as today. Informational holds (DORMANT, BANK_CHANGE, ITEM_LOGIN_REQUIRED, CONSENT_REQUIRED, KILL_SWITCH) do not block it: `is_paused` flips, 200, the page toasts the hold's sub copy. 409 only for `AUTHORIZATION_REQUIRED`, `ACH_RETURN_UNAUTHORIZED`, `ACH_RETURN_ACCOUNT`, `MANUAL_REVIEW`; the page hides pause and resume there and in ITEM_BROKEN.
- `POST /api/investment-schedule/update-ach-request-id`, `updateWithAchRequestId` and the unused client stubs are deleted (any client could write `achRequestId` and unpause); `adopt` is the only writer.
- Amount decrease: `POST /api/investment-schedule/amount` with `amount <= authorized_amount`, in place. Increase: always a new authorization through screen 2 (7.5).
- Set manually (`POST /api/investment-schedule/set-manual`): revoke `SET_MANUAL`, `mode=MANUAL`, `detection_state=null`, `primary_stream_id=null`, then today's MANUAL upsert. `hold_reason` is cleared only when it is AWAITING_AUTHORIZATION, DORMANT, ITEM_LOGIN_REQUIRED, CONSENT_REQUIRED or BANK_CHANGE; return, review and kill-switch holds are kept and the MANUAL eligibility clause (5.2) honors them. In REVOKED with `revokeReason=ACH_RETURN_UNAUTHORIZED` the endpoint returns 409 `CONTACT_SUPPORT` and the page shows "Contact support". Opt-out is separate: from SEARCHING, UNDETECTABLE or NEEDS_CONSENT (or in shadow mode) `paycheck_autopilot_opt_out` stays false and detection keeps running against the MANUAL row; `opt_out=true` only when the user rejects a found paycheck. A MANUAL user who later gets a primary receives PAYCHECK_FOUND and the "Turn on" row (9.7, Phase 2).
- Chat: `FredChatToolsService.investmentSchedule` reports `active = !isPaused && holdReason == null && (mode == MANUAL || detectionState in (PROVISIONAL, CONFIRMED))` plus `statusLabel` and `statusDetail`; `update_investment_amount` in DETECTED refuses an increase before any write ("Raising your amount needs a quick Authorize in Settings, Recurring Investments") and never passes `frequency`; `resume_investing` surfaces the 409 reasons.

## 6. Employer switch and interruptions

### 6.1 Succession (same bank, new payer)

A candidate S2 with a different `payer_token` is ignored at one deposit. S2 becomes PRIMARY when: (1) S2 has 2+ on-cadence posted deposits and score >= 55; (2) S1 has `missed_count >= 1`, or Plaid `is_active=false` or TOMBSTONED, or is quiet for more than 1.5 periods, or the recency exception holds (S1 older than 6 months, S2 started after S1's last 3 deposits, S1 still paying); (3) S2 is not a benefits-signature stream (4.2).

Effect, one transaction: S1 RETIRED (later S1 deposits are recorded, never confirmed); S2 `role=PRIMARY`, PROVISIONAL, `primary_stream_id=S2`; S2's second deposit confirms a cycle immediately; NEW_EMPLOYER notice through `NotificationGate`.

Amount rule, `ratio = S2.trailing_median / S1.trailing_median`. Below 0.80: `investmentAmount = floor(investmentAmount x ratio)`, `amount_source=DOWNSCALED`, authorization revoked `EMPLOYER_SWITCH_DOWNSCALE`, REVOKED, `hold_reason=AWAITING_AUTHORIZATION`, notice with a Review & Authorize deep link. At or above 0.80 the authorization stands; notice sent; a median 5 percent higher for three deposits offers the raise prompt (Phase 3), never applied. Cadence changes follow 6.5. Joint-account ties follow 4.5 (`needsChooser`).

### 6.2 Resurrection

RETIRED and DORMANT streams keep `payer_token`, `ppd_id`, `cadence`, anchors and `trailing_median`. A new group matching one on token with a first interval that fits the stored cadence reuses the old row: PRIMARY if it was primary, PROVISIONAL, DORMANT hold cleared, no warm-up; the first on-cadence deposit confirms a cycle. The authorization stays valid if the amount still passes the affordability cap; a median more than 20 percent lower follows the downscale path. WELCOME_BACK notice.

### 6.3 Descriptor drift

A new token with the same `ppd_id`, or the same amount within 5 percent on the same anchor day while the primary goes quiet, is drift at the same employer: merge into the primary, no succession, no copy. Plaid's `stream_id` is treated as unstable throughout.

### 6.4 Dormancy and income interruption

- A miss is an OPEN cycle whose `window_end` (expected date plus 7 business days) passed with no qualifying deposit. An amount mismatch is never a miss.
- First miss: cycle MISSED, `missed_count=1`, stream and schedule DORMANT, `hold_reason=DORMANT`, `dormant_since`, one DORMANT notice (`sendOnce`, key `dormant_since`). `is_paused` untouched; streaks per 5.1.
- Benefits signal: a benefits-signature stream appearing while the primary is DORMANT switches copy to INTERRUPTION and freezes succession.
- Snooze: `snoozed_until = today + 30`; suppresses dormancy, skip and D-1 notices; detection keeps running.
- 120 days dormant with no snooze: stream RETIRED (resurrection still works) and one DORMANT_120 notice.

### 6.5 Cadence change, three-paycheck months

A cadence change (same employer or succession) remaps `frequency`, rescales `investmentAmount` to preserve `monthlyAmount` (rounded to the dollar) and sends CADENCE_CHANGE; an increase holds AWAITING_AUTHORIZATION until the tap, a decrease applies in place. DETECTED invests on every confirmed paycheck, including the third payday in a three-paycheck month, with no heads-up notice and no skip toggle (decision 20, Andy 2026-10-19).

## 7. Consent and Reg E

### 7.1 Authorization record

`AchAuthorization` (3.7, plus `step_up VARCHAR(8)` and `user_public_id`) is written only by `AchAuthorizationService.create(user, request)` from `POST /api/ach-authorizations` (8.3). `monthlyGoal` everywhere is `User.monthlyInvestment`; `UserProgress.monthlyInvestment` has no writer and is not read.

Preconditions: active session (`sessionId` claim) else 401; a PROVISIONAL or CONFIRMED primary (or MANUAL, 7.9) else 409 `NO_DETECTED_STREAM`; `streamId` is the current primary else 409 `STREAM_CHANGED`; `1 <= amount <= affordabilityCap` else 422 `AMOUNT_ABOVE_CAP {cap}`; pulls enabled or allowlisted else 423 `KILL_SWITCH`; the server re-renders the template and the SHA-256 matches `textHash` else 409 `TEXT_MISMATCH`; if an unrevoked row exists, the same `amount` and `textHash` return 200 with that row, otherwise the request must name it in `supersedesAuthorizationId` else 409 `ALREADY_AUTHORIZED {authorizationId}`. The row binds amount rule, timing rule, Plaid item and account, encrypted Alpaca account and relationship ids and the 7.3 session evidence. Rows are never deleted; 2 years is the floor.

Side effects, one transaction: insert; revoke the named prior row as SUPERSEDED with `superseded_by`; `schedule.authorization_id`, `authorized_amount=amount`, `investmentAmount=amount`, `monthlyAmount` recomputed; clear `hold_reason` when it is AWAITING_AUTHORIZATION or BANK_CHANGE and the row binds the current `plaid_account_id`; `detection_state` back to the primary's state if REVOKED; `is_paused` not written (5.1), with `stillPaused: true` in the response so the modal toasts "Authorized. Paycheck investing is still paused; resume when you're ready."; `UserProgress.investmentScheduleCompleted=true` server side; then the email (7.4). Test: `authorizeDoesNotUnpauseUserPause`.

### 7.2 Text versioning

Templates `templates/ach-authorization/DETECTED-2026.10-v1.txt` and `MANUAL-2026.10-v1.txt`. The DETECTED text is exactly the mockup's second pager panel:

> By tapping Authorize, you allow FRED to transfer {{amount}} from {{fundingAccountLabel}} to your FRED investment account the night each {{payerDisplayName}} paycheck is deposited, about {{paychecksPerYear}} times a year. The money is invested once the transfer clears, usually within 1 to 3 business days. The amount never goes up without your OK.
>
> Stop or change this anytime in Settings. Changes made before 11:55 PM ET apply that same night. We'll email you a copy of this authorization.
>
> FRED finds your paycheck using transaction history you shared through Plaid. We never see your bank login.

The hash covers these three paragraphs only (what was on screen). Below a `---` separator the file carries an unhashed rights block for the email and the in-app record: "You can also stop a transfer by contacting your bank. If a paycheck is late or smaller than usual, FRED skips that transfer instead of pulling a smaller amount. Authorized on {{authorizedAt}} from session {{sessionIdShort}}." Any wording change is a new version file; old versions are never edited, so a house-style fix is a new version. `AchAuthorizationServiceTest.templatesContainNoEmDash` fails on U+2014 in any template.

### 7.3 Fresh authenticated tap

Authorize is a normal authenticated call. `UserSession` stores no credential, so V6 adds `user_sessions.passkey_credential_id VARCHAR(255)` and `auth_method VARCHAR(16)`: `WebAuthnService.finishAuthentication` writes the asserting credential's `PasskeyCredential.externalId` and `PASSKEY` when it creates or updates the session; the PIN login path writes `PIN`. `create` copies both, plus ip and user agent. No passkey or PIN step-up on the Authorize tap in either origin (Andy, 2026-10-19, decision 4); the authenticated session, the recorded credential and the on-screen text hash are the evidence of assent. The request carries `stepUp: "NONE"`, stored in `step_up` (kept so a step-up can be added later without a schema change). Revoke, snooze and `/amount` decreases keep the recurring page's existing PIN step-up (today's `saveChanges` behaviour). A MANUAL user migrating to DETECTED always gets screen 2 and a fresh tap.

### 7.4 Email copy and in-app viewing

After commit: `EmailService.sendOnce(ACH_AUTHORIZATION, userId, authorizationId, message)` with `rendered_text`, the rights block and the recap. Email is MOCKED until a provider ships (the `email_log` row is the proof of attempt); a real `EmailSender` is a Phase 2 gate (decision 5). In app: `GET /api/ach-authorizations/current` returns `rendered_text` behind "Authorized Oct 19 · View".

### 7.5 Amount rule and what needs a new authorization

`amount_rule=FIXED_OR_SKIP`: FRED pulls exactly `authorized_amount` or nothing; a paycheck that fails the 0.90 affordability gate is SKIPPED `AFFORDABILITY` with one notice. `schedule.investmentAmount <= authorized_amount` is enforced in `InvestmentScheduleService` on every write.

New authorization required: any increase (screen edit, MFU, chat, raise prompt, cadence change); an employer-switch downscale (6.1); a bank change (7.8); resume after an UNAUTHORIZED or STOP_PAYMENT return (7.7) or USER_PERMISSION_REVOKED; any resume from REVOKED; MANUAL to DETECTED. The amount endpoint rejects increases with 409 `AUTHORIZATION_REQUIRED {authorizedAmount, cap}`. Not required: a decrease, pause or resume, same-amount succession, a cadence decrease, or a relink keeping the same account and relationship.

### 7.6 Revocation and the cutoff

User revocation is one write: `revoked_at=now`, `revoke_reason=USER`, `schedule.authorization_id=null`, `detection_state=REVOKED`, `hold_reason=AWAITING_AUTHORIZATION`, AUTHORIZATION_REVOKED email, analytics. The 11:55 pre-flight re-reads the row, so the stated cutoff holds. System revocation reasons are listed in 3.7.

The control the text promises ("Stop or change this anytime in Settings"): a destructive "Stop paycheck investing" row in the recurring page's Modify card in CONFIRMED, PROVISIONAL and DORMANT, and a matching button in the "Authorized {date} · View" sheet. Flow: confirm alert ("FRED will stop pulling from your paycheck. Nothing more is transferred. Your streak stays, and you can authorize again any time."), PIN step-up (7.3), `POST /api/ach-authorizations/{id}/revoke` (204), the REVOKED row with the USER copy, toast "Paycheck investing is off." Pause does not revoke.

### 7.7 ACH return handling

What Alpaca documents: `Transfer.status` includes `RETURNED` ("The bank issued an ACH return"). `Transfer.reason` is a nullable string described only as "Cause of the status"; nothing says it carries a NACHA R code, and the transfer-status SSE event has no `reason`, so FRED reads it via `checkTransferStatus`. Alpaca does expose `admin_configurations.restrict_to_liquidation_reasons.ach_return` on the trading-account endpoint and the account-status SSE (the draft's "no ach_return flag" was wrong). Return windows (Plaid and NACHA, not Alpaca): standard returns (R01, R02, R03, R29) within about 3 business days; consumer unauthorized returns (R05, R07, R10, R11, R51 and the check-conversion codes) up to 60 calendar days. Alpaca sandbox documents no way to produce a RETURNED transfer, so the classifier is proven only in production.

`AchReturnClassifier.classify(status, reason)` parses `\bR\d{2}\b` from `reason`: NSF = R01, R09; ACCOUNT_INVALID = R02, R03, R04, R13, R16, R20; UNAUTHORIZED = R05, R07, R10, R11, R29, R51; STOP_PAYMENT = R08; OTHER when no code is present or unlisted. Called at the top of the failure branch in `checkExecutionFundingStatus`; every batch sibling is stamped. Until `ach.return.classifier.trusted=true` (after the first five production reasons are inspected) every class is treated as OTHER. A true `ach_return` liquidation flag sets `hold_reason=MANUAL_REVIEW` regardless of class and alerts. FUNDING_FAILED stays terminal; nothing is auto-retried.

| Class | Schedule writes | Authorization | Human |
|---|---|---|---|
| NSF | none; two in 90 days set `hold_reason=MANUAL_REVIEW` | stands | only at the 90-day hold |
| UNAUTHORIZED, STOP_PAYMENT | `hold_reason=ACH_RETURN_UNAUTHORIZED`, REVOKED, CONFIRMED cycles SKIPPED `HOLD`; `is_paused` untouched; Set manually blocked (5.9) | revoked | always: verify the record and the user's intent, clear the hold, then screen 2 |
| ACCOUNT_INVALID | `hold_reason=ACH_RETURN_ACCOUNT`, reconnect card | stands until the relink revokes it (7.8) | if no relink in 30 days |
| OTHER | `hold_reason=MANUAL_REVIEW` | stands | always, until the reason format is proven |

Late returns: a daily 10:00 ET sweep lists transfers settled in the last 90 days, matches RETURNED ones by `alpacaTransferId`, flags `returned_after_settlement` and applies the UNAUTHORIZED row; a person decides on liquidation and contacts the user (section 11).

### 7.8 Bank-change re-authorization and the Alpaca relationship

The authorization binds `plaid_account_id` and the Alpaca relationship id, so a bank change always needs a new row. Alpaca facts: the REST `ACHRelationship.status` enum is `QUEUED | APPROVED | REJECTED | PENDING | CANCEL_REQUESTED` (no CANCELED; the SSE stream adds four more including `CANCELED` and `CANCEL_FAILED`); a second create returns 409; DELETE documents no post-delete state, so FRED polls for absence, never for CANCELED.

`handleBankAccountChange`, in order, before the exchange nulls anything (today the exchange nulls the id first): (1) record `users.previous_ach_relationship_id`; (2) `resetForBankChange`: void open cycles, `hold_reason=BANK_CHANGE`, `achRequestId=null`, no `is_paused` write, purge streams and events, SEARCHING; revoke the authorization `BANK_CHANGE`; (3) DELETE every QUEUED, APPROVED, PENDING or CANCEL_REQUESTED relationship, deferred while any `FUNDING_INITIATED` execution exists; `alpacaAchStatus=CANCEL_REQUESTED`; (4) no synchronous `attemptAchCreation`. The 15-minute poll creates the new one only when the relationship list, filtered to those four statuses, holds nothing but the previous id in CANCEL_REQUESTED younger than 24 hours; older alerts for a person (decision 18).

The second re-adoption path is closed: the 409 branch in `AlpacaApiService.createAchRelationship` today returns any APPROVED or QUEUED id as if newly created and `PlaidToAlpacaService` adopts it; it now returns an error and the poll retries next tick. The re-adoption branches in `attemptAchCreation` are deleted. `AchRelationshipService.adopt` is the single writer of `User.alpacaAchRelationshipId`, `alpacaAchStatus`, `schedule.achRequestId` and the authorization back-fill, in one transaction. It refuses, sets `hold_reason=MANUAL_REVIEW` and alerts when the id equals `previous_ach_relationship_id`, the account number's last four differs from `users.plaid_account_mask`, or `created_at` precedes `plaid_item_linked_at`. It clears BANK_CHANGE for MANUAL immediately (today's behaviour minus the unpause) and for DETECTED only when an unrevoked authorization already binds the new account and relationship; otherwise the new authorization clears it (7.1). It never writes `is_paused` except the first-adoption unpause of decision 23 (5.1). Test: `conflict409NeverAdoptsOldRelationship`.

### 7.9 MANUAL users, privacy, retention, deletion

- MANUAL users have no authorization today and keep working unchanged until the MANUAL text ships (Phase 3, open decision 3).
- Privacy policy (LEGAL, before Phase 2) must list: transaction history, payer names and raw descriptors, deposit amounts and dates, cadence, the authorization record (2 years minimum, kept after closure), and deletion: relink, revoke or account deletion purges streams, events, cycles and cursors within 24 hours; events older than 24 months are deleted; descriptors are nulled when an Item breaks.
- Deletion has one path, `UserDeletionService.delete(user, reason)`, called by `AccountStatusService.handleRejected` (hard-deletes today; would crash-loop under V6's foreign keys), by `TradingController`'s close-account flow and an admin verb. It queues a `plaid_item_removals` row; DELETEs the Alpaca relationship; purges streams, events, cycles, cursor and inbox rows (V6 adds `ON DELETE CASCADE` on their `user_id` and `ON DELETE SET NULL` on the execution and schedule FKs); anonymizes authorization PII (keeps hashes, amounts, timestamps, session id) and detaches the row (`ach_authorizations.user_id` nullable, `user_public_id` kept); then deletes the user. It refuses and holds while any `FUNDING_INITIATED` execution exists or a transfer settled fewer than 60 days ago.

## 8. Backend API for the frontend

All endpoints are authenticated (Bearer JWT, principal-bound) except `POST /api/plaid/webhook` (2.8). The client calls `clearScheduleCache()` after every mutation. Error bodies: `{ "code": "<CODE>", ... }`. Analytics events: `paycheck_detected`, `paycheck_detection_failed`, `paycheck_cycle_confirmed`, `paycheck_cycle_skipped`, `paycheck_stream_switched`, `ach_authorization_created`, `ach_authorization_revoked`, `plaid_item_broken`, `plaid_item_repaired`.

Contract decisions (2.3 and 2.8 follow): states are SEARCHING, NEEDS_CONSENT, UNDETECTABLE, PROVISIONAL, CONFIRMED, DORMANT, ITEM_BROKEN, REVOKED, MANUAL; "awaiting authorization" is PROVISIONAL or CONFIRMED with `holdReason=AWAITING_AUTHORIZATION`. `textHash` is server-computed and echoed. Link-token body `{ "mode": "link" | "update" }`, completion call `POST /api/plaid/update-mode-complete` (the three-mode enum and `/consent-upgraded` are withdrawn). Server "today" is `LocalDate.now(America/New_York)`.

### 8.1 `GET /api/paycheck-detection/status` (screen 1 poll, recurring page, tab3 badge)

DB only, with one exception: the first call for an Item in SEARCHING with no `plaid_sync_cursor` and `detection_started_at` null stamps `detection_started_at` and enqueues `syncItem(INIT)` on the Plaid executor (decision 7); the response itself never waits on Plaid.

```json
{
  "mode": "DETECTED", "state": "CONFIRMED", "holdReason": "AWAITING_AUTHORIZATION",
  "itemStatus": "OK", "isPaused": false, "shadow": false, "optOut": false,
  "detectionStartedAt": "...", "historyComplete": true,
  "fundingAccountLabel": "Chase Checking ••••4821", "institutionName": "Chase",
  "monthlyGoal": 650, "goalSource": "survey",
  "primary": {
    "streamId": 41, "payerDisplayName": "Acme Corp", "rawDescriptor": "ACME CORP PAYROLL",
    "depositsFound": 6, "cadence": "BIWEEKLY", "cadenceLabel": "Every other Friday",
    "paychecksPerYear": 26, "factor": 2.17,
    "lastDepositAmount": 2418.00, "lastDepositDate": "2026-10-09", "nextExpectedDate": "2026-10-23",
    "affordabilityCap": 2176, "suggestedAmount": 300, "prefillAmount": 300,
    "lowConfidence": false, "needsChooser": false
  },
  "savedAmount": 250, "amountSource": "USER_EDITED", "authorization": null, "manualPrefill": { ... }
}
```

- `primary` is null in SEARCHING, NEEDS_CONSENT, UNDETECTABLE, MANUAL. `itemStatus`: OK, LOGIN_REQUIRED, PENDING_DISCONNECT, REVOKED, REMOVED, SYNC_LIMIT, NOT_SUPPORTED; NOT_SUPPORTED (Plaid `PRODUCTS_NOT_SUPPORTED`) is never ITEM_BROKEN (update mode cannot fix it): UNDETECTABLE, no hold, no RECONNECT notice.
- `monthlyGoal` is `User.monthlyInvestment`, the survey goal (`UserProgress.monthlyInvestment` has no writer); null or zero gives 500 with `goalSource: "default"`; nothing here writes it. `suggestedAmount = round(monthlyGoal / factor)`; `prefillAmount = max(1, min(suggestedAmount, affordabilityCap))`. `savedAmount` is `schedule.investmentAmount` once "Looks good" wrote it.
- Screen 1 anchors its timers to `detectionStartedAt`, never to mount. `authorization` is the latest row; unrevoked makes screen 1 skip to investment-confirmation. `manualPrefill` uses the primary's cadence and next expected date (else BIWEEKLY, today plus 14) and `savedAmount`, else `prefillAmount`, else the MANUAL amount.

### 8.2 `POST /api/investment-schedule/detected` ("Looks good")

`{ "streamId": 41, "investmentAmount": 300 }`. Asserts `mode=DETECTED` and `shadow=false`; `streamId` is the current PROVISIONAL or CONFIRMED primary (else 409 `STREAM_CHANGED`); `1 <= amount <= affordabilityCap` (else 422 `AMOUNT_ABOVE_CAP` with `cap`); no unrevoked authorization with a different amount (else 409 `ALREADY_AUTHORIZED`). Writes `investmentAmount`, the mapped `frequency`, `monthlyAmount = amount x factor`, `startDate = today ET` (never a trigger, 5.3), `nextInvestmentDate = next_expected_date`, anchors, `primary_stream_id`, `amount_source`. Never touches `is_paused`, `hold_reason` or `User.monthlyInvestment`. Idempotent; returns 8.5.

### 8.3 Authorization endpoints ("Authorize")

`GET /api/ach-authorizations/text?mode=DETECTED&amount=300` returns `textVersion`, `textHash` (SHA-256 of the UTF-8 paragraphs joined by a blank line), `paragraphs` (the mockup's panel 2), and the `recap` and `projections` objects 9.4 renders; the 8.2 errors apply.

`POST /api/ach-authorizations` with `{ mode, streamId, amount, textVersion, textHash, sawAuthorizationPanel, stepUp }` returns 201 `{ id, authorizedAt, amount, textVersion, emailLogStatus: "MOCKED", stillPaused, schedule }`. Preconditions and writes per 7.1 and 7.5, plus: `streamId` must equal the current primary; the server re-renders and hashes the text; `sawAuthorizationPanel` is recorded; `stepUp` is always `NONE` at launch and is stored in `step_up` (7.3); `hold_reason` is cleared only when AWAITING_AUTHORIZATION or BANK_CHANGE; `is_paused` is never written, so a user pause survives (`stillPaused: true`); `investmentScheduleCompleted=true` is written server-side; a re-tap with the same amount and hash returns 200. Errors: 401 session inactive; 409 `TEXT_MISMATCH`, `STREAM_CHANGED`, `NO_DETECTED_STREAM`, `ALREADY_AUTHORIZED`; 422 `AMOUNT_ABOVE_CAP`; 423 `KILL_SWITCH` (pulls off or shadow).

`GET /api/ach-authorizations/current` and `/{id}` return the row with `text` and `rightsBlock` (404 if none). `POST /api/ach-authorizations/{id}/revoke` (`?stepUp=`): the user revoke of 7.6, 204, never writes `is_paused`.

### 8.4 `POST /api/investment-schedule/set-manual`

Today's `/create` body plus `"cameFromDetection": true`, allowed from every DETECTED state. Revokes the authorization `SET_MANUAL`, clears the detection fields, sets `mode=MANUAL`, then runs today's upsert. Two rules: (1) `hold_reason` is cleared only when it is AWAITING_AUTHORIZATION, DORMANT, ITEM_LOGIN_REQUIRED, CONSENT_REQUIRED or BANK_CHANGE; the return holds, MANUAL_REVIEW and KILL_SWITCH are preserved and the MANUAL eligibility branch (5.2) refuses all but KILL_SWITCH, so a bank-returned pull can never be relaunched by switching modes; under `revokeReason=ACH_RETURN_UNAUTHORIZED` the endpoint returns 409 `CONTACT_SUPPORT` (8.10). (2) `paycheck_autopilot_opt_out=true` only when the user rejects a found paycheck (from PROVISIONAL, CONFIRMED, DORMANT or REVOKED, not shadow); from SEARCHING, UNDETECTABLE or NEEDS_CONSENT the flag stays false, evaluation keeps running, and a later primary sends PAYCHECK_FOUND (10) and shows the "Turn on" row (9.7). `/create` keeps serving MANUAL rows and rejects DETECTED rows with 409 `MODE_DETECTED`.

### 8.5 `GET /api/investment-schedule/current` (extended `InvestmentScheduleResponse`)

Existing fields stay; `achRequestId` is removed (it leaked an Alpaca id). SEARCHING users get 200 with primary fields null; 404 still means no row. Added in both modes: `mode`, `detectionState`, `holdReason`, `itemStatus`, `optOut`, `shadow`, `consentExpiresAt`, the 8.1 `primary` fields flattened, `fundingAccountLabel`, `institutionName`, `authorizedAmount`, `authorizationId`, `authorizedAt`, `revokedAt`, `revokeReason`, `dormantSince`, `snoozedUntil`, `lastSkip`, `secondaryStreams`, `statusLabel` and `statusDetail` (the 9.7 banner copy, shared with chat), `scheduleDescription` (mode-aware; MANUAL keeps today's text).

### 8.6 `POST /api/investment-schedule/amount`

`{ "investmentAmount": 250 }`. DETECTED only; `1 <= amount <= authorized_amount` applied in place (`monthlyAmount` recomputed, legacy mirrors written, `User.monthlyInvestment` untouched). An increase returns 409 `AUTHORIZATION_REQUIRED { "authorizedAmount": 300, "cap": 2176 }` before any write; above the cap 422 `AMOUNT_ABOVE_CAP`; MANUAL rows 409 `MODE_MANUAL`. Chat uses this same service (9.8).

### 8.7 Pause, resume, snooze, opt-in

`/{id}/pause` is unchanged. `/{id}/resume` keeps its 403 gate; informational holds (DORMANT, BANK_CHANGE, ITEM_LOGIN_REQUIRED, CONSENT_REQUIRED, KILL_SWITCH) do not block it, so `is_paused` flips and 200 returns the schedule; 409 `AUTHORIZATION_REQUIRED` for AWAITING_AUTHORIZATION or REVOKED; 409 `HOLD { "holdReason" }` for ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, MANUAL_REVIEW; 409 `ITEM_BROKEN` when the Item is broken; no catch-up. `POST /api/investment-schedule/snooze`: `snoozed_until = today + 30`. `POST /api/investment-schedule/detected/opt-in` (Phase 2): 409 `NEEDS_CONSENT` unless consented; else `mode=DETECTED` with the stream's state (or SEARCHING and the first sync), `hold_reason=AWAITING_AUTHORIZATION`, the MANUAL amount as prefill; MANUAL pulls stop only when Authorize succeeds.

### 8.8 Plaid endpoints

- `POST /api/plaid/create_link_token` body `{ "mode": "link" | "update" }` (default `link`): `link` from onboarding and change-bank-account, `update` from reconnect surfaces. For `update` the server passes `access_token`, omits `products`, and adds `additional_consented_products: ["transactions"]` only when `users.plaid_transactions_consented=false`. Every token carries `redirect_uri` (the registered universal link): Plaid requires OAuth for US institutions, in update mode too. When Plaid refuses an update-mode token, the server returns 409 `UPDATE_MODE_UNAVAILABLE` and the client retries with `link`, which queues the old Item for removal (2.9).
- `POST /api/plaid/exchange_public_token`: unchanged; a second exchange for the same user within 60 seconds returns 409 `LINK_IN_PROGRESS`.
- `POST /api/plaid/update-mode-complete` (no body) after update-mode `onSuccess`: `/item/get` (assert `transactions` in `consented_products` when consent was requested, else NEEDS_CONSENT stays), `/item/webhook/update` (the link-token `webhook` is inert in update mode), then 2.4 steps 4 to 6 if consent is new, else the LOGIN_REPAIRED path of 5.7; writes apply only when the `item_id` equals `users.plaid_item_id`.
- `POST /api/plaid/webhook`: permitAll, filter-verified (2.8). `GET /api/plaid/primary-bank-account` loses its token field and gains `mask` and `itemStatus` in Phase 0 and `consentExpiresAt` in Phase 1 (with the PENDING_DISCONNECT handling) (drives the PENDING_DISCONNECT card).

### 8.9 Deleted endpoints

`POST /api/investment-schedule/update-ach-request-id`, `InvestmentScheduleService.updateWithAchRequestId`, `UpdateAchRequestIdRequest` and the two client stubs are deleted: it let any client set `achRequestId` and unpause; `AchRelationshipService.adopt` is the single writer (7.8). `/user-data`, `/access-token` and the two dead `*_anonymous` permitAll entries go too.

### 8.10 Dev, admin and support surfaces

Dev endpoints stay under `/api/dev/paycheck/*` and never exist in prod (12.2). Section 11's interventions live under a prod-registered `/api/admin/paycheck/*`: separate `ADMIN_TOOLS_KEY` env var (`BrokerEnvironmentGuard` refuses prod when empty), required `X-Admin-Actor` header, every call logged to `admin_actions` (actor, user, verb, before and after, reason); verbs are the 12.2 intervention verbs minus sandbox and clock. Support never sets an amount, creates or clears an authorization, or resumes from REVOKED; only the user's Authorize tap does. Runbooks in 12.4.

## 9. Frontend changes

Merge resolutions: screen 2 is the child component `AuthorizeInvestingComponent`; post-onboarding Review & Authorize opens it in a full-screen `ion-modal`, never via the onboarding route. Copy additions beyond the mockup are exactly three (1.3): the capped-amount tip, the 90-second "Still looking" line, the not-found subtitle.

### 9.1 Route and progress topology

Screens 1 and 2 live at `/investment-schedule` inside `InvestmentScheduleComponent` as `subStep` 1 and 2 with resume key `fred.investmentSchedule.step`; no new route or progress flag. `investmentScheduleCompleted` is set by the server at Authorize (8.3) and by the client's `completeStep`; "Looks good" does not set it, so a kill between the taps relaunches to screen 2 (key `'2'`) with `amount = savedAmount`. An unrevoked `status.authorization` on load skips to `/investment-confirmation`.

### 9.2 Screen 1 ("Found your paycheck.")

The component gains `view: 'searching' | 'found' | 'manual'`, `subStep: 1 | 2` and `cameFromDetection`; today's manual markup is wrapped in `@if (view === 'manual')`; the shell and `appKeyboardAvoid` stay; the mockup's new-block styles are copied verbatim. `monthlyGoal` comes from 8.1.

Bindings map the 8.1 fields one to one (`nextExpectedDate` as `EEE, MMM d`, also in First investment). `#amt-found` starts at `savedAmount ?? prefillAmount`, re-renders the formatted amounts on input, and floors to 1 on blur. The info tip is hidden when `goalSource='default'`, reads "Set from your **${goal}/mo** goal: **${amount}** each payday. Change it anytime." when `prefill == suggested`, else the capped variant of 4.9. "Looks good" is disabled unless `view === 'found'` and `1 <= amount <= affordabilityCap`; above the cap "Keep it at ${cap} or less. That's 90 percent of your typical paycheck." shows under the input and "Looks good" stays disabled.

Pending variant (`searching`): the mockup's third frame verbatim, "Scanning {fundingAccountLabel}" bound. Plaid gives no upper bound, so the locked one-minute line is reassurance, never a timeout. Timing anchors to `detectionStartedAt`: at 90 seconds the subtitle's second line becomes "Still looking. You can set it manually any time."; at 5 minutes, or when `historyComplete=true` with `state=UNDETECTABLE`, the view flips to `manual` with the not-found subtitle (9.3), and a 30-second background poll flips back to `found` while the manual amount is untouched. Polling: 3 seconds, 10 seconds after 90 seconds, stopped at 5 minutes, restarted in `ionViewWillEnter`; one failed tick keeps the pending frame, three in a row fall back to the manual form (`trackErrorShown('load_failed')`).

State routing: SEARCHING pending; PROVISIONAL and CONFIRMED found; UNDETECTABLE, NEEDS_CONSENT, ITEM_BROKEN and MANUAL render the manual form; `shadow=true` renders the manual form with no detection UI. "Looks good": `trackOnboardingStepCompleted(..., 1)` at the tap, `POST /detected`, `clearScheduleCache()`, key `'2'`, `subStep = 2`, `trackOnboardingStepViewed(..., 2)`. Errors: alert and stay; 422 shows the cap line inline; 409 `STREAM_CHANGED` reloads; 409 `ALREADY_AUTHORIZED` goes to `/investment-confirmation`.

### 9.3 Manual fallback ("Set manually")

`view = 'manual'`, `cameFromDetection = true`; the existing form renders unchanged, pre-filled from `manualPrefill`. Hero subtitle when no paycheck was found: "We haven't spotted a direct deposit yet, so let's set this up together." `goBack()` returns to the previous view when `cameFromDetection`, else to `/link-bank`. Save posts `/set-manual` with `cameFromDetection` when the row is DETECTED, `/create` otherwise, then `completeStep` and `/investment-confirmation` as today.

### 9.4 Screen 2 (`AuthorizeInvestingComponent`, "Put it on autopilot.")

New `components/investment-schedule/authorize-investing.component`, rendered when `subStep === 2` or presented as a modal, inputs `amount`, `status` (8.1), `monthlyGoal`, `origin: 'onboarding' | 'recurring'`. Header with back button; no `appKeyboardAvoid`. The hashed text is the text on screen, never a cached copy after the amount changes.

Bindings: recap pills = live amount, `cadenceLabel`, "Starts {MMM d}" from `nextExpectedDate`; reaching pager panel 2 (scroll or tab) sets `seenAuth = true`; panel 2 renders `paragraphs` from `/text`, re-fetched whenever the amount changes; projections: `monthly = amount x factor`, `annual = monthly x 12`, `shortfall = max(0, round(goal - monthly))` with `warning` "{shortfall} short of your monthly goal"; "Authorize" is disabled until `seenAuth` and `textLoaded`.

By origin. Onboarding: back emits to the parent (`subStep = 1`, key `'1'`, amount kept); no PIN prompt, `stepUp: 'NONE'`; on 201 `clearScheduleCache()`, `completeStep`, remove the key, navigate to `/investment-confirmation` (replaceUrl) regardless of completeStep; 422 or 409 `STREAM_CHANGED` returns to screen 1 with the cap line. Recurring (modal): back or swipe dismisses `{ authorized: false }`; no PIN prompt at the Authorize tap (decision 4), `stepUp: 'NONE'`; on 201 dismiss `{ authorized: true, stillPaused }`, the page reloads and when `stillPaused` toasts "Authorized. Paycheck investing is still paused; resume when you're ready."; 422 or 409 `STREAM_CHANGED` dismisses `{ error, cap }` and the page shows the cap line.

Authorize: `trackOnboardingStepCompleted(..., 2)` at the tap, `POST /api/ach-authorizations` echoing `textVersion` and `textHash`. 409 `TEXT_MISMATCH` re-fetches, resets `seenAuth`, toasts "The wording was updated. Please read it again."; 409 `ALREADY_AUTHORIZED` behaves like 201; 423 alerts "Paycheck investing is not available right now."; 5xx alerts.

### 9.5 Analytics

Reuse `OnboardingStep 'investment_schedule'` with `sub_step` 1 and 2: viewed in `ngOnInit` (resumed when the key is `'2'` or `savedAmount` is set) and on entering screen 2, completed at each tap before the save; no PII or amounts in client events. `SERVER_EVENT_NAMES` gains the nine section 8 names (`FREDdocs/analytics-events.md`). Both components get analytics specs (13.1).

### 9.6 investment-confirmation

PER TRANSFER reads `investmentAmount` directly; the DETECTED card shows `cadenceLabel`, `monthlyAmount` and `scheduleDescription`. `goBack` for DETECTED sets key `'2'` so screen 2 reopens with `savedAmount`. Tier step untouched; `fred.investmentConfirmation.step` is cleared on success.

### 9.7 `/recurring-investments` state by state

Shell stays the blue settings hero; `investment.service.ts` gains the 8.5 fields; new status classes (searching, dormant, error) and the mockup's card styles. In DETECTED the frequency and date rows are static, `hasChanges()` compares amount only, `getEstimatedTradeWindow` uses `nextExpectedDate`. PIN step-up precedes every mutation except Authorize (decision 4); pause keeps the streak alert. The card shows PER PAYCHECK, FREQUENCY (`cadenceLabel`, "about N times a year"), NEXT EXPECTED and the pay-source, plus a `.helper-hint` in CONFIRMED, PROVISIONAL and DORMANT: "New job? FRED skips the first paycheck from a new employer while it confirms it, then follows it automatically." The streak survives every state.

Banner precedence: `holdReason`, then `isPaused`, then `state`. Hold rows apply in BOTH modes (a held MANUAL row is no longer byte-identical to today):

| holdReason | Banner (title / sub) | Actions |
|---|---|---|
| ACH_RETURN_UNAUTHORIZED | the REVOKED row with its ACH_RETURN_UNAUTHORIZED copy | "Contact support" (mailto help@fredvested.com) replaces every action |
| ACH_RETURN_ACCOUNT | `.status-error`, `link_off`, "Reconnect your bank" / "Your bank could not accept the transfer. Reconnect your bank to continue." | Reconnect only |
| MANUAL_REVIEW | `.status-error`, `gavel`, "Looking into a returned transfer" / "Nothing is pulled until it's sorted. Email help@fredvested.com with questions." | none |
| KILL_SWITCH | `.status-paused`, "Paycheck investing is temporarily unavailable" / "Nothing is pulled. Your schedule and streak are safe." | Set manually only |
| BANK_CHANGE | `.status-paused`, "Waiting on your new bank" / "Investing resumes once the new account is verified." | pause hidden |

| State | Banner (title / sub) | Actions |
|---|---|---|
| MANUAL | today's; with `optOut=false` and a primary found, `.disclosure-row` "Invest when your paycheck lands instead / Turn on" (update mode if consent is missing, `/detected/opt-in`, then the modal; MANUAL pulls continue until Authorize); Phase 2 | today's |
| SEARCHING | `.status-searching`, `search`, "Looking for your paycheck" / "Plaid is pulling recent deposits. This can take a minute or two."; card "Pending", "Scanning {fundingAccountLabel}" | "Don't want to wait? / Set manually" |
| UNDETECTABLE | `.status-searching` tokens, "We couldn't spot a direct deposit" / "Set a schedule manually. FRED keeps looking and will tell you if it finds one." | Set manually |
| PROVISIONAL or CONFIRMED, no authorization | `.status-paused`, "Almost there" / "Authorize paycheck investing to start" | `.cta-btn` "Review & Authorize" (modal); Set manually |
| CONFIRMED, PROVISIONAL | `.status-active`, `check_circle`, "Paycheck autopilot on" / "Invests ${X} the night each {payer} paycheck posts"; paused: `.status-paused`, "Paused" / "Nothing will be pulled until you resume"; PROVISIONAL adds `.pay-chip` "Still confirming your paycheck pattern" and "{depositsFound} deposits found" | Pause / Resume; "Adjust Amount" input "Invest per paycheck" capped at `affordabilityCap` before anything opens (cap line), Save (decrease, `/amount`) or "Review & Authorize" (increase, modal); Set manually; "Authorized {date} · View" (sheet with a Stop button); "Stop paycheck investing"; `lastSkip` line ("Skipped {date}: paused" for PAUSED) |
| DORMANT | `.status-dormant`, `hourglass_top`, "Waiting for your paycheck" / "No {payer} deposit since {lastDepositDate}. Nothing is pulled until it returns."; NEXT EXPECTED "When it returns" | as CONFIRMED (resume toasts "Resumed. FRED will invest when your paycheck returns.") plus snooze row "I'm between paychecks, check back in a month" |
| ITEM_BROKEN, NEEDS_CONSENT | `.status-error`, `link_off`, "Reconnect your bank" / "FRED lost access to {institution}. Paycheck investing is paused until you reconnect." or "FRED needs permission to read deposits at {institution}."; PENDING_DISCONNECT shows "Your {institution} connection expires {date}." with no hold | "Reconnect" (`update`, retry with `link` on 409 `UPDATE_MODE_UNAVAILABLE`); Set manually; pause hidden |
| REVOKED | `.status-error` (`.status-paused` when `revokeReason=USER`), `gavel`, "Authorization needed"; sub by reason: USER "You stopped paycheck investing on {date}"; ACH_RETURN_UNAUTHORIZED "Your bank returned a transfer as unauthorized. Contact support to continue."; EMPLOYER_SWITCH_DOWNSCALE "Your new paycheck is smaller; FRED suggests ${Y} per paycheck"; BANK_CHANGE "You changed banks. Re-authorize on the new account." | "Review & Authorize" (modal, prefilled at or below the cap); Set manually (Contact support under the return hold) |

"Stop paycheck investing": confirm alert ("FRED will stop pulling from your paycheck. Nothing more is transferred. Your streak stays, and you can authorize again any time." Cancel / Stop), PIN step-up, `POST /{id}/revoke`, `clearScheduleCache`, then the REVOKED row and toast "Paycheck investing is off." Pause and resume are hidden in the four 409 holds of 8.7, ITEM_BROKEN and SEARCHING; a 409 on resume maps to its banner plus a toast.

### 9.8 Other surfaces and deletions

- MFU: `?suggestedAmount` stays. CONFIRMED, PROVISIONAL and DORMANT seed `min(suggestedAmount, affordabilityCap)` (capped tip when clamped) and above `authorizedAmount` the CTA reads "Review & Authorize"; REVOKED prefills the modal with the same clamp; other states drop the param with the toast "You can change your amount once your paycheck is confirmed." The dismiss payload gains `mode`; boost copy says "per paycheck".
- change-bank-account: `mode: 'link'`; after exchange navigate to `/recurring-investments` (SEARCHING); its two copy lines become mode-aware. my-profile `formatScheduleText` uses `cadenceLabel` lowercased in DETECTED; freedom-stats keeps its survey-goal precedence but its schedule fallback and the MFU recurring amount honor `contributing=false` in DORMANT, ITEM_BROKEN and REVOKED (decision 9).
- tab3: the badge derives from `authService.getCurrentInvestmentSchedule()` (cached, cleared by every mutation), re-read on the tab3 activation pulse and in `ionViewWillEnter`: AWAITING_AUTHORIZATION or REVOKED "Authorize" (danger unless `revokeReason=USER`); ITEM_BROKEN, NEEDS_CONSENT or ACH_RETURN_ACCOUNT "Reconnect"; `isPaused` "Paused". `setupDemoData()` and its bindings are removed.
- Chat: `get_investment_schedule` adds the 8.5 status fields and redefines `active = !isPaused && holdReason == null && (mode == 'MANUAL' || detectionState in (PROVISIONAL, CONFIRMED))`. `update_investment_amount` in DETECTED calls the 8.6 service: a decrease applies in place ("per paycheck"); an increase is refused before any write with "Raising your amount needs a quick Authorize in Settings, Recurring Investments"; `frequency` is never passed. `resume_investing` in DETECTED says "FRED will invest the night your next {payer} paycheck posts (expected {nextExpectedDate})." and surfaces the 8.7 409 reasons. `FredKnowledge` and `FredConstitution` are rewritten accordingly (no action block above `authorizedAmount` in DETECTED). `InvestmentController` and `ChatService` label `nextInvestmentDate` as expected in DETECTED.
- `linkplaid.component.ts`: Plaid requires OAuth for US institutions and deprecates webview Link (Chase may block it). Phase 0 persists `link_token` in `sessionStorage` and re-creates Link with `receivedRedirectUri` on return (both modes); if the `ins_127287` device test (13.3) fails, the web SDK is replaced by native LinkKit or Hosted Link (`is_mobile_app=true`), open decision 1 and a Phase 0 gate. `goBack` is corrected to `/kyc-verification`.
- Delete the orphans `services/plaid-data.service.ts` and `models/plaid/*` (`services/plaid.service.ts` stays) and the two `updateAchRequestId` stubs (8.9).

## 10. Notifications

`NotificationService.sendOnce(user, type, periodKey, builder)` is the only sender. Email: `EmailService.hasBeenSent` and `record` run in one transaction before `sendEmail` (today it does not). Push and in-app notices use the same `email_log (email_type, user_id, period_key)` ledger (3.9), inserted first; a duplicate key means already sent. Keys are at most 10 characters (ISO date, `YYYY-MM`, or an id) to fit `email_log.period_key`; the reason lives in the type name; a test pins both. `NotificationGate.suppressed(user)` is true under shadow, `enabled=false` or `hold_reason=KILL_SWITCH`; every DETECTED-only row checks it and logs `paycheck.notice.suppressed{reason}` instead (Phase 1 exit: zero user-facing DETECTED notices). `InvestmentScheduler` computes `today` in `America/New_York` and passes it to `PaydayNotificationService`. A "night" is the ET day FRED first sees the posted deposit, so DETECTED copy says "when FRED sees it", never "payday night". SKIPPED `PAUSED` sends nothing. A test rejects em dashes in these strings and the hashed templates; amounts, payer and dates are examples.

| emailType (mode); periodKey | Copy (push = first sentence) |
|---|---|
| PAYDAY_D1 (MANUAL); date; no blocking hold | "FRED will invest $300 tomorrow. Your Freedom Date moves closer." |
| PAYDAY_D1 (DETECTED); date; eligible per 5.8 | "Your Acme Corp paycheck should land tomorrow. When FRED sees it post, it starts your $300 transfer that night." |
| PAYDAY_D0 (both); batch night; FUNDING_INITIATED, recurring only | "FRED started your $300 transfer. It's invested once it clears, usually 1 to 3 business days." DETECTED prefixes "FRED saw your Acme Corp paycheck and" |
| PAYDAY_D1_AFTER (both); batch night; still FUNDING_INITIATED | "Your $300 transfer is on its way. We'll let you know when it's invested." |
| INVESTED (both); execution id | "Your $300 was invested. Your Freedom Date moves closer." |
| PAYCHECK_FOUND (DETECTED, or MANUAL with `optOut=false`); stream id | "Found your paycheck: Acme Corp, every other Friday. Review and authorize in Recurring Investments." MANUAL: "...Turn it on in Recurring Investments." |
| SKIP_AFFORDABILITY; period key | "Your paycheck was smaller than usual, so FRED skipped this one. Nothing was pulled." Second in a row adds "Want to adjust your per-paycheck amount?" |
| SKIP_CATCHUP; period key | "Two paychecks showed up at once. FRED invested on the newest and skipped the other." |
| SKIP_HOLD_ACH, SKIP_HOLD, SKIP_NO_AUTH; `YYYY-MM` | "Your paycheck landed, but your bank link isn't ready yet, so FRED skipped this one." / "...but paycheck investing is on hold..." / "...but FRED needs a fresh authorization first." |
| DORMANT; `dormant_since`; not while snoozed | "No Acme Corp deposit since Oct 9. FRED will wait. Nothing is pulled and your streak is safe." |
| INTERRUPTION; `dormant_since` | "It looks like your regular paycheck paused. FRED will wait. Nothing is pulled and your streak is safe." |
| WELCOME_BACK; deposit date | "Welcome back. FRED spotted your Acme Corp paycheck and will invest on the next one." |
| NEW_EMPLOYER; new stream id | "FRED spotted a new paycheck from Globex and will follow it." Downscale adds "It's smaller than before, so FRED suggests $220 per paycheck." |
| CADENCE_CHANGE; check date | "Your pay schedule changed to twice a month. FRED adjusted to $325 per paycheck to keep $650 a month." The increase variant asks to review and authorize. |
| RECONNECT (both); incident date; LOGIN_REQUIRED, `consentExpiresAt` within 7 days, NEEDS_CONSENT; never NOT_SUPPORTED | "Reconnect your Chase account so FRED can keep spotting your paycheck. Paycheck investing is paused until then." MANUAL: "...so your investing keeps running." |
| ACH_AUTHORIZATION (both); authorization id; subject "Your FRED paycheck investing authorization" | the rendered text, the recap and the 7.2 rights block |
| AUTHORIZATION_REVOKED (both); authorization id | "Paycheck investing is off as of Oct 19. Nothing more will be pulled." |
| AUTHORIZATION_NEEDED; authorization id; system revokes except SET_MANUAL and SUPERSEDED | "Paycheck investing is paused until you review and authorize again." |
| ACH_RETURNED (both); execution id; class per 7.7 | NSF: "Your bank returned the $300 transfer for insufficient funds. FRED did not retry." UNAUTHORIZED or STOP_PAYMENT: "Your bank reported the transfer as unauthorized. Paycheck investing is on hold. We'll email you to sort it out." ACCOUNT_INVALID: "Your bank could not accept the transfer. Reconnect your bank to continue." OTHER: "Your bank returned the $300 transfer. FRED did not retry and is looking into it." OTHER is expected at first (Alpaca's `reason` is undocumented free text; the sandbox cannot produce RETURNED). |
| DORMANT_120; `dormant_since`; no interruption stream, no snooze | "Still want paycheck autopilot? FRED hasn't seen a paycheck since Oct 9." |

## 11. Edge cases and manual intervention matrix

Hold means `hold_reason`, the system's stop; `is_paused` is the user's stop (system writes: the 2.4 placeholder and `resetForBankChange` only). `MANUAL_REVIEW` is cleared only by an operator. Nothing retries money. Tools live under `/api/admin/paycheck/*` (12.2, with the support rule).

| Scenario | Automatic handling | Human needed when | Signal and tool |
|---|---|---|---|
| Months of history, two weeks, no ACH credits, or a slow pull | CONFIRMED about a minute after exchange (no Plaid SLA; the 90 s line is a UX choice). One deposit is CANDIDATE, the second on-cadence one makes PROVISIONAL; UNDETECTABLE renders the manual form. Set manually from SEARCHING, UNDETECTABLE or NEEDS_CONSENT keeps `paycheck_autopilot_opt_out=false`, so evaluation continues and PAYCHECK_FOUND fires later; opt-out is written only when a found paycheck is rejected. | SEARCHING over 24 h | `status`, `paycheck.searching_age_h`; `sync` |
| Legacy AUTH+IDENTITY Item | Only Items linked through a DTM-enabled customization return 400 `INVALID_INPUT / ADDITIONAL_CONSENT_REQUIRED`; a pre-DTM Item just initializes on the first sync; `/item/get consented_products` decides. | Update-mode token creation fails (create a fresh Link token); an OAuth bank cannot return to the app (decision 1) | `plaid.items{status}`; relink queues `/item/remove` |
| Stream split, merged or tombstoned; cadence unsettled; semi-monthly `LAST_BD`; 4-weekly; joint account or split deposit | Payer token plus cadence, `stream_id` unstable; re-check on confirm and weekly; rescale preserves `monthlyAmount`, an increase needs re-authorization; 4-weekly and UNKNOWN rejected; larger stream primary on a joint account | PRIMARY flips twice in 7 days; 3 wrong predictions in a row; two CONFIRMED payers | `paycheck.primary_switches_7d`, `paycheck.prediction_misses_consecutive`; `promote-stream`, `retire-stream` |
| Pending deposit; reversal; West-coast posting dates | Pending never confirms. The posted row arrives in `added[]` with the pending id in `removed[]`; `pending_transaction_id` may be null, so matching never requires it. `removed` voids the cycle; 11:55 re-verifies ACTIVE and posted; `modified` re-runs affordability. A night is the ET day FRED first sees the deposit. | Reversal after the 11:59 POST: expect an R-return, confirm no double debit | `cycle_voided_after_execution`; `paycheck.confirm_lag_days{institution_id}` |
| Smaller, larger, bonus, second job; two cycles one night; benefits, teacher summer, FMLA; employer switch; severance | Smaller: SKIPPED AFFORDABILITY, one notice; larger: asymmetric match; extras: gap, window, payer token; two confirmable: the NEWEST runs, the other SKIPPED CATCH_UP_CAP; DORMANT or benefits signal: no pull, streak kept, snooze, resurrection; succession at the second on-cadence deposit, a downscale revokes and deep-links Review & Authorize | Never | `paycheck.skip{reason}`, `paycheck.users{state}` |
| Webhook lost, duplicated, out of order, FAILED or rejected | Plaid retries a non-2xx or 10 s timeout for up to 24 h (30 s, then 4x); any other 4xx than 408, 425, 429 is permanent, so the receiver answers 503 on transient failures and 401 only on a bad signature or body hash. After 24 h the webhook is gone; `/beta/webhook_events/list` (7 days, payloads to re-process) excludes `TRANSACTIONS`, so recovery is `/transactions/sync` by cursor (22:30 is FRED's cadence, not Plaid's). Storms: the drainer claims rows `FOR UPDATE SKIP LOCKED` in batches, coalesced per `item_id`; alert on `inbox.oldest_age_sec` over 30 min or `rejected_ratio_24h` over 50 percent. | Reconcile fails 2 nights for one user; FAILED after 3 attempts; 401 spike | `inbox.failed`, `plaid.webhook.rejected{reason}`; `sync`; the `webhooks` verbs |
| `TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION`; `..._ITEM_LIMIT_EXCEEDED` | Restart from the first-page cursor of the current update up to 3 times, then the reconcile; limit: `SYNC_LIMIT`, UNDETECTABLE, hold MANUAL_REVIEW | Mutation 3 nights running; limit always | `plaid.sync.errors{code,institution_id,item_id}`; `set-manual` |
| ITEM_LOGIN_REQUIRED, PENDING_DISCONNECT passed, ITEM_NOT_FOUND; USER_PERMISSION_REVOKED, USER_ACCOUNT_REVOKED | ITEM webhooks carry `item_id`, `user_id`, `environment` (USER_ACCOUNT_REVOKED adds `account_id`, PENDING_DISCONNECT adds `disconnect_time`); rows whose `environment != plaid.env` or `item_id != users.plaid_item_id` are IGNORED. LOGIN_REPAIRED applies only from `LOGIN_REQUIRED`, else PROCESSED with a WARN. USER_PERMISSION_REVOKED may not fire for a revocation at the institution, and only revocation via my.plaid.com or Plaid support ends billing, so FRED always queues `/item/remove`. | Item broken 14 days (nudge) or 30 days (close); revoked and update-mode token creation fails | `plaid.items.broken_age_days`; `remove-item` |
| Two devices or a double tap link twice | Exchange serialized per user (a second within 60 s: 409 `LINK_IN_PROGRESS`). Item-state writes apply only when the call's `item_id` equals `users.plaid_item_id`, re-read before any cursor is persisted. `/detected` and `/text` return 409 `ALREADY_AUTHORIZED` over an unrevoked authorization at another amount. | Never | `plaid.stale_item_event` |
| Institution down (400 `INSTITUTION_DOWN`, `INSTITUTION_NOT_RESPONDING`, `INSTITUTION_NOT_AVAILABLE`), Plaid 500, `PLANNED_MAINTENANCE`, 429; outage during the reconcile | Transient keyed on the exact `error_code` (other `INSTITUTION_ERROR` codes are permanent; unrecognized 5xx and `PRODUCT_NOT_READY` are transient). The reconcile stops dispatching at 23:20 ET and aborts after 5 consecutive transient results; the next one pulls a night late, never two at once. | Same institution or Item 3 nights; `plaid.outage` 2 nights | `plaid.sync.errors{code,institution_id,item_id}`, `paycheck.reconcile.truncated`, `plaid.outage` |
| Bank change | Per 7.8; `previous_ach_relationship_id` is recorded before the exchange nulls the user field. The 409 branch in `createAchRelationship` returns an error and never adopts; `adopt` refuses the previous id, a mask unlike `plaid_account_mask`, or a `created_at` before `plaid_item_linked_at`, setting `MANUAL_REVIEW` on refusal. Alpaca's REST enum lacks CANCELED and leaves "active" undefined, so the 15-minute poll waits for no QUEUED, APPROVED, PENDING or CANCEL_REQUESTED row. BANK_CHANGE clears on `adopt` (MANUAL) or once an unrevoked authorization binds the new account and relationship (DETECTED). | `/item/remove` failing 7 days; CANCEL_REQUESTED over 24 h or CANCEL_FAILED (SSE only); old-relationship transfer pending 7 days; BANK_CHANGE 7 days | `ach.relationship.*`, `holds.by_reason`; `alpaca/{userId}/relationships` |
| Crash between the Alpaca POST and the commit; null relationship id; deploy during the batch window; 11:55 re-run; row queued at 23:59:xx | Conditional stamp (`WHERE transfer_attempted_at IS NULL`, rowcount must equal the batch) in REQUIRES_NEW; a conflict skips the user and alerts; a null id fails fast; each DETECTED row's authorization relationship must match the id sent. Job locks; deploy freeze 23:45 to 00:10 ET. Each schedule runs in its own REQUIRES_NEW unit with `existsBySourceKey` and `existsByNightKey` before insert; `createdAt < batchStart`. | Stranded stamp: always (list transfers, attach the id, clear the stamp); a SCHEDULED recurring execution older than a day | `executions.stranded` (30 min), `executions.claim_conflict`; `executions/{id}/attach-transfer` |
| ACH return NSF (R01, R09) | FUNDING_FAILED, one notice, no retry, no hold | Two in 90 days: hold MANUAL_REVIEW | `clear-review` |
| ACH return unauthorized or stop payment (R05, R07, R08, R10, R11, R29), including a late R10 | Per 7.7 (hold, revoke, email; `is_paused` untouched). Alpaca documents `reason` only as "Cause of the status" (an R code in it is unverified; the SSE event has no reason), so the sweep reads `GET .../transfers` plus the trading account's `restrict_to_liquidation_reasons.ach_return`. Consumer unauthorized returns arrive "up to 60 calendar days after the settlement date" (Plaid). Set manually becomes "Contact support"; the MANUAL eligibility branch refuses this hold. | Always, before any new authorization: verify the record, talk to the user; late return: liquidation decision | `ach.return{UNAUTHORIZED}`; `ach-authorizations/{userId}`; sweep report |
| ACH return account invalid (R02, R03, R04, R13, R16, R20); no R code (sandbox never produces one) | Hold ACH_RETURN_ACCOUNT and reconnect card; no code: FUNDING_FAILED, hold MANUAL_REVIEW | Account closed: relink; no code: always, until production proves the `reason` format | classifier log |
| Account deletion, privacy request, Alpaca REJECTED | `UserDeletionService.delete(user, reason)` is the single path for `handleRejected`, close-account and the admin verb: queue `/item/remove`, DELETE the Alpaca relationship, purge detection data, anonymize authorization PII, keep the row. Deferred while a FUNDING_INITIATED execution exists or a transfer settled under 60 days ago. | Deferred deletion older than 60 days | `users.deletion_deferred`; `users/{id}/delete` |

## 12. Ops tooling

### 12.1 Feature flags and configuration

Properties, env-overridable, prefix `paycheck.autopilot.*`:

- `enabled` (false): master. Off: inbox rows are stored, nothing else runs, existing DETECTED rows get `hold_reason=KILL_SWITCH`, onboarding shows the manual form.
- `shadow` (true in Phase 1): the exchange leaves `mode=MANUAL`; detection runs against the MANUAL row; `SHADOW_CONFIRMED` is the first gate in 4.7 step 9; `NotificationGate.suppressed(user)` (shadow, `enabled=false`, KILL_SWITCH) gates every DETECTED-mode `emailType` in section 10; `/set-manual` writes `opt_out` only when `cameFromDetection=true` and shadow is off.
- `pulls.enabled` (false until Phase 2) and `pulls.allowlist`: gate the 11:55 DETECTED branch and Authorize (423 `KILL_SWITCH`).
- `refresh.enabled` (false), `purgeStreamsOnBreak` (false); tunables `affordability.share` (0.90), `score.provisional` (55), `score.confirmed` (70), `daysRequested` (365, on every sync; the first successful sync initializes Transactions and locks history length).
- `investment.scheduler.enabled` is finally read: when false every job returns immediately (ci).
- Production Plaid block (Phase 0): every Plaid property from env vars with `plaid.env=production`; the committed sandbox secret is rotated. `BrokerEnvironmentGuard` (already refusing `plaid.env=sandbox` in prod) gains: prod requires an https webhook URL that is not loopback, trycloudflare or ngrok and refuses `ci-bypass-key`, the known sandbox secret, `app.ops.alert.provider=logging` and an unset `ADMIN_TOOLS_KEY`; non-prod requires `plaid.env=sandbox`.
- Pools: scheduler pool 6 (today 4), Hikari at least 10, all Plaid I/O on a bounded `plaidExecutor`; the exchange's `/item/get` leaves its transaction.

### 12.2 Admin and dev endpoints

`/api/admin/paycheck/*` is registered in every profile including prod. Guard: `ADMIN_TOOLS_KEY` in `X-Admin-Key` plus a required `X-Admin-Actor`; every call writes `admin_actions` (actor, user, verb, before and after JSON, reason, time). Support rule: support never sets an amount, never creates, clears or revokes an authorization, never resumes from REVOKED; only the user's Authorize tap does those. Verbs: `GET /{userId}/status` (full dump), `GET /{userId}/plaid-item`, `POST /{userId}/` `sync`, `evaluate`, `reconcile`, `hold {reason|null}`, `clear-review`, `snooze`, `set-manual`, `promote-stream/{id}`, `retire-stream/{id}`, `cycles/{id}/void`, `GET /webhooks?status=FAILED`, `POST /webhooks/{id}/replay`, `POST /webhooks/replay {from}` (reads `/beta/webhook_events/list` with `webhook_types:["ITEM"]`, re-processes each `payload`, dedupes on `webhook_message_id`); under `/api/admin/`: `plaid/items`, `plaid/{userId}/remove-item`, `ach-authorizations/{userId}` (read only), `alpaca/{userId}/relationships`, `executions/{id}/attach-transfer`, `users/{id}/delete`, `encryption/reencrypt`.

Dev-only `/api/dev/paycheck/*` (non-prod profiles; `DevToolsProdGuard` unchanged): `simulate-cron?date=`, `clock {date}`, `shadow-report`, `sandbox/paycheck`, `sandbox/fire {code}`, `sandbox/reset-login`. The ci webhook bypass is a `@Profile("ci")` verifier bean, not a property.

### 12.3 Logs, metrics, alerts

Logs: one SLF4J key=value line per decision (`paycheck.*`, `plaid.webhook`, `plaid.error`, `ach.return`, `admin.action`) with ids, decision, reason and Plaid `request_id`; never the access token, a descriptor or an amount at INFO. Counters: `plaid.webhook.received{code}`, `plaid.webhook.rejected{reason}`, `plaid.sync.errors{code,institution_id,item_id}`, `paycheck.cycle.state{to}`, `paycheck.skip{reason}`, `paycheck.notice.suppressed{reason}`, `ach.return{class}`, `plaid.stale_item_event`. Gauges: `paycheck.users{state}`, `plaid.items{status}`, `plaid.items.billable`, `holds.by_reason{reason}`, `inbox.backlog`, `paycheck.confirm_lag_days{institution_id}`.

Alert channel (a Phase 1 gate): `OpsAlertService` with its own provider `app.ops.alert.provider` (SES, Resend, SMTP or a push webhook), independent of user email; dedupe in `ops_alerts` keyed `(alert_type, subject_id, day)`; a 09:00 ET heartbeat digest carries every gauge, so silence is itself a signal. Alerts. Money: `ach.return{UNAUTHORIZED}`, `cycle_voided_after_execution`, `ach.relationship.mismatch`, `executions.stranded` (stamp with no transfer id after 30 min, or SCHEDULED recurring older than a day), `executions.claim_conflict`. Inbox: `inbox.failed > 0`, `inbox.oldest_age_sec > 1800`, `rejected_ratio_24h > 0.5`, `rejected{401} > 10` per hour, `{503} > 5` in 10 min, zero webhooks in 24 h. Ages: MANUAL_REVIEW 24 h, BANK_CHANGE 7 days, SEARCHING 24 h, Item broken 30 days, CANCEL_REQUESTED 24 h or CANCEL_FAILED, old-relationship transfer pending 7 days, deletion deferred 60 days. Detection: 2 primary switches in 7 days, 3 prediction misses in a row. Plaid: one `institution_id` or `item_id` erroring 3 nights, `plaid.outage` 2 nights, reconcile late, truncated or throwing. `encryption.decrypt_failures > 0`.

### 12.4 Shadow mode, key rotation, runbooks

Phase 1 runs the full pipeline with `shadow=true`; the report is reviewed weekly to tune 55/70, the 0.90 share and the token lists. Exit: two pay cycles of data and zero user-facing DETECTED notices.

Encryption: `EncryptionService` gains a key-version prefix `enc.v{n}:` with `encryption.keys.{n}` and `encryption.active-key`; decrypt by prefix (legacy `ENC:` is key 1), encrypt with the active key; the admin `ReencryptJob` rewrites every encrypted column in batches. The token copy in `plaid_item_removals` is deliberate: a relink overwrites `users.plaid_access_token`.

Runbooks, one per admin verb, in `FREDdocs/runbooks/paycheck-autopilot.md`: reconnect a broken Item; clear MANUAL_REVIEW after an unauthorized return (talk to the user first, then screen 2); recover a lost cursor (throttled; null-cursor syncs count against Plaid's 500 per minute); after a 503 storm; rotate the webhook URL (`/item/webhook/update` per Item); rotate the encryption key; Alpaca cancellation cleanup; deploy freeze; monthly invoice; privacy deletion.

## 13. Testing plan

House pattern: JUnit 5 plus Mockito, `new Service()` with `ReflectionTestUtils.setField`, `PlaidApi` mocked via a `retrofit2.Call` returning `Response.success(body)` or `Response.error(400, body)`. No new `@SpringBootTest`; `BackendApplicationTests` under ci proves V6 passes `ddl-auto=validate` on MySQL (TINYINT(1) columns declared `columnDefinition = "BOOLEAN DEFAULT FALSE"`, enums `@Enumerated(STRING)` with matching length, V3-style `information_schema` guards on every ALTER). Cron strings pinned like `TlhSchedulerTest`. The plaid-java 46.0.0 bump plus the `PlaidConfig` fix is its own commit; the ci build (`CiStubConfig`) is its compile gate.

### 13.1 Unit tests (named cases)

- Sync: `emptyFirstCallPersistsNoCursor`, `mutationRestartsFromOriginalCursor`, `itemLimitHoldsManualReview`, `consentRequiredFlipsFlag`, `institution400IsTransient`, `postedWithoutPendingIdStillConfirms`, `firstSyncOnAnyPathSendsDaysRequested`, `staleItemNotFoundIgnored`, `cursorNotPersistedWhenItemChangedMidSync`.
- Detection and cycles: the 4.2 gates and cadence fixtures, `twoOnCadenceDepositsMakeProvisional`, `manualRowWithoutOptOutKeepsEvaluating`, `downscaleRevokesAuthorization`, `missSetsDormantHoldNotPaused`, `rejectsLateSeenOver7EtDays`, `affordabilitySkipNeverReduces`, `catchUpCapSkipsOlderConfirmed`, `windowPastSevenDaysIsMissed`, `shadowGateRunsFirst`.
- Eligibility and cron: the four MANUAL pins, `manualHeldByReturnNotReady`, `detectedRequiresConfirmedCycle`, `detectedHoldBlocks`, `detectedRelationshipMismatchBlocks`, `todayUsesNewYork`, `createsOneExecutionWithKeys`, `duplicateKeyOnUserADoesNotRollBackUserB`, `removedOrPendingEventAt1155Voids`, `pauseOrRevokeAt1154Honored`, `killSwitchSkipsAndAllowlistBypasses`, `markerStringUnchanged`, `batchClaimIsConditionalUpdate`, `batchRefusesAuthorizationRelationshipMismatch`, `jobLockPreventsSecondInstance`.
- Returns and authorization: one case per code family, `freeTextReasonWithoutCodeIsOther`, `unauthorizedHoldsAndRevokes`, `secondNsfIn90DaysHoldsManualReview`, `lateReturnFlagsAfterSettlement`, `liquidationFlagReadFromTradingAccount`, `textHashMismatchRejected`, `templatesContainNoEmDash`, `recordsSessionAndPasskey` (non-null credential on a passkey session, `auth_method=PIN` otherwise), `differentAmountReturnsAlreadyAuthorized`, `amountAboveCapRejected`, `createClearsBankChangeHold`.
- Webhooks and reconcile: `rejectsNonEs256`, `rejectsOldIat`, `rejectsBodyHashMismatch`, `keyFetchFailureReturns503`, `expiredKeyAcceptedWithinIatWindow`, `expiredKeyRejectedWhenIatAfterExpiry`, `controllerReceivesFullBodyAfterFilter`, `mappingFailureReturns503`, one case per dispatch row, `identicalDailySyncBodiesAreNotDeduped`, `loginRepairedIgnoredUnlessLoginRequired`, `claimsWithSkipLockedInBatches`, `cronPinned2230NewYork`, `stopsDispatchingAt2320`, `circuitBreaksAfterFiveTransient`, `honorsSchedulerEnabledFalse`.
- Bank change, deletion, notices, config: `recordsPreviousRelationshipBeforeNull`, `conflict409NeverAdoptsOldRelationship`, `retryWaitsForCancel`, `adoptRefusesMaskMismatch`, `firstAdoptionUnpausesNewSchedule`, `adoptNeverUnpausesAfterBankChange`, `adoptNeverClearsUserPause`, `bankChangeHoldClearedOnAdoptManual`, `bankChangeHoldClearedOnReauthDetected`, `rejectedUserWithAuthorizationDeletesCleanly`, `deletionDeferredDuringReturnWindow`, `manualCopyFixed`, `shadowSendsNoUserNotices`, `shadowKeepsManualMode`, `EncryptionServiceTest` (legacy prefix, re-encrypt job), `BrokerEnvironmentGuardTest` (bypass key, tunnel URL, sandbox secret and logging alerts refused in prod).
- Parity and frontend: `FrequencyFactorParityTest` pins one `InvestmentFrequency` enum across the three backend readers and `investment-frequency.utils.spec.ts` the client table; `investment-schedule.component.spec.ts` (state rendering, the 5-minute stop, the `seenAuth` gate, the saved amount on resume), the 9.5 analytics spec, a Karma spec asserting no U+2014 in the 9.7 and 10 strings, and `recurring-investments.page.spec.ts` DETECTED cases.

### 13.2 Sandbox integration tests

Bash under `test/api/` against the ci backend with sandbox keys from CI secrets; skipped when absent. Prerequisites: product access for Transactions and Recurring; a public webhook URL, or captured payloads replayed through the `@Profile("ci")` verifier bean. Sandbox facts (docs read 2026-10-19): `/sandbox/transactions/create` works on any Sandbox Item, up to 10 per call, today or up to 730 days back, never future, so always posted; a pending deposit needs a custom-user fixture with a future `date_posted` and `roll_dates_forward:false` (the flag shifts every date to creation day); Sandbox Items break after 30 days; `reset_login` fires an ITEM `ERROR` webhook; streams for custom users are unverified. Not scriptable in Sandbox (13.3 only): the DTM consent error (`/sandbox/public_token/create` has no consent option, DTM is not enforced), the update-mode UI, and first-sync initialization with `options.days_requested`.

1. `paycheck_e2e.sh`: `/sandbox/public_token/create` at `ins_109508` with `initial_products:[auth,identity,transactions]`, `user_custom`, 13 biweekly -2450.00 `ACME CORP PAYROLL DIR DEP` deposits ending 14 days ago, `roll_dates_forward:true`, `webhook`; exchange through FRED; poll until `historyComplete`; PROVISIONAL or CONFIRMED, BIWEEKLY; "Looks good" and Authorize via the API; authorization row and MOCKED `email_log`; one posted -2450.00 today; `/sync`; one CONFIRMED cycle; the 11:55 job on the test clock: exactly one execution with `authorization_id`, `source_key`, `night_key`, zero on a re-run; the 11:59 batch: `transfer_attempted_at` and one sandbox transfer.
2. `paycheck_pending.sh` (`roll_dates_forward:false`, `date_posted` today+3: pending event, no cycle; a deposit dated today then confirms once). `paycheck_webhooks.sh` (`fire_webhook` `RECURRING_TRANSACTIONS_UPDATE`, `SYNC_UPDATES_AVAILABLE` twice, the same body twice, `RECURRING` before `SYNC`, `reset_login` then `LOGIN_REPAIRED` with the ITEM `ERROR` webhook, hold and clear, `USER_PERMISSION_REVOKED` with a queued `/item/remove`, then a trailing `LOGIN_REPAIRED` that must be a no-op).
3. `paycheck_succession.sh` (two posted `NEWCO PAYROLL` deposits, downscale at 30 percent lower), `paycheck_affordability.sh` (posted -900, SKIPPED AFFORDABILITY), `paycheck_lost_webhook.sh` (no webhook, `/reconcile`, one cycle), `plaid_relink.sh` (second Item: `/item/remove` on the first, SEARCHING, authorization revoked BANK_CHANGE, `previous_ach_relationship_id` recorded, Alpaca DELETE then create with no 409 adoption, BANK_CHANGE cleared after adopt), `paycheck_shadow.sh` (`shadow=true`: `mode=MANUAL`, a SKIPPED_SHADOW_CONFIRMED cycle, zero DETECTED-mode `email_log` rows).

### 13.3 Device checks (iPhone build)

Against sandbox: screen 1 pending to found (3 s poll, backgrounding, the 5-minute stop); screen 2 pager; kill between the two taps, relaunch lands on screen 2 with the saved amount; Set manually from both screen 1 variants; recurring page in every state via the admin hold endpoint; MFU handoff; two-device link. Against a Trial or Production Item (Phase 0 gate): `ins_127287` OAuth link for a NEW Item and an update-mode consent run returning through the universal-link `redirect_uri`; the consent upgrade of a pre-existing AUTH+IDENTITY Item; `/item/get` shows the consent and the requested history depth.

## 14. Rollout phases (solo dev, feature flags)

**Phase 0, before beta, about 5 days, nothing user-visible.** Gates: the OAuth answer (decision 1) proven on a device with `ins_127287` (Plaid requires OAuth support and `redirect_uri` on every link token, update mode included, and has deprecated webview Link); the 12.1 production block and guard extensions. Dashboard: product access, DTM use case, webhook domain, redirect URI, price. Code: plaid-java 46.0.0 plus the `PlaidConfig` fix as its own commit; V6 with guards and the `user_sessions` credential columns; `InvestmentFrequency` enum; link token fields; `/item/get` consent flag and mask; the verified webhook endpoint (re-readable wrapper, inbox only); `/item/webhook/update` backfill; `/item/remove` queue on relink; the bank-change fix; `night_key` and `source_key` with REQUIRES_NEW per schedule, the conditional batch claim, job locks, `createdAt < batchStart`; MANUAL D-1 copy fix; delete the token-leaking endpoints, `update-ach-request-id` and dead permitAll entries; `UserDeletionService`; recover the 2025 docs and service from `90a8cc1^`; LEGAL review.

**Phase 1, during beta, about 8 days, shadow mode, on Plaid Sandbox (decision 13).** `enabled=true, shadow=true, pulls.enabled=false`. Gate: the ops alert channel and heartbeat digest. Because Sandbox carries no real deposits, shadow data comes from custom sandbox users (13.2) and the 55/70 and 0.90 tuning on real paychecks moves to the Phase 2 allowlist (Andy first); Production and Pay-as-you-go are switched on at the start of Phase 2. Sync, recurring reader, detection, streams, events, shadow-skipped cycles, inbox drainer, 22:30 reconcile, Item-state handling, update-mode consent path and reconnect card, admin surface, shadow report, `NotificationGate`. Exit: two pay cycles tuned, zero user-facing DETECTED notices, `plaid.items.billable` reconciled against the first invoice.

**Phase 2, after beta stabilizes, about 9 to 10 days.** Gates: real `EmailSender` (decision 5), privacy policy live, runbooks, encryption key versioning. `shadow=false`; `pulls.enabled` through the allowlist (Andy first), then everyone. Screens 1 and 2; `AchAuthorization`, templates, email copy, in-app revoke control; DETECTED eligibility and cron pre-flight; affordability gate; `removed` handling; catch-up cap; `/recurring-investments` DETECTED states with the MANUAL "Turn on" disclosure; confirmation card; notifications; return classifier and sweep; chat tools; orphan deletion.

**Phase 3, about 5 days.** Succession recency exception, resurrection, descriptor drift merge, joint-account chooser, split summing, semantic semi-monthly anchors for MANUAL, raise and adjust prompts, MANUAL authorization text, optional `/transactions/refresh` on payday evening (re-read the rate-limit page first; the per-Item limits in 2.7 were not visible on 2026-10-19).

Roughly 27 to 28 build days; Plaid approvals, pricing, the OAuth test, providers and LEGAL run in parallel and are the risk.

## 15. Decisions (resolved with Andy, 2026-10-19)

Every recommendation below was accepted unless an answer says otherwise.

1. OAuth banks inside the WKWebView: universal-link `redirect_uri` plus `receivedRedirectUri` re-init, native LinkKit via a Capacitor plugin, or Hosted Link (`is_mobile_app=true`). A Phase 0 gate. Accepted: `ins_127287` device test first, Hosted Link if it fails.
2. `days_requested`: 365 (accepted); locked per Item at the first sync.
3. Versioned MANUAL authorization at the next schedule edit, Phase 3 (accepted).
4. Step-up on the Authorize tap: NO passkey or PIN step-up (Andy). Other mutations keep the page's existing PIN step-up.
5. Providers: a real `EmailSender` and a real ops alert provider are gates (accepted).
6. ACATS: none during DETECTED onboarding; retirees on pension or Social Security stay manual (accepted).
7. Where the first sync happens, a cost decision: it starts two per-Item subscriptions (Transactions at the first sync, Recurring at the first streams read), billed per UTC month, not pro-rated, ended only by `/item/remove`. Resolved: screen 1 (accepted). Exchange is the backend moment right after Plaid Link succeeds, before the user reaches the schedule page; screen 1 is the page itself.
8. Cost policy: skip the first streams read when "Set manually" is tapped at screen 1; `/item/remove` plus re-link on bank change for MANUAL opt-outs (accepted).
9. DORMANT, ITEM_BROKEN and REVOKED schedules: do NOT contribute to the Freedom estimate or MFU (Andy); see 5.8.
10. Streak credit at queue time (accepted).
11. On ITEM_LOGIN_REQUIRED, keep the stream skeleton (accepted).
12. Affordability share: 0.90 (Andy). Skip only when the per-paycheck amount exceeds 90 percent of the deposit; the UI refuses 91 percent or more with the cap line.
13. Plaid plan: Phase 1 stays on Sandbox; Pay-as-you-go Production is switched on at the start of Phase 2 (Andy).
14. Pause in DETECTED mode keeps zeroing the streak (accepted).
15. The 11:59 batch hardening is approved (Andy). "Mode-neutral" means these changes apply to MANUAL and DETECTED alike and change no selection or summing logic: (a) rows created between 23:59 and midnight are picked up the next night instead of never (`createdAt < batchStart`); (b) a row is claimed before the Alpaca POST so a crash or a double run can never send two transfers; (c) a null or mismatched relationship id fails fast instead of posting; plus job locks and a 23:45 to 00:10 ET deploy freeze.
16. jjwt 0.11.5 with a hand-built `ECPublicKey` (accepted).
17. Server timezone: the JVM default is pinned to UTC in `BackendApplication.main`; Phase 0 moves it to `America/New_York` (Andy wants Eastern time), and every money-path and notification `LocalDate.now()` names the zone explicitly regardless. See 5.8 for the switch-night note.
18. Lump sums: a client UUID on `POST /investment/execute` for `source_key` `LUMP:{userId}:{uuid}` (accepted).
19. Alpaca cancellation tracking: the 15-minute poll (accepted).
20. Third paycheck in a month: invest on it like any other, no heads-up notice, no skip toggle (Andy).
21. Admin surface: `ADMIN_TOOLS_KEY` plus `X-Admin-Actor` with an audit table (accepted).
22. Deletion deferral: 60 days after the last settled transfer (accepted).
23. First-adoption unpause (raised during Phase 0 planning): deleting `updateWithAchRequestId` removed the only unpause of a brand-new MANUAL schedule, which would leave every new user Paused forever. Resolved: `AchRelationshipService.adopt` clears `is_paused` exactly once, on the first-ever adoption (schedule.achRequestId null and users.previous_ach_relationship_id null); never after a bank change and never over a user's pause. Chosen over starting new rows unpaused because it keeps today's Recurring Investments banner exactly as it is during the pre-ACH window.

## 16. Sources

Plaid (read 2026-10-19), all under https://plaid.com/docs/ : `api/link/#linktokencreate`, `link/update-mode/`, `link/data-transparency-messaging-migration-guide/`, `link/initializing-products/`, `link/oauth/`, `link/webview/`; `api/products/transactions/`, `transactions/`, `transactions/webhooks/`; `errors/` and its item, invalid-input, institution, api, transactions and rate-limit-exceeded pages; `api/items/`, `api/webhooks/#webhook-retries`, `api/webhooks/webhook-verification/`, `api/webhooks/webhook-events/`; `api/sandbox/`, `sandbox/user-custom/`, `account/billing/`; ACH return windows `transfer/troubleshooting/`, `errors/transfer/`. plaid-java: https://github.com/plaid/plaid-java/blob/master/CHANGELOG.md ; 46.0.0 published 2026-10-02 per https://repo1.maven.org/maven2/com/plaid/plaid-java/maven-metadata.xml

Alpaca, all under https://docs.alpaca.markets/reference/ : `createachrelationshipforaccount`, `getaccountachrelationships`, `deleteachrelationshipfromaccount`, `gettransfersforaccount`, `subscribetofundingstatussse`, `gettradingaccount` (`admin_configurations.restrict_to_liquidation_reasons.ach_return`), and https://docs.alpaca.markets/us/reference/subscribetoaccountstatussse.md

Repo: `FREDdocs/flyway-baseline.md`, `FREDdocs/analytics-events.md`, `FREDdocs/specs/paycheck-autopilot-mockup.html` (locked UI; artifact https://claude.ai/artifact/DiQa1ZZGKLeJnUtJGGifyd), the spike (`FREDdocs/specs/paycheck-autopilot-spike.md`), and the 2025 attempt from `git show 90a8cc1^:` (`PAYCHECK_DETECTION.md`, `TESTING_PAYCHECK_DETECTION.md`, `PaycheckDetectionService.java`; the commit says only "removed unused files").