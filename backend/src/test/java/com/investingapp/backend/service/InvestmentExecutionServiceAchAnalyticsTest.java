package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.InvestmentTradeRepository;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scope: ONLY the two ACH settlement analytics events, at the point a transfer settles.
 *
 * <p>What this test is for is the counting. One ACH transfer can fund several
 * investment_executions (a batch shares one alpaca_transfer_id), the funding-status sweep enters
 * the settlement path once per execution, and the event describes the TRANSFER. So "three
 * executions settling under one transfer id produce exactly one event" is the property worth
 * pinning, alongside the exact property maps and the paths that must stay silent.
 *
 * <p>It cannot prove the transaction boundary: the outbox is mocked here, so nothing checks that
 * the event and the status change commit together. That is
 * {@link InvestmentExecutionAchAnalyticsRollbackTest}'s job.
 */
class InvestmentExecutionServiceAchAnalyticsTest {

    private static final String TRANSFER = "xfer-aaa";

    private InvestmentExecutionService service;
    private InvestmentExecutionRepository executionRepository;
    private InvestmentScheduleRepository scheduleRepository;
    private AnalyticsOutboxService analyticsOutboxService;
    private AlpacaService alpacaService;
    private EncryptionService encryptionService;
    private WashSaleGuardService washSaleGuardService;
    private InvestmentTradeRepository tradeRepository;
    private User user;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        executionRepository = mock(InvestmentExecutionRepository.class);
        scheduleRepository = mock(InvestmentScheduleRepository.class);
        analyticsOutboxService = mock(AnalyticsOutboxService.class);
        alpacaService = mock(AlpacaService.class);
        encryptionService = mock(EncryptionService.class);
        washSaleGuardService = mock(WashSaleGuardService.class);
        tradeRepository = mock(InvestmentTradeRepository.class);

        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(service, "investmentScheduleRepository", scheduleRepository);
        ReflectionTestUtils.setField(service, "analyticsOutboxService", analyticsOutboxService);
        ReflectionTestUtils.setField(service, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(service, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(service, "washSaleGuardService", washSaleGuardService);
        ReflectionTestUtils.setField(service, "tradeRepository", tradeRepository);
        ReflectionTestUtils.setField(service, "emailService", mock(EmailService.class));

        user = new User("ach@fredvested.com");
        user.setId(42L);
        user.setAlpacaAccountId("ENC:acct");

        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> i.getArgument(0));
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(encryptionService.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        // Nothing queued behind this transfer, and no trade placement worth following: trading is
        // not what is under test, and both of its outcomes (market open, market closed) leave the
        // settled executions out of FUNDING_INITIATED, which is all the event logic reads.
        when(executionRepository.findByUserIdAndStatus(anyLong(), any())).thenReturn(List.of());
        when(washSaleGuardService.blockingWindow(anyLong(), anyString(), any())).thenReturn(null);
        when(alpacaService.isFractionable(anyString())).thenReturn(true);
        when(alpacaService.placeBuyOrder(any(), anyString(), any())).thenReturn(
                new AlpacaService.AlpacaOrderResponse(null, "VTI", "FAILED", null, BigDecimal.TEN,
                        BigDecimal.ZERO, null, null, "ci: no broker"));
    }

    // --- fixtures ----------------------------------------------------------------------------

    private InvestmentExecution initiated(long id, String amount, String transferId) {
        InvestmentExecution execution = new InvestmentExecution(
                user, LocalDateTime.now().minusDays(2), new BigDecimal(amount), "stock", "VTI", "bank");
        execution.setId(id);
        execution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_INITIATED);
        execution.setExecutionDate(LocalDateTime.now().minusDays(2));
        execution.setAlpacaTransferId(transferId);
        execution.setAlpacaAccountId("ENC:acct");
        return execution;
    }

    private void awaiting(InvestmentExecution... executions) {
        List<InvestmentExecution> pending = new ArrayList<>(List.of(executions));
        when(executionRepository.findExecutionsAwaitingFundingCheck(
                eq(InvestmentExecution.ExecutionStatus.FUNDING_INITIATED), any())).thenReturn(pending);
        when(executionRepository.findByAlpacaTransferId(TRANSFER)).thenReturn(pending);
    }

    private void transferIs(String status, String amount) {
        when(alpacaService.checkTransferStatus(any(), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse(
                        TRANSFER, status, amount == null ? null : new BigDecimal(amount), null, null));
    }

    private void scheduleCreatedDaysAgo(long days) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setUser(user);
        schedule.setCreatedAt(LocalDateTime.now().minusDays(days));
        when(scheduleRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(schedule));
    }

    private Map<String, Object> captureSingleEvent(String expectedEventName) {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.forClass(Map.class);
        verify(analyticsOutboxService, times(1)).record(name.capture(), eq(user), properties.capture());
        assertThat(name.getValue()).isEqualTo(expectedEventName);
        return properties.getValue();
    }

    // --- the first settled transfer ----------------------------------------------------------

    @Test
    @DisplayName("the first settled transfer reports amount and how long it took, and nothing else")
    void firstSettledTransfer() {
        when(analyticsOutboxService.hasRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(false);
        scheduleCreatedDaysAgo(10);
        awaiting(initiated(1L, "400.00", TRANSFER));
        transferIs("COMPLETE", "400.00");

        service.checkFundingStatus();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("amount_band", "250_499");
        expected.put("days_since_schedule_band", "8_14");
        assertThat(captureSingleEvent(ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a lump sum from a user with no schedule reports 'unknown', never zero days")
    void firstSettledTransferWithoutASchedule() {
        when(analyticsOutboxService.hasRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(false);
        when(scheduleRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of());
        awaiting(initiated(1L, "25.00", TRANSFER));
        transferIs("COMPLETE", "25.00");

        service.checkFundingStatus();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("amount_band", "under_50");
        expected.put("days_since_schedule_band", "unknown");
        assertThat(captureSingleEvent(ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the amount is the TRANSFER's, not one execution's: a batch reports its total")
    void batchReportsTheWholeTransferAmount() {
        when(analyticsOutboxService.hasRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(false);
        scheduleCreatedDaysAgo(3);
        awaiting(initiated(1L, "300.00", TRANSFER),
                 initiated(2L, "300.00", TRANSFER),
                 initiated(3L, "400.00", TRANSFER));
        transferIs("COMPLETE", "1000.00");

        service.checkFundingStatus();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("amount_band", "1k_2499");
        expected.put("days_since_schedule_band", "1_3");
        assertThat(captureSingleEvent(ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a response with no amount falls back to the sum of what the transfer funds")
    void amountFallsBackToTheExecutionsItFunds() {
        when(analyticsOutboxService.hasRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(false);
        scheduleCreatedDaysAgo(60);
        awaiting(initiated(1L, "300.00", TRANSFER), initiated(2L, "400.00", TRANSFER));
        transferIs("COMPLETE", null);

        service.checkFundingStatus();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("amount_band", "500_999");
        expected.put("days_since_schedule_band", "31_90");
        assertThat(captureSingleEvent(ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED)).isEqualTo(expected);
    }

    // --- one event per transfer, not per execution -------------------------------------------

    @Test
    @DisplayName("three executions settling under one transfer id produce exactly ONE event")
    void aBatchOfThreeProducesOneEvent() {
        when(analyticsOutboxService.hasRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(false);
        scheduleCreatedDaysAgo(5);
        InvestmentExecution a = initiated(1L, "100.00", TRANSFER);
        InvestmentExecution b = initiated(2L, "100.00", TRANSFER);
        InvestmentExecution c = initiated(3L, "100.00", TRANSFER);
        awaiting(a, b, c);
        transferIs("COMPLETE", "300.00");

        // The sweep enters the settlement path once per pending execution: three times for this
        // batch. Only the pass that actually moves a sibling out of FUNDING_INITIATED reports.
        service.checkFundingStatus();

        verify(analyticsOutboxService, times(1)).record(anyString(), eq(user), anyMap());
        assertThat(List.of(a, b, c)).allSatisfy(settled ->
                assertThat(settled.getFundingCompletedAt()).isNotNull());
    }

    @Test
    @DisplayName("a transfer whose siblings already settled is a retry, and reports nothing")
    void alreadySettledTransferIsSilent() {
        InvestmentExecution settled = initiated(1L, "100.00", TRANSFER);
        settled.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_COMPLETED);
        settled.setFundingCompletedAt(LocalDateTime.now().minusHours(1));
        InvestmentExecution straggler = initiated(2L, "100.00", TRANSFER);
        // The sweep only ever returns FUNDING_INITIATED rows, so it finds the straggler alone. The
        // sibling lookup is what reveals that this transfer has already been reported once.
        awaiting(straggler);
        when(executionRepository.findByAlpacaTransferId(TRANSFER)).thenReturn(List.of(settled, straggler));
        transferIs("COMPLETE", "200.00");

        service.checkFundingStatus();

        // The straggler is still funded. What must not happen is a second event for one transfer.
        assertThat(straggler.getFundingCompletedAt()).isNotNull();
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    // --- every transfer after the first ------------------------------------------------------

    @Test
    @DisplayName("a later transfer reports its ordinal, banded, and carries no amount")
    void laterTransferReportsItsOrdinal() {
        when(analyticsOutboxService.hasRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(true);
        when(analyticsOutboxService.countRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(1L);
        when(analyticsOutboxService.countRecorded(user, ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED))
                .thenReturn(2L);
        awaiting(initiated(9L, "500.00", TRANSFER));
        transferIs("COMPLETE", "500.00");

        service.checkFundingStatus();

        // One first + two laters already on the ledger, so this is the fourth.
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("transfer_number_band", "4_9");
        assertThat(captureSingleEvent(ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the second transfer of all is number two")
    void secondTransferIsNumberTwo() {
        when(analyticsOutboxService.hasRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(true);
        when(analyticsOutboxService.countRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED))
                .thenReturn(1L);
        when(analyticsOutboxService.countRecorded(user, ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED))
                .thenReturn(0L);
        awaiting(initiated(9L, "500.00", TRANSFER));
        transferIs("COMPLETE", "500.00");

        service.checkFundingStatus();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("transfer_number_band", "2_3");
        assertThat(captureSingleEvent(ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED)).isEqualTo(expected);
    }

    // --- the paths that must stay silent -----------------------------------------------------

    @Test
    @DisplayName("a transfer still in flight reports nothing: the event is settlement, not intent")
    void pendingTransferIsSilent() {
        awaiting(initiated(1L, "400.00", TRANSFER));
        transferIs("PENDING", "400.00");

        service.checkFundingStatus();

        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("a returned transfer reports nothing")
    void returnedTransferIsSilent() {
        awaiting(initiated(1L, "400.00", TRANSFER));
        transferIs("RETURNED", "400.00");

        service.checkFundingStatus();

        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("a status we cannot read reports nothing")
    void unknownTransferStatusIsSilent() {
        awaiting(initiated(1L, "400.00", TRANSFER));
        when(alpacaService.checkTransferStatus(any(), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse(TRANSFER, "ERROR", null, null, "boom"));

        service.checkFundingStatus();

        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("buying-power funding reports nothing: no ACH transfer settled")
    void buyingPowerFundingIsSilent() {
        when(alpacaService.getBuyingPower(anyString())).thenReturn(new BigDecimal("5000.00"));
        InvestmentExecution execution = new InvestmentExecution(
                user, LocalDateTime.now(), new BigDecimal("400.00"), "stock", "VTI", "buying_power");
        execution.setId(77L);

        service.processInvestmentExecutionImmediately(execution);

        assertThat(execution.getFundingCompletedAt()).isNotNull();
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }
}
