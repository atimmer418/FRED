# Paycheck Autopilot runbook (Phase 1, shadow mode)

Phase 1 runs `enabled=true, shadow=true, pulls.enabled=false` on Plaid Sandbox: detection runs for every linked user, a matched paycheck closes its cycle `SKIPPED SHADOW_CONFIRMED` with the amount that would have moved, and nothing moves. Users see only the reconnect card and the RECONNECT email. No verb here moves money, sets an amount, writes a schedule's `status` or `mode`, or touches an authorization.

## Setup

```bash
export FRED_API_BASE=http://localhost:8080 FRED_ADMIN_ACTOR=andy   # or the Railway dev URL
read -rs ADMIN_TOOLS_KEY && export ADMIN_TOOLS_KEY                 # from Railway; never paste it anywhere
P="$FRED_API_BASE/api/admin/paycheck"; V="$FRED_API_BASE/api/dev/paycheck"
A() { curl -s -H "X-Admin-Key: $ADMIN_TOOLS_KEY" -H "X-Admin-Actor: $FRED_ADMIN_ACTOR" -H "Content-Type: application/json" "$@"; }
D() { curl -s -H "X-Dev-Tools-Key: $DEV_TOOLS_KEY" -H "Content-Type: application/json" "$@"; }
```

Wrong key 404, missing actor 400; every admin call writes an `admin_actions` row. Find a user with `SELECT id, public_id FROM users WHERE email = 'the.user@example.com'`, export `USER_ID` and `PUBLIC_ID`, and put only the `public_id` in tickets.

## Weekly shadow review (Mondays, and before the Phase 2 flip)

1. `A "$P/shadow-report?since=$(date -v-7d +%F)"` (Linux `date -d '-7 days' +%F`; 28 days when tuning). Copy the summary into the decision log.
2. Scores (55 PROVISIONAL, 70 CONFIRMED): `breakdown` shows which component starved a real paycheck or over-paid a non-paycheck; for an UNDETECTABLE user you know is paid, `status` lists every stream's score and reject reason.
3. Share (0.90): count cycles per `pullShareBand`; `passesAffordability=false` would be SKIPPED AFFORDABILITY in Phase 2.
4. Lag: `confirmLagDays` above 1 on average means late or lost webhooks; `-1` means an Item break purged the event. Misses on a regular stream mean the cadence or window is wrong.
5. Token lists are code (`paycheck/detect/DescriptorSignals`) and change with a test. Tunables: `PAYCHECK_SCORE_PROVISIONAL`, `PAYCHECK_SCORE_CONFIRMED`, `PAYCHECK_AFFORDABILITY_SHARE` on Railway (locally `paycheck.autopilot.score.provisional`, `.score.confirmed`, `.affordability.share`); record the decision first, redeploy outside the freeze.
6. Exit: two pay cycles tuned; `SELECT COUNT(*) FROM email_log WHERE email_type IN ('PAYDAY_D1_DETECTED','PAYCHECK_FOUND','SKIP_AFFORDABILITY','SKIP_CATCHUP','SKIP_HOLD_ACH','SKIP_HOLD','SKIP_NO_AUTH','DORMANT','INTERRUPTION','WELCOME_BACK','NEW_EMPLOYER','CADENCE_CHANGE','DORMANT_120','AUTHORIZATION_NEEDED')` is 0; the invoice check below.

The payer hash is a join key, not anonymization (tokens are guessable); the report never carries an email, descriptor, deposit amount or trailing median.

## Admin verbs (every profile)

- `A "$P/$USER_ID/status"` (streams with descriptors, cycles, last 20 inbox rows; start here), `"$P/$USER_ID/plaid-item"` (every `plaid_*` column but token and cursor), `"$FRED_API_BASE/api/admin/plaid/items"`.
- `A -X POST "$P/$USER_ID/sync"` (refused `REFUSED_EXCLUDED` or `REFUSED_DISABLED`), `.../evaluate` (no Plaid call; use after a tunable change), `.../reconcile` (one user, 50-minute budget).
- `A -X POST "$P/$USER_ID/hold" -d '{"reason":"ITEM_LOGIN_REQUIRED","note":"why"}'`: only the three Item-state holds, `BANK_CHANGE` or `null`; else 422 `HOLD_RESERVED`; over a blocking hold 409 `HOLD_BLOCKING`. None blocks money: rehearse the reconnect card, then send `{"reason":null}`.
- `A -X POST "$P/$USER_ID/clear-review" -d '{"note":"why"}'`: clears `MANUAL_REVIEW` only, after talking to the user; 409 `ACCOUNT_REJECTED` for a rejected applicant; on an ACTIVE schedule with no other blocking hold it records `schedule_resumed(system, review_cleared)` and money moves at the next due date.
- `.../promote-stream/$STREAM_ID` (wrong employer picked), `.../retire-stream/$STREAM_ID` (not a paycheck), `.../cycles/$CYCLE_ID/void` (OPEN or CONFIRMED, never one with an execution).
- `A "$P/webhooks?status=FAILED"`; `A -X POST "$P/webhooks/$EVENT_ID/replay"` (RECEIVED, attempts 0).
- `A -X POST "$FRED_API_BASE/api/admin/plaid/$USER_ID/remove-item"` (stops billing; the user must relink); `.../api/admin/users/$USER_ID/delete` (Privacy deletion).

## Dev verbs (local, dev, test, ci)

Under `$V/$USER_ID/`: `sandbox/paycheck` (up to 10 deposits on the user's own Item, newest `endDaysAgo` days back, then a sync unless `"sync":false`; build a history ending one period back, then post today's), `sandbox/fire {"code":...}` (the six `FIREABLE` codes), `sandbox/reset-login` (only Reconnect repairs it at Plaid), `reconcile?date=` (a later date reaches misses and dormancy); plus `D "$V/shadow-report"`.

## Sandbox scripts

`test/api/paycheck_*.sh` skip unless a non-ci backend answers. Locally: the local profile with the tunnel; a Sandbox bank linked as `user_transactions_dynamic` (the spec reads `/sandbox/transactions/create` as working on any Sandbox Item; if `user_good` is refused, relink this way); `plaid-item` showing a cursor and history complete; then `FRED_API_BASE=http://localhost:8080 FRED_PAYCHECK_USER_ID="$USER_ID" FRED_PAYCHECK_PUBLIC_ID="$PUBLIC_ID" bash test/api/paycheck_shadow.sh` (`FRED_MYSQL_DB=fred_local` adds the SQL checks; password from `MYSQL_PWD` or `~/.my.cnf`). The shadow and lost-webhook scripts each need a fresh user (a relink purges streams, events and cycles); `FRED_PAYCHECK_DESTRUCTIVE=1` lets the webhooks script break and revoke the Item.

## Procedures

- **Reconnect a broken Item**: the user taps Reconnect on Recurring Investments (`update-mode-complete` repairs the state); after two failures, change bank. Never set the status by SQL.
- **Clear MANUAL_REVIEW**: talk to the user, confirm why it was set (`admin_actions`), then `clear-review` with a note.
- **Lost cursor** (one Item errors nightly while `/item/get` is fine): `plaid-item`, then `UPDATE users SET plaid_sync_cursor = NULL WHERE id = $USER_ID`, then `sync` (events upsert on the Plaid id). At most 50 users per run: null-cursor syncs count against Plaid's 500 per minute. The reconcile also initializes null-cursor Items.
- **After a 503 storm**: Plaid retries for 24 h; watch `inboxBacklog` and `inboxOldestAgeSec` (alert above 1800 s), then replay each FAILED row. Older TRANSACTIONS webhooks need no replay (the nightly cursor sync recovers them). Five consecutive transient results stop that night's reconcile; the next catches up.
- **Rotate the webhook URL**: dev, set `PLAID_WEBHOOK_URL` on Railway, redeploy, `D -X POST "$FRED_API_BASE/api/dev/plaid/backfill-webhook-update"`; local, `plaid.webhook.url` plus the same call. Prod is not live in Phase 1 and needs an admin backfill verb in Phase 2.
- **Deploy freeze** (once there is a production environment): none 22:25 to 23:25 ET (reconcile) or 23:45 to 00:10 ET (money batch); `SELECT name FROM job_locks WHERE locked_until > UTC_TIMESTAMP()` is empty first.
- **Monthly invoice versus `plaid.items.billable`**: Sandbox bills nothing, so record the gauge monthly; reconcile on the first Production invoice (Phase 2). Compare `plaidItemsBillable` from the digest nearest the period end (`SELECT COUNT(*) FROM users WHERE plaid_access_token IS NOT NULL AND plaid_last_sync_at IS NOT NULL AND plaid_item_status <> 'REMOVED'`) with the invoice's Transactions Item count; gaps come from `removalsPending`, mid-month removals and rejected applicants' queued removals.
- **Privacy deletion**: `users delete` queues `/item/remove`, purges detection data and requests the analytics person deletion: 404 unknown, 409 `DELETION_DEFERRED` (transfer in flight or settled under 60 days; retry later), 200.
- **Heartbeat silence** (no 09:07 ET digest by 09:30): `SELECT alert_type, delivery_status FROM ops_alerts WHERE alert_day = '$(TZ=America/New_York date +%F)'`. No row: the job did not run (`investment.scheduler.enabled`, `job_locks`). FAILED: `OPS_ALERT_PROVIDER`, `OPS_ALERT_WEBHOOK_URL`. LOGGED: provider `logging` (prod refuses it at boot).

## Decision log

| Date (ET) | Reviewer | Users | Shadow cycles | Mean lag (days) | Decision |
| --- | --- | --- | --- | --- | --- |
