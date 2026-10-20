### Task 6: execution-idempotency

Make the two nightly crons safe to re-run and safe to crash: `source_key` / `night_key` pre-checks and a per-schedule REQUIRES_NEW transaction at 11:55, a committed compare-and-set claim before the Alpaca POST at 11:59 (plus `createdAt < batchStart` selection and a null-relationship fail-fast), and a per-tap `clientUuid` on lump sums. Builds on T02 (`EasternTime`, `processScheduledInvestments(LocalDate)`, `isReadyForInvestment(LocalDate)`), T04 (entity fields `schedule`, `sourceKey`, `nightKey`, `batchNight`, `transferAttemptedAt`, `InvestmentSchedule.mode`, `ScheduleMode`) and T05 (`SchedulerGate`, `JobLockService`, `JobLocks.EOD_BATCH`). Line numbers below are HEAD 9a09482; T02 and T05 shift a few lines in `InvestmentExecutionService` and `InvestmentController`, so anchor on the quoted text.

Two callers of `POST /api/investments/execute` exist on the frontend: `InvestmentService.createManualInvestment` (no component calls it today) and the real tap path in `LumpSumInvestmentPage.makeInvestment` (`lump-sum-investment.page.ts:385-399` builds `requestData` and posts directly). Both must send `clientUuid`, or decision 18 is a no-op in the shipped app.

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/util/ExecutionKeys.java`
- Create: `backend/src/main/java/com/investingapp/backend/service/ScheduledInvestmentProcessor.java`
- Modify: `backend/src/main/java/com/investingapp/backend/repository/InvestmentExecutionRepository.java:6-12` (imports), `:131` (append methods after `existsByUserAndFundingCompletedAtIsNotNull`, before the closing brace at `:133`)
- Modify: `backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java:9-13` (imports), after `:73` (two new @Autowired fields), `:210-257` (11:59 batch, javadoc at `:210` "Process all queued lump sum investments at end of day"), `:259-371` (coordinator replaces `processScheduledInvestments` at `:262`, deletes `createInvestmentExecution` `:326-340` and `updateScheduleAfterExecution` `:342-371`), `:1130-1143` (overload next to `notifyUserOfFailure`)
- Modify: `backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java:18-25` (imports), `:61` (bare `LocalDateTime.now()` in the history window, only if T02 left it), `:267-276` (clientUuid + duplicate short-circuit), end of class (helper)
- Modify: `frontend/src/app/services/investment.service.ts:153-160`
- Modify: `frontend/src/app/lump-sum-investment/lump-sum-investment.page.ts:385-389` (`requestData`)
- Test: `backend/src/test/java/com/investingapp/backend/util/ExecutionKeysTest.java`, `backend/src/test/java/com/investingapp/backend/service/ScheduledInvestmentProcessorTest.java`, `backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceCoordinatorTest.java`, `backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceEodBatchTest.java`, `backend/src/test/java/com/investingapp/backend/controller/InvestmentControllerLumpSumTest.java`, `frontend/src/app/services/investment.service.spec.ts`, `frontend/src/app/lump-sum-investment/lump-sum-investment.page.client-uuid.spec.ts`

**Interfaces:**
- Consumes: `EasternTime.now()` / `EasternTime.today()` (T02); `InvestmentScheduleService.getSchedulesReadyForInvestment(LocalDate todayET)` (T02, `InvestmentScheduleService.java:301`); `InvestmentSchedule.isReadyForInvestment(LocalDate todayET)` (T02, `InvestmentSchedule.java:300`); `InvestmentSchedule.getMode()` + `ScheduleMode { MANUAL, DETECTED }` (T04); `InvestmentExecution.setSchedule/setSourceKey/setNightKey/setBatchNight/setTransferAttemptedAt` and getters (T04); `SchedulerGate.isEnabled()`, `JobLockService.tryAcquire(String, Duration)` / `release(String)`, `JobLocks.EOD_BATCH` (T05); `AlpacaService.initiateAchTransfer(String, String, BigDecimal)` (`AlpacaService.java:111`); `AlpacaService.AlpacaTransferResponse(id, status, amount, createdAt, errorMessage)` (`AlpacaService.java:971`); `EmailService.sendEmail(String to, String subject, String text)` (`EmailService.java:43`); `UserRepository.findByEmail(String)` returning `Optional<User>` (`UserRepository.java:21`); `InvestmentSchedule.calculateNextInvestmentDate(LocalDate)` (`InvestmentSchedule.java:91`).
- Produces: `public final class com.investingapp.backend.util.ExecutionKeys { static String scheduled(long scheduleId, LocalDate dueDate); static String night(long userId, LocalDate nightET); static String lump(long userId, String clientUuid) }`; `@Service public class ScheduledInvestmentProcessor { ScheduledInvestmentProcessor(InvestmentScheduleRepository, InvestmentExecutionRepository); @Transactional(propagation = REQUIRES_NEW) public ProcessOutcome processOne(Long scheduleId, LocalDate todayET); public enum ProcessOutcome { QUEUED, NOT_DUE, DUPLICATE_SOURCE_KEY, DUPLICATE_NIGHT_KEY, NOT_ELIGIBLE, FAILED } }` (DETECTED mode returns NOT_ELIGIBLE in Phase 0); `InvestmentExecutionService`: `@Transactional(propagation = NOT_SUPPORTED) public void processScheduledInvestments(LocalDate todayET)`, `void runEndOfDayBatch(LocalDateTime batchStart)` (package-private, called by `processEndOfDayBatchTransfers()` inside the T05 gate and lock); `InvestmentExecutionRepository`: `boolean existsBySourceKey(String)`, `boolean existsByNightKey(String)`, `Optional<InvestmentExecution> findBySourceKey(String)`, `List<InvestmentExecution> findByStatusAndCreatedAtBefore(ExecutionStatus, LocalDateTime)`, `boolean existsByUserAndStatus(User, ExecutionStatus)`, `Optional<InvestmentExecution> findTopByUserAndFundingCompletedAtIsNotNullOrderByFundingCompletedAtDesc(User)`, `@Modifying int claimForBatch(List<Long> ids, LocalDateTime at, LocalDate night)` (consumed by T10, T11); `POST /api/investments/execute` body key `clientUuid` (optional; absent -> server UUID; malformed -> 400 `{success:false, message:"Invalid clientUuid"}`), duplicate answer `200 {success:true, executionId, duplicate:true, ...}`; `InvestmentService.createManualInvestment(amount)` posts `{ amount, clientUuid }`; `LumpSumInvestmentPage.makeInvestment()` posts `{ amount, type, fundingSource, symbol?, clientUuid }`.

- [ ] **Step 1: Write the failing ExecutionKeys test**

`backend/src/test/java/com/investingapp/backend/util/ExecutionKeysTest.java`:

```java
package com.investingapp.backend.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionKeysTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 19);

    @Test
    void scheduledKeyIsSchedPrefixScheduleIdAndIsoDueDate() {
        assertThat(ExecutionKeys.scheduled(7L, NIGHT)).isEqualTo("SCHED:7:2026-10-19");
    }

    @Test
    void nightKeyIsUserIdAndIsoNight() {
        assertThat(ExecutionKeys.night(42L, NIGHT)).isEqualTo("42:2026-10-19");
    }

    @Test
    void lumpKeyIsLumpPrefixUserIdAndClientUuid() {
        assertThat(ExecutionKeys.lump(42L, "0f8fad5b-d9cb-469f-a165-70867728950e"))
                .isEqualTo("LUMP:42:0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    @Test
    void keysFitTheirColumns() {
        // V6: source_key VARCHAR(120), night_key VARCHAR(40)
        assertThat(ExecutionKeys.scheduled(Long.MAX_VALUE, NIGHT)).hasSizeLessThanOrEqualTo(120);
        assertThat(ExecutionKeys.lump(Long.MAX_VALUE, UUID.randomUUID().toString())).hasSizeLessThanOrEqualTo(120);
        assertThat(ExecutionKeys.night(Long.MAX_VALUE, NIGHT)).hasSizeLessThanOrEqualTo(40);
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.util.ExecutionKeysTest'`
Expected: compilation fails, `cannot find symbol: class ExecutionKeys`.

- [ ] **Step 2: Implement ExecutionKeys**

`backend/src/main/java/com/investingapp/backend/util/ExecutionKeys.java`:

```java
package com.investingapp.backend.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Idempotency keys on investment_executions (spec 3.8, 5.4). source_key is UNIQUE: one row per
 * MANUAL due date per schedule, one row per lump-sum tap. night_key is UNIQUE and NULL for lump
 * sums: one recurring execution per user per Eastern night. Both are pre-checked before insert;
 * the database constraints are the backstop.
 */
public final class ExecutionKeys {

    private ExecutionKeys() {
    }

    public static String scheduled(long scheduleId, LocalDate dueDate) {
        return "SCHED:" + scheduleId + ":" + dueDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String night(long userId, LocalDate nightET) {
        return userId + ":" + nightET.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String lump(long userId, String clientUuid) {
        return "LUMP:" + userId + ":" + clientUuid;
    }
}
```

Run the Step 1 command. Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 3: Add the repository methods**

In `InvestmentExecutionRepository.java` replace the import block at `:6-12` (from `import org.springframework.data.jpa.repository.JpaRepository;` through `import java.util.List;`) with:

```java
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
```

Insert after `boolean existsByUserAndFundingCompletedAtIsNotNull(User user);` (`:131`), before the closing brace:

```java
    // --- Phase 0 idempotency (spec 3.8, 5.4, 5.5) -------------------------------------------

    /** source_key pre-check: one row per MANUAL due date per schedule, one per lump-sum tap. */
    boolean existsBySourceKey(String sourceKey);

    /** night_key pre-check: one recurring execution per user per Eastern night. */
    boolean existsByNightKey(String nightKey);

    /** Lump-sum duplicate short-circuit on POST /api/investments/execute. */
    Optional<InvestmentExecution> findBySourceKey(String sourceKey);

    /** 11:59 selection: still SCHEDULED and created before the batch started (a 23:59:xx row runs the next night). */
    List<InvestmentExecution> findByStatusAndCreatedAtBefore(ExecutionStatus status, LocalDateTime before);

    /** Deferral guard (T10 bank change, T11 deletion): is money in flight for this user. */
    boolean existsByUserAndStatus(User user, ExecutionStatus status);

    /** Deferral guard (T11): the most recent settled transfer, for the 60-day window. */
    Optional<InvestmentExecution> findTopByUserAndFundingCompletedAtIsNotNullOrderByFundingCompletedAtDesc(User user);

    /**
     * Compare-and-set claim before the Alpaca POST (spec 5.5 b). Only rows nobody attempted yet
     * are claimed; the caller compares the row count with the batch size and skips the user when
     * they differ. Run it in a REQUIRES_NEW TransactionTemplate so the claim commits before HTTP.
     */
    @Modifying
    @Query("UPDATE InvestmentExecution e SET e.transferAttemptedAt = :at, e.batchNight = :night " +
           "WHERE e.id IN :ids AND e.transferAttemptedAt IS NULL AND e.alpacaTransferId IS NULL")
    int claimForBatch(@Param("ids") List<Long> ids, @Param("at") LocalDateTime at, @Param("night") LocalDate night);
```

Run: `cd backend && ./gradlew compileJava`. Expected: `BUILD SUCCESSFUL`. (Derived-query names and the JPQL are validated at context start; `BackendApplicationTests` under ci in Step 16 is that gate.)

- [ ] **Step 4: Write the failing ScheduledInvestmentProcessor test**

`backend/src/test/java/com/investingapp/backend/service/ScheduledInvestmentProcessorTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scope: ONLY what processOne writes for one MANUAL schedule on one Eastern night. The
 * REQUIRES_NEW boundary cannot be proven without a context; what is pinned is that every
 * outcome short of QUEUED writes nothing, the keys are stamped exactly, and the marker string
 * the 11:59 batch selects on is unchanged.
 */
class ScheduledInvestmentProcessorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private InvestmentScheduleRepository scheduleRepository;
    private InvestmentExecutionRepository executionRepository;
    private ScheduledInvestmentProcessor processor;
    private User user;
    private InvestmentSchedule schedule;

    @BeforeEach
    void setUp() {
        scheduleRepository = mock(InvestmentScheduleRepository.class);
        executionRepository = mock(InvestmentExecutionRepository.class);
        processor = new ScheduledInvestmentProcessor(scheduleRepository, executionRepository);

        user = new User("recurring@fredvested.com");
        user.setId(42L);
        user.setAccountStatus("ACTIVE");

        schedule = new InvestmentSchedule();
        schedule.setId(7L);
        schedule.setUser(user);
        schedule.setFrequency("BIWEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("150.00"));
        schedule.setMonthlyAmount(new BigDecimal("325.50"));
        schedule.setStartDate(LocalDate.of(2026, 9, 7));
        schedule.setNextInvestmentDate(TODAY);
        schedule.setIsPaused(false);
        schedule.setAchRequestId("rel-1");
        schedule.setMonthlyStreak(3);
        schedule.setLastStreakIncrementDate(LocalDate.of(2026, 9, 21));

        when(scheduleRepository.findById(7L)).thenReturn(Optional.of(schedule));
        when(scheduleRepository.save(any(InvestmentSchedule.class))).thenAnswer(i -> i.getArgument(0));
        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> {
            InvestmentExecution e = i.getArgument(0);
            e.setId(1001L);
            return e;
        });
        when(executionRepository.existsBySourceKey(anyString())).thenReturn(false);
        when(executionRepository.existsByNightKey(anyString())).thenReturn(false);
    }

    private InvestmentExecution savedExecution() {
        ArgumentCaptor<InvestmentExecution> captor = ArgumentCaptor.forClass(InvestmentExecution.class);
        verify(executionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createsOneExecutionWithKeys() {
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.QUEUED);

        InvestmentExecution saved = savedExecution();
        assertThat(saved.getSourceKey()).isEqualTo("SCHED:7:2026-10-19");
        assertThat(saved.getNightKey()).isEqualTo("42:2026-10-19");
        assertThat(saved.getSchedule()).isSameAs(schedule);
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getAmount()).isEqualByComparingTo("150.00");
        assertThat(saved.getStatus()).isEqualTo(InvestmentExecution.ExecutionStatus.SCHEDULED);
        assertThat(saved.getInvestmentType()).isEqualTo("recurring");
        assertThat(saved.getFundingSource()).isEqualTo("bank");
        assertThat(saved.getScheduledDate()).isNotNull();
    }

    @Test
    void markerStringUnchanged() {
        processor.processOne(7L, TODAY);
        // The 11:59 batch selects on contains("Queued for end-of-day"); changing this drops recurring rows.
        assertThat(savedExecution().getErrorMessage())
                .isEqualTo("Queued for end-of-day batch processing (recurring investment)");
    }

    @Test
    void advancesNextDateAndStreakOnQueue() {
        processor.processOne(7L, TODAY);

        assertThat(schedule.getNextInvestmentDate()).isAfter(TODAY);
        assertThat(schedule.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(schedule.getMonthlyStreak()).isEqualTo(4);
        assertThat(schedule.getLastStreakIncrementDate()).isEqualTo(TODAY);
        verify(scheduleRepository).save(schedule);
    }

    @Test
    void streakIncrementsAtMostOncePerCalendarMonth() {
        schedule.setLastStreakIncrementDate(LocalDate.of(2026, 10, 5));
        processor.processOne(7L, TODAY);
        assertThat(schedule.getMonthlyStreak()).isEqualTo(3);
        assertThat(schedule.getLastStreakIncrementDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void duplicateSourceKeyWritesNothing() {
        when(executionRepository.existsBySourceKey("SCHED:7:2026-10-19")).thenReturn(true);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.DUPLICATE_SOURCE_KEY);
        verify(executionRepository, never()).save(any());
        verify(scheduleRepository, never()).save(any());
        assertThat(schedule.getNextInvestmentDate()).isEqualTo(TODAY);
    }

    @Test
    void duplicateNightKeyWritesNothing() {
        when(executionRepository.existsByNightKey("42:2026-10-19")).thenReturn(true);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.DUPLICATE_NIGHT_KEY);
        verify(executionRepository, never()).save(any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void notDueTonightWritesNothing() {
        schedule.setNextInvestmentDate(TODAY.plusDays(3));
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_DUE);
        verify(executionRepository, never()).existsBySourceKey(anyString());
        verify(executionRepository, never()).save(any());
    }

    @Test
    void startDateTonightIsDueEvenWhenNextDateIsLater() {
        schedule.setStartDate(TODAY);
        schedule.setNextInvestmentDate(TODAY.plusWeeks(2));
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.QUEUED);
    }

    @Test
    void pausedOrUnlinkedOrInactiveIsNotEligible() {
        schedule.setIsPaused(true);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        schedule.setIsPaused(false);
        schedule.setAchRequestId(null);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        schedule.setAchRequestId("rel-1");
        user.setAccountStatus("ACTION_REQUIRED");
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        verify(executionRepository, never()).save(any());
    }

    @Test
    void detectedModeIsNotEligibleInPhase0() {
        // Global constraint: every mode switch knows MANUAL and returns false/no-op for DETECTED.
        schedule.setMode(ScheduleMode.DETECTED);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        verify(executionRepository, never()).existsBySourceKey(anyString());
        verify(executionRepository, never()).save(any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void vanishedScheduleIsNotEligible() {
        when(scheduleRepository.findById(99L)).thenReturn(Optional.empty());
        assertThat(processor.processOne(99L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        verify(executionRepository, never()).save(any());
    }

    @Test
    void usesThePassedEasternDateNotTheClock() {
        LocalDate night = LocalDate.of(2031, 3, 3);
        schedule.setNextInvestmentDate(night);
        assertThat(processor.processOne(7L, night)).isEqualTo(ProcessOutcome.QUEUED);
        InvestmentExecution saved = savedExecution();
        assertThat(saved.getSourceKey()).isEqualTo("SCHED:7:2031-03-03");
        assertThat(saved.getNightKey()).isEqualTo("42:2031-03-03");
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.ScheduledInvestmentProcessorTest'`
Expected: compilation fails, `cannot find symbol: class ScheduledInvestmentProcessor`.

- [ ] **Step 5: Implement ScheduledInvestmentProcessor**

`backend/src/main/java/com/investingapp/backend/service/ScheduledInvestmentProcessor.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ExecutionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * One schedule, one transaction (spec 5.3). The 11:55 coordinator lists eligible ids and calls
 * processOne for each; REQUIRES_NEW means a unique-key violation or any other failure rolls back
 * one user's night, never the whole night. A separate bean on purpose: a REQUIRES_NEW method
 * called from inside InvestmentExecutionService would bypass the transaction proxy.
 */
@Service
public class ScheduledInvestmentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledInvestmentProcessor.class);

    /** Load-bearing: the 11:59 batch selects rows whose errorMessage contains "Queued for end-of-day". */
    static final String RECURRING_BATCH_MARKER = "Queued for end-of-day batch processing (recurring investment)";

    public enum ProcessOutcome { QUEUED, NOT_DUE, DUPLICATE_SOURCE_KEY, DUPLICATE_NIGHT_KEY, NOT_ELIGIBLE, FAILED }

    private final InvestmentScheduleRepository investmentScheduleRepository;
    private final InvestmentExecutionRepository executionRepository;

    public ScheduledInvestmentProcessor(InvestmentScheduleRepository investmentScheduleRepository,
            InvestmentExecutionRepository executionRepository) {
        this.investmentScheduleRepository = investmentScheduleRepository;
        this.executionRepository = executionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessOutcome processOne(Long scheduleId, LocalDate todayET) {
        InvestmentSchedule schedule = investmentScheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) {
            logger.warn("Schedule {} vanished between listing and processing", scheduleId);
            return ProcessOutcome.NOT_ELIGIBLE;
        }
        // Phase 0 knows MANUAL only. DETECTED due-ness is a confirmed cycle, not a date (spec 5.3
        // step 2); until Phase 2 wires that, a DETECTED row never queues from here.
        if (schedule.getMode() != ScheduleMode.MANUAL) {
            logger.info("Skipping schedule {}: mode {} is not processed in Phase 0", scheduleId, schedule.getMode());
            return ProcessOutcome.NOT_ELIGIBLE;
        }
        User user = schedule.getUser();

        if (!isDueOn(schedule, todayET)) {
            return ProcessOutcome.NOT_DUE;
        }
        // Re-checked inside this transaction: a pause, a lost ACH link or a status change since
        // the coordinator listed the schedule must win. T10 adds the hold clause inside isReadyForInvestment.
        if (user == null || !"ACTIVE".equals(user.getAccountStatus()) || !schedule.isReadyForInvestment(todayET)) {
            logger.info("Skipping schedule {}: no longer eligible", scheduleId);
            return ProcessOutcome.NOT_ELIGIBLE;
        }

        String sourceKey = ExecutionKeys.scheduled(scheduleId, todayET);
        if (executionRepository.existsBySourceKey(sourceKey)) {
            logger.warn("Schedule {} already has an execution for {} ({})", scheduleId, todayET, sourceKey);
            return ProcessOutcome.DUPLICATE_SOURCE_KEY;
        }
        String nightKey = ExecutionKeys.night(user.getId(), todayET);
        if (executionRepository.existsByNightKey(nightKey)) {
            logger.warn("User {} already has a recurring execution for night {} ({})", user.getId(), todayET, nightKey);
            return ProcessOutcome.DUPLICATE_NIGHT_KEY;
        }

        InvestmentExecution execution = createInvestmentExecution(schedule, sourceKey, nightKey);
        logger.info("Recurring investment {} queued for end-of-day processing (schedule {}, user {})",
                execution.getId(), scheduleId, user.getEmail());
        updateScheduleAfterExecution(schedule, todayET);
        return ProcessOutcome.QUEUED;
    }

    /** MANUAL keeps both branches: start date today (first run) or next date due (spec 5.3 step 2). */
    private static boolean isDueOn(InvestmentSchedule schedule, LocalDate todayET) {
        return (schedule.getStartDate() != null && schedule.getStartDate().equals(todayET))
                || (schedule.getNextInvestmentDate() != null && !schedule.getNextInvestmentDate().isAfter(todayET));
    }

    /** Moved from InvestmentExecutionService; now stamps schedule, source_key and night_key (spec 5.3 step 4). */
    private InvestmentExecution createInvestmentExecution(InvestmentSchedule schedule, String sourceKey, String nightKey) {
        InvestmentExecution execution = new InvestmentExecution();
        execution.setUser(schedule.getUser());
        execution.setSchedule(schedule);
        execution.setAmount(schedule.getInvestmentAmount());
        execution.setScheduledDate(EasternTime.now());
        execution.setStatus(InvestmentExecution.ExecutionStatus.SCHEDULED);
        execution.setInvestmentType("recurring");
        execution.setFundingSource("bank");
        execution.setSourceKey(sourceKey);
        execution.setNightKey(nightKey);
        execution.setErrorMessage(RECURRING_BATCH_MARKER);
        return executionRepository.save(execution);
    }

    /** Moved from InvestmentExecutionService; MANUAL unchanged (spec 5.3 step 5), clock replaced by todayET. */
    private void updateScheduleAfterExecution(InvestmentSchedule schedule, LocalDate todayET) {
        LocalDate currentNextDate = schedule.getNextInvestmentDate();
        LocalDate newNextDate = schedule.calculateNextInvestmentDate(todayET);
        schedule.setNextInvestmentDate(newNextDate);

        LocalDate lastIncrement = schedule.getLastStreakIncrementDate();
        boolean alreadyIncrementedThisMonth = lastIncrement != null
                && lastIncrement.getYear() == todayET.getYear()
                && lastIncrement.getMonth() == todayET.getMonth();
        if (!alreadyIncrementedThisMonth) {
            int current = schedule.getMonthlyStreak() != null ? schedule.getMonthlyStreak() : 0;
            schedule.setMonthlyStreak(current + 1);
            schedule.setLastStreakIncrementDate(todayET);
            logger.info("Monthly streak incremented to {} for schedule {}", current + 1, schedule.getId());
        }

        investmentScheduleRepository.save(schedule);
        logger.info("Updated schedule {} next investment date from {} to {}", schedule.getId(), currentNextDate, newNextDate);
    }
}
```

Run the Step 4 command. Expected: `BUILD SUCCESSFUL`, 12 tests pass.

- [ ] **Step 6: Write the failing coordinator test**

`backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceCoordinatorTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Scope: ONLY the 11:55 coordinator shape: list ids, hand each to processOne with the same todayET, never stop on one failure, write nothing itself. */
class InvestmentExecutionServiceCoordinatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private InvestmentExecutionService service;
    private InvestmentScheduleService investmentScheduleService;
    private ScheduledInvestmentProcessor processor;
    private InvestmentExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        investmentScheduleService = mock(InvestmentScheduleService.class);
        processor = mock(ScheduledInvestmentProcessor.class);
        executionRepository = mock(InvestmentExecutionRepository.class);
        ReflectionTestUtils.setField(service, "investmentScheduleService", investmentScheduleService);
        ReflectionTestUtils.setField(service, "scheduledInvestmentProcessor", processor);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
    }

    private static InvestmentSchedule schedule(long id) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setId(id);
        return s;
    }

    @Test
    void duplicateKeyOnUserADoesNotRollBackUserB() {
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY)).thenReturn(List.of(schedule(1L), schedule(2L)));
        when(processor.processOne(1L, TODAY)).thenThrow(new DataIntegrityViolationException("uk_investment_executions_source_key"));
        when(processor.processOne(2L, TODAY)).thenReturn(ProcessOutcome.QUEUED);

        assertThatCode(() -> service.processScheduledInvestments(TODAY)).doesNotThrowAnyException();

        verify(processor).processOne(1L, TODAY);
        verify(processor).processOne(2L, TODAY);
    }

    @Test
    void passesTodayEtThroughAndWritesNothingItself() {
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY)).thenReturn(List.of(schedule(5L)));
        when(processor.processOne(anyLong(), any())).thenReturn(ProcessOutcome.NOT_DUE);

        service.processScheduledInvestments(TODAY);

        verify(investmentScheduleService).getSchedulesReadyForInvestment(TODAY);
        verify(processor).processOne(5L, TODAY);
        verifyNoInteractions(executionRepository);
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentExecutionServiceCoordinatorTest'`
Expected: both tests fail with `IllegalArgumentException: Could not find field 'scheduledInvestmentProcessor'`.

- [ ] **Step 7: Implement the coordinator and delete the two private methods**

In `InvestmentExecutionService.java` add to the imports (the `org.springframework.*` block at `:9-13` and the `com.investingapp.*` block at `:3-6`; skip `EasternTime` if T02 already imported it, a duplicate identical import also compiles):

```java
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import com.investingapp.backend.util.EasternTime;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
```

After `private AnalyticsOutboxService analyticsOutboxService;` (`:73`) add:

```java
    @Autowired
    private ScheduledInvestmentProcessor scheduledInvestmentProcessor;

    @Autowired
    private PlatformTransactionManager transactionManager;
```

Delete from the Javadoc `/** Process all scheduled investments for today */` (`:259-261`) through the closing brace of `updateScheduleAfterExecution` (`:371`, the line before the Javadoc "Process an investment execution using existing buying power" at `:373`). After T02 the method is `processScheduledInvestments(LocalDate todayET)` and the two private helpers may take `todayET`; delete all three and put in their place:

```java
    /**
     * The 11:55 PM ET coordinator (spec 5.3). Lists tonight's eligible schedules, then hands each
     * id to ScheduledInvestmentProcessor.processOne, which runs in its own REQUIRES_NEW transaction.
     * NOT_SUPPORTED here is what makes that true: under the class-level @Transactional every
     * schedule would share one transaction and one unique-key violation would roll back the night.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processScheduledInvestments(LocalDate todayET) {
        logger.info("Starting scheduled investment processing for {}", todayET);

        List<Long> scheduleIds = investmentScheduleService.getSchedulesReadyForInvestment(todayET)
                .stream()
                .map(InvestmentSchedule::getId)
                .toList();
        logger.info("Found {} investment schedules ready for execution", scheduleIds.size());

        Map<ProcessOutcome, Integer> tally = new EnumMap<>(ProcessOutcome.class);
        for (Long scheduleId : scheduleIds) {
            ProcessOutcome outcome;
            try {
                outcome = scheduledInvestmentProcessor.processOne(scheduleId, todayET);
            } catch (Exception e) {
                // One user's failure (a unique-key race, a broken row) never stops the night
                logger.error("Error processing investment schedule {} for {}", scheduleId, todayET, e);
                outcome = ProcessOutcome.FAILED;
            }
            tally.merge(outcome, 1, Integer::sum);
        }
        logger.info("Completed scheduled investment processing for {}: {}", todayET, tally);
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentExecutionServiceCoordinatorTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceAchAnalyticsTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceTlhGuardTest' --tests 'com.investingapp.backend.scheduler.InvestmentSchedulerTest'`
Expected: `BUILD SUCCESSFUL`, all pass (2 + 12 + 3 + the T02/T05 scheduler cases; the scheduler test still calls `processScheduledInvestments(todayET)` on a mocked service).
Then: `grep -n "createInvestmentExecution\|updateScheduleAfterExecution" backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java` must print nothing.

- [ ] **Step 8: Write the failing 11:59 batch test**

`backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceEodBatchTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scope: ONLY the three 11:59 hardening changes (spec 5.5, decision 15) around the unchanged
 * one-POST-per-user shape: selection by createdAt < batchStart plus the marker filter, the
 * conditional claim before the POST, the null-relationship fail-fast. The REQUIRES_NEW boundary
 * of the claim is not provable here: the transaction manager is a mock, so TransactionTemplate
 * runs the callback straight through (same pattern as TlhHarvestServiceCycleTest).
 */
class InvestmentExecutionServiceEodBatchTest {

    private static final LocalDateTime BATCH_START = LocalDateTime.of(2026, 10, 19, 23, 59, 0);
    private static final LocalDate BATCH_NIGHT = LocalDate.of(2026, 10, 19);
    private static final String MARKER = "Queued for end-of-day batch processing";

    private InvestmentExecutionService service;
    private InvestmentExecutionRepository executionRepository;
    private AlpacaService alpacaService;
    private EncryptionService encryptionService;
    private EmailService emailService;
    private User user;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        executionRepository = mock(InvestmentExecutionRepository.class);
        alpacaService = mock(AlpacaService.class);
        encryptionService = mock(EncryptionService.class);
        emailService = mock(EmailService.class);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(service, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(service, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));

        user = new User("batch@fredvested.com");
        user.setId(42L);
        user.setAlpacaAccountId("ENC:acct");
        user.setAlpacaAchRelationshipId("ENC:rel");

        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> i.getArgument(0));
        when(encryptionService.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        when(encryptionService.encrypt(anyString())).thenAnswer(i -> "ENC:" + i.getArgument(0));
        when(alpacaService.initiateAchTransfer(any(), any(), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse("xfer-1", "QUEUED", new BigDecimal("550.00"), null, null));
    }

    private InvestmentExecution queued(long id, User owner, String amount, String marker) {
        InvestmentExecution execution = new InvestmentExecution(
                owner, BATCH_START.minusHours(6), new BigDecimal(amount), "portfolio", null, "bank");
        execution.setId(id);
        execution.setStatus(ExecutionStatus.SCHEDULED);
        execution.setErrorMessage(marker);
        return execution;
    }

    private void selected(InvestmentExecution... executions) {
        when(executionRepository.findByStatusAndCreatedAtBefore(ExecutionStatus.SCHEDULED, BATCH_START))
                .thenReturn(List.of(executions));
    }

    @Test
    void batchClaimIsConditionalUpdate() {
        InvestmentExecution lump = queued(1L, user, "400.00", MARKER);
        InvestmentExecution recurring = queued(2L, user, "150.00", MARKER + " (recurring investment)");
        selected(lump, recurring);
        when(executionRepository.claimForBatch(List.of(1L, 2L), BATCH_START, BATCH_NIGHT)).thenReturn(2);

        service.runEndOfDayBatch(BATCH_START);

        InOrder order = inOrder(executionRepository, alpacaService);
        order.verify(executionRepository).claimForBatch(List.of(1L, 2L), BATCH_START, BATCH_NIGHT);
        order.verify(alpacaService).initiateAchTransfer("acct", "rel", new BigDecimal("550.00"));
        verify(alpacaService, times(1)).initiateAchTransfer(any(), any(), any());
        assertThat(List.of(lump, recurring)).allSatisfy(execution -> {
            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FUNDING_INITIATED);
            assertThat(execution.getAlpacaTransferId()).isEqualTo("xfer-1");
            // the claim is mirrored on the entity so the later flush cannot erase it
            assertThat(execution.getTransferAttemptedAt()).isEqualTo(BATCH_START);
            assertThat(execution.getBatchNight()).isEqualTo(BATCH_NIGHT);
        });
    }

    @Test
    void shortClaimSkipsTheUserAndPostsNothing() {
        InvestmentExecution a = queued(1L, user, "400.00", MARKER);
        InvestmentExecution b = queued(2L, user, "150.00", MARKER);
        selected(a, b);
        when(executionRepository.claimForBatch(List.of(1L, 2L), BATCH_START, BATCH_NIGHT)).thenReturn(1);

        service.runEndOfDayBatch(BATCH_START);

        verify(alpacaService, never()).initiateAchTransfer(any(), any(), any());
        verify(executionRepository, never()).save(any());
        assertThat(a.getStatus()).isEqualTo(ExecutionStatus.SCHEDULED);
        assertThat(a.getTransferAttemptedAt()).isNull();
    }

    @Test
    void nullRelationshipFailsFastWithoutClaimOrPost() {
        user.setAlpacaAchRelationshipId(null);
        InvestmentExecution a = queued(1L, user, "400.00", MARKER);
        InvestmentExecution b = queued(2L, user, "150.00", MARKER);
        selected(a, b);

        service.runEndOfDayBatch(BATCH_START);

        verify(alpacaService, never()).initiateAchTransfer(any(), any(), any());
        verify(executionRepository, never()).claimForBatch(anyList(), any(), any());
        verify(encryptionService, never()).decrypt(any());
        assertThat(List.of(a, b)).allSatisfy(execution -> {
            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FUNDING_FAILED);
            assertThat(execution.getErrorMessage()).isEqualTo("No ACH relationship on file");
        });
        verify(executionRepository, times(2)).save(any(InvestmentExecution.class));
        verify(emailService, times(1)).sendEmail(eq("batch@fredvested.com"), anyString(), contains("No ACH relationship on file"));
        verify(emailService).sendEmail(eq("batch@fredvested.com"), anyString(), contains("550.00"));
    }

    @Test
    void selectionIsCreatedAtBeforeBatchStartNotToday() {
        selected();
        service.runEndOfDayBatch(BATCH_START);
        verify(executionRepository).findByStatusAndCreatedAtBefore(ExecutionStatus.SCHEDULED, BATCH_START);
        verify(executionRepository, never()).findByStatus(any());
    }

    @Test
    void markerStringUnchanged() {
        selected(queued(1L, user, "400.00", null), queued(2L, user, "150.00", "Waiting on something else"));
        service.runEndOfDayBatch(BATCH_START);
        verify(executionRepository, never()).claimForBatch(anyList(), any(), any());
        verify(alpacaService, never()).initiateAchTransfer(any(), any(), any());
    }

    @Test
    void aFailedPostForOneUserDoesNotStopTheNext() {
        User second = new User("second@fredvested.com");
        second.setId(43L);
        second.setAlpacaAccountId("ENC:acct2");
        second.setAlpacaAchRelationshipId("ENC:rel2");
        InvestmentExecution mine = queued(1L, user, "400.00", MARKER);
        InvestmentExecution theirs = queued(9L, second, "75.00", MARKER);
        selected(mine, theirs);
        when(executionRepository.claimForBatch(anyList(), eq(BATCH_START), eq(BATCH_NIGHT))).thenReturn(1);
        when(alpacaService.initiateAchTransfer(eq("acct"), eq("rel"), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse(null, "FAILED", new BigDecimal("400.00"), null, "insufficient funds"));
        when(alpacaService.initiateAchTransfer(eq("acct2"), eq("rel2"), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse("xfer-2", "QUEUED", new BigDecimal("75.00"), null, null));

        service.runEndOfDayBatch(BATCH_START);

        assertThat(mine.getStatus()).isEqualTo(ExecutionStatus.FUNDING_FAILED);
        assertThat(theirs.getStatus()).isEqualTo(ExecutionStatus.FUNDING_INITIATED);
        assertThat(theirs.getAlpacaTransferId()).isEqualTo("xfer-2");
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentExecutionServiceEodBatchTest'`
Expected: compilation fails, `cannot find symbol: method runEndOfDayBatch(LocalDateTime)`.

- [ ] **Step 9: Harden the 11:59 batch**

In `InvestmentExecutionService.java` replace the whole `processEndOfDayBatchTransfers` method (Javadoc "Process all queued lump sum investments at end of day (11:59 PM ET)" at `:210` through the brace at `:257`). After T05 the method body starts with the gate and the `JobLocks.EOD_BATCH` lock; keep those statements exactly as T05 wrote them (field names, `Duration` import) and replace only what sits inside the lock's try. Target shape, the three lines marked T05 being T05's:

```java
    /**
     * The 11:59 PM ET batch (spec 5.5, decision 15). Selection, grouping, summing, the marker
     * match and the one-POST-per-user shape are unchanged. Hardening: (a) rows created before the
     * batch started, so a 23:59:xx row runs the next night instead of never; (b) a conditional
     * claim committed before the Alpaca POST, so a crash or a double run never sends two
     * transfers; (c) a null relationship id fails fast with no POST.
     */
    @Scheduled(cron = "0 59 23 * * *", zone = "America/New_York")
    public void processEndOfDayBatchTransfers() {
        if (!schedulerGate.isEnabled()) {                                              // T05
            return;
        }
        if (!jobLockService.tryAcquire(JobLocks.EOD_BATCH, Duration.ofMinutes(30))) {  // T05
            return;
        }
        try {
            runEndOfDayBatch(EasternTime.now());
        } finally {
            jobLockService.release(JobLocks.EOD_BATCH);                                // T05
        }
    }

    /** Package-private so the unit test drives one batch with a fixed batchStart, without gate or lock. */
    void runEndOfDayBatch(LocalDateTime batchStart) {
        logger.info("Starting end-of-day batch transfer processing at {}", batchStart);
        LocalDate batchNight = batchStart.toLocalDate();

        try {
            // (a) every SCHEDULED row created before this batch started, still carrying the queue marker
            List<InvestmentExecution> scheduledExecutions = executionRepository
                    .findByStatusAndCreatedAtBefore(InvestmentExecution.ExecutionStatus.SCHEDULED, batchStart)
                    .stream()
                    .filter(exec -> exec.getErrorMessage() != null &&
                            exec.getErrorMessage().contains("Queued for end-of-day"))
                    .toList();

            Map<User, List<InvestmentExecution>> executionsByUser = scheduledExecutions.stream()
                    .collect(Collectors.groupingBy(InvestmentExecution::getUser));
            logger.info("Found {} users with queued investments for end-of-day processing", executionsByUser.size());

            for (Map.Entry<User, List<InvestmentExecution>> entry : executionsByUser.entrySet()) {
                User user = entry.getKey();
                List<InvestmentExecution> userExecutions = entry.getValue();
                try {
                    // (c) fail fast, tested BEFORE any decrypt: no relationship id means no POST
                    if (user.getAlpacaAchRelationshipId() == null) {
                        failBatchWithoutPost(user, userExecutions, "No ACH relationship on file");
                        continue;
                    }

                    // (b) claim in its own committed transaction before the POST
                    List<Long> ids = userExecutions.stream().map(InvestmentExecution::getId).toList();
                    int claimed = claimInNewTransaction(ids, batchStart, batchNight);
                    if (claimed != ids.size()) {
                        logger.error("alert.batch_claim_short user={} expected={} claimed={}", user.getId(), ids.size(), claimed);
                        continue;
                    }
                    // Mirror the claim on the managed entities: this runs inside the class-level
                    // transaction and Hibernate writes every column on flush, so saving these rows
                    // with a null transferAttemptedAt would erase the claim.
                    for (InvestmentExecution execution : userExecutions) {
                        execution.setTransferAttemptedAt(batchStart);
                        execution.setBatchNight(batchNight);
                    }

                    logger.info("Processing {} queued investments for user {}", userExecutions.size(), user.getEmail());
                    processBatchedInvestments(user, userExecutions);
                } catch (Exception e) {
                    logger.error("Failed to process end-of-day batch for user {}: {}", user.getEmail(), e.getMessage(), e);
                }
            }
            logger.info("Completed end-of-day batch transfer processing");
        } catch (Exception e) {
            logger.error("Error in end-of-day batch transfer processing: {}", e.getMessage(), e);
        }
    }

    /** The compare-and-set claim (spec 5.5 b), committed before the Alpaca POST. */
    private int claimInNewTransaction(List<Long> ids, LocalDateTime batchStart, LocalDate batchNight) {
        TransactionTemplate claimTx = new TransactionTemplate(transactionManager);
        claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer claimed = claimTx.execute(status -> executionRepository.claimForBatch(ids, batchStart, batchNight));
        return claimed == null ? 0 : claimed;
    }

    /** Spec 5.5 (c): every row FUNDING_FAILED, the user told once with the total, nothing posted. */
    private void failBatchWithoutPost(User user, List<InvestmentExecution> executions, String details) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvestmentExecution execution : executions) {
            execution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_FAILED);
            execution.setErrorMessage(details);
            executionRepository.save(execution);
            total = total.add(execution.getAmount());
        }
        logger.error("alert.batch_no_relationship user={} rows={} total={}", user.getId(), executions.size(), total);
        notifyUserOfFailure(user, total, "Funding Failed", details);
    }
```

`processBatchedInvestments` (`:153-208`) stays as it is. Directly below the existing `notifyUserOfFailure(InvestmentExecution, String, String)` (`:1130-1143`) add the overload:

```java
    private void notifyUserOfFailure(User user, BigDecimal amount, String reason, String details) {
        try {
            String subject = "Investment Execution Failed";
            String message = String.format(
                    "Your scheduled investment of $%s could not be executed.\n\nReason: %s\n\nDetails: %s\n\nPlease check your account settings and ensure sufficient funds are available.",
                    amount, reason, details);
            emailService.sendEmail(user.getEmail(), subject, message);
        } catch (Exception e) {
            logger.error("Failed to send batch failure notification to user {}", user.getId(), e);
        }
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentExecutionServiceEodBatchTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceAchAnalyticsTest'`
Expected: `BUILD SUCCESSFUL`, 6 + 12 tests pass.

- [ ] **Step 10: Write the failing lump-sum controller test**

`backend/src/test/java/com/investingapp/backend/controller/InvestmentControllerLumpSumTest.java`:

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.InvestmentExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Scope: ONLY the lump-sum idempotency key on POST /api/investments/execute (decision 18). */
class InvestmentControllerLumpSumTest {

    private static final String CLIENT_UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private InvestmentController controller;
    private InvestmentExecutionRepository executionRepository;
    private InvestmentExecutionService investmentExecutionService;
    private Authentication authentication;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new InvestmentController();
        executionRepository = mock(InvestmentExecutionRepository.class);
        investmentExecutionService = mock(InvestmentExecutionService.class);
        UserRepository userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(controller, "investmentExecutionService", investmentExecutionService);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);

        user = new User("lump@fredvested.com");
        user.setId(42L);
        user.setAlpacaAccountId("ENC:acct");
        user.setAlpacaAchRelationshipId("ENC:rel");
        when(userRepository.findByEmail("lump@fredvested.com")).thenReturn(Optional.of(user));
        authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("lump@fredvested.com");

        when(executionRepository.findBySourceKey(anyString())).thenReturn(Optional.empty());
        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> {
            InvestmentExecution e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(101L);
            }
            return e;
        });
    }

    private Map<String, Object> body(Object clientUuid) {
        Map<String, Object> request = new HashMap<>();
        request.put("amount", 250);
        if (clientUuid != null) {
            request.put("clientUuid", clientUuid);
        }
        return request;
    }

    private InvestmentExecution saved() {
        ArgumentCaptor<InvestmentExecution> captor = ArgumentCaptor.forClass(InvestmentExecution.class);
        verify(executionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void stampsLumpSourceKeyFromTheClientUuidAndNoNightKey() {
        ResponseEntity<Map<String, Object>> response = controller.createManualInvestment(body(CLIENT_UUID), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        InvestmentExecution execution = saved();
        assertThat(execution.getSourceKey()).isEqualTo("LUMP:42:" + CLIENT_UUID);
        assertThat(execution.getNightKey()).isNull();
        verify(investmentExecutionService).processInvestmentExecutionImmediately(execution);
    }

    @Test
    void sameClientUuidTwiceReturnsTheFirstRowAndMakesNoSecond() {
        InvestmentExecution first = new InvestmentExecution(user, LocalDateTime.of(2026, 10, 19, 14, 0),
                new BigDecimal("250"), "portfolio", null, "bank");
        first.setId(77L);
        first.setStatus(ExecutionStatus.SCHEDULED);
        when(executionRepository.findBySourceKey("LUMP:42:" + CLIENT_UUID)).thenReturn(Optional.of(first));

        ResponseEntity<Map<String, Object>> response = controller.createManualInvestment(body(CLIENT_UUID), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true)
                .containsEntry("executionId", 77L)
                .containsEntry("duplicate", true);
        verify(executionRepository, never()).save(any());
        verify(investmentExecutionService, never()).processInvestmentExecutionImmediately(any());
    }

    @Test
    void missingClientUuidGetsAServerGeneratedOne() {
        controller.createManualInvestment(body(null), authentication);
        String sourceKey = saved().getSourceKey();
        assertThat(sourceKey).startsWith("LUMP:42:");
        String suffix = sourceKey.substring("LUMP:42:".length());
        assertThat(UUID.fromString(suffix).toString()).isEqualTo(suffix);
    }

    @Test
    void clientUuidIsNormalizedToLowercase() {
        controller.createManualInvestment(body(CLIENT_UUID.toUpperCase()), authentication);
        assertThat(saved().getSourceKey()).isEqualTo("LUMP:42:" + CLIENT_UUID);
    }

    @Test
    void malformedClientUuidIs400AndWritesNothing() {
        ResponseEntity<Map<String, Object>> response = controller.createManualInvestment(body("not-a-uuid"), authentication);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "Invalid clientUuid");
        verify(executionRepository, never()).save(any());
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.InvestmentControllerLumpSumTest'`
Expected: compiles (the T04 getters and the Step 3 `findBySourceKey` exist) and all 5 fail: `sourceKey` is null in three cases, the duplicate case still saves a row, the malformed case answers 200 instead of 400.

- [ ] **Step 11: Stamp the lump-sum source key in the controller**

In `InvestmentController.java` add to the imports after `:25` (`import java.util.stream.Collectors;`); skip `EasternTime` if T02 already imported it:

```java
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ExecutionKeys;
import java.util.Locale;
import java.util.UUID;
```

If T02 left `:61` reading `LocalDateTime since = LocalDateTime.now().minusMonths(6);` (the history window in `getInvestmentHistory`), change it to `LocalDateTime since = EasternTime.now().minusMonths(6);` so this file carries no bare clock after this task (global constraint).

Replace `:267-276` (from the comment `// Create investment execution with type and symbol information` through `execution = executionRepository.save(execution);`; after T02 the ctor argument reads `EasternTime.now()` instead of `LocalDateTime.now()`) with:

```java
            // Idempotency (decision 18): one row per tap. A retry carrying the same clientUuid
            // finds the first row and makes no second one. Older clients send none, so a
            // server-side UUID keeps the current app working.
            String clientUuid = resolveClientUuid(request.get("clientUuid"));
            if (clientUuid == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Invalid clientUuid"));
            }
            String sourceKey = ExecutionKeys.lump(user.getId(), clientUuid);
            Optional<InvestmentExecution> existing = executionRepository.findBySourceKey(sourceKey);
            if (existing.isPresent()) {
                InvestmentExecution duplicate = existing.get();
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Investment already queued.",
                        "executionId", duplicate.getId(),
                        "amount", duplicate.getAmount(),
                        "status", duplicate.getStatus(),
                        "queued", true,
                        "duplicate", true));
            }

            // Create investment execution with type and symbol information
            InvestmentExecution execution = new InvestmentExecution(
                    user,
                    EasternTime.now(),
                    amount,
                    investmentType,
                    selectedSymbol,
                    fundingSource);
            execution.setSourceKey(sourceKey);
            // nightKey stays null: one-per-night is for recurring rows only (spec 5.4)

            execution = executionRepository.save(execution);
```

Add this helper at the end of the class, before its closing brace (`:406` at HEAD):

```java
    /** The client's per-tap UUID normalized to lowercase; a fresh one when absent; null when present but not a UUID. */
    static String resolveClientUuid(Object raw) {
        if (raw == null || raw.toString().isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(raw.toString().trim().toLowerCase(Locale.ROOT)).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
```

Run the Step 10 command. Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 12: Write the failing frontend service spec**

`frontend/src/app/services/investment.service.spec.ts`:

```ts
import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { InvestmentService } from './investment.service';
import { AuthService } from './auth.service';
import { environment } from '../../environments/environment';

/**
 * Scope: ONLY the lump-sum idempotency key (decision 18). Every call generates one UUID and sends
 * it as clientUuid; the backend returns the first row on a retry instead of queueing a second.
 * crypto.randomUUID needs a secure context; Karma's http://localhost counts as one.
 */
describe('InvestmentService createManualInvestment clientUuid', () => {
  const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  let post: jasmine.Spy;
  let service: InvestmentService;

  beforeEach(() => {
    post = jasmine.createSpy('post').and.returnValue(of({ success: true, executionId: 1, queued: true }));
    service = new InvestmentService({ post } as unknown as HttpClient, {} as AuthService);
  });

  it('sends the amount and a v4 clientUuid to /investments/execute', () => {
    service.createManualInvestment(250).subscribe();

    expect(post).toHaveBeenCalledTimes(1);
    const [url, body] = post.calls.mostRecent().args;
    expect(url).toBe(`${environment.backendApiUrl}/investments/execute`);
    expect(body).toEqual({ amount: 250, clientUuid: jasmine.stringMatching(UUID_RE) });
  });

  it('generates a different clientUuid for each call, built once per call', () => {
    const first$ = service.createManualInvestment(250);
    first$.subscribe();
    first$.subscribe();
    service.createManualInvestment(250).subscribe();

    expect(post).toHaveBeenCalledTimes(2);
    expect(post.calls.argsFor(0)[1].clientUuid).toMatch(UUID_RE);
    expect(post.calls.argsFor(1)[1].clientUuid).toMatch(UUID_RE);
    expect(post.calls.argsFor(0)[1].clientUuid).not.toBe(post.calls.argsFor(1)[1].clientUuid);
  });
});
```

Run: `cd frontend && npx ng test --include='**/investment.service.spec.ts' --watch=false`
Expected: `TOTAL: 2 FAILED` (the first on `Expected $.clientUuid = undefined to equal <jasmine.stringMatching>`, the second on `Expected undefined to match` the UUID pattern).

- [ ] **Step 13: Send clientUuid from InvestmentService**

In `frontend/src/app/services/investment.service.ts` replace `:153-160` (the Javadoc `Create a manual investment execution` through the method's closing brace) with:

```ts
  /**
   * Create a manual investment execution. clientUuid is generated once per call: a retry of this
   * request carries the same UUID and the backend returns the first row instead of queueing a
   * second one (decision 18).
   */
  createManualInvestment(amount: number): Observable<any> {
    const clientUuid = crypto.randomUUID();
    return this.http.post(`${this.apiUrl}/execute`, { amount, clientUuid }, {
      headers: this.getAuthHeaders()
    });
  }
```

Run the Step 12 command. Expected: `TOTAL: 2 SUCCESS`.

- [ ] **Step 14: Write the failing lump-sum page spec (the real tap path)**

`frontend/src/app/lump-sum-investment/lump-sum-investment.page.client-uuid.spec.ts` (constructor order copied from `lump-sum-investment.page.spec.ts:43-55`):

```ts
import { fakeAsync, tick } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of } from 'rxjs';
import { LumpSumInvestmentPage } from './lump-sum-investment.page';
import { InvestmentService } from '../services/investment.service';
import { PortfolioService } from '../services/portfolio.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { environment } from '../../environments/environment';

/**
 * Scope: ONLY the per-tap clientUuid on the lump-sum POST (decision 18). This page posts to
 * /investments/execute directly, so it is the request the backend keys on.
 */
describe('LumpSumInvestmentPage clientUuid', () => {
  const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  let http: jasmine.SpyObj<HttpClient>;
  let pin: jasmine.SpyObj<PinService>;
  let page: LumpSumInvestmentPage;

  beforeEach(() => {
    http = jasmine.createSpyObj<HttpClient>('HttpClient', ['get', 'post']);
    pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    http.post.and.returnValue(of({ success: true, message: 'Investment initiated successfully!' }));

    page = new LumpSumInvestmentPage(
      jasmine.createSpyObj<Router>('Router', ['navigate']),
      { queryParams: of({}), params: of({}), snapshot: { queryParams: {} } } as unknown as ActivatedRoute,
      jasmine.createSpyObj<InvestmentService>('InvestmentService', ['initiateAcatsTransfer']),
      jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getPortfolioDashboard']),
      http,
      jasmine.createSpyObj<PasskeyService>('PasskeyService', ['startAuthentication']),
      pin,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
        'track',
        'trackOnboardingStepViewed',
        'trackOnboardingStepCompleted',
        'trackErrorShown',
        'screen',
      ]),
    );
  });

  it('a tap posts amount, type, fundingSource and a v4 clientUuid', fakeAsync(() => {
    page.investmentAmount = 500;

    page.makeInvestment();
    tick();
    tick(2000); // the deferred form reset the success path schedules

    expect(http.post).toHaveBeenCalledTimes(1);
    const [url, body] = http.post.calls.mostRecent().args;
    expect(url).toBe(`${environment.backendApiUrl}/investments/execute`);
    expect(body).toEqual({
      amount: 500,
      type: 'portfolio',
      fundingSource: 'bank',
      clientUuid: jasmine.stringMatching(UUID_RE),
    });
  }));

  it('two taps send two different clientUuids', fakeAsync(() => {
    page.investmentAmount = 500;

    page.makeInvestment();
    tick();
    page.makeInvestment();
    tick();
    tick(2000);

    expect(http.post).toHaveBeenCalledTimes(2);
    const first = http.post.calls.argsFor(0)[1] as { clientUuid: string };
    const second = http.post.calls.argsFor(1)[1] as { clientUuid: string };
    expect(first.clientUuid).toMatch(UUID_RE);
    expect(second.clientUuid).toMatch(UUID_RE);
    expect(first.clientUuid).not.toBe(second.clientUuid);
  }));
});
```

Run: `cd frontend && npx ng test --include='**/lump-sum-investment.page.client-uuid.spec.ts' --watch=false`
Expected: `TOTAL: 2 FAILED` (`clientUuid` is undefined in both).

- [ ] **Step 15: Send clientUuid from the lump-sum page**

In `frontend/src/app/lump-sum-investment/lump-sum-investment.page.ts` replace `:384-389` (the comment `// Prepare request payload` through the closing `};` of `requestData`) with:

```ts
      // Prepare request payload. clientUuid is minted once per tap (decision 18): a retried
      // request carries the same id and the backend answers with the first row instead of
      // queueing a second investment.
      const requestData: any = {
        amount: this.investmentAmount,
        type: this.investmentType,
        fundingSource: this.fundingSource,
        clientUuid: crypto.randomUUID()
      };
```

Run: `cd frontend && npx ng test --include='**/lump-sum-investment.page.client-uuid.spec.ts' --watch=false` then `cd frontend && npx ng test --include='**/lump-sum-investment.page.spec.ts' --watch=false`
Expected: `TOTAL: 2 SUCCESS`, then the existing analytics spec still fully green (it never asserts the request body).

- [ ] **Step 16: Full regression for the files this task touched**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.util.ExecutionKeysTest' --tests 'com.investingapp.backend.service.ScheduledInvestmentProcessorTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceCoordinatorTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceEodBatchTest' --tests 'com.investingapp.backend.controller.InvestmentControllerLumpSumTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceAchAnalyticsTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceTlhGuardTest' --tests 'com.investingapp.backend.scheduler.InvestmentSchedulerTest' --tests 'com.investingapp.backend.BackendApplicationTests'`
Expected: `BUILD SUCCESSFUL`; `BackendApplicationTests` shows PASSED (not skipped) when the ci MySQL from the T04 validate gate is reachable (same `SPRING_DATASOURCE_*` env vars as T04), which proves the new derived-query names and the `claimForBatch` JPQL parse at context start.
Then: `grep -rn "LocalDate.now()\|LocalDateTime.now()" backend/src/main/java/com/investingapp/backend/service/ScheduledInvestmentProcessor.java backend/src/main/java/com/investingapp/backend/util/ExecutionKeys.java backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java` must print nothing, and `grep -n "LocalDateTime.now()" backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java` must print exactly two lines: the comment "LocalDateTime.now() and not MARKET_TIMEZONE on purpose" (HEAD `:641`) and the deliberate `AnalyticsBands.daysBetween(..., LocalDateTime.now())` call (HEAD `:645`).
Then: `grep -rn "—" backend/src/main/java/com/investingapp/backend/util/ExecutionKeys.java backend/src/main/java/com/investingapp/backend/service/ScheduledInvestmentProcessor.java backend/src/test/java/com/investingapp/backend/util/ExecutionKeysTest.java backend/src/test/java/com/investingapp/backend/service/ScheduledInvestmentProcessorTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceCoordinatorTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceEodBatchTest.java backend/src/test/java/com/investingapp/backend/controller/InvestmentControllerLumpSumTest.java frontend/src/app/services/investment.service.spec.ts frontend/src/app/lump-sum-investment/lump-sum-investment.page.client-uuid.spec.ts` must print nothing (new files carry no em dash; the pre-existing ones in `InvestmentExecutionService.java:106,514,557,581,594,834,839,902,907` and `InvestmentController.java:319` sit outside the ranges this task replaces and are left alone).

- [ ] **Step 17: Commit**

Hunk-verify first (shared dirty tree): `git diff HEAD -- backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java backend/src/main/java/com/investingapp/backend/repository/InvestmentExecutionRepository.java frontend/src/app/services/investment.service.ts frontend/src/app/lump-sum-investment/lump-sum-investment.page.ts` and confirm every hunk is from this task. Then:

```
git add backend/src/main/java/com/investingapp/backend/util/ExecutionKeys.java backend/src/main/java/com/investingapp/backend/service/ScheduledInvestmentProcessor.java backend/src/main/java/com/investingapp/backend/repository/InvestmentExecutionRepository.java backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java frontend/src/app/services/investment.service.ts frontend/src/app/lump-sum-investment/lump-sum-investment.page.ts backend/src/test/java/com/investingapp/backend/util/ExecutionKeysTest.java backend/src/test/java/com/investingapp/backend/service/ScheduledInvestmentProcessorTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceCoordinatorTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceEodBatchTest.java backend/src/test/java/com/investingapp/backend/controller/InvestmentControllerLumpSumTest.java frontend/src/app/services/investment.service.spec.ts frontend/src/app/lump-sum-investment/lump-sum-investment.page.client-uuid.spec.ts
git commit -m "feat(investing): make the nightly crons idempotent with source and night keys"
```