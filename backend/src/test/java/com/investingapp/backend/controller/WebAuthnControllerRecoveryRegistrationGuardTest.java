package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PasskeyCredentialRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.service.WebAuthnService;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two passkey rules that the email-change protection depends on.
 *
 * 1. A session token alone must not be able to add a passkey to an account that already has one.
 *    If it could, a thief with a stolen token would add their own passkey and then pass every
 *    "prove it is you" check, including the one on changing email.
 * 2. A device that still remembers an address from before an email change must still be able to
 *    start a sign-in.
 */
class WebAuthnControllerRecoveryRegistrationGuardTest {

    private static final String EMAIL = "victim@example.test";
    private static final String TOKEN = "stolen-or-recovery-token";

    private WebAuthnService webAuthnService;
    private UserRepository userRepository;
    private PasskeyCredentialRepository passkeyCredentialRepository;
    private Cache<String, PublicKeyCredentialCreationOptions> registrationChallenges;
    private Cache<String, PublicKeyCredentialRequestOptions> authChallenges;
    private WebAuthnController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        webAuthnService = mock(WebAuthnService.class);
        userRepository = mock(UserRepository.class);
        passkeyCredentialRepository = mock(PasskeyCredentialRepository.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        registrationChallenges = Caffeine.newBuilder().build();
        authChallenges = Caffeine.newBuilder().build();

        controller = new WebAuthnController(webAuthnService, registrationChallenges, authChallenges, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "passkeyCredentialRepository", passkeyCredentialRepository);

        when(jwtUtils.validateJwtToken(TOKEN)).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken(TOKEN)).thenReturn(EMAIL);
        User user = new User(EMAIL);
        user.setId(5L);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
    }

    @Test
    @DisplayName("an account that still has a passkey cannot get another one from a session token")
    void tokenAloneCannotAddAPasskey() {
        when(passkeyCredentialRepository.countByUser_Id(5L)).thenReturn(1L);

        ResponseEntity<?> response = controller.startRecoveryRegistration(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // No registration challenge was made or stored, so /register/finish has nothing to finish.
        verify(webAuthnService, never()).startRecoveryRegistrationFlow(anyString());
        assertThat(registrationChallenges.getIfPresent(EMAIL)).isNull();
    }

    @Test
    @DisplayName("an account with no passkeys left (just recovered) can still register a new one")
    void recoveredAccountCanRegister() {
        when(passkeyCredentialRepository.countByUser_Id(5L)).thenReturn(0L);
        when(webAuthnService.startRecoveryRegistrationFlow(EMAIL))
                .thenThrow(new IllegalStateException("reached the service"));

        ResponseEntity<?> response = controller.startRecoveryRegistration(request);

        // The guard let it through: the service was asked. (It is mocked to fail, hence the 500.)
        verify(webAuthnService).startRecoveryRegistrationFlow(EMAIL);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("a device holding a token from before an email change can finish signing in with its passkey")
    void staleTokenDoesNotBlockSignIn() throws Exception {
        signInFinishes("new@example.test");
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false); // the token's address is gone

        assertThat(controller.finishAuthentication(finishBody(), request).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a token for one live account still cannot be re-authenticated with another account's passkey")
    void stepUpMismatchIsStillRefused() throws Exception {
        signInFinishes("someone-else@example.test");
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThat(controller.finishAuthentication(finishBody(), request).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private Map<String, Object> finishBody() {
        return Map.of("sessionId", "auth-session", "credential", Map.of("id", "x"));
    }

    /** The passkey check passes and says the passkey belongs to the given email. */
    private void signInFinishes(String passkeyOwnerEmail) {
        authChallenges.put("auth-session", PublicKeyCredentialRequestOptions.builder()
                .challenge(new ByteArray(new byte[32]))
                .build());
        when(webAuthnService.finishAuthenticationFlow(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WebAuthnService.AuthenticationFinishResponse(
                        true, "ok", "jwt", 9L, passkeyOwnerEmail));
    }

    @Test
    @DisplayName("sign-in can start with an address no account has any more")
    void signInStartsWithAStaleAddress() {
        when(userRepository.existsByEmail("old@example.test")).thenReturn(false);
        when(webAuthnService.startAuthenticationFlow()).thenReturn(PublicKeyCredentialRequestOptions.builder()
                .challenge(new ByteArray(new byte[32]))
                .build());

        ResponseEntity<?> response = controller.startAuthentication(Map.of("email", "old@example.test"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webAuthnService).startAuthenticationFlow();
        verify(webAuthnService, never()).startAuthenticationFlow(anyString());
    }
}
