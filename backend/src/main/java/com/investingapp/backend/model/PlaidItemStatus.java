package com.investingapp.backend.model;

/** users.plaid_item_status (spec 3.1). Written by the webhook processor from Phase 1 on. */
public enum PlaidItemStatus {
    OK, LOGIN_REQUIRED, PENDING_DISCONNECT, REVOKED, REMOVED, SYNC_LIMIT, NOT_SUPPORTED
}
