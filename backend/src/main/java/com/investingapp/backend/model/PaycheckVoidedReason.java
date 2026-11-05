package com.investingapp.backend.model;

/** paycheck_cycles.voided_reason (spec 3.5, VARCHAR(32)). ADMIN is the admin void verb's (decision 16). */
public enum PaycheckVoidedReason {
    DEPOSIT_REMOVED, DEPOSIT_PENDING, AMOUNT_CHANGED, ADMIN
}
