package com.investingapp.backend.security.plaid;

/**
 * Verifies a Plaid webhook and returns the JWT's iat (seconds since the epoch), which the inbox
 * folds into the dedupe key. Exactly one implementation is registered per profile: the JWT
 * verifier everywhere except ci, the bypass under ci (there is no Plaid network in CI).
 */
public interface PlaidWebhookVerifier {

    /**
     * @throws PlaidWebhookRejectedException    the request fails verification (answered 401)
     * @throws PlaidWebhookUnavailableException FRED cannot verify right now (answered 503)
     */
    long verifyAndReturnIat(byte[] rawBody, String jwt);
}
