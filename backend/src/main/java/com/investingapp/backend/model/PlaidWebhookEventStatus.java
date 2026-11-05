package com.investingapp.backend.model;

/** plaid_webhook_events.status (spec 3.6). The inbox writes RECEIVED or IGNORED; PlaidWebhookProcessor claims RECEIVED as PROCESSING, then writes PROCESSED, IGNORED (stale item), FAILED, or RECEIVED again for a retry. */
public enum PlaidWebhookEventStatus {
    RECEIVED, PROCESSING, PROCESSED, FAILED, IGNORED
}
