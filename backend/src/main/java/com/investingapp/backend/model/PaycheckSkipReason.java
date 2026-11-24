package com.investingapp.backend.model;

/** paycheck_cycles.skip_reason (spec 3.5, VARCHAR(32)): the eleven reasons, in the spec's order. */
public enum PaycheckSkipReason {
    SHADOW_CONFIRMED, AFFORDABILITY, CATCH_UP_CAP, PAUSED, HOLD, NO_AUTHORIZATION, LATE_SEEN, ACH_NOT_READY,
    KILL_SWITCH, USER_SKIP_THIRD, STALE
}
