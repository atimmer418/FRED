package com.investingapp.backend.scheduler;

import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.AccountStatusService;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountStatusSchedulerTest {

    private AccountStatusService accountStatusService;
    private SchedulerGate schedulerGate;
    private JobLockService jobLockService;
    private AccountStatusScheduler scheduler;

    @BeforeEach
    void setUp() {
        accountStatusService = mock(AccountStatusService.class);
        schedulerGate = mock(SchedulerGate.class);
        jobLockService = mock(JobLockService.class);
        when(schedulerGate.isEnabled()).thenReturn(true);
        when(jobLockService.tryAcquire(anyString(), any())).thenReturn(true);
        scheduler = new AccountStatusScheduler(accountStatusService, schedulerGate, jobLockService);
    }

    @Test
    void closedGateSkipsBothJobs() {
        when(schedulerGate.isEnabled()).thenReturn(false);

        scheduler.pollAccountStatuses();
        scheduler.setupPendingAchRelationships();

        verifyNoInteractions(accountStatusService, jobLockService);
    }

    @Test
    void onlyTheAchPollLocksAndItReleasesEvenWhenTheServiceThrows() {
        scheduler.pollAccountStatuses();
        scheduler.setupPendingAchRelationships();

        verify(accountStatusService).checkAndUpdateAccountStatuses();
        verify(jobLockService, times(1)).tryAcquire(anyString(), any());
        InOrder inOrder = inOrder(jobLockService, accountStatusService);
        inOrder.verify(jobLockService).tryAcquire(JobLocks.ACH_POLL, Duration.ofMinutes(10));
        inOrder.verify(accountStatusService).setupPendingAchRelationships();
        inOrder.verify(jobLockService).release(JobLocks.ACH_POLL);

        doThrow(new IllegalStateException("boom")).when(accountStatusService).setupPendingAchRelationships();
        assertDoesNotThrow(scheduler::setupPendingAchRelationships);
        verify(jobLockService, times(2)).release(JobLocks.ACH_POLL);
    }

    @Test
    void achPollSkipsWhenTheLockIsNotTaken() {
        when(jobLockService.tryAcquire(JobLocks.ACH_POLL, Duration.ofMinutes(10))).thenReturn(false);

        scheduler.setupPendingAchRelationships();

        verify(accountStatusService, never()).setupPendingAchRelationships();
        verify(jobLockService, never()).release(anyString());
    }
}
