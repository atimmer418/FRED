package com.investingapp.backend.plaid;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Spec 2.1 error classes. Classification is on Plaid's exact error_code, never the HTTP status and
 * never error_type, with one fallback from spec 11: an answer with no parsable error_code and an
 * HTTP status of 500 or more is TRANSIENT. A parsed code that is not listed is PERMANENT (stop, log;
 * the nightly reconcile tries the Item again), which is also where a code Plaid adds later lands.
 *
 * <p>Handling per class belongs to the callers (T08, T13, T14, T15), not to this enum.
 */
public enum PlaidErrorClass {

    /** Re-queue once after 5 minutes, then the nightly reconcile. Counts toward the outage breaker. */
    TRANSIENT(Set.of("INSTITUTION_DOWN", "INSTITUTION_NOT_RESPONDING", "INSTITUTION_NOT_AVAILABLE",
            "INTERNAL_SERVER_ERROR", "PLANNED_MAINTENANCE", "PRODUCT_NOT_READY")),

    /**
     * Retry next minute. Counts toward the outage breaker. Every code Plaid documents under
     * error_type RATE_LIMIT_EXCEEDED (plaid.com/docs/errors/rate-limit-exceeded, read 2026-11-04).
     */
    RATE_LIMIT(Set.of("ACCOUNTS_LIMIT", "ACCOUNTS_BALANCE_GET_LIMIT", "AUTH_LIMIT", "BALANCE_LIMIT",
            "CREDITS_EXHAUSTED", "IDENTITY_LIMIT", "INSTITUTIONS_GET_LIMIT", "INSTITUTIONS_GET_BY_ID_LIMIT",
            "INSTITUTION_RATE_LIMIT", "INVESTMENTS_HOLDINGS_GET_LIMIT", "INVESTMENTS_LIMIT", "ITEM_GET_LIMIT",
            "RATE_LIMIT", "TRANSACTIONS_LIMIT", "TRANSACTIONS_REFRESH_LIMIT", "TRANSACTIONS_SYNC_LIMIT",
            "TRIAL_CONNECTION_LIMIT")),

    /** Consent flag false, NEEDS_CONSENT, no retry until update mode (T13 onConsentRequired). */
    CONSENT(Set.of("ADDITIONAL_CONSENT_REQUIRED", "ACCESS_NOT_GRANTED")),

    /** Item-state handler, spec 5.7 (T13). */
    ITEM_BROKEN(Set.of("ITEM_LOGIN_REQUIRED", "ITEM_NOT_FOUND", "USER_PERMISSION_REVOKED", "NO_ACCOUNTS")),

    /** plaid_item_status NOT_SUPPORTED, UNDETECTABLE, no hold, no reconnect card (T13 onNotSupported). */
    NOT_SUPPORTED(Set.of("PRODUCTS_NOT_SUPPORTED", "ITEM_NOT_SUPPORTED", "INVALID_PRODUCT")),

    /** Never retried. Phase 1 writes no hold for it in any mode (R2); T13 onSyncLimit owns the rest. */
    SYNC_LIMIT(Set.of("TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED")),

    /** /transactions/sync restarts from the first-page cursor, at most 3 times (T08). */
    MUTATION(Set.of("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION")),

    /** Everything else. Stop and log. */
    PERMANENT(Set.of());

    private static final Map<String, PlaidErrorClass> BY_CODE = new HashMap<>();

    static {
        for (PlaidErrorClass errorClass : values()) {
            for (String code : errorClass.codes) {
                BY_CODE.put(code, errorClass);
            }
        }
    }

    private final Set<String> codes;

    PlaidErrorClass(Set<String> codes) {
        this.codes = codes;
    }

    /** The exact error_code values this class owns (empty for PERMANENT). */
    public Set<String> codes() {
        return codes;
    }

    /**
     * @param errorCode  Plaid's error_code as parsed from the body, or null when none parsed
     * @param httpStatus the HTTP status, used only when errorCode is null or blank
     */
    public static PlaidErrorClass classify(String errorCode, int httpStatus) {
        if (errorCode == null || errorCode.isBlank()) {
            return httpStatus >= 500 ? TRANSIENT : PERMANENT;
        }
        return BY_CODE.getOrDefault(errorCode, PERMANENT);
    }
}
