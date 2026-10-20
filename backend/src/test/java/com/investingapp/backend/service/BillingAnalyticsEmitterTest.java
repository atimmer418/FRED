package com.investingapp.backend.service;

import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.model.User;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The three events billing will emit once billing exists.
 *
 * Nothing calls these yet, on purpose: the advisory fee is collected outside Apple by ACH from the
 * Plaid-linked bank, and a separate session owns that work. These tests are the contract that work
 * will be held to, written against a fake so they can exist before the caller does. They are also
 * what stops the properties drifting in the meantime, since no live traffic would reveal it.
 */
class BillingAnalyticsEmitterTest {

    private AnalyticsOutboxService outbox;
    private BillingAnalyticsEmitter emitter;

    @BeforeEach
    void setUp() {
        outbox = mock(AnalyticsOutboxService.class);
        when(outbox.record(any(), any(), any())).thenReturn(new AnalyticsOutboxEntry());
        emitter = new BillingAnalyticsEmitter(outbox);
    }

    private User user() {
        User u = new User();
        u.setId(7L);
        u.setPublicId("11111111-2222-4333-8444-555555555555");
        u.setSelectedTier("core");
        return u;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureProps(String expectedEvent) {
        ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
        verify(outbox).record(eq(expectedEvent), any(User.class), props.capture());
        return props.getValue();
    }

    @Test
    @DisplayName("trial_started carries the tier and the fixed fourteen days")
    void trialStarted() {
        emitter.trialStarted(user(), "plus");

        assertEquals(Map.of("tier", "plus", "trial_length_days", 14),
                captureProps(ServerAnalyticsEvent.TRIAL_STARTED));
    }

    @Test
    @DisplayName("the trial length is the same on every tier")
    void trialLengthIsUniform() {
        for (String tier : new String[] { "core", "plus", "pro" }) {
            AnalyticsOutboxService fresh = mock(AnalyticsOutboxService.class);
            new BillingAnalyticsEmitter(fresh).trialStarted(user(), tier);

            ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
            verify(fresh).record(eq(ServerAnalyticsEvent.TRIAL_STARTED), any(), props.capture());
            assertEquals(14, props.getValue().get("trial_length_days"));
            assertEquals(tier, props.getValue().get("tier"));
        }
    }

    @Test
    @DisplayName("subscription_billed says who and whether it is the first, never how much")
    void subscriptionBilled() {
        emitter.subscriptionBilled(user(), "pro", true);

        Map<String, Object> props = captureProps(ServerAnalyticsEvent.SUBSCRIPTION_BILLED);
        assertEquals(Map.of("tier", "pro", "is_first_billing", true), props);
        assertFalse(props.toString().matches(".*\\d+\\.\\d{2}.*"), "no amount may appear");
    }

    @Test
    @DisplayName("subscription_cancelled bands the time since the trial began")
    void subscriptionCancelled() {
        emitter.subscriptionCancelled(user(), "plus", LocalDateTime.now().minusDays(9));

        assertEquals(Map.of("tier", "plus", "days_since_trial_start_band", "8_14"),
                captureProps(ServerAnalyticsEvent.SUBSCRIPTION_CANCELLED));
    }

    @Test
    @DisplayName("an unknown trial start is reported as unknown, not guessed at")
    void unknownTrialStart() {
        emitter.subscriptionCancelled(user(), "core", null);

        assertEquals("unknown",
                captureProps(ServerAnalyticsEvent.SUBSCRIPTION_CANCELLED)
                        .get("days_since_trial_start_band"));
    }

    @Test
    @DisplayName("a tier the client invented becomes null rather than a new dimension value")
    void unknownTierIsNull() {
        emitter.trialStarted(user(), "enterprise-ultra");

        Map<String, Object> props = captureProps(ServerAnalyticsEvent.TRIAL_STARTED);
        assertTrue(props.containsKey("tier"));
        assertNull(props.get("tier"));
    }

    @Test
    @DisplayName("the tier billed wins over the tier on the profile, since they can differ")
    void billedTierOverridesProfile() {
        User onCore = user();                      // profile says core
        emitter.subscriptionBilled(onCore, "pro", false);   // billing says pro

        assertEquals("pro", captureProps(ServerAnalyticsEvent.SUBSCRIPTION_BILLED).get("tier"));
        assertEquals("core", onCore.getSelectedTier(), "the user must not be mutated by analytics");
    }
}
