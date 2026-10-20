### Task 5: job-locks-and-scheduler-flag

Spec 3.9 (`job_locks`), 5.6 (capacity paragraph), 12.1 (`investment.scheduler.enabled`, pools). Needs T02 (`EasternTime`, `InvestmentExecutionService.processScheduledInvestments(LocalDate)`, the T02-created `InvestmentSchedulerTest`) and T04 (`JobLock` entity on the `job_locks` table from V6). Gate scope, fixed by the contract: the `@Scheduled` methods of `InvestmentScheduler` (8), `AccountStatusScheduler` (2) and `InvestmentExecutionService.processEndOfDayBatchTransfers` read the flag; `TlhScheduler` (ctor pinned by `TlhSchedulerTest.java:25`), `StreakService`, `MarketBreakdownScheduler`, `ReferralValidationScheduler`, `AnalyticsPublisherScheduler`, `DailyQuestionService` and `ConversationAnalyticsService` stay untouched. Locks: `PROCESS_SCHEDULED` (11:55, 10 min), `EOD_BATCH` (11:59, 30 min), `ACH_POLL` (every 15 min, 10 min); `PLAID_ITEM_REMOVALS` is declared here and first used by T09.

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/config/SchedulerGate.java`, `backend/src/main/java/com/investingapp/backend/service/JobLocks.java`, `backend/src/main/java/com/investingapp/backend/service/JobLockService.java`, `backend/src/main/java/com/investingapp/backend/repository/JobLockRepository.java`
- Modify: `backend/src/main/java/com/investingapp/backend/config/SchedulingConfig.java:6` (import), `:16-19` (comment + pool size), after `:24` (new bean); `backend/src/main/resources/application.properties:16`; `backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java` (imports after `:12`, fields after `:26`, `processScheduledInvestments` `:34-43`, first statement of the other seven `@Scheduled` methods at `:51`, `:67`, `:84`, `:99`, `:124`, `:134`, `:144`; HEAD numbers, T02 may have shifted them by a few lines); `backend/src/main/java/com/investingapp/backend/scheduler/AccountStatusScheduler.java:6-10` (imports), `:17-21` (field + ctor), `:29` (gate), `:44-53` (ACH poll); `backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java:4` (import), `:17` (import), after `:73` (fields), `:215-219` (method opening), `:254-257` (method closing)
- Test: create `backend/src/test/java/com/investingapp/backend/config/SchedulerGateTest.java`, `backend/src/test/java/com/investingapp/backend/service/JobLockServiceTest.java`, `backend/src/test/java/com/investingapp/backend/scheduler/AccountStatusSchedulerTest.java`, `backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceEodGateTest.java`; extend `backend/src/test/java/com/investingapp/backend/scheduler/InvestmentSchedulerTest.java` (created by T02)

**Interfaces:**
- Consumes: `com.investingapp.backend.util.EasternTime.today()` / `now()` and `InvestmentExecutionService.processScheduledInvestments(LocalDate todayET)` (T02); `com.investingapp.backend.model.JobLock { @Id String name; LocalDateTime lockedUntil; String lockedBy }` on table `job_locks` (T04); `AccountStatusService.checkAndUpdateAccountStatuses()` (`AccountStatusService.java:60`) and `setupPendingAchRelationships()` (`:180`); `org.springframework.transaction.PlatformTransactionManager` (the JPA one Spring Boot already provides)
- Produces: `@Component public class com.investingapp.backend.config.SchedulerGate { @Value("${investment.scheduler.enabled:true}") boolean enabled; public SchedulerGate(); public SchedulerGate(boolean enabled); public boolean isEnabled() }`
- Produces: `public final class com.investingapp.backend.service.JobLocks { PROCESS_SCHEDULED = "investment.process-scheduled"; EOD_BATCH = "investment.eod-batch"; ACH_POLL = "ach.relationship-poll"; PLAID_ITEM_REMOVALS = "plaid.item-removals" }`
- Produces: `@Service public class com.investingapp.backend.service.JobLockService { public JobLockService(JobLockRepository, PlatformTransactionManager); public boolean tryAcquire(String name, Duration lease) /* own REQUIRES_NEW transaction via TransactionTemplate: insertIgnore == 1 || renew == 1; never throws, a database failure reads as false + logger.error("alert.job_lock.unavailable ...") */; public void release(String name) /* REQUIRES_NEW deleteOwned(name, owner); never throws */ }`
- Produces: `@Repository public interface com.investingapp.backend.repository.JobLockRepository extends JpaRepository<JobLock, String> { @Modifying native int insertIgnore(String name, LocalDateTime until, String owner); @Modifying native int renew(String name, LocalDateTime now, LocalDateTime until, String owner); @Modifying native int deleteOwned(String name, String owner) }`
- Produces: `@Bean(name = "plaidExecutor") ThreadPoolTaskExecutor plaidExecutor()` in `SchedulingConfig` (core 2, max 4, queue 100, prefix `plaid-`; T09 injects it as `@Qualifier("plaidExecutor") ThreadPoolTaskExecutor`); `taskScheduler` pool 6; `spring.datasource.hikari.maximum-pool-size=10`
- Produces: `AccountStatusScheduler(AccountStatusService, SchedulerGate, JobLockService)`; `InvestmentScheduler` and `InvestmentExecutionService` gain `@Autowired private SchedulerGate schedulerGate;` and `@Autowired private JobLockService jobLockService;`

- [ ] **Step 1: Failing SchedulerGate test**

Create `backend/src/test/java/com/investingapp/backend/config/SchedulerGateTest.java`:

```java
package com.investingapp.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerGateTest {

    @Test
    void isEnabledReflectsTheFlag() {
        assertTrue(new SchedulerGate(true).isEnabled());
        assertFalse(new SchedulerGate(false).isEnabled());
    }

    @Test
    void springFillsTheFlagFromInvestmentSchedulerEnabledDefaultingToTrue() throws Exception {
        Value value = SchedulerGate.class.getDeclaredField("enabled").getAnnotation(Value.class);
        assertNotNull(value, "the enabled field must carry @Value");
        assertEquals("${investment.scheduler.enabled:true}", value.value());
        assertTrue(new SchedulerGate().isEnabled(), "a bare instance (no Spring) defaults to open");
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.SchedulerGateTest'`
Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol ... class SchedulerGate`.

- [ ] **Step 2: Create SchedulerGate**

Create `backend/src/main/java/com/investingapp/backend/config/SchedulerGate.java`:

```java
package com.investingapp.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads investment.scheduler.enabled (false under the ci profile). The investment, ACH and
 * Plaid lifecycle @Scheduled jobs check it as their first statement and return when false.
 * Spring uses the no-arg constructor and injects the field; tests use the boolean constructor.
 */
@Component
public class SchedulerGate {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerGate.class);

    @Value("${investment.scheduler.enabled:true}")
    private boolean enabled = true;

    public SchedulerGate() {
    }

    public SchedulerGate(boolean enabled) {
        this.enabled = enabled;
    }

    @PostConstruct
    void logState() {
        if (!enabled) {
            logger.info("investment.scheduler.enabled=false: the investment, ACH and Plaid lifecycle jobs return immediately");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
```

Run the Step 1 command. Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 3: Failing JobLockService test**

Create `backend/src/test/java/com/investingapp/backend/service/JobLockServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.repository.JobLockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobLockServiceTest {

    private static final Duration LEASE = Duration.ofMinutes(10);

    private JobLockRepository repository;
    private PlatformTransactionManager transactionManager;
    private JobLockService service;

    @BeforeEach
    void setUp() {
        repository = mock(JobLockRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = new JobLockService(repository, transactionManager);
    }

    @Test
    void freshRowIsTakenByInsertAloneInItsOwnTransaction() {
        when(repository.insertIgnore(eq(JobLocks.EOD_BATCH), any(), anyString())).thenReturn(1);

        assertTrue(service.tryAcquire(JobLocks.EOD_BATCH, LEASE));

        verify(repository, never()).renew(anyString(), any(), any(), anyString());
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior(),
                "the lock row must commit on its own, before the job body runs");
        verify(transactionManager).commit(any());
    }

    @Test
    void expiredLeaseIsTakenOverByRenewWithUntilEqualToNowPlusLease() {
        when(repository.insertIgnore(anyString(), any(), anyString())).thenReturn(0);
        when(repository.renew(eq(JobLocks.ACH_POLL), any(), any(), anyString())).thenReturn(1);

        assertTrue(service.tryAcquire(JobLocks.ACH_POLL, LEASE));

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).renew(eq(JobLocks.ACH_POLL), now.capture(), until.capture(), anyString());
        assertEquals(now.getValue().plus(LEASE), until.getValue());
    }

    @Test
    void runningLeaseIsNotTaken() {
        when(repository.insertIgnore(anyString(), any(), anyString())).thenReturn(0);
        when(repository.renew(anyString(), any(), any(), anyString())).thenReturn(0);

        assertFalse(service.tryAcquire(JobLocks.EOD_BATCH, LEASE));
    }

    @Test
    void databaseFailureReadsAsNotAcquiredAndNeverThrows() {
        when(repository.insertIgnore(anyString(), any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        boolean acquired = assertDoesNotThrow(() -> service.tryAcquire(JobLocks.EOD_BATCH, LEASE));

        assertFalse(acquired);
        verify(transactionManager).rollback(any());
    }

    @Test
    void releaseDeletesOnlyTheRowThisProcessOwns() {
        when(repository.insertIgnore(anyString(), any(), anyString())).thenReturn(1);
        service.tryAcquire(JobLocks.EOD_BATCH, LEASE);
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIgnore(anyString(), any(), owner.capture());

        service.release(JobLocks.EOD_BATCH);

        verify(repository).deleteOwned(JobLocks.EOD_BATCH, owner.getValue());
        assertFalse(owner.getValue().isBlank());
        assertTrue(owner.getValue().length() <= 64, "locked_by is VARCHAR(64)");
    }

    @Test
    void releaseSwallowsDatabaseFailuresBecauseTheLeaseExpiresAnyway() {
        when(repository.deleteOwned(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> service.release(JobLocks.EOD_BATCH));
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.JobLockServiceTest'`
Expected: `> Task :compileTestJava FAILED` with `cannot find symbol` for `JobLockRepository`, `JobLocks` and `JobLockService`.

- [ ] **Step 4: Create JobLocks, JobLockRepository, JobLockService**

Create `backend/src/main/java/com/investingapp/backend/service/JobLocks.java`:

```java
package com.investingapp.backend.service;

/** Names of the job_locks rows (spec 3.9). Never share one name between two jobs. */
public final class JobLocks {

    public static final String PROCESS_SCHEDULED = "investment.process-scheduled";
    public static final String EOD_BATCH = "investment.eod-batch";
    public static final String ACH_POLL = "ach.relationship-poll";
    public static final String PLAID_ITEM_REMOVALS = "plaid.item-removals";

    private JobLocks() {
    }
}
```

Create `backend/src/main/java/com/investingapp/backend/repository/JobLockRepository.java`:

```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.JobLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface JobLockRepository extends JpaRepository<JobLock, String> {

    /** 1 when the row was created, 0 when the name already exists (INSERT IGNORE on the primary key). */
    @Modifying
    @Query(value = "INSERT IGNORE INTO job_locks (name, locked_until, locked_by) VALUES (:name, :until, :owner)",
            nativeQuery = true)
    int insertIgnore(@Param("name") String name, @Param("until") LocalDateTime until, @Param("owner") String owner);

    /** 1 when an expired lease was taken over, 0 while the previous holder's lease still runs. */
    @Modifying
    @Query(value = "UPDATE job_locks SET locked_until = :until, locked_by = :owner "
            + "WHERE name = :name AND locked_until < :now", nativeQuery = true)
    int renew(@Param("name") String name, @Param("now") LocalDateTime now,
            @Param("until") LocalDateTime until, @Param("owner") String owner);

    /** Deletes the row only while this process still owns it. */
    @Modifying
    @Query(value = "DELETE FROM job_locks WHERE name = :name AND locked_by = :owner", nativeQuery = true)
    int deleteOwned(@Param("name") String name, @Param("owner") String owner);
}
```

Create `backend/src/main/java/com/investingapp/backend/service/JobLockService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.repository.JobLockRepository;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Lease-based locks on job_locks (spec 3.9, 5.6). A crashed holder is taken over once its
 * lease expires. Both calls run in their own REQUIRES_NEW transaction (same shape as
 * TlhHarvestService.claimTx) so the lock row commits before the job body starts, even when
 * the caller is already inside a transaction (InvestmentExecutionService is class-level
 * @Transactional). Neither call throws: a job that cannot reach job_locks is skipped.
 */
@Service
public class JobLockService {

    private static final Logger logger = LoggerFactory.getLogger(JobLockService.class);

    private final JobLockRepository jobLockRepository;
    private final TransactionTemplate requiresNew;
    private final String owner;

    public JobLockService(JobLockRepository jobLockRepository, PlatformTransactionManager transactionManager) {
        this.jobLockRepository = jobLockRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName(); // pid@host
        this.owner = runtimeName.length() > 64 ? runtimeName.substring(0, 64) : runtimeName;
    }

    /** True when this process now holds the lock until now + lease. */
    public boolean tryAcquire(String name, Duration lease) {
        LocalDateTime now = EasternTime.now();
        LocalDateTime until = now.plus(lease);
        try {
            Boolean acquired = requiresNew.execute(status ->
                    jobLockRepository.insertIgnore(name, until, owner) == 1
                            || jobLockRepository.renew(name, now, until, owner) == 1);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException e) {
            logger.error("alert.job_lock.unavailable job={}", name, e);
            return false;
        }
    }

    /** Drops the row if this process still owns it; a failure is logged, the lease expires on its own. */
    public void release(String name) {
        try {
            requiresNew.executeWithoutResult(status -> jobLockRepository.deleteOwned(name, owner));
        } catch (RuntimeException e) {
            logger.error("alert.job_lock.release_failed job={}", name, e);
        }
    }
}
```

Run the Step 3 command. Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Add the gate and lock cases to InvestmentSchedulerTest**

Open `backend/src/test/java/com/investingapp/backend/scheduler/InvestmentSchedulerTest.java` (created by T02 in the `TlhSchedulerTest` pattern: `scheduler = new InvestmentScheduler()` plus `ReflectionTestUtils.setField(scheduler, "investmentExecutionService", ...)`, `"dripService"`, `"paydayNotificationService"`). First run `grep -n "private .*;" backend/src/test/java/com/investingapp/backend/scheduler/InvestmentSchedulerTest.java` and note the local variable names T02 chose for the three mocks and the scheduler; the code below uses `investmentExecutionService`, `dripService`, `paydayNotificationService` and `scheduler`; if T02 chose other variable names, use T02's names (the string field names passed to `setField` are fixed by the production class and are not variables).

Add these imports (a duplicate of an import T02 already has is legal Java and can stay):

```java
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import org.mockito.InOrder;
import java.time.Duration;
import java.time.LocalDate;
```

Add these fields beside the existing mocks:

```java
    private SchedulerGate schedulerGate;
    private JobLockService jobLockService;
```

Add these lines at the end of `setUp()` (every T02 test keeps passing: the gate is open and the lock is free):

```java
        schedulerGate = mock(SchedulerGate.class);
        jobLockService = mock(JobLockService.class);
        when(schedulerGate.isEnabled()).thenReturn(true);
        when(jobLockService.tryAcquire(anyString(), any())).thenReturn(true);
        ReflectionTestUtils.setField(scheduler, "schedulerGate", schedulerGate);
        ReflectionTestUtils.setField(scheduler, "jobLockService", jobLockService);
```

Add these tests:

```java
    private void runEveryJob() {
        scheduler.processScheduledInvestments();
        scheduler.checkFundingStatus();
        scheduler.checkTradingStatus();
        scheduler.processDripDividends();
        scheduler.checkDripOrderStatus();
        scheduler.sendDayBeforePaydayNotifications();
        scheduler.sendPaydayNotifications();
        scheduler.sendDayAfterPaydayNotifications();
    }

    @Test
    void closedGateSkipsEveryJob() {
        when(schedulerGate.isEnabled()).thenReturn(false);

        runEveryJob();

        verifyNoInteractions(investmentExecutionService, dripService, paydayNotificationService, jobLockService);
    }

    @Test
    void onlyTheElevenFiftyFiveJobTakesALock() {
        runEveryJob();

        verify(jobLockService, times(1)).tryAcquire(anyString(), any());
        verify(jobLockService).tryAcquire(JobLocks.PROCESS_SCHEDULED, Duration.ofMinutes(10));
    }

    @Test
    void elevenFiftyFiveTakesItsLockRunsThenReleasesEvenWhenTheServiceThrows() {
        scheduler.processScheduledInvestments();

        InOrder inOrder = inOrder(jobLockService, investmentExecutionService);
        inOrder.verify(jobLockService).tryAcquire(JobLocks.PROCESS_SCHEDULED, Duration.ofMinutes(10));
        inOrder.verify(investmentExecutionService).processScheduledInvestments(any(LocalDate.class));
        inOrder.verify(jobLockService).release(JobLocks.PROCESS_SCHEDULED);

        doThrow(new IllegalStateException("boom")).when(investmentExecutionService).processScheduledInvestments(any());
        assertDoesNotThrow(scheduler::processScheduledInvestments);
        verify(jobLockService, times(2)).release(JobLocks.PROCESS_SCHEDULED);
    }

    @Test
    void elevenFiftyFiveSkipsWhenTheLockIsNotTaken() {
        when(jobLockService.tryAcquire(JobLocks.PROCESS_SCHEDULED, Duration.ofMinutes(10))).thenReturn(false);

        scheduler.processScheduledInvestments();

        verify(investmentExecutionService, never()).processScheduledInvestments(any());
        verify(jobLockService, never()).release(anyString());
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.scheduler.InvestmentSchedulerTest'`
Expected: every test errors in `setUp` with `IllegalArgumentException: Could not find field 'schedulerGate' ... on target object of type [class com.investingapp.backend.scheduler.InvestmentScheduler]`.

- [ ] **Step 6: Gate every InvestmentScheduler job and lock the 11:55 one**

In `backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java` add these imports after `import java.time.LocalDate;` (HEAD `:12`; skip any line T02 already added):

```java
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.util.EasternTime;
import java.time.Duration;
```

After the `paydayNotificationService` field (HEAD `:25-26`) add:

```java
    @Autowired
    private SchedulerGate schedulerGate;

    @Autowired
    private JobLockService jobLockService;
```

Replace the whole `processScheduledInvestments()` method (HEAD `:34-43`, from its `@Scheduled` line through its closing brace; T02 already changed its body to pass `todayET`) with:

```java
    @Scheduled(cron = "0 55 23 * * *", zone = "America/New_York")
    public void processScheduledInvestments() {
        if (!schedulerGate.isEnabled()) return;
        if (!jobLockService.tryAcquire(JobLocks.PROCESS_SCHEDULED, Duration.ofMinutes(10))) {
            logger.warn("Skipping scheduled investment processing: job lock {} not taken (held by another run or job_locks unreachable)",
                    JobLocks.PROCESS_SCHEDULED);
            return;
        }
        LocalDate todayET = EasternTime.today();
        logger.info("Starting scheduled investment processing job for {}", todayET);
        try {
            investmentExecutionService.processScheduledInvestments(todayET);
            logger.info("Completed scheduled investment processing job successfully");
        } catch (Exception e) {
            logger.error("Error in scheduled investment processing job", e);
        } finally {
            jobLockService.release(JobLocks.PROCESS_SCHEDULED);
        }
    }
```

Insert `        if (!schedulerGate.isEnabled()) return;` as the first statement (directly after the opening brace, before the `logger.info`) of the other seven `@Scheduled` methods, leaving the rest of their bodies as T02 left them: `checkFundingStatus()` (HEAD `:51`), `checkTradingStatus()` (`:67`), `processDripDividends()` (`:84`), `checkDripOrderStatus()` (`:99`), `sendDayBeforePaydayNotifications()` (`:124`), `sendPaydayNotifications()` (`:134`), `sendDayAfterPaydayNotifications()` (`:144`).

Run the Step 5 command. Expected: `BUILD SUCCESSFUL`, T02's tests plus the 4 new ones pass.

- [ ] **Step 7: Failing AccountStatusScheduler test**

Create `backend/src/test/java/com/investingapp/backend/scheduler/AccountStatusSchedulerTest.java`:

```java
package com.investingapp.backend.scheduler;

import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.AccountStatusService;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountStatusSchedulerTest {

    private AccountStatusService accountStatusService;
    private SchedulerGate schedulerGate;
    private JobLockService jobLockService;
    private AccountStatusScheduler scheduler;

    @BeforeEach
    void setUp() {
        accountStatusService = mock(AccountStatusService.class);
        schedulerGate = mock(SchedulerGate.class);
        jobLockService = mock(JobLockService.class);
        when(schedulerGate.isEnabled()).thenReturn(true);
        when(jobLockService.tryAcquire(anyString(), any())).thenReturn(true);
        scheduler = new AccountStatusScheduler(accountStatusService, schedulerGate, jobLockService);
    }

    @Test
    void closedGateSkipsBothJobs() {
        when(schedulerGate.isEnabled()).thenReturn(false);

        scheduler.pollAccountStatuses();
        scheduler.setupPendingAchRelationships();

        verifyNoInteractions(accountStatusService, jobLockService);
    }

    @Test
    void onlyTheAchPollLocksAndItReleasesEvenWhenTheServiceThrows() {
        scheduler.pollAccountStatuses();
        scheduler.setupPendingAchRelationships();

        verify(accountStatusService).checkAndUpdateAccountStatuses();
        verify(jobLockService, times(1)).tryAcquire(anyString(), any());
        InOrder inOrder = inOrder(jobLockService, accountStatusService);
        inOrder.verify(jobLockService).tryAcquire(JobLocks.ACH_POLL, Duration.ofMinutes(10));
        inOrder.verify(accountStatusService).setupPendingAchRelationships();
        inOrder.verify(jobLockService).release(JobLocks.ACH_POLL);

        doThrow(new IllegalStateException("boom")).when(accountStatusService).setupPendingAchRelationships();
        assertDoesNotThrow(scheduler::setupPendingAchRelationships);
        verify(jobLockService, times(2)).release(JobLocks.ACH_POLL);
    }

    @Test
    void achPollSkipsWhenTheLockIsNotTaken() {
        when(jobLockService.tryAcquire(JobLocks.ACH_POLL, Duration.ofMinutes(10))).thenReturn(false);

        scheduler.setupPendingAchRelationships();

        verify(accountStatusService, never()).setupPendingAchRelationships();
        verify(jobLockService, never()).release(anyString());
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.scheduler.AccountStatusSchedulerTest'`
Expected: `> Task :compileTestJava FAILED` with `constructor AccountStatusScheduler in class AccountStatusScheduler cannot be applied to given types`.

- [ ] **Step 8: Gate AccountStatusScheduler and lock the ACH poll**

In `backend/src/main/java/com/investingapp/backend/scheduler/AccountStatusScheduler.java` add these imports after `import com.investingapp.backend.service.AccountStatusService;` (`:6`):

```java
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import java.time.Duration;
```

Replace the field and constructor (`:17-21`) with:

```java
    private final AccountStatusService accountStatusService;
    private final SchedulerGate schedulerGate;
    private final JobLockService jobLockService;

    public AccountStatusScheduler(AccountStatusService accountStatusService,
            SchedulerGate schedulerGate,
            JobLockService jobLockService) {
        this.accountStatusService = accountStatusService;
        this.schedulerGate = schedulerGate;
        this.jobLockService = jobLockService;
    }
```

In `pollAccountStatuses()` insert `        if (!schedulerGate.isEnabled()) return;` directly after the opening brace (`:29`, before `logger.info("Starting account status poll job")`). Replace `setupPendingAchRelationships()` (`:44-53`, from its `@Scheduled` line through its closing brace) with:

```java
    @Scheduled(cron = "0 */15 * * * *")
    public void setupPendingAchRelationships() {
        if (!schedulerGate.isEnabled()) return;
        if (!jobLockService.tryAcquire(JobLocks.ACH_POLL, Duration.ofMinutes(10))) {
            logger.warn("Skipping ACH setup retry: job lock {} not taken (held by another run or job_locks unreachable)",
                    JobLocks.ACH_POLL);
            return;
        }
        logger.info("Starting ACH setup retry job");
        try {
            accountStatusService.setupPendingAchRelationships();
            logger.info("Completed ACH setup retry job successfully");
        } catch (Exception e) {
            logger.error("Error in ACH setup retry job", e);
        } finally {
            jobLockService.release(JobLocks.ACH_POLL);
        }
    }
```

Run the Step 7 command. Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 9: Failing 11:59 batch gate test**

Create `backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceEodGateTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Only the gate and the lock around processEndOfDayBatchTransfers. The batch body is not
 * stubbed by query name on purpose: T06 replaces findByStatus with
 * findByStatusAndCreatedAtBefore and this test must keep passing unchanged.
 */
class InvestmentExecutionServiceEodGateTest {

    /** Every repository method throws; Object methods (toString, hashCode) keep their defaults. */
    private static final Answer<Object> DB_DOWN = invocation -> {
        if (invocation.getMethod().getDeclaringClass() == Object.class) {
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }
        throw new IllegalStateException("db down");
    };

    private InvestmentExecutionService service;
    private InvestmentExecutionRepository executionRepository;
    private SchedulerGate schedulerGate;
    private JobLockService jobLockService;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        // RETURNS_DEFAULTS: every List-returning query answers an empty list, so the batch finds no work.
        executionRepository = mock(InvestmentExecutionRepository.class);
        schedulerGate = mock(SchedulerGate.class);
        jobLockService = mock(JobLockService.class);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(service, "schedulerGate", schedulerGate);
        ReflectionTestUtils.setField(service, "jobLockService", jobLockService);
        when(schedulerGate.isEnabled()).thenReturn(true);
        when(jobLockService.tryAcquire(anyString(), any())).thenReturn(true);
    }

    @Test
    void closedGateTouchesNothing() {
        when(schedulerGate.isEnabled()).thenReturn(false);

        service.processEndOfDayBatchTransfers();

        verifyNoInteractions(jobLockService, executionRepository);
    }

    @Test
    void untakenLockSkipsTheBatchWithoutReleasing() {
        when(jobLockService.tryAcquire(JobLocks.EOD_BATCH, Duration.ofMinutes(30))).thenReturn(false);

        service.processEndOfDayBatchTransfers();

        verifyNoInteractions(executionRepository);
        verify(jobLockService, never()).release(anyString());
    }

    @Test
    void batchTakesItsLockRunsThenReleases() {
        service.processEndOfDayBatchTransfers();

        InOrder inOrder = inOrder(jobLockService);
        inOrder.verify(jobLockService).tryAcquire(JobLocks.EOD_BATCH, Duration.ofMinutes(30));
        inOrder.verify(jobLockService).release(JobLocks.EOD_BATCH);
        assertFalse(mockingDetails(executionRepository).getInvocations().isEmpty(),
                "the batch body must run between acquire and release");
    }

    @Test
    void lockIsReleasedEvenWhenTheBatchThrows() {
        ReflectionTestUtils.setField(service, "executionRepository",
                mock(InvestmentExecutionRepository.class, DB_DOWN));

        assertDoesNotThrow(service::processEndOfDayBatchTransfers);

        verify(jobLockService).release(JobLocks.EOD_BATCH);
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentExecutionServiceEodGateTest'`
Expected: all 4 tests error in `setUp` with `IllegalArgumentException: Could not find field 'schedulerGate' ...`.

- [ ] **Step 10: Gate and lock the 11:59 batch**

In `backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java`: after `import com.investingapp.backend.repository.*;` (`:4`) add `import com.investingapp.backend.config.SchedulerGate;`; after `import java.time.DayOfWeek;` (`:17`) add `import java.time.Duration;` (`JobLockService` and `JobLocks` are in the same package, no import). After the `analyticsOutboxService` field (`:72-73`) add:

```java
    @Autowired
    private SchedulerGate schedulerGate;

    @Autowired
    private JobLockService jobLockService;
```

Replace the method opening (`:215-219`, verbatim today:)

```java
    @Scheduled(cron = "0 59 23 * * *", zone = "America/New_York")
    public void processEndOfDayBatchTransfers() {
        logger.info("Starting end-of-day batch transfer processing");

        try {
```

with:

```java
    @Scheduled(cron = "0 59 23 * * *", zone = "America/New_York")
    public void processEndOfDayBatchTransfers() {
        if (!schedulerGate.isEnabled()) return;
        if (!jobLockService.tryAcquire(JobLocks.EOD_BATCH, Duration.ofMinutes(30))) {
            logger.warn("Skipping end-of-day batch transfer processing: job lock {} not taken (held by another run or job_locks unreachable)",
                    JobLocks.EOD_BATCH);
            return;
        }
        logger.info("Starting end-of-day batch transfer processing");

        try {
```

and the method closing (`:254-257`, verbatim today:)

```java
        } catch (Exception e) {
            logger.error("Error in end-of-day batch transfer processing: {}", e.getMessage(), e);
        }
    }
```

with:

```java
        } catch (Exception e) {
            logger.error("Error in end-of-day batch transfer processing: {}", e.getMessage(), e);
        } finally {
            jobLockService.release(JobLocks.EOD_BATCH);
        }
    }
```

The class is class-level `@Transactional`, so the batch body still runs inside one transaction; `JobLockService` opens its own REQUIRES_NEW transaction for the lock row, which is why the lock commits before the body starts. T06 keeps this gate/lock wrapper and rewrites only what sits inside the `try`.

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentExecutionServiceEodGateTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceAchAnalyticsTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceTlhGuardTest'`
Expected: `BUILD SUCCESSFUL`; the two existing suites never call the batch method and still pass.

- [ ] **Step 11: Raise the pools**

In `backend/src/main/java/com/investingapp/backend/config/SchedulingConfig.java` add `import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;` after `:6` (`import org.springframework.scheduling.annotation.EnableScheduling;`). Replace the comment and pool line (`:16-19`, verbatim today:)

```java
        // TLH adds a daily cycle, a 15-min lifecycle poller, an EOD sweep, and
        // a nightly reconciliation beside the investment crons — pool 2 let one
        // slow job delay funding/trading polls.
        taskScheduler.setPoolSize(4);
```

with:

```java
        // TLH's four jobs sit beside the investment crons; paycheck autopilot adds the webhook
        // inbox, the item removal drain and its reconcile, so the pool is 6 (spec 12.1).
        taskScheduler.setPoolSize(6);
```

and add this bean after the closing brace of `taskScheduler()` (`:24`), before the class's closing brace:

```java
    /**
     * Bounded pool for Plaid HTTP calls that run off a request thread (spec 5.6, 12.1). Nothing in
     * the app injects an Executor by type (only TaskScheduler), so a second executor bean is
     * unambiguous; consumers still name it with @Qualifier("plaidExecutor").
     */
    @Bean(name = "plaidExecutor")
    public ThreadPoolTaskExecutor plaidExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("plaid-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
```

In `backend/src/main/resources/application.properties` replace `:16` (`spring.datasource.hikari.maximum-pool-size=4`) with:

```properties
# 10, not 4: scheduler pool 6 plus the REQUIRES_NEW job-lock and batch-claim transactions (spec 5.6).
spring.datasource.hikari.maximum-pool-size=10
```

Run: `cd backend && ./gradlew compileJava && grep -cE 'setPoolSize\(6\)|setMaxPoolSize\(4\)|name = "plaidExecutor"' src/main/java/com/investingapp/backend/config/SchedulingConfig.java && grep -c 'maximum-pool-size=10' src/main/resources/application.properties && ! grep -c '—' src/main/java/com/investingapp/backend/config/SchedulingConfig.java`
Expected: `BUILD SUCCESSFUL`, then `3`, then `1`, then `0` (the old em dash comment is gone).

- [ ] **Step 12: Regression run, ci context boot, native SQL smoke**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.SchedulerGateTest' --tests 'com.investingapp.backend.service.JobLockServiceTest' --tests 'com.investingapp.backend.scheduler.InvestmentSchedulerTest' --tests 'com.investingapp.backend.scheduler.AccountStatusSchedulerTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceEodGateTest' --tests 'com.investingapp.backend.scheduler.TlhSchedulerTest'`
Expected: `BUILD SUCCESSFUL`, 0 failures (TlhSchedulerTest proves the untouched scheduler still builds with its 2-arg ctor).

Boot the ci context against a scratch schema on the local MySQL (T04 drops its scratch schema when it finishes, so create it again; never `investing_dev`; the password is the value of `spring.datasource.password` at `backend/src/main/resources/application-local.properties:3`). From the repo root:

```bash
mysql -h 127.0.0.1 -u root -p"$(sed -n 's/^spring.datasource.password=//p' backend/src/main/resources/application-local.properties)" -e "CREATE DATABASE IF NOT EXISTS fred_ci_scratch"
cd backend && SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_ci_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$(sed -n 's/^spring.datasource.password=//p' src/main/resources/application-local.properties)" ./gradlew test --tests 'com.investingapp.backend.BackendApplicationTests'
```

Expected: `BUILD SUCCESSFUL` and `backend/build/reports/tests/test/index.html` shows `contextLoads` PASSED, not skipped (a skip prints `ci database not reachable, skipping the context test`). This proves `SchedulerGate`, `JobLockService` (with the JPA `PlatformTransactionManager`), `JobLockRepository` and the `plaidExecutor` bean all wire under ci with `investment.scheduler.enabled=false`.

Hibernate never validates native SQL, so exercise the three statements on the scratch schema (Flyway V6 created `job_locks` during the boot above). From the repo root:

```bash
mysql -h 127.0.0.1 -u root -p"$(sed -n 's/^spring.datasource.password=//p' backend/src/main/resources/application-local.properties)" fred_ci_scratch -e "INSERT IGNORE INTO job_locks (name, locked_until, locked_by) VALUES ('smoke', NOW(6) + INTERVAL 10 MINUTE, 'me'); SELECT ROW_COUNT() AS first_insert; INSERT IGNORE INTO job_locks (name, locked_until, locked_by) VALUES ('smoke', NOW(6), 'other'); SELECT ROW_COUNT() AS second_insert; UPDATE job_locks SET locked_until = NOW(6) + INTERVAL 10 MINUTE, locked_by = 'other' WHERE name = 'smoke' AND locked_until < NOW(6); SELECT ROW_COUNT() AS renew_while_running; DELETE FROM job_locks WHERE name = 'smoke' AND locked_by = 'other'; SELECT ROW_COUNT() AS delete_as_stranger; DELETE FROM job_locks WHERE name = 'smoke' AND locked_by = 'me'; SELECT ROW_COUNT() AS delete_as_owner;"
mysql -h 127.0.0.1 -u root -p"$(sed -n 's/^spring.datasource.password=//p' backend/src/main/resources/application-local.properties)" -e "DROP DATABASE fred_ci_scratch"
```

Expected: `first_insert 1`, `second_insert 0`, `renew_while_running 0`, `delete_as_stranger 0`, `delete_as_owner 1`.

- [ ] **Step 13: Commit**

Hunk-verify the shared files first (another session edits this checkout): `git diff HEAD -- backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java backend/src/main/resources/application.properties backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java` must show only the Step 6, 10 and 11 hunks on top of T02's committed state. Then, from the repo root:

```bash
git add backend/src/main/java/com/investingapp/backend/config/SchedulerGate.java backend/src/main/java/com/investingapp/backend/config/SchedulingConfig.java backend/src/main/java/com/investingapp/backend/repository/JobLockRepository.java backend/src/main/java/com/investingapp/backend/service/JobLocks.java backend/src/main/java/com/investingapp/backend/service/JobLockService.java backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java backend/src/main/java/com/investingapp/backend/scheduler/AccountStatusScheduler.java backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java backend/src/main/resources/application.properties backend/src/test/java/com/investingapp/backend/config/SchedulerGateTest.java backend/src/test/java/com/investingapp/backend/service/JobLockServiceTest.java backend/src/test/java/com/investingapp/backend/scheduler/InvestmentSchedulerTest.java backend/src/test/java/com/investingapp/backend/scheduler/AccountStatusSchedulerTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceEodGateTest.java
git commit -m "feat(scheduler): honor investment.scheduler.enabled and lease job locks for the money crons"
```