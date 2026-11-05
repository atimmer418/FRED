### Task 11: detection-evaluate

`PaycheckDetectionService.evaluate` is the one orchestrator in front of the pure detection rules (spec 4.1 to 4.5, 6.1): it groups a user's posted inflow events, applies the hard gates, scores, upserts `paycheck_streams`, keeps or selects the primary, applies basic succession (R5) and mirrors the result onto the schedule row when one exists (R1), all in one transaction under the user row lock (Decision 4), skipping excluded users (R8). It never writes the schedule's `mode`, `status`, `hold_reason`, amount, dates or ACH fields, never calls Plaid or Alpaca, and moves no money. It also adds the one Phase 1 analytics event, `paycheck_stream_switched`.

Notation: `P` = `backend/src/main/java/com/investingapp/backend`, `T` = `backend/src/test/java/com/investingapp/backend`, `F` = `frontend/src/app`. Line numbers are HEAD 137998c. No earlier Phase 1 task edits `P/util/ServerAnalyticsEvent.java`, `F/services/analytics/analytics-events.ts`, `FREDdocs/analytics-events.md` or `T/service/AnalyticsOutboxForbiddenPayloadTest.java`, so their numbers hold; every edit there also quotes the text to match, because Goals v3 (branch `feature/goals-v3-wt`) edits the same files (R17 merge seam): add lines, never reorder existing ones. `T/PhaseOneMoneyInvarianceTest.java` is created by T03; this task only appends one method to it. Before a class under test exists, the run command fails at `compileTestJava` with `error: cannot find symbol` and `BUILD FAILED`; that is the expected red.

Facts this task depends on, restated so this file stands alone:

- `investment_schedules.is_paused` no longer exists. `InvestmentSchedule.status` is `PENDING_BANK | ACTIVE | PAUSED_BY_USER` (the user and bank lifecycle); `hold_reason` is the system's stop and is orthogonal. Money moves only when `InvestmentSchedule.isReadyForInvestment(todayET)` says so, and that rule lives nowhere else. Phase 1 never writes `status` and never reads the deprecated `getIsPaused()`.
- R1: detection outcomes are mirrored onto the schedule row in every mode (`detection_state`, `primary_stream_id`, `next_expected_date`, `last_cadence_check_at`); `mode` stays MANUAL under shadow. Streams, events and cycles are per user and exist with or without a schedule row.
- R2: Phase 1 detection writes no hold of any kind. DORMANT and AWAITING_AUTHORIZATION are Phase 2; Item-state holds belong to `PlaidItemStateService` (T13).
- R5: succession is spec 6.1 conditions (1) to (3) without the recency exception, stream-side effects only: S1 RETIRED `SUCCEEDED`, S2 PRIMARY and PROVISIONAL, `primary_stream_id`, the `paycheck_stream_switched` event. No amount rule, no revocation, no notice.
- R8: `evaluate` skips users whose `account_status` is REJECTED, who opted out, who hold no Plaid token, or whose Item is not OK or PENDING_DISCONNECT; T03's `DetectionEligibility.exclusion` is the single statement of that rule, and DISABLED (`paycheck.autopilot.enabled=false`) is one of its reasons.
- Analytics (handoff 7 and 8): an event is written only through `AnalyticsOutboxService.record(...)` inside the transaction that changes the state; the property map holds only keys on `ServerAnalyticsEvent.PROPERTY_KEYS`, in a fixed order, and never a name, descriptor, amount, id or date. `paycheck_stream_switched` carries exactly `frequency` (the new primary's cadence slug, the existing key of `investment_schedule_created`) then `reason` (new `REASON_SUCCESSION = "succession"`). No new property key. A new reason joins `LEGITIMATE_VALUES`; a new server event joins `ALL`, `SERVER_EVENT_NAMES` (same position) and the events document.
- Logs at INFO and above carry ids, codes, states and counts only, never a payer token, display name, descriptor or amount (spec 12.3).

Decisions this file makes inside the contract, each pinned by a test below:

1. Every trigger runs the same full recompute over the user's ACTIVE, posted inflow events (at most 24 months, so cheap); the trigger is logged and gives T12's `evaluateAndApply` its INCREMENTAL path.
2. The primary is sticky across payers: a current PRIMARY (not RETIRED) stays primary while it is PROVISIONAL, CONFIRMED or DORMANT, and a different payer replaces it only through `maybeSucceed` (spec 6.1). `PrimarySelector.select` runs only when there is no current primary that can keep the role.
3. Only streams on the funding account (`users.plaid_account_id`, when set) can be primary (spec 4.5: "until then only the funding-account leg counts"); others are scored, stored and SECONDARY.
4. Fewer than 2 deposits is CANDIDATE, never gated or scored (spec 4.2 gate 9). A RETIRED stream's stats are refreshed and its state never changes (spec 6.1: later S1 deposits are recorded, never confirmed). A stream with `missed_count >= 1` that would qualify stays DORMANT until T12 resets the count on the next matched deposit.
5. Roles after selection: PRIMARY for the primary; INTERRUPTION for a REJECTED `BENEFITS_SIGNAL` stream (spec 4.2); SECONDARY for any other PROVISIONAL, CONFIRMED or DORMANT stream; NONE otherwise and for RETIRED.
6. Detection state: SEARCHING while `plaid_history_complete` is false (no primary is selected and the mirror's primary is null); then the primary's state (PROVISIONAL, CONFIRMED or DORMANT), else UNDETECTABLE.
7. `evaluate` stamps `paycheck_events.stream_id` on every grouped deposit (T12's matcher and `findByStreamIdAndStatusOrderByPostedDateDesc` read it).
8. When `plaid_transactions_consented` is FALSE, `evaluate` writes nothing and reports NEEDS_CONSENT: no Transactions data can arrive and T13 owns that mirror value.

**Files:**
- Create: `P/paycheck/EvaluationTrigger.java`, `P/paycheck/EvaluationOutcome.java`, `P/service/PaycheckDetectionService.java`
- Modify: `P/util/ServerAnalyticsEvent.java:67` (after `public static final String SUBSCRIPTION_CANCELLED = "subscription_cancelled";`), `:84` (the line `            SUBSCRIPTION_CANCELLED);` that closes `ALL`), `:98` (after `public static final String REASON_KYC_REJECTED = "kyc_rejected";`); `F/services/analytics/analytics-events.ts:413` (after `  'subscription_cancelled',` inside `SERVER_EVENT_NAMES`); `FREDdocs/analytics-events.md:259` (after the `subscription_cancelled` row) and `:266` (after the line `FREDdocs/metrics.md for how `v_schedule_churn` reads them.`)
- Test: create `T/service/PaycheckDetectionServiceTest.java`; modify `T/service/AnalyticsOutboxForbiddenPayloadTest.java:217` (the line `                ServerAnalyticsEvent.REASON_KYC_REJECTED));`) and `T/PhaseOneMoneyInvarianceTest.java` (T03; one method appended before the class's closing brace). Unchanged gates run in Verification: `T/util/ServerAnalyticsEventParityTest.java`, `frontend/src/app/services/analytics/analytics-events.spec.ts`, `npm run test:events-documented`.

No property is added, so the gitignored `application-local.properties` needs no edit for this task.

**Interfaces:**
- Consumes:
  - T02 (`com.investingapp.backend.model`, Lombok `@Getter @Setter @NoArgsConstructor`, bare `Long` ids, one field per V6 column in camel case): `PaycheckStream` (the 3.3 columns; `cadence` is `InvestmentFrequency`, `Short anchorDow`, `short score`, `depositsFound`, `missedCount`, `Boolean plaidIsActive`, `state`, `role`, `cadenceSource`, `rejectReason`, `retiredReason` as the enums below); `PaycheckEvent` (the 3.4 columns; `Long streamId`, `boolean pending`, `status`); enums `PaycheckStreamState {CANDIDATE, PROVISIONAL, CONFIRMED, DORMANT, RETIRED, REJECTED}`, `PaycheckStreamRole {PRIMARY, SECONDARY, INTERRUPTION, NONE}`, `PaycheckEventStatus {ACTIVE, REMOVED, MODIFIED, SUPERSEDED}`, `PaycheckRejectReason` (includes `BENEFITS_SIGNAL`), `PaycheckRetiredReason {SUCCEEDED, DORMANT_120, ADMIN}`, `DetectionState {SEARCHING, NEEDS_CONSENT, UNDETECTABLE, PROVISIONAL, CONFIRMED, DORMANT, ITEM_BROKEN, REVOKED}`; `PaycheckStreamRepository.findByUserIdOrderByIdAsc(Long): List<PaycheckStream>`; `PaycheckEventRepository.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(Long, PaycheckEventStatus): List<PaycheckEvent>`; `User.getPlaidHistoryComplete()`, `getPaycheckAutopilotOptOut()` (Boolean); `InvestmentSchedule.setDetectionState(DetectionState)`, `setPrimaryStreamId(Long)`, `setNextExpectedDate(LocalDate)`, `setLastCadenceCheckAt(LocalDateTime)` and their getters; `util.ColumnText.fit(String, int)`.
  - T03: `config.PaycheckAutopilotProperties` (`isEnabled()`, `isShadow()`, `isPullsEnabled()`, `getScoreProvisional()`, `getScoreConfirmed()`, `getAffordabilityShare()`, `getDaysRequested()`); `static Optional<ExclusionReason> DetectionEligibility.exclusion(User, PaycheckAutopilotProperties)` with the enum nested as `DetectionEligibility.ExclusionReason {DISABLED, ACCOUNT_REJECTED, OPTED_OUT, NO_TOKEN, ITEM_STATUS}` (T03 creates no separate enum file, so the enum is nested); `T/PhaseOneMoneyInvarianceTest` (package `com.investingapp.backend`).
  - T09 (`com.investingapp.backend.paycheck.detect`, pure, static): `DepositGrouper.group(List<PaycheckEvent>): List<DepositGroup>`; `record DepositGroup(String plaidAccountId, String payerToken, List<PaycheckEvent> depositsAsc, Set<String> ppdIds)`; `DepositStats.trailingMedian(List<BigDecimal>)` and `trailingCv(List<BigDecimal>)`, both `BigDecimal`; `StreamFacts.of(PaycheckStream): Optional<StreamFacts>` with `plaidFrequency()` and `plaidStatus()`; `CadenceDeriver.derive(List<LocalDate>, Optional<String>): Optional<CadenceResult>`; `record CadenceResult(InvestmentFrequency cadence, PaycheckCadenceSource source, Integer anchorDow, LocalDate anchorParityDate, String anchorDay1, String anchorDay2, int intervals, boolean allInBand, int outsideBand)`; `HardGates.evaluate(DepositGroup, Optional<StreamFacts>, Optional<CadenceResult>, BigDecimal, LocalDate): Optional<PaycheckRejectReason>`; `StreamScorer.score(DepositGroup, Optional<StreamFacts>, CadenceResult, BigDecimal, BigDecimal): ScoreBreakdown` with `total()` and `toJson()`; `StreamStateRule.state(int, int, boolean, boolean, PaycheckAutopilotProperties): PaycheckStreamState` and `onCadenceCount(List<LocalDate>, CadenceResult): int`; `PrimarySelector.select(List<PaycheckStream>, BigDecimal)` returning the `Selection` record whose `primaryStreamId()` is read; `SuccessionRule.succeeds(PaycheckStream, PaycheckStream, LocalDate): boolean`; `PayCalendar.predictedNext(CadenceResult, LocalDate): LocalDate`; `PayerTokenizer.displayName(String, String, String, String): String`; `AmountPrefill.DEFAULT_GOAL` (500).
  - T10: catalog rows on `paycheck_streams` (the `plaid_*` columns), read only through `StreamFacts.of`.
  - T23 (runs before this task): `@DynamicUpdate` on `InvestmentSchedule`, so the mirror save names only the columns it set (R19).
  - Existing at HEAD: `UserRepository.findByIdForUpdate(Long): Optional<User>` (`P/repository/UserRepository.java:81-83`); `InvestmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(User): Optional<InvestmentSchedule>` (`:19`); `AnalyticsOutboxService.record(String, User, Map<String, Object>)` (`P/service/AnalyticsOutboxService.java:64`, refuses without a transaction); `static String InvestmentScheduleService.frequencySlug(String)` (package-private, `P/service/InvestmentScheduleService.java:231`); `EasternTime.now()`.
- Produces: `EvaluationTrigger`, `EvaluationOutcome` (with `skipped`), `PaycheckDetectionService.evaluate` and both `maybeSucceed` forms (the 4-argument contract form uses `EasternTime.today()`; `evaluate` calls the form with a trailing `LocalDate todayET`, because `SuccessionRule.succeeds` needs the evaluation's day), `ServerAnalyticsEvent.PAYCHECK_STREAM_SWITCHED`, `REASON_SUCCESSION`; exact signatures in the Produces recap at the end.

- [ ] **Step 1: Failing test for the new reason's value domain**

`T/service/AnalyticsOutboxForbiddenPayloadTest.java`: replace line 217, which is exactly

```java
                ServerAnalyticsEvent.REASON_KYC_REJECTED));
```

with

```java
                ServerAnalyticsEvent.REASON_KYC_REJECTED,
                // paycheck_stream_switched (spec 6.1 succession).
                ServerAnalyticsEvent.REASON_SUCCESSION));
```

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AnalyticsOutboxForbiddenPayloadTest' --console=plain
```

Expected: `BUILD FAILED` at `compileTestJava` with `error: cannot find symbol` / `symbol:   variable REASON_SUCCESSION` / `location: class ServerAnalyticsEvent`.

- [ ] **Step 2: The event name and the reason on ServerAnalyticsEvent**

`P/util/ServerAnalyticsEvent.java`. After line 67 (`    public static final String SUBSCRIPTION_CANCELLED = "subscription_cancelled";`) insert:

```java

    /**
     * The detected paycheck moved to a new payer at the same bank (spec 6.1 succession). Carries
     * the new primary's cadence and the reason, never the payer, the descriptor or an amount.
     */
    public static final String PAYCHECK_STREAM_SWITCHED = "paycheck_stream_switched";
```

Replace the line that closes `ALL` (HEAD line 84, exactly `            SUBSCRIPTION_CANCELLED);`) with:

```java
            SUBSCRIPTION_CANCELLED,
            PAYCHECK_STREAM_SWITCHED);
```

After the line `    public static final String REASON_KYC_REJECTED = "kyc_rejected";` (HEAD 98) insert:

```java
    /** paycheck_stream_switched only: a new payer took over as the primary paycheck (spec 6.1). */
    public static final String REASON_SUCCESSION = "succession";
```

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AnalyticsOutboxForbiddenPayloadTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.util.ServerAnalyticsEventParityTest' --console=plain
```

Expected: the first passes, 20 tests (the parameterized `everyEventIsScrubbed` grows from 14 to 15 cases, plus the 5 plain tests). The second fails 2 of 4: `namesMatch` with `SERVER_EVENT_NAMES in analytics-events.ts has drifted from ServerAnalyticsEvent.ALL`, and `propertyKeysMatchTheDocument` with `paycheck_stream_switched has no row in FREDdocs/analytics-events.md`.

- [ ] **Step 3: Client taxonomy and the events document**

`F/services/analytics/analytics-events.ts`: after line 413 (`  'subscription_cancelled',`, the last entry of `SERVER_EVENT_NAMES`) insert:

```ts
  'paycheck_stream_switched',
```

`FREDdocs/analytics-events.md`: after line 259 (the row starting ``| `subscription_cancelled` | `tier`, `days_since_trial_start_band` |``) insert:

```
| `paycheck_stream_switched` | `frequency`, `reason` | The detected paycheck moved to a new payer at the same bank (spec 6.1 succession), in shadow and live alike. |
```

After line 266 (`FREDdocs/metrics.md for how `v_schedule_churn` reads them.`) insert a blank line and then:

```
`paycheck_stream_switched` carries the new primary paycheck's cadence as `frequency` (`weekly`,
`biweekly`, `semi_monthly`, `monthly`) and a `reason` whose only value is `succession`. It never
carries the employer, the descriptor, an amount, a date or a stream id: payroll-deposit data stays
on our servers.
```

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.util.ServerAnalyticsEventParityTest' --console=plain
cd frontend && npm run test:events-documented
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/services/analytics/analytics-events.spec.ts'
```

Expected: 4 tests pass; the script prints `PASS: ... 15 server events, ...` and exits 0; Karma reports `Executed 14 of 14 SUCCESS` (a "no message in 30000 ms" disconnect is environmental: re-run).

- [ ] **Step 4: Failing invariance check (detection writes only the mirror)**

`T/PhaseOneMoneyInvarianceTest.java` (created by T03): append this method before the class's final closing brace. Every name is fully qualified so it compiles whatever imports T03 chose:

```java
    /**
     * T11: detection writes its own stream rows and the schedule's detection mirror, never money,
     * a stop or Item state. Read from source so a later edit that reaches for one of these fails
     * here, by name.
     */
    @org.junit.jupiter.api.Test
    void detectionEvaluateWritesOnlyTheMirror() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("src", "main", "java", "com", "investingapp", "backend");
        for (String relative : java.util.List.of(
                "service/PaycheckDetectionService.java",
                "paycheck/EvaluationTrigger.java",
                "paycheck/EvaluationOutcome.java")) {
            String source = java.nio.file.Files.readString(root.resolve(relative));
            for (String forbidden : java.util.List.of(
                    "setHoldReason(", "setInvestmentAmount(", "setMonthlyAmount(",
                    "setNextInvestmentDate(", "setAchRequestId(", "setFrequency(",
                    "setPlaidItemStatus(", "setPlaidHistoryComplete(", "setPlaidSyncCursor(",
                    "isReadyForInvestment(", "userRepository.save")) {
                org.junit.jupiter.api.Assertions.assertFalse(source.contains(forbidden),
                        relative + " must not contain " + forbidden);
            }
        }
    }
```

T03's own `phaseOneSourcesNeverCallSetStatusOrSetMode` already walks `service/Paycheck*` and `paycheck/**`, so the status and mode writers are covered there and not repeated here.

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
```

Expected: only `detectionEvaluateWritesOnlyTheMirror` fails, with `java.nio.file.NoSuchFileException: src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java`; every method already in the class passes.

- [ ] **Step 5: Failing service tests**

Create `T/service/PaycheckDetectionServiceTest.java`. The repositories are in-memory fakes over two lists; the pure T09 rules run for real, so the fixtures are spec-shaped: ACME pays biweekly on Fridays, the last on 2026-10-23, and today is Wednesday 2026-11-04. The ACME catalog row (as T10 writes it) carries Plaid MATURE, BIWEEKLY and INCOME_SALARY, so its CONFIRMED state does not depend on which side of the deposit the scorer reads PFC from. The fixtures call `setMode(` and `setStatus(` on test objects; T03's source walk covers `P/` only, so they do not trip it.

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckRejectReason;
import com.investingapp.backend.model.PaycheckRetiredReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaycheckDetectionServiceTest {

    private static final Long USER_ID = 42L;
    /** A Wednesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    /** Six biweekly Fridays; the next one is 2026-11-06. */
    private static final List<LocalDate> ACME_FRIDAYS = List.of(
            LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 28), LocalDate.of(2026, 9, 11),
            LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 23));
    /** Wire names are literals on purpose, as in ScheduleTransitionAnalyticsTest. */
    private static final String SWITCHED = "paycheck_stream_switched";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final PaycheckStreamRepository streamRepository = mock(PaycheckStreamRepository.class);
    private final PaycheckEventRepository eventRepository = mock(PaycheckEventRepository.class);
    private final AnalyticsOutboxService analyticsOutboxService = mock(AnalyticsOutboxService.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);

    private final List<PaycheckStream> storedStreams = new ArrayList<>();
    private final List<PaycheckEvent> storedEvents = new ArrayList<>();
    private final AtomicLong nextStreamId = new AtomicLong(100);
    private User user;
    private PaycheckDetectionService service;

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(props.isShadow()).thenReturn(true);
        when(props.isPullsEnabled()).thenReturn(false);
        when(props.getScoreProvisional()).thenReturn(55);
        when(props.getScoreConfirmed()).thenReturn(70);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        when(props.getDaysRequested()).thenReturn(365);

        user = activeUser();
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));

        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID)).thenAnswer(i -> new ArrayList<>(storedStreams));
        when(streamRepository.save(any(PaycheckStream.class))).thenAnswer(i -> {
            PaycheckStream s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(nextStreamId.getAndIncrement());
                storedStreams.add(s);
            }
            return s;
        });
        when(eventRepository.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(USER_ID, PaycheckEventStatus.ACTIVE))
                .thenAnswer(i -> storedEvents.stream().sorted(Comparator.comparing(PaycheckEvent::getPostedDate)).toList());

        service = new PaycheckDetectionService(userRepository, scheduleRepository, streamRepository,
                eventRepository, analyticsOutboxService, props);
    }

    @Test
    void manualRowWithoutOptOutKeepsEvaluating() {
        PaycheckStream acme = acmeCatalogRow();
        acmeHistory("acc-1");
        withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(outcome.excluded()).isEmpty();
        assertThat(outcome.state()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(outcome.primaryStreamId()).isEqualTo(acme.getId());
        assertThat(outcome.primaryChanged()).isTrue();
        assertThat(outcome.switched()).isFalse();
        assertThat(storedStreams).containsExactly(acme);
        assertThat(acme.getState()).isEqualTo(PaycheckStreamState.CONFIRMED);
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        assertThat(acme.getCadence()).isEqualTo(InvestmentFrequency.BIWEEKLY);
        assertThat(acme.getDepositsFound()).isEqualTo((short) 6);
        assertThat(acme.getLastDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(acme.getTrailingMedian()).isEqualByComparingTo("2418.37");
        assertThat(acme.getScore()).isGreaterThanOrEqualTo((short) 70);
        assertThat(storedEvents).allSatisfy(e -> assertThat(e.getStreamId()).isEqualTo(acme.getId()));
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    void rejectedOptOutNoTokenAndBrokenItemSkipped() {
        acmeCatalogRow();
        acmeHistory("acc-1");

        assertSkipped(u -> u.setAccountStatus("REJECTED"), ExclusionReason.ACCOUNT_REJECTED);
        assertSkipped(u -> u.setPaycheckAutopilotOptOut(true), ExclusionReason.OPTED_OUT);
        assertSkipped(u -> u.setPlaidAccessToken(null), ExclusionReason.NO_TOKEN);
        assertSkipped(u -> u.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED), ExclusionReason.ITEM_STATUS);

        verifyNoInteractions(streamRepository, eventRepository, scheduleRepository, analyticsOutboxService);
    }

    @Test
    void shadowMirrorsStateOntoManualRowAndNeverWritesMode() {
        PaycheckStream acme = acmeCatalogRow();
        acmeHistory("acc-1");
        InvestmentSchedule schedule = withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));

        service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(schedule.getPrimaryStreamId()).isEqualTo(acme.getId());
        assertThat(schedule.getNextExpectedDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(schedule.getLastCadenceCheckAt()).isNotNull();
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.ACTIVE);
        assertThat(schedule.getHoldReason()).isNull();
        verify(scheduleRepository).save(schedule);
    }

    @Test
    void noScheduleRowStillRecordsStreams() {
        PaycheckStream acme = acmeCatalogRow();
        acmeHistory("acc-1");

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(outcome.state()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void evaluateNeverWritesStatusOrHold() {
        acmeCatalogRow();
        acmeHistory("acc-1");
        InvestmentSchedule schedule = withSchedule(
                spy(manualSchedule(InvestmentSchedule.Status.PAUSED_BY_USER, HoldReason.BANK_CHANGE)));

        service.evaluate(USER_ID, EvaluationTrigger.RECONCILE, TODAY);

        verify(schedule, never()).setStatus(any());
        verify(schedule, never()).setHoldReason(any());
        verify(schedule, never()).setMode(any());
        verify(schedule, never()).setInvestmentAmount(any());
        verify(schedule, never()).setMonthlyAmount(any());
        verify(schedule, never()).setNextInvestmentDate(any());
        verify(schedule, never()).setAchRequestId(any());
        verify(schedule, never()).setFrequency(any());
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PAUSED_BY_USER);
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.BANK_CHANGE);
        // The mirror still lands on a paused, held row, and the row still cannot run.
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.CONFIRMED);
        assertThat(schedule.isReadyForInvestment(TODAY)).isFalse();
        verify(userRepository, never()).save(any());
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    void searchingUntilHistoryCompleteThenUndetectable() {
        PaycheckStream acme = acmeCatalogRow();
        acmeHistory("acc-1");
        user.setPlaidHistoryComplete(false);
        InvestmentSchedule schedule = withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));

        EvaluationOutcome searching = service.evaluate(USER_ID, EvaluationTrigger.INCREMENTAL, TODAY);

        assertThat(searching.state()).isEqualTo(DetectionState.SEARCHING);
        assertThat(searching.primaryStreamId()).isNull();
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.SEARCHING);
        assertThat(schedule.getPrimaryStreamId()).isNull();
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.SECONDARY);

        // History complete, and the only inflow is a benefits payment.
        storedStreams.clear();
        storedEvents.clear();
        user.setPlaidHistoryComplete(true);
        for (LocalDate posted : List.of(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 23))) {
            PaycheckEvent benefit = deposit("edd-" + posted, "acc-1", "EDD UI BENEFIT", null, "900.55", posted);
            benefit.setCounterpartyType(null);
            benefit.setPfcDetailed("INCOME_OTHER");
        }

        EvaluationOutcome undetectable = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(undetectable.state()).isEqualTo(DetectionState.UNDETECTABLE);
        assertThat(undetectable.primaryStreamId()).isNull();
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.UNDETECTABLE);
        assertThat(schedule.getNextExpectedDate()).isNull();
        PaycheckStream benefits = storedStreams.get(0);
        assertThat(benefits.getState()).isEqualTo(PaycheckStreamState.REJECTED);
        assertThat(benefits.getRejectReason()).isEqualTo(PaycheckRejectReason.BENEFITS_SIGNAL);
        assertThat(benefits.getRole()).isEqualTo(PaycheckStreamRole.INTERRUPTION);
    }

    @Test
    void oneDepositIsCandidateNeverPrimary() {
        deposit("acme-only", "acc-1", "ACME CORP PAYROLL", "Acme Corp", "2418.37", LocalDate.of(2026, 10, 23));

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(outcome.state()).isEqualTo(DetectionState.UNDETECTABLE);
        PaycheckStream candidate = storedStreams.get(0);
        assertThat(candidate.getState()).isEqualTo(PaycheckStreamState.CANDIDATE);
        assertThat(candidate.getRole()).isEqualTo(PaycheckStreamRole.NONE);
        assertThat(candidate.getDepositsFound()).isEqualTo((short) 1);
    }

    @Test
    void onlyTheFundingAccountLegCanBePrimary() {
        PaycheckStream savingsLeg = acmeCatalogRow();
        savingsLeg.setPlaidAccountId("acc-2");
        acmeHistory("acc-2");

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        assertThat(savingsLeg.getState()).isEqualTo(PaycheckStreamState.CONFIRMED);
        assertThat(savingsLeg.getRole()).isEqualTo(PaycheckStreamRole.SECONDARY);
        assertThat(outcome.state()).isEqualTo(DetectionState.UNDETECTABLE);
        assertThat(outcome.primaryStreamId()).isNull();
    }

    @Test
    void successionRetiresS1AndEmitsWholeMapOnce() {
        PaycheckStream acme = storedStream("ACME CORP", PaycheckStreamState.DORMANT, PaycheckStreamRole.PRIMARY);
        acme.setMissedCount((short) 1);
        acme.setCadence(InvestmentFrequency.BIWEEKLY);
        acme.setScore((short) 85);
        acme.setDepositsFound((short) 6);
        acme.setLastDate(LocalDate.of(2026, 9, 25));
        acme.setTrailingMedian(new BigDecimal("2418.37"));
        globexHistory();
        InvestmentSchedule schedule = withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));
        schedule.setPrimaryStreamId(acme.getId());

        // Order inside the one transaction: S1 retired, S2 promoted, the mirror moved, then the event.
        AtomicReference<String> stateAtEmit = new AtomicReference<>();
        when(analyticsOutboxService.record(eq(SWITCHED), eq(user), any())).thenAnswer(i -> {
            PaycheckStream globex = streamWithToken("GLOBEX");
            stateAtEmit.set(acme.getState() + "/" + acme.getRetiredReason() + "/" + globex.getRole() + "/"
                    + globex.getState() + "/" + globex.getId().equals(schedule.getPrimaryStreamId()));
            return null;
        });

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.INCREMENTAL, TODAY);

        PaycheckStream globex = streamWithToken("GLOBEX");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.forClass(Map.class);
        verify(analyticsOutboxService, times(1)).record(eq(SWITCHED), eq(user), properties.capture());
        assertThat(properties.getValue()).containsExactly(entry("frequency", "biweekly"), entry("reason", "succession"));
        assertThat(properties.getValue()).hasSize(2);
        assertThat(stateAtEmit.get()).isEqualTo("RETIRED/SUCCEEDED/PRIMARY/PROVISIONAL/true");
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.NONE);
        assertThat(acme.getRetiredAt()).isNotNull();
        assertThat(outcome.switched()).isTrue();
        assertThat(outcome.primaryChanged()).isTrue();
        assertThat(outcome.primaryStreamId()).isEqualTo(globex.getId());
        assertThat(outcome.state()).isEqualTo(DetectionState.PROVISIONAL);
        assertThat(schedule.getPrimaryStreamId()).isEqualTo(globex.getId());
        assertThat(schedule.getHoldReason()).isNull();

        // Nothing new: the switch is not re-announced.
        EvaluationOutcome again = service.evaluate(USER_ID, EvaluationTrigger.RECONCILE, TODAY);
        assertThat(again.switched()).isFalse();
        assertThat(again.primaryChanged()).isFalse();
        verify(analyticsOutboxService, times(1)).record(any(), any(), any());
    }

    @Test
    void successionNotTriggeredWhileS1KeepsPaying() {
        PaycheckStream acme = acmeCatalogRow();
        acme.setRole(PaycheckStreamRole.PRIMARY);
        acme.setState(PaycheckStreamState.CONFIRMED);
        acmeHistory("acc-1");
        globexHistory();

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.INCREMENTAL, TODAY);

        assertThat(outcome.switched()).isFalse();
        assertThat(outcome.primaryChanged()).isFalse();
        assertThat(outcome.primaryStreamId()).isEqualTo(acme.getId());
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        assertThat(streamWithToken("GLOBEX").getRole()).isEqualTo(PaycheckStreamRole.SECONDARY);
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    void refusedConsentWritesNothing() {
        acmeCatalogRow();
        acmeHistory("acc-1");
        user.setPlaidTransactionsConsented(false);
        InvestmentSchedule schedule = withSchedule(manualSchedule(InvestmentSchedule.Status.ACTIVE, null));
        schedule.setDetectionState(DetectionState.NEEDS_CONSENT);

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.RECONCILE, TODAY);

        assertThat(outcome.state()).isEqualTo(DetectionState.NEEDS_CONSENT);
        assertThat(outcome.excluded()).isEmpty();
        assertThat(schedule.getDetectionState()).isEqualTo(DetectionState.NEEDS_CONSENT);
        verify(scheduleRepository, never()).save(any());
        verifyNoInteractions(streamRepository, eventRepository, analyticsOutboxService);
    }

    @Test
    void evaluateRunsUnderUserRowLock() throws Exception {
        acmeCatalogRow();
        acmeHistory("acc-1");

        service.evaluate(USER_ID, EvaluationTrigger.FULL, TODAY);

        InOrder order = inOrder(userRepository, streamRepository, eventRepository);
        order.verify(userRepository).findByIdForUpdate(USER_ID);
        order.verify(streamRepository).findByUserIdOrderByIdAsc(USER_ID);
        order.verify(eventRepository).findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(USER_ID, PaycheckEventStatus.ACTIVE);
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).findByIdForUpdateNoWait(any());

        Method evaluate = PaycheckDetectionService.class
                .getMethod("evaluate", Long.class, EvaluationTrigger.class, LocalDate.class);
        Transactional tx = evaluate.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------

    private void assertSkipped(Consumer<User> change, ExclusionReason expected) {
        User excluded = activeUser();
        change.accept(excluded);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(excluded));

        EvaluationOutcome outcome = service.evaluate(USER_ID, EvaluationTrigger.RECONCILE, TODAY);

        assertThat(outcome.excluded()).contains(expected);
        assertThat(outcome.state()).isNull();
        assertThat(outcome.primaryStreamId()).isNull();
    }

    private User activeUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setAccountStatus("ACTIVE");
        u.setPlaidAccessToken("ENC:token-under-test");
        u.setPlaidItemId("item-1");
        u.setPlaidAccountId("acc-1");
        u.setPlaidItemStatus(PlaidItemStatus.OK);
        u.setPlaidHistoryComplete(true);
        u.setPaycheckAutopilotOptOut(false);
        u.setMonthlyInvestment(650.0);
        return u;
    }

    private InvestmentSchedule manualSchedule(InvestmentSchedule.Status status, HoldReason hold) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setId(7L);
        s.setUser(user);
        s.setStatus(status);
        s.setMode(ScheduleMode.MANUAL);
        s.setHoldReason(hold);
        s.setFrequency("BIWEEKLY");
        s.setInvestmentAmount(new BigDecimal("250.00"));
        s.setMonthlyAmount(new BigDecimal("542.50"));
        s.setStartDate(LocalDate.of(2026, 9, 1));
        s.setNextInvestmentDate(LocalDate.of(2026, 11, 6));
        s.setAchRequestId("rel-1");
        return s;
    }

    private InvestmentSchedule withSchedule(InvestmentSchedule schedule) {
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
        return schedule;
    }

    private PaycheckStream storedStream(String token, PaycheckStreamState state, PaycheckStreamRole role) {
        PaycheckStream s = new PaycheckStream();
        s.setId(nextStreamId.getAndIncrement());
        s.setUserId(USER_ID);
        s.setPlaidItemId("item-1");
        s.setPlaidAccountId("acc-1");
        s.setPayerToken(token);
        s.setPayerDisplayName(token);
        s.setState(state);
        s.setRole(role);
        s.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        storedStreams.add(s);
        return s;
    }

    /** The row T10 writes from /transactions/recurring/get for ACME before the first FULL evaluation. */
    private PaycheckStream acmeCatalogRow() {
        PaycheckStream s = storedStream("ACME CORP", PaycheckStreamState.CANDIDATE, PaycheckStreamRole.NONE);
        s.setPlaidStreamId("plaid-stream-acme");
        s.setPlaidStatus("MATURE");
        s.setPlaidFrequency("BIWEEKLY");
        s.setPlaidPfcDetailed("INCOME_SALARY");
        s.setPlaidPfcConfidence("VERY_HIGH");
        s.setPlaidIsActive(true);
        return s;
    }

    private void acmeHistory(String accountId) {
        for (int i = 0; i < ACME_FRIDAYS.size(); i++) {
            deposit("acme-" + i, accountId, "ACME CORP PAYROLL", "Acme Corp", "2418.37", ACME_FRIDAYS.get(i));
        }
    }

    /** A new employer at the same bank: two on-cadence Fridays, no Plaid stream yet. */
    private void globexHistory() {
        deposit("globex-1", "acc-1", "GLOBEX PAYROLL", "Globex", "1900.12", LocalDate.of(2026, 10, 9));
        deposit("globex-2", "acc-1", "GLOBEX PAYROLL", "Globex", "1900.12", LocalDate.of(2026, 10, 23));
    }

    private PaycheckEvent deposit(String txId, String accountId, String descriptor, String counterparty,
                                  String amount, LocalDate posted) {
        PaycheckEvent e = new PaycheckEvent();
        e.setUserId(USER_ID);
        e.setPlaidTransactionId(txId);
        e.setPlaidAccountId(accountId);
        e.setAmount(new BigDecimal(amount));
        e.setRawAmount(new BigDecimal(amount).negate());
        e.setPostedDate(posted);
        e.setPending(false);
        e.setStatus(PaycheckEventStatus.ACTIVE);
        e.setFirstSeenAt(posted.atTime(9, 0));
        e.setRawDescriptor(descriptor);
        e.setCounterpartyName(counterparty);
        e.setCounterpartyType("income_source");
        e.setPfcPrimary("INCOME");
        e.setPfcDetailed("INCOME_SALARY");
        e.setPfcConfidence("VERY_HIGH");
        e.setPaymentChannel("other");
        storedEvents.add(e);
        return e;
    }

    private PaycheckStream streamWithToken(String token) {
        return storedStreams.stream().filter(s -> token.equals(s.getPayerToken())).findFirst().orElseThrow();
    }
}
```

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckDetectionServiceTest' --console=plain
```

Expected: `BUILD FAILED` at `compileTestJava` with `error: cannot find symbol` for `class PaycheckDetectionService`, `class EvaluationOutcome` and `class EvaluationTrigger`.

- [ ] **Step 6: EvaluationTrigger and EvaluationOutcome**

Create `P/paycheck/EvaluationTrigger.java`:

```java
package com.investingapp.backend.paycheck;

/**
 * Why PaycheckDetectionService.evaluate ran (spec 4). Phase 1 runs the same full recompute for
 * every trigger and logs which one it was; INCREMENTAL is also the path T12's evaluateAndApply
 * takes before it applies new deposits to the open cycle.
 */
public enum EvaluationTrigger {
    /** History completed or an admin asked: every posted inflow event, from scratch. */
    FULL,
    /** A sync added deposits after history completed. */
    INCREMENTAL,
    /** /transactions/recurring/get changed the stream catalog. */
    STREAMS_CHANGED,
    /** The nightly reconcile or an admin evaluate verb. */
    RECONCILE
}
```

Create `P/paycheck/EvaluationOutcome.java`:

```java
package com.investingapp.backend.paycheck;

import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;

import java.util.Optional;

/**
 * What one evaluation decided. state and primaryStreamId are what was mirrored onto the schedule
 * row when one exists (R1). excluded is present when R8 skipped the user, and then nothing was
 * written and both are null. switched is true only when basic succession (spec 6.1, R5) replaced
 * the primary in this evaluation.
 */
public record EvaluationOutcome(DetectionState state, Long primaryStreamId, boolean primaryChanged,
                                boolean switched, Optional<ExclusionReason> excluded) {

    public static EvaluationOutcome skipped(ExclusionReason reason) {
        return new EvaluationOutcome(null, null, false, false, Optional.of(reason));
    }
}
```

- [ ] **Step 7: PaycheckDetectionService**

Create `P/service/PaycheckDetectionService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckRejectReason;
import com.investingapp.backend.model.PaycheckRetiredReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.paycheck.detect.AmountPrefill;
import com.investingapp.backend.paycheck.detect.CadenceDeriver;
import com.investingapp.backend.paycheck.detect.CadenceResult;
import com.investingapp.backend.paycheck.detect.DepositGroup;
import com.investingapp.backend.paycheck.detect.DepositGrouper;
import com.investingapp.backend.paycheck.detect.DepositStats;
import com.investingapp.backend.paycheck.detect.HardGates;
import com.investingapp.backend.paycheck.detect.PayCalendar;
import com.investingapp.backend.paycheck.detect.PayerTokenizer;
import com.investingapp.backend.paycheck.detect.PrimarySelector;
import com.investingapp.backend.paycheck.detect.ScoreBreakdown;
import com.investingapp.backend.paycheck.detect.StreamFacts;
import com.investingapp.backend.paycheck.detect.StreamScorer;
import com.investingapp.backend.paycheck.detect.StreamStateRule;
import com.investingapp.backend.paycheck.detect.SuccessionRule;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Paycheck detection's one orchestrator (spec 4, rulings R1, R2, R5, R8). Groups a user's posted
 * inflow events, applies the hard gates, scores, upserts paycheck_streams, keeps or selects the
 * primary, runs the basic succession rule and mirrors the outcome onto the schedule row when one
 * exists, in every mode. The rules themselves are the pure functions in paycheck/detect; this class
 * only sequences them and persists what they decide.
 *
 * <p>Money is out of reach here by construction. The schedule's mode, status, hold, amounts, dates
 * and ACH fields are never written (PhaseOneMoneyInvarianceTest reads this file to prove it), no
 * Item state is written, and nothing here calls Plaid, so the transaction holds no outbound HTTP.
 */
@Service
public class PaycheckDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckDetectionService.class);

    /** Spec 4.4: cadence, median and CV read the last six posted deposits. */
    static final int TRAILING_DEPOSITS = 6;

    /** V6 widths of the stream columns this class writes from Plaid-derived text. */
    private static final int PAYER_TOKEN_WIDTH = 64;
    private static final int DISPLAY_NAME_WIDTH = 120;
    private static final int DESCRIPTOR_WIDTH = 255;
    private static final int PLAID_ID_WIDTH = 80;

    /** trailing_cv is DECIMAL(6,4). */
    private static final BigDecimal CV_MAX = new BigDecimal("99.9999");

    /** Streams that can become primary in this evaluation. */
    private static final EnumSet<PaycheckStreamState> QUALIFYING =
            EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED);

    /** Streams that keep a role they already hold: a missed paycheck does not demote the primary. */
    private static final EnumSet<PaycheckStreamState> KEEPS_ROLE =
            EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED, PaycheckStreamState.DORMANT);

    /** Successor candidates, best first: highest score, then the larger trailing median. */
    private static final Comparator<PaycheckStream> SUCCESSOR_ORDER = Comparator
            .comparingInt((PaycheckStream s) -> s.getScore()).reversed()
            .thenComparing(PaycheckStream::getTrailingMedian, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()));

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckEventRepository eventRepository;
    private final AnalyticsOutboxService analyticsOutboxService;
    private final PaycheckAutopilotProperties props;

    public PaycheckDetectionService(UserRepository userRepository,
                                    InvestmentScheduleRepository scheduleRepository,
                                    PaycheckStreamRepository streamRepository,
                                    PaycheckEventRepository eventRepository,
                                    AnalyticsOutboxService analyticsOutboxService,
                                    PaycheckAutopilotProperties props) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.eventRepository = eventRepository;
        this.analyticsOutboxService = analyticsOutboxService;
        this.props = props;
    }

    /**
     * One evaluation, in one transaction, under the user row lock (Decision 4: a shadow user may
     * have no schedule row to lock, and a concurrent exchange already serializes on this row).
     * Excluded users (R8) are reported and nothing is written.
     */
    @Transactional
    public EvaluationOutcome evaluate(Long userId, EvaluationTrigger trigger, LocalDate todayET) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("No user " + userId));
        Optional<ExclusionReason> excluded = DetectionEligibility.exclusion(user, props);
        if (excluded.isPresent()) {
            logger.info("paycheck.evaluate.skipped user={} trigger={} reason={}", userId, trigger, excluded.get());
            return EvaluationOutcome.skipped(excluded.get());
        }
        InvestmentSchedule schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
        if (Boolean.FALSE.equals(user.getPlaidTransactionsConsented())) {
            // No Transactions data can arrive, and PlaidItemStateService owns the NEEDS_CONSENT
            // mirror; recomputing from older events would overwrite it.
            logger.info("paycheck.evaluate.skipped user={} trigger={} reason=CONSENT_REFUSED", userId, trigger);
            return new EvaluationOutcome(DetectionState.NEEDS_CONSENT,
                    schedule == null ? null : schedule.getPrimaryStreamId(), false, false, Optional.empty());
        }
        return evaluateLocked(user, schedule, trigger, todayET);
    }

    /** The body of evaluate; the caller holds the user row lock and has checked eligibility. */
    private EvaluationOutcome evaluateLocked(User user, InvestmentSchedule schedule,
                                             EvaluationTrigger trigger, LocalDate todayET) {
        Long userId = user.getId();
        LocalDateTime nowET = EasternTime.now();

        List<PaycheckStream> streams = new ArrayList<>(streamRepository.findByUserIdOrderByIdAsc(userId));
        PaycheckStream previousPrimary = streams.stream()
                .filter(s -> s.getRole() == PaycheckStreamRole.PRIMARY && s.getState() != PaycheckStreamState.RETIRED)
                .findFirst()
                .orElse(null);
        Long previousPrimaryId = previousPrimary == null ? null : previousPrimary.getId();

        List<PaycheckEvent> deposits = eventRepository
                .findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(userId, PaycheckEventStatus.ACTIVE);
        // Each grouped stream's cadence, by stream id. T12 opens the primary's cycle from this map.
        Map<Long, CadenceResult> cadences = new HashMap<>();
        List<PaycheckEvent> relinked = new ArrayList<>();
        for (DepositGroup group : DepositGrouper.group(deposits)) {
            String token = ColumnText.fit(group.payerToken(), PAYER_TOKEN_WIDTH);
            if (token == null || token.isBlank() || group.depositsAsc().isEmpty()) {
                continue;
            }
            PaycheckStream stream = findOrCreate(streams, user, group.plaidAccountId(), token, nowET);
            applyGroup(stream, group, todayET, nowET).ifPresent(cadence -> cadences.put(stream.getId(), cadence));
            for (PaycheckEvent deposit : group.depositsAsc()) {
                if (!stream.getId().equals(deposit.getStreamId())) {
                    deposit.setStreamId(stream.getId());
                    relinked.add(deposit);
                }
            }
        }

        PaycheckStream primary = null;
        boolean switched = false;
        if (Boolean.TRUE.equals(user.getPlaidHistoryComplete())) {
            List<PaycheckStream> qualifying = streams.stream()
                    .filter(s -> QUALIFYING.contains(s.getState()))
                    .filter(s -> fundsTheSchedule(user, s))
                    .toList();
            if (previousPrimary != null) {
                Optional<Long> successor = maybeSucceed(user, schedule, previousPrimary, qualifying, todayET);
                if (successor.isPresent()) {
                    primary = byId(streams, successor.get());
                    switched = true;
                } else if (KEEPS_ROLE.contains(previousPrimary.getState())) {
                    primary = previousPrimary;
                }
            }
            if (primary == null && !qualifying.isEmpty()) {
                primary = byId(streams, PrimarySelector.select(qualifying, monthlyGoal(user)).primaryStreamId());
            }
        }
        assignRoles(streams, primary);
        streamRepository.saveAll(streams);
        if (!relinked.isEmpty()) {
            eventRepository.saveAll(relinked);
        }

        DetectionState state = detectionState(user, primary);
        Long primaryId = primary == null ? null : primary.getId();
        if (schedule != null) {
            // R1: the mirror, in every mode. Nothing that moves money is touched.
            schedule.setDetectionState(state);
            schedule.setPrimaryStreamId(primaryId);
            schedule.setNextExpectedDate(primary == null ? null : primary.getPredictedNextDate());
            schedule.setLastCadenceCheckAt(nowET);
            scheduleRepository.save(schedule);
        }

        boolean primaryChanged = !Objects.equals(previousPrimaryId, primaryId);
        logger.info("paycheck.evaluate user={} trigger={} state={} streams={} primary_stream={} primary_changed={} switched={}",
                userId, trigger, state, streams.size(), primaryId, primaryChanged, switched);
        return new EvaluationOutcome(state, primaryId, primaryChanged, switched, Optional.empty());
    }

    /** Basic succession (spec 6.1 conditions 1 to 3, R5), judged as of today in Eastern time. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> maybeSucceed(User user, InvestmentSchedule schedule, PaycheckStream s1,
                                       List<PaycheckStream> candidates) {
        return maybeSucceed(user, schedule, s1, candidates, EasternTime.today());
    }

    /**
     * Basic succession (spec 6.1 conditions 1 to 3, no recency exception; R5). The first candidate
     * with a different payer, best first, that SuccessionRule accepts becomes the primary. Effects,
     * in this order, inside the caller's transaction: S1 RETIRED with SUCCEEDED, S2 PRIMARY and
     * PROVISIONAL, the schedule mirror's primary_stream_id, then paycheck_stream_switched with
     * exactly frequency then reason. No amount, authorization, hold or notice change (Phase 2).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> maybeSucceed(User user, InvestmentSchedule schedule, PaycheckStream s1,
                                       List<PaycheckStream> candidates, LocalDate todayET) {
        List<PaycheckStream> ordered = candidates.stream()
                .filter(s2 -> !Objects.equals(s2.getId(), s1.getId()))
                .filter(s2 -> !Objects.equals(s2.getPayerToken(), s1.getPayerToken()))
                .sorted(SUCCESSOR_ORDER)
                .toList();
        for (PaycheckStream s2 : ordered) {
            if (!SuccessionRule.succeeds(s1, s2, todayET)) {
                continue;
            }
            LocalDateTime nowET = EasternTime.now();
            s1.setState(PaycheckStreamState.RETIRED);
            s1.setRetiredReason(PaycheckRetiredReason.SUCCEEDED);
            s1.setRetiredAt(nowET);
            s1.setRole(PaycheckStreamRole.NONE);
            s1.setUpdatedAt(nowET);
            streamRepository.save(s1);

            s2.setRole(PaycheckStreamRole.PRIMARY);
            s2.setState(PaycheckStreamState.PROVISIONAL);
            s2.setUpdatedAt(nowET);
            streamRepository.save(s2);

            if (schedule != null) {
                schedule.setPrimaryStreamId(s2.getId());
            }

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("frequency", InvestmentScheduleService.frequencySlug(
                    s2.getCadence() == null ? null : s2.getCadence().name()));
            properties.put("reason", ServerAnalyticsEvent.REASON_SUCCESSION);
            analyticsOutboxService.record(ServerAnalyticsEvent.PAYCHECK_STREAM_SWITCHED, user, properties);

            logger.info("paycheck.stream.switched user={} from_stream={} to_stream={}",
                    user.getId(), s1.getId(), s2.getId());
            return Optional.of(s2.getId());
        }
        return Optional.empty();
    }

    private PaycheckStream findOrCreate(List<PaycheckStream> streams, User user, String accountId,
                                        String token, LocalDateTime nowET) {
        String account = ColumnText.fit(accountId, PLAID_ID_WIDTH);
        for (PaycheckStream existing : streams) {
            if (Objects.equals(existing.getPlaidAccountId(), account) && token.equals(existing.getPayerToken())) {
                return existing;
            }
        }
        PaycheckStream created = new PaycheckStream();
        created.setUserId(user.getId());
        created.setPlaidItemId(ColumnText.fit(user.getPlaidItemId(), PLAID_ID_WIDTH));
        created.setPlaidAccountId(account);
        created.setPayerToken(token);
        created.setPayerDisplayName(token);
        created.setState(PaycheckStreamState.CANDIDATE);
        created.setRole(PaycheckStreamRole.NONE);
        created.setCreatedAt(nowET);
        PaycheckStream saved = streamRepository.save(created);
        streams.add(saved);
        return saved;
    }

    /**
     * Stats, gates, score and state for one group (spec 4.2 to 4.5). Returns the cadence when the
     * stream passed the gates, so the caller can hand it to the cycle code.
     */
    private Optional<CadenceResult> applyGroup(PaycheckStream stream, DepositGroup group,
                                               LocalDate todayET, LocalDateTime nowET) {
        List<PaycheckEvent> all = group.depositsAsc();
        PaycheckEvent latest = all.get(all.size() - 1);
        List<PaycheckEvent> trailing = all.subList(Math.max(0, all.size() - TRAILING_DEPOSITS), all.size());
        List<LocalDate> dates = trailing.stream().map(PaycheckEvent::getPostedDate).toList();
        List<BigDecimal> amounts = trailing.stream().map(PaycheckEvent::getAmount).toList();
        BigDecimal median = DepositStats.trailingMedian(amounts);
        BigDecimal cv = amounts.size() < 2 ? null : DepositStats.trailingCv(amounts);

        String displayName = PayerTokenizer.displayName(latest.getCounterpartyName(),
                latest.getCounterpartyType(), latest.getMerchantName(), stream.getPayerToken());
        stream.setPayerDisplayName(ColumnText.fit(Objects.requireNonNullElse(displayName, stream.getPayerToken()),
                DISPLAY_NAME_WIDTH));
        stream.setRawDescriptor(ColumnText.fit(latest.getRawDescriptor(), DESCRIPTOR_WIDTH));
        stream.setDepositsFound(toShort(all.size()));
        stream.setFirstDate(all.get(0).getPostedDate());
        stream.setLastDate(latest.getPostedDate());
        stream.setLastAmount(latest.getAmount());
        stream.setTrailingMedian(median == null ? null : median.setScale(2, RoundingMode.HALF_UP));
        stream.setTrailingCv(cv == null ? null : cv.min(CV_MAX).setScale(4, RoundingMode.HALF_UP));
        stream.setUpdatedAt(nowET);

        if (stream.getState() == PaycheckStreamState.RETIRED) {
            // Spec 6.1: a retired stream's later deposits are recorded, never confirmed or promoted.
            return Optional.empty();
        }
        if (all.size() < 2) {
            // Spec 4.2 gate 9: fewer than 2 posted deposits is a candidate, never rejected.
            markCandidate(stream);
            return Optional.empty();
        }
        Optional<StreamFacts> facts = StreamFacts.of(stream);
        Optional<CadenceResult> cadence = CadenceDeriver.derive(dates, facts.map(StreamFacts::plaidFrequency));
        Optional<PaycheckRejectReason> reject = HardGates.evaluate(group, facts, cadence, median, todayET);
        if (reject.isPresent()) {
            stream.setState(PaycheckStreamState.REJECTED);
            stream.setRejectReason(reject.get());
            stream.setScore((short) 0);
            stream.setScoreBreakdownJson(null);
            return Optional.empty();
        }
        if (cadence.isEmpty()) {
            markCandidate(stream);
            return Optional.empty();
        }

        CadenceResult result = cadence.get();
        ScoreBreakdown breakdown = StreamScorer.score(group, facts, result, median, cv);
        int onCadence = StreamStateRule.onCadenceCount(dates, result);
        boolean plaidMature = facts.map(f -> "MATURE".equals(f.plaidStatus())).orElse(false);
        PaycheckStreamState state = StreamStateRule.state(breakdown.total(), onCadence, plaidMature, facts.isPresent(), props);
        if (stream.getMissedCount() > 0 && QUALIFYING.contains(state)) {
            // A miss stands until T12 matches the next deposit and resets missed_count (spec 6.4).
            state = PaycheckStreamState.DORMANT;
        }
        stream.setState(state);
        stream.setRejectReason(null);
        stream.setScore(toShort(breakdown.total()));
        stream.setScoreBreakdownJson(breakdown.toJson());
        stream.setCadence(result.cadence());
        stream.setCadenceSource(result.source());
        stream.setAnchorDow(result.anchorDow() == null ? null : result.anchorDow().shortValue());
        stream.setAnchorParityDate(result.anchorParityDate());
        stream.setAnchorDay1(result.anchorDay1());
        stream.setAnchorDay2(result.anchorDay2());
        stream.setPredictedNextDate(PayCalendar.predictedNext(result, latest.getPostedDate()));
        return cadence;
    }

    private static void markCandidate(PaycheckStream stream) {
        stream.setState(PaycheckStreamState.CANDIDATE);
        stream.setRejectReason(null);
        stream.setScore((short) 0);
        stream.setScoreBreakdownJson(null);
    }

    /** Roles follow states and the chosen primary; RETIRED holds none (spec 4.2, 4.5, 6.1). */
    private static void assignRoles(List<PaycheckStream> streams, PaycheckStream primary) {
        for (PaycheckStream s : streams) {
            PaycheckStreamRole role;
            if (s.getState() == PaycheckStreamState.RETIRED) {
                role = PaycheckStreamRole.NONE;
            } else if (primary != null && s.getId().equals(primary.getId())) {
                role = PaycheckStreamRole.PRIMARY;
            } else if (s.getState() == PaycheckStreamState.REJECTED
                    && s.getRejectReason() == PaycheckRejectReason.BENEFITS_SIGNAL) {
                role = PaycheckStreamRole.INTERRUPTION;
            } else if (KEEPS_ROLE.contains(s.getState())) {
                role = PaycheckStreamRole.SECONDARY;
            } else {
                role = PaycheckStreamRole.NONE;
            }
            s.setRole(role);
        }
    }

    /** Spec 4.5 screen-1 outcome: SEARCHING until history completes, then the primary or nothing. */
    private static DetectionState detectionState(User user, PaycheckStream primary) {
        if (!Boolean.TRUE.equals(user.getPlaidHistoryComplete())) {
            return DetectionState.SEARCHING;
        }
        if (primary == null) {
            return DetectionState.UNDETECTABLE;
        }
        return switch (primary.getState()) {
            case CONFIRMED -> DetectionState.CONFIRMED;
            case DORMANT -> DetectionState.DORMANT;
            default -> DetectionState.PROVISIONAL;
        };
    }

    /** Spec 4.5: until split-deposit summing (Phase 3), only the funding-account leg can be primary. */
    private static boolean fundsTheSchedule(User user, PaycheckStream stream) {
        return user.getPlaidAccountId() == null || user.getPlaidAccountId().equals(stream.getPlaidAccountId());
    }

    /** Spec 4.1: the survey goal, else 500. */
    private static BigDecimal monthlyGoal(User user) {
        Double goal = user.getMonthlyInvestment();
        return goal == null || goal <= 0 ? BigDecimal.valueOf(AmountPrefill.DEFAULT_GOAL) : BigDecimal.valueOf(goal);
    }

    private static PaycheckStream byId(List<PaycheckStream> streams, Long id) {
        if (id == null) {
            return null;
        }
        return streams.stream().filter(s -> id.equals(s.getId())).findFirst().orElse(null);
    }

    private static short toShort(int value) {
        return (short) Math.min(value, Short.MAX_VALUE);
    }
}
```

`evaluate` reaches `maybeSucceed` by a self-call, so `MANDATORY` binds only outside callers (Phase 2), who must already hold the user-locked transaction.

- [ ] **Step 8: Run the service and invariance tests**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckDetectionServiceTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
```

Expected, read from `backend/build/test-results/test/TEST-*.xml` and never the console: `tests="12" skipped="0" failures="0" errors="0"` for PaycheckDetectionServiceTest, then the count before this task plus 1 (`detectionEvaluateWritesOnlyTheMirror`). If an assertion about a T09 outcome fails (for example GLOBEX not reaching PROVISIONAL), the T09 rule disagrees with spec 4.4 or 4.5: fix the rule against its own T09 test, never loosen this one.

- [ ] **Step 9: Run everything this task touches**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckDetectionServiceTest' --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --tests 'com.investingapp.backend.util.ServerAnalyticsEventParityTest' --tests 'com.investingapp.backend.service.AnalyticsOutboxForbiddenPayloadTest' --tests 'com.investingapp.backend.util.AnalyticsPrivacyGuardParityTest' --tests 'com.investingapp.backend.service.ScheduleTransitionAnalyticsTest' --console=plain
cd frontend && npm run test:events-documented
cd frontend && npx tsc --noEmit
grep -n 'setStatus(\|setMode(\|setHoldReason(\|getIsPaused(\|isPausedForAnyReason(' backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java backend/src/main/java/com/investingapp/backend/paycheck/EvaluationTrigger.java backend/src/main/java/com/investingapp/backend/paycheck/EvaluationOutcome.java
grep -n 'logger\.' backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java
git diff HEAD -- backend/src frontend/src FREDdocs/analytics-events.md | grep -n $'^+.*\xe2\x80\x94'
grep -rn $'\xe2\x80\x94' backend/src/main/java/com/investingapp/backend/paycheck/EvaluationTrigger.java backend/src/main/java/com/investingapp/backend/paycheck/EvaluationOutcome.java backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java backend/src/test/java/com/investingapp/backend/service/PaycheckDetectionServiceTest.java
```

Expected: `BUILD SUCCESSFUL`; the XML shows PaycheckDetectionServiceTest 12, PhaseOneMoneyInvarianceTest the count before this task plus 1, ServerAnalyticsEventParityTest 4, AnalyticsOutboxForbiddenPayloadTest 20, the other two at their pre-task counts, all `failures="0" errors="0" skipped="0"`; the script prints `PASS` with 15 server events; `tsc` exits 0; the first grep prints nothing; the second prints exactly four lines (`paycheck.evaluate.skipped` twice, `paycheck.evaluate`, `paycheck.stream.switched`) whose arguments are ids, the trigger, states, counts and booleans only; the two em dash greps (the `$'...'` byte form, run in bash or zsh) print nothing.

- [ ] **Step 10: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` first (shared tree: stage only what this task wrote; never `ITPM/agent-memory/*` or `application-local.properties`; never `git checkout`, `git restore`, `git stash` or `git reset`). Then:

```
git add backend/src/main/java/com/investingapp/backend/paycheck/EvaluationTrigger.java backend/src/main/java/com/investingapp/backend/paycheck/EvaluationOutcome.java backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java backend/src/main/java/com/investingapp/backend/util/ServerAnalyticsEvent.java backend/src/test/java/com/investingapp/backend/service/PaycheckDetectionServiceTest.java backend/src/test/java/com/investingapp/backend/service/AnalyticsOutboxForbiddenPayloadTest.java backend/src/test/java/com/investingapp/backend/PhaseOneMoneyInvarianceTest.java frontend/src/app/services/analytics/analytics-events.ts FREDdocs/analytics-events.md
git commit -m "feat(paycheck): evaluate detection under the user lock, mirror it onto the schedule, and record succession"
```

## Verification

Per class (counts from `backend/build/test-results/test/*.xml`, never the console):

| Command | Expected |
|---|---|
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckDetectionServiceTest' --console=plain` | `tests="12" skipped="0" failures="0" errors="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain` | the count before this task plus 1, `skipped="0" failures="0" errors="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.util.ServerAnalyticsEventParityTest' --console=plain` | `tests="4" skipped="0" failures="0" errors="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AnalyticsOutboxForbiddenPayloadTest' --console=plain` | `tests="20" skipped="0" failures="0" errors="0"` |
| `cd frontend && npm run test:events-documented` | `PASS: ...` naming 15 server events, exit 0 |
| `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/services/analytics/analytics-events.spec.ts'` | `Executed 14 of 14 SUCCESS` |

None of this task's classes is `@RequiresCiDatabase`, but the whole suite still runs against a scratch MySQL before the task is done, since a green build can hide skipped database tests. The password is typed, never written down or echoed:

```
read -rs SCRATCH_DB_PASSWORD
mysql -u root -p"$SCRATCH_DB_PASSWORD" -e "CREATE DATABASE fred_t11_scratch"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t11_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD="$SCRATCH_DB_PASSWORD"
export PLAID_WEBHOOK_URL=https://local.fredvested.com/api/plaid/webhook
export PLAID_REDIRECT_URI=https://local.fredvested.com/link-bank
export PLAID_LINK_CUSTOMIZATION=default
cd backend && ./gradlew cleanTest test --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests=" t, "skipped=" s, "failures=" f, "errors=" e}'
mysql -u root -p"$SCRATCH_DB_PASSWORD" -e "DROP DATABASE fred_t11_scratch"
```

Expected: `skipped=0 failures=0 errors=0`, and `tests` equal to the total before this task plus 14 (12 service tests, 1 invariance method, 1 new `everyEventIsScrubbed` case).

## Produces

- `com.investingapp.backend.paycheck.EvaluationTrigger { FULL, INCREMENTAL, STREAMS_CHANGED, RECONCILE }`
- `com.investingapp.backend.paycheck.EvaluationOutcome(DetectionState state, Long primaryStreamId, boolean primaryChanged, boolean switched, Optional<ExclusionReason> excluded)` and `EvaluationOutcome.skipped(ExclusionReason)`
- `com.investingapp.backend.service.PaycheckDetectionService(UserRepository, InvestmentScheduleRepository, PaycheckStreamRepository, PaycheckEventRepository, AnalyticsOutboxService, PaycheckAutopilotProperties)`:
  - `@Transactional EvaluationOutcome evaluate(Long userId, EvaluationTrigger trigger, LocalDate todayET)`
  - `@Transactional(propagation = MANDATORY) Optional<Long> maybeSucceed(User, InvestmentSchedule, PaycheckStream s1, List<PaycheckStream> candidates)` and the overload with a trailing `LocalDate todayET`
  - seam for T12: T12 renames this body package-private `evaluateWithoutCycles` and wraps it in a new `evaluate` that opens the primary's cycle in the same transaction
- `ServerAnalyticsEvent.PAYCHECK_STREAM_SWITCHED = "paycheck_stream_switched"` (appended to `ALL`), `ServerAnalyticsEvent.REASON_SUCCESSION = "succession"`
- `'paycheck_stream_switched'` appended to `SERVER_EVENT_NAMES`; its row and value note in `FREDdocs/analytics-events.md`; `succession` in the `reason` domain of `LEGITIMATE_VALUES`
- `PhaseOneMoneyInvarianceTest.detectionEvaluateWritesOnlyTheMirror`
