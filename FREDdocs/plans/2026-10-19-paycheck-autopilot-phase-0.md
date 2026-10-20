# Paycheck Autopilot Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The 15 task sections live in sibling files under `FREDdocs/plans/2026-10-19-paycheck-autopilot-phase-0/` (one file per task, each under 75 KB so a single Read call loads it); this file is the header, constraints, prerequisites and task index.

**Goal:** Land every backend and frontend change the Paycheck Autopilot spec requires before the private beta that is invisible to today's manual-schedule users: Plaid SDK and link-token groundwork, the webhook inbox, the V6 schema, batch idempotency, the bank-change ACH fix, the Eastern-time clock, and the deletions, so Phases 1 to 3 (detection, authorization, DETECTED pulls) build on a hardened base.

**Architecture:** Phase 0 adds no detection and never produces a DETECTED schedule. It introduces the shared primitives the later phases consume (an `EasternTime` clock, one `InvestmentFrequency` enum, `JobLockService` and a scheduler gate, execution `source_key` and `night_key`, a verified `plaid_webhook_events` inbox that only stores, `AchRelationshipService.adopt` as the single ACH writer, `PlaidItemLifecycleService` for `/item/remove`, `UserDeletionService` as the single deletion path) and ships all of spec section 3 as `V6__paycheck_autopilot.sql` so later phases add no DDL. The 11:55 and 11:59 crons keep their selection and summing logic; they gain the approved mode-neutral hardening only.

**Tech Stack:** Spring Boot 3.5, Java 17, Gradle, MySQL 8 with Flyway (`ddl-auto=validate` in every profile), plaid-java 46.0.0, jjwt 0.11.5, Caffeine 3.1.8, JUnit 5 + Mockito, Angular 19 + Ionic (Karma specs), Capacitor iOS.

**Spec:** `FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md` (approved 2026-10-19; all 23 decisions resolved in section 15). Locked UI: `FREDdocs/specs/paycheck-autopilot-mockup.html`. Origin: `FREDdocs/specs/paycheck-autopilot-spike.md`.

## Global Constraints

Every task's requirements implicitly include this section.

- The migration is `V6__paycheck_autopilot.sql` (`V5__analytics_outbox.sql` is already committed in 1453c9a). Every ALTER uses the V3 `information_schema` + `PREPARE` guard; new tables use `CREATE TABLE IF NOT EXISTS`. Schema is Flyway-owned; `spring.jpa.hibernate.ddl-auto=validate` stays in every profile. V6 carries all of spec section 3; Phase 0 maps only the columns it uses onto entities.
- Entity mapping: NOT NULL booleans use `columnDefinition = "BOOLEAN DEFAULT FALSE"`; the tri-state `plaid_transactions_consented` is `columnDefinition = "BOOLEAN"` nullable; SMALLINT columns map to `short`; enums are `@Enumerated(EnumType.STRING)` with a matching `@Column(length)`.
- Eastern clock everywhere money or notifications run: `EasternTime.ZONE` (`America/New_York`); crons compute `todayET` once and pass it down; no bare `LocalDate.now()` or `LocalDateTime.now()` in any file a Phase 0 task touches, except the two analytics day-band lines the T02 section names.
- `InvestmentSchedule.frequency` stays a `VARCHAR(20)` String column holding WEEKLY, BIWEEKLY, SEMI_MONTHLY or MONTHLY; `InvestmentFrequency` is for parsing and math only. Factors 4.33 / 2.17 / 2 / 1 are pinned by `FrequencyFactorParityTest` and the client table in `investment-frequency.utils.ts`.
- Single writers: `AchRelationshipService.adopt` is the only writer of `User.alpacaAchRelationshipId`, `User.alpacaAchStatus` (except `CANCEL_REQUESTED` set by `cancelAllActive`) and `InvestmentSchedule.achRequestId` (except the null set by `resetForBankChange` and the exchange). `is_paused` is written only by `pauseSchedule`, `resumeSchedule`, new MANUAL row creation and the one-time first-adoption unpause of spec decision 23.
- DETECTED mode is never produced in Phase 0; every mode switch knows MANUAL and returns false or no-op for DETECTED.
- Every `@Scheduled` job body starts with the `SchedulerGate` check (`investment.scheduler.enabled`), then `JobLockService.tryAcquire(name, lease)` with release in `finally`; never share one lock name between two jobs.
- No Plaid or Alpaca HTTP call inside a database transaction. Two documented exceptions remain in Phase 0 and are called out in their tasks: `cancelAllActive` runs inside the exchange transaction (T10) and `UserDeletionService` calls Alpaca before its delete (T11).
- `BrokerEnvironmentGuard` is edited by T14 only; T07 declares the ci bypass bean under the name `ciBypassPlaidWebhookVerifier`.
- 409 error bodies use `ErrorCodeResponse{code, message}` with codes `UPDATE_MODE_UNAVAILABLE`, `LINK_IN_PROGRESS`, `DELETION_DEFERRED`. The webhook endpoint answers only 200, 401 and 503 with empty bodies.
- Tests follow the house pattern: JUnit 5 + Mockito, `new Service()` plus `ReflectionTestUtils.setField`, `retrofit2.Call` mocked with `Response.success` or `Response.error`, no new `@SpringBootTest`. `BackendApplicationTests` under the ci profile against a scratch MySQL schema is the validate gate; without a database `RequiresCiDatabase` skips it, so read the test report, not just the exit code.
- Deletions are real deletions (endpoints, methods, DTOs, client stubs, `plaid-data.service.ts`, `models/plaid/*`); grep both trees for the removed names before each commit.
- No em dashes anywhere: code comments, copy, docs, commit messages. One conventional commit per task (T01 ships the SDK bump and its test as two commits by design). The executing session appends its own attribution trailer.
- Phase 0 is invisible to MANUAL users except the D-1 copy fix; the lump-sum `clientUuid` is optional so the current app keeps working.
- The repo is a shared dirty tree with other sessions' uncommitted work: never `git checkout`, `git restore`, `git stash` or `git reset`; hunk-verify `git diff HEAD -- <file>` before staging; stage only the files each task names. If another session's WIP breaks compilation, verify in a scratch worktree (see memory note "Verify in Scratch Worktree When Shared Tree Is Broken").
- Local run prerequisites introduced by this plan: after T07 and T08, `PLAID_WEBHOOK_URL` and `PLAID_REDIRECT_URI` must be exported in every non-prod environment (local, Railway dev and test) or the backend will not boot.

## Manual prerequisites (not code tasks)

Track these alongside the tasks; items marked "gate" block the phase from being called done.

- [ ] Plaid dashboard: product access request for Transactions and Recurring Transactions (gate for Phase 1, start now; review takes days).
- [ ] Plaid dashboard: Data Transparency Messaging use case and the link customization name that T08 reads from `plaid.link.customization`.
- [ ] Plaid dashboard: register the webhook URL (`plaid.webhook.url`) for the environment T07 runs against.
- [ ] Plaid dashboard: register the universal-link redirect URI that T08 reads from `plaid.redirect.uri`.
- [ ] Decision 1 device test (gate): link a NEW Item at `ins_127287` (OAuth bank) from the iPhone build through the WKWebView with the redirect URI; if Link cannot return to the app, switch to Hosted Link (`is_mobile_app=true`) before Phase 1.
- [ ] Rotate the committed Plaid sandbox secret after T14 moves it to environment variables (`FREDdocs/plaid-sandbox-secret-rotation.md`, written by T14).
- [ ] Price quote from Plaid for Transactions plus Recurring per Item (needed before Phase 2 switches to Production pay-as-you-go).
- [ ] LEGAL review of the authorization text (spec 7.2) and the privacy-policy additions (spec 7.9).

## Task index

| # | Task file | Depends on | Scope of the commit |
|---|---|---|---|
| 1 | `task-01-sdk-bump.md` | none | plaid-java 46.0.0, `PlaidConfig` loses the Development adapter, `PlaidConfigTest` |
| 2 | `task-02-timezone.md` | none | `EasternTime`, JVM default to America/New_York, `todayET` passed through every money and notification path |
| 3 | `task-03-frequency-enum.md` | none | `InvestmentFrequency` enum replacing three factor copies, `FrequencyFactorParityTest`, client parity spec |
| 4 | `task-04-v6-migration.md` | 3 | `V6__paycheck_autopilot.sql` (all of spec section 3), Phase 0 entity mappings, `ScheduleMode`, `HoldReason`, `PlaidWebhookEvent`, `PlaidItemRemoval`, `JobLock` |
| 5 | `task-05-job-locks-and-scheduler-flag.md` | 2, 4 | `JobLockService`, `SchedulerGate` (`investment.scheduler.enabled`), pool sizes |
| 6 | `task-06-execution-idempotency.md` | 5 | `source_key` and `night_key`, `ScheduledInvestmentProcessor` (REQUIRES_NEW per schedule), batch claim before the Alpaca POST, `createdAt < batchStart`, fail-fast on a null relationship id, lump-sum `clientUuid` |
| 7 | `task-07-webhook-inbox.md` | 1, 2, 4 | `POST /api/plaid/webhook`, `PlaidWebhookVerificationFilter`, inbox insert only, ci bypass bean, `plaid.webhook.url` |
| 8 | `task-08-link-token.md` | 7 | `create_link_token { mode }`, `additional_consented_products`, webhook and redirect URI, `linkplaid` and `change-bank-account` OAuth return |
| 9 | `task-09-exchange-and-item-lifecycle.md` | 5, 8 | exchange serialization and `LINK_IN_PROGRESS`, `previous_ach_relationship_id`, mask, `/item/get` consent flag after commit, `PlaidItemLifecycleService` (`/item/remove` drain), `/item/webhook/update` backfill |
| 10 | `task-10-bank-change-ach-fix.md` | 6, 9 | `AchRelationshipService` (adopt, cancel, poll), 409 never adopts, `resetForBankChange` hold, MANUAL eligibility hold clause, delete `update-ach-request-id`, decision 23 |
| 11 | `task-11-user-deletion-service.md` | 10 | `UserDeletionService.delete(user, reason)` as the single deletion path with the 60-day deferral |
| 12 | `task-12-endpoint-and-orphan-cleanup.md` | 9 | delete `/user-data`, `/access-token`, dead `*_anonymous` permitAll entries, `plaid-data.service.ts`, `models/plaid/*`; `primary-bank-account` gains `mask` and `itemStatus` |
| 13 | `task-13-d1-copy-fix.md` | 4 | MANUAL D-1 copy and `mode='MANUAL'` query |
| 14 | `task-14-prod-plaid-block.md` | 7, 8 | production Plaid properties from environment variables, `BrokerEnvironmentGuard` extensions, secret-rotation note |
| 15 | `task-15-archive-2025-attempt.md` | none | recover the 2025 paycheck-detection docs and service into `FREDdocs/archive/2025-paycheck-detection/` |

Tasks 1, 2, 3 and 15 can run in parallel. Tasks 12 and 13 can run in parallel with 10 and 11.

## Known seams between tasks

Each task was reviewed against the repository at HEAD 9a09482. These are the places where two tasks touch the same code or where a task depends on another's exact text; the implementer must re-read the neighbouring task before editing.

- T02 defines `InvestmentSchedulerTest` mock field names (`investmentExecutionService`, `dripService`, `paydayNotificationService`, subject `scheduler`); T05 extends that test and must follow T02's actual names.
- T05 defines the `schedulerGate` and `jobLockService` fields in `InvestmentExecutionService`; T06 shows them as a target shape and must keep T05's exact text.
- `InvestmentController.java:61` (bare `LocalDateTime.now()`): T02 owns the timezone sweep; T06 fixes this line only if T02 left it.
- T07 stamps `receivedAt` with `EasternTime.now()`, so it depends on T02 as well as T01 and T04 (added to the index above).
- T10 replaces whole methods by name in `InvestmentSchedule.isReadyForInvestment(LocalDate)` and `InvestmentScheduleService.getSchedulesReadyForInvestment(LocalDate)` after T02 changed their signatures; match on text, not HEAD line numbers.
- T11 anchors on quoted text in `AccountStatusService` because T10 shifts its lines.
- T04's `V6` adds no cascade on `investment_executions.user_id`, `drip_executions`, `beneficiaries` or `investment_trades`; T11 deletes those rows explicitly.
- Seven untracked `R__v_*.sql` repeatable views (another session's WIP) will run on the scratch schema in T04's validate gate; a failure there is outside this plan.

## Deferred to Phase 1 (named here so nothing is silently dropped)

Webhook processing and the `/transactions/sync` path, `POST /api/plaid/update-mode-complete`, `plaid_transactions_consented = false` (refused), `OpsAlertService` and the `executions.stranded` alert, the `plaid.webhook.*` metrics beyond SLF4J lines, `consentExpiresAt` on `primary-bank-account`, the two further prod guard refusals (`app.ops.alert.provider=logging`, unset `ADMIN_TOOLS_KEY`), the scheduler gate on jobs other than the 11:55, 11:59 and ACH poll, and encryption key versioning.
