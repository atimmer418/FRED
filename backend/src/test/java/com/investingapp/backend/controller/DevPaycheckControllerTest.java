package com.investingapp.backend.controller;

import com.investingapp.backend.controller.DevPaycheckController.FireRequest;
import com.investingapp.backend.controller.DevPaycheckController.SandboxPaycheckRequest;
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.admin.ShadowReport;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.PaycheckReconcileService;
import com.investingapp.backend.service.PaycheckReconcileService.ReconcileReport;
import com.investingapp.backend.service.PaycheckSyncPipeline;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.service.ShadowReportService;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.CustomSandboxTransaction;
import com.plaid.client.model.SandboxItemFireWebhookRequest;
import com.plaid.client.model.SandboxItemFireWebhookResponse;
import com.plaid.client.model.SandboxItemResetLoginRequest;
import com.plaid.client.model.SandboxItemResetLoginResponse;
import com.plaid.client.model.SandboxTransactionsCreateRequest;
import com.plaid.client.model.SandboxTransactionsCreateResponse;
import com.plaid.client.model.WebhookType;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
        List<Boolean> transactionOpenDuringPlaid = new ArrayList<>();
        doAnswer(invocation -> {
            transactionOpenDuringPlaid.add(TransactionSynchronizationManager.isActualTransactionActive());
            return new PlaidCallResult<>(new SandboxTransactionsCreateResponse(), null);
        }).when(gateway).call(eq("sandbox_transactions_create"), any());

        ResponseEntity<?> response = controller.sandboxPaycheck(7L, acme(3, 14, null));

        // PlaidGateway refuses a call inside a transaction: the controller opens none, on the class or any method.
        assertThat(transactionOpenDuringPlaid).containsExactly(false);
        assertThat(DevPaycheckController.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(java.util.Arrays.stream(DevPaycheckController.class.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(Transactional.class))).isTrue();

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
