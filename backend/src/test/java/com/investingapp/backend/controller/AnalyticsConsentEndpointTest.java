package com.investingapp.backend.controller;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server owns analytics consent, so this endpoint is the only way it changes. It has to reject
 * anything it cannot trust rather than guess: an unauthenticated caller, or a body that does not
 * carry an explicit boolean.
 */
class AnalyticsConsentEndpointTest {

    private static final String EMAIL = "user@fredvested.com";

    private UserController controller;
    private UserRepository userRepository;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new UserController();
        userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);

        user = new User(EMAIL);
        user.setId(7L);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    private Authentication authentication() {
        org.springframework.security.core.userdetails.User principal =
                new org.springframework.security.core.userdetails.User(EMAIL, "", List.of());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @Test
    @DisplayName("a new user starts opted out")
    void defaultsToOptedOut() {
        assertThat(user.getAnalyticsOptOut()).isTrue();
    }

    @Test
    @DisplayName("opting in stores false and returns what was stored")
    @SuppressWarnings("unchecked")
    void optIn() {
        ResponseEntity<?> response = controller.updateAnalyticsConsent(Map.of("optOut", false), authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("analyticsOptOut", false);
        assertThat(user.getAnalyticsOptOut()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("opting back out stores true")
    @SuppressWarnings("unchecked")
    void optOutAgain() {
        user.setAnalyticsOptOut(false);

        ResponseEntity<?> response = controller.updateAnalyticsConsent(Map.of("optOut", true), authentication());

        assertThat((Map<String, Object>) response.getBody()).containsEntry("analyticsOptOut", true);
        assertThat(user.getAnalyticsOptOut()).isTrue();
    }

    @Test
    @DisplayName("no authentication: 401, and nothing is written")
    void rejectsUnauthenticated() {
        ResponseEntity<?> response = controller.updateAnalyticsConsent(Map.of("optOut", false), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("a body without an explicit boolean is refused rather than guessed at")
    void rejectsNonBooleanBody() {
        for (Object bad : List.of("true", 1, "yes")) {
            ResponseEntity<?> response =
                    controller.updateAnalyticsConsent(Map.of("optOut", bad), authentication());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<?> missing = controller.updateAnalyticsConsent(Map.of(), authentication());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("the session payload reports consent, defaulting to opted out on a null column")
    void sessionPayloadCarriesConsent() {
        user.setAnalyticsOptOut(false);
        assertThat(new UserController.UserProgressResponse(user).isAnalyticsOptOut()).isFalse();

        user.setAnalyticsOptOut(true);
        assertThat(new UserController.UserProgressResponse(user).isAnalyticsOptOut()).isTrue();

        user.setAnalyticsOptOut(null);
        assertThat(new UserController.UserProgressResponse(user).isAnalyticsOptOut())
                .as("a null column must read as opted out, never as consent")
                .isTrue();
    }
}
