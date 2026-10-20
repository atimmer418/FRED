package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.dto.RegistrationFinishResponse;
import com.investingapp.backend.model.PasskeyCredential;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PasskeyCredentialRepository;
import com.investingapp.backend.repository.UserProgressRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.security.services.UserDetailsServiceImpl;
import com.investingapp.backend.util.AnalyticsBands;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The two registration events, pinned at the service that emits them.
 *
 * <p>Both call sites sit inside a method that was already {@code @Transactional}, so what matters
 * here is the payload and the silence: exactly one event per fact, with exactly the properties the
 * taxonomy declares, in taxonomy order, every one of them banded, and nothing at all on the paths
 * where no fact was persisted. The whole map is asserted rather than a subset, because a property
 * that quietly appears is a privacy problem and one that quietly disappears is a broken funnel.
 *
 * <p>Atomicity is not testable here: a Mockito test has no transaction and the outbox is a mock.
 * That is {@link RegistrationAnalyticsOutboxRollbackTest}'s job.
 *
 * <p>One deliberate gap. The happy path of finishRegistrationFlow cannot be driven in a unit test:
 * it calls the static {@code PublicKeyCredential.parseRegistrationResponseJson} and then the real
 * Yubico verifier, so reaching the credential save needs a genuine attestation from a platform
 * authenticator. So the success path is driven through {@link WebAuthnService#recordPasskeyCreated}
 * and {@link WebAuthnService#countPasskeysBeforeSave}, the two methods the call site is made of,
 * and the call site itself is covered on its failure path, where it must stay silent.
 */
class WebAuthnServiceAnalyticsTest {

    /** The literal wire names. Spelled out so a rename of the constants cannot pass unnoticed. */
    private static final String FREEDOM_PLAN_CREATED = "freedom_plan_created";
    private static final String PASSKEY_CREATED = "passkey_created";

    private static final String EMAIL = "registration-analytics@example.invalid";
    private static final long USER_ID = 42L;
    private static final String PUBLIC_ID = "11111111-2222-3333-4444-555555555555";

    private UserRepository userRepository;
    private PasskeyCredentialRepository passkeyCredentialRepository;
    private AnalyticsOutboxService analyticsOutboxService;
    private WebAuthnService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passkeyCredentialRepository = mock(PasskeyCredentialRepository.class);
        analyticsOutboxService = mock(AnalyticsOutboxService.class);

        // A real RelyingParty rather than a mock: it is a final class, and startRegistration only
        // needs an identity and a credential repository to generate options offline.
        RelyingParty relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id("localhost").name("FRED (tests)").build())
                .credentialRepository(mock(CredentialRepository.class))
                .build();

        UserService userService = mock(UserService.class);
        service = new WebAuthnService(
                relyingParty,
                userRepository,
                passkeyCredentialRepository,
                mock(UserProgressRepository.class),
                mock(JwtUtils.class),
                mock(UserDetailsServiceImpl.class),
                mock(PlaidService.class),
                mock(EncryptionService.class),
                userService,
                analyticsOutboxService);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(userService.generateUniqueReferralCode(EMAIL)).thenReturn("FRED1234");
        // The real save assigns the id, and @PrePersist assigns the public_id that becomes the
        // distinct id on the event.
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(USER_ID);
            saved.setPublicId(PUBLIC_ID);
            return saved;
        });
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> propsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    // --- freedom_plan_created ------------------------------------------------------------------

    @Test
    @DisplayName("freedom_plan_created: exactly one event, with exactly the banded property map")
    void freedomPlanCreatedCarriesTheExactPropertyMap() {
        // 90,000 a year is 7,500 a month; 22.8 years; 750 a month invested.
        service.startRegistrationFlow(EMAIL, "guardrails", "22.8", 2_250_000.0, 90_000.0, 750.0);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<Map<String, Object>> props = propsCaptor();
        verify(analyticsOutboxService, times(1))
                .record(eq(FREEDOM_PLAN_CREATED), userCaptor.capture(), props.capture());
        verifyNoMoreInteractions(analyticsOutboxService);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("strategy", "guardrails");
        expected.put("monthly_income_band", "5k_9999");
        expected.put("monthly_invest_band", "500_999");
        expected.put("years_to_freedom", "over_20");

        assertEquals(expected, props.getValue(), "the whole property map, not a subset");
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(props.getValue().keySet()),
                "properties are sent in taxonomy order");

        // The event describes the SAVED row, so it carries an id and a public_id.
        assertEquals(USER_ID, userCaptor.getValue().getId());
        assertEquals(PUBLIC_ID, userCaptor.getValue().getPublicId());
    }

    /**
     * Also the guard that makes the event fire once per user: the duplicate-email check throws
     * before the insert, so a second attempt on the same email never reaches the emit.
     */
    @Test
    @DisplayName("freedom_plan_created: a taken email writes nothing, so it emits nothing")
    void freedomPlanCreatedIsNotEmittedWhenTheEmailIsTaken() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(new User(EMAIL)));

        assertThrows(IllegalArgumentException.class, () -> service.startRegistrationFlow(
                EMAIL, "guardrails", "22.8", 2_250_000.0, 90_000.0, 750.0));

        verify(userRepository, never()).save(any(User.class));
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("freedom_plan_created: an unrecognised plan is sent as null, never as free text")
    void unknownStrategyIsSentAsNullNotAsFreeText() {
        // 36,000 a year is 3,000 a month; the glyph means freedom is unreachable as planned.
        service.startRegistrationFlow(EMAIL, "optimal_drawdown", "∞", 900_000.0, 36_000.0, 125.0);

        ArgumentCaptor<Map<String, Object>> props = propsCaptor();
        verify(analyticsOutboxService, times(1))
                .record(eq(FREEDOM_PLAN_CREATED), any(User.class), props.capture());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("strategy", null);
        expected.put("monthly_income_band", "2500_4999");
        expected.put("monthly_invest_band", "100_249");
        expected.put("years_to_freedom", "never");

        assertEquals(expected, props.getValue());
        assertEquals(List.of("strategy", "monthly_income_band", "monthly_invest_band",
                "years_to_freedom"), new ArrayList<>(props.getValue().keySet()));
    }

    @Test
    @DisplayName("the strategy is a closed vocabulary; plan_id is unvalidated client text")
    void strategySlugIsAClosedVocabulary() {
        assertEquals("yield", WebAuthnService.strategySlug("yield"));
        assertEquals("guardrails", WebAuthnService.strategySlug("guardrails"));
        assertEquals("sbloc", WebAuthnService.strategySlug("sbloc"));
        assertEquals("annuity", WebAuthnService.strategySlug("annuity"));
        assertEquals("yield", WebAuthnService.strategySlug("  YIELD  "));

        assertNull(WebAuthnService.strategySlug(null));
        assertNull(WebAuthnService.strategySlug(""));
        assertNull(WebAuthnService.strategySlug("optimal_drawdown"));
        assertNull(WebAuthnService.strategySlug("whatever the client felt like sending"));
    }

    /**
     * The unit mismatch this event exists to get right: the column is annual, the client's own
     * freedom_plan_changed bands the monthly figure, and banding the annual number would file the
     * same user under a different bucket on each side of the funnel.
     */
    @Test
    @DisplayName("the annual column is banded as monthly, to match the client's event")
    void annualIncomeIsBandedAsMonthlyToMatchTheClient() {
        assertEquals(7_500.0, WebAuthnService.monthlyFromAnnual(90_000.0));
        assertNull(WebAuthnService.monthlyFromAnnual(null));

        assertEquals("5k_9999", AnalyticsBands.amount(WebAuthnService.monthlyFromAnnual(90_000.0)));
        assertNotEquals(AnalyticsBands.amount(90_000.0),
                AnalyticsBands.amount(WebAuthnService.monthlyFromAnnual(90_000.0)),
                "banding the annual figure would be a different band, and unreadable against the client");
    }

    @Test
    @DisplayName("time_tofi is display text: read the number, and report anything else as 'never'")
    void yearsToFreedomIsParsedLeniently() {
        assertEquals(22.8, WebAuthnService.yearsToFreedom("22.8"));
        assertEquals(7.0, WebAuthnService.yearsToFreedom("7"));
        assertEquals(22.8, WebAuthnService.yearsToFreedom("22.8 years"));
        assertEquals(1234.5, WebAuthnService.yearsToFreedom("1,234.5"));

        assertNull(WebAuthnService.yearsToFreedom(null));
        assertNull(WebAuthnService.yearsToFreedom(""));
        assertNull(WebAuthnService.yearsToFreedom("∞"));
        assertNull(WebAuthnService.yearsToFreedom("never"));

        // Unparseable is 'never', which is the fact, not a missing value.
        assertEquals("never", AnalyticsBands.years(WebAuthnService.yearsToFreedom("∞")));
        assertEquals("over_20", AnalyticsBands.years(WebAuthnService.yearsToFreedom("22.8")));
    }

    // --- passkey_created ----------------------------------------------------------------------

    @Test
    @DisplayName("passkey_created: no credentials before the save means is_first_passkey is true")
    void firstPasskeyEmitsIsFirstTrue() {
        User user = savedUser();

        service.recordPasskeyCreated(user, 0L);

        ArgumentCaptor<Map<String, Object>> props = propsCaptor();
        verify(analyticsOutboxService, times(1))
                .record(eq(PASSKEY_CREATED), eq(user), props.capture());
        verifyNoMoreInteractions(analyticsOutboxService);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("is_first_passkey", true);
        assertEquals(expected, props.getValue(), "the whole property map, not a subset");
    }

    @Test
    @DisplayName("passkey_created: a second device is still an event, with is_first_passkey false")
    void secondPasskeyEmitsIsFirstFalse() {
        User user = savedUser();

        service.recordPasskeyCreated(user, 1L);

        ArgumentCaptor<Map<String, Object>> props = propsCaptor();
        verify(analyticsOutboxService, times(1))
                .record(eq(PASSKEY_CREATED), eq(user), props.capture());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("is_first_passkey", false);
        assertEquals(expected, props.getValue());
    }

    @Test
    @DisplayName("passkey_created: an unanswerable is_first_passkey emits nothing, not a guess")
    void aFailedCountEmitsNothing() {
        service.recordPasskeyCreated(savedUser(), null);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("the count comes from the credential repository, and never throws")
    void countPasskeysBeforeSaveReadsTheRepositoryAndSwallowsItsFailures() {
        User user = savedUser();

        when(passkeyCredentialRepository.findAllByUser(user)).thenReturn(List.of());
        assertEquals(0L, service.countPasskeysBeforeSave(user));

        when(passkeyCredentialRepository.findAllByUser(user))
                .thenReturn(List.of(new PasskeyCredential(), new PasskeyCredential()));
        assertEquals(2L, service.countPasskeysBeforeSave(user));

        // A failed count must not be able to fail a registration, so it is swallowed and reported
        // as "cannot say", which recordPasskeyCreated turns into no event.
        when(passkeyCredentialRepository.findAllByUser(user))
                .thenThrow(new RuntimeException("connection reset"));
        assertNull(service.countPasskeysBeforeSave(user));
    }

    /**
     * The real call site, on the path where nothing is persisted. finishRegistrationFlow swallows
     * every exception and answers success=false; it must emit nothing when it does, and the
     * registration's own failure handling must be unchanged by the analytics addition.
     */
    @Test
    @DisplayName("passkey_created: a failed registration finish emits nothing and still fails")
    void aFailedRegistrationFinishEmitsNothing() throws Exception {
        User user = savedUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        RegistrationFinishResponse response = service.finishRegistrationFlow(
                EMAIL, new ObjectMapper().readTree("{}"), null);

        assertFalse(response.isSuccess(), "an unparseable credential is still a failed registration");
        verify(passkeyCredentialRepository, never()).save(any(PasskeyCredential.class));
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("neither event carries anything that identifies a person")
    void noPropertyIsEverPersonallyIdentifying() {
        service.startRegistrationFlow(EMAIL, "sbloc", "9.1", 1_200_000.0, 48_000.0, 300.0);
        service.recordPasskeyCreated(savedUser(), 0L);

        ArgumentCaptor<Map<String, Object>> props = propsCaptor();
        verify(analyticsOutboxService, times(2)).record(any(), any(User.class), props.capture());

        for (Map<String, Object> sent : props.getAllValues()) {
            for (Object value : sent.values()) {
                assertTrue(value == null || value instanceof String || value instanceof Boolean,
                        "only banded strings and booleans go on the wire, never a raw number");
                if (value instanceof String text) {
                    assertFalse(text.contains("@"), "no email ever reaches a property");
                }
            }
        }
    }

    private User savedUser() {
        User user = new User(EMAIL);
        user.setId(USER_ID);
        user.setPublicId(PUBLIC_ID);
        return user;
    }
}
