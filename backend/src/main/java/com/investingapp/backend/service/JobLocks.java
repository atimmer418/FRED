package com.investingapp.backend.service;

/** Names of the job_locks rows (spec 3.9). Never share one name between two jobs. */
public final class JobLocks {

    public static final String PROCESS_SCHEDULED = "investment.process-scheduled";
    public static final String EOD_BATCH = "investment.eod-batch";
    public static final String ACH_POLL = "ach.relationship-poll";
    public static final String PLAID_ITEM_REMOVALS = "plaid.item-removals";

    private JobLocks() {
    }
}
