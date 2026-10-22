# Handoff: Paycheck Autopilot, after the analytics merge

**Written 2026-10-22, for whoever picks Paycheck Autopilot back up.** Phase 0 is merged and
unchanged in behaviour. What changed underneath it is how a schedule says why it is not running.
Read this before writing Phase 1 code; it is about 10 minutes and will save you a wrong assumption
about money movement.

Assume no prior context. Everything you need is here or named here.

---

## 1. `is_paused` is gone. There is a three-state `status` instead.

`investment_schedules.is_paused` **no longer exists**. `V7__investment_schedule_status.sql` dropped
it and replaced it with `status`:

| status | meaning |
| --- | --- |
| `PENDING_BANK` | Created, waiting for an ACH relationship. **Has never run.** |
| `ACTIVE` | Running. |
| `PAUSED_BY_USER` | Was running; the user turned it off. **This, and only this, is churn.** |

### Why, because it matters for correctness and not just tidiness

One boolean could not say *why* a schedule was stopped, and the two reasons are nothing alike. A
schedule waiting for its bank has never run; a schedule the user paused was running and they
switched it off. Two things went wrong as a result:

1. **Churn was unmeasurable.** Every new user looked like a pause, because a schedule is born
   stopped. The churn number counted onboarding.
2. **The ACH machinery could restart a deliberate pause.** `AccountStatusService.attemptAchCreation`
   and the ACH retry scheduler reach the adoption path knowing nothing about *why* a schedule is
   stopped. Under the boolean they force-unpaused it, so a retry could silently switch a schedule
   back on after the user had switched it off, and start moving their money again.

The status makes (2) structurally impossible rather than merely discouraged: the ACH path may move
`PENDING_BANK` and nothing else.

The REST contract did not change. `InvestmentScheduleResponse` still sends `isPaused`, derived from
the status via a deprecated `getIsPaused()`. **Do not build anything new on that getter.** It
cannot tell a pending bank from a user pause, which is the whole point. Read `getStatus()`.

---

## 2. `hold_reason` is unchanged, and now explicitly ORTHOGONAL to the status

Your `hold_reason` column, `HoldReason` enum and `BLOCKING_HOLDS` set survived the merge untouched.
What is now written down and tested is how the two relate:

| | who writes it | what it means |
| --- | --- | --- |
| `status` | the user's own pause and resume, and the ACH relationship landing | the user and bank lifecycle |
| `hold_reason` | the system only | a system stop, with a reason |

**Neither one ever writes the other.** Concretely, and each of these has a test:

- A **bank change is a hold**. `resetForBankChange` sets `hold_reason = BANK_CHANGE`, clears the
  stale relationship id, and **leaves the status exactly as it was**. It does not move `ACTIVE` to
  `PENDING_BANK`.
  - Two reasons. A Plaid outage or a routine bank change would otherwise read as users leaving. And
    a schedule the user had paused would come back as `ACTIVE` when the new bank landed, against
    their wishes.
- A **user pause or resume never touches a hold**. A user pausing a held schedule still records a
  genuine user pause: their decision is real whether or not the system was already stopping it. A
  user pressing resume on a held schedule moves the status to `ACTIVE` and the hold **stays**: they
  cannot wave away an ACH return.
- **Adoption only starts a schedule that was waiting for its bank.** `AchRelationshipService.adopt`
  moves `PENDING_BANK` to `ACTIVE` and nothing else, and does not consult the hold when doing it.

### The UX question this answers

Your Phase 0 notes recorded an open question: a bank-change hold shows as active in the app. That is
now the deliberate, documented answer rather than an accident. `status` says the user still wants
it; `hold_reason` says why nothing is moving. **The app can and should show both.** The derived
`isPaused` on the wire is `status != ACTIVE`, so it reads `false` for a held schedule. If the UI
needs to say "paused while we reconnect your bank", it needs the hold on the wire, which nothing
sends yet. That is unbuilt work, not a bug.

---

## 3. The runnable rule, in one place

```java
// InvestmentSchedule.isReadyForInvestment(LocalDate todayET)
if (status != Status.ACTIVE || achRequestId == null)          return false;
if (holdReason != null && BLOCKING_HOLDS.contains(holdReason)) return false;
return (startDate != null && startDate.equals(todayET))
    || (nextInvestmentDate != null && !nextInvestmentDate.isAfter(todayET));
```

**A schedule is runnable only when `status == ACTIVE` and no blocking hold is set.** Both have to be
clear for money to move. This rule lives in `isReadyForInvestment` and **nowhere else**. If you
need it elsewhere, call it, do not restate it.

`BLOCKING_HOLDS` is unchanged and is still only three of the ten `HoldReason` values:
`ACH_RETURN_UNAUTHORIZED`, `ACH_RETURN_ACCOUNT`, `MANUAL_REVIEW`. **`BANK_CHANGE` is not blocking**,
which is your Phase 0 decision and was left alone. The signature still takes `todayET`, as Phase 0
made it.

---

## 4. Your files that the merge touched, and how

Behaviour is preserved everywhere below; these are the status rename and the orthogonality rule.

| File | What happened |
| --- | --- |
| `model/InvestmentSchedule.java` | Added `Status` enum and `status` field; removed the `isPaused` field; `isReadyForInvestment(todayET)` keeps **your** signature and hold check, with the pause check now `status != ACTIVE`. `isPausedForAnyReason()` and a deprecated `getIsPaused()` are derived. |
| `service/InvestmentScheduleService.java` | `resetForBankChange` keeps your hold and your system `schedule_paused`, and no longer writes the status. `pauseSchedule` / `resumeSchedule` gate on the status and **stop consulting the hold**. |
| `service/AchRelationshipService.java` | `adopt`'s restart gate is now `status == PENDING_BANK` instead of `is_paused`; `isStopped` is `hold != null \|\| status != ACTIVE`. Your hold-clearing and your system `schedule_resumed` are unchanged. |
| `repository/InvestmentScheduleRepository.java` | The due-today query is `status = 'ACTIVE' AND mode = 'MANUAL'`. Your mode clause survived. |
| `controller/TradingController.java` | `closeAccount` delegates to **your** `UserDeletionService`. It rethrows on failure so a partial close cannot be reported as an orderly one. |
| `service/UserDeletionService.java` | Gained **one** call inside its existing transaction: `analyticsPersonDeletionService.request(user)`, before the row deletes. Covers every `DeletionReason`. Nothing else changed; the broker call still happens outside the transaction. |
| `R__v_schedule_churn.sql` | See section 6. One deliberate reversal. |
| `service/FredChatActionService.java`, `service/FredChatToolsService.java` | Read `getStatus()` where they read the flag. Chat now reports three states, including `waiting_for_bank`. |

`PaydayNotificationService` and `ScheduledInvestmentProcessor` are **unchanged from develop**. The
processor asks `isReadyForInvestment(todayET)` rather than reading any flag itself, so it inherited
the new rule for free; the notifier only ever read `hold_reason`. That is the rule living in one
place paying off, and it is the shape to copy.

`updateWithAchRequestId` **stays deleted**, as Phase 0 deleted it. `AchRelationshipService` is still
the single writer of the ACH fields. The guard that the ACH path may never restart a user's pause
moved into `AchRelationshipServiceTest.firstAdoptionNeverRestartsAScheduleTheUserPaused`.

---

## 5. Migration numbers: **your next one is V9**

| Version | Owner |
| --- | --- |
| `V6__paycheck_autopilot.sql` | Paycheck Autopilot Phase 0 (yours, untouched) |
| `V7__investment_schedule_status.sql` | analytics (the status replacement) |
| `V8__analytics_person_deletions.sql` | analytics (vendor person-deletion queue) |

**Start at `V9`.** Check `backend/src/main/resources/db/migration/` before you name it: this
collision happened once already, because two branches both chose V6, and git merges that cleanly
(different filenames) while Flyway refuses to start. The failure only appears at boot, so a
duplicate number will not be caught by tests or review.

House rules, from `FREDdocs/flyway-baseline.md`: versioned migrations must be **idempotent**
(`information_schema` guards plus `PREPARE`/`EXECUTE`/`DEALLOCATE`); views are **repeatable**
migrations named `R__v_<name>.sql`; and `ddl-auto=validate` is on in every profile, so an entity
that drifts from the schema fails at startup. That guardrail is worth trusting: it caught a
`char(36)` versus `varchar(36)` mismatch in V5 and a missing column in V7.

One thing left undone deliberately: `paused_by_user_at`, which your V6 added, is **not backfilled**
by V7. V7 had no evidence for when a historical pause happened, and inventing a timestamp seemed
worse than leaving it null. If you come to rely on that column, treat existing nulls as unknown.

---

## 6. One behaviour of yours that was deliberately reversed

Commit `344b7bb` made `v_schedule_churn.active_at_month_end` require `hold_reason IS NULL` alongside
`is_paused = 0`, and `2580767` pinned it with a test. **That is reversed.** The predicate is now
`status = 'ACTIVE'` alone, and holds get their own column, `held_at_month_end`.

The reasoning: a hold is an operational stop, not churn. If a held schedule drops out of the active
count, a Plaid outage or a routine bank change reads as users leaving, which is the one thing this
view exists to measure correctly. It is also unexplainable arithmetic, because a schedule would be neither
paused-by-user nor active, with no column accounting for it.

Nothing is lost. `held_at_month_end` counts `status = 'ACTIVE' AND hold_reason IS NOT NULL`, so
"the user still wants it, we are stopping it" is visible on its own. A schedule that is both paused
by its owner and held appears in **neither** column, deliberately: the user's pause is the real
state, and counting it twice would double-count one schedule. The replaced test is
`ScheduleChurnViewTest.activeAtMonthEndKeepsAHeldScheduleAndCountsItSeparately`.

**If you disagree, say so.** It is a metrics judgement about your feature's operational states, and
it is one line of SQL plus two tests to swap back.

---

## 7. If you add a new transition, it must emit

Any new way to stop or start a schedule has to record an event, or the churn number quietly goes
wrong. The two names are `schedule_paused` and `schedule_resumed`, and each carries **exactly two
properties, in this order**:

```java
props.put("initiator", ...);  // "user" or "system"
props.put("reason", ...);     // "user_request", "bank_change", "ach_confirmed", ...
```

Rules, all of them load-bearing:

1. **`initiator` decides whether it counts as churn.** `v_schedule_churn` filters every transition
   column on `initiator = 'user'`. A system transition that claimed `user` would corrupt the metric;
   one correctly marked `system` is free to exist and is visible in the totals.
2. **Only emit on a real transition.** Read the state *before* you write, and emit only if it
   actually changed. Pausing an already-paused schedule is a no-op and must be silent, or the churn
   count inflates. Every call site has both a transition test and a no-op test; copy that shape.
3. **Two properties and no more.** No amount, no frequency, no bank, no id, no dates, no free text.
   The tests assert the **whole** map including key order, because a property that quietly appears
   is a privacy problem and one that quietly disappears breaks the view's `$.initiator` /
   `$.reason` reads. Money amounts are never sent; see `FREDdocs/what-we-send.md`.
4. **Emit through the outbox, inside the transaction that changes the state.** Call
   `analyticsOutboxService.record(...)` from a method that already has a transaction.
   `AnalyticsOutboxService` **refuses to write without one**, by design, so the event and the state
   change commit together or not at all. Do not open a transaction just for analytics.
5. **Add a new `reason` to the vocabulary**, do not invent one at the call site. The constants live
   on `util/ServerAnalyticsEvent.java` (`REASON_USER_REQUEST`, `REASON_BANK_CHANGE`,
   `REASON_ACH_CONFIRMED`, `REASON_ACCOUNT_CLOSED`, `REASON_KYC_REJECTED`); the frontend taxonomy in
   `frontend/src/app/services/analytics/analytics-events.ts` lists the server event names, and
   `frontend/scripts/docs/assert-events-documented.mjs` fails the build if an event or property is
   undocumented. Document it in `FREDdocs/analytics-events.md` in the same change.

A third name, `schedule_cancelled`, exists for a schedule that goes away entirely (account closure,
KYC rejection). Do not reuse it for a stop.

### Where to copy from

| You are adding | Pattern to copy |
| --- | --- |
| a user transition | `InvestmentScheduleService.pauseSchedule` |
| a system transition | `InvestmentScheduleService.resetForBankChange` |
| a system transition on ACH state | `AchRelationshipService.adopt` |
| the test shape | `ScheduleTransitionAnalyticsTest` (mocked) and `ScheduleTransitionOutboxRollbackTest` (real database, commit and rollback) |

---

## 8. State of the tree

- Branch `feature/analytics`, merge commit `c3372d6`, which has both parents: analytics `ff0a317`
  and develop `2580767`.
- **720 tests, 0 failures, 0 skipped**, including the database-gated ones.
- The migration chain was rehearsed against a database at your V6 carrying Autopilot's shape: all
  five row shapes backfilled correctly, including the Autopilot-only one (`is_paused = 0`, no ACH
  id, `BANK_CHANGE` hold) landing on `ACTIVE` with its hold intact. `is_paused` dropped, re-run
  clean, and the app booted over it with Flyway applying 7, 8 and the seven views under
  `ddl-auto=validate`.
- Analytics work stops after Checkpoint I (the completion review). Nothing further is queued that
  will move these files again.

## Related reading

- `FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md`: your spec, still current
- `FREDdocs/metrics.md`: the MySQL views, including `v_schedule_churn` column by column
- `FREDdocs/analytics-events.md`: the event taxonomy and every property
- `FREDdocs/what-we-send.md`: what leaves the building, and what never does
- `FREDdocs/flyway-baseline.md`: migration conventions
