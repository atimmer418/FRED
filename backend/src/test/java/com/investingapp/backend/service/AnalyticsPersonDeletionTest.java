package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.AnalyticsPersonDeletion;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.AnalyticsPersonDeletionRepository;
import com.investingapp.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Deleting someone's analytics when they close their account is a promise, not a metric. These
 * tests pin the two ways that promise could quietly become false: a request recorded beside a
 * closure that rolled back, and a request marked done that was never actually sent.
 */
class AnalyticsPersonDeletionTest {

    private AnalyticsPersonDeletionRepository repository;
    private AnalyticsPersonDeletionService service;

    private static final String PUBLIC_ID = "11111111-2222-4333-8444-555555555555";

    @BeforeEach
    void setUp() {
        repository = mock(AnalyticsPersonDeletionRepository.class);
        when(repository.findByPublicId(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new AnalyticsPersonDeletionService(repository);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private void insideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private User user(String publicId) {
        User u = new User();
        u.setId(7L);
        u.setPublicId(publicId);
        return u;
    }

    // --- recording the request ----------------------------------------------------------------

    @Test
    @DisplayName("records a pending request carrying only the opaque id")
    void recordsTheRequest() {
        insideTransaction();

        AnalyticsPersonDeletion deletion = service.request(user(PUBLIC_ID));

        assertNotNull(deletion);
        assertEquals(PUBLIC_ID, deletion.getPublicId());
        assertEquals(AnalyticsPersonDeletion.STATUS_PENDING, deletion.getStatus());
        assertNotNull(deletion.getRequestedAt());
        assertNull(deletion.getCompletedAt());
    }

    @Test
    @DisplayName("refuses outside a transaction, where it could outlive a rolled-back closure")
    void refusesOutsideATransaction() {
        assertNull(service.request(user(PUBLIC_ID)));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("asking twice is the same request, not two")
    void isIdempotentPerPerson() {
        insideTransaction();
        AnalyticsPersonDeletion existing = new AnalyticsPersonDeletion();
        existing.setPublicId(PUBLIC_ID);
        when(repository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(existing));

        assertSame(existing, service.request(user(PUBLIC_ID)));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a user with no public id has no person record to delete")
    void noPublicIdNothingToDo() {
        insideTransaction();

        assertNull(service.request(user(null)));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("never throws into the closure: a lost request beats an account that cannot close")
    void neverBreaksTheClosure() {
        insideTransaction();
        when(repository.findByPublicId(anyString())).thenThrow(new RuntimeException("database down"));

        assertDoesNotThrow(() -> service.request(user(PUBLIC_ID)));
    }

    // --- sending it ---------------------------------------------------------------------------

    /** Records what it was asked to delete, and can be told to fail. */
    private static class RecordingGateway implements AnalyticsPersonGateway {
        final List<String> deleted = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void deletePerson(String publicId) {
            if (failWith != null) {
                throw failWith;
            }
            deleted.add(publicId);
        }
    }

    private AnalyticsPersonDeletion pending(String publicId, int attempts) {
        AnalyticsPersonDeletion d = new AnalyticsPersonDeletion();
        d.setId(1L);
        d.setPublicId(publicId);
        d.setStatus(AnalyticsPersonDeletion.STATUS_PENDING);
        d.setAttempts(attempts);
        return d;
    }

    private AnalyticsPublisherService publisherWith(RecordingGateway gateway,
                                                    AnalyticsPersonDeletion... queued) {
        AnalyticsPersonDeletionRepository deletions = mock(AnalyticsPersonDeletionRepository.class);
        when(deletions.findSendable(eq(AnalyticsPersonDeletion.STATUS_PENDING), any(), any(Pageable.class)))
                .thenReturn(List.of(queued));
        when(deletions.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        AnalyticsOutboxRepository outbox = mock(AnalyticsOutboxRepository.class);
        when(outbox.findPublishable(anyString(), any(), any(Pageable.class))).thenReturn(List.of());

        return new AnalyticsPublisherService(outbox, deletions, mock(UserRepository.class),
                events -> { }, gateway, new ObjectMapper(), true, 100);
    }

    @Test
    @DisplayName("sends the deletion and marks it completed")
    void sendsAndCompletes() {
        RecordingGateway gateway = new RecordingGateway();
        AnalyticsPersonDeletion queued = pending(PUBLIC_ID, 0);

        assertEquals(1, publisherWith(gateway, queued).publishPendingDeletions());

        assertEquals(List.of(PUBLIC_ID), gateway.deleted);
        assertEquals(AnalyticsPersonDeletion.STATUS_COMPLETED, queued.getStatus());
        assertNotNull(queued.getCompletedAt());
        assertNull(queued.getNextAttemptAt());
    }

    @Test
    @DisplayName("a failure backs off on the same curve as the events, and stays owed")
    void backsOffAndStaysOwed() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.failWith = new RuntimeException("posthog unavailable");
        AnalyticsPersonDeletion queued = pending(PUBLIC_ID, 0);

        assertEquals(0, publisherWith(gateway, queued).publishPendingDeletions());

        assertEquals(AnalyticsPersonDeletion.STATUS_PENDING, queued.getStatus(), "still owed");
        assertEquals(1, queued.getAttempts());
        assertNotNull(queued.getNextAttemptAt());
        assertNull(queued.getCompletedAt(), "never completed by a failure");
        assertTrue(queued.getLastError().contains("posthog unavailable"));
    }

    @Test
    @DisplayName("one person who cannot be deleted does not stop the others")
    void oneFailureDoesNotStopTheBatch() {
        // The gateway fails only for the first id, so the second must still go.
        AnalyticsPersonDeletion first = pending(PUBLIC_ID, 0);
        AnalyticsPersonDeletion second = pending("22222222-2222-4333-8444-555555555555", 0);
        RecordingGateway gateway = new RecordingGateway() {
            @Override
            public void deletePerson(String publicId) {
                if (publicId.equals(PUBLIC_ID)) {
                    throw new RuntimeException("stuck");
                }
                super.deletePerson(publicId);
            }
        };

        assertEquals(1, publisherWith(gateway, first, second).publishPendingDeletions());

        assertEquals(AnalyticsPersonDeletion.STATUS_PENDING, first.getStatus());
        assertEquals(AnalyticsPersonDeletion.STATUS_COMPLETED, second.getStatus());
    }

    @Test
    @DisplayName("a request that keeps failing is parked for a human, never dropped")
    void parksAfterMaxAttempts() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.failWith = new RuntimeException("still refusing");
        AnalyticsPersonDeletion queued = pending(PUBLIC_ID, AnalyticsPublisherService.MAX_ATTEMPTS - 1);

        publisherWith(gateway, queued).publishPendingDeletions();

        assertEquals(AnalyticsPersonDeletion.STATUS_PARKED, queued.getStatus());
        assertNull(queued.getCompletedAt(), "parking is not completing");
        assertNull(queued.getNextAttemptAt());
    }

    @Test
    @DisplayName("disabled sends nothing and completes nothing")
    void disabledDoesNothing() {
        RecordingGateway gateway = new RecordingGateway();
        AnalyticsPersonDeletion queued = pending(PUBLIC_ID, 0);
        AnalyticsPersonDeletionRepository deletions = mock(AnalyticsPersonDeletionRepository.class);
        AnalyticsOutboxRepository outbox = mock(AnalyticsOutboxRepository.class);
        AnalyticsPublisherService off = new AnalyticsPublisherService(
                outbox, deletions, mock(UserRepository.class), events -> { }, gateway,
                new ObjectMapper(), false, 100);

        assertEquals(0, off.publishPendingDeletions());

        assertTrue(gateway.deleted.isEmpty());
        assertEquals(AnalyticsPersonDeletion.STATUS_PENDING, queued.getStatus());
        verify(deletions, never()).findSendable(anyString(), any(), any(Pageable.class));
    }
}
