package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import com.yubico.webauthn.exception.AssertionFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server-side passkey check that guards a change of email. The WebAuthn library does the
 * cryptography (it is mocked here, and exercised for real by sign-in); what this pins down is
 * everything around it: a challenge works once, an unverified or failed assertion counts for
 * nothing, and the answer is the owner of the passkey and nobody else.
 */
class WebAuthnPasskeyReauthVerifierTest {

    private static final String SESSION = "challenge-session";
    private static final ByteArray HANDLE = new ByteArray(new byte[] {1, 2, 3, 4});

    private RelyingParty relyingParty;
    private Cache<String, PublicKeyCredentialRequestOptions> challenges;
    private UserRepository userRepository;
    private WebAuthnPasskeyReauthVerifier verifier;
    private JsonNode assertion;

    @BeforeEach
    void setUp() throws Exception {
        relyingParty = mock(RelyingParty.class);
        challenges = Caffeine.newBuilder().build();
        userRepository = mock(UserRepository.class);
        verifier = new WebAuthnPasskeyReauthVerifier(relyingParty, challenges, userRepository);

        challenges.put(SESSION, PublicKeyCredentialRequestOptions.builder()
                .challenge(new ByteArray(new byte[32]))
                .build());

        // A well-formed assertion. Its signature is never checked here: the library is mocked.
        String b64 = "AAAA";
        assertion = new ObjectMapper().readTree("{\"id\":\"" + b64 + "\",\"rawId\":\"" + b64 + "\",\"type\":\"public-key\","
                + "\"clientExtensionResults\":{},\"response\":{"
                + "\"clientDataJSON\":\"" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "{\"type\":\"webauthn.get\",\"challenge\":\"AAAA\",\"origin\":\"https://example.test\"}".getBytes())
                + "\",\"authenticatorData\":\"" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[37])
                + "\",\"signature\":\"" + b64 + "\"}}");

        User owner = new User("owner@example.test");
        owner.setId(42L);
        when(userRepository.findByUserHandle(HANDLE.getBase64Url())).thenReturn(Optional.of(owner));
    }

    private void libraryAnswers(boolean success, boolean userVerified) throws Exception {
        AssertionResult result = mock(AssertionResult.class);
        RegisteredCredential credential = mock(RegisteredCredential.class);
        when(credential.getUserHandle()).thenReturn(HANDLE);
        when(result.isSuccess()).thenReturn(success);
        when(result.isUserVerified()).thenReturn(userVerified);
        when(result.getCredential()).thenReturn(credential);
        when(relyingParty.finishAssertion(any(FinishAssertionOptions.class))).thenReturn(result);
    }

    @Test
    @DisplayName("a verified assertion answers with the passkey owner's id")
    void verifiedAssertionNamesItsOwner() throws Exception {
        libraryAnswers(true, true);

        assertThat(verifier.verifiedUserId(SESSION, assertion)).contains(42L);
    }

    @Test
    @DisplayName("a challenge works once: the same assertion sent again is refused without being checked")
    void challengeIsSingleUse() throws Exception {
        libraryAnswers(true, true);

        assertThat(verifier.verifiedUserId(SESSION, assertion)).contains(42L);
        assertThat(verifier.verifiedUserId(SESSION, assertion)).isEmpty();

        verify(relyingParty, times(1)).finishAssertion(any(FinishAssertionOptions.class));
        assertThat(challenges.getIfPresent(SESSION)).isNull();
    }

    @Test
    @DisplayName("a failed check still uses the challenge up")
    void failedCheckConsumesTheChallenge() throws Exception {
        when(relyingParty.finishAssertion(any(FinishAssertionOptions.class)))
                .thenThrow(new AssertionFailedException("bad signature"));

        assertThat(verifier.verifiedUserId(SESSION, assertion)).isEmpty();
        assertThat(challenges.getIfPresent(SESSION)).isNull();
    }

    @Test
    @DisplayName("an unknown challenge, or missing fields, is refused before the library is asked")
    void unknownOrMissingInputsAreRefused() throws Exception {
        libraryAnswers(true, true);

        assertThat(verifier.verifiedUserId("never-issued", assertion)).isEmpty();
        assertThat(verifier.verifiedUserId(null, assertion)).isEmpty();
        assertThat(verifier.verifiedUserId("  ", assertion)).isEmpty();
        assertThat(verifier.verifiedUserId(SESSION, null)).isEmpty();

        verify(relyingParty, never()).finishAssertion(any(FinishAssertionOptions.class));
    }

    @Test
    @DisplayName("an assertion the library rejects is refused")
    void unsuccessfulAssertionIsRefused() throws Exception {
        libraryAnswers(false, true);

        assertThat(verifier.verifiedUserId(SESSION, assertion)).isEmpty();
    }

    @Test
    @DisplayName("holding the passkey without Face ID, Touch ID or passcode is refused")
    void assertionWithoutUserVerificationIsRefused() throws Exception {
        libraryAnswers(true, false);

        assertThat(verifier.verifiedUserId(SESSION, assertion)).isEmpty();
        verify(userRepository, never()).findByUserHandle(anyString());
    }

    @Test
    @DisplayName("an assertion that is not valid JSON for a passkey is refused, not thrown")
    void garbageIsRefused() throws Exception {
        JsonNode garbage = new ObjectMapper().readTree("{\"id\":\"x\"}");

        assertThat(verifier.verifiedUserId(SESSION, garbage)).isEmpty();
    }

    @Test
    @DisplayName("a passkey whose owner cannot be found is refused")
    void unknownOwnerIsRefused() throws Exception {
        libraryAnswers(true, true);
        when(userRepository.findByUserHandle(anyString())).thenReturn(Optional.empty());

        assertThat(verifier.verifiedUserId(SESSION, assertion)).isEmpty();
    }
}
