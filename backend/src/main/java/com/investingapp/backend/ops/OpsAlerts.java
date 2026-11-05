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

    /** The 09:07 ET digest (T05): raised every morning with every gauge, subject "digest". */
    public static final String HEARTBEAT = "ops.heartbeat";
    /** Webhook inbox rows in FAILED (T05 daily, subject "global"; T14 also raises it per row). */
    public static final String INBOX_FAILED = "plaid.inbox.failed";
    /** The oldest RECEIVED or PROCESSING inbox row is older than 30 minutes (T05). */
    public static final String INBOX_OLDEST_AGE = "plaid.inbox.oldest_age";
    /** No webhook row received in 24 hours while billable Items exist (T05). */
    public static final String WEBHOOKS_NONE_24H = "plaid.webhooks.none_24h";
    /** detection_started_at is over 24 hours old and the mirror is SEARCHING or still null; REJECTED users excluded (T05). */
    public static final String SEARCHING_24H = "paycheck.searching_24h";
    /** A MANUAL_REVIEW hold exists on a user who is not a rejected KYC applicant (T05). */
    public static final String HOLDS_MANUAL_REVIEW_PRESENT = "holds.manual_review_present";
    /** No RECONCILE_RAN row for yesterday's Eastern day (T05, subject "nightly"). */
    public static final String RECONCILE_LATE = "paycheck.reconcile.late";
    /** Raised by the 22:30 reconcile once per run, subject "nightly" (T15); T05 reads it. */
    public static final String RECONCILE_RAN = "paycheck.reconcile.ran";


    private OpsAlerts() {
    }
}
