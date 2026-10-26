package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.util.AnalyticsBands;
import com.investingapp.backend.util.AnalyticsPrivacyGuard;
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
import java.util.Set;

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

    /**
     * The other direction, and the one that costs data rather than leaking it.
     *
     * The value-shape checks run over EVERY property, declared or not: decision 12's exemption
     * covers a key's NAME and nothing else. So a declared property whose legitimate value happened
     * to be a long run of digits, an e.164 number or a {@code ###-###-####} shape would be
     * stripped in silence, and the first sign of it would be a funnel with a hole in it.
     *
     * This table is every key {@link ServerAnalyticsEvent#PROPERTY_KEYS} and
     * {@link ServerAnalyticsEvent#PERSON_PROPERTY_KEYS} declare, with its COMPLETE legitimate
     * value domain: the whole closed vocabulary for an enum, every band for a band, and the real
     * range for a number. The band domains are read from AnalyticsBands rather than retyped, so a
     * new band is covered the day it is added. The last test refuses to let the table fall behind
     * the taxonomy, so a new property cannot be added without someone stating here what it holds.
     *
     * The client's half of this is in analytics-forbidden-payload.spec.ts.
     */
    private static final Map<String, List<Object>> LEGITIMATE_VALUES = legitimateValues();

    private static Map<String, List<Object>> legitimateValues() {
        List<Object> booleans = List.of(true, false);
        List<Object> tiers = List.of("core", "plus", "pro");
        List<Object> amounts = List.copyOf(AnalyticsBands.AMOUNT_BANDS);
        List<Object> counts = List.copyOf(AnalyticsBands.COUNT_BANDS);
        List<Object> days = List.copyOf(AnalyticsBands.DAY_BANDS);
        List<Object> years = List.copyOf(AnalyticsBands.YEARS_BANDS);

        Map<String, List<Object>> domains = new LinkedHashMap<>();
        domains.put("amount_band", amounts);
        domains.put("attempt", List.of("1", "2", "3_plus"));
        domains.put("days_since_schedule_band", days);
        domains.put("days_since_signup_band", days);
        domains.put("days_since_trial_start_band", days);
        domains.put("days_to_decision_band", days);
        // InvestmentFrequency, lowercased by frequencySlug.
        domains.put("frequency", List.of("weekly", "biweekly", "semi_monthly", "monthly"));
        domains.put("initiator",
                List.of(ServerAnalyticsEvent.INITIATOR_USER, ServerAnalyticsEvent.INITIATOR_SYSTEM));
        domains.put("is_first_billing", booleans);
        domains.put("is_first_link", booleans);
        domains.put("is_first_passkey", booleans);
        domains.put("monthly_income_band", amounts);
        domains.put("monthly_invest_band", amounts);
        // kyc_decided's outcomes, from AccountStatusService.kycOutcome.
        domains.put("outcome", List.of("approved", "rejected", "needs_more_info"));
        domains.put("reason", List.of(
                ServerAnalyticsEvent.REASON_USER_REQUEST,
                ServerAnalyticsEvent.REASON_BANK_CHANGE,
                ServerAnalyticsEvent.REASON_ACH_CONFIRMED,
                ServerAnalyticsEvent.REASON_ACCOUNT_CLOSED,
                ServerAnalyticsEvent.REASON_KYC_REJECTED));
        domains.put("strategy", List.of("yield", "guardrails", "sbloc", "annuity"));
        domains.put("tier", tiers);
        domains.put("transfer_number_band", counts);
        // The only number the server sends, and it is a constant. The absurd upper bound is
        // deliberate: a trial of ten million days would be stripped, and that is worth knowing.
        domains.put("trial_length_days",
                List.of(ServerAnalyticsEvent.TRIAL_LENGTH_DAYS, 1, 365, 9999999));
        domains.put("years_to_freedom", years);
        // The person properties, from AnalyticsOutboxService.
        domains.put("kyc_status", List.of(
                "not_started", "pending", "approved", "rejected", "action_required", "other"));
        domains.put("has_linked_bank", booleans);
        domains.put("has_schedule", booleans);
        domains.put("has_funded", booleans);
        return Map.copyOf(domains);
    }

    @Test
    @DisplayName("every legitimate value of every declared property survives the guard")
    void legitimateValuesSurvive() {
        LEGITIMATE_VALUES.forEach((key, values) -> {
            for (Object value : values) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put(key, value);
                Map<String, Object> scrubbed =
                        AnalyticsPrivacyGuard.scrub("kyc_decided", "properties", properties);
                assertEquals(properties, scrubbed,
                        key + "=" + value + " is a legitimate declared value and was stripped");
            }
        });
    }

    @Test
    @DisplayName("the table covers every declared property, so a new one cannot skip it")
    void theTableCoversEveryDeclaredKey() {
        Set<String> declared = new java.util.TreeSet<>(ServerAnalyticsEvent.PROPERTY_KEYS);
        declared.addAll(ServerAnalyticsEvent.PERSON_PROPERTY_KEYS);
        assertEquals(declared, new java.util.TreeSet<>(LEGITIMATE_VALUES.keySet()),
                "a property was added to ServerAnalyticsEvent without stating what it can hold; "
                        + "state its whole legitimate domain in LEGITIMATE_VALUES");
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
