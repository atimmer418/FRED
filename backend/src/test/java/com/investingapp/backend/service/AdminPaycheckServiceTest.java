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
                mock(PaycheckDetectionStatusService.class), pipeline, detection, reconcile, processor, audit, outbox,
                new UserRowLock(users, mock(jakarta.persistence.EntityManager.class)), tx);
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

    // Ruling R-a: a verb whose downstream call throws still leaves an admin_actions row, then rethrows.
    @Test
    void downstreamVerbThatThrowsIsAuditedAsFailedThenRethrown() {
        schedule(Status.ACTIVE, null);
        failedInboxRow();
        IllegalStateException boom = new IllegalStateException("secret descriptor ACME PAYROLL in the message");
        when(pipeline.run(eq(UID), eq("item-1"), eq(SyncReason.MANUAL))).thenThrow(boom);
        when(detection.evaluate(eq(UID), eq(EvaluationTrigger.RECONCILE), any())).thenThrow(boom);
        when(reconcile.runForUser(eq(UID), any())).thenThrow(boom);
        when(processor.replay(5L)).thenThrow(boom);
        List<String> reasons = new ArrayList<>();
        List<String> verbs = new ArrayList<>();
        onAudit(inv -> {
            verbs.add(inv.getArgument(2));
            reasons.add(inv.getArgument(6));
        });

        assertSame(boom, assertThrows(IllegalStateException.class, () -> service.sync(ACTOR, UID)));
        assertSame(boom, assertThrows(IllegalStateException.class, () -> service.evaluate(ACTOR, UID)));
        assertSame(boom, assertThrows(IllegalStateException.class, () -> service.reconcile(ACTOR, UID)));
        assertSame(boom, assertThrows(IllegalStateException.class, () -> service.replay(ACTOR, 5L)));

        assertEquals(List.of(AdminPaycheckService.VERB_SYNC, AdminPaycheckService.VERB_EVALUATE,
                AdminPaycheckService.VERB_RECONCILE, AdminPaycheckService.VERB_REPLAY), verbs);
        assertEquals(Collections.nCopies(4, "FAILED:IllegalStateException"), reasons);
        verify(audit).record(eq(ACTOR), eq(UID), eq(AdminPaycheckService.VERB_REPLAY), eq("5"), any(), any(),
                eq("FAILED:IllegalStateException"));
    }

    @Test
    void mutatingVerbThatThrowsIsAuditedAsFailedThenRethrown() {
        schedule(Status.ACTIVE, null);
        stream(11L, PaycheckStreamState.CANDIDATE, PaycheckStreamRole.NONE);
        when(streams.findByUserIdOrderByIdAsc(UID)).thenReturn(List.of());
        IllegalStateException boom = new IllegalStateException("row text");
        when(streams.save(any())).thenThrow(boom);

        assertSame(boom, assertThrows(IllegalStateException.class, () -> service.promoteStream(ACTOR, UID, 11L, "note")));

        verify(audit).record(eq(ACTOR), eq(UID), eq(AdminPaycheckService.VERB_PROMOTE_STREAM), eq("11"), any(), any(),
                eq("FAILED:IllegalStateException"));
        verify(audit, never()).recordInTransaction(any(), any(), any(), any(), any(), any(), any());
    }

    // Ruling R-b: promote never downgrades a stream.
    @Test
    void promoteKeepsAConfirmedStreamConfirmed() {
        schedule(Status.ACTIVE, null);
        PaycheckStream confirmed = stream(11L, PaycheckStreamState.CONFIRMED, PaycheckStreamRole.SECONDARY);
        PaycheckStream provisional = stream(12L, PaycheckStreamState.PROVISIONAL, PaycheckStreamRole.NONE);
        PaycheckStream dormant = stream(13L, PaycheckStreamState.DORMANT, PaycheckStreamRole.NONE);
        PaycheckStream candidate = stream(14L, PaycheckStreamState.CANDIDATE, PaycheckStreamRole.NONE);
        when(streams.findByUserIdOrderByIdAsc(UID)).thenReturn(List.of(confirmed, provisional, dormant, candidate));

        assertEquals(200, service.promoteStream(ACTOR, UID, 11L, null).httpStatus());
        assertEquals(PaycheckStreamState.CONFIRMED, confirmed.getState());
        assertEquals(PaycheckStreamRole.PRIMARY, confirmed.getRole());

        assertEquals(200, service.promoteStream(ACTOR, UID, 12L, null).httpStatus());
        assertEquals(PaycheckStreamState.PROVISIONAL, provisional.getState());

        assertEquals(200, service.promoteStream(ACTOR, UID, 13L, null).httpStatus());
        assertEquals(PaycheckStreamState.PROVISIONAL, dormant.getState());

        assertEquals(200, service.promoteStream(ACTOR, UID, 14L, null).httpStatus());
        assertEquals(PaycheckStreamState.PROVISIONAL, candidate.getState());
    }

    // Ruling R-c: the list is bounded in SQL, newest first.
    @Test
    void webhooksListIsBoundedInTheQuery() {
        PlaidWebhookEvent row = new PlaidWebhookEvent();
        row.setId(5L);
        row.setStatus(PlaidWebhookEventStatus.FAILED);
        when(inbox.findTop200ByStatusOrderByReceivedAtDesc(PlaidWebhookEventStatus.FAILED)).thenReturn(List.of(row));

        VerbResult result = service.webhooks(ACTOR, "failed");

        assertEquals(200, result.httpStatus());
        assertEquals(1, ((List<?>) result.body()).size());
        verify(inbox).findTop200ByStatusOrderByReceivedAtDesc(PlaidWebhookEventStatus.FAILED);
        assertEquals(200, AdminPaycheckService.WEBHOOK_LIST_MAX, "the derived query's Top200 is this bound");
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
