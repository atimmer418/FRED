### Task 2: timezone (EasternTime, JVM default to America/New_York, todayET handed down every money and notification cron)

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/util/EasternTime.java`
- Create: `backend/src/test/java/com/investingapp/backend/util/EasternTimeTest.java`
- Create: `backend/src/test/java/com/investingapp/backend/scheduler/InvestmentSchedulerTest.java`
- Create: `backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceReadyDateTest.java`
- Create: `backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceTodayEtTest.java`
- Modify: `backend/src/main/java/com/investingapp/backend/BackendApplication.java:15-17`
- Modify: `backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java:5 (import), :34-43, :123-151`
- Modify: `backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java:5 (import), :31, :259-324, :333, :342-371` (keep the bare `LocalDateTime.now()` at :645 and its comment :641-644)
- Modify: `backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java:7 (import), :77, :81, :133, :136, :293-312` (keep :199, analytics band)
- Modify: `backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java:9 (import), :69-86, :92-93, :297-317`
- Modify: `backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java:10 (import), :61, :268-274`
- Test (existing, must stay green): `service/InvestmentExecutionServiceAchAnalyticsTest`, `service/InvestmentExecutionServiceTlhGuardTest`, `service/InvestmentScheduleServiceAnalyticsTest`, `scheduler/TlhSchedulerTest`
- Not touched: `PaydayNotificationService.java` (its three methods already take `LocalDate`; T02 only changes what the scheduler passes), `SchedulingConfig.java` (T05), `User.java`

All line numbers in this task are HEAD (9a09482) numbers. Every multi-edit step below is ordered from the bottom of the file upward so each number is still valid when you reach it. The only callers of the three signatures that change are `InvestmentScheduler.java:38` and `InvestmentScheduleService.java:307`; no existing test calls them.

**Interfaces:**
- Consumes: nothing from other tasks (T02 has no dependencies). Existing and unchanged: `PaydayNotificationService.sendDayBeforeNotifications(LocalDate)` :30, `sendPaydayNotifications(LocalDate)` :46, `sendDayAfterNotifications(LocalDate)` :63; `InvestmentSchedule.calculateNextInvestmentDate(LocalDate)` :91.
- Produces:
  - `public final class com.investingapp.backend.util.EasternTime { public static final ZoneId ZONE = ZoneId.of("America/New_York"); public static LocalDate today(); public static LocalDateTime now(); }` (consumers T05, T06, T09, T10, T11, T13)
  - `BackendApplication.main`: `TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")); System.setProperty("user.timezone", "America/New_York");`
  - `public void InvestmentExecutionService.processScheduledInvestments(LocalDate todayET)` replaces the no-arg at :262 (T06 rewrites the body into the coordinator); private `updateScheduleAfterExecution(InvestmentSchedule schedule, LocalDate todayET)`; `MARKET_TIMEZONE = EasternTime.ZONE`
  - `@Transactional(readOnly = true) public List<InvestmentSchedule> InvestmentScheduleService.getSchedulesReadyForInvestment(LocalDate todayET)` filtering with `s -> isReady(s, todayET)`; package-private `boolean isReady(InvestmentSchedule schedule, LocalDate todayET)` delegating to the entity (T10 replaces its body with the mode switch)
  - `public boolean InvestmentSchedule.isReadyForInvestment(LocalDate todayET)` replaces the no-arg at :300 (T10 adds the hold clause)
  - `InvestmentScheduler`: `processScheduledInvestments`, `sendDayBeforePaydayNotifications`, `sendPaydayNotifications`, `sendDayAfterPaydayNotifications` each compute `LocalDate todayET = EasternTime.today()` once and pass `processScheduledInvestments(todayET)`, `sendDayBeforeNotifications(todayET)`, `sendPaydayNotifications(todayET.minusDays(1))`, `sendDayAfterNotifications(todayET)` (consumers T05, T13)

- [ ] **Step 1: Write the failing EasternTime test (also pins decision 17 at the source of main)**

Two of these tests move the JVM default zone and restore it in `finally`. That is safe here: `backend/build.gradle:62-64` is `useJUnitPlatform()` with no parallel settings and there is no `junit-platform.properties` under `backend/src/test`, so test classes run one at a time in a single fork.

Create `backend/src/test/java/com/investingapp/backend/util/EasternTimeTest.java`:

```java
package com.investingapp.backend.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One clock for money and notifications (spec 5.8, decision 17).
 *
 * <p>BackendApplication.main also pins the JVM default to Eastern, but tests, tools and anything
 * that bypasses main run with the machine zone, so money paths must name the zone themselves. These
 * tests move the JVM default to a zone whose calendar day differs from Eastern right now and prove
 * EasternTime does not follow it.
 */
class EasternTimeTest {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    /** A JVM default whose calendar day differs from Eastern at this moment. */
    static TimeZone aZoneWhoseDayDiffersFromEastern() {
        int easternHour = LocalTime.now(EASTERN).getHour();
        return TimeZone.getTimeZone(easternHour < 6 ? ZoneId.of("-12:00") : ZoneId.of("+14:00"));
    }

    @Test
    void zoneIsAmericaNewYork() {
        assertEquals(EASTERN, EasternTime.ZONE);
    }

    @Test
    void todayIgnoresTheJvmDefaultZone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(aZoneWhoseDayDiffersFromEastern());
            LocalDate expected = LocalDate.now(EASTERN);
            assertNotEquals(expected, LocalDate.now(),
                    "precondition: the JVM default day must differ from Eastern for this test to bite");
            assertEquals(expected, EasternTime.today());
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void nowReadsTheEasternWallClock() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(aZoneWhoseDayDiffersFromEastern());
            LocalDateTime expected = LocalDateTime.now(EASTERN);
            LocalDateTime actual = EasternTime.now();
            assertTrue(Math.abs(ChronoUnit.SECONDS.between(expected, actual)) < 5,
                    "EasternTime.now() drifted from the Eastern wall clock: " + actual + " vs " + expected);
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    /**
     * Decision 17: the JVM default moves from UTC to America/New_York. main() boots Spring, so the
     * pin is checked at the source, the way AnalyticsBandsParityTest reads the client band table.
     * Gradle runs tests with backend/ as the working directory, so the path is project-relative.
     */
    @Test
    void jvmDefaultInMainIsPinnedToTheSameZone() throws IOException {
        Path main = Paths.get("src", "main", "java", "com", "investingapp", "backend",
                "BackendApplication.java").toAbsolutePath().normalize();
        assertTrue(Files.exists(main), "cannot find BackendApplication.java at " + main);
        String source = Files.readString(main);
        assertTrue(source.contains("TimeZone.setDefault(TimeZone.getTimeZone(\"America/New_York\"))"),
                "BackendApplication.main must pin the JVM default to America/New_York");
        assertTrue(source.contains("System.setProperty(\"user.timezone\", \"America/New_York\")"),
                "BackendApplication.main must set user.timezone to America/New_York");
        assertFalse(source.contains("\"UTC\""), "BackendApplication.main must no longer mention UTC");
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.util.EasternTimeTest'
```
Expected: `BUILD FAILED`, compileTestJava reports `error: cannot find symbol ... symbol: variable EasternTime` (three sites in EasternTimeTest: `EasternTime.ZONE`, `EasternTime.today()`, `EasternTime.now()`).

- [ ] **Step 3: Create EasternTime**

Create `backend/src/main/java/com/investingapp/backend/util/EasternTime.java`:

```java
package com.investingapp.backend.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The one clock for money paths and notifications (spec 5.8, decision 17).
 *
 * <p>Every cron computes {@code todayET} once through {@link #today()} and passes it down, so a job
 * that straddles midnight keeps one calendar day. Callers never read {@code LocalDate.now()} with
 * the JVM default, even though {@code BackendApplication.main} pins that default to the same zone:
 * tests and tools run without main, and money math must not depend on where it runs.
 */
public final class EasternTime {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    private EasternTime() {
    }

    /** The Eastern calendar day right now. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** The Eastern wall clock right now. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
```

- [ ] **Step 4: Run again; three pass, the main() pin still fails**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.util.EasternTimeTest'
```
Expected: `zoneIsAmericaNewYork`, `todayIgnoresTheJvmDefaultZone`, `nowReadsTheEasternWallClock` PASSED; `jvmDefaultInMainIsPinnedToTheSameZone` FAILED with `BackendApplication.main must pin the JVM default to America/New_York`.

- [ ] **Step 5: Move the JVM default to Eastern in BackendApplication.main**

In `backend/src/main/java/com/investingapp/backend/BackendApplication.java` replace lines 15-17 (tab-indented in the file):

```java
		// Force UTC timezone to prevent date shifting issues
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		System.setProperty("user.timezone", "UTC");
```
with:
```java
		// Decision 17: the server runs on Eastern time. Money paths and notifications still name
		// EasternTime.ZONE explicitly; this pin only keeps bare clocks elsewhere on the same day.
		TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
		System.setProperty("user.timezone", "America/New_York");
```

- [ ] **Step 6: Run; all four pass**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.util.EasternTimeTest'
```
Expected: `BUILD SUCCESSFUL`, 4 tests PASSED.

- [ ] **Step 7: Write the failing eligibility-clock test for InvestmentScheduleService**

Create `backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceReadyDateTest.java` (same `@ExtendWith(MockitoExtension) + @InjectMocks` wiring as `InvestmentScheduleServiceAnalyticsTest.java:50-66`; the service's four-arg `@Autowired` constructor at `InvestmentScheduleService.java:35-42` is what `@InjectMocks` fills):

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Eligibility is judged against the day the cron passes in, never against a clock the entity reads
 * itself. The dates sit in 2031 so no wall clock can satisfy them by accident.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentScheduleServiceReadyDateTest {

    private static final LocalDate TODAY_ET = LocalDate.of(2031, 3, 4);

    @Mock
    private InvestmentScheduleRepository investmentScheduleRepository;
    @Mock
    private PortfolioService portfolioService;
    @Mock
    private StreakService streakService;
    @Mock
    private AnalyticsOutboxService analyticsOutboxService;

    @InjectMocks
    private InvestmentScheduleService service;

    private static InvestmentSchedule schedule(long id, LocalDate startDate, LocalDate nextInvestmentDate,
            boolean paused, String achRequestId) {
        User user = new User("ready-" + id + "@fredvested.com");
        user.setId(id);
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(id);
        schedule.setUser(user);
        schedule.setFrequency("WEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("50.00"));
        schedule.setMonthlyAmount(new BigDecimal("216.50"));
        schedule.setStartDate(startDate);
        schedule.setNextInvestmentDate(nextInvestmentDate);
        schedule.setIsPaused(paused);
        schedule.setAchRequestId(achRequestId);
        return schedule;
    }

    @Test
    void onlySchedulesDueOnThePassedDayAreReady() {
        InvestmentSchedule dueToday = schedule(1, TODAY_ET.minusWeeks(4), TODAY_ET, false, "ach-1");
        InvestmentSchedule pastDue = schedule(2, TODAY_ET.minusWeeks(4), TODAY_ET.minusDays(3), false, "ach-2");
        InvestmentSchedule startsToday = schedule(3, TODAY_ET, TODAY_ET.plusWeeks(1), false, "ach-3");
        InvestmentSchedule dueTomorrow = schedule(4, TODAY_ET.minusWeeks(4), TODAY_ET.plusDays(1), false, "ach-4");
        InvestmentSchedule paused = schedule(5, TODAY_ET.minusWeeks(4), TODAY_ET, true, "ach-5");
        InvestmentSchedule noAch = schedule(6, TODAY_ET.minusWeeks(4), TODAY_ET, false, null);
        when(investmentScheduleRepository.findByUser_AccountStatus("ACTIVE"))
                .thenReturn(List.of(dueToday, pastDue, startsToday, dueTomorrow, paused, noAch));

        List<InvestmentSchedule> ready = service.getSchedulesReadyForInvestment(TODAY_ET);

        assertEquals(List.of(1L, 2L, 3L), ready.stream().map(InvestmentSchedule::getId).toList());
    }

    @Test
    void theSameScheduleIsNotReadyTheDayBefore() {
        InvestmentSchedule dueToday = schedule(1, TODAY_ET.minusWeeks(4), TODAY_ET, false, "ach-1");
        when(investmentScheduleRepository.findByUser_AccountStatus("ACTIVE")).thenReturn(List.of(dueToday));

        assertEquals(List.of(), service.getSchedulesReadyForInvestment(TODAY_ET.minusDays(1)));
    }
}
```

- [ ] **Step 8: Write the failing todayET test for the 11:55 service method**

Create `backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceTodayEtTest.java` (house pattern from `InvestmentExecutionServiceAchAnalyticsTest.java:62-98`; `InvestmentExecutionService` has no constructor, its collaborators are `@Autowired` fields named `investmentScheduleService`, `investmentScheduleRepository`, `executionRepository` at `InvestmentExecutionService.java:39-46`):

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 11:55 job works on the day the scheduler hands it, not on a clock it reads itself. 2031 dates
 * so no wall clock can satisfy them by accident; 2031-03-04 is a Tuesday and 2031-03-11 (one week
 * on, the WEEKLY next date) is a Tuesday with no US holiday, so adjustForBusinessDay leaves it alone.
 *
 * <p>T06 moves the per-schedule body into ScheduledInvestmentProcessor: carry
 * aScheduleDueOnThePassedDayIsQueuedAgainstThatDay into ScheduledInvestmentProcessorTest then, and
 * keep asksForSchedulesDueOnThePassedDay on the coordinator.
 */
class InvestmentExecutionServiceTodayEtTest {

    private static final LocalDate TODAY_ET = LocalDate.of(2031, 3, 4);

    private InvestmentExecutionService service;
    private InvestmentScheduleService investmentScheduleService;
    private InvestmentScheduleRepository scheduleRepository;
    private InvestmentExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        investmentScheduleService = mock(InvestmentScheduleService.class);
        scheduleRepository = mock(InvestmentScheduleRepository.class);
        executionRepository = mock(InvestmentExecutionRepository.class);
        ReflectionTestUtils.setField(service, "investmentScheduleService", investmentScheduleService);
        ReflectionTestUtils.setField(service, "investmentScheduleRepository", scheduleRepository);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> i.getArgument(0));
        when(scheduleRepository.save(any(InvestmentSchedule.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static InvestmentSchedule weeklyDueOn(LocalDate day) {
        User user = new User("today-et@fredvested.com");
        user.setId(7L);
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(11L);
        schedule.setUser(user);
        schedule.setFrequency("WEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("50.00"));
        schedule.setMonthlyAmount(new BigDecimal("216.50"));
        schedule.setStartDate(day.minusWeeks(4));
        schedule.setChosenDate(day.minusWeeks(4));
        schedule.setNextInvestmentDate(day);
        schedule.setIsPaused(false);
        schedule.setAchRequestId("ach-11");
        return schedule;
    }

    @Test
    void asksForSchedulesDueOnThePassedDay() {
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY_ET)).thenReturn(List.of());

        service.processScheduledInvestments(TODAY_ET);

        verify(investmentScheduleService).getSchedulesReadyForInvestment(TODAY_ET);
        verify(executionRepository, never()).save(any());
    }

    @Test
    void aScheduleDueOnThePassedDayIsQueuedAgainstThatDay() {
        InvestmentSchedule schedule = weeklyDueOn(TODAY_ET);
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY_ET)).thenReturn(List.of(schedule));

        service.processScheduledInvestments(TODAY_ET);

        ArgumentCaptor<InvestmentExecution> saved = ArgumentCaptor.forClass(InvestmentExecution.class);
        verify(executionRepository, atLeastOnce()).save(saved.capture());
        InvestmentExecution execution = saved.getValue();
        assertEquals(InvestmentExecution.ExecutionStatus.SCHEDULED, execution.getStatus());
        assertEquals("recurring", execution.getInvestmentType());
        assertEquals("Queued for end-of-day batch processing (recurring investment)", execution.getErrorMessage());
        // Streak date and next date come from todayET, not from the wall clock
        assertEquals(TODAY_ET, schedule.getLastStreakIncrementDate());
        assertEquals(Integer.valueOf(1), schedule.getMonthlyStreak());
        assertEquals(TODAY_ET.plusWeeks(1), schedule.getNextInvestmentDate());
    }
}
```

- [ ] **Step 9: Run both; they fail to compile against the no-arg signatures**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentScheduleServiceReadyDateTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceTodayEtTest'
```
Expected: `BUILD FAILED`, compileTestJava reports `method getSchedulesReadyForInvestment in class InvestmentScheduleService cannot be applied to given types` and `method processScheduledInvestments in class InvestmentExecutionService cannot be applied to given types`.

- [ ] **Step 10: InvestmentSchedule: zone-explicit lifecycle clocks and isReadyForInvestment(todayET)**

In `backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java`, bottom up.

10a. Replace lines 297-317 (the whole `isReadyForInvestment()` method and its Javadoc, from `    /**` through the closing `    }`):
```java
    /**
     * Check if this schedule is ready for investment execution on the given Eastern calendar day.
     * The cron computes todayET once per run and passes it in; this method never reads a clock.
     */
    public boolean isReadyForInvestment(LocalDate todayET) {
        // Must not be paused and must have ACH linked
        boolean basicRequirements = !isPaused && achRequestId != null;

        if (!basicRequirements) {
            return false;
        }

        // Check if either:
        // 1. Start date is today (first-time investment)
        // 2. Next investment date is today or past due (recurring investment)
        boolean dateRequirement = (startDate != null && startDate.equals(todayET)) ||
                (nextInvestmentDate != null && !nextInvestmentDate.isAfter(todayET));

        return dateRequirement;
    }
```

10b. Replace lines 92-93 (inside `calculateNextInvestmentDate`):
```java
        if (fromDate == null)
            fromDate = LocalDate.now();
```
with:
```java
        if (fromDate == null)
            fromDate = EasternTime.today();
```

10c. Replace lines 69-86 (`@PrePersist` through the closing brace of `onUpdate`):
```java
    @PrePersist
    protected void onCreate() {
        createdAt = EasternTime.now();
        updatedAt = EasternTime.now();

        // Set initial dates if not provided
        if (startDate == null) {
            startDate = EasternTime.today();
        }
        if (nextInvestmentDate == null) {
            nextInvestmentDate = calculateNextInvestmentDate(startDate);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = EasternTime.now();
    }
```

10d. Add after line 9 (`import java.time.LocalDateTime;`):
```java
import com.investingapp.backend.util.EasternTime;
```

- [ ] **Step 11: InvestmentScheduleService: zone-explicit create path and getSchedulesReadyForInvestment(todayET)**

In `backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java`, bottom up.

11a. Replace lines 293-312 (Javadoc + `getSchedulesReadyForInvestment()`, from `    /**` through the closing `    }`):
```java
    /**
     * Get all schedules ready for investment execution on {@code todayET} (for cron processing).
     * Only returns schedules where:
     *   - isPaused == false (the user has not paused the schedule), AND
     *   - user.accountStatus == "ACTIVE" (the Alpaca account is fully active)
     * Both conditions must be true for a trade to be placed.
     */
    @Transactional(readOnly = true)
    public List<InvestmentSchedule> getSchedulesReadyForInvestment(LocalDate todayET) {
        logger.info("Finding investment schedules ready for execution on {}...", todayET);

        List<InvestmentSchedule> readySchedules = investmentScheduleRepository
                .findByUser_AccountStatus("ACTIVE")
                .stream()
                .filter(s -> isReady(s, todayET))
                .toList();

        logger.info("Found {} schedules ready for investment execution", readySchedules.size());
        return readySchedules;
    }

    /**
     * Eligibility of one schedule on one Eastern calendar day. Phase 0 knows only MANUAL schedules;
     * T10 turns this into the mode switch and adds the hold clause.
     */
    boolean isReady(InvestmentSchedule schedule, LocalDate todayET) {
        return schedule.isReadyForInvestment(todayET);
    }
```

11b. Leave line 199 (`AnalyticsBands.daysBetween(user.getCreateDate(), LocalDateTime.now())`) untouched: analytics day band, not a money path.

11c. Line 136: replace `            LocalDate today = LocalDate.now();` with
```java
            LocalDate today = EasternTime.today();
```

11d. Line 133: replace `            LocalDate effectiveStartDate = startDate != null ? startDate : LocalDate.now();` with
```java
            LocalDate effectiveStartDate = startDate != null ? startDate : EasternTime.today();
```

11e. Line 81: replace `                LocalDate today = LocalDate.now();` with
```java
                LocalDate today = EasternTime.today();
```

11f. Line 77: replace `                if (schedule.getStartDate() == null || LocalDate.now().isBefore(schedule.getStartDate())) {` with
```java
                if (schedule.getStartDate() == null || EasternTime.today().isBefore(schedule.getStartDate())) {
```

11g. Add after line 7 (`import com.investingapp.backend.util.AnalyticsBands;`):
```java
import com.investingapp.backend.util.EasternTime;
```

- [ ] **Step 12: InvestmentExecutionService: processScheduledInvestments(todayET) threads the day through**

In `backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java`, bottom up. Leave line 645 (`AnalyticsBands.daysBetween(firstScheduleCreatedAt(user), LocalDateTime.now())`) and its comment at 641-644 exactly as they are. Every other `LocalDate.now(MARKET_TIMEZONE)` / `LocalDateTime.now(MARKET_TIMEZONE)` in this file already names the zone and stays.

12a. Replace lines 342-371 (Javadoc + whole `updateScheduleAfterExecution(InvestmentSchedule)`, from `    /**` through the closing `    }`):
```java
    /**
     * Update schedule's next investment date after successful execution, relative to todayET
     */
    private void updateScheduleAfterExecution(InvestmentSchedule schedule, LocalDate todayET) {
        LocalDate currentNextDate = schedule.getNextInvestmentDate();
        LocalDate newNextDate = schedule.calculateNextInvestmentDate(todayET);

        schedule.setNextInvestmentDate(newNextDate);
        // Note: We don't update startDate - it remains as the original start date

        // Increment monthly streak if we have not already done so this calendar month
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

        // Save the updated schedule
        investmentScheduleRepository.save(schedule);

        logger.info("Updated schedule {} next investment date from {} to {}",
                schedule.getId(), currentNextDate, newNextDate);
    }
```

12b. Line 333 (inside `createInvestmentExecution`): replace `        execution.setScheduledDate(LocalDateTime.now(MARKET_TIMEZONE)); // Use LocalDateTime` with
```java
        execution.setScheduledDate(EasternTime.now());
```

12c. Replace lines 259-324 (Javadoc + whole `processScheduledInvestments()`, from `    /**` through the closing `    }`):
```java
    /**
     * Process all scheduled investments for the given Eastern calendar day.
     *
     * <p>{@code todayET} is computed once by InvestmentScheduler and passed down so a run that
     * straddles midnight keeps one calendar day (spec 5.8).
     */
    public void processScheduledInvestments(LocalDate todayET) {
        logger.info("Starting scheduled investment processing for {}", todayET);

        // Get all investment schedules that are ready for execution
        // This includes schedules where either:
        // 1. nextInvestmentDate is today or past due (recurring investments)
        // 2. startDate is today (first-time investments starting today)
        List<InvestmentSchedule> readySchedules = investmentScheduleService.getSchedulesReadyForInvestment(todayET);

        logger.info("Found {} investment schedules ready for execution", readySchedules.size());

        for (InvestmentSchedule schedule : readySchedules) {
            try {
                User user = schedule.getUser();

                // Check if this schedule should be processed today
                boolean shouldProcessToday = false;
                String reason = "";

                if (schedule.getStartDate() != null && schedule.getStartDate().equals(todayET)) {
                    shouldProcessToday = true;
                    reason = "start date is today";
                } else if (schedule.getNextInvestmentDate() != null &&
                        !schedule.getNextInvestmentDate().isAfter(todayET)) {
                    shouldProcessToday = true;
                    reason = "next investment date is due";
                }

                if (!shouldProcessToday) {
                    logger.debug("Skipping schedule {} for user {} - not due today",
                            schedule.getId(), user.getEmail());
                    continue;
                }

                logger.info("Queueing recurring investment schedule {} for user {} - {}",
                        schedule.getId(), user.getEmail(), reason);

                // Create new investment execution
                InvestmentExecution execution = createInvestmentExecution(schedule);

                // Queue for end-of-day batch processing
                // This ensures recurring + lump sum investments are batched together
                execution.setStatus(InvestmentExecution.ExecutionStatus.SCHEDULED);
                execution.setErrorMessage("Queued for end-of-day batch processing (recurring investment)");
                executionRepository.save(execution);

                logger.info("Recurring investment {} queued for end-of-day processing", execution.getId());

                // Update the schedule's next investment date for next cycle
                updateScheduleAfterExecution(schedule, todayET);

            } catch (Exception e) {
                logger.error("Error processing investment schedule {} for user {}",
                        schedule.getId(), schedule.getUser().getEmail(), e);

                // Could add notification logic here for schedule processing failures
            }
        }

        logger.info("Completed scheduled investment processing");
    }
```

12d. Line 31: replace `    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");` with
```java
    private static final ZoneId MARKET_TIMEZONE = EasternTime.ZONE;
```

12e. Add after line 5 (`import com.investingapp.backend.util.AnalyticsBands;`):
```java
import com.investingapp.backend.util.EasternTime;
```

- [ ] **Step 13: InvestmentScheduler: the 11:55 job computes todayET once (compile fix for the new signature)**

In `backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java`, bottom up. The commented-out test block at lines 113-121 stays as it is (it is a comment).

13a. Replace lines 34-43 (the `@Scheduled` annotation through the closing brace of `processScheduledInvestments`; the Javadoc at 28-33 stays):
```java
    @Scheduled(cron = "0 55 23 * * *", zone = "America/New_York")
    public void processScheduledInvestments() {
        LocalDate todayET = EasternTime.today();
        logger.info("Starting scheduled investment processing job for {}", todayET);
        try {
            investmentExecutionService.processScheduledInvestments(todayET);
            logger.info("Completed scheduled investment processing job successfully");
        } catch (Exception e) {
            logger.error("Error in scheduled investment processing job", e);
        }
    }
```

13b. Add after line 5 (`import com.investingapp.backend.service.PaydayNotificationService;`):
```java
import com.investingapp.backend.util.EasternTime;
```
The existing `import java.time.LocalDate;` at line 12 stays; it is still used.

- [ ] **Step 14: Run the two service tests; both pass**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentScheduleServiceReadyDateTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceTodayEtTest'
```
Expected: `BUILD SUCCESSFUL`, 4 tests PASSED (`onlySchedulesDueOnThePassedDayAreReady`, `theSameScheduleIsNotReadyTheDayBefore`, `asksForSchedulesDueOnThePassedDay`, `aScheduleDueOnThePassedDayIsQueuedAgainstThatDay`).

- [ ] **Step 15: Write the failing scheduler test (notification jobs still read the JVM default)**

Create `backend/src/test/java/com/investingapp/backend/scheduler/InvestmentSchedulerTest.java` (shape of `TlhSchedulerTest.java:15-66`; `InvestmentScheduler` is field-injected with the implicit no-arg constructor, so the mocks go in with `ReflectionTestUtils` under the field names at `InvestmentScheduler.java:19-26`):

```java
package com.investingapp.backend.scheduler;

import com.investingapp.backend.service.DripService;
import com.investingapp.backend.service.InvestmentExecutionService;
import com.investingapp.backend.service.PaydayNotificationService;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The scheduler computes todayET once per job and hands it down (spec 5.8). The JVM default is
 * moved to a zone whose calendar day differs from Eastern right now, so a bare LocalDate.now()
 * anywhere on the path is caught.
 */
class InvestmentSchedulerTest {

    private InvestmentExecutionService investmentExecutionService;
    private DripService dripService;
    private PaydayNotificationService paydayNotificationService;
    private InvestmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        investmentExecutionService = mock(InvestmentExecutionService.class);
        dripService = mock(DripService.class);
        paydayNotificationService = mock(PaydayNotificationService.class);
        scheduler = new InvestmentScheduler();
        ReflectionTestUtils.setField(scheduler, "investmentExecutionService", investmentExecutionService);
        ReflectionTestUtils.setField(scheduler, "dripService", dripService);
        ReflectionTestUtils.setField(scheduler, "paydayNotificationService", paydayNotificationService);
    }

    private static Scheduled scheduledOf(String method) throws Exception {
        Method m = InvestmentScheduler.class.getMethod(method);
        Scheduled annotation = m.getAnnotation(Scheduled.class);
        assertNotNull(annotation, method + " must be @Scheduled");
        return annotation;
    }

    /** A JVM default whose calendar day differs from Eastern at this moment. */
    private static TimeZone aZoneWhoseDayDiffersFromEastern() {
        int easternHour = LocalTime.now(EasternTime.ZONE).getHour();
        return TimeZone.getTimeZone(easternHour < 6 ? ZoneId.of("-12:00") : ZoneId.of("+14:00"));
    }

    @Test
    void moneyAndNotificationCronsRunOnTheEasternClock() throws Exception {
        assertEquals("0 55 23 * * *", scheduledOf("processScheduledInvestments").cron());
        assertEquals("0 0 7 * * *", scheduledOf("sendDayBeforePaydayNotifications").cron());
        assertEquals("0 0 0 * * *", scheduledOf("sendPaydayNotifications").cron());
        assertEquals("0 0 8 * * *", scheduledOf("sendDayAfterPaydayNotifications").cron());
        for (String job : new String[] {"processScheduledInvestments", "sendDayBeforePaydayNotifications",
                "sendPaydayNotifications", "sendDayAfterPaydayNotifications"}) {
            assertEquals("America/New_York", scheduledOf(job).zone(), job);
        }
    }

    @Test
    void everyMoneyAndNotificationJobPassesTheEasternDay() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(aZoneWhoseDayDiffersFromEastern());
            LocalDate todayET = EasternTime.today();
            assertNotEquals(todayET, LocalDate.now(),
                    "precondition: the JVM default day must differ from Eastern for this test to bite");

            scheduler.processScheduledInvestments();
            scheduler.sendDayBeforePaydayNotifications();
            scheduler.sendPaydayNotifications();
            scheduler.sendDayAfterPaydayNotifications();

            verify(investmentExecutionService).processScheduledInvestments(todayET);
            verify(paydayNotificationService).sendDayBeforeNotifications(todayET);
            verify(paydayNotificationService).sendPaydayNotifications(todayET.minusDays(1));
            verify(paydayNotificationService).sendDayAfterNotifications(todayET);
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void jobsDelegateAndNeverRethrow() {
        doThrow(new IllegalStateException("boom")).when(investmentExecutionService).processScheduledInvestments(any());
        doThrow(new IllegalStateException("boom")).when(paydayNotificationService).sendDayBeforeNotifications(any());
        doThrow(new IllegalStateException("boom")).when(paydayNotificationService).sendPaydayNotifications(any());
        doThrow(new IllegalStateException("boom")).when(paydayNotificationService).sendDayAfterNotifications(any());

        assertDoesNotThrow(scheduler::processScheduledInvestments);
        assertDoesNotThrow(scheduler::sendDayBeforePaydayNotifications);
        assertDoesNotThrow(scheduler::sendPaydayNotifications);
        assertDoesNotThrow(scheduler::sendDayAfterPaydayNotifications);

        verify(investmentExecutionService).processScheduledInvestments(any());
        verify(paydayNotificationService).sendDayBeforeNotifications(any());
        verify(paydayNotificationService).sendPaydayNotifications(any());
        verify(paydayNotificationService).sendDayAfterNotifications(any());
    }
}
```

- [ ] **Step 16: Run it; the notification assertion fails**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.scheduler.InvestmentSchedulerTest'
```
Expected: `moneyAndNotificationCronsRunOnTheEasternClock` and `jobsDelegateAndNeverRethrow` PASSED; `everyMoneyAndNotificationJobPassesTheEasternDay` FAILED. The first verify (`processScheduledInvestments(todayET)`) passes because Step 13 already landed; the second fails with `Argument(s) are different! Wanted: paydayNotificationService.sendDayBeforeNotifications(<todayET>) ... Actual invocations have different arguments: ... sendDayBeforeNotifications(<the JVM default day>)`.

- [ ] **Step 17: InvestmentScheduler: the three notification jobs compute todayET once**

In `backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java` replace the three methods at HEAD lines 123-151 (now lines 125-153: Step 13 added one import line and one `todayET` line above them; the block starts at `    @Scheduled(cron = "0 0 7 * * *", zone = "America/New_York")` and ends at the closing brace of `sendDayAfterPaydayNotifications`, the line before the class's final `}`) with:

```java
    @Scheduled(cron = "0 0 7 * * *", zone = "America/New_York")
    public void sendDayBeforePaydayNotifications() {
        LocalDate todayET = EasternTime.today();
        logger.info("Sending D-1 payday notifications for {}", todayET);
        try {
            paydayNotificationService.sendDayBeforeNotifications(todayET);
        } catch (Exception e) {
            logger.error("Error sending D-1 payday notifications", e);
        }
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "America/New_York")
    public void sendPaydayNotifications() {
        LocalDate todayET = EasternTime.today();
        logger.info("Sending D+0 payday notifications for {}", todayET.minusDays(1));
        try {
            paydayNotificationService.sendPaydayNotifications(todayET.minusDays(1));
        } catch (Exception e) {
            logger.error("Error sending D+0 payday notifications", e);
        }
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "America/New_York")
    public void sendDayAfterPaydayNotifications() {
        LocalDate todayET = EasternTime.today();
        logger.info("Sending D+1 payday notifications for {}", todayET);
        try {
            paydayNotificationService.sendDayAfterNotifications(todayET);
        } catch (Exception e) {
            logger.error("Error sending D+1 payday notifications", e);
        }
    }
```

- [ ] **Step 18: Run the scheduler test; all three pass**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.scheduler.InvestmentSchedulerTest'
```
Expected: `BUILD SUCCESSFUL`, 3 tests PASSED.

- [ ] **Step 19: InvestmentController: the lump-sum execution timestamp and the history window name the zone**

In `backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java`, bottom up. These are the only two bare clocks in the file (grep confirms :61 and :270).

19a. Lines 268-274: replace
```java
            InvestmentExecution execution = new InvestmentExecution(
                    user,
                    LocalDateTime.now(),
                    amount,
                    investmentType,
                    selectedSymbol,
                    fundingSource);
```
with
```java
            InvestmentExecution execution = new InvestmentExecution(
                    user,
                    EasternTime.now(),
                    amount,
                    investmentType,
                    selectedSymbol,
                    fundingSource);
```

19b. Line 61: replace `            LocalDateTime since = LocalDateTime.now().minusMonths(6);` with
```java
            LocalDateTime since = EasternTime.now().minusMonths(6);
```

19c. Add after line 10 (`import com.investingapp.backend.service.InvestmentExecutionService;`):
```java
import com.investingapp.backend.util.EasternTime;
```

Compile gate:
```bash
cd backend && ./gradlew compileJava compileTestJava
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 20: Regression run and the bare-clock sweep**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.util.EasternTimeTest' --tests 'com.investingapp.backend.scheduler.InvestmentSchedulerTest' --tests 'com.investingapp.backend.scheduler.TlhSchedulerTest' --tests 'com.investingapp.backend.service.InvestmentScheduleServiceReadyDateTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceTodayEtTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceAchAnalyticsTest' --tests 'com.investingapp.backend.service.InvestmentExecutionServiceTlhGuardTest' --tests 'com.investingapp.backend.service.InvestmentScheduleServiceAnalyticsTest'
```
Expected: `BUILD SUCCESSFUL`, every test PASSED.

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED && grep -n "LocalDate\.now()\|LocalDateTime\.now()" backend/src/main/java/com/investingapp/backend/BackendApplication.java backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java backend/src/main/java/com/investingapp/backend/util/EasternTime.java
```
Expected: exactly three hits, all analytics day-banding: `InvestmentScheduleService.java` (`AnalyticsBands.daysBetween(user.getCreateDate(), LocalDateTime.now())`), `InvestmentExecutionService.java` the comment line `// LocalDateTime.now() and not MARKET_TIMEZONE on purpose` and the `AnalyticsBands.daysBetween(firstScheduleCreatedAt(user), LocalDateTime.now())` call. Any other hit is a miss: fix it before committing.

- [ ] **Step 21: Hunk-verify, stage only T02 files, commit**

The checkout is shared and dirty. Never run `git checkout`, `git restore`, `git stash` or `git reset`. Inspect every hunk first; each one must be an edit from Steps 5, 10, 11, 12, 13, 17 or 19. If a hunk you did not write appears, stage that file with `git add -p` and skip the foreign hunk.

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
git diff HEAD -- backend/src/main/java/com/investingapp/backend/BackendApplication.java backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java
git add backend/src/main/java/com/investingapp/backend/BackendApplication.java backend/src/main/java/com/investingapp/backend/util/EasternTime.java backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java backend/src/main/java/com/investingapp/backend/service/InvestmentExecutionService.java backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/controller/InvestmentController.java backend/src/test/java/com/investingapp/backend/util/EasternTimeTest.java backend/src/test/java/com/investingapp/backend/scheduler/InvestmentSchedulerTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceReadyDateTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentExecutionServiceTodayEtTest.java
git status --short | grep '^[MA]'
git commit -m "fix(time): pin the server and every money cron to America/New_York"
```
Expected: `git status --short | grep '^[MA]'` lists exactly the 11 files above and nothing else staged; the commit lands with 6 modified files (BackendApplication, InvestmentScheduler, InvestmentExecutionService, InvestmentScheduleService, InvestmentSchedule, InvestmentController) and 5 new files (EasternTime plus the four tests).

Deploy note for the executing session (spec 5.8): this commit changes the clock basis of `investment_schedules.created_at`/`updated_at` and of lump-sum `scheduled_date` from UTC to Eastern. Roll it out outside the 23:45 to 00:10 ET freeze and check the first night's 11:59 batch selection by hand; rows written before the switch hold UTC values.