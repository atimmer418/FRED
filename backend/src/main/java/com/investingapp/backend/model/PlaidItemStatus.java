package com.investingapp.backend.model;

/**
 * users.plaid_item_status (spec 3.1). Writers: the exchange (OK on a new Item),
 * PlaidItemStateService (every webhook- and error-driven state, spec 5.7), and REMOVED beside
 * enqueueRemoval in the reconcile's REJECTED pass and the admin remove-item verb.
 */
public enum PlaidItemStatus {
    OK, LOGIN_REQUIRED, PENDING_DISCONNECT, REVOKED, REMOVED, SYNC_LIMIT, NOT_SUPPORTED
}
