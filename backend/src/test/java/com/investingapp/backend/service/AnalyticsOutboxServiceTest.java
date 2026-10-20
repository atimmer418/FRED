package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The writer's job is to be atomic or absent. These tests pin the refusals, because each one is a
 * case where writing the row anyway would put something untrue into the ledger that Checkpoint G
 * reads as the source of truth for money.
 */
class AnalyticsOutboxServiceTest {

    private AnalyticsOutboxRepository outbox;
    private InvestmentScheduleRepository schedules;
    private InvestmentExecutionRepository executions;
    private AnalyticsOutboxService service;

    @BeforeEach
    void setUp() {
        outbox = mock(AnalyticsOutboxRepository.class);
        schedules = mock(InvestmentScheduleRepository.class);
        executions = mock(InvestmentExecutionRepository.class);
        service = new AnalyticsOutboxService(outbox, schedules, executions, new ObjectMapper());
        when(outbox.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        // Never leave a faked transaction behind for the next test.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /** Stands in for a caller that is @Transactional. */
    private void insideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private User user() {
        User u = new User();
        u.setId(42L);
        u.setPublicId("11111111-2222-4333-8444-555555555555");
        u.setSelectedTier("plus");
        u.setAccountStatus("ACTIVE");
        return u;
    }

    @Test
    @DisplayName("writes a row inside a transaction, with a uuid and the opaque distinct id")
    void writesInsideATransaction() {
        insideTransaction();

        AnalyticsOutboxEntry entry = service.record(
                ServerAnalyticsEvent.KYC_SUBMITTED, user(), Map.of("attempt", "1"));

        assertNotNull(entry);
        assertEquals(ServerAnalyticsEvent.KYC_SUBMITTED, entry.getEventName());
        assertEquals("11111111-2222-4333-8444-555555555555", entry.getPublicId());
        assertEquals(42L, entry.getUserId());
        assertNotNull(entry.getEventId(), "every row needs its own uuid for idempotent resend");
        assertEquals(AnalyticsOutboxEntry.STATUS_PENDING, entry.getStatus());
        assertTrue(entry.getProperties().contains("\"attempt\":\"1\""));
        verify(outbox).save(any());
    }

    @Test
    @DisplayName("refuses to write outside a transaction, because such a row could outlive a rollback")
    void refusesOutsideATransaction() {
        AnalyticsOutboxEntry entry = service.record(
                ServerAnalyticsEvent.PLAID_LINKED, user(), Map.of("is_first_link", true));

        assertNull(entry);
        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("refuses an event name that is not in the taxonomy")
    void refusesUnknownEvent() {
        insideTransaction();

        assertNull(service.record("something_invented", user(), Map.of()));
        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("never throws into the caller, whatever goes wrong")
    void neverThrows() {
        insideTransaction();
        when(outbox.save(any())).thenThrow(new RuntimeException("database on fire"));

        assertDoesNotThrow(() -> service.record(ServerAnalyticsEvent.PASSKEY_CREATED, user(),
                Map.of("is_first_passkey", true)));
        assertNull(service.record(ServerAnalyticsEvent.PASSKEY_CREATED, user(),
                Map.of("is_first_passkey", true)));
    }

    @Test
    @DisplayName("person properties are booleans and enums, and carry the consent snapshot")
    void personProperties() {
        insideTransaction();
        User u = user();
        u.setPlaidAccessToken("ENC:whatever");
        u.setAnalyticsOptOut(true);
        when(schedules.existsByUser(u)).thenReturn(true);
        when(executions.existsByUserAndFundingCompletedAtIsNotNull(u)).thenReturn(false);

        AnalyticsOutboxEntry entry = service.record(ServerAnalyticsEvent.KYC_DECIDED, u,
                Map.of("outcome", "approved"));

        assertNotNull(entry);
        String set = entry.getPersonProperties();
        assertTrue(set.contains("\"tier\":\"plus\""));
        assertTrue(set.contains("\"kyc_status\":\"approved\""));
        assertTrue(set.contains("\"has_linked_bank\":true"));
        assertTrue(set.contains("\"has_schedule\":true"));
        assertTrue(set.contains("\"has_funded\":false"));
        assertFalse(set.contains("ENC:"), "never the token itself");
        assertTrue(entry.isConsentOptOutAtWrite(), "consent is snapshotted for the deleted-user case");
    }

    @Test
    @DisplayName("the tier is narrowed to the three real values, because the column is unvalidated")
    void tierNarrowing() {
        User u = new User();
        u.setSelectedTier("core");
        assertEquals("core", AnalyticsOutboxService.tier(u));
        u.setSelectedTier("PLUS");
        assertEquals("plus", AnalyticsOutboxService.tier(u));
        u.setSelectedTier("piggy");
        assertEquals("core", AnalyticsOutboxService.tier(u), "legacy name for the same tier");
        u.setSelectedTier("");
        assertNull(AnalyticsOutboxService.tier(u));
        u.setSelectedTier("whatever-a-client-sent");
        assertNull(AnalyticsOutboxService.tier(u), "an unknown tier is null, not a new dimension");
        u.setSelectedTier(null);
        assertNull(AnalyticsOutboxService.tier(u));
    }

    @Test
    @DisplayName("broker statuses collapse to the handful of states the funnel asks about")
    void kycStatusMapping() {
        User u = new User();
        assertEquals("not_started", AnalyticsOutboxService.kycStatus(u));
        u.setAccountStatus("SUBMITTED");
        assertEquals("pending", AnalyticsOutboxService.kycStatus(u));
        u.setAccountStatus("APPROVAL_PENDING");
        assertEquals("pending", AnalyticsOutboxService.kycStatus(u));
        u.setAccountStatus("ACTIVE");
        assertEquals("approved", AnalyticsOutboxService.kycStatus(u));
        u.setAccountStatus("REJECTED");
        assertEquals("rejected", AnalyticsOutboxService.kycStatus(u));
        u.setAccountStatus("ACTION_REQUIRED");
        assertEquals("action_required", AnalyticsOutboxService.kycStatus(u));
        u.setAccountStatus("SOMETHING_NEW_FROM_THE_BROKER");
        assertEquals("other", AnalyticsOutboxService.kycStatus(u));
    }

    @Test
    @DisplayName("property order is stable, so two identical events serialise identically")
    void stablePropertyOrder() {
        insideTransaction();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("frequency", "monthly");
        props.put("amount_band", "250_499");
        props.put("days_since_signup_band", "1_3");

        AnalyticsOutboxEntry entry = service.record(
                ServerAnalyticsEvent.INVESTMENT_SCHEDULE_CREATED, user(), props);

        assertEquals("{\"frequency\":\"monthly\",\"amount_band\":\"250_499\","
                + "\"days_since_signup_band\":\"1_3\"}", entry.getProperties());
    }
}
