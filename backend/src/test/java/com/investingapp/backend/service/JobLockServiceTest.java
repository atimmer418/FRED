package com.investingapp.backend.service;

import com.investingapp.backend.repository.JobLockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobLockServiceTest {

    private static final Duration LEASE = Duration.ofMinutes(10);

    private JobLockRepository repository;
    private PlatformTransactionManager transactionManager;
    private JobLockService service;

    @BeforeEach
    void setUp() {
        repository = mock(JobLockRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = new JobLockService(repository, transactionManager);
    }

    @Test
    void freshRowIsTakenByInsertAloneInItsOwnTransaction() {
        when(repository.insertIgnore(eq(JobLocks.EOD_BATCH), any(), anyString())).thenReturn(1);

        assertTrue(service.tryAcquire(JobLocks.EOD_BATCH, LEASE));

        verify(repository, never()).renew(anyString(), any(), any(), anyString());
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior(),
                "the lock row must commit on its own, before the job body runs");
        verify(transactionManager).commit(any());
    }

    @Test
    void theLeaseClockIsUtcNotEasternOrTheSystemDefaultZone() {
        when(repository.insertIgnore(anyString(), any(), anyString())).thenReturn(1);

        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        service.tryAcquire(JobLocks.EOD_BATCH, LEASE);
        verify(repository).insertIgnore(anyString(), until.capture(), anyString());

        LocalDateTime expectedUntil = LocalDateTime.now(ZoneOffset.UTC).plus(LEASE);
        assertTrue(Duration.between(until.getValue(), expectedUntil).abs().compareTo(Duration.ofSeconds(5)) < 0,
                "locked_until must be derived from the UTC clock, not Eastern or the JVM default zone");
    }

    @Test
    void expiredLeaseIsTakenOverByRenewWithUntilEqualToNowPlusLease() {
        when(repository.insertIgnore(anyString(), any(), anyString())).thenReturn(0);
        when(repository.renew(eq(JobLocks.ACH_POLL), any(), any(), anyString())).thenReturn(1);

        assertTrue(service.tryAcquire(JobLocks.ACH_POLL, LEASE));

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).renew(eq(JobLocks.ACH_POLL), now.capture(), until.capture(), anyString());
        assertEquals(now.getValue().plus(LEASE), until.getValue());
    }

    @Test
    void runningLeaseIsNotTaken() {
        when(repository.insertIgnore(anyString(), any(), anyString())).thenReturn(0);
        when(repository.renew(anyString(), any(), any(), anyString())).thenReturn(0);

        assertFalse(service.tryAcquire(JobLocks.EOD_BATCH, LEASE));
    }

    @Test
    void databaseFailureReadsAsNotAcquiredAndNeverThrows() {
        when(repository.insertIgnore(anyString(), any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        boolean acquired = assertDoesNotThrow(() -> service.tryAcquire(JobLocks.EOD_BATCH, LEASE));

        assertFalse(acquired);
        verify(transactionManager).rollback(any());
    }

    @Test
    void releaseDeletesOnlyTheRowThisProcessOwns() {
        when(repository.insertIgnore(anyString(), any(), anyString())).thenReturn(1);
        service.tryAcquire(JobLocks.EOD_BATCH, LEASE);
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIgnore(anyString(), any(), owner.capture());

        service.release(JobLocks.EOD_BATCH);

        verify(repository).deleteOwned(JobLocks.EOD_BATCH, owner.getValue());
        assertFalse(owner.getValue().isBlank());
        assertTrue(owner.getValue().length() <= 64, "locked_by is VARCHAR(64)");
    }

    @Test
    void releaseSwallowsDatabaseFailuresBecauseTheLeaseExpiresAnyway() {
        when(repository.deleteOwned(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> service.release(JobLocks.EOD_BATCH));
    }
}
