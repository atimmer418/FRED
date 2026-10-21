package com.investingapp.backend.model;

/** plaid_webhook_events.status (spec 3.6). Phase 0 writes RECEIVED and IGNORED only. */
public enum PlaidWebhookEventStatus {
    RECEIVED, PROCESSING, PROCESSED, FAILED, IGNORED
}
