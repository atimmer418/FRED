package com.investingapp.backend.model;

/**
 * investment_schedules.hold_reason (spec 3.2): the system's stop. is_paused stays the user's stop;
 * null means no system hold.
 */
public enum HoldReason {
    AWAITING_AUTHORIZATION, BANK_CHANGE, ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED,
    ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, DORMANT, KILL_SWITCH, MANUAL_REVIEW
}
