package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scope: ONLY the three 11:59 hardening changes (spec 5.5, decision 15) around the unchanged
 * one-POST-per-user shape: selection by createdAt < batchStart plus the marker filter, the
 * conditional claim before the POST, the null-relationship fail-fast. The REQUIRES_NEW boundary
 * of the claim is not provable here: the transaction manager is a mock, so TransactionTemplate
 * runs the callback straight through (same pattern as TlhHarvestServiceCycleTest).
 */
class InvestmentExecutionServiceEodBatchTest {

    private static final LocalDateTime BATCH_START = LocalDateTime.of(2026, 10, 19, 23, 59, 0);
    private static final LocalDate BATCH_NIGHT = LocalDate.of(2026, 10, 19);
    private static final String MARKER = "Queued for end-of-day batch processing";

    private InvestmentExecutionService service;
    private InvestmentExecutionRepository executionRepository;
    private AlpacaService alpacaService;
    private EncryptionService encryptionService;
    private EmailService emailService;
    private User user;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        executionRepository = mock(InvestmentExecutionRepository.class);
        alpacaService = mock(AlpacaService.class);
        encryptionService = mock(EncryptionService.class);
        emailService = mock(EmailService.class);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(service, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(service, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));

        user = new User("batch@fredvested.com");
        user.setId(42L);
        // Unprefixed on purpose: encryptionService.decrypt is identity-mocked below, so whatever
        // goes in here is exactly what the test asserts reaches alpacaService.
        user.setAlpacaAccountId("acct");
        user.setAlpacaAchRelationshipId("rel");

        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> i.getArgument(0));
        when(encryptionService.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        when(encryptionService.encrypt(anyString())).thenAnswer(i -> "ENC:" + i.getArgument(0));
        when(alpacaService.initiateAchTransfer(any(), any(), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse("xfer-1", "QUEUED", new BigDecimal("550.00"), null, null));
    }

    private InvestmentExecution queued(long id, User owner, String amount, String marker) {
        InvestmentExecution execution = new InvestmentExecution(
                owner, BATCH_START.minusHours(6), new BigDecimal(amount), "portfolio", null, "bank");
        execution.setId(id);
        execution.setStatus(ExecutionStatus.SCHEDULED);
        execution.setErrorMessage(marker);
        return execution;
    }

    private void selected(InvestmentExecution... executions) {
        when(executionRepository.findByStatusAndCreatedAtBefore(ExecutionStatus.SCHEDULED, BATCH_START))
                .thenReturn(List.of(executions));
    }

    @Test
    void batchClaimIsConditionalUpdate() {
        InvestmentExecution lump = queued(1L, user, "400.00", MARKER);
        InvestmentExecution recurring = queued(2L, user, "150.00", MARKER + " (recurring investment)");
        selected(lump, recurring);
        when(executionRepository.claimForBatch(List.of(1L, 2L), BATCH_START, BATCH_NIGHT)).thenReturn(2);

        service.runEndOfDayBatch(BATCH_START);

        InOrder order = inOrder(executionRepository, alpacaService);
        order.verify(executionRepository).claimForBatch(List.of(1L, 2L), BATCH_START, BATCH_NIGHT);
        order.verify(alpacaService).initiateAchTransfer("acct", "rel", new BigDecimal("550.00"));
        verify(alpacaService, times(1)).initiateAchTransfer(any(), any(), any());
        assertThat(List.of(lump, recurring)).allSatisfy(execution -> {
            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FUNDING_INITIATED);
            assertThat(execution.getAlpacaTransferId()).isEqualTo("xfer-1");
            // the claim is mirrored on the entity so the later flush cannot erase it
            assertThat(execution.getTransferAttemptedAt()).isEqualTo(BATCH_START);
            assertThat(execution.getBatchNight()).isEqualTo(BATCH_NIGHT);
        });
    }

    @Test
    void shortClaimSkipsTheUserAndPostsNothing() {
        InvestmentExecution a = queued(1L, user, "400.00", MARKER);
        InvestmentExecution b = queued(2L, user, "150.00", MARKER);
        selected(a, b);
        when(executionRepository.claimForBatch(List.of(1L, 2L), BATCH_START, BATCH_NIGHT)).thenReturn(1);

        service.runEndOfDayBatch(BATCH_START);

        verify(alpacaService, never()).initiateAchTransfer(any(), any(), any());
        verify(executionRepository, never()).save(any());
        assertThat(a.getStatus()).isEqualTo(ExecutionStatus.SCHEDULED);
        assertThat(a.getTransferAttemptedAt()).isNull();
    }

    @Test
    void nullRelationshipFailsFastWithoutClaimOrPost() {
        user.setAlpacaAchRelationshipId(null);
        InvestmentExecution a = queued(1L, user, "400.00", MARKER);
        InvestmentExecution b = queued(2L, user, "150.00", MARKER);
        selected(a, b);

        service.runEndOfDayBatch(BATCH_START);

        verify(alpacaService, never()).initiateAchTransfer(any(), any(), any());
        verify(executionRepository, never()).claimForBatch(anyList(), any(), any());
        verify(encryptionService, never()).decrypt(any());
        assertThat(List.of(a, b)).allSatisfy(execution -> {
            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FUNDING_FAILED);
            assertThat(execution.getErrorMessage()).isEqualTo("No ACH relationship on file");
        });
        verify(executionRepository, times(2)).save(any(InvestmentExecution.class));
        verify(emailService, times(1)).sendEmail(eq("batch@fredvested.com"), anyString(), contains("No ACH relationship on file"));
        verify(emailService).sendEmail(eq("batch@fredvested.com"), anyString(), contains("550.00"));
    }

    @Test
    void selectionIsCreatedAtBeforeBatchStartNotToday() {
        selected();
        service.runEndOfDayBatch(BATCH_START);
        verify(executionRepository).findByStatusAndCreatedAtBefore(ExecutionStatus.SCHEDULED, BATCH_START);
        verify(executionRepository, never()).findByStatus(any());
    }

    @Test
    void markerStringUnchanged() {
        selected(queued(1L, user, "400.00", null), queued(2L, user, "150.00", "Waiting on something else"));
        service.runEndOfDayBatch(BATCH_START);
        verify(executionRepository, never()).claimForBatch(anyList(), any(), any());
        verify(alpacaService, never()).initiateAchTransfer(any(), any(), any());
    }

    @Test
    void aFailedPostForOneUserDoesNotStopTheNext() {
        User second = new User("second@fredvested.com");
        second.setId(43L);
        second.setAlpacaAccountId("acct2");
        second.setAlpacaAchRelationshipId("rel2");
        InvestmentExecution mine = queued(1L, user, "400.00", MARKER);
        InvestmentExecution theirs = queued(9L, second, "75.00", MARKER);
        selected(mine, theirs);
        when(executionRepository.claimForBatch(anyList(), eq(BATCH_START), eq(BATCH_NIGHT))).thenReturn(1);
        when(alpacaService.initiateAchTransfer(eq("acct"), eq("rel"), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse(null, "FAILED", new BigDecimal("400.00"), null, "insufficient funds"));
        when(alpacaService.initiateAchTransfer(eq("acct2"), eq("rel2"), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse("xfer-2", "QUEUED", new BigDecimal("75.00"), null, null));

        service.runEndOfDayBatch(BATCH_START);

        assertThat(mine.getStatus()).isEqualTo(ExecutionStatus.FUNDING_FAILED);
        assertThat(theirs.getStatus()).isEqualTo(ExecutionStatus.FUNDING_INITIATED);
        assertThat(theirs.getAlpacaTransferId()).isEqualTo("xfer-2");
    }
}
