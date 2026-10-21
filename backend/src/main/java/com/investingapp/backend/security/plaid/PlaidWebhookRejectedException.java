package com.investingapp.backend.security.plaid;

/** The request itself failed verification. Answered 401, which Plaid treats as permanent (no retry). */
public class PlaidWebhookRejectedException extends RuntimeException {

    public PlaidWebhookRejectedException(String message) {
        super(message);
    }

    public PlaidWebhookRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
