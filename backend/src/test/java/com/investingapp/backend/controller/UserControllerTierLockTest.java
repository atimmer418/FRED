package com.investingapp.backend.controller;

import com.investingapp.backend.config.SelfServiceTierPolicy;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A user used to be able to give themselves any paid tier by sending it to PUT /user/profile.
 * Development and test still rely on that (there is no billing yet). Production must refuse it.
 *
 * The user here starts on "core". Every production-side test asserts the tier is still "core"
 * afterwards and that nothing was saved.
 */
class UserControllerTierLockTest {

    private static final String EMAIL = "user@fredvested.test";

    private UserController controller;
    private UserRepository userRepository;
    private SubscriptionService subscriptionService;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new UserController();
        userRepository = mock(UserRepository.class);
        subscriptionService = mock(SubscriptionService.class);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "subscriptionService", subscriptionService);

        user = new User(EMAIL);
        user.setId(7L);
        user.setSelectedTier("core");
        user.setBillingPeriod("monthly");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    private void runningWithProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        ReflectionTestUtils.setField(controller, "selfServiceTierPolicy", new SelfServiceTierPolicy(environment));
    }

    private Authentication authentication() {
        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User(EMAIL, "", List.of());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @SuppressWarnings("unchecked")
    private void assertRefusedAndUnchanged(ResponseEntity<?> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("code", "TIER_CHANGE_NOT_ALLOWED");
        assertThat(user.getSelectedTier()).isEqualTo("core");
        assertThat(user.getBillingPeriod()).isEqualTo("monthly");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(subscriptionService);
    }

    // ── Production: locked ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("prod refuses a self-granted paid tier")
    void prodRefusesSelfGrantedTier() {
        runningWithProfiles("prod");

        assertRefusedAndUnchanged(controller.updateUserProfile(Map.of("selectedTier", "pro"), authentication()));
    }

    @Test
    @DisplayName("prod refuses clearing the tier and changing the billing period too")
    void prodRefusesEveryTierField() {
        runningWithProfiles("prod");
        Map<String, Object> clear = new HashMap<>();
        clear.put("selectedTier", null);

        assertRefusedAndUnchanged(controller.updateUserProfile(clear, authentication()));
        assertRefusedAndUnchanged(controller.updateUserProfile(Map.of("billingPeriod", "yearly"), authentication()));
    }

    @Test
    @DisplayName("prod applies nothing from a request that carries a tier, so no half-saved update")
    void prodRefusalIsAllOrNothing() {
        runningWithProfiles("prod");
        user.setMonthlyInvestment(100.0);

        assertRefusedAndUnchanged(controller.updateUserProfile(
                Map.of("selectedTier", "pro", "monthlyInvestment", 999), authentication()));
        assertThat(user.getMonthlyInvestment()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("prod still saves the ordinary profile fields")
    void prodStillSavesOrdinaryFields() {
        runningWithProfiles("prod");

        ResponseEntity<?> response =
                controller.updateUserProfile(Map.of("monthlyInvestment", 250), authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(user.getMonthlyInvestment()).isEqualTo(250.0);
        assertThat(user.getSelectedTier()).isEqualTo("core");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("prod refuses the unverified subscription confirm, the other way to self-grant")
    void prodRefusesUnverifiedConfirm() {
        runningWithProfiles("prod");

        assertRefusedAndUnchanged(controller.confirmSubscription(
                authentication(), Map.of("tier", "pro", "billingPeriod", "monthly")));
    }

    // ── Fail safe: anything that is not clearly development is treated as production ───────────

    @Test
    @DisplayName("no active profile at all is treated as prod")
    void noProfileIsLocked() {
        runningWithProfiles();

        assertRefusedAndUnchanged(controller.updateUserProfile(Map.of("selectedTier", "pro"), authentication()));
    }

    @Test
    @DisplayName("an unknown profile is treated as prod")
    void unknownProfileIsLocked() {
        runningWithProfiles("staging");

        assertRefusedAndUnchanged(controller.updateUserProfile(Map.of("selectedTier", "pro"), authentication()));
    }

    @Test
    @DisplayName("prod wins when it is active alongside a dev profile")
    void prodPlusDevIsLocked() {
        runningWithProfiles("dev", "prod");

        assertRefusedAndUnchanged(controller.updateUserProfile(Map.of("selectedTier", "pro"), authentication()));
    }

    @Test
    @DisplayName("an environment that cannot be read is treated as prod")
    void unreadableEnvironmentIsLocked() {
        Environment broken = mock(Environment.class);
        when(broken.getActiveProfiles()).thenThrow(new IllegalStateException("no environment"));
        ReflectionTestUtils.setField(controller, "selfServiceTierPolicy", new SelfServiceTierPolicy(broken));

        assertRefusedAndUnchanged(controller.updateUserProfile(Map.of("selectedTier", "pro"), authentication()));
    }

    @Test
    @DisplayName("a missing policy or a missing environment is treated as prod")
    void missingPolicyIsLocked() {
        // The policy field is left unset: the controller must not fall open.
        assertRefusedAndUnchanged(controller.updateUserProfile(Map.of("selectedTier", "pro"), authentication()));

        ReflectionTestUtils.setField(controller, "selfServiceTierPolicy", new SelfServiceTierPolicy(null));
        assertRefusedAndUnchanged(controller.updateUserProfile(Map.of("selectedTier", "pro"), authentication()));
    }

    // ── Development and test: unchanged ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "the {0} profile still lets a user change their own tier")
    @ValueSource(strings = {"local", "dev", "test", "ci"})
    void devProfilesStillAllowSelfService(String profile) {
        runningWithProfiles(profile);

        ResponseEntity<?> response = controller.updateUserProfile(
                Map.of("selectedTier", "pro", "billingPeriod", "yearly"), authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(user.getSelectedTier()).isEqualTo("pro");
        assertThat(user.getBillingPeriod()).isEqualTo("yearly");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("dev still lets a user clear their tier")
    void devStillAllowsClearing() {
        runningWithProfiles("dev");
        Map<String, Object> clear = new HashMap<>();
        clear.put("selectedTier", null);

        ResponseEntity<?> response = controller.updateUserProfile(clear, authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(user.getSelectedTier()).isNull();
    }

    @Test
    @DisplayName("dev still accepts the subscription confirm")
    void devStillAllowsConfirm() {
        runningWithProfiles("dev");

        ResponseEntity<?> response = controller.confirmSubscription(
                authentication(), Map.of("tier", "plus", "billingPeriod", "monthly"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(subscriptionService).confirmSubscription(EMAIL, "plus", "monthly");
    }
}
