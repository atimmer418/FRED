package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.service.PaycheckEventService.ApplyResult;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadStatus;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaycheckSyncPipelineTest {

    private static final Long USER_ID = 42L;
    private static final String ITEM = "item-current";

    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PlaidTransactionsSyncService syncService = mock(PlaidTransactionsSyncService.class);
    private final PlaidRecurringService recurringService = mock(PlaidRecurringService.class);
    private final PaycheckDetectionService detectionService = mock(PaycheckDetectionService.class);
    private final PlaidItemStateService itemStateService = mock(PlaidItemStateService.class);
    private final PaycheckSyncPipeline pipeline = new PaycheckSyncPipeline(props, userRepository, syncService,
            recurringService, detectionService, itemStateService);

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(eligibleUser()));
        when(detectionService.evaluate(any(), any(), any())).thenReturn(evaluation());
        when(detectionService.evaluateAndApply(any(), any(), any())).thenReturn(evaluation());
    }

    private static User eligibleUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidItemId(ITEM);
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setPaycheckAutopilotOptOut(false);
        return user;
    }

    private static SyncOutcome applied(boolean historyJustCompleted, List<Long> newIds) {
        return new SyncOutcome(SyncStatus.APPLIED, historyJustCompleted, new ApplyResult(newIds, List.of(), List.of(), 0), null);
    }

    private static PlaidApiError error(String code, PlaidErrorClass errorClass) {
        return new PlaidApiError("ITEM_ERROR", code, null, "req-1", 400, errorClass);
    }

    private static EvaluationOutcome evaluation() {
        return new EvaluationOutcome(DetectionState.SEARCHING, null, false, false, Optional.empty());
    }

    @Test
    void disabledPipelineRefusesBeforeAnyCall() {
        when(props.isEnabled()).thenReturn(false);
        assertThat(pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK).sync().status()).isEqualTo(SyncStatus.REFUSED_DISABLED);
        assertThat(pipeline.streamsChanged(USER_ID, ITEM).sync().status()).isEqualTo(SyncStatus.REFUSED_DISABLED);
        verifyNoInteractions(userRepository, syncService, recurringService, itemStateService);
        verify(detectionService, never()).evaluate(any(), any(), any());
    }

    static Stream<Arguments> excludedUsers() {
        User rejected = eligibleUser();
        rejected.setAccountStatus("REJECTED");
        User optedOut = eligibleUser();
        optedOut.setPaycheckAutopilotOptOut(true);
        User noToken = eligibleUser();
        noToken.setPlaidAccessToken(null);
        return Stream.of(Arguments.of(ExclusionReason.ACCOUNT_REJECTED, rejected),
                Arguments.of(ExclusionReason.OPTED_OUT, optedOut), Arguments.of(ExclusionReason.NO_TOKEN, noToken));
    }

    // syncItem and readStreams are the only PlaidGateway callers here, so no call to them is no Plaid call.
    @ParameterizedTest(name = "{0}")
    @MethodSource("excludedUsers")
    void excludedUserNeverReachesPlaid(ExclusionReason reason, User user) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        assertThat(DetectionEligibility.exclusion(user, props)).as("fixture triggers this reason").contains(reason);
        for (SyncReason syncReason : SyncReason.values()) {
            assertThat(pipeline.run(USER_ID, ITEM, syncReason).sync().status()).isEqualTo(SyncStatus.REFUSED_EXCLUDED);
        }
        assertThat(pipeline.streamsChanged(USER_ID, ITEM).sync().status()).isEqualTo(SyncStatus.REFUSED_EXCLUDED);
        verifyNoInteractions(syncService, recurringService, itemStateService);
        verify(detectionService, never()).evaluate(any(), any(), any());
    }

    @Test
    void incrementalPathAppliesDepositsInsideOneTransaction() throws NoSuchMethodException {
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.RECONCILE)).thenReturn(applied(false, List.of(11L, 12L)));

        assertThat(pipeline.run(USER_ID, ITEM, SyncReason.RECONCILE).evaluation()).isNotNull();

        verify(detectionService, times(1)).evaluateAndApply(eq(USER_ID), eq(List.of(11L, 12L)), any(LocalDate.class));
        verify(detectionService, never()).evaluate(any(), any(), any());
        verifyNoInteractions(recurringService, itemStateService);
        assertThat(PaycheckDetectionService.class.getMethod("evaluateAndApply", Long.class, List.class, LocalDate.class)
                .getAnnotation(Transactional.class)).as("deposits and evaluation commit together (T12)").isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ADDITIONAL_CONSENT_REQUIRED,CONSENT,consent", "ACCESS_NOT_GRANTED,CONSENT,consent",
            "ITEM_LOGIN_REQUIRED,ITEM_BROKEN,login", "NO_ACCOUNTS,ITEM_BROKEN,login", "ITEM_NOT_FOUND,ITEM_BROKEN,revoked",
            "USER_PERMISSION_REVOKED,ITEM_BROKEN,revoked", "PRODUCTS_NOT_SUPPORTED,NOT_SUPPORTED,not_supported",
            "TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED,SYNC_LIMIT,sync_limit", "INSTITUTION_DOWN,TRANSIENT,none",
            "RATE_LIMIT_EXCEEDED,RATE_LIMIT,none", "INSTITUTION_NO_LONGER_SUPPORTED,PERMANENT,none"})
    void itemErrorRoutedByClass(String code, PlaidErrorClass errorClass, String handler) {
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK))
                .thenReturn(new SyncOutcome(SyncStatus.ITEM_ERROR, false, null, error(code, errorClass)));
        when(itemStateService.onConsentRequired(any(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemStateService.onLoginRequired(any(), any(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemStateService.onRevoked(any(), any(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemStateService.onNotSupported(any(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemStateService.onSyncLimit(any(), any())).thenReturn(ItemStateResult.APPLIED);

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK);

        switch (handler) {
            case "consent" -> verify(itemStateService).onConsentRequired(USER_ID, ITEM);
            case "login" -> verify(itemStateService).onLoginRequired(USER_ID, ITEM, code);
            case "revoked" -> verify(itemStateService).onRevoked(USER_ID, ITEM, code);
            case "not_supported" -> verify(itemStateService).onNotSupported(USER_ID, ITEM);
            case "sync_limit" -> verify(itemStateService).onSyncLimit(USER_ID, ITEM);
            default -> { }
        }
        verifyNoMoreInteractions(itemStateService);
        verifyNoInteractions(recurringService);
        assertThat(outcome.itemState()).isEqualTo("none".equals(handler) ? null : ItemStateResult.APPLIED);
        assertThat(outcome.evaluation()).isNull();
    }

    @Test
    void historyCompletionReadsStreamsThenEvaluatesFull() {
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK)).thenReturn(applied(true, List.of(7L)));
        StreamsReadOutcome streams = new StreamsReadOutcome(StreamsReadStatus.APPLIED, 1, 1, 0, null);
        when(recurringService.readStreams(USER_ID, ITEM)).thenReturn(streams);

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK);

        InOrder order = inOrder(syncService, recurringService, detectionService);
        order.verify(syncService).syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);
        order.verify(recurringService).readStreams(USER_ID, ITEM);
        order.verify(detectionService).evaluate(eq(USER_ID), eq(EvaluationTrigger.FULL), any(LocalDate.class));
        verify(detectionService, never()).evaluateAndApply(any(), any(), any());
        assertThat(outcome.streams()).isSameAs(streams);
        assertThat(outcome.evaluation()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(value = SyncStatus.class, names = {"NOT_INITIALIZED", "REFUSED_CONSENT", "REFUSED_STALE_ITEM",
            "REFUSED_ITEM_STATUS", "RETRY_LATER", "FAILED"})
    void nonAppliedSyncStopsThePipeline(SyncStatus status) {
        User loginRequired = eligibleUser();
        loginRequired.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED); // ITEM_STATUS must not refuse at entry
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(loginRequired));
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.INIT)).thenReturn(new SyncOutcome(status, false, null, null));

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.INIT);

        assertThat(outcome.sync().status()).isEqualTo(status);
        assertThat(outcome.evaluation()).isNull();
        verifyNoInteractions(recurringService, itemStateService);
        verify(detectionService, never()).evaluateAndApply(any(), any(), any());
    }

    @Test
    void transientStreamsFailureAtHistoryCompletionStillEvaluatesFull() {
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK)).thenReturn(applied(true, List.of()));
        when(recurringService.readStreams(USER_ID, ITEM)).thenReturn(new StreamsReadOutcome(StreamsReadStatus.ITEM_ERROR,
                0, 0, 0, error("INTERNAL_SERVER_ERROR", PlaidErrorClass.TRANSIENT)));

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome.evaluation()).isNotNull();
        verify(detectionService).evaluate(eq(USER_ID), eq(EvaluationTrigger.FULL), any(LocalDate.class));
        verifyNoInteractions(itemStateService);
    }

    @Test
    void streamsChangedReadsThenEvaluatesStreamsChanged() {
        when(recurringService.readStreams(USER_ID, ITEM)).thenReturn(new StreamsReadOutcome(StreamsReadStatus.APPLIED, 2, 1, 1, null));

        PipelineOutcome outcome = pipeline.streamsChanged(USER_ID, ITEM);

        InOrder order = inOrder(recurringService, detectionService);
        order.verify(recurringService).readStreams(USER_ID, ITEM);
        order.verify(detectionService).evaluate(eq(USER_ID), eq(EvaluationTrigger.STREAMS_CHANGED), any(LocalDate.class));
        verifyNoInteractions(syncService);
        assertThat(outcome.sync()).isNull();
    }

    @Test
    void missingUserIsStaleAndCallsNothing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome.sync().status()).isEqualTo(SyncStatus.REFUSED_STALE_ITEM);
        verifyNoInteractions(syncService, recurringService, itemStateService);
    }

    @Test
    void refusesToRunInsideACallerTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> pipeline.run(USER_ID, ITEM, SyncReason.MANUAL)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pipeline.streamsChanged(USER_ID, ITEM)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(syncService, recurringService);
    }
}
