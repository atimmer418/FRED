### Task 13: d1-copy-fix

**Goal:** The MANUAL D-1 notice stops claiming "Your paycheck hits tomorrow" (the typed date proves nothing about a paycheck) and says what FRED will actually do: "FRED will invest $X tomorrow. Your Freedom Date moves closer." The D-1 query selects only MANUAL schedules so Phase 2's DETECTED rows never fall into this sender, and the sender skips the three holds that stop the 11:55 PM cron from pulling (spec 5.2 MANUAL clause), so a D-1 never promises a transfer a hold will block. The Eastern date keeps arriving from the scheduler; this service never reads the clock.

**Depends on:** T04 (`ScheduleMode`, `HoldReason`, `InvestmentSchedule.mode` and `InvestmentSchedule.holdReason` must exist). Coordinates with T02 (InvestmentScheduler passes `todayET`); T13 does not edit the scheduler.

**Spec:** section 10 table row `PAYDAY_D1 (MANUAL); date; no blocking hold` (FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md:572), section 5.8 bullets 2 and 3 (:302-303), section 5.2 MANUAL clause (:236-238), section 10 first paragraph "A test rejects em dashes in these strings" (:568).

**Files:**
- Modify: `backend/src/main/java/com/investingapp/backend/service/PaydayNotificationService.java`
  - :3-14 imports (add `HoldReason`, `EnumSet`, `Set`)
  - after :19 (`logger`) insert the four copy constants and `BLOCKING_HOLDS`
  - :30-44 `sendDayBeforeNotifications(LocalDate today)` body (new copy, hold skip, parameter renamed `todayET`)
  - :83 push log line carries an em dash; replaced in passing (house rule, file is touched)
- Modify: `backend/src/main/java/com/investingapp/backend/repository/InvestmentScheduleRepository.java:61-66` (`findActiveByNextInvestmentDate` JPQL gains `AND s.mode = com.investingapp.backend.model.ScheduleMode.MANUAL`; name and signature kept)
- Test (create): `backend/src/test/java/com/investingapp/backend/service/PaydayNotificationServiceTest.java`
- Test (existing, ci gate for the JPQL): `backend/src/test/java/com/investingapp/backend/BackendApplicationTests.java` (not edited; run against a scratch schema)
- NOT edited: `backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java:123-131` (T02 replaces `LocalDate.now()` at :127 with `todayET`)

**Interfaces:**
- Consumes:
  - T04: `com.investingapp.backend.model.ScheduleMode { MANUAL, DETECTED }`; `com.investingapp.backend.model.HoldReason { AWAITING_AUTHORIZATION, BANK_CHANGE, ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED, ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, DORMANT, KILL_SWITCH, MANUAL_REVIEW }`; `InvestmentSchedule` (Lombok `@Data`, InvestmentSchedule.java:11) fields `ScheduleMode mode = ScheduleMode.MANUAL` and `HoldReason holdReason`, so `getMode()`, `getHoldReason()`, `setHoldReason(HoldReason)` exist.
  - T02: `InvestmentScheduler.sendDayBeforePaydayNotifications()` calls `paydayNotificationService.sendDayBeforeNotifications(todayET)` with `todayET = EasternTime.today()` (today InvestmentScheduler.java:127 still passes `LocalDate.now()`; T02 owns that line).
  - Existing: `EmailService.sendEmail(String to, String subject, String text)` (EmailService.java:43-45; the only other overload takes 4 arguments, so Mockito matchers resolve without ambiguity); `User.getEmail()` (Lombok `@Getter`, User.java:27); `User.getDevicePushToken()` (hand-written getter, User.java:288); `new User(String email)` (User.java:349); `InvestmentScheduleRepository.findActiveByNextInvestmentDate(LocalDate)` (InvestmentScheduleRepository.java:65-66, sole caller PaydayNotificationService.java:32); `InvestmentSchedule.setIsPaused(Boolean)` (Lombok on the boxed `Boolean isPaused` field, InvestmentSchedule.java:41-42).
- Produces:
  - `InvestmentScheduleRepository`: `@Query("SELECT s FROM InvestmentSchedule s WHERE s.nextInvestmentDate = :date AND s.isPaused = false AND s.mode = com.investingapp.backend.model.ScheduleMode.MANUAL") List<InvestmentSchedule> findActiveByNextInvestmentDate(@Param("date") LocalDate date)` (name kept).
  - `PaydayNotificationService` package-private constants: `static final String D1_MANUAL_SUBJECT = "Your investment hits tomorrow"; static final String D1_MANUAL_BODY = "FRED will invest %s tomorrow. Your Freedom Date moves closer."; static final String D1_MANUAL_PUSH_TITLE = "Investment tomorrow"; static final String D1_MANUAL_PUSH_BODY = "FRED will invest %s tomorrow."; static final Set<HoldReason> BLOCKING_HOLDS = EnumSet.of(ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, MANUAL_REVIEW)`; amount formatted `String.format("$%.0f", schedule.getInvestmentAmount())` as today.
  - `public void sendDayBeforeNotifications(LocalDate todayET)` signature unchanged (parameter renamed only).

---

- [ ] **Step 1: Confirm the T04 types exist and the scheduler state (read only, 1 minute)**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
ls backend/src/main/java/com/investingapp/backend/model/ScheduleMode.java backend/src/main/java/com/investingapp/backend/model/HoldReason.java
grep -n "holdReason\|ScheduleMode mode" backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java
grep -n "sendDayBeforeNotifications" backend/src/main/java/com/investingapp/backend/scheduler/InvestmentScheduler.java
git status --short -- backend/src/main/java/com/investingapp/backend/service/PaydayNotificationService.java backend/src/main/java/com/investingapp/backend/repository/InvestmentScheduleRepository.java
```

Expected: both enum files listed; at least two grep hits in InvestmentSchedule.java, including `private ScheduleMode mode = ScheduleMode.MANUAL;` and `private HoldReason holdReason;` (more hits appear if T10 has already landed its hold clause in `isReadyForInvestment`; that is fine). The scheduler grep prints one line, either `paydayNotificationService.sendDayBeforeNotifications(todayET);` (T02 landed) or `paydayNotificationService.sendDayBeforeNotifications(LocalDate.now());` (T02 not yet landed). In both cases do not edit InvestmentScheduler.java in this task. The `git status` line prints nothing (both files clean at HEAD 9a09482 when this plan was written); if it prints `M`, run `git diff HEAD -- <file>` and keep those foreign hunks out of this task's commit (stage with `git add -p` if needed).

If the two enum files are missing, stop: T04 has not landed and the JPQL literal in Step 9 cannot compile.

- [ ] **Step 2: Write the failing tests for the MANUAL D-1 copy, the date pass-through and the hold skip (5 minutes)**

Create `backend/src/test/java/com/investingapp/backend/service/PaydayNotificationServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Scope: the MANUAL D-1 notice only (spec section 10, PAYDAY_D1 MANUAL row).
 *
 * <p>Pins the copy, the Eastern date pass-through (the service never reads the clock), and the
 * blocking-hold skip. The JPQL mode filter in InvestmentScheduleRepository is not provable here;
 * BackendApplicationTests under the ci profile parses it at context start.
 */
class PaydayNotificationServiceTest {

    private static final LocalDate TODAY_ET = LocalDate.of(2026, 11, 5);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 11, 6);

    private PaydayNotificationService service;
    private EmailService emailService;
    private InvestmentScheduleRepository scheduleRepository;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PaydayNotificationService();
        emailService = mock(EmailService.class);
        scheduleRepository = mock(InvestmentScheduleRepository.class);

        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "investmentScheduleRepository", scheduleRepository);
        ReflectionTestUtils.setField(service, "investmentExecutionRepository",
                mock(InvestmentExecutionRepository.class));

        user = new User("payday@fredvested.com");
        user.setId(42L);
    }

    // --- fixtures ----------------------------------------------------------------------------

    private InvestmentSchedule manualScheduleDueTomorrow(String amount) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(7L);
        schedule.setUser(user);
        schedule.setNextInvestmentDate(TOMORROW);
        schedule.setInvestmentAmount(new BigDecimal(amount));
        schedule.setIsPaused(false);
        return schedule;
    }

    // --- copy --------------------------------------------------------------------------------

    @Test
    void manualD1EmailSaysFredWillInvestTomorrow() {
        when(scheduleRepository.findActiveByNextInvestmentDate(TOMORROW))
                .thenReturn(List.of(manualScheduleDueTomorrow("300.00")));

        service.sendDayBeforeNotifications(TODAY_ET);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("payday@fredvested.com"), subject.capture(), body.capture());
        assertThat(subject.getValue()).isEqualTo("Your investment hits tomorrow");
        assertThat(body.getValue())
                .isEqualTo("FRED will invest $300 tomorrow. Your Freedom Date moves closer.");
    }

    // --- Eastern date pass-through -----------------------------------------------------------

    @Test
    void queriesTheDayAfterTheEasternDateItWasGiven() {
        // A date far from the real today: if the service read the clock, this would not match.
        LocalDate givenTodayET = LocalDate.of(2031, 2, 27);
        when(scheduleRepository.findActiveByNextInvestmentDate(any())).thenReturn(List.of());

        service.sendDayBeforeNotifications(givenTodayET);

        verify(scheduleRepository).findActiveByNextInvestmentDate(LocalDate.of(2031, 2, 28));
        verifyNoInteractions(emailService);
    }

    // --- blocking holds (spec 5.2 MANUAL clause; section 10 "no blocking hold") ---------------

    @Test
    void manualReviewHoldSendsNoD1Notice() {
        InvestmentSchedule held = manualScheduleDueTomorrow("300.00");
        held.setHoldReason(HoldReason.MANUAL_REVIEW);
        when(scheduleRepository.findActiveByNextInvestmentDate(TOMORROW)).thenReturn(List.of(held));

        service.sendDayBeforeNotifications(TODAY_ET);

        verifyNoInteractions(emailService);
    }

    @Test
    void achReturnHoldsSendNoD1Notice() {
        InvestmentSchedule unauthorized = manualScheduleDueTomorrow("300.00");
        unauthorized.setHoldReason(HoldReason.ACH_RETURN_UNAUTHORIZED);
        InvestmentSchedule account = manualScheduleDueTomorrow("300.00");
        account.setHoldReason(HoldReason.ACH_RETURN_ACCOUNT);
        when(scheduleRepository.findActiveByNextInvestmentDate(TOMORROW))
                .thenReturn(List.of(unauthorized, account));

        service.sendDayBeforeNotifications(TODAY_ET);

        verifyNoInteractions(emailService);
    }

    @Test
    void bankChangeHoldStillGetsTheD1Notice() {
        // Informational hold: the 15-minute ACH poll clears it before the 11:55 PM pull.
        InvestmentSchedule held = manualScheduleDueTomorrow("300.00");
        held.setHoldReason(HoldReason.BANK_CHANGE);
        when(scheduleRepository.findActiveByNextInvestmentDate(TOMORROW)).thenReturn(List.of(held));

        service.sendDayBeforeNotifications(TODAY_ET);

        verify(emailService).sendEmail(eq("payday@fredvested.com"),
                eq("Your investment hits tomorrow"),
                eq("FRED will invest $300 tomorrow. Your Freedom Date moves closer."));
    }
}
```

- [ ] **Step 3: Run the tests and watch four of them fail (2 minutes)**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew test --tests 'com.investingapp.backend.service.PaydayNotificationServiceTest'
```

Expected: `BUILD FAILED`. build.gradle has no `testLogging` block, so Gradle prints only the exception class and line for each failure, in this shape:

```
PaydayNotificationServiceTest > manualD1EmailSaysFredWillInvestTomorrow() FAILED
    org.opentest4j.AssertionFailedError at PaydayNotificationServiceTest.java:NN

PaydayNotificationServiceTest > manualReviewHoldSendsNoD1Notice() FAILED
    org.mockito.exceptions.verification.NoInteractionsWanted at PaydayNotificationServiceTest.java:NN

PaydayNotificationServiceTest > achReturnHoldsSendNoD1Notice() FAILED
    org.mockito.exceptions.verification.NoInteractionsWanted at PaydayNotificationServiceTest.java:NN

PaydayNotificationServiceTest > bankChangeHoldStillGetsTheD1Notice() FAILED
    org.mockito.exceptions.verification.opentest4j.ArgumentsAreDifferent at PaydayNotificationServiceTest.java:NN

5 tests completed, 4 failed
```

`queriesTheDayAfterTheEasternDateItWasGiven` passes already (the current body computes `today.plusDays(1)`); it stays as the pin that no `LocalDate.now()` creeps in. To see the copy assertion's expected/actual text, read it from the XML report:

```bash
grep -c "Your paycheck hits tomorrow" /Users/andrewtimmer/PersonalTypeshit/FRED/backend/build/test-results/test/TEST-com.investingapp.backend.service.PaydayNotificationServiceTest.xml
```

Expected: a number of at least 1 (the old body is the "but was" value of the first failure).

- [ ] **Step 4: Add the copy constants, the hold set, and rewrite the D-1 body (4 minutes)**

In `backend/src/main/java/com/investingapp/backend/service/PaydayNotificationService.java`, replace the import block at :3-14:

```java
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
```

Then replace the logger line at :19 plus the blank line after it with the logger, the constants, and the hold set:

```java
    private static final Logger logger = LoggerFactory.getLogger(PaydayNotificationService.class);

    /** MANUAL D-1 copy (spec section 10, PAYDAY_D1 MANUAL row). Push is the first sentence. */
    static final String D1_MANUAL_SUBJECT = "Your investment hits tomorrow";
    static final String D1_MANUAL_BODY = "FRED will invest %s tomorrow. Your Freedom Date moves closer.";
    static final String D1_MANUAL_PUSH_TITLE = "Investment tomorrow";
    static final String D1_MANUAL_PUSH_BODY = "FRED will invest %s tomorrow.";

    /**
     * Holds that stop the 11:55 PM cron from pulling a MANUAL schedule (spec 5.2 MANUAL clause).
     * A D-1 notice must not promise a transfer one of these holds will block. Informational holds
     * (BANK_CHANGE and the rest) still get the notice: they clear on their own before the pull.
     */
    static final Set<HoldReason> BLOCKING_HOLDS = EnumSet.of(
            HoldReason.ACH_RETURN_UNAUTHORIZED,
            HoldReason.ACH_RETURN_ACCOUNT,
            HoldReason.MANUAL_REVIEW);

```

Then replace the whole method at :30-44 (`public void sendDayBeforeNotifications(LocalDate today) { ... }`) with:

```java
    /**
     * D-1 notice for MANUAL schedules due tomorrow. {@code todayET} is the America/New_York date
     * computed once by InvestmentScheduler and passed down; this method never reads the clock.
     * The repository query already restricts to MANUAL, not paused rows.
     */
    public void sendDayBeforeNotifications(LocalDate todayET) {
        LocalDate tomorrow = todayET.plusDays(1);
        List<InvestmentSchedule> schedules = investmentScheduleRepository.findActiveByNextInvestmentDate(tomorrow);
        for (InvestmentSchedule schedule : schedules) {
            if (schedule.getNextInvestmentDate() == null) continue;
            HoldReason hold = schedule.getHoldReason();
            if (hold != null && BLOCKING_HOLDS.contains(hold)) {
                logger.info("Skipping D-1 notice for schedule {}: blocking hold {}", schedule.getId(), hold);
                continue;
            }
            User user = schedule.getUser();
            if (user == null) continue;
            String amount = String.format("$%.0f", schedule.getInvestmentAmount());
            emailService.sendEmail(user.getEmail(), D1_MANUAL_SUBJECT, String.format(D1_MANUAL_BODY, amount));
            sendPushIfTokenPresent(user, D1_MANUAL_PUSH_TITLE, String.format(D1_MANUAL_PUSH_BODY, amount));
        }
    }
```

Leave `sendPaydayNotifications` (:46-61) and `sendDayAfterNotifications` (:63-76) untouched.

- [ ] **Step 5: Remove the em dash from the push log line (1 minute)**

Still in `PaydayNotificationService.java`, inside `sendPushIfTokenPresent` (the line that was :83 before the edits above; it is the only line in the file containing an em dash, and it starts `logger.info("[PUSH] Would send to token {}: {} `). Find it:

```bash
grep -n $'\xe2\x80\x94' /Users/andrewtimmer/PersonalTypeshit/FRED/backend/src/main/java/com/investingapp/backend/service/PaydayNotificationService.java
```

Expected: exactly one line, the `[PUSH] Would send to token` log statement. Replace that whole statement with:

```java
        logger.info("[PUSH] Would send to token {} with title '{}' and body '{}'",
                token.substring(0, Math.min(8, token.length())), title, body);
```

Then confirm the file is clean of em dashes:

```bash
grep -c $'\xe2\x80\x94' /Users/andrewtimmer/PersonalTypeshit/FRED/backend/src/main/java/com/investingapp/backend/service/PaydayNotificationService.java
```

Expected: `0`.

- [ ] **Step 6: Run the test class and watch all five pass (2 minutes)**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew test --tests 'com.investingapp.backend.service.PaydayNotificationServiceTest' && grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.PaydayNotificationServiceTest.xml
```

Expected: `BUILD SUCCESSFUL` and `tests="5" skipped="0" failures="0" errors="0"`.

If instead `manualReviewHoldSendsNoD1Notice() FAILED` with `org.mockito.exceptions.verification.NoInteractionsWanted`, the `hold != null && BLOCKING_HOLDS.contains(hold)` guard from Step 4 is missing from the loop; re-apply the Step 4 method body exactly and rerun.

- [ ] **Step 7: Pin the constants: push copy, hold set, and the no-em-dash rule (3 minutes)**

This test references the package-private constants, so it can only compile after Step 4; it is a pin, not a red-first test. In `backend/src/test/java/com/investingapp/backend/service/PaydayNotificationServiceTest.java`, append this method inside the class, after `bankChangeHoldStillGetsTheD1Notice` and before the final closing brace:

```java
    // --- constants: push copy and the no-em-dash rule (spec section 10) ----------------------

    @Test
    void pushCopyIsTheFirstSentenceAndNoD1StringCarriesAnEmDash() {
        assertThat(String.format(PaydayNotificationService.D1_MANUAL_PUSH_BODY, "$300"))
                .isEqualTo("FRED will invest $300 tomorrow.");
        assertThat(PaydayNotificationService.D1_MANUAL_PUSH_TITLE).isEqualTo("Investment tomorrow");
        assertThat(PaydayNotificationService.D1_MANUAL_SUBJECT).isEqualTo("Your investment hits tomorrow");
        assertThat(PaydayNotificationService.BLOCKING_HOLDS).containsExactlyInAnyOrder(
                HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);
        // U+2014 is the em dash; the escape keeps the character itself out of the source tree.
        for (String copy : List.of(
                PaydayNotificationService.D1_MANUAL_SUBJECT,
                PaydayNotificationService.D1_MANUAL_BODY,
                PaydayNotificationService.D1_MANUAL_PUSH_TITLE,
                PaydayNotificationService.D1_MANUAL_PUSH_BODY)) {
            assertThat(copy).doesNotContain("—");
        }
    }
```

- [ ] **Step 8: Run the full test class (2 minutes)**

Run:

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew test --tests 'com.investingapp.backend.service.PaydayNotificationServiceTest' && grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.PaydayNotificationServiceTest.xml
```

Expected: `BUILD SUCCESSFUL` and `tests="6" skipped="0" failures="0" errors="0"`.

- [ ] **Step 9: Restrict the D-1 query to MANUAL schedules (2 minutes)**

In `backend/src/main/java/com/investingapp/backend/repository/InvestmentScheduleRepository.java`, replace :61-66:

```java
    /**
     * Find active (non-paused) schedules whose next investment date matches the given date.
     * Used for D-1 payday notifications.
     */
    @Query("SELECT s FROM InvestmentSchedule s WHERE s.nextInvestmentDate = :date AND s.isPaused = false")
    List<InvestmentSchedule> findActiveByNextInvestmentDate(@Param("date") java.time.LocalDate date);
```

with:

```java
    /**
     * MANUAL schedules, not paused, whose next investment date matches the given date.
     * Used for the MANUAL D-1 payday notice (spec 5.8, 10). DETECTED schedules are never selected
     * here; Phase 2 adds findDetectedByNextExpectedDate for them.
     */
    @Query("SELECT s FROM InvestmentSchedule s WHERE s.nextInvestmentDate = :date AND s.isPaused = false"
            + " AND s.mode = com.investingapp.backend.model.ScheduleMode.MANUAL")
    List<InvestmentSchedule> findActiveByNextInvestmentDate(@Param("date") java.time.LocalDate date);
```

The enum literal is fully qualified on purpose: Hibernate 6 HQL resolves `com.investingapp.backend.model.ScheduleMode.MANUAL` without an import. Compile check (no database needed):

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED/backend && ./gradlew compileJava compileTestJava
```

Expected: `BUILD SUCCESSFUL`. Note `compileJava` proves only Java syntax; the JPQL string is parsed by Hibernate when Spring Data builds the repository at context start (next step).

- [ ] **Step 10: Prove the JPQL parses: ci context test against a scratch schema (5 minutes)**

`BackendApplicationTests` (`@SpringBootTest @ActiveProfiles("ci") @RequiresCiDatabase`, BackendApplicationTests.java:20-27) is the only gate that parses `@Query` text; it is SKIPPED, not failed, when no MySQL is reachable (RequiresCiDatabase.java:66-69 prints `ci database not reachable, skipping the context test`). Use a throwaway schema on the local server (never `investing_dev`, the schema at application-local.properties:1). The local root password is the value of `spring.datasource.password` at application-local.properties:3; read it into a shell variable instead of typing it.

Two details make this run deterministic. First, `db/migration` holds seven UNTRACKED `R__v_*.sql` view files from other WIP (`git status --short backend/src/main/resources/db/migration/` lists them as `??`); CI never sees them, so point Flyway at a copy that holds only the versioned `V*.sql` files. Second, Gradle does not treat environment variables as task inputs, so pass `--rerun` (Gradle 8.14) or a repeat run reports a stale, skipped result.

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
PW=$(sed -n 's/^spring.datasource.password=//p' backend/src/main/resources/application-local.properties)
/opt/homebrew/bin/mysql -h 127.0.0.1 -u root -p"$PW" -e "CREATE DATABASE IF NOT EXISTS fred_ci_scratch"
rm -rf backend/build/t13-migrations && mkdir -p backend/build/t13-migrations
cp backend/src/main/resources/db/migration/V*.sql backend/build/t13-migrations/
ls backend/build/t13-migrations/
cd backend && SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_ci_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$PW" SPRING_FLYWAY_LOCATIONS="filesystem:$(pwd)/build/t13-migrations" ./gradlew test --rerun --tests 'com.investingapp.backend.BackendApplicationTests' && grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
```

Expected: the `ls` prints `V1__baseline.sql` through `V6__paycheck_autopilot.sql` and nothing else; then `BUILD SUCCESSFUL` and `tests="1" skipped="0" failures="0" errors="0"` (Flyway applies V1 to V6 on the empty scratch schema, Hibernate validate passes, Spring Data parses every `@Query`). `skipped="1"` means the database was not reached and proves nothing; fix the connection and rerun. A JPQL error shows up in the test report as `BeanCreationException ... Validation failed for query for method public abstract java.util.List com.investingapp.backend.repository.InvestmentScheduleRepository.findActiveByNextInvestmentDate(java.time.LocalDate)`; if you see it, the `mode` field from T04 is missing or the enum literal is misspelled.

Drop the scratch schema and the migration copy afterwards:

```bash
/opt/homebrew/bin/mysql -h 127.0.0.1 -u root -p"$PW" -e "DROP DATABASE fred_ci_scratch"
rm -rf /Users/andrewtimmer/PersonalTypeshit/FRED/backend/build/t13-migrations
```

CI runs the identical test with its mysql:8.0 service and the committed `classpath:db/migration` location (.github/workflows/verify.yml:24-31, :60-66).

- [ ] **Step 11: Final sweep: old copy gone, no em dashes, neighbour tests green (2 minutes)**

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
grep -rn "auto-invest %s\|Your paycheck hits tomorrow" backend/src
grep -n $'\xe2\x80\x94' backend/src/main/java/com/investingapp/backend/service/PaydayNotificationService.java backend/src/main/java/com/investingapp/backend/repository/InvestmentScheduleRepository.java backend/src/test/java/com/investingapp/backend/service/PaydayNotificationServiceTest.java
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaydayNotificationServiceTest' --tests 'com.investingapp.backend.service.InvestmentScheduleServiceAnalyticsTest'
```

Expected: both greps print nothing (the only remaining mentions of the old wording are the historical story text in FREDdocs/.stories/FRED-171.md:8, FREDdocs/backlog.md:755 and the spec's problem statement, which stay as they are); `BUILD SUCCESSFUL`. `InvestmentScheduleServiceAnalyticsTest` is the nearest consumer of the edited repository interface (it mocks `InvestmentScheduleRepository`), so it proves the changed interface still wires into the existing service tests.

- [ ] **Step 12: Commit only the three T13 files (2 minutes)**

Hunk-verify first (shared dirty tree: other sessions edit this checkout):

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
git diff HEAD -- backend/src/main/java/com/investingapp/backend/service/PaydayNotificationService.java backend/src/main/java/com/investingapp/backend/repository/InvestmentScheduleRepository.java
```

Expected: only the hunks from Steps 4, 5 and 9 (imports, constants, `sendDayBeforeNotifications`, the push log line, the `@Query` text and its javadoc). If a foreign hunk appears, stage with `git add -p` and skip it. Then:

```bash
git add backend/src/main/java/com/investingapp/backend/service/PaydayNotificationService.java backend/src/main/java/com/investingapp/backend/repository/InvestmentScheduleRepository.java backend/src/test/java/com/investingapp/backend/service/PaydayNotificationServiceTest.java
git status --short | grep '^[MA] '
git commit -m "fix(notifications): correct the MANUAL D-1 copy and query only MANUAL schedules"
```

Expected: `git status` shows exactly the three paths staged (`M` twice, `A` once); the commit lands with those three files. Do not add InvestmentScheduler.java (T02), FREDdocs/backlog.md, or anything under `db/migration` (the untracked `R__v_*.sql` files belong to other WIP).