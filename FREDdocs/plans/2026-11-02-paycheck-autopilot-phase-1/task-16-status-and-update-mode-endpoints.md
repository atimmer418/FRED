### Task 16: status-and-update-mode-endpoints

Full code for the steps this file gives as tables or patterns is in task-16-status-and-update-mode-endpoints-appendix.md; read both.

Ships `GET /api/paycheck-detection/status` (spec 8.1) with its INIT enqueue (R4a), `POST /api/plaid/update-mode-complete` (spec 2.3, 8.8, R10), and `transactionsConsented` plus `consentExpiresAt` on `GET /api/plaid/primary-bank-account` (decision 11). The status endpoint is database only except that it hands a cursor-less user's first sync to the Plaid pool after its stamp commits; the app does not call it in Phase 1 (R4). T21 calls `update-mode-complete`. Nothing here moves money, writes `status`, `mode` or `holdReason`, changes `isReadyForInvestment`, or emits analytics.

`M/` = `backend/src/main/java/com/investingapp/backend/`, `T/` = `backend/src/test/java/com/investingapp/backend/`. Lines are HEAD 137998c (no earlier task edits `PlaidController.java`); anchors also quote text. `FREDdocs/API_ENDPOINTS.md` is edited in parallel by T17, T18 and Goals v3 (R17): quoted-text anchors, no reordering.

Facts this task depends on:

1. **State is `InvestmentSchedule.status`** (`PENDING_BANK`, `ACTIVE`, `PAUSED_BY_USER`; `is_paused` is gone). Wire "paused" is `getStatus() != ACTIVE`, never the deprecated getters (decision 59). `hold_reason` is the system's stop, orthogonal; this task writes neither (T03's invariance walk and T13's `setHoldReason` allowlist cover these files).
2. **No Plaid HTTP inside a transaction.** `PlaidUpdateModeService` opens none and writes only through T13's handlers, each with its own template, user row lock and item re-check (decision 40). `InitSyncEnqueuer` stamps in a short template and submits `pipeline.run(INIT)` to `plaidExecutor` after commit, proven with T06's `RecordingTransactionManager` (`open` from begin to commit or rollback).
3. **INIT trigger (decisions 17, 69):** not excluded, no cursor, and no row or `detectionStartedAt == null`. A no-row user is submitted directly (the GET has no transaction); an in-JVM in-flight set blocks a second call while one runs.
4. **Update mode never enqueues INIT** (decision 10): new consent `onConsentGranted`; then every success `onConsentRenewed` (last, as granting nulls the expiry; PENDING_DISCONNECT to OK, spec 2.3); absent with a TRUE or FALSE flag `onConsentRequired` (NULL stays unknown); LOGIN_REQUIRED `onLoginRepaired(UPDATE_MODE)`, then `pipeline.run(MANUAL)` on the pool when a cursor exists.
5. **`consentExpiresAt` is `users.plaid_disconnect_at`** (Eastern wall time, sent with its offset).
6. **Logging:** INFO carries closed words only; user ids on WARN only; never a payer name, descriptor, amount, date, Plaid id, token or cursor.
7. **No property is added**; nothing to hand-add to `application-local.properties`.

Code convention: the line before each Java block lists its imports as `pkg.{A, B}` groups (`B.` = `com.investingapp.backend.`, `S.` = `org.springframework.`), one `import` per name; every new test class also takes `import static org.assertj.core.api.Assertions.*;`, `import static org.mockito.Mockito.*;` (with the inherited `ArgumentMatchers` statics) and `org.junit.jupiter.api.{BeforeEach, Test}`. The block is the rest of the file, literally, except that a `// [ ... ]` comment stands for the earlier lines it names, which you write out.

**Files:**
- Create: `M/dto/PaycheckDetectionStatusResponse.java`, `M/service/{PaycheckDetectionStatusService, InitSyncEnqueuer, PlaidUpdateModeService}.java`, `M/controller/PaycheckDetectionController.java`
- Modify: `M/controller/PlaidController.java` (imports after `:10`, `:11`; field after `:39`; method after `:120`; two lines after `:143`); `FREDdocs/API_ENDPOINTS.md`
- Test: create `T/service/{PaycheckDetectionStatusServiceTest, InitSyncEnqueuerTest, PlaidUpdateModeServiceTest}.java`, `T/controller/{PaycheckDetectionControllerTest, PlaidControllerUpdateModeCompleteTest}.java`; modify `T/controller/PlaidControllerPrimaryBankAccountTest.java`

**Interfaces:**
- Consumes (a type created inside a single-file task is nested in that file's class): T02 the Phase 1 `User` and `InvestmentSchedule` accessors, `model.{DetectionState, PaycheckStream, PaycheckStreamState, PaycheckStreamRole, PaycheckCadenceSource}` (stream fields named after V6 columns, `Short anchorDow`, `short` counters), `PaycheckStreamRepository.findById`, `findByUserIdOrderByIdAsc(Long)`; T03 `PaycheckAutopilotProperties.isEnabled()`, `isShadow()`, `getAffordabilityShare()`, static `DetectionEligibility.exclusion(User, PaycheckAutopilotProperties)`; T06 `PlaidGateway.call(String, Call<T>)`, `PlaidCallResult<T>(body, error).ok()`, `PlaidApiError` (six components), `PlaidErrorClass`, test `support.RecordingTransactionManager` (public `boolean open`); T08 `PlaidTransactionsSyncService.SyncReason`; T09 `AmountPrefill.compute(Double, InvestmentFrequency, BigDecimal median, BigDecimal share)` returning `AmountPrefill.Prefill` (null median, null cap), `PrimarySelector.select` returning `PrimarySelector.Selection(primaryStreamId, secondaryStreamIds, needsChooser, lowConfidence)`, `CadenceResult` (nine components), `PayCalendar.cadenceLabel(CadenceResult)`; T13 `PlaidItemStateService.onConsentRenewed(Long, String, OffsetDateTime)`, `onConsentGranted(Long, String)`, `onConsentRequired(Long, String)`, `onLoginRepaired(Long, String, RepairPath)`, each returning `ItemStateResult`; T14 `PaycheckSyncPipeline.run(Long, String, SyncReason)`; HEAD `findByIdForUpdate` (`UserRepository:81-83`), `findTopByUserOrderByCreatedAtDesc` (`InvestmentScheduleRepository:19`), bean `plaidExecutor`.
- Produces: the recap at the end.

- [ ] **Step 1: Re-run `javap` on `ItemWithConsentFields`**

```
find ~/.gradle/caches -name 'plaid-java-46.0.0.jar'
/opt/homebrew/opt/openjdk@17/bin/javap -cp <path printed above> com.plaid.client.model.ItemWithConsentFields | grep -E 'getItemId|getError|getConsentedProducts|getConsentExpirationTime'
```

Expected, exactly (verified at 137998c):

```
  public java.lang.String getItemId();
  public com.plaid.client.model.PlaidError getError();
  public java.util.List<com.plaid.client.model.Products> getConsentedProducts();
  public java.time.OffsetDateTime getConsentExpirationTime();
```

Also: `ItemGetResponse.getItem()` is `ItemWithConsentFields`; `ItemWebhookUpdateRequest` has `accessToken`, `webhook`; `PlaidError` has `errorCode(String)`. Stop and report on any difference.

- [ ] **Step 2: Failing test for the status service**

Create `T/service/PaycheckDetectionStatusServiceTest.java`. Imports: `com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}`, `B.config.PaycheckAutopilotProperties`, `B.dto.PaycheckDetectionStatusResponse`, `B.model.{DetectionState, InvestmentFrequency, InvestmentSchedule, PaycheckCadenceSource, PaycheckStream, PaycheckStreamState, ScheduleMode, User}`, `B.repository.{InvestmentScheduleRepository, PaycheckStreamRepository}`, `java.math.BigDecimal`, `java.time.LocalDate`, `java.util.{List, Optional}`.

```java
package com.investingapp.backend.service;

class PaycheckDetectionStatusServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final PaycheckStreamRepository streamRepository = mock(PaycheckStreamRepository.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private PaycheckDetectionStatusService service;
    private User user;

    @BeforeEach
    void setUp() {
        when(props.isShadow()).thenReturn(true);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        service = new PaycheckDetectionStatusService(scheduleRepository, streamRepository, props);
        user = new User("status@fredvested.com"); // monthlyInvestment starts at 0.0
        user.setId(7L);
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
    }

    private InvestmentSchedule row(InvestmentSchedule.Status status) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(70L);
        schedule.setUser(user);
        schedule.setMode(ScheduleMode.MANUAL);
        schedule.setStatus(status);
        schedule.setInvestmentAmount(new BigDecimal("250.00"));
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
        return schedule;
    }

    private void acmeStream(Long ownerId) {
        PaycheckStream s = new PaycheckStream();
        s.setId(41L);
        s.setUserId(ownerId);
        s.setPayerToken("ACME CORP");
        s.setPayerDisplayName("Acme Corp");
        s.setRawDescriptor("ACME CORP PAYROLL");
        s.setState(PaycheckStreamState.CONFIRMED);
        s.setCadence(InvestmentFrequency.BIWEEKLY);
        s.setCadenceSource(PaycheckCadenceSource.OBSERVED);
        s.setAnchorDow((short) 5);
        s.setScore((short) 80);
        s.setLastAmount(new BigDecimal("2418.00"));
        s.setTrailingMedian(new BigDecimal("2418.00"));
        s.setTrailingCv(new BigDecimal("0.0100"));
        s.setPredictedNextDate(LocalDate.of(2026, 10, 23));
        when(streamRepository.findById(41L)).thenReturn(Optional.of(s));
        when(streamRepository.findByUserIdOrderByIdAsc(7L)).thenReturn(List.of(s));
    }

    @Test
    void searchingWithoutRowHasNoPrimaryAndDefaultGoal() {
        user.setPlaidAccountName("Plaid Checking");
        user.setPlaidAccountMask("0000");
        user.setPaycheckAutopilotOptOut(true);
        user.setPlaidHistoryComplete(true);

        PaycheckDetectionStatusResponse r = service.status(user, TODAY);

        assertThat(r.fundingAccountLabel()).isEqualTo("Plaid Checking \u2022\u2022\u2022\u20220000");
        assertThat(r.optOut()).isTrue();
        assertThat(r.historyComplete()).isTrue();
        assertThat(r.shadow()).isTrue();
        assertThat(r.mode()).isEqualTo("MANUAL");
        assertThat(r.state()).isEqualTo("SEARCHING");
        assertThat(r.itemStatus()).isEqualTo("OK");
        assertThat(r.paused()).isFalse();
        assertThat(r.primary()).isNull();
        assertThat(r.monthlyGoal()).isEqualByComparingTo("500");
        assertThat(r.goalSource()).isEqualTo("default");
        assertThat(r.savedAmount()).isNull();
        assertThat(r.amountSource()).isNull();
        assertThat(r.authorization()).isNull();
        assertThat(r.manualPrefill().frequency()).isEqualTo("BIWEEKLY");
        assertThat(r.manualPrefill().nextInvestmentDate()).isEqualTo(TODAY.plusDays(14));
        assertThat(r.manualPrefill().amount()).isEqualByComparingTo("230"); // round(500 / 2.17)
        verify(streamRepository, never()).findById(anyLong());
    }

    @Test
    void isPausedComesFromStatusNeverTheDeprecatedGetter() throws Exception {
        row(InvestmentSchedule.Status.PENDING_BANK);
        assertThat(service.status(user, TODAY).paused()).isTrue();
        row(InvestmentSchedule.Status.PAUSED_BY_USER);
        assertThat(service.status(user, TODAY).paused()).isTrue();
        row(InvestmentSchedule.Status.ACTIVE);
        PaycheckDetectionStatusResponse active = service.status(user, TODAY);
        assertThat(active.paused()).isFalse();

        String json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(active);
        assertThat(json).contains("\"isPaused\":false").doesNotContain("\"paused\"");
    }

    @Test
    void primaryFromTheMirroredStreamWithPrefill() {
        user.setMonthlyInvestment(650.0);
        InvestmentSchedule schedule = row(InvestmentSchedule.Status.ACTIVE);
        schedule.setDetectionState(DetectionState.CONFIRMED);
        schedule.setPrimaryStreamId(41L);
        schedule.setNextExpectedDate(LocalDate.of(2026, 10, 23));
        acmeStream(7L);

        PaycheckDetectionStatusResponse r = service.status(user, TODAY);

        assertThat(r.state()).isEqualTo("CONFIRMED");
        assertThat(r.mode()).isEqualTo("MANUAL"); // shadow writes the mirror, never the mode (R1)
        assertThat(r.monthlyGoal()).isEqualByComparingTo("650");
        assertThat(r.goalSource()).isEqualTo("survey");
        PaycheckDetectionStatusResponse.PrimaryStreamView p = r.primary();
        assertThat(p.streamId()).isEqualTo(41L);
        assertThat(p.rawDescriptor()).isEqualTo("ACME CORP PAYROLL");
        assertThat(p.cadence()).isEqualTo("BIWEEKLY");
        assertThat(p.cadenceLabel()).startsWith("Every other ");
        assertThat(p.paychecksPerYear()).isEqualTo(26);
        assertThat(p.factor()).isEqualByComparingTo("2.17");
        assertThat(p.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(p.suggestedAmount()).isEqualTo(300);   // round(650 / 2.17)
        assertThat(p.affordabilityCap()).isEqualTo(2176); // floor(0.90 x 2418)
        assertThat(p.prefillAmount()).isEqualTo(300);
        assertThat(p.lowConfidence()).isFalse();
        assertThat(p.needsChooser()).isFalse();
        assertThat(r.manualPrefill().amount()).isEqualByComparingTo("250.00"); // savedAmount wins
    }

    @Test
    void noPrimaryInSearchingNeedsConsentOrUndetectableEvenWithAnId() {
        InvestmentSchedule schedule = row(InvestmentSchedule.Status.ACTIVE);
        schedule.setPrimaryStreamId(41L);
        acmeStream(7L);
        for (DetectionState state : List.of(DetectionState.SEARCHING, DetectionState.NEEDS_CONSENT, DetectionState.UNDETECTABLE)) {
            schedule.setDetectionState(state);
            assertThat(service.status(user, TODAY).primary()).as(state.name()).isNull();
        }
        verify(streamRepository, never()).findById(anyLong());
    }

    @Test
    void anotherUsersStreamIsNeverShown() {
        InvestmentSchedule schedule = row(InvestmentSchedule.Status.ACTIVE);
        schedule.setDetectionState(DetectionState.PROVISIONAL);
        schedule.setPrimaryStreamId(41L);
        acmeStream(8L);

        assertThat(service.status(user, TODAY).primary()).isNull();
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckDetectionStatusServiceTest' --console=plain`. Expected: `Compilation failed`, `error: cannot find symbol` for `class PaycheckDetectionStatusResponse` and `class PaycheckDetectionStatusService`.

- [ ] **Step 3: The response record and the status service**

Create `M/dto/PaycheckDetectionStatusResponse.java`. Imports: `com.fasterxml.jackson.annotation.JsonProperty`, `java.math.BigDecimal`, `java.time.{LocalDate, LocalDateTime}`.

```java
package com.investingapp.backend.dto;

/** Spec 8.1. "isPaused" on the wire; a component named isPaused would give Jackson an is-getter. */
public record PaycheckDetectionStatusResponse(
        String mode, String state, String holdReason, String itemStatus,
        @JsonProperty("isPaused") boolean paused,
        boolean shadow, boolean optOut, LocalDateTime detectionStartedAt, boolean historyComplete,
        String fundingAccountLabel, String institutionName, BigDecimal monthlyGoal, String goalSource,
        PrimaryStreamView primary, BigDecimal savedAmount, String amountSource, Object authorization,
        ManualPrefillView manualPrefill) {

    public record PrimaryStreamView(
            Long streamId, String payerDisplayName, String rawDescriptor, int depositsFound,
            String cadence, String cadenceLabel, Integer paychecksPerYear, BigDecimal factor,
            BigDecimal lastDepositAmount, LocalDate lastDepositDate, LocalDate nextExpectedDate,
            Integer affordabilityCap, int suggestedAmount, int prefillAmount,
            boolean lowConfidence, boolean needsChooser) {
    }

    public record ManualPrefillView(String frequency, LocalDate nextInvestmentDate, BigDecimal amount) {
    }
}
```

Create `M/service/PaycheckDetectionStatusService.java`. Imports: `B.config.PaycheckAutopilotProperties`, `B.dto.PaycheckDetectionStatusResponse`, `B.dto.PaycheckDetectionStatusResponse.{ManualPrefillView, PrimaryStreamView}`, `B.model.{DetectionState, InvestmentFrequency, InvestmentSchedule, PaycheckStream, PaycheckStreamState, User}`, `B.paycheck.detect.{AmountPrefill, CadenceResult, PayCalendar, PrimarySelector}`, `B.repository.{InvestmentScheduleRepository, PaycheckStreamRepository}`, `B.util.EasternTime`, `S.stereotype.Service`, `java.math.BigDecimal`, `java.time.LocalDate`, `java.util.{EnumSet, List, Optional, Set}`.

```java
package com.investingapp.backend.service;

/** Spec 8.1 body from the database alone: never calls Plaid, never writes. */
@Service
public class PaycheckDetectionStatusService {

    private static final Set<DetectionState> NO_PRIMARY =
            EnumSet.of(DetectionState.SEARCHING, DetectionState.NEEDS_CONSENT, DetectionState.UNDETECTABLE);
    private static final Set<PaycheckStreamState> QUALIFYING =
            EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED);
    static final int MANUAL_DEFAULT_DAYS = 14;
    private static final String MASK_DOTS = "\u2022\u2022\u2022\u2022";

    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckAutopilotProperties props;

    public PaycheckDetectionStatusService(InvestmentScheduleRepository scheduleRepository,
            PaycheckStreamRepository streamRepository, PaycheckAutopilotProperties props) {
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.props = props;
    }

    public PaycheckDetectionStatusResponse status(User user) {
        return status(user, EasternTime.today());
    }

    PaycheckDetectionStatusResponse status(User user, LocalDate todayET) {
        InvestmentSchedule schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
        DetectionState state = schedule != null && schedule.getDetectionState() != null
                ? schedule.getDetectionState() : DetectionState.SEARCHING;
        BigDecimal share = props.getAffordabilityShare();
        // The goal does not depend on cadence; BIWEEKLY with no median yields it and its source.
        AmountPrefill.Prefill base =
                AmountPrefill.compute(user.getMonthlyInvestment(), InvestmentFrequency.BIWEEKLY, null, share);
        PrimaryStreamView primary = primaryStream(user, schedule, state)
                .map(stream -> primaryView(user, schedule, stream, share)).orElse(null);
        BigDecimal savedAmount = schedule == null ? null : schedule.getInvestmentAmount();

        return new PaycheckDetectionStatusResponse(
                schedule == null || schedule.getMode() == null ? "MANUAL" : schedule.getMode().name(),
                state.name(),
                schedule == null || schedule.getHoldReason() == null ? null : schedule.getHoldReason().name(),
                user.getPlaidItemStatus() == null ? null : user.getPlaidItemStatus().name(),
                schedule != null && schedule.getStatus() != InvestmentSchedule.Status.ACTIVE,
                props.isShadow(), Boolean.TRUE.equals(user.getPaycheckAutopilotOptOut()),
                schedule == null ? null : schedule.getDetectionStartedAt(),
                Boolean.TRUE.equals(user.getPlaidHistoryComplete()),
                fundingAccountLabel(user), user.getPlaidInstitutionName(),
                base.monthlyGoal(), base.goalSource(), primary, savedAmount, null, null,
                manualPrefill(primary, savedAmount, base, todayET));
    }

    private Optional<PaycheckStream> primaryStream(User user, InvestmentSchedule schedule, DetectionState state) {
        if (schedule == null || schedule.getPrimaryStreamId() == null || NO_PRIMARY.contains(state)) {
            return Optional.empty();
        }
        return streamRepository.findById(schedule.getPrimaryStreamId())
                .filter(stream -> user.getId().equals(stream.getUserId()));
    }

    private PrimaryStreamView primaryView(User user, InvestmentSchedule schedule, PaycheckStream stream,
            BigDecimal share) {
        InvestmentFrequency cadence = stream.getCadence();
        AmountPrefill.Prefill prefill = AmountPrefill.compute(user.getMonthlyInvestment(),
                cadence != null ? cadence : InvestmentFrequency.BIWEEKLY, stream.getTrailingMedian(), share);
        PrimarySelector.Selection selection = selection(user, prefill.monthlyGoal());
        LocalDate next = schedule.getNextExpectedDate() != null
                ? schedule.getNextExpectedDate() : stream.getPredictedNextDate();
        return new PrimaryStreamView(stream.getId(), stream.getPayerDisplayName(), stream.getRawDescriptor(),
                stream.getDepositsFound(), cadence == null ? null : cadence.name(), cadenceLabel(stream),
                cadence == null ? null : cadence.perYear(), cadence == null ? null : cadence.monthlyFactor(),
                stream.getLastAmount(), stream.getLastDate(), next,
                prefill.affordabilityCap(), prefill.suggestedAmount(), prefill.prefillAmount(),
                selection.lowConfidence(), selection.needsChooser());
    }

    private PrimarySelector.Selection selection(User user, BigDecimal monthlyGoal) {
        List<PaycheckStream> qualifying = streamRepository.findByUserIdOrderByIdAsc(user.getId()).stream()
                .filter(stream -> QUALIFYING.contains(stream.getState())).toList();
        return qualifying.isEmpty()
                ? new PrimarySelector.Selection(null, List.of(), false, false)
                : PrimarySelector.select(qualifying, monthlyGoal);
    }

    private static String cadenceLabel(PaycheckStream stream) {
        if (stream.getCadence() == null) {
            return null;
        }
        return PayCalendar.cadenceLabel(new CadenceResult(stream.getCadence(), stream.getCadenceSource(),
                stream.getAnchorDow() == null ? null : stream.getAnchorDow().intValue(),
                stream.getAnchorParityDate(), stream.getAnchorDay1(), stream.getAnchorDay2(), 0, true, 0));
    }

    private static ManualPrefillView manualPrefill(PrimaryStreamView primary, BigDecimal savedAmount,
            AmountPrefill.Prefill base, LocalDate todayET) {
        String frequency = primary != null && primary.cadence() != null
                ? primary.cadence() : InvestmentFrequency.BIWEEKLY.name();
        LocalDate next = primary != null && primary.nextExpectedDate() != null
                ? primary.nextExpectedDate() : todayET.plusDays(MANUAL_DEFAULT_DAYS);
        BigDecimal amount = savedAmount != null ? savedAmount
                : BigDecimal.valueOf(primary != null ? primary.prefillAmount() : base.suggestedAmount());
        return new ManualPrefillView(frequency, next, amount);
    }

    static String fundingAccountLabel(User user) {
        String name = user.getPlaidAccountName();
        String mask = user.getPlaidAccountMask();
        if (name == null || name.isBlank()) {
            return mask == null ? null : MASK_DOTS + mask;
        }
        return mask == null ? name : name + " " + MASK_DOTS + mask;
    }
}
```

Run the Step 2 command. Expected: `BUILD SUCCESSFUL`; `build/test-results/test/TEST-com.investingapp.backend.service.PaycheckDetectionStatusServiceTest.xml` shows `tests="5" skipped="0" failures="0" errors="0"`.

- [ ] **Step 4: Failing test for the INIT enqueue**

Create `T/service/InitSyncEnqueuerTest.java`. Imports: `B.config.PaycheckAutopilotProperties`, `B.model.{InvestmentSchedule, PlaidItemStatus, ScheduleMode, User}`, `B.repository.{InvestmentScheduleRepository, UserRepository}`, `B.service.PlaidTransactionsSyncService.SyncReason`, `B.support.RecordingTransactionManager`, `jakarta.persistence.{EntityManager, LockModeType}`, `S.core.task.TaskRejectedException`, `S.test.util.ReflectionTestUtils`, `S.transaction.support.TransactionSynchronizationManager`, `java.util.{ArrayList, List, Optional}`, `java.util.concurrent.Executor`, `java.util.function.Consumer`, `java.util.concurrent.atomic.AtomicBoolean`.

```java
package com.investingapp.backend.service;

class InitSyncEnqueuerTest {

    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    private final List<Runnable> queued = new ArrayList<>();
    private User user;

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(props.isShadow()).thenReturn(true);
        user = eligibleUser();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
    }

    static User eligibleUser() {
        User u = new User("init@fredvested.com");
        u.setId(7L);
        u.setAccountStatus("ACTIVE");
        u.setPlaidAccessToken("ENC:token");
        u.setPlaidItemId("item-1");
        u.setPlaidItemStatus(PlaidItemStatus.OK);
        u.setPaycheckAutopilotOptOut(false);
        return u;
    }

    private InvestmentSchedule manualRow() {
        InvestmentSchedule row = new InvestmentSchedule();
        row.setId(70L);
        row.setUser(user);
        row.setMode(ScheduleMode.MANUAL);
        row.setStatus(InvestmentSchedule.Status.ACTIVE);
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(row));
        when(scheduleRepository.save(row)).thenAnswer(inv -> {
            assertThat(txManager.open).isTrue();
            return row;
        });
        return row;
    }

    private InitSyncEnqueuer enqueuer(Executor executor) {
        InitSyncEnqueuer enqueuer = new InitSyncEnqueuer(scheduleRepository, userRepository, pipeline, props,
                txManager, executor);
        ReflectionTestUtils.setField(enqueuer, "entityManager", entityManager);
        return enqueuer;
    }

    @Test
    void firstCallStampsAndEnqueuesInitOnce() {
        InvestmentSchedule row = manualRow();
        InitSyncEnqueuer enqueuer = enqueuer(queued::add);

        assertThat(enqueuer.enqueueIfNeeded(user)).isTrue();
        assertThat(row.getDetectionStartedAt()).isNotNull();
        assertThat(queued).hasSize(1);
        verify(entityManager).refresh(user);
        verify(entityManager).refresh(row, LockModeType.PESSIMISTIC_WRITE);

        assertThat(enqueuer.enqueueIfNeeded(user)).isFalse(); // stamped: never asked again from here
        queued.get(0).run();
        assertThat(enqueuer.enqueueIfNeeded(user)).isFalse();

        assertThat(queued).hasSize(1);
        verify(pipeline, times(1)).run(7L, "item-1", SyncReason.INIT);
        verify(scheduleRepository, times(1)).save(row);
    }

    @Test
    void excludedUserGetsNoInit() {
        InitSyncEnqueuer enqueuer = enqueuer(queued::add);
        List<Consumer<User>> exclusions = List.of(u -> u.setAccountStatus("REJECTED"),
                u -> u.setPaycheckAutopilotOptOut(true), u -> u.setPlaidAccessToken(null),
                u -> u.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED),
                u -> u.setPlaidTransactionsConsented(false), u -> u.setPlaidSyncCursor("cursor-1"));
        for (Consumer<User> exclusion : exclusions) {
            User excluded = eligibleUser();
            exclusion.accept(excluded);
            assertThat(enqueuer.enqueueIfNeeded(excluded)).isFalse();
        }
        when(props.isEnabled()).thenReturn(false);
        assertThat(enqueuer.enqueueIfNeeded(eligibleUser())).isFalse();

        assertThat(queued).isEmpty();
        verifyNoInteractions(pipeline, scheduleRepository);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void enqueueWithoutTransactionSubmitsDirectly() {
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        List<Boolean> synchronizationAtSubmit = new ArrayList<>();
        InitSyncEnqueuer enqueuer = enqueuer(task -> {
            synchronizationAtSubmit.add(TransactionSynchronizationManager.isSynchronizationActive());
            queued.add(task);
        });

        assertThat(enqueuer.enqueueIfNeeded(user)).isTrue();
        assertThat(synchronizationAtSubmit).containsExactly(false);
        assertThat(enqueuer.enqueueIfNeeded(user)).as("in flight").isFalse();
        queued.get(0).run();
        assertThat(enqueuer.enqueueIfNeeded(user)).as("still no cursor: asked again").isTrue();

        verify(userRepository, never()).findByIdForUpdate(anyLong());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void plaidCallsOutsideTransaction() {
        manualRow();
        when(pipeline.run(7L, "item-1", SyncReason.INIT)).thenAnswer(inv -> {
            assertThat(txManager.open).as("INIT starts only after the stamp commits").isFalse();
            return null;
        });

        assertThat(enqueuer(Runnable::run).enqueueIfNeeded(user)).isTrue();

        verify(pipeline).run(7L, "item-1", SyncReason.INIT);
    }

    @Test
    void stampRecheckUnderLockSkipsAWinningSync() {
        InvestmentSchedule row = manualRow();
        User locked = eligibleUser();
        locked.setPlaidSyncCursor("cursor-won-meanwhile");
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(locked));

        assertThat(enqueuer(queued::add).enqueueIfNeeded(user)).isFalse();

        assertThat(row.getDetectionStartedAt()).isNull();
        assertThat(queued).isEmpty();
    }

    @Test
    void rejectedSubmissionFreesTheUser() {
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        AtomicBoolean poolFull = new AtomicBoolean(true);
        InitSyncEnqueuer enqueuer = enqueuer(task -> {
            if (poolFull.get()) {
                throw new TaskRejectedException("queue full");
            }
            queued.add(task);
        });

        assertThat(enqueuer.enqueueIfNeeded(user)).isFalse();
        poolFull.set(false);
        assertThat(enqueuer.enqueueIfNeeded(user)).isTrue();
        assertThat(queued).hasSize(1);
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InitSyncEnqueuerTest' --console=plain`. Expected: `Compilation failed`, `error: cannot find symbol` for `class InitSyncEnqueuer`.

- [ ] **Step 5: InitSyncEnqueuer**

Create `M/service/InitSyncEnqueuer.java`. Imports: `B.config.PaycheckAutopilotProperties`, `B.model.{InvestmentSchedule, User}`, `B.repository.{InvestmentScheduleRepository, UserRepository}`, `B.service.PlaidTransactionsSyncService.SyncReason`, `B.util.EasternTime`, `jakarta.persistence.{EntityManager, LockModeType, PersistenceContext}`, `org.slf4j.{Logger, LoggerFactory}`, `S.beans.factory.annotation.Qualifier`, `S.stereotype.Service`, `S.transaction.PlatformTransactionManager`, `S.transaction.support.{TransactionSynchronization, TransactionSynchronizationManager, TransactionTemplate}`, `java.util.{Optional, Set}`, `java.util.concurrent.{ConcurrentHashMap, Executor}`.

```java
package com.investingapp.backend.service;

/** First /transactions/sync from the status endpoint (spec 8.1, R4a); writes only detection_started_at. */
@Service
public class InitSyncEnqueuer {

    private static final Logger logger = LoggerFactory.getLogger(InitSyncEnqueuer.class);

    private final InvestmentScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final PaycheckSyncPipeline pipeline;
    private final PaycheckAutopilotProperties props;
    private final TransactionTemplate writeTx;
    private final Executor plaidExecutor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @PersistenceContext
    private EntityManager entityManager;

    public InitSyncEnqueuer(InvestmentScheduleRepository scheduleRepository, UserRepository userRepository,
            PaycheckSyncPipeline pipeline, PaycheckAutopilotProperties props,
            PlatformTransactionManager transactionManager,
            @Qualifier("plaidExecutor") Executor plaidExecutor) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.pipeline = pipeline;
        this.props = props;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.plaidExecutor = plaidExecutor;
    }

    public boolean enqueueIfNeeded(User user) {
        if (DetectionEligibility.exclusion(user, props).isPresent()
                || user.getPlaidSyncCursor() != null
                || Boolean.FALSE.equals(user.getPlaidTransactionsConsented())) {
            return false;
        }
        String itemId = user.getPlaidItemId();
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        Optional<InvestmentSchedule> row = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        if (row.isPresent() && row.get().getDetectionStartedAt() != null) {
            return false;
        }
        Long userId = user.getId();
        if (!inFlight.add(userId)) {
            return false;
        }
        boolean claimed;
        try {
            claimed = row.isPresent()
                    ? Boolean.TRUE.equals(writeTx.execute(status -> stampAndSchedule(userId, itemId)))
                    : submitAfterCommitOrNow(userId, itemId);
        } catch (RuntimeException e) {
            inFlight.remove(userId);
            logger.warn("paycheck.init.enqueue_failed user_id={} error_class={}", userId, e.getClass().getSimpleName());
            return false;
        }
        if (!claimed) {
            inFlight.remove(userId);
        }
        return claimed;
    }

    private boolean stampAndSchedule(Long userId, String itemId) {
        User locked = userRepository.findByIdForUpdate(userId).orElse(null);
        if (locked == null) {
            return false;
        }
        // Open-in-view hands back cached copies unread (see PlaidService): refresh both, so the re-check
        // is real and the full-row save never writes a stale status over a fresh pause.
        entityManager.refresh(locked);
        if (locked.getPlaidSyncCursor() != null || !itemId.equals(locked.getPlaidItemId())) {
            return false;
        }
        InvestmentSchedule schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(locked).orElse(null);
        if (schedule == null) {
            return submitAfterCommitOrNow(userId, itemId);
        }
        entityManager.refresh(schedule, LockModeType.PESSIMISTIC_WRITE);
        if (schedule.getDetectionStartedAt() != null) {
            return false;
        }
        schedule.setDetectionStartedAt(EasternTime.now());
        scheduleRepository.save(schedule);
        return submitAfterCommitOrNow(userId, itemId);
    }

    private boolean submitAfterCommitOrNow(Long userId, String itemId) {
        Runnable init = () -> runInit(userId, itemId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(userId, init);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        inFlight.remove(userId);
                    }
                }
            });
            return true;
        }
        return submit(userId, init);
    }

    private boolean submit(Long userId, Runnable init) {
        try {
            plaidExecutor.execute(init);
        } catch (RuntimeException e) {
            // plaidExecutor keeps AbortPolicy; a full queue must not fail the status call.
            inFlight.remove(userId);
            logger.warn("paycheck.init.rejected user_id={} error_class={}", userId, e.getClass().getSimpleName());
            return false;
        }
        logger.info("paycheck.init.enqueued");
        return true;
    }

    private void runInit(Long userId, String itemId) {
        try {
            pipeline.run(userId, itemId, SyncReason.INIT);
        } catch (RuntimeException e) {
            logger.warn("paycheck.init.failed user_id={} error_class={}", userId, e.getClass().getSimpleName());
        } finally {
            inFlight.remove(userId);
        }
    }
}
```

The `Boolean.FALSE` check goes beyond the contract (`syncItem` refuses that Item anyway). A stamped row whose submit is rejected is initialized by the R4b reconcile that night.

Run the Step 4 command. Expected: `BUILD SUCCESSFUL`, `tests="6" skipped="0" failures="0" errors="0"`.

- [ ] **Step 6: Failing test for the status controller**

Create `T/controller/PaycheckDetectionControllerTest.java`. Imports: `B.config.PaycheckAutopilotProperties`, `B.dto.PaycheckDetectionStatusResponse`, `B.model.{InvestmentSchedule, PlaidItemStatus, ScheduleMode, User}`, `B.repository.{InvestmentScheduleRepository, PaycheckStreamRepository, UserRepository}`, `B.security.services.UserDetailsImpl`, `B.service.{InitSyncEnqueuer, PaycheckDetectionStatusService, PaycheckSyncPipeline}`, `B.support.RecordingTransactionManager`, `jakarta.persistence.EntityManager`, `S.http.{HttpStatus, ResponseEntity}`, `S.security.authentication.UsernamePasswordAuthenticationToken`, `S.security.core.Authentication`, `S.test.util.ReflectionTestUtils`, `java.math.BigDecimal`, `java.util.{ArrayList, List, Optional}`.

```java
package com.investingapp.backend.controller;

class PaycheckDetectionControllerTest {

    private static final String EMAIL = "status@fredvested.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final List<Runnable> queued = new ArrayList<>();
    private PaycheckDetectionStatusService statusService;
    private User user;

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        statusService = new PaycheckDetectionStatusService(scheduleRepository, mock(PaycheckStreamRepository.class), props);
        user = new User(EMAIL);
        // [the six setter lines of InitSyncEnqueuerTest.eligibleUser() (Step 4), with user. for u.]
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
    }

    private static Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(new UserDetailsImpl(7L, EMAIL, List.of()), null, List.of());
    }

    private PaycheckDetectionController controller() {
        InitSyncEnqueuer enqueuer = new InitSyncEnqueuer(scheduleRepository, userRepository, pipeline, props,
                new RecordingTransactionManager(), queued::add);
        ReflectionTestUtils.setField(enqueuer, "entityManager", mock(EntityManager.class));
        return new PaycheckDetectionController(userRepository, enqueuer, statusService);
    }

    @Test
    void responseNeverWaitsOnPlaid() {
        InvestmentSchedule row = new InvestmentSchedule();
        row.setId(70L);
        row.setUser(user);
        row.setMode(ScheduleMode.MANUAL);
        row.setStatus(InvestmentSchedule.Status.ACTIVE);
        row.setInvestmentAmount(new BigDecimal("100.00"));
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(row));

        ResponseEntity<?> response = controller().status(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaycheckDetectionStatusResponse body = (PaycheckDetectionStatusResponse) response.getBody();
        assertThat(body.state()).isEqualTo("SEARCHING");
        assertThat(body.detectionStartedAt()).isNotNull();
        assertThat(queued).hasSize(1);
        verifyNoInteractions(pipeline);
    }

    @Test
    void unauthenticatedIs401() {
        assertThat(controller().status(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownUserIs404() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(controller().status(authentication()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(queued).isEmpty();
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.PaycheckDetectionControllerTest' --console=plain`. Expected: `Compilation failed`, `error: cannot find symbol` for `class PaycheckDetectionController`.

- [ ] **Step 7: PaycheckDetectionController**

Create `M/controller/PaycheckDetectionController.java`. Imports: `B.dto.MessageResponse`, `B.model.User`, `B.repository.UserRepository`, `B.security.services.UserDetailsImpl`, `B.service.{InitSyncEnqueuer, PaycheckDetectionStatusService}`, `S.http.{HttpStatus, ResponseEntity}`, `S.security.core.Authentication`, `S.web.bind.annotation.{CrossOrigin, GetMapping, RequestMapping, RestController}`.

```java
package com.investingapp.backend.controller;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/paycheck-detection")
public class PaycheckDetectionController {

    private final UserRepository userRepository;
    private final InitSyncEnqueuer initSyncEnqueuer;
    private final PaycheckDetectionStatusService statusService;

    public PaycheckDetectionController(UserRepository userRepository, InitSyncEnqueuer initSyncEnqueuer,
            PaycheckDetectionStatusService statusService) {
        this.userRepository = userRepository;
        this.initSyncEnqueuer = initSyncEnqueuer;
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl userDetails)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("User not authenticated"));
        }
        User user = userRepository.findById(userDetails.getId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("User not found in database"));
        }
        initSyncEnqueuer.enqueueIfNeeded(user);
        return ResponseEntity.ok(statusService.status(user));
    }
}
```

Run the Step 6 command. Expected: `BUILD SUCCESSFUL`, `tests="3"`, 0 skipped, 0 failures.

- [ ] **Step 8: Failing test for update-mode completion**

Create `T/service/PlaidUpdateModeServiceTest.java`. Imports: `B.model.{PlaidItemStatus, User}`, `B.plaid.{PlaidApiError, PlaidCallResult, PlaidErrorClass, PlaidGateway}`, `B.service.PlaidItemStateService.{ItemStateResult, RepairPath}`, `B.service.PlaidTransactionsSyncService.SyncReason`, `B.service.PlaidUpdateModeService.UpdateModeResult`, `com.plaid.client.model.{ItemGetResponse, ItemWebhookUpdateResponse, ItemWithConsentFields, PlaidError, Products}`, `com.plaid.client.request.PlaidApi`, `org.mockito.InOrder`, `S.transaction.annotation.Transactional`, `S.transaction.support.TransactionSynchronizationManager`, `java.time.{OffsetDateTime, ZoneOffset}`, `java.util.{ArrayList, List}`.

```java
package com.investingapp.backend.service;

class PlaidUpdateModeServiceTest {

    private static final String WEBHOOK = "https://hooks.example.invalid/api/plaid/webhook";
    private static final OffsetDateTime EXPIRES = OffsetDateTime.of(2027, 4, 1, 14, 0, 0, 0, ZoneOffset.UTC);

    private final PlaidApi plaidApi = mock(PlaidApi.class);
    private final PlaidGateway gateway = mock(PlaidGateway.class);
    private final EncryptionService encryptionService = mock(EncryptionService.class);
    private final PlaidItemStateService itemState = mock(PlaidItemStateService.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final List<Runnable> queued = new ArrayList<>();
    private PlaidUpdateModeService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PlaidUpdateModeService(plaidApi, gateway, encryptionService, itemState, pipeline, queued::add, WEBHOOK);
        user = new User("reconnect@fredvested.com");
        user.setId(7L);
        user.setPlaidAccessToken("ENC:tok");
        user.setPlaidItemId("item-1");
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        when(encryptionService.decrypt("ENC:tok")).thenReturn("raw-token");
        when(itemState.onConsentRenewed(anyLong(), anyString(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemState.onConsentGranted(anyLong(), anyString())).thenReturn(ItemStateResult.APPLIED);
        when(itemState.onConsentRequired(anyLong(), anyString())).thenReturn(ItemStateResult.APPLIED);
        when(itemState.onLoginRepaired(anyLong(), anyString(), any())).thenReturn(ItemStateResult.APPLIED);
    }

    private static ItemWithConsentFields item(String itemId, boolean transactions) {
        return new ItemWithConsentFields().itemId(itemId)
                .consentedProducts(transactions ? List.of(Products.AUTH, Products.TRANSACTIONS) : List.of(Products.AUTH))
                .consentExpirationTime(EXPIRES);
    }

    private void plaidAnswers(ItemWithConsentFields item) {
        doAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PlaidCallResult<>(new ItemGetResponse().item(item), null);
        }).when(gateway).call(eq("item.get"), any());
        doAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PlaidCallResult<>(new ItemWebhookUpdateResponse(), null);
        }).when(gateway).call(eq("item.webhook_update"), any());
    }

    @Test
    void updateModeCompleteReReadsConsent() {
        user.setPlaidTransactionsConsented(false);
        plaidAnswers(item("item-1", true));

        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.CONSENT_GRANTED);

        verify(plaidApi).itemGet(argThat(r -> "raw-token".equals(r.getAccessToken())));
        verify(plaidApi).itemWebhookUpdate(argThat(r -> "raw-token".equals(r.getAccessToken()) && WEBHOOK.equals(r.getWebhook())));
        verify(itemState).onConsentGranted(7L, "item-1");
        verify(itemState, never()).onConsentRequired(anyLong(), anyString());
    }

    @Test
    void absentConsentIsMissingUnlessTheFlagWasUnknown() {
        plaidAnswers(item("item-1", false)); // NULL flag: one sync is the authoritative test (spec 2.3)
        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.NO_CHANGE);
        verify(itemState, never()).onConsentRequired(anyLong(), anyString());

        user.setPlaidTransactionsConsented(true); // the scope was dropped inside update mode
        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.CONSENT_STILL_MISSING);
        verify(itemState).onConsentRequired(7L, "item-1");
        verify(itemState, never()).onConsentGranted(anyLong(), anyString());
    }

    @Test
    void newConsentDoesNotEnqueueInit() {
        plaidAnswers(item("item-1", true));

        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.CONSENT_GRANTED);
        verifyNoInteractions(pipeline);
        assertThat(queued).isEmpty();
    }

    @Test
    void updateModeClearsPendingDisconnect() {
        user.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
        user.setPlaidTransactionsConsented(true);
        plaidAnswers(item("item-1", true));

        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.NO_CHANGE);
        verify(itemState).onConsentRenewed(7L, "item-1", EXPIRES);
        verify(itemState, never()).onConsentGranted(anyLong(), anyString());
        verify(itemState, never()).onLoginRepaired(anyLong(), anyString(), any());
    }

    @Test
    void repairPathOnlyFromLoginRequired() {
        user.setPlaidTransactionsConsented(true);
        plaidAnswers(item("item-1", true));
        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.NO_CHANGE);
        verify(itemState, never()).onLoginRepaired(anyLong(), anyString(), any());

        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        assertThat(service.complete(user)).as("no cursor yet").isEqualTo(UpdateModeResult.REPAIRED);
        assertThat(queued).isEmpty();

        user.setPlaidSyncCursor("cursor-1");
        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.REPAIRED);
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(pipeline).run(7L, "item-1", SyncReason.MANUAL);
        verify(itemState, times(2)).onLoginRepaired(7L, "item-1", RepairPath.UPDATE_MODE);
    }

    @Test
    void staleItemWritesNothing() {
        plaidAnswers(item("item-2", true));
        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.STALE_ITEM);
        verifyNoInteractions(itemState, pipeline);
        verify(plaidApi, never()).itemWebhookUpdate(any());

        plaidAnswers(item("item-1", true)); // a relink between /item/get and the handler's lock
        when(itemState.onConsentGranted(anyLong(), anyString())).thenReturn(ItemStateResult.STALE_ITEM);
        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.STALE_ITEM);
        verify(itemState, never()).onConsentRenewed(anyLong(), anyString(), any());
    }

    @Test
    void plaidErrorWritesNothing() {
        doReturn(new PlaidCallResult<>(null, new PlaidApiError("ITEM_ERROR", "ITEM_LOGIN_REQUIRED", null, "req-1", 400,
                PlaidErrorClass.ITEM_BROKEN))).when(gateway).call(eq("item.get"), any());
        assertThat(service.complete(user)).isEqualTo(UpdateModeResult.PLAID_ERROR);

        plaidAnswers(item("item-1", true).error(new PlaidError().errorCode("ITEM_LOGIN_REQUIRED")));
        assertThat(service.complete(user)).as("the Item still reports an error").isEqualTo(UpdateModeResult.PLAID_ERROR);

        verifyNoInteractions(itemState, pipeline);
    }

    @Test
    void noItemIsUpdateModeUnavailable() {
        user.setPlaidAccessToken(null);

        assertThatThrownBy(() -> service.complete(user)).isInstanceOf(UpdateModeUnavailableException.class);
        verifyNoInteractions(gateway, itemState);
    }

    @Test
    void plaidCallsOutsideTransaction() throws Exception {
        assertThat(PlaidUpdateModeService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(PlaidUpdateModeService.class.getMethod("complete", User.class).isAnnotationPresent(Transactional.class))
                .isFalse();
        plaidAnswers(item("item-1", true));

        service.complete(user);

        InOrder order = inOrder(gateway, itemState);
        order.verify(gateway).call(eq("item.get"), any());
        order.verify(gateway).call(eq("item.webhook_update"), any());
        order.verify(itemState).onConsentGranted(7L, "item-1");
        order.verify(itemState).onConsentRenewed(7L, "item-1", EXPIRES); // last: granting nulls the expiry
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidUpdateModeServiceTest' --console=plain`. Expected: `Compilation failed`, `error: cannot find symbol` for `class PlaidUpdateModeService`.

- [ ] **Step 9: PlaidUpdateModeService**

Create `M/service/PlaidUpdateModeService.java`. Imports: `B.model.{PlaidItemStatus, User}`, `B.plaid.{PlaidCallResult, PlaidGateway}`, `B.service.PlaidItemStateService.{ItemStateResult, RepairPath}`, `B.service.PlaidTransactionsSyncService.SyncReason`, `com.plaid.client.model.{ItemGetRequest, ItemGetResponse, ItemWebhookUpdateRequest, ItemWebhookUpdateResponse, ItemWithConsentFields, Products}`, `com.plaid.client.request.PlaidApi`, `org.slf4j.{Logger, LoggerFactory}`, `S.beans.factory.annotation.{Qualifier, Value}`, `S.stereotype.Service`, `java.util.List`, `java.util.concurrent.Executor`.

```java
package com.investingapp.backend.service;

/** Server half of an update-mode Link (spec 2.3, 8.8). No transaction; writes only through PlaidItemStateService. */
@Service
public class PlaidUpdateModeService {

    public enum UpdateModeResult {
        CONSENT_GRANTED, CONSENT_STILL_MISSING, REPAIRED, NO_CHANGE, STALE_ITEM, PLAID_ERROR
    }

    private static final Logger logger = LoggerFactory.getLogger(PlaidUpdateModeService.class);

    private final PlaidApi plaidApi;
    private final PlaidGateway gateway;
    private final EncryptionService encryptionService;
    private final PlaidItemStateService itemStateService;
    private final PaycheckSyncPipeline pipeline;
    private final Executor plaidExecutor;
    private final String plaidWebhookUrl;

    public PlaidUpdateModeService(PlaidApi plaidApi, PlaidGateway gateway, EncryptionService encryptionService,
            PlaidItemStateService itemStateService, PaycheckSyncPipeline pipeline,
            @Qualifier("plaidExecutor") Executor plaidExecutor,
            @Value("${plaid.webhook.url}") String plaidWebhookUrl) {
        this.plaidApi = plaidApi;
        this.gateway = gateway;
        this.encryptionService = encryptionService;
        this.itemStateService = itemStateService;
        this.pipeline = pipeline;
        this.plaidExecutor = plaidExecutor;
        this.plaidWebhookUrl = plaidWebhookUrl;
    }

    public UpdateModeResult complete(User user) {
        String token = user.getPlaidAccessToken();
        String storedItemId = user.getPlaidItemId();
        if (token == null || token.isBlank() || storedItemId == null || storedItemId.isBlank()) {
            throw new UpdateModeUnavailableException("No Plaid Item on file to complete update mode for");
        }
        Long userId = user.getId();
        String rawToken = encryptionService.decrypt(token);

        PlaidCallResult<ItemGetResponse> got =
                gateway.call("item.get", plaidApi.itemGet(new ItemGetRequest().accessToken(rawToken)));
        ItemWithConsentFields item = got.ok() && got.body() != null ? got.body().getItem() : null;
        if (item == null || item.getItemId() == null) {
            logger.warn("plaid.update_mode.item_get_failed user_id={} code={}", userId,
                    got.error() == null ? "NO_ITEM" : got.error().errorCode());
            return UpdateModeResult.PLAID_ERROR;
        }
        String itemId = item.getItemId();
        if (!itemId.equals(storedItemId)) {
            return finish(UpdateModeResult.STALE_ITEM); // a relink won; nothing here applies
        }
        if (item.getError() != null) {
            logger.warn("plaid.update_mode.item_still_errored user_id={} code={}", userId, item.getError().getErrorCode());
            return UpdateModeResult.PLAID_ERROR;
        }

        PlaidCallResult<ItemWebhookUpdateResponse> hook = gateway.call("item.webhook_update",
                plaidApi.itemWebhookUpdate(new ItemWebhookUpdateRequest().accessToken(rawToken).webhook(plaidWebhookUrl)));
        if (!hook.ok()) {
            // Not fatal: the consent and repair writes still apply; the reconcile's /item/get covers it.
            logger.warn("plaid.update_mode.webhook_update_failed user_id={} code={}", userId,
                    hook.error() == null ? "UNKNOWN" : hook.error().errorCode());
        }

        List<Products> consented = item.getConsentedProducts();
        boolean consentedNow = consented != null && consented.contains(Products.TRANSACTIONS);
        Boolean consentBefore = user.getPlaidTransactionsConsented();
        boolean wasLoginRequired = user.getPlaidItemStatus() == PlaidItemStatus.LOGIN_REQUIRED;
        boolean hasCursor = user.getPlaidSyncCursor() != null;

        UpdateModeResult result = UpdateModeResult.NO_CHANGE;
        ItemStateResult step = ItemStateResult.APPLIED;
        if (consentedNow && !Boolean.TRUE.equals(consentBefore)) {
            step = itemStateService.onConsentGranted(userId, itemId);
            result = UpdateModeResult.CONSENT_GRANTED;
        } else if (!consentedNow && consentBefore != null) {
            // FALSE stays refused; TRUE means the scope was dropped inside update mode; NULL stays unknown.
            step = itemStateService.onConsentRequired(userId, itemId);
            result = UpdateModeResult.CONSENT_STILL_MISSING;
        }
        // Every success renews the expiry after granting (which nulls it); PENDING_DISCONNECT to OK (2.3).
        if (step != ItemStateResult.STALE_ITEM) {
            step = itemStateService.onConsentRenewed(userId, itemId, item.getConsentExpirationTime());
        }
        if (step != ItemStateResult.STALE_ITEM && wasLoginRequired) {
            step = itemStateService.onLoginRepaired(userId, itemId, RepairPath.UPDATE_MODE);
            if (step == ItemStateResult.APPLIED) {
                result = UpdateModeResult.REPAIRED;
                if (hasCursor) {
                    submitResync(userId, itemId);
                }
            }
        }
        return finish(step == ItemStateResult.STALE_ITEM ? UpdateModeResult.STALE_ITEM : result);
    }

    private UpdateModeResult finish(UpdateModeResult result) {
        logger.info("plaid.update_mode.complete result={}", result);
        return result;
    }

    private void submitResync(Long userId, String itemId) {
        try {
            plaidExecutor.execute(() -> {
                try {
                    pipeline.run(userId, itemId, SyncReason.MANUAL);
                } catch (RuntimeException e) {
                    logger.warn("plaid.update_mode.resync_failed user_id={} error_class={}", userId, e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            logger.warn("plaid.update_mode.resync_rejected user_id={} error_class={}", userId, e.getClass().getSimpleName());
        }
    }
}
```

Run the Step 8 command. Expected: `BUILD SUCCESSFUL`, `tests="9"`, 0 skipped, 0 failures.

- [ ] **Step 10: Failing cases for `primary-bank-account`, then the two fields**

In `T/controller/PlaidControllerPrimaryBankAccountTest.java`, add `import java.time.LocalDateTime;` before `:16` (`import java.util.List;`) and insert before the class's final `}` (HEAD `:116`):

```java

    @Test
    void primaryBankAccountCarriesConsentExpiresAtAndConsentFlag() {
        user.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
        user.setPlaidDisconnectAt(LocalDateTime.of(2026, 12, 1, 9, 0));
        user.setPlaidTransactionsConsented(false);

        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response))
                .containsEntry("itemStatus", "PENDING_DISCONNECT")
                .containsEntry("consentExpiresAt", "2026-12-01T09:00-05:00")
                .containsEntry("transactionsConsented", false);
    }

    @Test
    void unknownConsentAndNoExpiryAnswerNull() {
        Map<String, Object> body = body(controller.getPrimaryBankAccount(authentication()));

        assertThat(body).containsKey("consentExpiresAt").containsKey("transactionsConsented");
        assertThat(body.get("consentExpiresAt")).isNull();
        assertThat(body.get("transactionsConsented")).isNull();
    }
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.PlaidControllerPrimaryBankAccountTest' --console=plain`. Expected: compiles; 4 pass, the 2 new fail naming `consentExpiresAt`.

In `M/controller/PlaidController.java`, add `import com.investingapp.backend.util.EasternTime;` after `:11` (`import com.investingapp.backend.service.UpdateModeUnavailableException;`); after `:143`, exactly `            bankAccount.put("itemStatus", user.getPlaidItemStatus().name());`, insert:

```java
            // Phase 1 (spec 8.8, R10): the reconnect card reads both. consentExpiresAt is
            // users.plaid_disconnect_at, an Eastern wall time, sent with its offset.
            bankAccount.put("transactionsConsented", user.getPlaidTransactionsConsented());
            bankAccount.put("consentExpiresAt", user.getPlaidDisconnectAt() == null ? null
                    : user.getPlaidDisconnectAt().atZone(EasternTime.ZONE).toOffsetDateTime().toString());
```

Run the same command. Expected: `BUILD SUCCESSFUL`, `tests="6"`, 0 failures.

- [ ] **Step 11: Failing controller test for `update-mode-complete`, then the endpoint**

Create `T/controller/PlaidControllerUpdateModeCompleteTest.java`. Imports: `B.dto.ErrorCodeResponse`, `B.model.User`, `B.repository.UserRepository`, `B.security.services.UserDetailsImpl`, `B.service.{PlaidUpdateModeService, UpdateModeUnavailableException}`, `B.service.PlaidUpdateModeService.UpdateModeResult`, `S.http.{HttpStatus, ResponseEntity}`, `S.security.authentication.UsernamePasswordAuthenticationToken`, `S.security.core.Authentication`, `S.test.util.ReflectionTestUtils`, `java.util.{List, Map, Optional}`.

```java
package com.investingapp.backend.controller;

class PlaidControllerUpdateModeCompleteTest {

    private static final String EMAIL = "reconnect@fredvested.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PlaidUpdateModeService updateModeService = mock(PlaidUpdateModeService.class);
    private PlaidController controller;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new PlaidController();
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "plaidUpdateModeService", updateModeService);
        user = new User(EMAIL);
        user.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
    }

    private static Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(new UserDetailsImpl(7L, EMAIL, List.of()), null, List.of());
    }

    private String code(ResponseEntity<?> response) {
        return ((ErrorCodeResponse) response.getBody()).code();
    }

    @Test
    void resultsMapToStatusCodes() {
        for (UpdateModeResult ok : List.of(UpdateModeResult.CONSENT_GRANTED, UpdateModeResult.CONSENT_STILL_MISSING,
                UpdateModeResult.REPAIRED, UpdateModeResult.NO_CHANGE)) {
            when(updateModeService.complete(user)).thenReturn(ok);
            ResponseEntity<?> response = controller.completeUpdateMode(authentication());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(Map.of("result", ok.name()));
        }
        when(updateModeService.complete(user)).thenReturn(UpdateModeResult.STALE_ITEM);
        ResponseEntity<?> stale = controller.completeUpdateMode(authentication());
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(stale)).isEqualTo("STALE_ITEM");

        when(updateModeService.complete(user)).thenReturn(UpdateModeResult.PLAID_ERROR);
        ResponseEntity<?> plaid = controller.completeUpdateMode(authentication());
        assertThat(plaid.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(code(plaid)).isEqualTo("PLAID_ERROR");
    }

    @Test
    void noItemIs409UpdateModeUnavailable() {
        when(updateModeService.complete(user)).thenThrow(new UpdateModeUnavailableException("No Plaid Item"));

        ResponseEntity<?> response = controller.completeUpdateMode(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(response)).isEqualTo("UPDATE_MODE_UNAVAILABLE");
    }

    // [unauthenticatedIs401 and unknownUserIs404 of PaycheckDetectionControllerTest (Step 6), calling
    //  controller.completeUpdateMode(...) for controller().status(...), each ending with
    //  verifyNoInteractions(updateModeService) (in the 404 one it replaces the queue check)]
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.PlaidControllerUpdateModeCompleteTest' --console=plain`. Expected: `Compilation failed`, `error: cannot find symbol` for `method completeUpdateMode(Authentication)`.

In `PlaidController.java`: add `import com.investingapp.backend.service.PlaidUpdateModeService;` after `:10` (`import com.investingapp.backend.service.PlaidService;`); after `:39` (`    private UserRepository userRepository;`) insert

```java

    @Autowired
    private PlaidUpdateModeService plaidUpdateModeService;
```

and after the `}` closing `exchangePublicTokenAuthenticated` (HEAD `:120`, two lines below `.body(new MessageResponse("Error exchanging Plaid public token: " + e.getMessage()));`) insert:

```java

    /** After an update-mode Link onSuccess (spec 2.3, 8.8): no body, no exchange. */
    @PostMapping("/update-mode-complete")
    public ResponseEntity<?> completeUpdateMode(Authentication authentication) {
        // [the eight lines of PaycheckDetectionController.status from "if (authentication == null" through
        //  the 404 return's closing brace (Step 7), verbatim: 401 MessageResponse, findById, 404 MessageResponse]
        try {
            PlaidUpdateModeService.UpdateModeResult result = plaidUpdateModeService.complete(user);
            return switch (result) {
                case STALE_ITEM -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorCodeResponse(
                        "STALE_ITEM", "This bank connection changed while you were reconnecting. Try again."));
                case PLAID_ERROR -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorCodeResponse(
                        "PLAID_ERROR", "Plaid could not confirm the bank connection. Try again in a moment."));
                default -> ResponseEntity.ok(Map.of("result", result.name()));
            };
        } catch (UpdateModeUnavailableException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorCodeResponse(
                    "UPDATE_MODE_UNAVAILABLE", "Plaid could not reopen this bank connection. Link the bank again."));
        } catch (RuntimeException e) {
            logger.error("plaid.update_mode.failed user_id={} error_class={}", user.getId(), e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error completing the bank reconnection"));
        }
    }
```

(The error line is `plaid.`, not `alert.`: T04 `AlertSitesRoutedTest` does not apply.) Run the Step 11 command: `BUILD SUCCESSFUL`, `tests="4"`; re-run the Step 10 command: `tests="6"`.

- [ ] **Step 12: `FREDdocs/API_ENDPOINTS.md`**

Quoted-text anchors only. In `## 3. Investment Schedules`, delete the row that is exactly

```
| `POST` | `/api/investment-schedule/update-ach-request-id` | Update ACH request ID | Yes | `{ achRequestId, userEmail }` | `InvestmentScheduleResponse` |
```

(deleted in Phase 0, spec 8.9). In `## 6. Banking & Plaid`, replace the row beginning `` | `GET` | `/api/plaid/primary-bank-account` | `` with:

```
| `GET` | `/api/plaid/primary-bank-account` | Primary bank account, display fields only (never the Plaid access token) | Yes | None | `{ accountId, institutionName, accountName, accountType, accountSubtype, mask, itemStatus, transactionsConsented, consentExpiresAt }` (`transactionsConsented` true, false or null for unknown; `consentExpiresAt` ISO date-time with offset, or null) |
| `POST` | `/api/plaid/update-mode-complete` | Finish an update-mode Link (reconnect or consent): re-reads consent and its expiry, re-registers the webhook, repairs a login. Never exchanges a token | Yes | None | `{ result }`: `CONSENT_GRANTED`, `CONSENT_STILL_MISSING`, `REPAIRED` or `NO_CHANGE`; 409 `STALE_ITEM` or `UPDATE_MODE_UNAVAILABLE`; 502 `PLAID_ERROR` |
| `GET` | `/api/paycheck-detection/status` | Paycheck detection state (spec 8.1), database only; the first call for a user with no sync cursor starts the initial sync in the background. Not called by the app yet (Phase 1) | Yes | None | the spec 8.1 body (`PaycheckDetectionStatusResponse`) |
```

Check: `grep -c 'update-ach-request-id' FREDdocs/API_ENDPOINTS.md` prints `0`; `grep -c 'update-mode-complete\|paycheck-detection/status' FREDdocs/API_ENDPOINTS.md` prints `2`.

- [ ] **Step 13: Gates**

Run the Verification block below. Then from the repository root set `M=backend/src/main/java/com/investingapp/backend`, `T=backend/src/test/java/com/investingapp/backend` and `S=($M/service/PaycheckDetectionStatusService.java $M/service/InitSyncEnqueuer.java $M/service/PlaidUpdateModeService.java)` (an array, for zsh):

```
grep -n 'getIsPaused\|isPausedForAnyReason\|setStatus(\|setMode(\|setHoldReason(\|analyticsOutboxService' ${S[@]} $M/controller/PaycheckDetectionController.java
grep -n '@Transactional' ${S[@]}
grep -n 'logger.info(' ${S[@]}
git diff HEAD -- backend/src FREDdocs/API_ENDPOINTS.md | perl -ne 'print if /^\+.*\xe2\x80\x94/'
perl -ne 'print "$ARGV: $_" if /\xe2\x80\x94/' ${S[@]} $M/dto/PaycheckDetectionStatusResponse.java $M/controller/PaycheckDetectionController.java $T/service/{PaycheckDetectionStatusServiceTest,InitSyncEnqueuerTest,PlaidUpdateModeServiceTest}.java $T/controller/{PaycheckDetectionControllerTest,PlaidControllerUpdateModeCompleteTest}.java
```

Expected: the first two print nothing; the third prints only `logger.info("paycheck.init.enqueued");` and `logger.info("plaid.update_mode.complete result={}", result);`; the em-dash checks print nothing (the second covers the untracked files).

- [ ] **Step 14: Boot and full suite on a scratch MySQL**

Only a boot proves the new constructor wiring, and `BackendApplicationTests` is `@RequiresCiDatabase`:

```
read -s FRED_DB_PW; echo
MYSQL_PWD="$FRED_DB_PW" mysql -u root -e "CREATE DATABASE fred_t16_scratch"
cd backend
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t16_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$FRED_DB_PW"
export PLAID_WEBHOOK_URL=https://ci.invalid/api/plaid/webhook PLAID_REDIRECT_URI=https://local.fredvested.com/link-bank PLAID_LINK_CUSTOMIZATION=default
./gradlew cleanTest test --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests="t, "skipped="s, "failures="f, "errors="e}'
grep -o 'tests="[0-9]*" skipped="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
cd .. && MYSQL_PWD="$FRED_DB_PW" mysql -u root -e "DROP DATABASE fred_t16_scratch"
unset FRED_DB_PW SPRING_DATASOURCE_PASSWORD
```

Expected: `skipped=0 failures=0 errors=0`, `tests=` the previous task's total plus 29; `BackendApplicationTests` `tests="1" skipped="0"`. Any skip means the datasource did not reach Gradle; never trust that run, never echo the password.

- [ ] **Step 15: Commit**

With the Step 13 variables set, hunk-verify `git diff HEAD -- $M/controller/PlaidController.java $T/controller/PlaidControllerPrimaryBankAccountTest.java FREDdocs/API_ENDPOINTS.md` (shared tree; stage only your API rows, with `git add -p` if another task's are there), then:

```
git add $M/dto/PaycheckDetectionStatusResponse.java ${S[@]} $M/controller/PaycheckDetectionController.java $M/controller/PlaidController.java $T/service/PaycheckDetectionStatusServiceTest.java $T/service/InitSyncEnqueuerTest.java $T/service/PlaidUpdateModeServiceTest.java $T/controller/PaycheckDetectionControllerTest.java $T/controller/PlaidControllerUpdateModeCompleteTest.java $T/controller/PlaidControllerPrimaryBankAccountTest.java FREDdocs/API_ENDPOINTS.md
git status --short
git commit -m "feat(paycheck): detection status endpoint with INIT enqueue, and update-mode completion"
```

Nothing under `ITPM/agent-memory/` and no `application-local.properties` may be staged.

### Verification

```
cd backend && ./gradlew test --tests '*.PaycheckDetectionStatusServiceTest' --tests '*.InitSyncEnqueuerTest' --tests '*.PaycheckDetectionControllerTest' --tests '*.PlaidUpdateModeServiceTest' --tests '*.PlaidControllerUpdateModeCompleteTest' --tests '*.PlaidControllerPrimaryBankAccountTest' --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-*PaycheckDetection*.xml build/test-results/test/TEST-*InitSyncEnqueuer*.xml build/test-results/test/TEST-*PlaidUpdateMode*.xml build/test-results/test/TEST-*PlaidController*.xml
```

Expected (XML, never the console), all `skipped="0" failures="0"`: `PaycheckDetectionStatusServiceTest` 5, `InitSyncEnqueuerTest` 6, `PaycheckDetectionControllerTest` 3, `PlaidUpdateModeServiceTest` 9, `PlaidControllerUpdateModeCompleteTest` 4, `PlaidControllerPrimaryBankAccountTest` 6 (33 in all, 29 new); `PhaseOneMoneyInvarianceTest` (T03; `find backend/src/test -name PhaseOneMoneyInvarianceTest.java` if its package differs) passes with its count unchanged. Every contract test name is present (the update-mode `plaidCallsOutsideTransaction` uses reflection, as that service opens no transaction).

### Produces

- `dto.PaycheckDetectionStatusResponse` with nested `PrimaryStreamView`, `ManualPrefillView`, components as in Step 3 (wire key `isPaused`; `amountSource`, `authorization` always null, the Phase 2 seam).
- `PaycheckDetectionStatusService.status(User)`, database only.
- `InitSyncEnqueuer.enqueueIfNeeded(User): boolean` (Phase 2 seam: screen 1's poll).
- `PaycheckDetectionController`: `GET /api/paycheck-detection/status` (200, 401, 404).
- `PlaidUpdateModeService.complete(User): UpdateModeResult`, nested `enum UpdateModeResult {CONSENT_GRANTED, CONSENT_STILL_MISSING, REPAIRED, NO_CHANGE, STALE_ITEM, PLAID_ERROR}`; `UpdateModeUnavailableException` without an Item.
- `PlaidController.completeUpdateMode(Authentication)`: `POST /api/plaid/update-mode-complete` (200 `{ "result" }`, 409 `STALE_ITEM`, 409 `UPDATE_MODE_UNAVAILABLE`, 502 `PLAID_ERROR`, 401, 404, 500); `primary-bank-account` gains `transactionsConsented` and `consentExpiresAt` (read by T21).
- No migration, property, analytics event, reason or ops alert type.

