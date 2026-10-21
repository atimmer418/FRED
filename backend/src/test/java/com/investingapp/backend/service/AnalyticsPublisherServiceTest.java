package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The publisher is the part that can lose or duplicate real money events, so its behaviour is
 * pinned here rather than left to a live run: a retry that double counts, or a suppressed user
 * whose event escapes, would both be invisible until the numbers were already wrong.
 */
class AnalyticsPublisherServiceTest {

    private AnalyticsOutboxRepository outbox;
    private com.investingapp.backend.repository.AnalyticsPersonDeletionRepository deletions;
    private AnalyticsPersonGateway personGateway;
    private UserRepository users;
    private RecordingTransport transport;
    private AnalyticsPublisherService publisher;

    /** Records what was handed to the vendor, and can be told to fail. */
    private static class RecordingTransport implements AnalyticsTransport {
        final List<List<Map<String, Object>>> batches = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void send(List<Map<String, Object>> events) {
            batches.add(events);
            if (failWith != null) {
                throw failWith;
            }
        }
    }

    @BeforeEach
    void setUp() {
        outbox = mock(AnalyticsOutboxRepository.class);
        // Person deletions have their own test; here they are simply never queued, so the event
        // assertions cannot be disturbed by them.
        deletions = mock(com.investingapp.backend.repository.AnalyticsPersonDeletionRepository.class);
        personGateway = mock(AnalyticsPersonGateway.class);
        users = mock(UserRepository.class);
        transport = new RecordingTransport();
        publisher = new AnalyticsPublisherService(outbox, deletions, users, transport,
                personGateway, new ObjectMapper(), true, 100);
        when(outbox.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AnalyticsOutboxEntry entry(long id, Long userId, String publicId, String eventName) {
        AnalyticsOutboxEntry e = new AnalyticsOutboxEntry();
        e.setId(id);
        e.setEventId("uuid-" + id);
        e.setEventName(eventName);
        e.setUserId(userId);
        e.setPublicId(publicId);
        e.setProperties("{\"attempt\":\"1\"}");
        e.setPersonProperties("{\"tier\":\"core\",\"has_funded\":false}");
        e.setOccurredAt(LocalDateTime.of(2026, 10, 19, 12, 0));
        e.setStatus(AnalyticsOutboxEntry.STATUS_PENDING);
        return e;
    }

    private User user(long id, boolean optedOut) {
        User u = new User();
        u.setId(id);
        u.setAnalyticsOptOut(optedOut);
        return u;
    }

    private void pending(AnalyticsOutboxEntry... entries) {
        when(outbox.findPublishable(eq(AnalyticsOutboxEntry.STATUS_PENDING), any(), any(Pageable.class)))
                .thenReturn(List.of(entries));
    }

    @Test
    @DisplayName("sends a pending row and marks it sent, with the distinct id and its own uuid")
    void sendsAndMarks() {
        AnalyticsOutboxEntry e = entry(1, 7L, "11111111-2222-4333-8444-555555555555", "kyc_submitted");
        pending(e);
        when(users.findAllById(any())).thenReturn(List.of(user(7L, false)));

        assertEquals(1, publisher.publishPending());

        assertEquals(1, transport.batches.size());
        Map<String, Object> payload = transport.batches.get(0).get(0);
        assertEquals("kyc_submitted", payload.get("event"));
        assertEquals("11111111-2222-4333-8444-555555555555", payload.get("distinct_id"));
        assertEquals("uuid-1", payload.get("uuid"), "the row's own uuid is what makes a resend safe");

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) payload.get("properties");
        assertEquals("1", props.get("attempt"));
        assertEquals("11111111-2222-4333-8444-555555555555", props.get("distinct_id"));
        assertEquals(Map.of("tier", "core", "has_funded", false), props.get("$set"),
                "person properties ride as $set on every event");

        assertEquals(AnalyticsOutboxEntry.STATUS_SENT, e.getStatus());
        assertNotNull(e.getSentAt());
        assertNull(e.getNextAttemptAt());
    }

    @Test
    @DisplayName("an opted-out user's row is kept, marked suppressed, and never sent")
    void suppressesOptedOut() {
        AnalyticsOutboxEntry e = entry(2, 8L, "22222222-2222-4333-8444-555555555555", "plaid_linked");
        pending(e);
        when(users.findAllById(any())).thenReturn(List.of(user(8L, true)));

        assertEquals(0, publisher.publishPending());

        assertTrue(transport.batches.isEmpty(), "nothing may reach the vendor for an opted-out user");
        assertEquals(AnalyticsOutboxEntry.STATUS_SUPPRESSED, e.getStatus());
        assertNull(e.getSentAt());
    }

    @Test
    @DisplayName("consent is read live, so an opt-out after the event still suppresses it")
    void consentIsReadAtPublishTime() {
        // The row was written while the user was opted IN.
        AnalyticsOutboxEntry e = entry(3, 9L, "33333333-2222-4333-8444-555555555555", "kyc_decided");
        e.setConsentOptOutAtWrite(false);
        pending(e);
        // By the time it comes up for publishing, they have opted out.
        when(users.findAllById(any())).thenReturn(List.of(user(9L, true)));

        publisher.publishPending();

        assertTrue(transport.batches.isEmpty());
        assertEquals(AnalyticsOutboxEntry.STATUS_SUPPRESSED, e.getStatus());
    }

    @Test
    @DisplayName("a deleted user falls back to the consent captured when the row was written")
    void deletedUserUsesTheSnapshot() {
        // This is the KYC rejection path: the user row is deleted in the same transaction that
        // records the decision, so there is nobody left to ask at publish time.
        AnalyticsOutboxEntry optedIn = entry(4, 10L, "44444444-2222-4333-8444-555555555555", "kyc_decided");
        optedIn.setConsentOptOutAtWrite(false);
        AnalyticsOutboxEntry optedOut = entry(5, 11L, "55555555-2222-4333-8444-555555555555", "kyc_decided");
        optedOut.setConsentOptOutAtWrite(true);
        pending(optedIn, optedOut);
        when(users.findAllById(any())).thenReturn(List.of());  // both users are gone

        assertEquals(1, publisher.publishPending());

        assertEquals(AnalyticsOutboxEntry.STATUS_SENT, optedIn.getStatus());
        assertEquals(AnalyticsOutboxEntry.STATUS_SUPPRESSED, optedOut.getStatus());
    }

    @Test
    @DisplayName("a failure backs off instead of hammering, and keeps the row pending")
    void backsOffOnFailure() {
        AnalyticsOutboxEntry e = entry(6, 12L, "66666666-2222-4333-8444-555555555555", "passkey_created");
        pending(e);
        when(users.findAllById(any())).thenReturn(List.of(user(12L, false)));
        transport.failWith = new RuntimeException("ingest unavailable");

        assertEquals(0, publisher.publishPending());

        assertEquals(AnalyticsOutboxEntry.STATUS_PENDING, e.getStatus(), "still ours to retry");
        assertEquals(1, e.getAttempts());
        assertNotNull(e.getNextAttemptAt(), "a failed row must wait before being tried again");
        assertNull(e.getSentAt());
        assertTrue(e.getLastError().contains("ingest unavailable"));
    }

    @Test
    @DisplayName("backoff grows and then caps, so a long outage still recovers promptly")
    void backoffCurve() {
        assertEquals(2, AnalyticsPublisherService.backoffMinutes(1));
        assertEquals(4, AnalyticsPublisherService.backoffMinutes(2));
        assertEquals(8, AnalyticsPublisherService.backoffMinutes(3));
        assertEquals(32, AnalyticsPublisherService.backoffMinutes(5));
        assertEquals(AnalyticsPublisherService.MAX_BACKOFF_MINUTES,
                AnalyticsPublisherService.backoffMinutes(9));
        assertEquals(AnalyticsPublisherService.MAX_BACKOFF_MINUTES,
                AnalyticsPublisherService.backoffMinutes(40), "no overflow at absurd attempt counts");
    }

    @Test
    @DisplayName("a row that keeps failing is parked for a human rather than retried forever")
    void parksAfterMaxAttempts() {
        AnalyticsOutboxEntry e = entry(7, 13L, "77777777-2222-4333-8444-555555555555", "plaid_linked");
        e.setAttempts(AnalyticsPublisherService.MAX_ATTEMPTS - 1);
        pending(e);
        when(users.findAllById(any())).thenReturn(List.of(user(13L, false)));
        transport.failWith = new RuntimeException("still broken");

        publisher.publishPending();

        assertEquals(AnalyticsOutboxEntry.STATUS_PARKED, e.getStatus());
        assertNull(e.getNextAttemptAt(), "a parked row is not eligible again");
    }

    @Test
    @DisplayName("a resend carries the same uuid, so the vendor de-duplicates it")
    void resendIsIdempotent() {
        AnalyticsOutboxEntry e = entry(8, 14L, "88888888-2222-4333-8444-555555555555", "kyc_submitted");
        pending(e);
        when(users.findAllById(any())).thenReturn(List.of(user(14L, false)));
        transport.failWith = new RuntimeException("timeout, but it may well have landed");

        publisher.publishPending();
        String firstUuid = transport.batches.get(0).get(0).get("uuid").toString();

        // The outage clears and the same row comes round again.
        transport.failWith = null;
        e.setStatus(AnalyticsOutboxEntry.STATUS_PENDING);
        e.setNextAttemptAt(null);
        publisher.publishPending();

        assertEquals(2, transport.batches.size());
        assertEquals(firstUuid, transport.batches.get(1).get(0).get("uuid"),
                "the uuid must survive a retry or the event is counted twice");
        assertEquals(AnalyticsOutboxEntry.STATUS_SENT, e.getStatus());
    }

    @Test
    @DisplayName("a row with no distinct id is parked, never sent under a null")
    void parksRowsWithNoPublicId() {
        AnalyticsOutboxEntry e = entry(9, 15L, null, "freedom_plan_created");
        pending(e);

        assertEquals(0, publisher.publishPending());

        assertTrue(transport.batches.isEmpty());
        assertEquals(AnalyticsOutboxEntry.STATUS_PARKED, e.getStatus());
        assertTrue(e.getLastError().contains("public_id"));
    }

    @Test
    @DisplayName("disabled means nothing leaves, and nothing is marked")
    void disabledPublishesNothing() {
        AnalyticsPublisherService off = new AnalyticsPublisherService(outbox, deletions, users,
                transport, personGateway, new ObjectMapper(), false, 100);

        assertEquals(0, off.publishPending());

        assertTrue(transport.batches.isEmpty());
        verify(outbox, never()).findPublishable(anyString(), any(), any(Pageable.class));
    }
}
