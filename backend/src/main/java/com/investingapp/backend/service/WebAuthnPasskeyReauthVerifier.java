package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.WebAuthnConversionUtil;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The real check: the same WebAuthn verification that sign-in uses, without any of sign-in's side
 * effects. It does not create a session, issue a token or touch the security context. It only
 * answers "whose passkey signed this challenge", and only when the device also verified the person
 * (Face ID, Touch ID or passcode).
 */
@Service
public class WebAuthnPasskeyReauthVerifier implements PasskeyReauthVerifier {

    private static final Logger logger = LoggerFactory.getLogger(WebAuthnPasskeyReauthVerifier.class);

    private final RelyingParty relyingParty;
    private final Cache<String, PublicKeyCredentialRequestOptions> authChallengeCache;
    private final UserRepository userRepository;

    public WebAuthnPasskeyReauthVerifier(RelyingParty relyingParty,
            Cache<String, PublicKeyCredentialRequestOptions> authChallengeCache,
            UserRepository userRepository) {
        this.relyingParty = relyingParty;
        this.authChallengeCache = authChallengeCache;
        this.userRepository = userRepository;
    }

    @Override
    public Optional<Long> verifiedUserId(String challengeSessionId, JsonNode credential) {
        if (challengeSessionId == null || challengeSessionId.isBlank() || credential == null || credential.isNull()) {
            return Optional.empty();
        }

        // One use only: taking the challenge removes it in the same step, so the same assertion
        // cannot be accepted twice, whether or not the check below passes.
        PublicKeyCredentialRequestOptions challenge = authChallengeCache.asMap().remove(challengeSessionId);
        if (challenge == null) {
            return Optional.empty();
        }

        try {
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> parsed =
                    PublicKeyCredential.parseAssertionResponseJson(credential.toString());

            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(WebAuthnConversionUtil.toAssertionRequest(challenge))
                    .response(parsed)
                    .build());

            if (!result.isSuccess()) {
                return Optional.empty();
            }
            // Holding the passkey is not enough here: the device must also say it checked the
            // person (Face ID, Touch ID or the device passcode) for this very assertion.
            if (!result.isUserVerified()) {
                logger.warn("Passkey re-auth refused: the device did not verify the user");
                return Optional.empty();
            }

            String userHandle = result.getCredential().getUserHandle().getBase64Url();
            return userRepository.findByUserHandle(userHandle).map(User::getId);
        } catch (Exception e) {
            logger.warn("Passkey re-auth check failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
