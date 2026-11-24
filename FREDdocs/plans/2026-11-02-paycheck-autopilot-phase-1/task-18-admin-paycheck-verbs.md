### Task 18: admin-paycheck-verbs

Full code for the steps this file gives as tables or patterns is in task-18-admin-paycheck-verbs-appendix.md; read both.

The twelve Phase 1 operator verbs of spec 12.2 under `/api/admin/paycheck/*` (R12), each written to `admin_actions`. `clear-review` is the only Phase 1 code that crosses a blocking-hold boundary, so it alone emits: `schedule_resumed(system, review_cleared)` (R3). Nothing here moves money, writes `status` or `mode`, changes `isReadyForInvestment`, or writes a blocking hold.

`.../` = `backend/src/main/java/com/investingapp/backend/`; `T/` = `backend/src/test/java/com/investingapp/backend/`. Lines are HEAD 137998c; earlier tasks edit the five modified files, so anchors are quoted text and lines are hints.

**Facts this task depends on:**

1. `is_paused` is gone; `status` (`PENDING_BANK | ACTIVE | PAUSED_BY_USER`) has four Phase 0 writers and none here. T03's `PhaseOneMoneyInvarianceTest` fails a `service/Admin*` or `controller/admin/**` file containing `setStatus(Status.`, `setMode(`, `getIsPaused(` or `isPausedForAnyReason(`; T13's `onlyAllowlistedFilesCallSetHoldReason` allowlists `service/AdminPaycheckService`.
2. `hold_reason` is the system's stop, orthogonal to `status`; `BLOCKING_HOLDS` = `ACH_RETURN_UNAUTHORIZED`, `ACH_RETURN_ACCOUNT`, `MANUAL_REVIEW`. The runnable rule lives only in `isReadyForInvestment` ("call it, do not restate it"), hence Deviation 1.
3. A rejected applicant keeps the row (`account_status = 'REJECTED'`) and a `MANUAL_REVIEW` hold, their only reliable stop; `schedule_cancelled(kyc_rejected)` is already recorded, so `clear-review` refuses them (Decision 32).
4. Events (handoff 7): only on a real transition; exactly `initiator` then `reason`; via `AnalyticsOutboxService.record` inside the state-changing transaction; a new reason goes into `ServerAnalyticsEvent`, `LEGITIMATE_VALUES` and `analytics-events.md`. No new key or event name.
5. Decision 31: `hold` writes `ITEM_LOGIN_REQUIRED`, `PERMISSION_REVOKED`, `CONSENT_REQUIRED`, `BANK_CHANGE` or `null`; else 422 `HOLD_RESERVED`; any write over a blocking hold is 409 `HOLD_BLOCKING`; no event (R3).
6. Decision 56: mutating verbs are ONE `TransactionTemplate` under `findByIdForUpdate` writing state, outbox row and audit row (`recordInTransaction`, `MANDATORY`); pipeline verbs call with nothing open and audit afterwards in their own template; `record` (`REQUIRES_NEW`) only for refusals and GETs.
7. Audit JSON is only `{status, holdReason, detectionState, primaryStreamId}`; INFO logs carry verb, HTTP status and code. The `status` body shows `raw_descriptor` to the operator (R13) and is never logged or audited.
8. T17's `AdminKeyFilter` guards `/api/admin/*`; no CORS.

**Files:**
- Create: `.../controller/admin/AdminPaycheckController.java`, `.../service/AdminPaycheckService.java`, `.../dto/admin/AdminUserStatusView.java`, `.../dto/admin/PlaidItemDump.java`
- Modify: `.../util/ServerAnalyticsEvent.java` (after `public static final String REASON_KYC_REJECTED = "kyc_rejected";`, HEAD `:98`); `.../repository/PlaidWebhookEventRepository.java` (after `Optional<PlaidWebhookEvent> findByDedupeKey(String dedupeKey);`, HEAD `:13`); `FREDdocs/analytics-events.md` (paragraph beginning `The three schedule transitions carry`, HEAD `:261-266`); `FREDdocs/API_ENDPOINTS.md` (before the `---` preceding `## Authentication Notes`, HEAD `:230`)
- Test: create `T/service/AdminPaycheckServiceTest.java`, `T/service/AdminPaycheckServiceRollbackTest.java` (`@RequiresCiDatabase`), `T/controller/admin/AdminPaycheckControllerTest.java`; modify `T/service/AnalyticsOutboxForbiddenPayloadTest.java` (`reason` domain, HEAD `:212-217`), `T/service/ScheduleTransitionAnalyticsTest.java` (import after HEAD `:7`, one case before `// --- Site 1: the trap.`, HEAD `:432`)

No property is added, so `application-local.properties` needs no hand edit.

**Interfaces:**
- Consumes: T23 `InvestmentSchedule.isBlockingHold(HoldReason)` (T23 runs before this task). T17 `AdminAuditService.record(actor, userId, verb, targetId, before, after, reason)` (`REQUIRES_NEW`), `recordInTransaction(...)` (`MANDATORY`), `actor(HttpServletRequest)`. T16 `PaycheckDetectionStatusService.status(User)`. T14 `PaycheckSyncPipeline.run(Long, String, SyncReason)` (`PipelineOutcome(sync, streams, evaluation, itemState)`), `PlaidWebhookProcessor.replay(Long): ReplayResult` (nested `REQUEUED | NOT_FOUND | IN_FLIGHT`), `findByStatusOrderByReceivedAtAsc`. T15 `PaycheckReconcileService.runForUser(Long, LocalDate)`. T11/T12 `PaycheckDetectionService.evaluate(Long, EvaluationTrigger, LocalDate)` (`EvaluationOutcome(state, primaryStreamId, primaryChanged, switched, excluded)`). T08 `SyncReason` (nested in `PlaidTransactionsSyncService`), `SyncOutcome.status()`, `.error()`; T06 `PlaidApiError.errorCode()`, `T/support/RecordingTransactionManager` (field `open`); T10 `StreamsReadOutcome.status()`. T02 `PaycheckStream`, `PaycheckCycle` (Lombok accessors, camelCase of each V6 column), their enums and `ADMIN` reasons, `findByUserIdOrderByIdAsc`, `findByUserIdAndState`, the schedule mirror accessors, the seven new `User` getters, `ColumnText.fit`. Phase 0 `findByIdForUpdate` (`UserRepository:83`), `findTopByUserOrderByCreatedAtDesc` (`:19`).
- Produces: the Produces recap at the end.

- [ ] **Step 1: Confirm the consumed signatures (read only)**

```
cd backend
ls src/main/java/com/investingapp/backend/service/{AdminAuditService,PaycheckSyncPipeline,PlaidWebhookProcessor,PaycheckReconcileService,PaycheckDetectionService,PaycheckDetectionStatusService}.java
grep -n 'recordInTransaction\|public .* record(\|public String actor' src/main/java/com/investingapp/backend/service/AdminAuditService.java
grep -n 'enum SyncReason' src/main/java/com/investingapp/backend/service/PlaidTransactionsSyncService.java
grep -n 'public .* run(\|public .* replay(\|public .* runForUser(' src/main/java/com/investingapp/backend/service/{PaycheckSyncPipeline,PlaidWebhookProcessor,PaycheckReconcileService}.java
grep -n 'open' src/test/java/com/investingapp/backend/support/RecordingTransactionManager.java
grep -n 'public static boolean isBlockingHold(HoldReason' src/main/java/com/investingapp/backend/model/InvestmentSchedule.java
```

Expected: every path exists, every grep prints a line, `SyncReason` is nested and `open` is readable from another test package. If not, stop and report it.

- [ ] **Step 2: Failing test for the new reason**

`T/service/ScheduleTransitionAnalyticsTest.java`: after `import com.investingapp.backend.repository.InvestmentScheduleRepository;` add `import com.investingapp.backend.util.ServerAnalyticsEvent;`. Immediately before `    // --- Site 1: the trap. A schedule is BORN paused, which is not a pause. ---------------------` insert:

```java
    // --- Site 6: clear-review (cases in AdminPaycheckServiceTest and its RollbackTest) -----------

    @Test
    void reviewClearedIsAClosedReasonWithItsWireSpelling() {
        assertEquals("review_cleared", ServerAnalyticsEvent.REASON_REVIEW_CLEARED);
    }

```

`T/service/AnalyticsOutboxForbiddenPayloadTest.java`, `reason` domain (T11 left it ending `REASON_KYC_REJECTED,` then `REASON_SUCCESSION));`): after the line `                ServerAnalyticsEvent.REASON_KYC_REJECTED,` insert `                ServerAnalyticsEvent.REASON_REVIEW_CLEARED,`.

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.ScheduleTransitionAnalyticsTest' --console=plain`. Expected: `compileTestJava` fails with `cannot find symbol` / `symbol: variable REASON_REVIEW_CLEARED` / `location: class ServerAnalyticsEvent`.

- [ ] **Step 3: Add the reason and document it**

`.../util/ServerAnalyticsEvent.java`, directly after `    public static final String REASON_KYC_REJECTED = "kyc_rejected";` (before any T11 line that follows; add, never reorder):

```java
    /** An operator cleared MANUAL_REVIEW with the admin clear-review verb (system resume, R3). */
    public static final String REASON_REVIEW_CLEARED = "review_cleared";
```

`FREDdocs/analytics-events.md`: in the paragraph beginning `The three schedule transitions carry`, insert `, ` plus `` `review_cleared` `` directly after the first `` `kyc_rejected` ``. After the line `FREDdocs/metrics.md for how `v_schedule_churn` reads them.` add a blank line and:

```markdown
`review_cleared` is a system resume: an operator lifted a `MANUAL_REVIEW` hold with the admin
`clear-review` verb on a schedule whose status was already `ACTIVE`. It is never recorded for a
schedule the user paused or that is still waiting for its bank, and never for a rejected applicant.
```

Run the Verification rows for `ScheduleTransitionAnalyticsTest`, `AnalyticsOutboxForbiddenPayloadTest` and `ServerAnalyticsEventParityTest`. Expected: the counts given there, read with `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/*.xml`, never from the console.

- [ ] **Step 4: Failing service tests**

Create `T/service/AdminPaycheckServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.*;
import com.investingapp.backend.model.InvestmentSchedule.Status;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.repository.*;
import com.investingapp.backend.service.AdminPaycheckService.VerbResult;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidWebhookProcessor.ReplayResult;
import com.investingapp.backend.support.RecordingTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AdminPaycheckServiceTest {

    static Stream<HoldReason> blockingHolds() { // T23's list, never restated
        return Arrays.stream(HoldReason.values()).filter(InvestmentSchedule::isBlockingHold);
    }

    private static final String ACTOR = "ops-test";
    private static final Long UID = 7L;
    private static final String DESCRIPTOR = "ACME CORP PAYROLL 0042";
    private static final String TOKEN = "ENC:access-token-ciphertext";
    private static final String CURSOR = "cursor-must-never-leave";

    private final UserRepository users = mock(UserRepository.class);
    private final InvestmentScheduleRepository schedules = mock(InvestmentScheduleRepository.class);
    private final PaycheckStreamRepository streams = mock(PaycheckStreamRepository.class);
    private final PaycheckCycleRepository cycles = mock(PaycheckCycleRepository.class);
    private final PlaidWebhookEventRepository inbox = mock(PlaidWebhookEventRepository.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PaycheckDetectionService detection = mock(PaycheckDetectionService.class);
    private final PaycheckReconcileService reconcile = mock(PaycheckReconcileService.class);
    private final PlaidWebhookProcessor processor = mock(PlaidWebhookProcessor.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AnalyticsOutboxService outbox = mock(AnalyticsOutboxService.class);
    private final RecordingTransactionManager tx = new RecordingTransactionManager();

    private AdminPaycheckService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AdminPaycheckService(users, schedules, streams, cycles, inbox,
                mock(PaycheckDetectionStatusService.class), pipeline, detection, reconcile, processor, audit, outbox, tx);
        user = new User();
        user.setId(UID);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken(TOKEN);
        user.setPlaidSyncCursor(CURSOR);
        user.setPlaidItemId("item-1");
        when(users.findByIdForUpdate(UID)).thenReturn(Optional.of(user));
        when(users.findById(UID)).thenReturn(Optional.of(user));
    }

    private InvestmentSchedule schedule(Status status, HoldReason hold) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setId(42L);
        s.setUser(user);
        s.setStatus(status);
        s.setAchRequestId("ach-1");
        s.setHoldReason(hold);
        when(schedules.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(s));
        return s;
    }

    private PaycheckStream stream(Long id, PaycheckStreamState state, PaycheckStreamRole role) {
        PaycheckStream s = new PaycheckStream();
        s.setId(id);
        s.setUserId(UID);
        s.setPayerDisplayName("Acme Corp");
        s.setRawDescriptor(DESCRIPTOR);
        s.setState(state);
        s.setRole(role);
        when(streams.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private PaycheckCycle cycle(Long id, Long streamId, PaycheckCycleState state) {
        PaycheckCycle c = new PaycheckCycle();
        c.setId(id);
        c.setUserId(UID);
        c.setStreamId(streamId);
        c.setState(state);
        when(cycles.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private void failedInboxRow() {
        PlaidWebhookEvent row = new PlaidWebhookEvent();
        row.setId(5L);
        row.setUserId(UID);
        row.setStatus(PlaidWebhookEventStatus.FAILED);
        when(inbox.findById(5L)).thenReturn(Optional.of(row));
        when(processor.replay(5L)).thenReturn(ReplayResult.REQUEUED);
    }

    private static String code(VerbResult r) {
        return ((ErrorCodeResponse) r.body()).code();
    }

    private void assertNoEvent() {
        verify(outbox, never()).record(any(), any(), any());
    }

    private Object outsideTx(InvocationOnMock inv) throws Throwable {
        assertFalse(tx.open, "a verb that reaches Plaid must not hold a database transaction");
        return Answers.RETURNS_DEFAULTS.answer(inv);
    }

    private Object insideTx(InvocationOnMock inv) throws Throwable {
        assertTrue(tx.open, "state, event and audit row share one transaction");
        return Answers.RETURNS_DEFAULTS.answer(inv);
    }

    private void onAudit(Consumer<InvocationOnMock> sink) {
        Answer<Object> answer = inv -> { sink.accept(inv); return Answers.RETURNS_DEFAULTS.answer(inv); };
        doAnswer(answer).when(audit).record(any(), any(), any(), any(), any(), any(), any());
        doAnswer(answer).when(audit).recordInTransaction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void clearReviewEmitsResumedOnlyWhenActiveAndUnblocked() {
        InvestmentSchedule s = schedule(Status.ACTIVE, HoldReason.MANUAL_REVIEW);
        doAnswer(this::insideTx).when(outbox).record(any(), any(), any());
        doAnswer(this::insideTx).when(audit).recordInTransaction(any(), any(), any(), any(), any(), any(), any());

        assertEquals(200, service.clearReview(ACTOR, UID, "resolved").httpStatus());

        assertNull(s.getHoldReason());
        assertEquals(Status.ACTIVE, s.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
        verify(outbox, times(1)).record(eq("schedule_resumed"), eq(user), props.capture());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("initiator", "system");
        expected.put("reason", "review_cleared");
        assertEquals(expected, props.getValue());
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(props.getValue().keySet()), "initiator then reason");
        verify(audit).recordInTransaction(eq(ACTOR), eq(UID), eq(AdminPaycheckService.VERB_CLEAR_REVIEW), isNull(),
                any(), any(), eq("resolved"));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"PAUSED_BY_USER", "PENDING_BANK"})
    void clearReviewSilentWhenPausedByUser(Status status) {
        InvestmentSchedule s = schedule(status, HoldReason.MANUAL_REVIEW);

        assertEquals(200, service.clearReview(ACTOR, UID, null).httpStatus());

        assertNull(s.getHoldReason());
        assertEquals(status, s.getStatus());
        assertNoEvent();
    }

    @Test
    void clearReviewRefusedForRejectedApplicant() {
        user.setAccountStatus("REJECTED");
        InvestmentSchedule s = schedule(Status.ACTIVE, HoldReason.MANUAL_REVIEW);

        VerbResult result = service.clearReview(ACTOR, UID, "please");

        assertEquals(409, result.httpStatus());
        assertEquals("ACCOUNT_REJECTED", code(result));
        assertEquals(HoldReason.MANUAL_REVIEW, s.getHoldReason());
        verify(schedules, never()).save(any());
        assertNoEvent();
        verify(audit, never()).recordInTransaction(any(), any(), any(), any(), any(), any(), any());
        verify(audit).record(eq(ACTOR), eq(UID), eq(AdminPaycheckService.VERB_CLEAR_REVIEW), isNull(), any(), any(),
                eq("ACCOUNT_REJECTED"));

        user.setAccountStatus("ACTIVE");
        s.setHoldReason(HoldReason.BANK_CHANGE);
        assertEquals("NOT_IN_REVIEW", code(service.clearReview(ACTOR, UID, null)));
        assertEquals(HoldReason.BANK_CHANGE, s.getHoldReason());
    }

    @ParameterizedTest
    @MethodSource("blockingHolds")
    void holdVerbRefusesWriteOverBlockingHold(HoldReason blocking) {
        InvestmentSchedule s = schedule(Status.ACTIVE, blocking);

        VerbResult replace = service.hold(ACTOR, UID, "BANK_CHANGE", "relink");
        VerbResult clear = service.hold(ACTOR, UID, null, "clear it");

        assertEquals(409, replace.httpStatus());
        assertEquals("HOLD_BLOCKING", code(replace));
        assertEquals("HOLD_BLOCKING", code(clear));
        assertEquals(blocking, s.getHoldReason());
        verify(schedules, never()).save(any());
        assertNoEvent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DORMANT", "AWAITING_AUTHORIZATION", "KILL_SWITCH", "MANUAL_REVIEW",
            "ACH_RETURN_ACCOUNT", "ACH_RETURN_UNAUTHORIZED", "NOT_A_HOLD"})
    void holdVerbRefusesDetectedModeHolds(String reason) {
        schedule(Status.ACTIVE, null);

        VerbResult result = service.hold(ACTOR, UID, reason, null);

        assertEquals(422, result.httpStatus());
        assertEquals("HOLD_RESERVED", code(result));
        verify(users, never()).findByIdForUpdate(any());
        verify(schedules, never()).save(any());
        verify(audit).record(eq(ACTOR), eq(UID), eq(AdminPaycheckService.VERB_HOLD), any(), any(), any(),
                eq("HOLD_RESERVED"));
    }

    @Test
    void holdVerbNeverWritesStatus() {
        InvestmentSchedule s = schedule(Status.ACTIVE, null);
        doAnswer(this::insideTx).when(audit).recordInTransaction(any(), any(), any(), any(), any(), any(), any());

        assertEquals(200, service.hold(ACTOR, UID, "ITEM_LOGIN_REQUIRED", "login broke").httpStatus());
        assertEquals(HoldReason.ITEM_LOGIN_REQUIRED, s.getHoldReason());
        assertEquals(200, service.hold(ACTOR, UID, null, "fixed").httpStatus());
        assertNull(s.getHoldReason());

        assertEquals(Status.ACTIVE, s.getStatus());
        assertNoEvent();
        verify(audit, times(2)).recordInTransaction(eq(ACTOR), eq(UID), eq(AdminPaycheckService.VERB_HOLD),
                any(), any(), any(), any());
    }

    @Test
    void promoteStreamDemotesOldPrimaryAndMirrors() {
        InvestmentSchedule s = schedule(Status.ACTIVE, null);
        s.setPrimaryStreamId(11L);
        PaycheckStream oldPrimary = stream(11L, PaycheckStreamState.CONFIRMED, PaycheckStreamRole.PRIMARY);
        PaycheckStream promoted = stream(12L, PaycheckStreamState.CANDIDATE, PaycheckStreamRole.NONE);
        PaycheckStream retired = stream(13L, PaycheckStreamState.RETIRED, PaycheckStreamRole.NONE);
        when(streams.findByUserIdOrderByIdAsc(UID)).thenReturn(List.of(oldPrimary, promoted, retired));

        assertEquals(200, service.promoteStream(ACTOR, UID, 12L, null).httpStatus());
        assertEquals(PaycheckStreamRole.PRIMARY, promoted.getRole());
        assertEquals(PaycheckStreamState.PROVISIONAL, promoted.getState());
        assertEquals(PaycheckStreamRole.SECONDARY, oldPrimary.getRole());
        assertEquals(12L, s.getPrimaryStreamId(), "R1 mirror");

        assertEquals("STREAM_NOT_PROMOTABLE", code(service.promoteStream(ACTOR, UID, 13L, null)));
        assertEquals(PaycheckStreamRole.NONE, retired.getRole());
        assertEquals(Status.ACTIVE, s.getStatus());
        assertNoEvent();
    }

    @Test
    void retireStreamVoidsItsOpenCycleAndClearsMirror() {
        InvestmentSchedule s = schedule(Status.ACTIVE, null);
        s.setPrimaryStreamId(11L);
        s.setNextExpectedDate(java.time.LocalDate.of(2026, 11, 6));
        PaycheckStream primary = stream(11L, PaycheckStreamState.CONFIRMED, PaycheckStreamRole.PRIMARY);
        PaycheckCycle open = cycle(21L, 11L, PaycheckCycleState.OPEN);
        PaycheckCycle other = cycle(22L, 99L, PaycheckCycleState.OPEN);
        when(cycles.findByUserIdAndState(UID, PaycheckCycleState.OPEN)).thenReturn(List.of(open, other));

        assertEquals(200, service.retireStream(ACTOR, UID, 11L, "employer gone").httpStatus());

        assertEquals(PaycheckStreamState.RETIRED, primary.getState());
        assertEquals(PaycheckRetiredReason.ADMIN, primary.getRetiredReason());
        assertEquals(PaycheckStreamRole.NONE, primary.getRole());
        assertEquals(PaycheckCycleState.VOIDED, open.getState());
        assertEquals(PaycheckVoidedReason.ADMIN, open.getVoidedReason());
        assertEquals(PaycheckCycleState.OPEN, other.getState());
        assertNull(s.getPrimaryStreamId());
        assertNull(s.getNextExpectedDate());
        assertNoEvent();
    }

    @Test
    void voidCycleRefusesAnExecutedCycleAndVoidsAnOpenOne() {
        schedule(Status.ACTIVE, null);
        PaycheckCycle executed = cycle(21L, 11L, PaycheckCycleState.CONFIRMED);
        executed.setExecutionId(500L);
        PaycheckCycle skipped = cycle(22L, 11L, PaycheckCycleState.SKIPPED);
        PaycheckCycle open = cycle(23L, 11L, PaycheckCycleState.OPEN);

        assertEquals("CYCLE_HAS_EXECUTION", code(service.voidCycle(ACTOR, UID, 21L, null)));
        assertEquals("CYCLE_NOT_VOIDABLE", code(service.voidCycle(ACTOR, UID, 22L, null)));
        assertEquals(200, service.voidCycle(ACTOR, UID, 23L, null).httpStatus());

        assertEquals(PaycheckCycleState.CONFIRMED, executed.getState());
        assertEquals(PaycheckCycleState.SKIPPED, skipped.getState());
        assertEquals(PaycheckCycleState.VOIDED, open.getState());
        assertEquals(PaycheckVoidedReason.ADMIN, open.getVoidedReason());
        assertNoEvent();
    }

    @Test
    void pipelineVerbsRunOutsideAnyTransaction() {
        schedule(Status.ACTIVE, null);
        failedInboxRow();
        when(pipeline.run(eq(UID), eq("item-1"), eq(SyncReason.MANUAL))).thenAnswer(this::outsideTx);
        when(detection.evaluate(eq(UID), eq(EvaluationTrigger.RECONCILE), any())).thenAnswer(this::outsideTx);
        when(reconcile.runForUser(eq(UID), any())).thenAnswer(this::outsideTx);
        when(processor.replay(5L)).thenAnswer(inv -> { outsideTx(inv); return ReplayResult.REQUEUED; });

        assertEquals(200, service.sync(ACTOR, UID).httpStatus());
        assertEquals(200, service.evaluate(ACTOR, UID).httpStatus());
        assertEquals(200, service.reconcile(ACTOR, UID).httpStatus());
        assertEquals(200, service.replay(ACTOR, 5L).httpStatus());
        when(processor.replay(5L)).thenReturn(ReplayResult.IN_FLIGHT);
        assertEquals("WEBHOOK_IN_FLIGHT", code(service.replay(ACTOR, 5L)));

        verify(processor, times(2)).replay(5L);
    }

    @Test
    void everyVerbWritesAdminAction() {
        schedule(Status.ACTIVE, HoldReason.MANUAL_REVIEW);
        stream(11L, PaycheckStreamState.PROVISIONAL, PaycheckStreamRole.SECONDARY);
        stream(12L, PaycheckStreamState.CONFIRMED, PaycheckStreamRole.PRIMARY);
        cycle(21L, 12L, PaycheckCycleState.OPEN);
        failedInboxRow();
        List<String> seen = new ArrayList<>();
        onAudit(inv -> seen.add(inv.getArgument(2)));

        service.status(ACTOR, UID);
        service.plaidItem(ACTOR, UID);
        service.sync(ACTOR, UID);
        service.evaluate(ACTOR, UID);
        service.reconcile(ACTOR, UID);
        service.hold(ACTOR, UID, "BANK_CHANGE", null);
        service.clearReview(ACTOR, UID, null);
        service.promoteStream(ACTOR, UID, 11L, null);
        service.retireStream(ACTOR, UID, 12L, null);
        service.voidCycle(ACTOR, UID, 21L, null);
        service.webhooks(ACTOR, "FAILED");
        service.replay(ACTOR, 5L);
        assertEquals(404, service.status(ACTOR, 99L).httpStatus());

        assertEquals(13, seen.size(), "one row per call, refusals included");
        assertEquals(new TreeSet<>(AdminPaycheckService.VERBS), new TreeSet<>(seen));
    }

    @Test
    void auditJsonCarriesNoDescriptorOrToken() throws Exception {
        schedule(Status.ACTIVE, null);
        PaycheckStream primary = stream(11L, PaycheckStreamState.CONFIRMED, PaycheckStreamRole.PRIMARY);
        when(streams.findByUserIdOrderByIdAsc(UID)).thenReturn(List.of(primary));
        List<Object> audited = new ArrayList<>();
        onAudit(inv -> audited.addAll(Arrays.asList(inv.getArgument(4), inv.getArgument(5))));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        VerbResult status = service.status(ACTOR, UID);
        VerbResult item = service.plaidItem(ACTOR, UID);
        service.promoteStream(ACTOR, UID, 11L, null);

        String auditJson = json.writeValueAsString(audited);
        String itemJson = json.writeValueAsString(item.body());
        for (String secret : List.of(DESCRIPTOR, TOKEN, CURSOR, "Acme")) {
            assertFalse(auditJson.contains(secret), auditJson);
        }
        assertFalse(itemJson.contains(TOKEN) || itemJson.contains(CURSOR), itemJson);
        assertTrue(json.writeValueAsString(status.body()).contains(DESCRIPTOR), "R13");
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AdminPaycheckServiceTest' --console=plain`. Expected: `compileTestJava` fails with `cannot find symbol` for `class AdminPaycheckService` (and `VerbResult`).

- [ ] **Step 5: The inbox finder**

`InvestmentSchedule.isBlockingHold` is T23's; this task never edits `InvestmentSchedule.java`. `.../repository/PlaidWebhookEventRepository.java`, after `    Optional<PlaidWebhookEvent> findByDedupeKey(String dedupeKey);` (plus `import java.util.List;` if T14 did not add it):

```java

    /** The admin status dump's inbox tail for one user (T18). Read only. */
    List<PlaidWebhookEvent> findTop20ByUserIdOrderByReceivedAtDesc(Long userId);
```

- [ ] **Step 6: The two admin DTOs**

Create `.../dto/admin/AdminUserStatusView.java`:

```java
package com.investingapp.backend.dto.admin;

import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PlaidWebhookEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** GET /{userId}/status: never the token, cursor or a payload; never logged or audited. */
public record AdminUserStatusView(PaycheckDetectionStatusResponse detection, List<StreamRow> streams,
                                  List<CycleRow> cycles, List<InboxRow> inbox) {

    public record StreamRow(Long id, String state, String role, String payerToken, String payerDisplayName,
                            String rawDescriptor, String plaidAccountId, String cadence, int score,
                            String scoreBreakdownJson, int depositsFound, int missedCount, LocalDate lastDate,
                            BigDecimal trailingMedian, LocalDate predictedNextDate, String rejectReason,
                            String retiredReason, LocalDateTime retiredAt) {
        public static StreamRow of(PaycheckStream s) {
            return new StreamRow(s.getId(), name(s.getState()), name(s.getRole()), s.getPayerToken(),
                    s.getPayerDisplayName(), s.getRawDescriptor(), s.getPlaidAccountId(), name(s.getCadence()),
                    s.getScore(), s.getScoreBreakdownJson(), s.getDepositsFound(), s.getMissedCount(),
                    s.getLastDate(), s.getTrailingMedian(), s.getPredictedNextDate(), name(s.getRejectReason()),
                    name(s.getRetiredReason()), s.getRetiredAt());
        }
    }

    public record CycleRow(Long id, Long streamId, String periodKey, String state, LocalDate expectedDate,
                           LocalDate windowEnd, Long eventId, BigDecimal amountToPull, LocalDateTime confirmedAt,
                           String skipReason, String voidedReason, Long executionId) {
        public static CycleRow of(PaycheckCycle c) { /* ROW PATTERN */ }
    }

    public record InboxRow(Long id, Long userId, String plaidItemId, String webhookType, String webhookCode,
                           String status, int attempts, String lastError, LocalDateTime receivedAt,
                           LocalDateTime processedAt) {
        public static InboxRow of(PlaidWebhookEvent e) { /* ROW PATTERN */ }
    }

    public static AdminUserStatusView of(PaycheckDetectionStatusResponse detection, List<PaycheckStream> streams,
                                         List<PaycheckCycle> cycles, List<PlaidWebhookEvent> inbox) {
        return new AdminUserStatusView(detection, streams.stream().map(StreamRow::of).toList(),
                cycles.stream().map(CycleRow::of).toList(), inbox.stream().map(InboxRow::of).toList());
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
```

Create `.../dto/admin/PlaidItemDump.java` (every `plaid_*` user column except token and cursor, which are reported only as present or absent):

```java
package com.investingapp.backend.dto.admin;

import com.investingapp.backend.model.User;

import java.time.LocalDateTime;

public record PlaidItemDump(String publicId, String accountStatus, String plaidItemId, String plaidAccountId,
                            String plaidInstitutionName, String plaidAccountName, String plaidAccountType,
                            String plaidAccountSubtype, String plaidAccountMask, String plaidItemStatus,
                            String plaidItemErrorCode, Boolean plaidTransactionsConsented, boolean hasAccessToken,
                            boolean hasSyncCursor, Boolean plaidHistoryComplete, String plaidTransactionsUpdateStatus,
                            LocalDateTime plaidItemLinkedAt, LocalDateTime plaidWebhookAckedAt,
                            LocalDateTime plaidLastSyncAt, LocalDateTime plaidStreamsReadAt,
                            LocalDateTime plaidDisconnectAt, Boolean paycheckAutopilotOptOut) {

    public static PlaidItemDump of(User u) { /* ROW PATTERN */ }
}
```

ROW PATTERN (mechanical, as `StreamRow.of`): `return new <Record>(...)` with one argument per component in order, the getter of the same name, enums through `name(...)` (`CycleRow`: `state`, `skipReason`, `voidedReason`; `InboxRow`: `status`). In `PlaidItemDump.of`: `plaidItemStatus` is `u.getPlaidItemStatus() == null ? null : u.getPlaidItemStatus().name()`, `hasAccessToken` is `u.getPlaidAccessToken() != null`, `hasSyncCursor` is `u.getPlaidSyncCursor() != null`.

- [ ] **Step 7: AdminPaycheckService**

Create `.../service/AdminPaycheckService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.admin.AdminUserStatusView;
import com.investingapp.backend.dto.admin.PlaidItemDump;
import com.investingapp.backend.model.*;
import com.investingapp.backend.model.InvestmentSchedule.Status;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.repository.*;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidWebhookProcessor.ReplayResult;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/** Spec 12.2 verbs, Phase 1 (R12). No class-level transaction (Decision 56); none for pipelines. */
@Service
public class AdminPaycheckService {

    private static final Logger logger = LoggerFactory.getLogger(AdminPaycheckService.class);

    public static final String VERB_STATUS = "paycheck.status";
    public static final String VERB_PLAID_ITEM = "paycheck.plaid-item";
    public static final String VERB_SYNC = "paycheck.sync";
    public static final String VERB_EVALUATE = "paycheck.evaluate";
    public static final String VERB_RECONCILE = "paycheck.reconcile";
    public static final String VERB_HOLD = "paycheck.hold";
    public static final String VERB_CLEAR_REVIEW = "paycheck.clear-review";
    public static final String VERB_PROMOTE_STREAM = "paycheck.promote-stream";
    public static final String VERB_RETIRE_STREAM = "paycheck.retire-stream";
    public static final String VERB_VOID_CYCLE = "paycheck.void-cycle";
    public static final String VERB_WEBHOOKS = "paycheck.webhooks";
    public static final String VERB_REPLAY = "paycheck.webhook-replay";

    /** Phase 2 adds snooze, set-manual and webhooks/replay {from}. */
    public static final List<String> VERBS = List.of(VERB_STATUS, VERB_PLAID_ITEM, VERB_SYNC, VERB_EVALUATE,
            VERB_RECONCILE, VERB_HOLD, VERB_CLEAR_REVIEW, VERB_PROMOTE_STREAM, VERB_RETIRE_STREAM,
            VERB_VOID_CYCLE, VERB_WEBHOOKS, VERB_REPLAY);

    /** Everything the hold verb may write besides null (R2, Decision 31). */
    public static final Set<HoldReason> HOLD_VERB_REASONS = EnumSet.of(HoldReason.ITEM_LOGIN_REQUIRED,
            HoldReason.PERMISSION_REVOKED, HoldReason.CONSENT_REQUIRED, HoldReason.BANK_CHANGE);

    static final int NOTE_MAX = 255;
    static final int TARGET_MAX = 80;
    static final int CYCLE_LIST_MAX = 50;
    static final int WEBHOOK_LIST_MAX = 200;
    static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    public record ScheduleSnapshot(String status, String holdReason, String detectionState, Long primaryStreamId) {
        static final ScheduleSnapshot NONE = new ScheduleSnapshot(null, null, null, null);

        static ScheduleSnapshot of(InvestmentSchedule s) {
            return s == null ? NONE : new ScheduleSnapshot(name(s.getStatus()), name(s.getHoldReason()),
                    name(s.getDetectionState()), s.getPrimaryStreamId());
        }
    }

    public record VerbResult(int httpStatus, Object body) {
        public static VerbResult ok(Object body) {
            return new VerbResult(200, body);
        }

        public static VerbResult refusal(int httpStatus, String code, String message) {
            return new VerbResult(httpStatus, new ErrorCodeResponse(code, message));
        }

        public boolean isRefusal() {
            return httpStatus != 200;
        }

        String code() {
            return body instanceof ErrorCodeResponse e ? e.code() : null;
        }
    }

    @FunctionalInterface
    private interface Mutation {
        /** Runs under the user row lock. Decide first, then write: a refusal writes nothing. */
        VerbResult apply(User user, InvestmentSchedule scheduleOrNull);
    }

    // FIELDS (see the note after this block).

    public AdminPaycheckService(UserRepository userRepository, InvestmentScheduleRepository scheduleRepository,
                                PaycheckStreamRepository streamRepository, PaycheckCycleRepository cycleRepository,
                                PlaidWebhookEventRepository webhookEventRepository,
                                PaycheckDetectionStatusService statusService, PaycheckSyncPipeline pipeline,
                                PaycheckDetectionService detectionService, PaycheckReconcileService reconcileService,
                                PlaidWebhookProcessor webhookProcessor, AdminAuditService adminAuditService,
                                AnalyticsOutboxService analyticsOutboxService,
                                PlatformTransactionManager transactionManager) {
        // ASSIGNMENTS (see the note).
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    // GET verbs: REQUIRES_NEW audit.

    public VerbResult status(String actor, Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return refuseUnknownUser(actor, userId, VERB_STATUS);
        }
        User user = found.get();
        ScheduleSnapshot snapshot = ScheduleSnapshot.of(scheduleOf(user));
        List<PaycheckCycle> userCycles = new ArrayList<>();
        for (PaycheckCycleState state : PaycheckCycleState.values()) {
            userCycles.addAll(cycleRepository.findByUserIdAndState(userId, state));
        }
        userCycles.sort(Comparator.comparing(PaycheckCycle::getId).reversed());
        AdminUserStatusView view = AdminUserStatusView.of(statusService.status(user),
                streamRepository.findByUserIdOrderByIdAsc(userId),
                userCycles.subList(0, Math.min(userCycles.size(), CYCLE_LIST_MAX)),
                webhookEventRepository.findTop20ByUserIdOrderByReceivedAtDesc(userId));
        adminAuditService.record(actor, userId, VERB_STATUS, null, snapshot, snapshot, null);
        return done(VERB_STATUS, VerbResult.ok(view));
    }

    // plaidItem(String actor, Long userId): PATTERN A (note after this block).

    public VerbResult webhooks(String actor, String status) {
        String wanted = status == null || status.isBlank() ? "FAILED" : status.trim().toUpperCase(Locale.ROOT);
        PlaidWebhookEventStatus parsed = Arrays.stream(PlaidWebhookEventStatus.values())
                .filter(s -> s.name().equals(wanted)).findFirst().orElse(null);
        if (parsed == null) {
            adminAuditService.record(actor, null, VERB_WEBHOOKS, ColumnText.fit(wanted, TARGET_MAX), null, null, "BAD_STATUS");
            return done(VERB_WEBHOOKS, VerbResult.refusal(400, "BAD_STATUS", "unknown inbox status"));
        }
        List<AdminUserStatusView.InboxRow> rows = webhookEventRepository.findByStatusOrderByReceivedAtAsc(parsed)
                .stream().limit(WEBHOOK_LIST_MAX).map(AdminUserStatusView.InboxRow::of).toList();
        adminAuditService.record(actor, null, VERB_WEBHOOKS, parsed.name(), null, null, null);
        return done(VERB_WEBHOOKS, VerbResult.ok(rows));
    }

    // Pipeline verbs: nothing open around the call.

    public VerbResult sync(String actor, Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return refuseUnknownUser(actor, userId, VERB_SYNC);
        }
        ScheduleSnapshot before = ScheduleSnapshot.of(scheduleOf(found.get()));
        String itemId = found.get().getPlaidItemId();
        if (itemId == null) {
            adminAuditService.record(actor, userId, VERB_SYNC, null, before, before, "NO_ITEM");
            return done(VERB_SYNC, VerbResult.refusal(409, "NO_ITEM", "the user has no linked Plaid Item"));
        }
        var outcome = pipeline.run(userId, itemId, SyncReason.MANUAL);
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome != null) {
            body.put("sync", outcome.sync() == null ? null : name(outcome.sync().status()));
            body.put("errorCode", outcome.sync() == null || outcome.sync().error() == null
                    ? null : outcome.sync().error().errorCode());
            body.put("streams", outcome.streams() == null ? null : name(outcome.streams().status()));
            body.put("evaluation", outcome.evaluation() == null ? null : name(outcome.evaluation().state()));
            body.put("itemState", name(outcome.itemState()));
        }
        auditInOwnTransaction(actor, userId, VERB_SYNC, null, before, snapshotFor(userId), null);
        return done(VERB_SYNC, VerbResult.ok(body));
    }

    // evaluate(String actor, Long userId) and reconcile(String actor, Long userId): PATTERN B (note).

    public VerbResult replay(String actor, Long eventId) {
        Optional<PlaidWebhookEvent> row = webhookEventRepository.findById(eventId);
        if (row.isEmpty()) {
            adminAuditService.record(actor, null, VERB_REPLAY, String.valueOf(eventId), null, null, "WEBHOOK_NOT_FOUND");
            return done(VERB_REPLAY, VerbResult.refusal(404, "WEBHOOK_NOT_FOUND", "no inbox row with that id"));
        }
        Long userId = row.get().getUserId();
        String previousStatus = name(row.get().getStatus());
        ScheduleSnapshot before = snapshotFor(userId);
        ReplayResult replayed = webhookProcessor.replay(eventId);
        if (replayed != ReplayResult.REQUEUED) {
            boolean inFlight = replayed == ReplayResult.IN_FLIGHT;
            String code = inFlight ? "WEBHOOK_IN_FLIGHT" : "WEBHOOK_NOT_FOUND";
            adminAuditService.record(actor, userId, VERB_REPLAY, String.valueOf(eventId), before, before, code);
            return done(VERB_REPLAY, VerbResult.refusal(inFlight ? 409 : 404, code, "the row was not requeued"));
        }
        auditInOwnTransaction(actor, userId, VERB_REPLAY, String.valueOf(eventId), before, snapshotFor(userId),
                "requeued from " + previousStatus);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("previousStatus", previousStatus);
        body.put("requeued", true);
        return done(VERB_REPLAY, VerbResult.ok(body));
    }

    // Mutating verbs: one template under the user row lock.

    public VerbResult hold(String actor, Long userId, String requestedReason, String note) {
        HoldReason wanted = null;
        if (requestedReason != null && !requestedReason.isBlank()) {
            wanted = Arrays.stream(HoldReason.values()).filter(h -> h.name().equals(requestedReason.trim()))
                    .findFirst().orElse(null);
            if (wanted == null || !HOLD_VERB_REASONS.contains(wanted)) {
                adminAuditService.record(actor, userId, VERB_HOLD, ColumnText.fit(requestedReason.trim(), TARGET_MAX),
                        null, null, "HOLD_RESERVED");
                return done(VERB_HOLD, VerbResult.refusal(422, "HOLD_RESERVED",
                        "allowed: ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED, BANK_CHANGE, null"));
            }
        }
        HoldReason target = wanted;
        return mutate(actor, userId, VERB_HOLD, target == null ? "null" : target.name(), note, (user, schedule) -> {
            if (schedule == null) {
                return VerbResult.refusal(409, "NO_SCHEDULE", "the user has no schedule row");
            }
            if (InvestmentSchedule.isBlockingHold(schedule.getHoldReason())) {
                return VerbResult.refusal(409, "HOLD_BLOCKING", "a blocking hold is lifted only by clear-review");
            }
            boolean changed = schedule.getHoldReason() != target;
            if (changed) {
                schedule.setHoldReason(target);
                scheduleRepository.save(schedule);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("changed", changed);
            body.put("holdReason", name(target));
            return VerbResult.ok(body);
        });
    }

    public VerbResult clearReview(String actor, Long userId, String note) {
        return mutate(actor, userId, VERB_CLEAR_REVIEW, null, note, (user, schedule) -> {
            if ("REJECTED".equals(user.getAccountStatus())) {
                return VerbResult.refusal(409, "ACCOUNT_REJECTED", "a rejected applicant's MANUAL_REVIEW hold stays");
            }
            if (schedule == null) {
                return VerbResult.refusal(409, "NO_SCHEDULE", "the user has no schedule row");
            }
            if (schedule.getHoldReason() != HoldReason.MANUAL_REVIEW) {
                return VerbResult.refusal(409, "NOT_IN_REVIEW", "the hold is not MANUAL_REVIEW");
            }
            boolean wasActive = schedule.getStatus() == Status.ACTIVE;
            schedule.setHoldReason(null);
            scheduleRepository.save(schedule);
            boolean resumed = wasActive && !InvestmentSchedule.isBlockingHold(schedule.getHoldReason());
            if (resumed) {
                recordScheduleTransition(user, ServerAnalyticsEvent.SCHEDULE_RESUMED,
                        ServerAnalyticsEvent.INITIATOR_SYSTEM, ServerAnalyticsEvent.REASON_REVIEW_CLEARED);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cleared", true);
            body.put("resumed", resumed);
            return VerbResult.ok(body);
        });
    }

    public VerbResult promoteStream(String actor, Long userId, Long streamId, String note) {
        return mutate(actor, userId, VERB_PROMOTE_STREAM, String.valueOf(streamId), note, (user, schedule) -> {
            Optional<PaycheckStream> found = streamRepository.findById(streamId).filter(s -> userId.equals(s.getUserId()));
            if (found.isEmpty()) {
                return VerbResult.refusal(404, "STREAM_NOT_FOUND", "no such stream for this user");
            }
            PaycheckStream stream = found.get();
            if (stream.getState() == PaycheckStreamState.RETIRED || stream.getState() == PaycheckStreamState.REJECTED) {
                return VerbResult.refusal(409, "STREAM_NOT_PROMOTABLE", "a retired or rejected stream cannot be primary");
            }
            LocalDateTime now = EasternTime.now();
            int demoted = 0;
            for (PaycheckStream other : streamRepository.findByUserIdOrderByIdAsc(userId)) {
                if (!other.getId().equals(streamId) && other.getRole() == PaycheckStreamRole.PRIMARY) {
                    other.setRole(PaycheckStreamRole.SECONDARY);
                    other.setUpdatedAt(now);
                    streamRepository.save(other);
                    demoted++;
                }
            }
            stream.setRole(PaycheckStreamRole.PRIMARY);
            stream.setState(PaycheckStreamState.PROVISIONAL);
            stream.setUpdatedAt(now);
            streamRepository.save(stream);
            if (schedule != null) {
                schedule.setPrimaryStreamId(streamId);
                scheduleRepository.save(schedule);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("streamId", streamId);
            body.put("demoted", demoted);
            return VerbResult.ok(body);
        });
    }

    public VerbResult retireStream(String actor, Long userId, Long streamId, String note) {
        return mutate(actor, userId, VERB_RETIRE_STREAM, String.valueOf(streamId), note, (user, schedule) -> {
            Optional<PaycheckStream> found = streamRepository.findById(streamId).filter(s -> userId.equals(s.getUserId()));
            if (found.isEmpty()) {
                return VerbResult.refusal(404, "STREAM_NOT_FOUND", "no such stream for this user");
            }
            PaycheckStream stream = found.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("changed", stream.getState() != PaycheckStreamState.RETIRED);
            if (stream.getState() == PaycheckStreamState.RETIRED) {
                return VerbResult.ok(body);
            }
            LocalDateTime now = EasternTime.now();
            stream.setState(PaycheckStreamState.RETIRED);
            stream.setRetiredReason(PaycheckRetiredReason.ADMIN);
            stream.setRetiredAt(now);
            stream.setRole(PaycheckStreamRole.NONE);
            stream.setUpdatedAt(now);
            streamRepository.save(stream);
            int voided = 0;
            for (PaycheckCycle open : cycleRepository.findByUserIdAndState(userId, PaycheckCycleState.OPEN)) {
                if (streamId.equals(open.getStreamId())) {
                    voidAsAdmin(open, now);
                    voided++;
                }
            }
            if (schedule != null && streamId.equals(schedule.getPrimaryStreamId())) {
                schedule.setPrimaryStreamId(null);
                schedule.setNextExpectedDate(null);
                scheduleRepository.save(schedule);
            }
            body.put("openCyclesVoided", voided);
            return VerbResult.ok(body);
        });
    }

    public VerbResult voidCycle(String actor, Long userId, Long cycleId, String note) {
        return mutate(actor, userId, VERB_VOID_CYCLE, String.valueOf(cycleId), note, (user, schedule) -> {
            Optional<PaycheckCycle> found = cycleRepository.findById(cycleId).filter(c -> userId.equals(c.getUserId()));
            if (found.isEmpty()) {
                return VerbResult.refusal(404, "CYCLE_NOT_FOUND", "no such cycle for this user");
            }
            PaycheckCycle cycle = found.get();
            if (cycle.getExecutionId() != null) {
                return VerbResult.refusal(409, "CYCLE_HAS_EXECUTION", "a cycle with an execution is never voided here");
            }
            if (cycle.getState() != PaycheckCycleState.OPEN && cycle.getState() != PaycheckCycleState.CONFIRMED) {
                return VerbResult.refusal(409, "CYCLE_NOT_VOIDABLE", "only OPEN and CONFIRMED cycles are voided");
            }
            voidAsAdmin(cycle, EasternTime.now());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cycleId", cycleId);
            body.put("state", name(cycle.getState()));
            return VerbResult.ok(body);
        });
    }

    // Shared machinery.

    private VerbResult mutate(String actor, Long userId, String verb, String targetId, String note, Mutation mutation) {
        String reason = ColumnText.fit(note, NOTE_MAX);
        ScheduleSnapshot[] before = {ScheduleSnapshot.NONE};
        VerbResult result = writeTx.execute(tx -> {
            Optional<User> locked = userRepository.findByIdForUpdate(userId);
            if (locked.isEmpty()) {
                return VerbResult.refusal(404, USER_NOT_FOUND, "no user with that id");
            }
            InvestmentSchedule schedule = scheduleOf(locked.get());
            before[0] = ScheduleSnapshot.of(schedule);
            VerbResult outcome = mutation.apply(locked.get(), schedule);
            if (!outcome.isRefusal()) {
                adminAuditService.recordInTransaction(actor, userId, verb, targetId, before[0],
                        ScheduleSnapshot.of(schedule), reason);
            }
            return outcome;
        });
        if (result.isRefusal()) {
            // The template wrote nothing; the refusal is still recorded, in its own transaction.
            adminAuditService.record(actor, userId, verb, targetId, before[0], before[0], result.code());
        }
        return done(verb, result);
    }

    private void voidAsAdmin(PaycheckCycle cycle, LocalDateTime now) {
        cycle.setState(PaycheckCycleState.VOIDED);
        cycle.setVoidedReason(PaycheckVoidedReason.ADMIN);
        cycle.setUpdatedAt(now);
        cycleRepository.save(cycle);
    }

    private void auditInOwnTransaction(String actor, Long userId, String verb, String targetId,
                                       ScheduleSnapshot before, ScheduleSnapshot after, String reason) {
        writeTx.executeWithoutResult(tx ->
                adminAuditService.recordInTransaction(actor, userId, verb, targetId, before, after, reason));
    }

    private VerbResult refuseUnknownUser(String actor, Long userId, String verb) {
        adminAuditService.record(actor, userId, verb, null, null, null, USER_NOT_FOUND);
        return done(verb, VerbResult.refusal(404, USER_NOT_FOUND, "no user with that id"));
    }

    private InvestmentSchedule scheduleOf(User user) {
        return scheduleRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
    }

    private ScheduleSnapshot snapshotFor(Long userId) {
        return userId == null ? ScheduleSnapshot.NONE
                : userRepository.findById(userId).map(u -> ScheduleSnapshot.of(scheduleOf(u))).orElse(ScheduleSnapshot.NONE);
    }

    /** As InvestmentScheduleService.recordScheduleTransition: exactly initiator then reason. */
    private void recordScheduleTransition(User user, String event, String initiator, String reason) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("initiator", initiator);
        props.put("reason", reason);
        analyticsOutboxService.record(event, user, props);
    }

    /** INFO carries the verb, the HTTP status and the refusal code; ids live in admin_actions. */
    private static VerbResult done(String verb, VerbResult result) {
        logger.info("admin.action verb={} http={} code={}", verb, result.httpStatus(), result.code());
        return result;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
```

Mechanical repetitions in this class, each following code shown in full:

- FIELDS and ASSIGNMENTS: twelve `private final` fields typed and named as the first twelve constructor parameters (`userRepository` through `analyticsOutboxService`), in that order, then `private final TransactionTemplate writeTx;`; each of the twelve assigned `this.x = x;`.
- PATTERN A, `public VerbResult plaidItem(String actor, Long userId)`: `status` without the cycle, stream, inbox and `statusService` reads: `findById`; when empty `return refuseUnknownUser(actor, userId, VERB_PLAID_ITEM)`; `snapshot = ScheduleSnapshot.of(scheduleOf(found.get()))`; `adminAuditService.record(actor, userId, VERB_PLAID_ITEM, null, snapshot, snapshot, null)`; `return done(VERB_PLAID_ITEM, VerbResult.ok(PlaidItemDump.of(found.get())))`.
- PATTERN B, `public VerbResult evaluate(String actor, Long userId)` and `reconcile(...)`: `sync` minus the `NO_ITEM` check, with `VERB_EVALUATE` / `VERB_RECONCILE`. `evaluate` calls `var outcome = detectionService.evaluate(userId, EvaluationTrigger.RECONCILE, EasternTime.today());` and, when non-null, puts `state` (`name(outcome.state())`), `primaryStreamId`, `primaryChanged`, `switched`, `excluded` (`outcome.excluded() == null ? null : outcome.excluded().map(Enum::name).orElse(null)`). `reconcile` returns `VerbResult.ok(reconcileService.runForUser(userId, EasternTime.today()))`. Both audit with `auditInOwnTransaction(actor, userId, <verb>, null, before, snapshotFor(userId), null)` after the call.

A `promote-stream` override lasts until the next `evaluate` re-runs T11's `PrimarySelector`. `retire-stream` voids the stream's OPEN cycle in the same transaction, so T12's `markMissesForUser` never sets DORMANT over a stream an operator retired.

Run the Step 4 command. Expected: `BUILD SUCCESSFUL`, 21 tests (12 methods; the three parameterized ones give 2, 3 and 7), 0 failures, 0 skipped in `build/test-results/test/TEST-com.investingapp.backend.service.AdminPaycheckServiceTest.xml`.

- [ ] **Step 8: Failing controller test**

Create `T/controller/admin/AdminPaycheckControllerTest.java`:

```java
package com.investingapp.backend.controller.admin;

import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.AdminPaycheckService;
import com.investingapp.backend.service.AdminPaycheckService.VerbResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPaycheckControllerTest {

    private final AdminPaycheckService service = mock(AdminPaycheckService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(audit.actor(any(HttpServletRequest.class))).thenReturn("ops-test");
        mvc = MockMvcBuilders.standaloneSetup(new AdminPaycheckController(service, audit)).build();
    }

    @Test
    void holdPassesReasonNoteAndActorToTheService() throws Exception {
        when(service.hold("ops-test", 7L, "BANK_CHANGE", "relink")).thenReturn(VerbResult.ok(Map.of("changed", true)));

        mvc.perform(post("/api/admin/paycheck/7/hold").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"BANK_CHANGE\",\"note\":\"relink\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void refusalAnswersItsStatusAndCode() throws Exception {
        when(service.clearReview("ops-test", 7L, null)).thenReturn(VerbResult.refusal(409, "ACCOUNT_REJECTED", "stays"));

        mvc.perform(post("/api/admin/paycheck/7/clear-review"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REJECTED"));
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.admin.AdminPaycheckControllerTest' --console=plain`. Expected: `compileTestJava` fails with `cannot find symbol` / `symbol: class AdminPaycheckController`.

- [ ] **Step 9: AdminPaycheckController**

Create `.../controller/admin/AdminPaycheckController.java`:

```java
package com.investingapp.backend.controller.admin;

import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.AdminPaycheckService;
import com.investingapp.backend.service.AdminPaycheckService.VerbResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/paycheck")
public class AdminPaycheckController {

    public record HoldRequest(String reason, String note) {
    }

    public record NoteRequest(String note) {
    }

    private final AdminPaycheckService service;
    private final AdminAuditService adminAuditService;

    public AdminPaycheckController(AdminPaycheckService service, AdminAuditService adminAuditService) {
        this.service = service;
        this.adminAuditService = adminAuditService;
    }

    @GetMapping("/{userId}/status")
    public ResponseEntity<Object> status(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.status(actor(request), userId));
    }

    @PostMapping("/{userId}/hold")
    public ResponseEntity<Object> hold(@PathVariable("userId") Long userId, @RequestBody HoldRequest body,
                                       HttpServletRequest request) {
        return respond(service.hold(actor(request), userId, body.reason(), body.note()));
    }

    @PostMapping("/{userId}/clear-review")
    public ResponseEntity<Object> clearReview(@PathVariable("userId") Long userId,
                                              @RequestBody(required = false) NoteRequest body,
                                              HttpServletRequest request) {
        return respond(service.clearReview(actor(request), userId, note(body)));
    }

    @GetMapping("/webhooks")
    public ResponseEntity<Object> webhooks(@RequestParam(name = "status", defaultValue = "FAILED") String status,
                                           HttpServletRequest request) {
        return respond(service.webhooks(actor(request), status));
    }

    private String actor(HttpServletRequest request) {
        return adminAuditService.actor(request);
    }

    private static String note(NoteRequest body) {
        return body == null ? null : body.note();
    }

    private static ResponseEntity<Object> respond(VerbResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
```

Add eight handlers that copy a shown one exactly (every `@PathVariable` named, every return `respond(service.<same name>(actor(request), ...))`): as `status`, `plaidItem` on `@GetMapping("/{userId}/plaid-item")`, and `sync`, `evaluate`, `reconcile` on `@PostMapping("/{userId}/<name>")`, each passing `userId`; as `status` with `@PathVariable("eventId") Long eventId` in place of `userId`, `replay` on `@PostMapping("/webhooks/{eventId}/replay")`; as `clearReview` with one more path variable passed before `note(body)`, `promoteStream` on `"/{userId}/promote-stream/{streamId}"` and `retireStream` on `"/{userId}/retire-stream/{streamId}"` (`Long streamId`), and `voidCycle` on `"/{userId}/cycles/{cycleId}/void"` (`Long cycleId`).

Run the Step 8 command. Expected: `BUILD SUCCESSFUL`, 2 tests, 0 failures.

- [ ] **Step 10: The real transaction boundary (`@RequiresCiDatabase`)**

Create `T/service/AdminPaycheckServiceRollbackTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** clear-review on a real database: hold, outbox row and audit row commit or roll back together. Not @Transactional. */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class AdminPaycheckServiceRollbackTest {

    @Autowired
    private AdminPaycheckService adminPaycheckService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate transactions;
    private Long userId;

    @BeforeEach
    void createCommittedUser() {
        transactions = new TransactionTemplate(transactionManager);
        User fresh = new User();
        fresh.setEmail("admin-paycheck-" + UUID.randomUUID() + "@example.invalid");
        fresh.setAccountStatus("ACTIVE");
        userId = transactions.execute(status -> userRepository.saveAndFlush(fresh)).getId();
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("admin_actions", "analytics_outbox", "investment_schedules")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
        }
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private long seedActiveScheduleInReview() {
        LocalDate today = EasternTime.today();
        jdbc.update("INSERT INTO investment_schedules (user_id, created_at, frequency, investment_amount, monthly_amount,"
                        + " monthly_streak, next_investment_date, start_date, status, mode, hold_reason, ach_request_id)"
                        + " VALUES (?, NOW(), 'BIWEEKLY', 250.00, 542.50, 0, ?, ?, 'ACTIVE', 'MANUAL', 'MANUAL_REVIEW', 'ach-1')",
                userId, today.plusDays(3), today);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void committedClearReviewWritesStateOutboxAndAuditTogether() {
        long scheduleId = seedActiveScheduleInReview();

        assertEquals(200, adminPaycheckService.clearReview("ops-test", userId, "resolved").httpStatus());

        assertNull(holdReason(scheduleId));
        assertEquals("{\"initiator\":\"system\",\"reason\":\"review_cleared\"}", jdbc.queryForObject(
                "SELECT properties FROM analytics_outbox WHERE user_id = ? AND event_name = 'schedule_resumed'",
                String.class, userId));
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor, verb, before_json, after_json FROM admin_actions WHERE user_id = ?", userId);
        assertEquals(AdminPaycheckService.VERB_CLEAR_REVIEW, audit.get("verb"));
        assertTrue(String.valueOf(audit.get("before_json")).contains("MANUAL_REVIEW"));
        assertFalse(String.valueOf(audit.get("after_json")).contains("MANUAL_REVIEW"));
    }

    @Test
    void mutatingVerbRollbackLeavesNoAdminAction() {
        long scheduleId = seedActiveScheduleInReview();

        transactions.executeWithoutResult(status -> {
            adminPaycheckService.clearReview("ops-test", userId, "resolved");
            status.setRollbackOnly();
        });

        assertEquals(0, count("admin_actions"), "the audit row must not outlive the state change it describes");
        assertEquals(0, count("analytics_outbox"));
        assertEquals("MANUAL_REVIEW", holdReason(scheduleId), "the rollback really discarded the hold change");
    }

    private String holdReason(long scheduleId) {
        return jdbc.queryForObject("SELECT hold_reason FROM investment_schedules WHERE id = ?", String.class, scheduleId);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }
}
```

The service template (`REQUIRED`), `recordInTransaction` (`MANDATORY`) and the outbox all join the outer template, so a `REQUIRES_NEW` audit would leave `count("admin_actions") == 1`.

Run against a scratch MySQL 8 on 127.0.0.1:3306 from the repository root (the password comes from a prompt, never a command line):

```
read -rs SCRATCH_PW; echo
MYSQL_PWD="$SCRATCH_PW" mysql -h 127.0.0.1 -u root -e "CREATE DATABASE fred_t18"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t18?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$SCRATCH_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook' PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth' PLAID_LINK_CUSTOMIZATION='default'
cd backend && ./gradlew cleanTest test --tests 'com.investingapp.backend.service.AdminPaycheckServiceRollbackTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/*.xml
cd .. && MYSQL_PWD="$SCRATCH_PW" mysql -h 127.0.0.1 -u root -e "DROP DATABASE fred_t18"
unset SCRATCH_PW SPRING_DATASOURCE_PASSWORD
```

Expected: Flyway migrates the empty schema through V10, then `tests="2" skipped="0" failures="0"`. `skipped="2"` means no database was reached: fix it and re-run. Read the XML only.

- [ ] **Step 11: API_ENDPOINTS.md**

`FREDdocs/API_ENDPOINTS.md`: before the `---` preceding `## Authentication Notes` (after T17's section), insert this block and a blank line:

```markdown
## Admin: Paycheck Autopilot (`/api/admin/paycheck`)

Every profile, prod included; curl only. Each call needs `X-Admin-Key` (`ADMIN_TOOLS_KEY`; else 404, empty body) and a non-blank `X-Admin-Actor` (else 400), and writes one `admin_actions` row, refusals included. No verb writes the schedule status or a blocking hold, or moves money. Unknown user: 404 `USER_NOT_FOUND`.

| Method | Path under `/api/admin/paycheck` | Does | Refusals |
|--------|------|------|----------|
| `GET` | `/{userId}/status` | 8.1 status, streams with raw descriptors, 50 cycles, 20 inbox rows | |
| `GET` | `/{userId}/plaid-item` | `plaid_*` user columns, token and cursor as presence only | |
| `POST` | `/{userId}/sync`, `/evaluate`, `/reconcile` | Pipeline (MANUAL), detection (RECONCILE), one-user reconcile | 409 `NO_ITEM` (sync) |
| `POST` | `/{userId}/hold` `{ reason, note }` | Sets ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED, BANK_CHANGE or null; no event | 422 `HOLD_RESERVED`; 409 `HOLD_BLOCKING`, `NO_SCHEDULE` |
| `POST` | `/{userId}/clear-review` `{ note }` | Clears MANUAL_REVIEW; `schedule_resumed` (system, `review_cleared`) only when ACTIVE | 409 `ACCOUNT_REJECTED`, `NOT_IN_REVIEW`, `NO_SCHEDULE` |
| `POST` | `/{userId}/promote-stream/{streamId}` | PRIMARY PROVISIONAL, old primary SECONDARY, mirrored | 404; 409 `STREAM_NOT_PROMOTABLE` |
| `POST` | `/{userId}/retire-stream/{streamId}` | RETIRED `ADMIN`, its OPEN cycle VOIDED `ADMIN`, mirror cleared | 404 |
| `POST` | `/{userId}/cycles/{cycleId}/void` | VOIDED `ADMIN` for OPEN or CONFIRMED with no execution | 404; 409 `CYCLE_HAS_EXECUTION`, `CYCLE_NOT_VOIDABLE` |
| `GET` | `/webhooks?status=FAILED` | Inbox rows in one status, oldest first, at most 200 | 400 `BAD_STATUS` |
| `POST` | `/webhooks/{eventId}/replay` | Row back to RECEIVED, attempts 0 | 404 `WEBHOOK_NOT_FOUND`; 409 `WEBHOOK_IN_FLIGHT` |
```

- [ ] **Step 12: Run everything this task touches**

Run every row of the Verification table.

- [ ] **Step 13: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (shared tree; never `git checkout`, `restore`, `stash` or `reset`; stage only these thirteen files, which `git status --short` must show staged before the commit):

```
git add backend/src/main/java/com/investingapp/backend/{controller/admin/AdminPaycheckController,service/AdminPaycheckService,dto/admin/AdminUserStatusView,dto/admin/PlaidItemDump,util/ServerAnalyticsEvent,repository/PlaidWebhookEventRepository}.java backend/src/test/java/com/investingapp/backend/{service/AdminPaycheckServiceTest,service/AdminPaycheckServiceRollbackTest,controller/admin/AdminPaycheckControllerTest,service/AnalyticsOutboxForbiddenPayloadTest,service/ScheduleTransitionAnalyticsTest}.java FREDdocs/analytics-events.md FREDdocs/API_ENDPOINTS.md
git status --short
git commit -m "feat(admin): audited paycheck admin verbs, with clear-review as the only blocking-hold transition"
```

### Verification

Backend rows run from the repository root as `(cd backend && ./gradlew test --tests '<class>' --console=plain)`; counts come from the XML.

| Class or command | Expected |
|---|---|
| `com.investingapp.backend.service.AdminPaycheckServiceTest` | 21 tests, 0 failures, 0 skipped |
| `com.investingapp.backend.controller.admin.AdminPaycheckControllerTest` | 2 tests |
| `com.investingapp.backend.service.ScheduleTransitionAnalyticsTest` | the count before this task (T23 added one) plus 1 (`reviewClearedIsAClosedReasonWithItsWireSpelling`) |
| `com.investingapp.backend.service.AnalyticsOutboxForbiddenPayloadTest` | 20 tests (15 parameterized plus 5) |
| `com.investingapp.backend.util.ServerAnalyticsEventParityTest`, `com.investingapp.backend.PhaseOneMoneyInvarianceTest` | green, counts unchanged by this task |
| `com.investingapp.backend.service.AdminPaycheckServiceRollbackTest`, Step 10 scratch-MySQL form | `tests="2" skipped="0" failures="0"` |
| whole suite: the Step 10 block with its gradle line changed to `./gradlew cleanTest test --console=plain` | 0 failures, 0 skipped in every XML file |
| `(cd frontend && npm run test:events-documented)` | exit 0 |

Then, from the repository root:

```
grep -n 'setStatus(\|setMode(\|getIsPaused(\|isPausedForAnyReason(' backend/src/main/java/com/investingapp/backend/service/AdminPaycheckService.java backend/src/main/java/com/investingapp/backend/controller/admin/AdminPaycheckController.java
grep -n 'logger\.' backend/src/main/java/com/investingapp/backend/service/AdminPaycheckService.java
git diff HEAD -- backend/src FREDdocs | grep -n "^+.*$(printf '\342\200\224')"
git ls-files -o --exclude-standard backend/src | xargs grep -ln "$(printf '\342\200\224')"
```

Expected: the first prints nothing; the second prints only the `admin.action verb={} http={} code={}` line; the last two (U+2014 built by `printf`, so the command holds no em dash; the fourth covers the new, still untracked files) print nothing.

### Produces (recap, matching the contract)

- The twelve endpoints of the Step 11 table, under `/api/admin/paycheck`.
- `AdminPaycheckService` (the twelve verb methods; `VERB_*`, `VERBS`, `HOLD_VERB_REASONS`; `ScheduleSnapshot(status, holdReason, detectionState, primaryStreamId)`, `VerbResult(httpStatus, body)`), the Phase 2 seam for `snooze`, `set-manual` and `webhooks/replay {from}`; `AdminPaycheckController` (`HoldRequest`, `NoteRequest`); `AdminUserStatusView` (`StreamRow`, `CycleRow`, `InboxRow`); `PlaidItemDump.of(User)`.
- `ServerAnalyticsEvent.REASON_REVIEW_CLEARED = "review_cleared"`; `PlaidWebhookEventRepository.findTop20ByUserIdOrderByReceivedAtDesc(Long)`.

### Deviations from the contract

1. 409 `HOLD_BLOCKING` calls T23's `InvestmentSchedule.isBlockingHold(HoldReason)`, since `BLOCKING_HOLDS` is private and the handoff forbids restating it.
2. `PlaidWebhookEventRepository` gains `findTop20ByUserIdOrderByReceivedAtDesc(Long)` for the "last 20 inbox rows"; no earlier task produces one.
3. Pipeline verbs audit through `recordInTransaction` in their own short template after the call, so `record` stays for refusals and GETs (Decision 56).
4. Two test classes beyond the contract list: `AdminPaycheckControllerTest`, and `AdminPaycheckServiceRollbackTest` (`@RequiresCiDatabase`), which holds `mutatingVerbRollbackLeavesNoAdminAction` because only a real database shows the rollback.
