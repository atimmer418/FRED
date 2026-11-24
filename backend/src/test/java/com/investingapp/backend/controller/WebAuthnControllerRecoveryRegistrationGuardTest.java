package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.investingapp.backend.dto.RegistrationFinishRequest;
import com.investingapp.backend.dto.RegistrationFinishResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PasskeyCredentialRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.service.AccountRecoveryService;
import com.investingapp.backend.service.WebAuthnService;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may register a passkey, and two sign-in rules the email-change protection depends on.
 *
 * 1. After account recovery, only the one-time recovery token opens passkey registration. A session
 *    token never does: if it could, a thief with a stolen token would add their own passkey and
 *    then pass every "prove it is you" check.
 * 2. The recovery token decides which account the new passkey goes on. The email in the request is
 *    not trusted for that.
 * 3. The sign-up path cannot add a passkey to an account that already has one.
 * 4. A device that still remembers an address from before an email change must still be able to
 *    start and finish a sign-in.
 */
class WebAuthnControllerRecoveryRegistrationGuardTest {

    private static final String EMAIL = "victim@example.test";
    private static final String TOKEN = "session-token";
    private static final String RECOVERY_TOKEN = "recovery-token";
    private static final Long USER_ID = 5L;

    private WebAuthnService webAuthnService;
    private UserRepository userRepository;
    private PasskeyCredentialRepository passkeyCredentialRepository;
    private AccountRecoveryService accountRecoveryService;
    private Cache<String, PublicKeyCredentialCreationOptions> registrationChallenges;
    private Cache<String, PublicKeyCredentialRequestOptions> authChallenges;
    private WebAuthnController controller;
    private MockHttpServletRequest request;
    private PublicKeyCredentialCreationOptions options;

    @BeforeEach
    void setUp() {
        webAuthnService = mock(WebAuthnService.class);
        userRepository = mock(UserRepository.class);
        passkeyCredentialRepository = mock(PasskeyCredentialRepository.class);
        accountRecoveryService = mock(AccountRecoveryService.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        registrationChallenges = Caffeine.newBuilder().build();
        authChallenges = Caffeine.newBuilder().build();

        controller = new WebAuthnController(webAuthnService, registrationChallenges, authChallenges, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "passkeyCredentialRepository", passkeyCredentialRepository);
        ReflectionTestUtils.setField(controller, "accountRecoveryService", accountRecoveryService);

        when(jwtUtils.validateJwtToken(TOKEN)).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken(TOKEN)).thenReturn(EMAIL);
        User user = new User(EMAIL);
        user.setId(USER_ID);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);

        options = PublicKeyCredentialCreationOptions.builder()
                .rp(RelyingPartyIdentity.builder().id("example.test").name("FRED").build())
                .user(UserIdentity.builder().name(EMAIL).displayName(EMAIL).id(new ByteArray(new byte[16])).build())
                .challenge(new ByteArray(new byte[32]))
                .pubKeyCredParams(List.of(PublicKeyCredentialParameters.ES256))
                .build();
    }

    private RegistrationFinishRequest finishRequest(String email, String recoveryToken) {
        RegistrationFinishRequest finish = new RegistrationFinishRequest();
        finish.setEmail(email);
        finish.setCredential(new ObjectMapper().createObjectNode()
                .set("response", new ObjectMapper().createObjectNode().put("attestationObject", "real-looking")));
        finish.setRecoveryToken(recoveryToken);
        return finish;
    }

    private RegistrationFinishResponse registered(boolean success) {
        return new RegistrationFinishResponse(success, success ? "ok" : "no", success ? "jwt" : null, USER_ID, EMAIL);
    }

    // ------------------------------------------------------------------ starting the passkey after recovery

    @Test
    @DisplayName("without a recovery token nothing starts, whatever session token the caller holds")
    void noRecoveryTokenNoRegistration() {
        when(accountRecoveryService.userForRecoveryToken(null)).thenReturn(Optional.empty());

        assertThat(controller.startRecoveryRegistration(Map.of()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.startRecoveryRegistration(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(webAuthnService, never()).startRecoveryRegistrationFlow(anyString());
        verify(accountRecoveryService, never()).rememberPasskeyChallenge(any(), any());
    }

    @Test
    @DisplayName("a recovery token that already made its passkey is told to sign in, not to start again")
    void finishedRecoveryTokenIsToldToSignIn() {
        when(accountRecoveryService.userForRecoveryToken(RECOVERY_TOKEN)).thenReturn(Optional.empty());
        when(accountRecoveryService.alreadyFinished(RECOVERY_TOKEN)).thenReturn(true);

        assertThat(controller.startRecoveryRegistration(Map.of("recoveryToken", RECOVERY_TOKEN)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(webAuthnService, never()).startRecoveryRegistrationFlow(anyString());
    }

    @Test
    @DisplayName("a recovery token for an account that somehow still has a passkey is refused")
    void recoveryTokenCannotAddASecondPasskey() {
        when(accountRecoveryService.userForRecoveryToken(RECOVERY_TOKEN)).thenReturn(Optional.of(USER_ID));
        when(passkeyCredentialRepository.countByUser_Id(USER_ID)).thenReturn(1L);

        ResponseEntity<?> response = controller.startRecoveryRegistration(Map.of("recoveryToken", RECOVERY_TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(webAuthnService, never()).startRecoveryRegistrationFlow(anyString());
    }

    @Test
    @DisplayName("a live recovery token starts registration, and the challenge is kept with the token only")
    void recoveryTokenStartsRegistration() {
        when(accountRecoveryService.userForRecoveryToken(RECOVERY_TOKEN)).thenReturn(Optional.of(USER_ID));
        when(webAuthnService.startRecoveryRegistrationFlow(EMAIL)).thenReturn(options);
        when(accountRecoveryService.rememberPasskeyChallenge(RECOVERY_TOKEN, options)).thenReturn(true);

        ResponseEntity<?> response = controller.startRecoveryRegistration(Map.of("recoveryToken", RECOVERY_TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accountRecoveryService).rememberPasskeyChallenge(RECOVERY_TOKEN, options);
        // Not in the sign-up cache, so a finish request without the token finds nothing to finish.
        assertThat(registrationChallenges.getIfPresent(EMAIL)).isNull();
    }

    @Test
    @DisplayName("a recovery token that has been used too often gives no challenge")
    void usedUpRecoveryTokenGivesNothing() {
        when(accountRecoveryService.userForRecoveryToken(RECOVERY_TOKEN)).thenReturn(Optional.of(USER_ID));
        when(webAuthnService.startRecoveryRegistrationFlow(EMAIL)).thenReturn(options);
        when(accountRecoveryService.rememberPasskeyChallenge(RECOVERY_TOKEN, options)).thenReturn(false);

        assertThat(controller.startRecoveryRegistration(Map.of("recoveryToken", RECOVERY_TOKEN)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ finishing it

    @Test
    @DisplayName("the passkey goes on the account the recovery token names, not the email in the request")
    void recoveryTokenDecidesTheAccount() {
        when(accountRecoveryService.takePasskeyChallenge(RECOVERY_TOKEN))
                .thenReturn(Optional.of(new AccountRecoveryService.PendingPasskey(USER_ID, options)));
        when(webAuthnService.finishRegistrationFlow(eq(EMAIL), any(), eq(options))).thenReturn(registered(true));

        ResponseEntity<RegistrationFinishResponse> response = controller.finishRegistration(
                finishRequest("someone-else@example.test", RECOVERY_TOKEN), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webAuthnService).finishRegistrationFlow(eq(EMAIL), any(), eq(options));
        verify(webAuthnService, never()).finishRegistrationFlow(eq("someone-else@example.test"), any(), any());
        // Used up only now that the passkey is in place.
        verify(accountRecoveryService).recoveryFinished(RECOVERY_TOKEN);
    }

    @Test
    @DisplayName("a failed passkey leaves the recovery token alive for another try")
    void failedPasskeyKeepsTheRecoveryToken() {
        when(accountRecoveryService.takePasskeyChallenge(RECOVERY_TOKEN))
                .thenReturn(Optional.of(new AccountRecoveryService.PendingPasskey(USER_ID, options)));
        when(webAuthnService.finishRegistrationFlow(eq(EMAIL), any(), eq(options))).thenReturn(registered(false));

        assertThat(controller.finishRegistration(finishRequest(EMAIL, RECOVERY_TOKEN), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(accountRecoveryService, never()).recoveryFinished(anyString());
    }

    @Test
    @DisplayName("a recovery token with no waiting challenge finishes nothing, even if a sign-up challenge exists")
    void recoveryTokenWithoutChallengeFinishesNothing() {
        registrationChallenges.put(EMAIL, options);
        when(accountRecoveryService.takePasskeyChallenge("made-up")).thenReturn(Optional.empty());

        assertThat(controller.finishRegistration(finishRequest(EMAIL, "made-up"), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(webAuthnService, never()).finishRegistrationFlow(anyString(), any(), any());
    }

    @Test
    @DisplayName("a new sign-up still finishes with its own challenge, once")
    void signUpStillWorksOnce() {
        registrationChallenges.put(EMAIL, options);
        when(passkeyCredentialRepository.countByUser_Id(USER_ID)).thenReturn(0L);
        when(webAuthnService.finishRegistrationFlow(eq(EMAIL), any(), eq(options))).thenReturn(registered(true));

        assertThat(controller.finishRegistration(finishRequest(EMAIL, null), request).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.finishRegistration(finishRequest(EMAIL, null), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(webAuthnService, times(1)).finishRegistrationFlow(anyString(), any(), any());
        verify(accountRecoveryService, never()).recoveryFinished(any());
    }

    @Test
    @DisplayName("the sign-up path cannot add a passkey to an account that already has one")
    void signUpPathCannotAddASecondPasskey() {
        registrationChallenges.put(EMAIL, options);
        when(passkeyCredentialRepository.countByUser_Id(USER_ID)).thenReturn(1L);

        assertThat(controller.finishRegistration(finishRequest(EMAIL, null), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(webAuthnService, never()).finishRegistrationFlow(anyString(), any(), any());
    }

    // ------------------------------------------------------------------ sign-in after an email change

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
