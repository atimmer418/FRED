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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 7's test, server side: record every event with a payload that must never leave, through
 * the real writer, and prove nothing identifying reaches the row that the publisher sends.
 *
 * The outbox row IS the dispatch path here. The publisher reads these two JSON columns and posts
 * them verbatim, so a forbidden property that survives into the row is a forbidden property on its
 * way to the vendor; there is nothing between this row and PostHog that could still save us.
 *
 * AnalyticsPrivacyGuardTest pins which shapes are forbidden. This file pins that the guard is
 * installed in front of the writer, for every event, on both payloads.
 */
class AnalyticsOutboxForbiddenPayloadTest {

    private AnalyticsOutboxRepository outbox;
    private AnalyticsOutboxService service;

    /** Every kind of thing the rules forbid, plus one legitimate property that must survive. */
    private static Map<String, Object> forbiddenPayload() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("email", "someone@example.com");
        properties.put("user_email", "someone@example.com");
        properties.put("phone_number", "(555) 123-4567");
        properties.put("ssn", "123-45-6789");
        properties.put("date_of_birth", "1990-04-12");
        properties.put("first_name", "Andrew");
        properties.put("institution_name", "Chase");
        properties.put("street_address", "1 Main St");
        properties.put("account_number", "000123456789");
        properties.put("plaid_access_token", "access-sandbox-0123456789abcdef");
        properties.put("alpaca_account_id", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0");
        properties.put("contribution_amount", 2400);
        properties.put("ip_address", "203.0.113.7");
        properties.put("rejection_reason_text", "the SSN did not match");
        // Innocently named, forbidden by value. These are the ones a key deny-list alone misses.
        properties.put("detail", "someone@example.com");
        properties.put("ref", "123-45-6789");
        properties.put("code", "987654321");
        // The one property that must survive, so a passing test cannot be an empty payload.
        properties.put("reason", ServerAnalyticsEvent.REASON_USER_REQUEST);
        return properties;
    }

    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "someone@example.com", "(555) 123-4567", "123-45-6789", "1990-04-12", "Andrew",
            "Chase", "1 Main St", "000123456789", "access-sandbox", "eyJhbGciOiJIUzI1NiJ9",
            "2400", "987654321", "the SSN did not match");

    static List<String> everyServerEvent() {
        return ServerAnalyticsEvent.ALL;
    }

    @BeforeEach
    void setUp() {
        outbox = mock(AnalyticsOutboxRepository.class);
        service = new AnalyticsOutboxService(outbox, mock(InvestmentScheduleRepository.class),
                mock(InvestmentExecutionRepository.class), new ObjectMapper());
        when(outbox.save(any())).thenAnswer(i -> i.getArgument(0));
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private User user() {
        User u = new User();
        u.setId(42L);
        u.setPublicId("11111111-2222-4333-8444-555555555555");
        u.setSelectedTier("plus");
        u.setAccountStatus("ACTIVE");
        // Real identifying data on the user, because the person payload is built from this object
        // and a mistake in personProperties() would read it straight out of here.
        u.setEmail("andrew@example.com");
        u.setFirstName("Andrew");
        u.setLastName("Timmer");
        return u;
    }

    @ParameterizedTest(name = "{0} carries nothing forbidden into the outbox row")
    @MethodSource("everyServerEvent")
    void everyEventIsScrubbed(String eventName) {
        AnalyticsOutboxEntry entry = service.record(eventName, user(), forbiddenPayload());

        assertNotNull(entry, "the event itself must still be recorded: it is the money ledger");
        String properties = entry.getProperties();
        String personProperties = entry.getPersonProperties();

        for (String key : forbiddenPayload().keySet()) {
            if (key.equals("reason")) {
                continue;
            }
            assertFalse(properties.contains("\"" + key + "\""),
                    eventName + " still carries the key " + key + ": " + properties);
        }
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            assertFalse(properties.contains(fragment),
                    eventName + " still carries " + fragment);
            assertFalse(personProperties.contains(fragment),
                    eventName + "'s person properties still carry " + fragment);
        }
        // A funnel missing one dimension is still a funnel. Dropping the event would hide the
        // mistake instead of reporting it, so the legitimate property has to survive.
        assertTrue(properties.contains("\"reason\":\"user_request\""),
                eventName + " lost its legitimate property: " + properties);
    }

    @Test
    @DisplayName("the person payload is guarded too, and still carries all five real properties")
    void personPropertiesAreGuardedAndIntact() {
        AnalyticsOutboxEntry entry = service.record(
                ServerAnalyticsEvent.KYC_SUBMITTED, user(), Map.of("attempt", "1"));

        String person = entry.getPersonProperties();
        for (String key : ServerAnalyticsEvent.PERSON_PROPERTY_KEYS) {
            assertTrue(person.contains("\"" + key + "\""),
                    "the guard must not strip the real person property " + key + ": " + person);
        }
        assertFalse(person.contains("andrew@example.com"));
        assertFalse(person.contains("Timmer"));
    }

    @Test
    @DisplayName("a declared key keeps its banded value, which is the whole point of the exemption")
    void declaredKeysSurvive() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("amount_band", "1k_2499");
        properties.put("days_since_schedule_band", "4_7");

        AnalyticsOutboxEntry entry = service.record(
                ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED, user(), properties);

        assertTrue(entry.getProperties().contains("\"amount_band\":\"1k_2499\""));
        assertTrue(entry.getProperties().contains("\"days_since_schedule_band\":\"4_7\""));
    }

    @Test
    @DisplayName("a raw amount under a declared-looking name is still refused")
    void aRawAmountIsStillRefused() {
        // `amount` is not `amount_band`. This is the mistake the guard exists for: the author
        // reached for the obvious name and sent the real number.
        AnalyticsOutboxEntry entry = service.record(
                ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED, user(),
                Map.of("amount", "2400.00"));

        assertEquals("{}", entry.getProperties(),
                "a raw amount must not reach the row under any name");
    }
}
