package com.investingapp.backend.controller;

import com.investingapp.backend.model.User;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * users.public_id is NULLable until the migration that tightens it, so every read path has to
 * tolerate null. Token refresh is the one that puts it in a response body, and Map.of would have
 * turned a null into a 500 on every refresh for that user.
 */
class AuthControllerRefreshPublicIdTest {

    private static final String EMAIL = "user@fredvested.com";

    private AuthController controller;
    private UserService userService;
    private JwtUtils jwtUtils;
    private com.investingapp.backend.security.TokenCutoffRegistry tokenCutoffs;
    private com.investingapp.backend.repository.UserSessionRepository sessions;

    @BeforeEach
    void setUp() {
        controller = new AuthController();
        userService = mock(UserService.class);
        jwtUtils = mock(JwtUtils.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        tokenCutoffs = new com.investingapp.backend.security.TokenCutoffRegistry(3_600_000);
        ReflectionTestUtils.setField(controller, "tokenCutoffs", tokenCutoffs);
        sessions = mock(com.investingapp.backend.repository.UserSessionRepository.class);
        ReflectionTestUtils.setField(controller, "userSessionRepository", sessions);
        when(jwtUtils.getSessionIdFromJwtToken("old.token.value")).thenReturn(5L);
        when(sessions.existsByIdAndUserIdAndActiveTrue(5L, 7L)).thenReturn(true);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, java.util.List.of()));

        when(jwtUtils.parseJwt(any())).thenReturn("old.token.value");
        when(jwtUtils.getNextStepFromJwtToken(any())).thenReturn("complete");
        // Refresh continues the old token's session, so the new token stays revocable and keeps its end.
        when(jwtUtils.continueSessionToken(eq("old.token.value"), eq(EMAIL), any(), any()))
                .thenReturn(java.util.Optional.of("new.token.value"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> refreshBody() {
        ResponseEntity<?> response = controller.refreshToken(new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) response.getBody();
    }

    @Test
    @DisplayName("a token that account recovery ended is not refreshed, even if it got past the filter")
    void endedTokenIsNotRefreshed() {
        User user = new User(EMAIL);
        user.setId(7L);
        when(userService.getUserByEmail(EMAIL)).thenReturn(user);
        java.time.Instant issued = java.time.Instant.parse("2026-11-23T12:00:00Z");
        when(jwtUtils.getIssuedAtFromToken("old.token.value")).thenReturn(java.util.Date.from(issued));
        tokenCutoffs.endTokensIssuedUpTo(7L, issued.plusSeconds(30));

        ResponseEntity<?> response = controller.refreshToken(new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        org.mockito.Mockito.verify(jwtUtils, org.mockito.Mockito.never())
                .continueSessionToken(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a token whose session was replaced or revoked after the filter is not refreshed")
    void replacedSessionIsNotRefreshed() {
        User user = new User(EMAIL);
        user.setId(7L);
        when(userService.getUserByEmail(EMAIL)).thenReturn(user);
        when(sessions.existsByIdAndUserIdAndActiveTrue(5L, 7L)).thenReturn(false);

        ResponseEntity<?> response = controller.refreshToken(new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        org.mockito.Mockito.verify(jwtUtils, org.mockito.Mockito.never())
                .continueSessionToken(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a token whose session has ended is not refreshed")
    void endedSessionIsNotRefreshed() {
        User user = new User(EMAIL);
        user.setId(7L);
        when(userService.getUserByEmail(EMAIL)).thenReturn(user);
        when(jwtUtils.continueSessionToken(eq("old.token.value"), eq(EMAIL), any(), any()))
                .thenReturn(java.util.Optional.empty());

        ResponseEntity<?> response = controller.refreshToken(new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a refreshed token response carries the public id")
    void refreshReturnsPublicId() {
        String publicId = UUID.randomUUID().toString();
        User user = new User(EMAIL);
        user.setId(7L);
        user.setPublicId(publicId);
        when(userService.getUserByEmail(EMAIL)).thenReturn(user);

        Map<String, Object> body = refreshBody();

        assertThat(body).containsEntry("publicId", publicId);
        assertThat(body).containsEntry("jwtToken", "new.token.value");
    }

    @Test
    @DisplayName("a user whose public id is still null refreshes normally instead of 500ing")
    void refreshToleratesNullPublicId() {
        User user = new User(EMAIL);
        user.setId(7L);
        user.setPublicId(null);
        when(userService.getUserByEmail(EMAIL)).thenReturn(user);

        Map<String, Object> body = refreshBody();

        assertThat(body).containsKey("publicId");
        assertThat(body.get("publicId")).isNull();
        assertThat(body).containsEntry("jwtToken", "new.token.value");
    }
}
