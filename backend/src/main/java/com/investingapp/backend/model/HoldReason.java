package com.investingapp.backend.model;

/**
 * investment_schedules.hold_reason (spec 3.2): the system's stop; null means no system hold. The
 * user's stop is InvestmentSchedule.Status.PAUSED_BY_USER (V7 replaced the old pause flag), and neither one
 * ever writes the other. Which holds block money is decided only in isReadyForInvestment.
 */
public enum HoldReason {
    AWAITING_AUTHORIZATION, BANK_CHANGE, ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED,
    ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, DORMANT, KILL_SWITCH, MANUAL_REVIEW
}
