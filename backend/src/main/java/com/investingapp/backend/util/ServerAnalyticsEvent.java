package com.investingapp.backend.util;

import java.util.List;

/**
 * The events the server emits, and the only names allowed in the outbox.
 *
 * This list is the mirror of SERVER_EVENT_NAMES in
 * frontend/src/app/services/analytics/analytics-events.ts. The client declares them so it can
 * never define a second, conflicting version of the same fact; the server declares them because
 * it is the one that actually emits them. ServerAnalyticsEventParityTest fails if the two drift.
 *
 * Three of these are DEFINED BUT NOT EMITTED yet: trial_started, subscription_billed and
 * subscription_cancelled. Billing does not exist in this repository. The advisory fee is collected
 * outside Apple, by ACH from the Plaid-linked bank, and a separate billing session owns it. The
 * emitter interface and its tests exist so that work has something to call; see
 * AnalyticsEmitter for the exact call sites it must use.
 */
public final class ServerAnalyticsEvent {

    private ServerAnalyticsEvent() {
    }

    /** The plan and its drawdown strategy were first persisted. Once per user. */
    public static final String FREEDOM_PLAN_CREATED = "freedom_plan_created";

    /** A passkey was registered. Carries whether it is the user's first. */
    public static final String PASSKEY_CREATED = "passkey_created";

    /** A KYC submission was accepted by us and sent to the broker. Carries which attempt. */
    public static final String KYC_SUBMITTED = "kyc_submitted";

    /** A bank was linked through Plaid. Never carries the institution. */
    public static final String PLAID_LINKED = "plaid_linked";

    /** A recurring investment schedule was created. */
    public static final String INVESTMENT_SCHEDULE_CREATED = "investment_schedule_created";

    /**
     * A recurring schedule stopped. `reason` says why, because the reasons are not equivalent: a
     * user pausing is churn, a system pause during a bank change is a lifecycle step.
     */
    public static final String SCHEDULE_PAUSED = "schedule_paused";

    /** A recurring schedule started again, either because the user asked or because ACH landed. */
    public static final String SCHEDULE_RESUMED = "schedule_resumed";

    /** A recurring schedule was destroyed. Only ever system- or account-closure-driven today. */
    public static final String SCHEDULE_CANCELLED = "schedule_cancelled";

    /** A trial began. NOT EMITTED YET: billing owns this. */
    public static final String TRIAL_STARTED = "trial_started";

    /** The broker decided on a KYC submission: approved, rejected or needs_more_info. */
    public static final String KYC_DECIDED = "kyc_decided";

    /** The user's first ACH transfer settled. Settled, not initiated. */
    public static final String FIRST_ACH_TRANSFER_COMPLETED = "first_ach_transfer_completed";

    /** Every ACH transfer after the first, once settled. */
    public static final String ACH_TRANSFER_COMPLETED = "ach_transfer_completed";

    /** A subscription was billed. NOT EMITTED YET: billing owns this. */
    public static final String SUBSCRIPTION_BILLED = "subscription_billed";

    /** A subscription was cancelled. NOT EMITTED YET: billing owns this. */
    public static final String SUBSCRIPTION_CANCELLED = "subscription_cancelled";

    /** Every name, in funnel order. The outbox rejects anything not on this list. */
    public static final List<String> ALL = List.of(
            FREEDOM_PLAN_CREATED,
            PASSKEY_CREATED,
            KYC_SUBMITTED,
            PLAID_LINKED,
            INVESTMENT_SCHEDULE_CREATED,
            SCHEDULE_PAUSED,
            SCHEDULE_RESUMED,
            SCHEDULE_CANCELLED,
            TRIAL_STARTED,
            KYC_DECIDED,
            FIRST_ACH_TRANSFER_COMPLETED,
            ACH_TRANSFER_COMPLETED,
            SUBSCRIPTION_BILLED,
            SUBSCRIPTION_CANCELLED);

    /** The three that billing will emit once it exists. Here so tests can assert they are inert. */
    public static final List<String> AWAITING_BILLING =
            List.of(TRIAL_STARTED, SUBSCRIPTION_BILLED, SUBSCRIPTION_CANCELLED);

    /**
     * Why a schedule changed state. Closed, because the whole point of recording the reason is to
     * tell churn ("the user stopped it") from lifecycle ("we stopped it while the bank changed").
     */
    public static final String REASON_USER_REQUEST = "user_request";
    public static final String REASON_BANK_CHANGE = "bank_change";
    public static final String REASON_ACH_CONFIRMED = "ach_confirmed";
    public static final String REASON_ACCOUNT_CLOSED = "account_closed";
    public static final String REASON_KYC_REJECTED = "kyc_rejected";

    /** Who caused it. 'user' is churn you can act on; 'system' is our own machinery. */
    public static final String INITIATOR_USER = "user";
    public static final String INITIATOR_SYSTEM = "system";

    /** Every trial is two weeks, on every tier. */
    public static final int TRIAL_LENGTH_DAYS = 14;
}
