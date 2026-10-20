package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * Scope: the 11:59 hardening changes (spec 5.5, decision 15) around the unchanged one-POST-per-
 * user shape: selection by createdAt < batchStart, never-claimed (transferAttemptedAt IS NULL)
 * plus the marker filter, the conditional claim before the POST, the null-relationship fail-fast,
 * and the post-POST status write committing in its own REQUIRES_NEW unit (findings round 1: a
 * stranded claim must not block every later night, and the Alpaca POST must never sit inside the
 * ambient class-level transaction). The REQUIRES_NEW propagation itself IS provable here (captured
 * off the mocked PlatformTransactionManager, same pattern as JobLockServiceTest); what the mock
 * cannot prove is that a real connection actually commits independently per call, since
 * TransactionTemplate runs the callback straight through regardless (same caveat as
 * TlhHarvestServiceCycleTest).
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
    private PlatformTransactionManager transactionManager;
    private User user;

    /** Populated by queued(); findAllById's default stub reloads from here, simulating a DB round trip. */
    private final Map<Long, InvestmentExecution> byId = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        executionRepository = mock(InvestmentExecutionRepository.class);
        alpacaService = mock(AlpacaService.class);
        encryptionService = mock(EncryptionService.class);
        emailService = mock(EmailService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(service, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(service, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);

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
        // The post-POST write re-loads by id: this simulates that round trip against byId, which
        // queued() populates, so the SAME instances a test holds a reference to are the ones the
        // write mutates (standing in for "freshly loaded from the DB").
        when(executionRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<InvestmentExecution> found = new ArrayList<>();
            for (Long id : ids) {
                InvestmentExecution execution = byId.get(id);
                if (execution != null) {
                    found.add(execution);
                }
            }
            return found;
        });
    }

    private InvestmentExecution queued(long id, User owner, String amount, String marker) {
        InvestmentExecution execution = new InvestmentExecution(
                owner, BATCH_START.minusHours(6), new BigDecimal(amount), "portfolio", null, "bank");
        execution.setId(id);
        execution.setStatus(ExecutionStatus.SCHEDULED);
        execution.setErrorMessage(marker);
        byId.put(id, execution);
        return execution;
    }

    private void selected(InvestmentExecution... executions) {
        when(executionRepository.findByStatusAndTransferAttemptedAtIsNullAndCreatedAtBefore(ExecutionStatus.SCHEDULED, BATCH_START))
                .thenReturn(List.of(executions));
    }

    /**
     * Stubs a successful claim AND mirrors what the real UPDATE would have persisted onto the
     * given rows, since claimForBatch is a mocked @Modifying query here (it does not actually touch
     * the entities). The later reload-via-findAllById then reflects that committed state, the same
     * way a real database round trip would.
     */
    private void claimSucceeds(List<Long> ids, InvestmentExecution... claimedRows) {
        when(executionRepository.claimForBatch(ids, BATCH_START, BATCH_NIGHT)).thenAnswer(invocation -> {
            for (InvestmentExecution execution : claimedRows) {
                execution.setTransferAttemptedAt(BATCH_START);
                execution.setBatchNight(BATCH_NIGHT);
            }
            return claimedRows.length;
        });
    }

    @Test
    void batchClaimIsConditionalUpdate() {
        InvestmentExecution lump = queued(1L, user, "400.00", MARKER);
        InvestmentExecution recurring = queued(2L, user, "150.00", MARKER + " (recurring investment)");
        selected(lump, recurring);
        claimSucceeds(List.of(1L, 2L), lump, recurring);

        service.runEndOfDayBatch(BATCH_START);

        InOrder order = inOrder(executionRepository, alpacaService);
        order.verify(executionRepository).claimForBatch(List.of(1L, 2L), BATCH_START, BATCH_NIGHT);
        order.verify(alpacaService).initiateAchTransfer("acct", "rel", new BigDecimal("550.00"));
        verify(alpacaService, times(1)).initiateAchTransfer(any(), any(), any());
        assertThat(List.of(lump, recurring)).allSatisfy(execution -> {
            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FUNDING_INITIATED);
            assertThat(execution.getAlpacaTransferId()).isEqualTo("xfer-1");
            // the claim committed before the POST; the reloaded row already carries it
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
    void selectionIsNeverClaimedAndCreatedAtBeforeBatchStartNotToday() {
        selected();
        service.runEndOfDayBatch(BATCH_START);
        verify(executionRepository).findByStatusAndTransferAttemptedAtIsNullAndCreatedAtBefore(ExecutionStatus.SCHEDULED, BATCH_START);
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

    /** Finding round 1, #1: a row claimed on an earlier night but never written is never selected again. */
    @Test
    void strandedRowIsNotSelectedAgain() {
        // Night 1: claimed, then the process died before the status write committed. The real
        // query's transferAttemptedAt IS NULL clause would exclude this row on its own; simulating
        // that exclusion at the mock boundary (only the fresh row comes back from selection) is
        // what proves the per-user claim math no longer counts it.
        InvestmentExecution stranded = queued(1L, user, "400.00", MARKER);
        stranded.setTransferAttemptedAt(BATCH_START.minusDays(1));
        stranded.setBatchNight(BATCH_NIGHT.minusDays(1));
        InvestmentExecution fresh = queued(2L, user, "150.00", MARKER);
        selected(fresh);
        claimSucceeds(List.of(2L), fresh);

        service.runEndOfDayBatch(BATCH_START);

        verify(executionRepository).claimForBatch(List.of(2L), BATCH_START, BATCH_NIGHT);
        verify(alpacaService).initiateAchTransfer("acct", "rel", new BigDecimal("150.00"));
        assertThat(fresh.getStatus()).isEqualTo(ExecutionStatus.FUNDING_INITIATED);
        // the stranded row from night 1 is untouched by tonight's run: it was never selected
        assertThat(stranded.getStatus()).isEqualTo(ExecutionStatus.SCHEDULED);
    }

    /** Finding round 1, #2: the post-POST write commits in its own REQUIRES_NEW unit, not the claim's. */
    @Test
    void postPostStatusWriteUsesItsOwnRequiresNewTransaction() {
        InvestmentExecution lump = queued(1L, user, "400.00", MARKER);
        selected(lump);
        claimSucceeds(List.of(1L), lump);

        service.runEndOfDayBatch(BATCH_START);

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        // once to commit the claim, once to commit the post-POST status write
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(definition ->
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        assertThat(lump.getStatus()).isEqualTo(ExecutionStatus.FUNDING_INITIATED);
    }

    /** Finding round 1, #2: one user's write failure cannot undo another user's already-committed write. */
    @Test
    void oneUsersWriteFailureDoesNotUndoAnotherUsersCommittedWrite() {
        User second = new User("second@fredvested.com");
        second.setId(43L);
        second.setAlpacaAccountId("acct2");
        second.setAlpacaAchRelationshipId("rel2");
        InvestmentExecution mine = queued(1L, user, "400.00", MARKER);
        InvestmentExecution theirs = queued(9L, second, "75.00", MARKER);
        selected(mine, theirs);
        when(executionRepository.claimForBatch(anyList(), eq(BATCH_START), eq(BATCH_NIGHT))).thenReturn(1);
        when(alpacaService.initiateAchTransfer(eq("acct"), eq("rel"), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse("xfer-1", "QUEUED", new BigDecimal("400.00"), null, null));
        when(alpacaService.initiateAchTransfer(eq("acct2"), eq("rel2"), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse("xfer-2", "QUEUED", new BigDecimal("75.00"), null, null));
        // User B's post-POST write transaction blows up reloading its rows; user A's write is a
        // wholly separate REQUIRES_NEW unit and must already be committed by the time this throws.
        when(executionRepository.findAllById(List.of(9L))).thenThrow(new RuntimeException("write tx failed for user B"));

        service.runEndOfDayBatch(BATCH_START);

        assertThat(mine.getStatus()).isEqualTo(ExecutionStatus.FUNDING_INITIATED);
        assertThat(mine.getAlpacaTransferId()).isEqualTo("xfer-1");
        // theirs never got the write: the reload blew up before any save for that user
        assertThat(theirs.getStatus()).isEqualTo(ExecutionStatus.SCHEDULED);
    }
}
