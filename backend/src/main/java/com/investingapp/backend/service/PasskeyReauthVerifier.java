package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Proves, on the server, that the person making a request holds a passkey right now.
 *
 * A session token alone only proves that someone has the token. Sensitive changes (the first is a
 * change of email) ask for a fresh passkey assertion as well, and this is what checks it.
 */
public interface PasskeyReauthVerifier {

    /**
     * Checks a passkey assertion against the one-time challenge issued by
     * POST /api/passkey/authenticate/start.
     *
     * @param challengeSessionId the sessionId that came back with the challenge
     * @param credential         the assertion the device produced for that challenge
     * @return the id of the user who owns the passkey, or empty if the challenge is unknown or
     *         already used, or the assertion does not verify. Never throws.
     */
    Optional<Long> verifiedUserId(String challengeSessionId, JsonNode credential);
}
