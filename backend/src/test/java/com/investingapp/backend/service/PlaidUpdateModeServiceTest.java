package com.investingapp.backend.service;

import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidUpdateModeService.UpdateModeResult;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemWebhookUpdateResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.PlaidError;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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
