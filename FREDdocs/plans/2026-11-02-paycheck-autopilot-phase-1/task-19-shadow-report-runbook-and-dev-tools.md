### Task 19: shadow-report-runbook-and-dev-tools

Full code for the steps this file gives as tables or patterns is in task-19-shadow-report-runbook-and-dev-tools-appendix.md; read both.

The weekly shadow report (spec 12.4, R13) on the admin and dev surfaces, the dev Sandbox verbs (spec 12.2), three self-skipping Bash scripts (the Sandbox-scriptable subset of spec 13.2) and `FREDdocs/runbooks/paycheck-autopilot.md`. Depends on T18. Nothing here moves money, writes `InvestmentSchedule.status`, `mode` or `hold_reason`, changes `isReadyForInvestment`, emits an analytics event or raises an ops alert: the report is a read-only query, and the dev verbs call Plaid Sandbox from a controller with no transaction, then hand off to T14's pipeline, which never runs inside one.

Facts: Phase 1 is `enabled=true, shadow=true, pulls.enabled=false` on Sandbox; a matched deposit closes its cycle `SKIPPED SHADOW_CONFIRMED` with `deposit_amount`, `event_id`, `confirmed_at` (ET) and `amount_to_pull` (R7, T12). R13 keys rows by `public_id`, never email, and shows the payer only as a hash of the payer token; Decision 38 forbids any deposit amount or trailing median (R14). R8: REJECTED, opted-out and token-less users are not detection subjects. `DevToolsProdGuard` refuses listed dev beans by type in prod; `/api/dev/*` is behind `DevToolsKeyFilter` (`X-Dev-Tools-Key`, 404); `/api/admin/*` is behind T17's `AdminKeyFilter` (`X-Admin-Key` 404, `X-Admin-Actor` 400). CI runs `test/api/run-all.sh` against a ci backend, whose `PlaidApi` is the offline `CiStubConfig` proxy (`config/CiStubConfig.java:55-64`), and fails on any non-zero exit. No em dashes; one commit; never `git checkout`, `restore`, `stash` or `reset`; stage only the named files.

`.../` = `backend/src/main/java/com/investingapp/backend/`, `T/` = `backend/src/test/java/com/investingapp/backend/`; anchors are HEAD 137998c, quoted where an earlier task edits the file.

Mechanical repetitions, stated once and not written out (all behavioural code is in full): (1) imports: each block names the non-obvious ones (`a.b.{X, Y}` is one import per name); organize-imports adds the rest, each resolving unambiguously from `com.investingapp.backend.*`, `java.*`, Spring, Jackson, JUnit 5, Mockito or AssertJ (Plaid models by name, never `com.plaid.client.model.*`); (2) the `ShadowReportService` and `DevPaycheckController` constructors take and assign one parameter per `private final` field, in declaration order (`objectMapper` excepted); (3) the four fixture builders in `ShadowReportServiceTest` are one Lombok setter per listed `field value` pair; (4) `paycheck_lost_webhook.sh` is `paycheck_shadow.sh` with the changes Step 9 lists.

Local properties: none added; it relies on `paycheck.autopilot.enabled=true` (T03), `app.admin-tools.key` (T17) and `app.dev-tools.key` in the gitignored `application-local.properties`. Check by name only: `grep -c '^paycheck.autopilot.enabled=\|^app.admin-tools.key=\|^app.dev-tools.key=' backend/src/main/resources/application-local.properties` prints `3`.

**Files:**
- Create: `.../dto/admin/ShadowReport.java`, `.../service/ShadowReportService.java`, `.../controller/DevPaycheckController.java`, `FREDdocs/runbooks/paycheck-autopilot.md` (new directory), `test/api/lib/sandbox.sh`, `test/api/paycheck_shadow.sh`, `test/api/paycheck_webhooks.sh`, `test/api/paycheck_lost_webhook.sh`
- Modify: `.../controller/admin/AdminPaycheckController.java` (T18); `.../config/DevToolsProdGuard.java` (HEAD `:3-5`, `:45-49`, quoted anchors because T17 drops `DevUserController`); `test/api/run-all.sh:25`
- Test: create `T/service/ShadowReportServiceTest`, `T/controller/DevPaycheckControllerTest`, `T/config/DevToolsProdGuardTest` (new), `T/controller/admin/AdminPaycheckShadowReportTest`; modify T18's `T/controller/admin/AdminPaycheckControllerTest` (Step 8). T03's invariance walk covers these sources.

**Interfaces:**
- Consumes: T02 entities, enums and repositories; T03 `PaycheckAutopilotProperties.isEnabled()`, `isShadow()`, `getAffordabilityShare()`, static `DetectionEligibility.exclusion(User, PaycheckAutopilotProperties)`; T09 static `PrimarySelector.select(List<PaycheckStream>, BigDecimal).lowConfidence()`, `AmountPrefill.DEFAULT_GOAL`; T06 `PlaidGateway.call(String, Call<T>)`, `PlaidCallResult(body, error).ok()`, `PlaidApiError(errorType, errorCode, errorCodeReason, requestId, httpStatus, errorClass)`; T08 `SyncReason`, `SyncStatus`, `SyncOutcome(status, historyJustCompleted, applied, error)`; T14 `PaycheckSyncPipeline.run(Long, String, SyncReason): PipelineOutcome(sync, streams, evaluation, itemState)`; T15 `PaycheckReconcileService.runForUser(Long, LocalDate): ReconcileReport` (12 fields); T17 `AdminAuditService.record(actor, userId, verb, targetId, before, after, reason)`, `recordInTransaction(...)`, `actor(HttpServletRequest)`; T18 `AdminPaycheckController`; HEAD `UserRepository.findByPlaidAccessTokenIsNotNull()` (`:46`), `InvestmentScheduleRepository.findTopByUserOrderByCreatedAtDesc` (`:19`), `EncryptionService.decrypt` (`:106`).
- Type locations (one file per service in T08, T14, T15, T03): `SyncReason`, `SyncStatus`, `SyncOutcome` nest in `PlaidTransactionsSyncService`, `PipelineOutcome` in `PaycheckSyncPipeline`, `ReconcileReport` in `PaycheckReconcileService`, `ExclusionReason` in `DetectionEligibility`; Step 0 checks, and a top-level one changes only its import.
- Produces: see the end of this file.

Deviation: `sandbox/paycheck` also takes optional `endDaysAgo` (default 0; Sandbox never posts a future date, so history and today's deposit are two calls) and `sync` (default true; false leaves the deposit for the reconcile). The dev report also sends `X-Fred-Profiles`, so `lib/sandbox.sh` can spot a ci backend.

- [ ] **Step 0: Preflight (read only)**

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
grep -rn 'enum SyncReason\|enum SyncStatus\|record SyncOutcome\|record PipelineOutcome\|record ReconcileReport\|enum ExclusionReason' backend/src/main/java
grep -rn 'ReconcileReport runForUser\|public static .* select(\|public static .* exclusion(\|DEFAULT_GOAL =' backend/src/main/java
grep -n 'AdminAuditService\|@RequestMapping\|public AdminPaycheckController(' backend/src/main/java/com/investingapp/backend/controller/admin/AdminPaycheckController.java
grep -n 'scoreBreakdownJson\|payerToken;' backend/src/main/java/com/investingapp/backend/model/PaycheckStream.java
grep -rln 'DevUserController' backend/src
```

Expected: the six types once each, nested as Type locations says; `runForUser` returns `ReconcileReport`; `select` and `exclusion` static; `AdminPaycheckController` on `/api/admin/paycheck` with one public constructor and a `private final AdminAuditService` field; both stream fields; no `DevUserController`. Anything else: stop and report the contract break.

- [ ] **Step 1: Failing test for the report**

Create `T/service/ShadowReportServiceTest.java`. Non-obvious imports: `com.investingapp.backend.dto.admin.ShadowReport.{CycleRow, UserRow}`, `com.fasterxml.jackson.datatype.jsr310.JavaTimeModule`, `java.util.Map`.

```java
package com.investingapp.backend.service;

class ShadowReportServiceTest {

    private static final LocalDate SINCE = LocalDate.of(2026, 10, 1);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private UserRepository userRepository;
    private PaycheckCycleRepository cycleRepository;
    private ShadowReportService service;
    private User ada;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
        PaycheckStreamRepository streamRepository = mock(PaycheckStreamRepository.class);
        cycleRepository = mock(PaycheckCycleRepository.class);
        PaycheckEventRepository eventRepository = mock(PaycheckEventRepository.class);
        PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
        when(props.isEnabled()).thenReturn(true);
        when(props.isShadow()).thenReturn(true);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        service = new ShadowReportService(userRepository, scheduleRepository, streamRepository, cycleRepository, eventRepository, props);

        ada = user(7L, "pub-ada-7", "ada@fredvested.com");
        when(userRepository.findByPlaidAccessTokenIsNotNull()).thenReturn(List.of(ada));
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setDetectionState(DetectionState.PROVISIONAL);
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(ada)).thenReturn(Optional.of(schedule));
        when(streamRepository.findByUserIdOrderByIdAsc(7L)).thenReturn(List.of(primaryStream()));
        cycles(cycle(301L, "300.00", LocalDateTime.of(2026, 10, 24, 23, 10)));
        when(eventRepository.findById(301L)).thenReturn(Optional.of(event(LocalDate.of(2026, 10, 23))));
        when(eventRepository.findById(302L)).thenReturn(Optional.of(event(LocalDate.of(2026, 11, 6))));
    }

    @Test
    void rowsKeyedByPublicIdNeverEmail() throws Exception {
        ShadowReport report = service.build(SINCE);
        assertThat(report.users()).extracting(UserRow::publicId).containsExactly("pub-ada-7");
        assertThat(json.writeValueAsString(report)).doesNotContain("ada@fredvested.com").doesNotContain("item-7");
    }

    @Test
    void payerIsHashNeverDescriptor() throws Exception {
        ShadowReport report = service.build(SINCE);
        // printf 'ACME CORP' | shasum -a 256 | cut -c1-16
        assertThat(ShadowReportService.payerTokenHash("ACME CORP")).isEqualTo("d2683906516246b0");
        assertThat(ShadowReportService.payerTokenHash(null)).isNull();
        assertThat(report.users().get(0).primaryPayerTokenHash()).isEqualTo("d2683906516246b0");
        assertThat(json.writeValueAsString(report)).doesNotContainIgnoringCase("acme");
    }

    @Test
    void shadowCyclesCarryWouldHavePulled() {
        ShadowReport report = service.build(SINCE);
        UserRow row = report.users().get(0);
        assertThat(report.shadow()).isTrue();
        assertThat(row.detectionState()).isEqualTo("PROVISIONAL");
        assertThat(row.itemStatus()).isEqualTo("OK");
        assertThat(row.score()).isEqualTo(64);
        assertThat(row.breakdown()).containsEntry("total", 64).containsEntry("cadenceFit", 14);
        assertThat(row.cadence()).isEqualTo("BIWEEKLY");
        assertThat(row.depositsFound()).isEqualTo(6);
        assertThat(row.misses()).isEqualTo(1);
        assertThat(row.lowConfidence()).isFalse();
        CycleRow cycle = row.shadowCycles().get(0);
        assertThat(cycle.periodKey()).isEqualTo("2026-10-23");
        assertThat(cycle.wouldHavePulled()).isEqualByComparingTo("300.00");
        assertThat(cycle.pullShareBand()).isEqualTo("under_50");
        assertThat(cycle.passesAffordability()).isTrue();
        assertThat(report.summary().provisional()).isEqualTo(1);
        assertThat(report.summary().shadowCycles()).isEqualTo(1);
    }

    @Test
    void reportCarriesNoDepositAmount() throws Exception {
        assertThat(json.writeValueAsString(service.build(SINCE)))
                .doesNotContain("depositAmount").doesNotContain("trailingMedian").doesNotContain("2450")
                .doesNotContain("rawDescriptor").doesNotContain("payerDisplayName").doesNotContain("PAYROLL");
    }

    @Test
    void confirmLagIsEtDayMinusPostedDate() {
        cycles(cycle(301L, "300.00", LocalDateTime.of(2026, 10, 24, 23, 10)),
                cycle(302L, "300.00", LocalDateTime.of(2026, 11, 6, 8, 0)),
                cycle(null, "300.00", LocalDateTime.of(2026, 11, 20, 9, 0)));

        ShadowReport report = service.build(SINCE);

        List<CycleRow> rows = report.users().get(0).shadowCycles();
        assertThat(rows).extracting(CycleRow::confirmedOnET)
                .containsExactly(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 20));
        assertThat(rows).extracting(CycleRow::confirmLagDays).containsExactly(1, 0, -1);
        assertThat(rows.get(2).postedDate()).isNull();
        assertThat(report.summary().meanConfirmLagDays()).isEqualTo(0.5);
    }

    @Test
    void pullShareBandsAndAffordability() {
        assertThat(ShadowReportService.share(new BigDecimal("300.00"), new BigDecimal("2450.00"))).isEqualByComparingTo("0.1224");
        assertThat(ShadowReportService.share(new BigDecimal("300.00"), null)).isNull();
        Map.of("0.4999", "under_50", "0.5000", "50_79", "0.7999", "50_79", "0.8000", "80_90", "0.9000", "80_90", "0.9001", "over_90")
                .forEach((share, band) -> assertThat(ShadowReportService.pullShareBand(new BigDecimal(share))).isEqualTo(band));
        assertThat(ShadowReportService.pullShareBand(null)).isNull();
        cycles(cycle(301L, "2300.00", LocalDateTime.of(2026, 10, 24, 23, 10)));
        CycleRow row = service.build(SINCE).users().get(0).shadowCycles().get(0);
        assertThat(row.pullShareBand()).isEqualTo("over_90");
        assertThat(row.passesAffordability()).isFalse();
    }

    @Test
    void excludedUsersAreLeftOut() {
        User bob = user(8L, "pub-bob-8", "bob@fredvested.com");
        bob.setAccountStatus("REJECTED");
        User cy = user(9L, "pub-cy-9", "cy@fredvested.com");
        cy.setPaycheckAutopilotOptOut(true);
        User dan = user(10L, "pub-dan-10", "dan@fredvested.com");
        dan.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        dan.setPlaidHistoryComplete(false);
        when(userRepository.findByPlaidAccessTokenIsNotNull()).thenReturn(List.of(dan, cy, ada, bob));

        ShadowReport report = service.build(SINCE);

        assertThat(report.users()).extracting(UserRow::publicId).containsExactly("pub-ada-7", "pub-dan-10");
        assertThat(report.users().get(1).itemStatus()).isEqualTo("LOGIN_REQUIRED");
        assertThat(report.users().get(1).detectionState()).isEqualTo("SEARCHING");
        assertThat(ShadowReportService.detectionState(Optional.empty(), ada, Optional.of(primaryStream()))).isEqualTo("PROVISIONAL");
        assertThat(ShadowReportService.detectionState(Optional.empty(), ada, Optional.empty())).isEqualTo("UNDETECTABLE");
    }

    private void cycles(PaycheckCycle... rows) {
        when(cycleRepository.findByUserIdAndSkipReasonAndConfirmedAtAfter(eq(7L), eq(PaycheckSkipReason.SHADOW_CONFIRMED), any()))
                .thenReturn(List.of(rows));
    }
}
```

Fixture builders (`private static`, mechanical repetition (3)): `User user(Long id, String publicId, String email)`: `new User(email)`; `id id`, `publicId publicId`, `accountStatus "ACTIVE"`, `plaidAccessToken "ENC:token-" + id`, `plaidItemId "item-" + id`, `plaidItemStatus PlaidItemStatus.OK`, `monthlyInvestment 650.0`, `plaidHistoryComplete true`, `paycheckAutopilotOptOut false`. `PaycheckStream primaryStream()`: `id 41L`, `userId 7L`, `plaidAccountId "acc-7"`, `payerToken "ACME CORP"`, `payerDisplayName "Acme Corp"`, `rawDescriptor "ACME CORP PAYROLL DIR DEP"`, `state PaycheckStreamState.PROVISIONAL`, `role PaycheckStreamRole.PRIMARY`, `cadence InvestmentFrequency.BIWEEKLY`, `score (short) 64`, `scoreBreakdownJson "{\"pfc\":20,\"plaidStatus\":10,\"median\":10,\"cadenceFit\":14,\"stability\":5,\"payrollToken\":5,\"total\":64}"`, `depositsFound (short) 6`, `missedCount (short) 1`, `trailingMedian new BigDecimal("2450.00")`, `trailingCv new BigDecimal("0.0100")`, `lastDate LocalDate.of(2026, 10, 9)`. `PaycheckCycle cycle(Long eventId, String pull, LocalDateTime confirmedAt)`: `userId 7L`, `streamId 41L`, `periodKey "2026-10-23"`, `state PaycheckCycleState.SKIPPED`, `skipReason PaycheckSkipReason.SHADOW_CONFIRMED`, `expectedDate LocalDate.of(2026, 10, 23)`, `eventId eventId`, `depositAmount new BigDecimal("2450.00")`, `confirmedAt confirmedAt`, `amountToPull new BigDecimal(pull)`. `PaycheckEvent event(LocalDate posted)`: `postedDate posted`.

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.ShadowReportServiceTest' --console=plain`.

Expected: compilation fails with `cannot find symbol`, naming `ShadowReport` or `ShadowReportService`.

- [ ] **Step 2: The report records and the service**

Create `.../dto/admin/ShadowReport.java` (package `com.investingapp.backend.dto.admin`; imports `java.math.BigDecimal`, `java.time.LocalDate`, `java.util.{List, Map}`; `confirmLagDays` is -1 when an Item break purged the event):

```java
public record ShadowReport(LocalDate since, LocalDate generatedOn, boolean shadow, Summary summary, List<UserRow> users) {
    public record Summary(int users, int provisional, int confirmed, int undetectable, int shadowCycles,
            double meanConfirmLagDays) {}
    public record UserRow(String publicId, String detectionState, String itemStatus, String primaryPayerTokenHash,
            Integer score, Map<String, Integer> breakdown, String cadence, int depositsFound, int misses,
            boolean lowConfidence, List<CycleRow> shadowCycles) {}
    public record CycleRow(String periodKey, LocalDate expectedDate, LocalDate postedDate, LocalDate confirmedOnET,
            int confirmLagDays, BigDecimal wouldHavePulled, String pullShareBand, boolean passesAffordability) {}
}
```

Create `.../service/ShadowReportService.java`. Non-obvious imports: `com.investingapp.backend.dto.admin.ShadowReport.{CycleRow, Summary, UserRow}`, `com.investingapp.backend.service.DetectionEligibility.ExclusionReason`, `com.investingapp.backend.paycheck.detect.{AmountPrefill, PrimarySelector}`, `java.util.HexFormat`.

```java
package com.investingapp.backend.service;

/** Spec 12.4 / R13. DB only, read only. R8's rejected, opted-out and token-less users are left out. */
@Service
public class ShadowReportService {

    private static final Logger logger = LoggerFactory.getLogger(ShadowReportService.class);
    static final int DEFAULT_WINDOW_DAYS = 7;
    static final int LAG_UNKNOWN = -1;
    private static final Set<ExclusionReason> LEFT_OUT =
            EnumSet.of(ExclusionReason.ACCOUNT_REJECTED, ExclusionReason.OPTED_OUT, ExclusionReason.NO_TOKEN);
    private static final TypeReference<LinkedHashMap<String, Integer>> BREAKDOWN = new TypeReference<>() {
    };
    private static final BigDecimal BAND_50 = new BigDecimal("0.50");
    private static final BigDecimal BAND_80 = new BigDecimal("0.80");
    private static final BigDecimal BAND_90 = new BigDecimal("0.90");

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckCycleRepository cycleRepository;
    private final PaycheckEventRepository eventRepository;
    private final PaycheckAutopilotProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Constructor: mechanical repetition (2) in the header.

    /** Shadow cycles confirmed on or after since (ET day); null means the last 7 ET days. */
    @Transactional(readOnly = true)
    public ShadowReport build(LocalDate since) {
        LocalDate today = EasternTime.today();
        LocalDate from = since != null ? since : today.minusDays(DEFAULT_WINDOW_DAYS);
        List<User> users = new ArrayList<>(userRepository.findByPlaidAccessTokenIsNotNull());
        users.sort(Comparator.comparing(User::getId));
        List<UserRow> rows = new ArrayList<>();
        for (User user : users) {
            Optional<ExclusionReason> excluded = DetectionEligibility.exclusion(user, props);
            if (excluded.isEmpty() || !LEFT_OUT.contains(excluded.get())) {
                rows.add(row(user, from));
            }
        }
        Summary summary = summarize(rows);
        logger.info("paycheck.shadow_report users={} shadow_cycles={}", summary.users(), summary.shadowCycles());
        return new ShadowReport(from, today, props.isShadow(), summary, List.copyOf(rows));
    }

    public static String payerTokenHash(String payerToken) {
        if (payerToken == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payerToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private UserRow row(User user, LocalDate from) {
        Optional<InvestmentSchedule> schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        Optional<PaycheckStream> primary = streamRepository.findByUserIdOrderByIdAsc(user.getId()).stream()
                .filter(s -> s.getRole() == PaycheckStreamRole.PRIMARY && s.getState() != PaycheckStreamState.RETIRED)
                .findFirst();
        List<CycleRow> cycles = cycleRepository
                .findByUserIdAndSkipReasonAndConfirmedAtAfter(user.getId(), PaycheckSkipReason.SHADOW_CONFIRMED, from.atStartOfDay())
                .stream().sorted(Comparator.comparing(PaycheckCycle::getConfirmedAt)).map(this::cycleRow).toList();
        return new UserRow(
                user.getPublicId(),
                detectionState(schedule, user, primary),
                user.getPlaidItemStatus() == null ? null : user.getPlaidItemStatus().name(),
                primary.map(s -> payerTokenHash(s.getPayerToken())).orElse(null),
                primary.map(s -> (int) s.getScore()).orElse(null),
                primary.map(this::breakdown).orElse(Map.of()),
                primary.map(s -> s.getCadence() == null ? null : s.getCadence().name()).orElse(null),
                primary.map(s -> (int) s.getDepositsFound()).orElse(0),
                primary.map(s -> (int) s.getMissedCount()).orElse(0),
                primary.map(s -> lowConfidence(s, user)).orElse(false),
                cycles);
    }

    /** The R1 mirror when set, else the primary stream, else history. */
    static String detectionState(Optional<InvestmentSchedule> schedule, User user, Optional<PaycheckStream> primary) {
        if (schedule.isPresent() && schedule.get().getDetectionState() != null) {
            return schedule.get().getDetectionState().name();
        }
        if (primary.isPresent()) {
            return primary.get().getState().name();
        }
        return Boolean.TRUE.equals(user.getPlaidHistoryComplete()) ? DetectionState.UNDETECTABLE.name() : DetectionState.SEARCHING.name();
    }

    private CycleRow cycleRow(PaycheckCycle cycle) {
        LocalDate posted = cycle.getEventId() == null ? null
                : eventRepository.findById(cycle.getEventId()).map(PaycheckEvent::getPostedDate).orElse(null);
        LocalDate confirmedOn = cycle.getConfirmedAt().toLocalDate();
        int lag = posted == null ? LAG_UNKNOWN : (int) ChronoUnit.DAYS.between(posted, confirmedOn);
        BigDecimal pull = cycle.getAmountToPull() == null ? null : cycle.getAmountToPull().setScale(2, RoundingMode.HALF_UP);
        BigDecimal share = share(pull, cycle.getDepositAmount());
        return new CycleRow(cycle.getPeriodKey(), cycle.getExpectedDate(), posted, confirmedOn, lag, pull,
                pullShareBand(share), share != null && share.compareTo(props.getAffordabilityShare()) <= 0);
    }

    static BigDecimal share(BigDecimal pull, BigDecimal deposit) {
        if (pull == null || deposit == null || deposit.signum() <= 0) {
            return null;
        }
        return pull.divide(deposit, 4, RoundingMode.HALF_UP);
    }

    static String pullShareBand(BigDecimal share) {
        if (share == null) {
            return null;
        }
        if (share.compareTo(BAND_50) < 0) {
            return "under_50";
        }
        if (share.compareTo(BAND_80) < 0) {
            return "50_79";
        }
        return share.compareTo(BAND_90) <= 0 ? "80_90" : "over_90";
    }

    private Map<String, Integer> breakdown(PaycheckStream stream) {
        String body = stream.getScoreBreakdownJson();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, BREAKDOWN);
        } catch (JsonProcessingException e) {
            logger.warn("paycheck.shadow_report breakdown_unreadable stream={}", stream.getId());
            return Map.of();
        }
    }

    private static boolean lowConfidence(PaycheckStream primary, User user) {
        Double goal = user.getMonthlyInvestment();
        BigDecimal monthlyGoal = goal == null || goal <= 0 ? BigDecimal.valueOf(AmountPrefill.DEFAULT_GOAL) : BigDecimal.valueOf(goal);
        return PrimarySelector.select(List.of(primary), monthlyGoal).lowConfidence();
    }

    static Summary summarize(List<UserRow> rows) {
        List<Integer> lags = rows.stream().flatMap(r -> r.shadowCycles().stream()).map(CycleRow::confirmLagDays).filter(l -> l >= 0).toList();
        double mean = Math.round(lags.stream().mapToInt(Integer::intValue).average().orElse(0.0) * 100.0) / 100.0;
        return new Summary(rows.size(), count(rows, DetectionState.PROVISIONAL), count(rows, DetectionState.CONFIRMED),
                count(rows, DetectionState.UNDETECTABLE), rows.stream().mapToInt(r -> r.shadowCycles().size()).sum(), mean);
    }

    private static int count(List<UserRow> rows, DetectionState state) {
        return (int) rows.stream().filter(r -> state.name().equals(r.detectionState())).count();
    }
}
```

Run the Step 1 command.

Expected: `BUILD SUCCESSFUL`; its XML in `build/test-results/test/` shows `tests="7" skipped="0" failures="0"`.

- [ ] **Step 3: Failing test for the dev controller**

Create `T/controller/DevPaycheckControllerTest.java`. Non-obvious imports: `com.investingapp.backend.controller.DevPaycheckController.{FireRequest, SandboxPaycheckRequest}`, `com.investingapp.backend.service.PaycheckReconcileService.ReconcileReport`, `com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome`, `com.investingapp.backend.service.PlaidTransactionsSyncService.{SyncOutcome, SyncReason, SyncStatus}`, `com.plaid.client.model.{CustomSandboxTransaction, SandboxItemFireWebhookRequest, SandboxItemFireWebhookResponse, SandboxItemResetLoginRequest, SandboxItemResetLoginResponse, SandboxTransactionsCreateRequest, SandboxTransactionsCreateResponse, WebhookType}`, `org.springframework.mock.env.MockEnvironment`.

```java
package com.investingapp.backend.controller;

@SuppressWarnings("unchecked")
class DevPaycheckControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final EncryptionService encryption = mock(EncryptionService.class);
    private final PlaidApi plaidApi = mock(PlaidApi.class);
    private final PlaidGateway gateway = mock(PlaidGateway.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PaycheckReconcileService reconcile = mock(PaycheckReconcileService.class);
    private final ShadowReportService shadowReports = mock(ShadowReportService.class);
    private DevPaycheckController controller;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        controller = new DevPaycheckController(userRepository, encryption, plaidApi, gateway, pipeline, reconcile, shadowReports, environment);
        User seven = new User("seven@fredvested.com");
        seven.setId(7L);
        seven.setPlaidAccessToken("ENC:seven");
        seven.setPlaidItemId("item-7");
        seven.setPlaidAccountId("acc-7");
        when(userRepository.findById(7L)).thenReturn(Optional.of(seven));
        when(userRepository.findById(9L)).thenReturn(Optional.empty());
        when(encryption.decrypt("ENC:seven")).thenReturn("access-sandbox-seven");
        doReturn(new PlaidCallResult<>(new SandboxTransactionsCreateResponse(), null)).when(gateway).call(eq("sandbox_transactions_create"), any());
        when(pipeline.run(7L, "item-7", SyncReason.MANUAL))
                .thenReturn(new PipelineOutcome(new SyncOutcome(SyncStatus.APPLIED, false, null, null), null, null, null));
    }

    private static SandboxPaycheckRequest acme(int count, int endDaysAgo, Boolean sync) {
        return req("2450.00", count, "ACME CORP PAYROLL DIR DEP", 14, endDaysAgo, sync);
    }

    private static SandboxPaycheckRequest req(String amount, int count, String description, int cadenceDays, int endDaysAgo, Boolean sync) {
        return new SandboxPaycheckRequest(new BigDecimal(amount), count, description, cadenceDays, endDaysAgo, sync);
    }

    @Test
    void sandboxFixtureUsesCallersItemOnly() {
        ResponseEntity<?> response = controller.sandboxPaycheck(7L, acme(3, 14, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<SandboxTransactionsCreateRequest> sent = ArgumentCaptor.forClass(SandboxTransactionsCreateRequest.class);
        verify(plaidApi).sandboxTransactionsCreate(sent.capture());
        assertThat(sent.getValue().getAccessToken()).isEqualTo("access-sandbox-seven");
        LocalDate newest = EasternTime.today().minusDays(14);
        assertThat(sent.getValue().getTransactions()).extracting(CustomSandboxTransaction::getDatePosted)
                .containsExactly(newest.minusDays(28), newest.minusDays(14), newest);
        assertThat(sent.getValue().getTransactions()).allSatisfy(t -> {
            assertThat(t.getAmount()).isEqualTo(-2450.00);
            assertThat(t.getAccountId()).isEqualTo("acc-7");
            assertThat(t.getIsoCurrencyCode()).isEqualTo("USD");
        });
        verify(pipeline).run(7L, "item-7", SyncReason.MANUAL);
        verify(userRepository).findById(7L);
        verifyNoMoreInteractions(userRepository);
        verify(encryption).decrypt("ENC:seven");
        verifyNoMoreInteractions(encryption);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("created", 3).containsEntry("syncStatus", SyncStatus.APPLIED);
    }

    @Test
    void fixtureWithoutItemIs409AndCallsNothing() {
        User eight = new User("eight@fredvested.com");
        eight.setId(8L);
        when(userRepository.findById(8L)).thenReturn(Optional.of(eight));

        ResponseEntity<?> noItem = controller.sandboxPaycheck(8L, acme(1, 0, null));

        assertThat(noItem.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ErrorCodeResponse) noItem.getBody()).code()).isEqualTo("NO_ITEM");
        assertThat(controller.sandboxPaycheck(9L, acme(1, 0, null)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(plaidApi, gateway, pipeline, encryption);
    }

    @Test
    void fixtureRejectsOutOfRangeInput() {
        for (SandboxPaycheckRequest body : List.of(acme(11, 0, null), acme(0, 0, null), acme(6, 700, null), acme(1, -1, null),
                req("-5.00", 1, "ACME", 14, 0, null), req("1.005", 1, "ACME", 14, 0, null), req("2450.00", 1, " ", 14, 0, null),
                req("2450.00", 1, "ACME", 0, 0, null))) {
            ResponseEntity<?> response = controller.sandboxPaycheck(7L, body);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(((ErrorCodeResponse) response.getBody()).code()).isEqualTo("INVALID_FIXTURE");
        }
        verifyNoInteractions(userRepository, plaidApi, gateway, pipeline, encryption);
    }

    @Test
    void fireAndResetLoginUseCallersTokenAndRefuseUnknownCodes() {
        doReturn(new PlaidCallResult<>(new SandboxItemFireWebhookResponse().webhookFired(true), null)).when(gateway).call(eq("sandbox_item_fire_webhook"), any());
        doReturn(new PlaidCallResult<>(new SandboxItemResetLoginResponse().resetLogin(true), null)).when(gateway).call(eq("sandbox_item_reset_login"), any());

        ResponseEntity<?> sync = controller.fire(7L, new FireRequest("sync_updates_available"));
        assertThat(controller.fire(7L, new FireRequest("LOGIN_REPAIRED")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<?> unknown = controller.fire(7L, new FireRequest("DEFAULT_UPDATE"));
        ResponseEntity<?> reset = controller.resetLogin(7L);

        assertThat((Map<String, Object>) sync.getBody()).containsEntry("code", "SYNC_UPDATES_AVAILABLE").containsEntry("fired", true);
        assertThat(((ErrorCodeResponse) unknown.getBody()).code()).isEqualTo("UNSUPPORTED_CODE");
        assertThat((Map<String, Object>) reset.getBody()).containsEntry("resetLogin", true);
        ArgumentCaptor<SandboxItemFireWebhookRequest> fired = ArgumentCaptor.forClass(SandboxItemFireWebhookRequest.class);
        verify(plaidApi, times(2)).sandboxItemFireWebhook(fired.capture());
        assertThat(fired.getAllValues()).extracting(SandboxItemFireWebhookRequest::getWebhookType).containsExactly(WebhookType.TRANSACTIONS, WebhookType.ITEM);
        assertThat(fired.getAllValues()).extracting(SandboxItemFireWebhookRequest::getWebhookCode).containsExactly(
                SandboxItemFireWebhookRequest.WebhookCodeEnum.SYNC_UPDATES_AVAILABLE, SandboxItemFireWebhookRequest.WebhookCodeEnum.LOGIN_REPAIRED);
        assertThat(fired.getAllValues()).extracting(SandboxItemFireWebhookRequest::getAccessToken).containsOnly("access-sandbox-seven");
        verify(plaidApi).sandboxItemResetLogin(new SandboxItemResetLoginRequest().accessToken("access-sandbox-seven"));
    }

    @Test
    void reconcileAndReportDelegate() {
        ReconcileReport report = new ReconcileReport(1, 0, 1, 0, 1, 0, 0, 0, 0, 0, false, false);
        when(reconcile.runForUser(7L, LocalDate.of(2026, 11, 20))).thenReturn(report);
        when(reconcile.runForUser(7L, EasternTime.today())).thenReturn(report);
        ShadowReport shadow = new ShadowReport(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 4), true, new ShadowReport.Summary(0, 0, 0, 0, 0, 0.0), List.of());
        when(shadowReports.build(null)).thenReturn(shadow);

        assertThat(controller.reconcile(7L, LocalDate.of(2026, 11, 20)).getBody()).isSameAs(report);
        assertThat(controller.reconcile(7L, null).getBody()).isSameAs(report);
        assertThat(controller.reconcile(9L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(reconcile, never()).runForUser(eq(9L), any());
        ResponseEntity<ShadowReport> response = controller.shadowReport(null);
        assertThat(response.getBody()).isSameAs(shadow);
        assertThat(response.getHeaders().getFirst(DevPaycheckController.PROFILES_HEADER)).isEqualTo("local");
    }

    @Test
    void syncFalseOrPlaidRefusalNeverRunsPipeline() {
        ResponseEntity<?> noSync = controller.sandboxPaycheck(7L, acme(1, 0, false));
        doReturn(new PlaidCallResult<>(null, new PlaidApiError("INVALID_INPUT", "INVALID_ACCESS_TOKEN", null, "req-9", 400, PlaidErrorClass.PERMANENT)))
                .when(gateway).call(eq("sandbox_transactions_create"), any());
        ResponseEntity<?> refused = controller.sandboxPaycheck(7L, acme(1, 0, null));

        assertThat((Map<String, Object>) noSync.getBody()).containsEntry("syncStatus", "NOT_RUN");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(((ErrorCodeResponse) refused.getBody()).code()).isEqualTo("INVALID_ACCESS_TOKEN");
        verify(pipeline, never()).run(any(), any(), any());
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.DevPaycheckControllerTest' --console=plain`.

Expected: compilation fails with `cannot find symbol: class DevPaycheckController`.

- [ ] **Step 4: The dev controller**

`javap` on plaid-java 46.0.0: `CustomSandboxTransaction` has `dateTransacted`, `datePosted` (`LocalDate`), `amount(Double)`, `description`, `isoCurrencyCode`, `accountId`; `SandboxItemFireWebhookRequest.WebhookCodeEnum` has no `ENUM_UNKNOWN`, hence the closed map before `valueOf`.

Create `.../controller/DevPaycheckController.java`. Non-obvious imports: `com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome`, `com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason`, the same eight `com.plaid.client.model` types as the test, `com.plaid.client.request.PlaidApi`, `java.util.function.BiFunction`.

```java
package com.investingapp.backend.controller;

/** Spec 12.2 dev verbs. No method opens a transaction; Plaid first, then T14's pipeline outside any. */
@RestController
@RequestMapping("/api/dev/paycheck")
@CrossOrigin(origins = "*")
@Profile({"local", "dev", "test", "ci"})
public class DevPaycheckController {

    private static final Logger logger = LoggerFactory.getLogger(DevPaycheckController.class);
    static final String PROFILES_HEADER = "X-Fred-Profiles";
    static final int MAX_PER_CALL = 10;
    static final int MAX_DAYS_BACK = 730;
    static final int MAX_DESCRIPTION = 255;
    static final Map<String, WebhookType> FIREABLE = Map.of(
            "SYNC_UPDATES_AVAILABLE", WebhookType.TRANSACTIONS, "RECURRING_TRANSACTIONS_UPDATE", WebhookType.TRANSACTIONS,
            "LOGIN_REPAIRED", WebhookType.ITEM, "PENDING_DISCONNECT", WebhookType.ITEM,
            "USER_PERMISSION_REVOKED", WebhookType.ITEM, "ERROR", WebhookType.ITEM);

    public record SandboxPaycheckRequest(BigDecimal amount, Integer count, String description, Integer cadenceDays,
                                         Integer endDaysAgo, Boolean sync) {
    }

    public record FireRequest(String code) {
    }

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final PlaidApi plaidApi;
    private final PlaidGateway plaidGateway;
    private final PaycheckSyncPipeline pipeline;
    private final PaycheckReconcileService reconcileService;
    private final ShadowReportService shadowReportService;
    private final Environment environment;

    // Constructor: mechanical repetition (2) in the header.

    @PostMapping("/{userId}/sandbox/paycheck")
    public ResponseEntity<?> sandboxPaycheck(@PathVariable Long userId, @RequestBody SandboxPaycheckRequest body) {
        String invalid = validate(body);
        if (invalid != null) {
            return ResponseEntity.badRequest().body(new ErrorCodeResponse("INVALID_FIXTURE", invalid));
        }
        return withItem(userId, (user, token) -> {
            SandboxTransactionsCreateRequest request = new SandboxTransactionsCreateRequest().accessToken(token)
                    .transactions(fixture(body, user.getPlaidAccountId(), EasternTime.today()));
            PlaidCallResult<SandboxTransactionsCreateResponse> created =
                    plaidGateway.call("sandbox_transactions_create", plaidApi.sandboxTransactionsCreate(request));
            if (!created.ok()) {
                return plaidRefused(created.error());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("created", body.count());
            if (Boolean.FALSE.equals(body.sync())) {
                result.put("syncStatus", "NOT_RUN");
            } else {
                PipelineOutcome outcome = pipeline.run(userId, user.getPlaidItemId(), SyncReason.MANUAL);
                result.put("syncStatus", outcome == null || outcome.sync() == null ? null : outcome.sync().status());
                result.put("detectionState", outcome == null || outcome.evaluation() == null ? null : outcome.evaluation().state());
            }
            logger.info("dev.paycheck.sandbox_fixture count={} sync_status={}", body.count(), result.get("syncStatus"));
            return ResponseEntity.ok(result);
        });
    }

    @PostMapping("/{userId}/sandbox/fire")
    public ResponseEntity<?> fire(@PathVariable Long userId, @RequestBody FireRequest body) {
        String code = body == null || body.code() == null ? "" : body.code().trim().toUpperCase(Locale.ROOT);
        WebhookType type = FIREABLE.get(code);
        if (type == null) {
            return ResponseEntity.badRequest().body(new ErrorCodeResponse("UNSUPPORTED_CODE", "code must be one of " + new TreeSet<>(FIREABLE.keySet())));
        }
        return withItem(userId, (user, token) -> {
            PlaidCallResult<SandboxItemFireWebhookResponse> fired = plaidGateway.call("sandbox_item_fire_webhook",
                    plaidApi.sandboxItemFireWebhook(new SandboxItemFireWebhookRequest().accessToken(token)
                            .webhookType(type).webhookCode(SandboxItemFireWebhookRequest.WebhookCodeEnum.valueOf(code))));
            if (!fired.ok()) {
                return plaidRefused(fired.error());
            }
            logger.info("dev.paycheck.sandbox_fire code={}", code);
            return ResponseEntity.ok(Map.of("code", code, "fired", Boolean.TRUE.equals(fired.body().getWebhookFired())));
        });
    }

    @PostMapping("/{userId}/sandbox/reset-login")
    public ResponseEntity<?> resetLogin(@PathVariable Long userId) {
        return withItem(userId, (user, token) -> {
            PlaidCallResult<SandboxItemResetLoginResponse> reset = plaidGateway.call("sandbox_item_reset_login",
                    plaidApi.sandboxItemResetLogin(new SandboxItemResetLoginRequest().accessToken(token)));
            if (!reset.ok()) {
                return plaidRefused(reset.error());
            }
            return ResponseEntity.ok(Map.of("resetLogin", Boolean.TRUE.equals(reset.body().getResetLogin())));
        });
    }

    @PostMapping("/{userId}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable Long userId, @RequestParam(value = "date", required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (userRepository.findById(userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reconcileService.runForUser(userId, date != null ? date : EasternTime.today()));
    }

    @GetMapping("/shadow-report")
    public ResponseEntity<ShadowReport> shadowReport(@RequestParam(value = "since", required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return ResponseEntity.ok().header(PROFILES_HEADER, String.join(",", environment.getActiveProfiles()))
                .body(shadowReportService.build(since));
    }

    private ResponseEntity<?> withItem(Long userId, BiFunction<User, String, ResponseEntity<?>> action) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = found.get();
        if (user.getPlaidAccessToken() == null || user.getPlaidItemId() == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorCodeResponse("NO_ITEM", "no linked Plaid Item"));
        }
        return action.apply(user, encryptionService.decrypt(user.getPlaidAccessToken()));
    }

    static String validate(SandboxPaycheckRequest body) {
        if (body == null) {
            return "body is required";
        }
        if (body.amount() == null || body.amount().signum() <= 0 || body.amount().scale() > 2) {
            return "amount: positive, at most 2 decimals";
        }
        if (body.count() == null || body.count() < 1 || body.count() > MAX_PER_CALL) {
            return "count: 1 to 10";
        }
        if (body.description() == null || body.description().isBlank() || body.description().length() > MAX_DESCRIPTION) {
            return "description: 1 to 255 characters";
        }
        if (body.cadenceDays() == null || body.cadenceDays() < 1 || body.cadenceDays() > 31) {
            return "cadenceDays: 1 to 31";
        }
        int end = body.endDaysAgo() == null ? 0 : body.endDaysAgo();
        if (end < 0 || end + (long) (body.count() - 1) * body.cadenceDays() > MAX_DAYS_BACK) {
            return "endDaysAgo: 0 or more, oldest deposit at most 730 days back";
        }
        return null;
    }

    /** Oldest first; a Plaid inflow is negative. */
    static List<CustomSandboxTransaction> fixture(SandboxPaycheckRequest body, String accountId, LocalDate todayET) {
        LocalDate newest = todayET.minusDays(body.endDaysAgo() == null ? 0 : body.endDaysAgo());
        List<CustomSandboxTransaction> rows = new ArrayList<>();
        for (int i = body.count() - 1; i >= 0; i--) {
            LocalDate date = newest.minusDays((long) i * body.cadenceDays());
            CustomSandboxTransaction row = new CustomSandboxTransaction().dateTransacted(date).datePosted(date)
                    .amount(body.amount().negate().doubleValue()).description(body.description()).isoCurrencyCode("USD");
            if (accountId != null) {
                row.accountId(accountId);
            }
            rows.add(row);
        }
        return rows;
    }

    private static ResponseEntity<ErrorCodeResponse> plaidRefused(PlaidApiError error) {
        String code = error == null || error.errorCode() == null ? "PLAID_ERROR" : error.errorCode();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorCodeResponse(code, "Plaid refused; see the plaid.call log line"));
    }
}
```

Run the Step 3 command.

Expected: `BUILD SUCCESSFUL`; its XML shows `tests="6" skipped="0" failures="0"`.

- [ ] **Step 5: Failing test for the prod guard**

Create `T/config/DevToolsProdGuardTest.java`:

```java
package com.investingapp.backend.config;

class DevToolsProdGuardTest {

    private static DevToolsProdGuard guard(String profile) {
        DevToolsProdGuard guard = new DevToolsProdGuard();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        guard.setEnvironment(environment);
        return guard;
    }

    private static DefaultListableBeanFactory withDevPaycheckController() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("devPaycheckController", new RootBeanDefinition(DevPaycheckController.class));
        return beanFactory;
    }

    @Test
    void devControllerRefusedInProdByGuard() {
        assertThatThrownBy(() -> guard(DevToolsProdGuard.PROD_PROFILE).postProcessBeanFactory(withDevPaycheckController()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dev tooling must never be registered in the prod profile")
                .hasMessageContaining("devPaycheckController (DevPaycheckController)");
    }

    @Test
    void devControllerAllowedOutsideProd() {
        for (String profile : DevToolsConfig.DEV_TOOL_PROFILES) {
            assertThatCode(() -> guard(profile).postProcessBeanFactory(withDevPaycheckController())).doesNotThrowAnyException();
        }
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.DevToolsProdGuardTest' --console=plain`.

Expected: 2 tests, 1 failure: `devControllerRefusedInProdByGuard` with `Expecting code to raise a throwable.` (the name scan matches only `DevAuth` and `Simulated`).

- [ ] **Step 6: Add the controller to the guard**

In `.../config/DevToolsProdGuard.java`, make two insertions:
1. After `import com.investingapp.backend.controller.DevAuthController;`, insert `import com.investingapp.backend.controller.DevPaycheckController;`.
2. After the line `        addIfPresent(beanFactory, DevPlaidController.class, offenders);`, insert:

```java
        addIfPresent(beanFactory, DevPaycheckController.class, offenders);
```

Run the Step 5 command. Expected: `tests="2" skipped="0" failures="0"`.

- [ ] **Step 7: Failing test for the admin report verb**

Create `T/controller/admin/AdminPaycheckShadowReportTest.java` (a mock per constructor parameter, so T18's dependency list is never restated):

```java
package com.investingapp.backend.controller.admin;

class AdminPaycheckShadowReportTest {

    @Test
    void shadowReportIsAuditedAsAGet() throws Exception {
        ShadowReportService shadowReports = mock(ShadowReportService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        Constructor<?> constructor = AdminPaycheckController.class.getConstructors()[0];
        assertThat(AdminPaycheckController.class.getConstructors()).hasSize(1);
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == ShadowReportService.class ? shadowReports : types[i] == AdminAuditService.class ? audit : mock(types[i]);
        }
        AdminPaycheckController controller = (AdminPaycheckController) constructor.newInstance(args);
        LocalDate since = LocalDate.of(2026, 10, 28);
        ShadowReport report = new ShadowReport(since, LocalDate.of(2026, 11, 4), true, new ShadowReport.Summary(0, 0, 0, 0, 0, 0.0), List.of());
        when(shadowReports.build(since)).thenReturn(report);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/paycheck/shadow-report");
        when(audit.actor(request)).thenReturn("andy");

        ResponseEntity<ShadowReport> response = controller.shadowReport(since, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(report);
        verify(audit).record("andy", null, AdminPaycheckController.VERB_SHADOW_REPORT, null, null, Map.of("users", 0, "shadowCycles", 0), null);
        verify(audit, never()).recordInTransaction(any(), any(), any(), any(), any(), any(), any());
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.admin.AdminPaycheckShadowReportTest' --console=plain`.

Expected: compilation fails with `cannot find symbol` for both `method shadowReport(LocalDate,MockHttpServletRequest)` and `VERB_SHADOW_REPORT`.

- [ ] **Step 8: Add the verb to AdminPaycheckController**

In `.../controller/admin/AdminPaycheckController.java` (T18): add the missing imports (`ShadowReport`, `ShadowReportService`, `jakarta.servlet.http.HttpServletRequest`, `DateTimeFormat`, `GetMapping`, `RequestParam`, `LocalDate`, `Map`); add `static final String VERB_SHADOW_REPORT = "paycheck.shadow-report";` (T18's verb style) under the class's opening brace; add `private final ShadowReportService shadowReportService;` after the last `private final` field, append it as the last constructor parameter and assign it last in the body (each test calling `new AdminPaycheckController(`, found with `grep -rn 'new AdminPaycheckController(' backend/src/test`, gains `mock(ShadowReportService.class)` as its last argument); then add this method after the last `@GetMapping` method:

```java
    /** Spec 12.4 weekly shadow review (R13). A GET, so audited through the REQUIRES_NEW path. */
    @GetMapping("/shadow-report")
    public ResponseEntity<ShadowReport> shadowReport(@RequestParam(value = "since", required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
                                                     HttpServletRequest request) {
        ShadowReport report = shadowReportService.build(since);
        adminAuditService.record(adminAuditService.actor(request), null, VERB_SHADOW_REPORT, null, null,
                Map.of("users", report.users().size(), "shadowCycles", report.summary().shadowCycles()), null);
        return ResponseEntity.ok(report);
    }
```

Run the Step 7 command, then `--tests 'com.investingapp.backend.controller.admin.*'`. Expected: `tests="1" skipped="0" failures="0"`; T18's classes keep their counts with `failures="0"`.

- [ ] **Step 9: The Sandbox skip library and the three scripts**

Failing check first: `FRED_API_BASE= bash test/api/paycheck_shadow.sh; echo "exit=$?"` prints `No such file or directory` and `exit=127`. The scripts keep their directory in `PAYCHECK_API_DIR` (sourcing `lib/auth.sh` reassigns `SCRIPT_DIR`) and never source `auth.sh`: the dev and admin surfaces take keys, not a JWT.

Create `test/api/lib/sandbox.sh`:

```bash
#!/bin/bash
# Paycheck Autopilot Sandbox guard (spec 13.2). Prints [SKIP] and exits 0 unless FRED_API_BASE is set, the
# dev shadow report answers 200 from a non-ci profile, and FRED_PAYCHECK_USER_ID / _PUBLIC_ID are set.

sandbox_skip() {
  echo "[SKIP] $1"
  exit 0
}

[ -n "$FRED_API_BASE" ] || sandbox_skip "FRED_API_BASE is not set."
command -v python3 >/dev/null 2>&1 || sandbox_skip "python3 is not installed."
DEV_TOOLS_KEY="${DEV_TOOLS_KEY:-ci-local-dev-tools}"
sandbox_headers=$(mktemp)
sandbox_status=$(curl -s -o /dev/null -D "$sandbox_headers" -w "%{http_code}" \
  -H "X-Dev-Tools-Key: $DEV_TOOLS_KEY" "$FRED_API_BASE/api/dev/paycheck/shadow-report")
sandbox_profiles=$(grep -i '^x-fred-profiles:' "$sandbox_headers" | cut -d: -f2- | tr -d ' \r\n')
rm -f "$sandbox_headers"
[ "$sandbox_status" = "200" ] || sandbox_skip "dev shadow report answered HTTP $sandbox_status."
[ -n "$sandbox_profiles" ] || sandbox_skip "no X-Fred-Profiles header."
case ",$sandbox_profiles," in
  *,ci,*) sandbox_skip "ci profile: PlaidApi is the offline CiStubConfig proxy." ;;
esac
[[ "$FRED_PAYCHECK_USER_ID" =~ ^[0-9]+$ ]] || sandbox_skip "FRED_PAYCHECK_USER_ID is not set to a user id."
[ -n "$FRED_PAYCHECK_PUBLIC_ID" ] || sandbox_skip "FRED_PAYCHECK_PUBLIC_ID is not set."

PAYCHECK_DEV="/api/dev/paycheck/$FRED_PAYCHECK_USER_ID"
DETECTED_ONLY_TYPES="'PAYDAY_D1_DETECTED','PAYCHECK_FOUND','SKIP_AFFORDABILITY','SKIP_CATCHUP','SKIP_HOLD_ACH','SKIP_HOLD','SKIP_NO_AUTH','DORMANT','INTERRUPTION','WELCOME_BACK','NEW_EMPLOYER','CADENCE_CHANGE','DORMANT_120','AUTHORIZATION_NEEDED'"

dev_call() {
  local method="$1" path="$2" body="${3:-}" tmp
  tmp=$(mktemp)
  local args=(-s -o "$tmp" -w "%{http_code}" -X "$method" "$FRED_API_BASE$path" -H "X-Dev-Tools-Key: $DEV_TOOLS_KEY" -H "Content-Type: application/json")
  [ -n "$body" ] && args+=(-d "$body")
  DEV_STATUS=$(curl "${args[@]}")
  DEV_BODY=$(cat "$tmp")
  rm -f "$tmp"
}

admin_call() {
  [ -n "$ADMIN_TOOLS_KEY" ] || return 1
  local tmp
  tmp=$(mktemp)
  DEV_STATUS=$(curl -s -o "$tmp" -w "%{http_code}" -X "$1" "$FRED_API_BASE$2" \
    -H "X-Admin-Key: $ADMIN_TOOLS_KEY" -H "X-Admin-Actor: ${FRED_ADMIN_ACTOR:-sandbox-script}")
  DEV_BODY=$(cat "$tmp")
  rm -f "$tmp"
}

report_field() {
  dev_call GET "/api/dev/paycheck/shadow-report"
  printf '%s' "$DEV_BODY" | python3 -c '
import json, sys
rows = [u for u in json.load(sys.stdin).get("users", []) if u.get("publicId") == sys.argv[2]]
value = rows[0].get(sys.argv[1]) if rows else None
print(len(value) if isinstance(value, list) else ("" if value is None else value))
' "$1" "$FRED_PAYCHECK_PUBLIC_ID"
}

report_top() {
  dev_call GET "/api/dev/paycheck/shadow-report"
  printf '%s' "$DEV_BODY" | python3 -c 'import json, sys; print(str(json.load(sys.stdin).get(sys.argv[1])).lower())' "$1"
}

# wait_for NAME REGEX SECONDS [reconcile]: poll the report every 5 s; report_field prints a list's length.
wait_for() {
  local name="$1" pattern="$2" seconds="$3" nudge="${4:-}" waited=0 value=""
  while [ "$waited" -le "$seconds" ]; do
    [ "$nudge" = "reconcile" ] && dev_call POST "$PAYCHECK_DEV/reconcile"
    value=$(report_field "$name")
    [[ "$value" =~ ^($pattern)$ ]] && return 0
    sleep 5
    waited=$((waited + 5))
  done
  echo "  last $name=$value"
  return 1
}

check() {
  local label="$1"
  shift
  if "$@"; then echo "[PASS] $label"; PASS=$((PASS + 1)); else echo "[FAIL] $label"; FAIL=$((FAIL + 1)); fi
}

# mysql_scalar SQL: returns 1 without FRED_MYSQL_DB; password from MYSQL_PWD or ~/.my.cnf, never an argument.
mysql_scalar() {
  [ -n "$FRED_MYSQL_DB" ] || return 1
  mysql -N -B -u "${FRED_MYSQL_USER:-root}" -h "${FRED_MYSQL_HOST:-127.0.0.1}" "$FRED_MYSQL_DB" -e "$1"
}
```

Create `test/api/paycheck_shadow.sh`:

```bash
#!/bin/bash
# Spec 13.2 paycheck_shadow.sh: MANUAL kept, one SHADOW_CONFIRMED cycle, no DETECTED-only notice. Fresh user.
PAYCHECK_API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$PAYCHECK_API_DIR/lib/sandbox.sh"
PASS=0; FAIL=0
[ "$(report_field shadowCycles)" = "0" ] || sandbox_skip "user has shadow cycles; relink or use another user."
check "report says shadow=true" [ "$(report_top shadow)" = "true" ]
dev_call POST "$PAYCHECK_DEV/sandbox/paycheck" '{"amount":2450.00,"count":6,"description":"ACME CORP PAYROLL DIR DEP","cadenceDays":14,"endDaysAgo":14}'
check "history accepted (HTTP $DEV_STATUS)" [ "$DEV_STATUS" = "200" ]
check "a primary qualifies (PROVISIONAL or CONFIRMED)" wait_for detectionState 'PROVISIONAL|CONFIRMED' 120 reconcile
dev_call POST "$PAYCHECK_DEV/sandbox/paycheck" '{"amount":2450.00,"count":1,"description":"ACME CORP PAYROLL DIR DEP","cadenceDays":14,"endDaysAgo":0}'
check "today's deposit accepted (HTTP $DEV_STATUS)" [ "$DEV_STATUS" = "200" ]
check "exactly one SHADOW_CONFIRMED cycle" wait_for shadowCycles '1' 120 reconcile
if mode=$(mysql_scalar "SELECT mode FROM investment_schedules WHERE user_id = $FRED_PAYCHECK_USER_ID"); then
  check "schedule stays MANUAL (or has no row)" [ -z "$mode" -o "$mode" = "MANUAL" ]
  notices=$(mysql_scalar "SELECT COUNT(*) FROM email_log WHERE user_id = $FRED_PAYCHECK_USER_ID AND email_type IN ($DETECTED_ONLY_TYPES)")
  check "zero DETECTED-only email_log rows" [ "$notices" = "0" ]
else
  echo "[SKIP] mode and email_log checks: FRED_MYSQL_DB is not set"
fi
echo "---"
echo "Paycheck shadow: $PASS passed, $FAIL failed"
exit $FAIL
```

Create `test/api/paycheck_webhooks.sh`:

```bash
#!/bin/bash
# Spec 13.2 paycheck_webhooks.sh (Sandbox part). Destructive half only with FRED_PAYCHECK_DESTRUCTIVE=1.
PAYCHECK_API_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$PAYCHECK_API_DIR/lib/sandbox.sh"
PASS=0; FAIL=0
fire() { dev_call POST "$PAYCHECK_DEV/sandbox/fire" "{\"code\":\"$1\"}"; [ "$DEV_STATUS" = "200" ]; }
no_failed_inbox_rows() {
  admin_call GET "/api/admin/paycheck/webhooks?status=FAILED" || return 1
  [ "$DEV_STATUS" = "200" ] && [ "$(printf '%s' "$DEV_BODY" | python3 -c 'import json, sys; print(len(json.load(sys.stdin)))')" = "0" ]
}
check "RECURRING before SYNC fired" fire RECURRING_TRANSACTIONS_UPDATE
check "SYNC_UPDATES_AVAILABLE fired" fire SYNC_UPDATES_AVAILABLE
check "SYNC_UPDATES_AVAILABLE fired again" fire SYNC_UPDATES_AVAILABLE
sleep 45
check "Item still OK" [ "$(report_field itemStatus)" = "OK" ]
if [ -n "$ADMIN_TOOLS_KEY" ]; then check "no FAILED inbox rows" no_failed_inbox_rows; else echo "[SKIP] FAILED inbox check: ADMIN_TOOLS_KEY is not set"; fi
if [ "$FRED_PAYCHECK_DESTRUCTIVE" = "1" ]; then
  dev_call POST "$PAYCHECK_DEV/sandbox/reset-login"
  check "reset_login accepted (HTTP $DEV_STATUS)" [ "$DEV_STATUS" = "200" ]
  check "ITEM ERROR gives LOGIN_REQUIRED" wait_for itemStatus 'LOGIN_REQUIRED' 90
  check "LOGIN_REPAIRED fired" fire LOGIN_REPAIRED
  check "LOGIN_REPAIRED gives OK" wait_for itemStatus 'OK' 90
  check "USER_PERMISSION_REVOKED fired" fire USER_PERMISSION_REVOKED
  check "revocation gives REVOKED" wait_for itemStatus 'REVOKED' 90
  dev_call POST "$PAYCHECK_DEV/sandbox/fire" '{"code":"LOGIN_REPAIRED"}'
  if [ "$DEV_STATUS" = "200" ]; then
    sleep 30
    check "a trailing LOGIN_REPAIRED is a no-op" [ "$(report_field itemStatus)" = "REVOKED" ]
  else
    echo "[SKIP] trailing LOGIN_REPAIRED: Plaid refused it (HTTP $DEV_STATUS), the Item is removed"
  fi
else
  echo "[SKIP] destructive half: set FRED_PAYCHECK_DESTRUCTIVE=1 on a throwaway user"
fi
echo "---"
echo "Paycheck webhooks: $PASS passed, $FAIL failed"
exit $FAIL
```

Create `test/api/paycheck_lost_webhook.sh` (mechanical repetition (4): the shadow script's lines with these changes). Header: `# Spec 13.2 paycheck_lost_webhook.sh: "sync":false, then only the reconcile confirms. A true lost-webhook test only without the tunnel.` Keep the shebang, setup and precondition lines; drop the `shadow=true` check and the `mysql_scalar` block; both fixtures use `"amount":1900.00` and `"description":"GLOBEX PAYROLL"`; the second body ends `"endDaysAgo":0,"sync":false}` and its `today's deposit accepted` check becomes `check "today's deposit created without a sync" [ "$DEV_STATUS" = "200" ]` and `check "the response says no sync ran" [ "$(printf '%s' "$DEV_BODY" | python3 -c 'import json, sys; print(json.load(sys.stdin).get("syncStatus"))')" = "NOT_RUN" ]`; the last check's label becomes `"the reconcile alone confirms one shadow cycle"`; the summary line reads `Paycheck lost webhook: $PASS passed, $FAIL failed`.

In `test/api/run-all.sh`, after the line `run_suite "Beneficiaries"  beneficiaries.sh`, insert:

```bash
run_suite "Paycheck shadow (Sandbox, self-skipping)"       paycheck_shadow.sh
run_suite "Paycheck webhooks (Sandbox, self-skipping)"     paycheck_webhooks.sh
run_suite "Paycheck lost webhook (Sandbox, self-skipping)" paycheck_lost_webhook.sh
```

Then run:

```
chmod +x test/api/lib/sandbox.sh test/api/paycheck_shadow.sh test/api/paycheck_webhooks.sh test/api/paycheck_lost_webhook.sh
for f in test/api/lib/sandbox.sh test/api/paycheck_shadow.sh test/api/paycheck_webhooks.sh test/api/paycheck_lost_webhook.sh test/api/run-all.sh; do bash -n "$f" && echo "syntax ok $f"; done
for f in paycheck_shadow paycheck_webhooks paycheck_lost_webhook; do env -u FRED_API_BASE bash test/api/$f.sh; echo "exit=$?"; done
FRED_API_BASE=http://127.0.0.1:9 bash test/api/paycheck_shadow.sh; echo "exit=$?"
```

Expected: five `syntax ok` lines; three `[SKIP] FRED_API_BASE is not set.` each followed by `exit=0`; then `[SKIP] dev shadow report answered HTTP 000.` and `exit=0` (nothing listens on port 9).

- [ ] **Step 10: The runbook**

Create `FREDdocs/runbooks/paycheck-autopilot.md` with exactly this content:

````markdown
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
````

Then `grep -c "$(printf '\342\200\224')" FREDdocs/runbooks/paycheck-autopilot.md` (octal bytes of U+2014, the em dash) prints `0`.

- [ ] **Step 11: Run everything this task touches**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.ShadowReportServiceTest' --tests 'com.investingapp.backend.controller.DevPaycheckControllerTest' --tests 'com.investingapp.backend.config.DevToolsProdGuardTest' --tests 'com.investingapp.backend.config.DevToolsGatingTest' --tests 'com.investingapp.backend.controller.admin.AdminPaycheck*' --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/*.xml
cd .. && grep -rln "$(printf '\342\200\224')" backend/src/main/java/com/investingapp/backend/{service/ShadowReportService,controller/DevPaycheckController,dto/admin/ShadowReport,controller/admin/AdminPaycheckController,config/DevToolsProdGuard}.java test/api/lib/sandbox.sh test/api/paycheck_*.sh test/api/run-all.sh FREDdocs/runbooks/paycheck-autopilot.md
```

Expected: `BUILD SUCCESSFUL`; the new classes at 7, 6, 2 and 1 tests, the others at their own counts, every line `skipped="0" failures="0"`; the em dash grep prints nothing.

- [ ] **Step 12: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (shared tree; the brace lists expand to the exact 16 paths, T18's `AdminPaycheckControllerTest` included), then:

```
git add backend/src/main/java/com/investingapp/backend/{dto/admin/ShadowReport,service/ShadowReportService,controller/DevPaycheckController,controller/admin/AdminPaycheckController,config/DevToolsProdGuard}.java backend/src/test/java/com/investingapp/backend/{service/ShadowReportServiceTest,controller/DevPaycheckControllerTest,config/DevToolsProdGuardTest,controller/admin/AdminPaycheckShadowReportTest,controller/admin/AdminPaycheckControllerTest}.java test/api/lib/sandbox.sh test/api/paycheck_{shadow,webhooks,lost_webhook}.sh test/api/run-all.sh FREDdocs/runbooks/paycheck-autopilot.md
git commit -m "feat(paycheck): shadow report, dev Sandbox tools and the Phase 1 runbook"
```

### Verification

Run the Step 11 command. Expected, from `backend/build/test-results/test/TEST-<class>.xml` and never the console: `ShadowReportServiceTest` 7, `DevPaycheckControllerTest` 6, `DevToolsProdGuardTest` 2, `AdminPaycheckShadowReportTest` 1, each `skipped="0" failures="0"`.

Full suite on a scratch MySQL (the gate is zero skips):

```
read -rs MYSQL_PWD && export MYSQL_PWD
mysql -u root -e "CREATE DATABASE fred_t19_scratch"
cd backend && SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t19_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$MYSQL_PWD" \
  PLAID_WEBHOOK_URL=https://ci.invalid/api/plaid/webhook PLAID_REDIRECT_URI=https://local.fredvested.com/link-bank PLAID_LINK_CUSTOMIZATION=default \
  ./gradlew cleanTest test --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{t+=$2; s+=$4; f+=$6} END {print "tests="t" skipped="s" failures="f}'
mysql -u root -e "DROP DATABASE fred_t19_scratch"
```

Expected: `skipped=0 failures=0`; `tests` is the post-T18 total plus 16.

Scripts: Step 9's output. Locally, with the tunnel and a fresh Sandbox user each: `paycheck_shadow.sh` ends `Paycheck shadow: 7 passed, 0 failed` (5 passed and one `[SKIP]` without `FRED_MYSQL_DB`); `paycheck_lost_webhook.sh` ends `Paycheck lost webhook: 5 passed, 0 failed`; `paycheck_webhooks.sh` ends `Paycheck webhooks: 5 passed, 0 failed` with `ADMIN_TOOLS_KEY` set and `FRED_PAYCHECK_DESTRUCTIVE` unset (4 and one `[SKIP]` without the key).

### Produces

- `dto.admin.ShadowReport` with nested `Summary`, `UserRow`, `CycleRow`, as in Step 2.
- `service.ShadowReportService.build(LocalDate since)`; `public static String payerTokenHash(String)` (SHA-256 hex, first 16).
- `GET /api/admin/paycheck/shadow-report?since=` on `AdminPaycheckController` (`VERB_SHADOW_REPORT = "paycheck.shadow-report"`, audited).
- `controller.DevPaycheckController` (`/api/dev/paycheck`, `@Profile({"local", "dev", "test", "ci"})`, refused in prod by `DevToolsProdGuard`): `POST /{userId}/sandbox/paycheck` (`SandboxPaycheckRequest(amount, count, description, cadenceDays, endDaysAgo, sync)`), `POST /{userId}/sandbox/fire` (`FireRequest(code)`, closed `FIREABLE`), `POST /{userId}/sandbox/reset-login`, `POST /{userId}/reconcile?date=`, `GET /shadow-report?since=` (`PROFILES_HEADER = "X-Fred-Profiles"`); the Phase 2 seam for `simulate-cron` and `clock`.
- `test/api/lib/sandbox.sh` (`sandbox_skip`, `dev_call`, `admin_call`, `report_field`, `report_top`, `wait_for`, `check`, `mysql_scalar`, `DETECTED_ONLY_TYPES`), the three `paycheck_*.sh` suites in `run-all.sh`, and the runbook.
