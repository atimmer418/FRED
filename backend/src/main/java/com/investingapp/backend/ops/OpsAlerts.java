package com.investingapp.backend.ops;

/** ops_alerts.alert_type values (max 64). Phase 0 names are the old log keys without "alert.". */
public final class OpsAlerts {

    public static final String USER_DELETION_DEFERRED = "user_deletion.deferred";
    public static final String ACH_ADOPT_REFUSED = "ach.adopt_refused";
    public static final String ACH_CANCEL_FAILED = "ach.cancel_failed";
    public static final String ACH_POLL_LIST_FAILED = "ach.poll_list_failed";
    public static final String ACH_UNKNOWN_RELATIONSHIP = "ach.unknown_relationship";
    public static final String ACH_CANCEL_STUCK = "ach.cancel_stuck";
    public static final String ACH_CREATE_FAILED = "ach.create_failed";
    public static final String BATCH_ROWS_MISSING = "batch_rows_missing";
    public static final String BATCH_CLAIM_SHORT = "batch_claim_short";
    public static final String BATCH_NO_RELATIONSHIP = "batch_no_relationship";
    public static final String JOB_LOCK_UNAVAILABLE = "job_lock.unavailable";
    public static final String JOB_LOCK_RELEASE_FAILED = "job_lock.release_failed";
    public static final String PLAID_ITEM_REMOVE_EXHAUSTED = "plaid.item_remove.exhausted";
    public static final String PLAID_WEBHOOK_AMBIGUOUS_ITEM = "plaid.webhook.ambiguous_item";

    private OpsAlerts() {
    }
}
