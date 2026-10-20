package com.investingapp.backend.security.plaid;

/** FRED could not verify (key fetch, Plaid 5xx, unusable JWK). Answered 503 so Plaid retries. */
public class PlaidWebhookUnavailableException extends RuntimeException {

    public PlaidWebhookUnavailableException(String message) {
        super(message);
    }

    public PlaidWebhookUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
