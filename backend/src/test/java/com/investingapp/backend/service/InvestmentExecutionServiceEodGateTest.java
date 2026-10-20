package com.investingapp.backend.service;

import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Only the gate and the lock around processEndOfDayBatchTransfers. The batch body is not
 * stubbed by query name on purpose: T06 replaces findByStatus with
 * findByStatusAndCreatedAtBefore and this test must keep passing unchanged.
 */
class InvestmentExecutionServiceEodGateTest {

    /** Every repository method throws; Object methods (toString, hashCode) keep their defaults. */
    private static final Answer<Object> DB_DOWN = invocation -> {
        if (invocation.getMethod().getDeclaringClass() == Object.class) {
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }
        throw new IllegalStateException("db down");
    };

    private InvestmentExecutionService service;
    private InvestmentExecutionRepository executionRepository;
    private SchedulerGate schedulerGate;
    private JobLockService jobLockService;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        // RETURNS_DEFAULTS: every List-returning query answers an empty list, so the batch finds no work.
        executionRepository = mock(InvestmentExecutionRepository.class);
        schedulerGate = mock(SchedulerGate.class);
        jobLockService = mock(JobLockService.class);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(service, "schedulerGate", schedulerGate);
        ReflectionTestUtils.setField(service, "jobLockService", jobLockService);
        when(schedulerGate.isEnabled()).thenReturn(true);
        when(jobLockService.tryAcquire(anyString(), any())).thenReturn(true);
    }

    @Test
    void closedGateTouchesNothing() {
        when(schedulerGate.isEnabled()).thenReturn(false);

        service.processEndOfDayBatchTransfers();

        verifyNoInteractions(jobLockService, executionRepository);
    }

    @Test
    void untakenLockSkipsTheBatchWithoutReleasing() {
        when(jobLockService.tryAcquire(JobLocks.EOD_BATCH, Duration.ofMinutes(30))).thenReturn(false);

        service.processEndOfDayBatchTransfers();

        verifyNoInteractions(executionRepository);
        verify(jobLockService, never()).release(anyString());
    }

    @Test
    void batchTakesItsLockRunsThenReleases() {
        service.processEndOfDayBatchTransfers();

        InOrder inOrder = inOrder(jobLockService);
        inOrder.verify(jobLockService).tryAcquire(JobLocks.EOD_BATCH, Duration.ofMinutes(30));
        inOrder.verify(jobLockService).release(JobLocks.EOD_BATCH);
        assertFalse(mockingDetails(executionRepository).getInvocations().isEmpty(),
                "the batch body must run between acquire and release");
    }

    @Test
    void lockIsReleasedEvenWhenTheBatchThrows() {
        ReflectionTestUtils.setField(service, "executionRepository",
                mock(InvestmentExecutionRepository.class, DB_DOWN));

        assertDoesNotThrow(service::processEndOfDayBatchTransfers);

        verify(jobLockService).release(JobLocks.EOD_BATCH);
    }
}
