### Task 23: schedule-write-safety

Two small hardening changes to Phase 0 code, made necessary by the extra schedule writers Phase 1 adds (the detection mirror in T11 and T12, the Item-state holds in T13, the reconcile in T15, the admin verbs in T18). First (ruling R19): `InvestmentSchedule` gets `@org.hibernate.annotations.DynamicUpdate`, so an UPDATE names only the columns that changed in that unit of work, and a writer holding a stale copy can no longer write back a `next_investment_date` the 11:55 job already advanced (an extra pull) or a `hold_reason` another writer already set (an unblocked schedule). A real-database test proves both directions. Second (ruling R20): `InvestmentScheduleService.resetForBankChange` stops replacing a blocking hold with `BANK_CHANGE`, and `InvestmentSchedule` gains `public static boolean isBlockingHold(HoldReason)` so T13 and T18 never restate the blocking set. Nothing here moves money, writes `status`, changes `isReadyForInvestment`, adds an event, a property, a migration or a Plaid or Alpaca call. This task was added by the controller after the per-task reviews; it depends on nothing and must land before T11, T13 and T18 (the execution order puts it right after T02).

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests mirror it under `backend/src/test/java/com/investingapp/backend/`. Every anchor below was checked against HEAD 137998c on `feature/paycheck-autopilot-phase-1`. T02 (which lands before this task) inserts seven fields into `InvestmentSchedule.java` after HEAD `:85`, so every anchor in that file below `:85` is given as quoted text as well as the HEAD line; match the text. Nothing before T23 edits `InvestmentScheduleService.java` or `ScheduleTransitionAnalyticsTest.java`, so their HEAD lines hold, and the text is quoted anyway.

**Facts this task depends on (restated so this file stands alone):**

- **`is_paused` is gone.** `investment_schedules.status` is the enum `InvestmentSchedule.Status` (`PENDING_BANK`, `ACTIVE`, `PAUSED_BY_USER`). Never read the deprecated `getIsPaused()` or build on `isPausedForAnyReason()`. Phase 1 never writes `status`; its writers stay `pauseSchedule`, `resumeSchedule`, row creation and `AchRelationshipService.adopt`. This task writes it nowhere.
- **`hold_reason` is the system's stop, orthogonal to `status`.** Neither ever writes the other. `BLOCKING_HOLDS` (private, `InvestmentSchedule.java` HEAD `:328-329`) is exactly `ACH_RETURN_UNAUTHORIZED`, `ACH_RETURN_ACCOUNT`, `MANUAL_REVIEW`. `BANK_CHANGE` is not blocking (its null `achRequestId` is what stops a bank-change schedule). `HoldReason` has ten values.
- **The runnable rule lives only in `InvestmentSchedule.isReadyForInvestment(LocalDate todayET)`** (HEAD `:356-365`): `status == ACTIVE`, `achRequestId != null`, no blocking hold, and the start date is today or the next date is today or past. T03's `PhaseOneMoneyInvarianceTest.isReadyForInvestmentBodyUnchanged` pins that method's exact text. **This task does not edit it**, not even to call the new `isBlockingHold`: the contract allows that refactor as optional, and taking it would break T03's pin for no behavioural gain.
- **Two MANUAL_REVIEW writers exist at HEAD**: `AccountStatusService.holdSchedulesForRejection` (a KYC rejection; the applicant keeps their row and the hold is the only reliable stop, handoff 2.1) and `AchRelationshipService.refuse` (an adoption refused for a previous id, a mask mismatch or a relationship that predates the link). `AchRelationshipService.adopt` clears a hold only when it is `BANK_CHANGE` on a MANUAL schedule (`if (schedule.getMode() == ScheduleMode.MANUAL && schedule.getHoldReason() == HoldReason.BANK_CHANGE)`).
- **The R20 hazard, concretely.** At HEAD `resetForBankChange` sets `BANK_CHANGE` unconditionally. A rejected applicant (MANUAL_REVIEW) who relinks their bank gets `BANK_CHANGE` instead; when the new relationship lands, `adopt` clears `BANK_CHANGE` and writes the new `achRequestId`; if the status is `ACTIVE` the schedule is runnable. Two ordinary steps unblock money that a person was meant to decide on. After this task the blocking hold survives the bank change, `adopt` leaves it alone, and the schedule stays stopped until an operator acts (T18's `clear-review`).
- **The event rule for `resetForBankChange` is unchanged.** It emits `schedule_paused` with exactly `initiator = system`, `reason = bank_change` through `AnalyticsOutboxService.record` inside the service's class-level `@Transactional`, and only when the schedule was running before the call (`holdReason == null && status == ACTIVE`). A schedule under a blocking hold was not running, so keeping its hold emits nothing, which is what the new test pins. No new event, reason or property key is added, so `ServerAnalyticsEvent`, `FREDdocs/analytics-events.md`, the frontend taxonomy and `AnalyticsOutboxForbiddenPayloadTest` are untouched.
- **The R19 hazard, concretely.** `InvestmentSchedule` has no `@Version`, and Hibernate's default UPDATE writes every mapped column from the in-memory entity. `ScheduledInvestmentProcessor.processOne` (REQUIRES_NEW, one schedule per transaction) advances `next_investment_date` and the monthly streak. If a Phase 1 writer loaded the same row before that commit and flushes after it, the default UPDATE writes the old `next_investment_date` back: the schedule is due again the next night, an extra pull. The mirror case is a writer whose stale copy has `hold_reason = null` flushing after a KYC rejection set `MANUAL_REVIEW`: the hold is wiped and money can move.
- **What `@DynamicUpdate` does and does not do.** It changes only which columns appear in the generated `UPDATE ... SET`; the values written for changed columns are the same, so no behaviour changes. It protects a writer whose managed entity was loaded in its own persistence context and who changes only its own fields. It does **not** protect (1) `repository.save(detached)`: a merge copies every field of the detached copy onto the freshly loaded row, so every stale field differs from the database and is written; (2) two writers changing the same column (the last commit wins; there is no version check); (3) JPQL or JDBC bulk updates, which bypass the entity. That is why R19 has a second rule that the later tasks carry, not this one: **every Phase 1 schedule writer loads the row inside the transaction that writes it, immediately before writing, and sets only its own fields.** A `@Version` column was not chosen: it needs DDL (the only Phase 1 migration, V10, belongs to T01 and adds no schedule column) and it would make the 11:55 job throw `OptimisticLockException` on a race, which is a behaviour change on the money path.
- **The T13 allowlist count.** T13 later adds `PhaseOneMoneyInvarianceTest.onlyAllowlistedFilesCallSetHoldReason`, which counts the text `setHoldReason(` per file and requires exactly four sites across `InvestmentScheduleService`, `AccountStatusService` and `AchRelationshipService`. `InvestmentScheduleService` has one today (`schedule.setHoldReason(HoldReason.BANK_CHANGE);`, HEAD `:254`). The new code keeps exactly one call, with a computed argument, and no comment may contain the text `setHoldReason(`.
- **Known and deferred, not fixed here (R21).** Relinking out of a non-blocking Item-state hold (for example `PERMISSION_REVOKED`) still replaces it with `BANK_CHANGE`, and `adopt` then emits `schedule_resumed(system, ach_confirmed)` with no matching pause, because `adopt`'s `isStopped` counts any hold. System transitions never reach the churn number. The plan header's Deferred list carries the fix for Phase 2. Do not change `adopt` or `isStopped` in this task. A second Phase 2 seam: spec section 7 (the ACCOUNT_INVALID return row, "stands until the relink revokes it (7.8)", and the "Reconnect your bank to continue" copy) expects a relink to lift `ACH_RETURN_ACCOUNT`, while R20 as contracted keeps every blocking hold through a bank change. Nothing writes `ACH_RETURN_ACCOUNT` in Phase 0 or Phase 1 (the return classifier is later), so nothing changes today; the task that adds the classifier must decide how a relink clears that one hold.
- **Logs.** INFO lines carry ids, codes, states and counts only, never a payer name, descriptor, amount, token or cursor. The one log line this task edits (`resetForBankChange`) moves from the user's email to the internal user id and the hold code.

**Seams with other tasks.**

- Controller note 2 makes T23 the only producer of `isBlockingHold`. T18 consumes it (its Step 1 checks it is present) and does not edit `InvestmentSchedule.java`; its 409 `HOLD_BLOCKING` tests call this method.
- This task adds one test to `ScheduleTransitionAnalyticsTest` (17 at HEAD, 18 after T23). T18 also adds one there, before the quoted line `    // --- Site 1: the trap. A schedule is BORN paused, which is not a pause. ---------------------`; this task inserts before a different line (`    // --- Site 5: ...`), so the two edits do not overlap. Any later count for that class is "the count before that task plus its own additions".
- T13 and every later task that asks "is this hold blocking" calls `InvestmentSchedule.isBlockingHold(...)`, never a restated list.

**Database caution.** The real-database steps run only against a throwaway scratch schema created and dropped in the same shell block. Never point them at `investing_dev` or `api-dev`: if this branch's V10 is recorded in a persistent database before the Goals v3 version 9 migration (`goals.sql`) arrives there, Flyway refuses the next boot (`spring.flyway.out-of-order` is not set). This task adds no migration and changes no Flyway setting.

**Local-properties note:** this task adds no property. The gitignored `backend/src/main/resources/application-local.properties` needs no edit for T23. The scratch blocks in Steps 2, 4 and 12 read its `spring.datasource.username` and `spring.datasource.password` keys into shell variables without printing them; never echo either value.

**Files:**
- Modify: `.../model/InvestmentSchedule.java`: insert the `@org.hibernate.annotations.DynamicUpdate` annotation and its comment between HEAD `:16` (`@Entity`) and `:17` (`@Table(name = "investment_schedules")`); insert `isBlockingHold` directly after the two-line `BLOCKING_HOLDS` declaration (HEAD `:328-329`, ending `HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);`; shifted by T02). `isReadyForInvestment` is not touched.
- Modify: `.../service/InvestmentScheduleService.java:237-269` (the javadoc starting `Bank change step 2 (spec 7.8)` and the body of `public void resetForBankChange(User user)`).
- Test: create `backend/src/test/java/com/investingapp/backend/service/ScheduleStaleWriteTest.java` (`@SpringBootTest @ActiveProfiles("ci") @RequiresCiDatabase`, 2 tests: `staleHoldWriteDoesNotRevertNextInvestmentDate`, `staleDateWriteDoesNotClearAHold`).
- Test: create `backend/src/test/java/com/investingapp/backend/model/InvestmentScheduleBlockingHoldTest.java` (plain JUnit, no Spring, no database; 4 tests).
- Test: modify `backend/src/test/java/com/investingapp/backend/service/ScheduleTransitionAnalyticsTest.java` (site 4): add `bankChangeKeepsABlockingHold` after `bankChangeWithNoScheduleEmitsNothing` (HEAD `:418-426`); the 17 existing cases stay green unchanged.
- Not modified, run as regression: `ScheduleTransitionOutboxRollbackTest` (16, real database), `InvestmentScheduleEligibilityTest` (4), `AccountStatusServiceKycDecidedTest` (14), `AchRelationshipServiceTest` (20), `ScheduledInvestmentProcessorTest` (12), `PhaseZeroEntityDefaultsTest` (5), and `PhaseOneMoneyInvarianceTest` when T03 has landed.

**Interfaces:**
- Consumes (Phase 0 code at HEAD, no Phase 1 task): `ScheduledInvestmentProcessor.processOne(Long scheduleId, LocalDate todayET): ProcessOutcome` (`.../service/ScheduledInvestmentProcessor.java:44-85`, `@Transactional(propagation = Propagation.REQUIRES_NEW)` at `:44`) and its nested `enum ProcessOutcome { QUEUED, NOT_DUE, DUPLICATE_SOURCE_KEY, DUPLICATE_NIGHT_KEY, NOT_ELIGIBLE, FAILED }` (`:33`); `InvestmentScheduleRepository.findById(Long)` and `findTopByUserOrderByCreatedAtDesc(User)` (`:19`); `UserRepository.saveAndFlush(User)`; `EasternTime.today()`; `com.investingapp.backend.support.RequiresCiDatabase`; `org.hibernate.annotations.DynamicUpdate` (hibernate-core 6.6.15.Final through Spring Boot 3.5.0, `@Retention(RUNTIME)`).
- Produces (consumed by T13 and T18; relied on by T11, T12, T15, T16 and T18 writers):
  - `public static boolean InvestmentSchedule.isBlockingHold(HoldReason reason)`: `reason != null && BLOCKING_HOLDS.contains(reason)`; true for exactly `ACH_RETURN_UNAUTHORIZED`, `ACH_RETURN_ACCOUNT`, `MANUAL_REVIEW`; false for null and the other seven.
  - `@org.hibernate.annotations.DynamicUpdate` on `InvestmentSchedule`.
  - `InvestmentScheduleService.resetForBankChange(User)` keeps an existing blocking hold (still nulls `achRequestId`, still emits `schedule_paused(system, bank_change)` only when the schedule was running, still leaves `status` alone).

- [ ] **Step 1: Failing real-database test for stale writes**

Create `backend/src/test/java/com/investingapp/backend/service/ScheduleStaleWriteTest.java`. It copies the fixture style of `ScheduleTransitionOutboxRollbackTest` (committed user through `TransactionTemplate`, schedule seeded through `JdbcTemplate`, no class-level `@Transactional`, rows removed by hand), and drives the real 11:55 per-schedule path for the first case.

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ruling R19: a schedule write must not lose another transaction's write.
 *
 * <p>InvestmentSchedule has no version column, so the only thing between a stale copy and the
 * row is which columns the UPDATE names. Without {@code @DynamicUpdate} Hibernate writes every
 * mapped column from the in-memory entity, and a writer that loaded the row before another
 * transaction committed writes that transaction's columns back to their old values.
 *
 * <p>Each case opens transaction A and loads the schedule, commits a separate transaction B that
 * changes one column, then has A change a different column and commit. The row must end with both
 * changes. The first case uses the real 11:55 per-schedule path for B, because the column it
 * advances (next_investment_date) is the one whose loss is an extra pull.
 *
 * <p>Deliberately NOT annotated {@code @Transactional}: the boundaries are the subject. Every
 * column check goes through JDBC after A has committed, or inside a REQUIRES_NEW transaction
 * while A is open (A's own connection would answer from A's snapshot); the one entity read, the
 * final runnable check, loads a fresh copy after A has committed.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ScheduleStaleWriteTest {

    private static final String ACH_ID = "ach-stale-write";

    @Autowired
    private InvestmentScheduleRepository investmentScheduleRepository;
    @Autowired
    private ScheduledInvestmentProcessor scheduledInvestmentProcessor;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Transaction A, the writer holding the stale copy. */
    private TransactionTemplate outer;
    /** Transaction B (and the in-flight reads): always a new transaction on its own connection. */
    private TransactionTemplate separate;
    private Long userId;
    private LocalDate today;

    @BeforeEach
    void createCommittedUser() {
        outer = new TransactionTemplate(transactionManager);
        separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        today = EasternTime.today();

        User fresh = new User();
        fresh.setEmail("stale-write-" + UUID.randomUUID() + "@example.invalid");
        // processOne re-checks the brokerage account inside its own transaction.
        fresh.setAccountStatus("ACTIVE");
        User saved = outer.execute(status -> userRepository.saveAndFlush(fresh));
        assertNotNull(saved);
        userId = saved.getId();
        assertNotNull(userId, "the test user must be committed before any schedule is seeded");
    }

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM investment_executions WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM analytics_outbox WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM investment_schedules WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    /**
     * A committed MANUAL schedule that the 11:55 job would run tonight: ACTIVE, ACH linked, no
     * hold, next date today. The start date is two weeks back so only the next date makes it due
     * (isReadyForInvestment also treats "start date is today" as due).
     */
    private long seedDueSchedule() {
        jdbcTemplate.update(
                "INSERT INTO investment_schedules "
                        + "(user_id, created_at, frequency, investment_amount, monthly_amount, "
                        + " monthly_streak, next_investment_date, start_date, status, mode, "
                        + " hold_reason, ach_request_id) "
                        + "VALUES (?, NOW(), 'BIWEEKLY', 250.00, 542.50, 0, ?, ?, 'ACTIVE', 'MANUAL', NULL, ?)",
                userId, today, today.minusDays(14), ACH_ID);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM investment_schedules WHERE user_id = ?", Long.class, userId);
        assertNotNull(id);
        return id;
    }

    @Test
    void staleHoldWriteDoesNotRevertNextInvestmentDate() {
        long scheduleId = seedDueSchedule();
        AtomicReference<LocalDate> advanced = new AtomicReference<>();

        outer.executeWithoutResult(a -> {
            // A: a Phase 1 writer (an Item-state hold, a detection mirror) loads the row.
            InvestmentSchedule stale = investmentScheduleRepository.findById(scheduleId).orElseThrow();
            assertEquals(today, stale.getNextInvestmentDate(), "sanity check: A's copy says the schedule is due today");

            // B, committed while A is still open: tonight's run queues and advances the schedule.
            assertEquals(ProcessOutcome.QUEUED, scheduledInvestmentProcessor.processOne(scheduleId, today),
                    "sanity check: the 11:55 path queued this schedule");
            advanced.set(separate.execute(b -> nextDateInDatabase(scheduleId)));
            assertTrue(advanced.get().isAfter(today), "sanity check: B moved the date forward");

            // A changes only its own column and commits.
            stale.setHoldReason(HoldReason.ITEM_LOGIN_REQUIRED);
        });

        assertEquals(advanced.get(), nextDateInDatabase(scheduleId),
                "the 11:55 advance must survive a stale hold write, or the schedule is due again: an extra pull");
        assertEquals(HoldReason.ITEM_LOGIN_REQUIRED.name(), holdReasonInDatabase(scheduleId),
                "A's own write landed");
        assertEquals(1, monthlyStreakInDatabase(scheduleId), "the processor's streak write survived too");
        assertFalse(investmentScheduleRepository.findById(scheduleId).orElseThrow().isReadyForInvestment(today),
                "and the schedule is not runnable again");
    }

    @Test
    void staleDateWriteDoesNotClearAHold() {
        long scheduleId = seedDueSchedule();
        AtomicReference<LocalDate> written = new AtomicReference<>();

        outer.executeWithoutResult(a -> {
            // A: a writer loads the row while it has no hold.
            InvestmentSchedule stale = investmentScheduleRepository.findById(scheduleId).orElseThrow();
            assertNull(stale.getHoldReason(), "A's copy has no hold");

            // B, committed while A is still open: a system stop lands (the KYC rejection hold).
            separate.executeWithoutResult(b -> jdbcTemplate.update(
                    "UPDATE investment_schedules SET hold_reason = 'MANUAL_REVIEW' WHERE id = ?", scheduleId));

            // A advances the date from its stale copy and commits.
            written.set(stale.calculateNextInvestmentDate(today));
            stale.setNextInvestmentDate(written.get());
        });

        assertEquals(HoldReason.MANUAL_REVIEW.name(), holdReasonInDatabase(scheduleId),
                "a stale date write must not clear a blocking hold that landed after the read");
        assertEquals(written.get(), nextDateInDatabase(scheduleId), "A's own write landed");
    }

    // --- helpers: straight through JDBC, so no persistence context can answer for the row ------

    private LocalDate nextDateInDatabase(long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_investment_date FROM investment_schedules WHERE id = ?", LocalDate.class, scheduleId);
    }

    private String holdReasonInDatabase(long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT hold_reason FROM investment_schedules WHERE id = ?", String.class, scheduleId);
    }

    private int monthlyStreakInDatabase(long scheduleId) {
        Integer streak = jdbcTemplate.queryForObject(
                "SELECT monthly_streak FROM investment_schedules WHERE id = ?", Integer.class, scheduleId);
        return streak == null ? 0 : streak;
    }
}
```

Why this shape works: MySQL's default isolation is REPEATABLE READ, so A's plain SELECT takes no row lock and B commits without waiting; A's UPDATE at commit is a current read and touches the row as B left it. The only thing that decides the outcome is which columns A's UPDATE names. `processOne` is `REQUIRES_NEW` on a separate Spring bean, so calling it inside A suspends A and runs B on its own connection, exactly as the 11:55 coordinator calls it. `processOne` only queues an `InvestmentExecution` row; it makes no Alpaca call.

```bash
(cd backend && ./gradlew compileTestJava --console=plain)
```

Expected: `BUILD SUCCESSFUL` (every symbol already exists at HEAD).

- [ ] **Step 2: Run it on a scratch schema and watch both cases fail**

`@RequiresCiDatabase` skips, not fails, when no database answers, and Gradle still prints `BUILD SUCCESSFUL`. Read the XML, never the console. Run the whole block as ONE shell invocation from the repository root (variables and the `db` function do not survive between invocations). It reads the local MySQL user and password from the gitignored `application-local.properties` without printing them, creates a throwaway schema, exports the ci-equivalent Plaid values so a shell holding real ones cannot leak them into the ci boot, runs the class, and drops the schema whatever happened.

```bash
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
db -e "DROP DATABASE IF EXISTS fred_t23_scratch; CREATE DATABASE fred_t23_scratch"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t23_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER"
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
(cd backend && ./gradlew cleanTest test --tests 'com.investingapp.backend.service.ScheduleStaleWriteTest' --console=plain)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.ScheduleStaleWriteTest.xml
grep -o 'message="[^"]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.ScheduleStaleWriteTest.xml
db -e "DROP DATABASE fred_t23_scratch"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_PW
```

Expected:

1. Gradle ends `BUILD FAILED` with `2 tests completed, 2 failed`.
2. The XML line is exactly `tests="2" skipped="0" failures="2" errors="0"`. `skipped="2"` means the scratch schema was not reached (the `<skipped message=...>` gives the cause): fix the connection and re-run; a skip is not the expected failure.
3. Two messages, printed as the XML stores them (`>` as `&gt;`, `<` as `&lt;`). `staleHoldWriteDoesNotRevertNextInvestmentDate`: `the 11:55 advance must survive a stale hold write, or the schedule is due again: an extra pull ==&gt; expected: &lt;D&gt; but was: &lt;T&gt;`, where T is today's Eastern date and D the later date the processor wrote (two weeks on, moved to a business day). `staleDateWriteDoesNotClearAHold`: `a stale date write must not clear a blocking hold that landed after the read ==&gt; expected: &lt;MANUAL_REVIEW&gt; but was: &lt;null&gt;`. Either sanity message instead (`sanity check: ...`) means the fixture is wrong, not the entity: stop and fix the fixture before Step 3.
4. The drop prints nothing.

- [ ] **Step 3: `@DynamicUpdate` on the entity**

`.../model/InvestmentSchedule.java`. The class header at HEAD `:13-18` is exactly:

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "investment_schedules")
public class InvestmentSchedule {
```

Insert two comment lines and the annotation between `@Entity` and `@Table(name = "investment_schedules")`, so it reads:

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
// Ruling R19: an UPDATE names only the columns changed in this unit of work, so a writer holding a
// stale copy cannot write back a column another transaction moved (next_investment_date, hold_reason).
@org.hibernate.annotations.DynamicUpdate
@Table(name = "investment_schedules")
public class InvestmentSchedule {
```

The fully qualified name is deliberate: it needs no import edit, so it cannot collide with import lines T02 or a later task adds to this file. Nothing else in the entity changes in this step. `ddl-auto=validate` is unaffected (the annotation changes generated SQL, not the mapping).

- [ ] **Step 4: Run the scratch block again: both cases pass**

Run the Step 2 block unchanged.

Expected: `BUILD SUCCESSFUL`; the XML line is exactly `tests="2" skipped="0" failures="0" errors="0"`; the `message=` grep prints nothing; the drop prints nothing.

- [ ] **Step 5: Failing unit test for `isBlockingHold` and the annotation**

Create `backend/src/test/java/com/investingapp/backend/model/InvestmentScheduleBlockingHoldTest.java`:

```java
package com.investingapp.backend.model;

import org.hibernate.annotations.DynamicUpdate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ruling R20: the blocking set has one home, InvestmentSchedule.BLOCKING_HOLDS, and callers ask
 * isBlockingHold instead of restating it. Ruling R19: the entity writes only changed columns.
 * No Spring, no database: the real-database proof of R19 is ScheduleStaleWriteTest.
 */
class InvestmentScheduleBlockingHoldTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    /** Spelled out here on purpose: a fourth blocking reason must fail this test and be decided. */
    private static final EnumSet<HoldReason> BLOCKING = EnumSet.of(
            HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);

    @Test
    void isBlockingHoldIsTrueForExactlyTheThreeBlockingReasons() {
        EnumSet<HoldReason> reported = EnumSet.noneOf(HoldReason.class);
        for (HoldReason hold : HoldReason.values()) {
            if (InvestmentSchedule.isBlockingHold(hold)) {
                reported.add(hold);
            }
        }
        assertThat(reported).isEqualTo(BLOCKING);
        assertThat(EnumSet.complementOf(BLOCKING))
                .as("BANK_CHANGE, the Item-state holds and the DETECTED-mode holds are not blocking")
                .hasSize(7)
                .noneMatch(InvestmentSchedule::isBlockingHold);
    }

    @Test
    void isBlockingHoldIsFalseForNoHold() {
        assertThat(InvestmentSchedule.isBlockingHold(null)).isFalse();
    }

    /** The accessor and the runnable rule read the same set, so they can never disagree. */
    @Test
    void isBlockingHoldAgreesWithTheRunnableRuleForEveryHold() {
        assertThat(runnable().isReadyForInvestment(TODAY)).as("the fixture runs with no hold").isTrue();
        for (HoldReason hold : HoldReason.values()) {
            InvestmentSchedule held = runnable();
            held.setHoldReason(hold);
            assertThat(InvestmentSchedule.isBlockingHold(hold))
                    .as(hold.name())
                    .isEqualTo(!held.isReadyForInvestment(TODAY));
        }
    }

    @Test
    void scheduleUpdatesNameOnlyChangedColumns() {
        assertThat(InvestmentSchedule.class.isAnnotationPresent(DynamicUpdate.class))
                .as("R19: without @DynamicUpdate a stale copy writes every column back")
                .isTrue();
    }

    /** ACTIVE, ACH linked, no hold, next date today, start date two weeks back: due by date only. */
    private static InvestmentSchedule runnable() {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setFrequency("BIWEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("250.00"));
        schedule.setMonthlyAmount(new BigDecimal("542.50"));
        schedule.setStartDate(TODAY.minusDays(14));
        schedule.setNextInvestmentDate(TODAY);
        schedule.setStatus(InvestmentSchedule.Status.ACTIVE);
        schedule.setAchRequestId("ach-1");
        return schedule;
    }
}
```

```bash
(cd backend && ./gradlew test --tests 'com.investingapp.backend.model.InvestmentScheduleBlockingHoldTest' --console=plain)
```

Expected: `BUILD FAILED` at `:compileTestJava` with `error: cannot find symbol`, `symbol:   method isBlockingHold(HoldReason)`, `location: class InvestmentSchedule`; javac may also print `invalid method reference` for `InvestmentSchedule::isBlockingHold`. Both name the method Step 6 adds.

- [ ] **Step 6: `isBlockingHold` after the blocking set**

`.../model/InvestmentSchedule.java`. The declaration at HEAD `:327-329` (shifted by T02's seven fields) is exactly:

```java
    /** Holds that stop a MANUAL schedule from investing (spec 5.2); BANK_CHANGE is not one of them. */
    private static final EnumSet<HoldReason> BLOCKING_HOLDS = EnumSet.of(
            HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);
```

Insert directly after its last line (before the blank line and the javadoc `Whether the schedule is stopped, for any reason.`):

```java

    /**
     * Whether this hold, on its own, stops money: true for exactly the members of BLOCKING_HOLDS,
     * the set {@link #isReadyForInvestment} reads. Exposed so callers ask instead of restating the
     * list (ruling R20). Null, BANK_CHANGE and every other hold are not blocking.
     */
    public static boolean isBlockingHold(HoldReason reason) {
        return reason != null && BLOCKING_HOLDS.contains(reason);
    }
```

Do not edit `isReadyForInvestment` (its line `if (holdReason != null && BLOCKING_HOLDS.contains(holdReason)) {` stays exactly as it is): T03's `isReadyForInvestmentBodyUnchanged` compares that method's text with a pinned copy.

Run the Step 5 command. Expected: `BUILD SUCCESSFUL`; then

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.model.InvestmentScheduleBlockingHoldTest.xml
```

prints exactly `tests="4" skipped="0" failures="0" errors="0"`.

- [ ] **Step 7: Failing mocked case: a bank change keeps a blocking hold**

`backend/src/test/java/com/investingapp/backend/service/ScheduleTransitionAnalyticsTest.java`, site 4. The method at HEAD `:418-426` is exactly:

```java
    /** No schedule at all: the method returns early, well before any emit. */
    @Test
    void bankChangeWithNoScheduleEmitsNothing() {
        stubLatestSchedule(null);

        service.resetForBankChange(user);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }
```

Insert the new test directly after that method's closing brace, before the blank line and the comment line `    // --- Site 5: the system resume, which now lives in AchRelationshipService.adopt -------------`:

```java

    /**
     * Ruling R20: a bank change never downgrades a blocking hold. BANK_CHANGE is not blocking, and
     * AchRelationshipService.adopt clears it when the new relationship lands, so overwriting
     * MANUAL_REVIEW (a KYC rejection, a refused adoption) or an ACH return with it would let an
     * ordinary relink restart money a person or the bank had stopped. The stale relationship id
     * still goes, the status is still left alone, and nothing is counted: a schedule under a
     * blocking hold was not running, so this call stopped nothing.
     */
    @Test
    void bankChangeKeepsABlockingHold() {
        stubSaveEchoesBack();
        for (HoldReason blocking : List.of(HoldReason.MANUAL_REVIEW,
                HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT)) {
            InvestmentSchedule held = heldSchedule(blocking);
            stubLatestSchedule(held);

            service.resetForBankChange(user);

            assertEquals(blocking, held.getHoldReason(), blocking + " survives the bank change");
            assertNull(held.getAchRequestId(), "the old relationship is still gone");
            assertEquals(Status.ACTIVE, held.getStatus(), "and the status is still left alone");
        }
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }
```

It needs no import: `HoldReason`, `InvestmentSchedule`, `Status`, `List`, `assertEquals`, `assertNull`, `verify`, `never` and `any` are already imported (`:3-5`, `:22`, `:26-34`), and `heldSchedule(HoldReason)` (`:145-149`, an ACTIVE schedule with `EXISTING_ACH_ID` and the given hold), `stubLatestSchedule` (`:162-165`) and `stubSaveEchoesBack` (`:152-155`) are the class's own helpers. Re-stubbing `findTopByUserOrderByCreatedAtDesc(user)` inside the loop is legal under the class's strict stubs: every stub is consumed by the call that follows it, and the argument never changes.

```bash
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.ScheduleTransitionAnalyticsTest' --console=plain)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.ScheduleTransitionAnalyticsTest.xml
```

Expected: `BUILD FAILED`; the XML line is exactly `tests="18" skipped="0" failures="1" errors="0"`; the failure is `bankChangeKeepsABlockingHold` with `org.opentest4j.AssertionFailedError: MANUAL_REVIEW survives the bank change ==> expected: <MANUAL_REVIEW> but was: <BANK_CHANGE>`. The 17 existing cases pass.

- [ ] **Step 8: `resetForBankChange` keeps a blocking hold**

`.../service/InvestmentScheduleService.java`. Replace HEAD `:237-256`, which is exactly this block (from the javadoc through the log line; the blank line, the analytics comment starting `// Analytics: a SYSTEM pause, on a real transition only.` and the `if (wasRunning) {` block that follow, `:257-268`, stay as they are):

```java
    /**
     * Bank change step 2 (spec 7.8): drop the old relationship id and hold the schedule. is_paused is
     * not written; the hold is cleared by AchRelationshipService.adopt when the new relationship lands.
     */
    public void resetForBankChange(User user) {
        investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user).ifPresent(schedule -> {
            // Read the current state BEFORE the hold goes on: only a schedule that was actually
            // running is being stopped by this call.
            //
            // Status and holds are orthogonal (see InvestmentSchedule). A bank change is a SYSTEM
            // stop, so it sets a hold and leaves the status alone: the user never asked for this,
            // and overwriting PAUSED_BY_USER would lose their intent while overwriting ACTIVE would
            // put Plaid outages into the churn number.
            boolean wasRunning = schedule.getHoldReason() == null
                    && schedule.getStatus() == InvestmentSchedule.Status.ACTIVE;

            schedule.setAchRequestId(null);
            schedule.setHoldReason(HoldReason.BANK_CHANGE);
            investmentScheduleRepository.save(schedule);
            logger.info("Investment schedule held for bank change, user {}", user.getEmail());
```

with:

```java
    /**
     * Bank change step 2 (spec 7.8): drop the old relationship id and hold the schedule. The status
     * is not written; the BANK_CHANGE hold is cleared by AchRelationshipService.adopt when the new
     * relationship lands.
     *
     * <p>Ruling R20: a blocking hold ({@link InvestmentSchedule#isBlockingHold}) is kept as it is.
     * adopt clears only BANK_CHANGE, so replacing MANUAL_REVIEW or an ACH return with BANK_CHANGE
     * would let a relink restart a schedule that a person or the bank had stopped.
     */
    public void resetForBankChange(User user) {
        investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user).ifPresent(schedule -> {
            // Read the current state BEFORE the hold goes on: only a schedule that was actually
            // running is being stopped by this call.
            //
            // Status and holds are orthogonal (see InvestmentSchedule). A bank change is a SYSTEM
            // stop, so it sets a hold and leaves the status alone: the user never asked for this,
            // and overwriting PAUSED_BY_USER would lose their intent while overwriting ACTIVE would
            // put Plaid outages into the churn number.
            boolean wasRunning = schedule.getHoldReason() == null
                    && schedule.getStatus() == InvestmentSchedule.Status.ACTIVE;

            // R20: a blocking hold stays; no hold, or a non-blocking one, becomes BANK_CHANGE as
            // before. One hold write, so this file keeps exactly one hold-writer site.
            HoldReason hold = InvestmentSchedule.isBlockingHold(schedule.getHoldReason())
                    ? schedule.getHoldReason()
                    : HoldReason.BANK_CHANGE;

            schedule.setAchRequestId(null);
            schedule.setHoldReason(hold);
            investmentScheduleRepository.save(schedule);
            logger.info("Investment schedule held for bank change, user {} hold {}", user.getId(), hold);
```

What did not change, and must not: `wasRunning` is still computed before any write, so a schedule under a blocking hold (never running) emits nothing and a running one still emits `schedule_paused` with exactly `initiator = system`, `reason = bank_change` inside the service's class-level transaction; `achRequestId` is still nulled in every case; the status is never written. The log line now carries the internal user id and the hold code instead of the email. `HoldReason` and `InvestmentSchedule` are already imported (`:3`, `:5`).

Run the Step 7 commands. Expected: `BUILD SUCCESSFUL`; the XML line is exactly `tests="18" skipped="0" failures="0" errors="0"`.

- [ ] **Step 9: Mocked neighbours, one class at a time**

Each of these drives `InvestmentSchedule` or `resetForBankChange` and must stay green unchanged. Run each command, then read its XML line with `grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-<fully qualified class>.xml`.

```bash
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentScheduleEligibilityTest' --console=plain)
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AccountStatusServiceKycDecidedTest' --console=plain)
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AchRelationshipServiceTest' --console=plain)
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.ScheduledInvestmentProcessorTest' --console=plain)
(cd backend && ./gradlew test --tests 'com.investingapp.backend.model.PhaseZeroEntityDefaultsTest' --console=plain)
```

Expected, in order: `tests="4"`, `tests="14"`, `tests="20"`, `tests="12"`, `tests="5"`, each with `skipped="0" failures="0" errors="0"` and `BUILD SUCCESSFUL`. `InvestmentScheduleEligibilityTest.resetForBankChangeHoldsWithoutPausing` still passes: its schedule has no hold, so it still gets `BANK_CHANGE`.

If T03 has landed (`ls backend/src/test/java/com/investingapp/backend/PhaseOneMoneyInvarianceTest.java` succeeds), also run:

```bash
(cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain)
```

Expected: `BUILD SUCCESSFUL` and `skipped="0" failures="0" errors="0"` with the same `tests=` count the class had before this task (T03 creates 6; this task adds none). `isReadyForInvestmentBodyUnchanged` and `blockingHoldsStillThree` passing is the proof that the runnable rule did not move.

- [ ] **Step 10: The real-database rollback test for the bank-change site**

`ScheduleTransitionOutboxRollbackTest` drives the real `resetForBankChange` and `adopt` against a real row and proves each event commits and rolls back with its state change; it must stay green with `@DynamicUpdate` on. Run the Step 2 block with the test line and the two grep lines replaced by:

```bash
(cd backend && ./gradlew cleanTest test --tests 'com.investingapp.backend.service.ScheduleTransitionOutboxRollbackTest' --console=plain)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.ScheduleTransitionOutboxRollbackTest.xml
```

(keep the schema creation, the exports, the drop and the `unset` exactly as in Step 2).

Expected: `BUILD SUCCESSFUL`; exactly `tests="16" skipped="0" failures="0" errors="0"`; the drop prints nothing.

- [ ] **Step 11: Scope and house-style checks**

From the repository root:

```bash
git diff HEAD -- backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java backend/src/test/java/com/investingapp/backend/service/ScheduleTransitionAnalyticsTest.java | grep -n $'^+.*\xe2\x80\x94'
grep -n $'\xe2\x80\x94' backend/src/test/java/com/investingapp/backend/service/ScheduleStaleWriteTest.java backend/src/test/java/com/investingapp/backend/model/InvestmentScheduleBlockingHoldTest.java
grep -c 'setHoldReason(' backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java
git diff HEAD -- backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java | grep -n -e '^[-+].*public boolean isReadyForInvestment' -e '^[-+].*BLOCKING_HOLDS.contains(holdReason)' -e '^-.*BLOCKING_HOLDS = EnumSet'
grep -n 'setStatus(\|getIsPaused(\|isPausedForAnyReason(' backend/src/test/java/com/investingapp/backend/service/ScheduleStaleWriteTest.java
git diff --stat HEAD
git status --short
```

Expected:

1. The two em dash greps print nothing. (The first reads only added lines: `InvestmentScheduleService.java` already holds one em dash at HEAD, outside this task's lines, which this task leaves alone.)
2. `1`: one hold-writer site in `InvestmentScheduleService.java`, so T13's four-site allowlist count holds.
3. Nothing: the signature and blocking line of `isReadyForInvestment` and the `BLOCKING_HOLDS` declaration are untouched. (The new javadoc's `{@link #isReadyForInvestment}` is an added line that this pattern deliberately does not match.)
4. Nothing: the stale-write test writes no status.
5. `git diff --stat HEAD` lists exactly `InvestmentSchedule.java`, `InvestmentScheduleService.java` and `ScheduleTransitionAnalyticsTest.java` from this task (another session's tracked work in progress may also appear: leave it alone). `git status --short` shows those three as ` M` and the two new test files as `??`. `backend/src/main/resources/application-local.properties`, any `*.properties`, any migration, `ServerAnalyticsEvent.java` and anything under `frontend/` must not appear from this task.

- [ ] **Step 12: Whole backend suite on a fresh scratch schema (never trust a run with skips)**

Same shape as Step 2, as ONE shell invocation from the repository root. It takes several minutes; it is one command, not a step to split.

```bash
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
db -e "DROP DATABASE IF EXISTS fred_t23_suite; CREATE DATABASE fred_t23_suite"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t23_suite?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER"
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
(cd backend && ./gradlew cleanTest test --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/*.xml \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests=" t, "skipped=" s, "failures=" f, "errors=" e}'
db -e "DROP DATABASE fred_t23_suite"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_PW
```

Expected: `BUILD SUCCESSFUL` and `skipped=0 failures=0 errors=0`, with `tests=` equal to the total of the last green full run before this task plus 7 (2 in `ScheduleStaleWriteTest`, 4 in `InvestmentScheduleBlockingHoldTest`, 1 in `ScheduleTransitionAnalyticsTest`). If T23 were the first backend task on 137998c the total would be `tests=763` (the handoff records 756 at 597bb4f and no backend file changed since); with T01 and T02 landed first it is 756, plus T01's 8, plus T02's 19, plus 7, so `tests=790`. A non-zero `skipped` means the scratch schema was not reached: fix it and re-run, never commit on a run with skips.

- [ ] **Step 13: Commit exactly these files**

The repository may be a shared dirty tree: hunk-verify each modified file with `git diff HEAD -- <file>` first (only this task's hunks: the annotation and `isBlockingHold` in the entity, the `resetForBankChange` javadoc and body in the service, the one new test in `ScheduleTransitionAnalyticsTest`), stage by full path only, never `git add -A`, and never `git checkout`, `git restore`, `git stash` or `git reset`.

```bash
git add backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java \
  backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java \
  backend/src/test/java/com/investingapp/backend/service/ScheduleStaleWriteTest.java \
  backend/src/test/java/com/investingapp/backend/model/InvestmentScheduleBlockingHoldTest.java \
  backend/src/test/java/com/investingapp/backend/service/ScheduleTransitionAnalyticsTest.java
git status --short
git commit -m "fix(schedule): write only changed schedule columns and keep a blocking hold through a bank change"
```

Expected: before the commit, `git status --short` shows the two new files as `A ` and the three modified files as `M `, and nothing else staged (in particular not `application-local.properties` and nothing under `ITPM/agent-memory/`). The commit message has no em dash.

### Verification

| what | command | expected |
| --- | --- | --- |
| stale writes, before the fix | Step 2 block (scratch schema `fred_t23_scratch`, created and dropped) | `tests="2" skipped="0" failures="2" errors="0"`; the date and the hold messages of Step 2 |
| stale writes, after `@DynamicUpdate` | Step 4 (the Step 2 block re-run) | `tests="2" skipped="0" failures="0" errors="0"` |
| accessor and annotation | `(cd backend && ./gradlew test --tests 'com.investingapp.backend.model.InvestmentScheduleBlockingHoldTest' --console=plain)`, XML grep of Step 6 | `tests="4" skipped="0" failures="0" errors="0"` |
| bank change keeps a blocking hold | `(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.ScheduleTransitionAnalyticsTest' --console=plain)`, XML grep of Step 7 | `tests="18" skipped="0" failures="0" errors="0"` (18 failing 1 before Step 8) |
| mocked neighbours | the five Step 9 commands, one class each | 4, 14, 20, 12, 5 tests; `skipped="0" failures="0" errors="0"` each |
| invariance (only if T03 landed) | `(cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain)` | same count as before this task, 0 failures |
| real-database bank-change site | Step 10 block | `tests="16" skipped="0" failures="0" errors="0"` |
| scope and style | Step 11 commands | no em dash; `1` hold-writer site; no `isReadyForInvestment` or `BLOCKING_HOLDS` diff; three ` M`, two `??` |
| whole backend suite | Step 12 block (scratch schema `fred_t23_suite`, created and dropped) | previous total plus 7 (763 if T23 lands first); `skipped=0 failures=0 errors=0` always |

No frontend file changes, so no Karma run belongs to this task. No property, migration, event, reason or property key is added, so `npm run test:events-documented` and the analytics parity tests are unaffected.

### Produces

- `public static boolean InvestmentSchedule.isBlockingHold(HoldReason reason)` in `com.investingapp.backend.model.InvestmentSchedule`, directly after `BLOCKING_HOLDS`: `reason != null && BLOCKING_HOLDS.contains(reason)`; true for exactly `ACH_RETURN_UNAUTHORIZED`, `ACH_RETURN_ACCOUNT`, `MANUAL_REVIEW`. T13 and T18 call it; nobody restates the set. `BLOCKING_HOLDS` stays private and the single list; `isReadyForInvestment` is unchanged.
- `@org.hibernate.annotations.DynamicUpdate` on `InvestmentSchedule`: an UPDATE names only the columns changed in its unit of work. It covers managed entities loaded and written in one transaction; every Phase 1 schedule writer (T11, T12, T13, T15, T16, T18) still loads the row inside its writing transaction, immediately before writing, and sets only its own fields (R19), because a merge of a detached copy is not covered.
- `InvestmentScheduleService.resetForBankChange(User)` keeps an existing blocking hold; with no hold or a non-blocking hold it writes `BANK_CHANGE` as before; it always nulls `achRequestId`, never writes `status`, and emits `schedule_paused(system, bank_change)` only when the schedule was running (`holdReason == null && status == ACTIVE`).
- Tests: `ScheduleStaleWriteTest` (`staleHoldWriteDoesNotRevertNextInvestmentDate`, `staleDateWriteDoesNotClearAHold`), `InvestmentScheduleBlockingHoldTest` (`isBlockingHoldIsTrueForExactlyTheThreeBlockingReasons`, `isBlockingHoldIsFalseForNoHold`, `isBlockingHoldAgreesWithTheRunnableRuleForEveryHold`, `scheduleUpdatesNameOnlyChangedColumns`), `ScheduleTransitionAnalyticsTest.bankChangeKeepsABlockingHold`.
- Consumes from other Phase 1 tasks: nothing.
