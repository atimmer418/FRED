package com.investingapp.backend.service;

import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadStatus;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.service.PlaidWebhookDispatcher.DispatchOutcome;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlaidWebhookDispatcherTest {

    private static final Long USER_ID = 42L;
    private static final String ITEM = "item-current";
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 11, 3, 9, 0);

    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PlaidItemStateService itemState = mock(PlaidItemStateService.class);
    private final PlaidWebhookDispatcher dispatcher = new PlaidWebhookDispatcher(pipeline, itemState);

    private static PlaidWebhookEvent row(String type, String code, String extraJson) {
        PlaidWebhookEvent row = new PlaidWebhookEvent();
        row.setId(7L);
        row.setUserId(USER_ID);
        row.setPlaidItemId(ITEM);
        row.setWebhookType(type);
        row.setWebhookCode(code);
        row.setReceivedAt(RECEIVED_AT);
        row.setPayloadJson("{\"webhook_type\":\"" + type + "\",\"webhook_code\":\"" + code + "\",\"item_id\":\"" + ITEM + "\"" + extraJson + "}");
        return row;
    }

    private static PipelineOutcome sync(SyncStatus status, PlaidErrorClass errorClass) {
        PlaidApiError error = errorClass == null ? null : new PlaidApiError("API_ERROR", "CODE", null, "req-1", 400, errorClass);
        return new PipelineOutcome(new SyncOutcome(status, false, null, error), null, null, null);
    }

    // Uses T06's real classify, so it also pins that ERROR is classified on error.error_code (Decision 24).
    @ParameterizedTest
    @CsvSource({"ITEM_LOGIN_REQUIRED,ITEM_BROKEN", "ACCESS_NOT_GRANTED,CONSENT", "ITEM_NOT_FOUND,ITEM_BROKEN", "INSTITUTION_DOWN,TRANSIENT"})
    void itemErrorWebhookIsClassifiedOnItsCode(String code, PlaidErrorClass expectedClass) {
        when(pipeline.routeItemError(eq(USER_ID), eq(ITEM), any())).thenReturn(ItemStateResult.APPLIED);
        String error = ",\"error\":{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"" + code + "\",\"error_code_reason\":null}";

        assertThat(dispatcher.dispatch(row("ITEM", "ERROR", error))).isEqualTo(DispatchOutcome.PROCESSED);

        ArgumentCaptor<PlaidApiError> routed = ArgumentCaptor.forClass(PlaidApiError.class);
        verify(pipeline).routeItemError(eq(USER_ID), eq(ITEM), routed.capture());
        assertThat(routed.getValue().errorCode()).isEqualTo(code);
        assertThat(routed.getValue().errorClass()).isEqualTo(expectedClass);
        verify(pipeline, never()).run(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PENDING_DISCONNECT|,\"disconnect_time\":\"2026-11-10T14:00:00Z\"|2026-11-10T14:00:00Z",
            "PENDING_EXPIRATION|,\"consent_expiration_time\":\"2026-11-12T00:00:00Z\"|2026-11-12T00:00:00Z",
            "PENDING_DISCONNECT|,\"reason\":\"INSTITUTION_MIGRATION\"|"})
    void pendingDisconnectPassesPlaidsTimeElseTheSevenDayLead(String code, String extraJson, String expected) {
        when(itemState.onPendingDisconnect(any(), any(), any())).thenReturn(ItemStateResult.APPLIED);
        OffsetDateTime at = expected == null
                ? RECEIVED_AT.plusDays(7).atZone(EasternTime.ZONE).toOffsetDateTime() : OffsetDateTime.parse(expected);

        assertThat(dispatcher.dispatch(row("ITEM", code, extraJson))).isEqualTo(DispatchOutcome.PROCESSED);
        verify(itemState).onPendingDisconnect(USER_ID, ITEM, at);
    }

    @ParameterizedTest
    @CsvSource({"APPLIED,,PROCESSED", "NOT_INITIALIZED,,PROCESSED", "REFUSED_CONSENT,,PROCESSED",
            "REFUSED_STALE_ITEM,,IGNORED_STALE", "REFUSED_ITEM_STATUS,,PROCESSED", "REFUSED_DISABLED,,PROCESSED",
            "REFUSED_EXCLUDED,,PROCESSED", "RETRY_LATER,,RETRY", "FAILED,,RETRY", "ITEM_ERROR,TRANSIENT,RETRY",
            "ITEM_ERROR,RATE_LIMIT,RETRY", "ITEM_ERROR,CONSENT,PROCESSED"})
    void pipelineOutcomesMapToRowOutcomes(SyncStatus status, PlaidErrorClass errorClass, DispatchOutcome expected) {
        assertThat(PlaidWebhookDispatcher.fromPipeline(sync(status, errorClass))).isEqualTo(expected);
    }

    @Test
    void syncUpdatesAvailableRunsOneWebhookSync() {
        when(pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK)).thenReturn(sync(SyncStatus.APPLIED, null));

        assertThat(dispatcher.dispatch(row("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verify(pipeline).run(USER_ID, ITEM, SyncReason.WEBHOOK);
        verifyNoInteractions(itemState);
    }

    @Test
    void loginRepairedFromLoginRequiredThenSyncs() {
        when(itemState.onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED)).thenReturn(ItemStateResult.APPLIED);
        when(pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK)).thenReturn(sync(SyncStatus.APPLIED, null));

        assertThat(dispatcher.dispatch(row("ITEM", "LOGIN_REPAIRED", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        InOrder order = inOrder(itemState, pipeline);
        order.verify(itemState).onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED);
        order.verify(pipeline).run(USER_ID, ITEM, SyncReason.WEBHOOK);
    }

    @Test
    void recurringUpdateRereadsStreams() {
        when(pipeline.streamsChanged(USER_ID, ITEM)).thenReturn(new PipelineOutcome(null,
                new StreamsReadOutcome(StreamsReadStatus.APPLIED, 1, 1, 0, null), null, null));

        assertThat(dispatcher.dispatch(row("TRANSACTIONS", "RECURRING_TRANSACTIONS_UPDATE", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verify(pipeline).streamsChanged(USER_ID, ITEM);
        verify(pipeline, never()).run(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INITIAL_UPDATE", "HISTORICAL_UPDATE", "DEFAULT_UPDATE", "TRANSACTIONS_REMOVED"})
    void legacyTransactionsCodesAreProcessedWithoutAction(String code) {
        assertThat(dispatcher.dispatch(row("TRANSACTIONS", code, ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verifyNoInteractions(pipeline, itemState);
    }

    @ParameterizedTest
    @CsvSource({"ITEM,NEW_ACCOUNTS_AVAILABLE", "AUTH,AUTOMATICALLY_VERIFIED"})
    void anythingElseIsProcessedAndLogged(String type, String code) {
        assertThat(dispatcher.dispatch(row(type, code, ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verifyNoInteractions(pipeline, itemState);
    }

    @Test
    void malformedItemPayloadsFail() {
        assertThat(dispatcher.dispatch(row("ITEM", "ERROR", ",\"error\":{}"))).isEqualTo(DispatchOutcome.FAILED);
        assertThat(dispatcher.dispatch(row("ITEM", "USER_ACCOUNT_REVOKED", ""))).isEqualTo(DispatchOutcome.FAILED);

        verifyNoInteractions(pipeline, itemState);
    }

    @Test
    void loginRepairedFromAnyOtherStatusWritesNothingMore() {
        when(itemState.onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED)).thenReturn(ItemStateResult.NO_OP);

        assertThat(dispatcher.dispatch(row("ITEM", "LOGIN_REPAIRED", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verifyNoInteractions(pipeline);
    }

    @Test
    void revocationsAndAckReachTheirHandlers() {
        when(itemState.onRevoked(USER_ID, ITEM, "USER_PERMISSION_REVOKED")).thenReturn(ItemStateResult.APPLIED);
        when(itemState.onAccountRevoked(USER_ID, ITEM, "acc-9")).thenReturn(ItemStateResult.APPLIED);
        when(itemState.onWebhookAcked(USER_ID, ITEM)).thenReturn(ItemStateResult.APPLIED);

        assertThat(dispatcher.dispatch(row("ITEM", "USER_PERMISSION_REVOKED", ""))).isEqualTo(DispatchOutcome.PROCESSED);
        assertThat(dispatcher.dispatch(row("ITEM", "USER_ACCOUNT_REVOKED", ",\"account_id\":\"acc-9\"")))
                .isEqualTo(DispatchOutcome.PROCESSED);
        assertThat(dispatcher.dispatch(row("ITEM", "WEBHOOK_UPDATE_ACKNOWLEDGED", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verify(itemState, times(1)).onRevoked(USER_ID, ITEM, "USER_PERMISSION_REVOKED");
        verify(itemState, times(1)).onAccountRevoked(USER_ID, ITEM, "acc-9");
        verify(itemState, times(1)).onWebhookAcked(USER_ID, ITEM);
        verifyNoInteractions(pipeline);
    }

    @Test
    void staleItemEventIsIgnored() {
        when(itemState.onWebhookAcked(USER_ID, ITEM)).thenReturn(ItemStateResult.STALE_ITEM);

        assertThat(dispatcher.dispatch(row("ITEM", "WEBHOOK_UPDATE_ACKNOWLEDGED", ""))).isEqualTo(DispatchOutcome.IGNORED_STALE);
    }

    @Test
    void unparsableOrUnresolvedRowsCallNothing() {
        PlaidWebhookEvent unparsable = row("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", "");
        unparsable.setPayloadJson("not json");
        PlaidWebhookEvent unresolved = row("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", "");
        unresolved.setUserId(null);

        assertThat(dispatcher.dispatch(unparsable)).isEqualTo(DispatchOutcome.FAILED);
        assertThat(dispatcher.dispatch(unresolved)).isEqualTo(DispatchOutcome.IGNORED_STALE);

        verifyNoInteractions(pipeline, itemState);
    }
}
