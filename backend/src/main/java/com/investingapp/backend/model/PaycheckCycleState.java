package com.investingapp.backend.model;

/**
 * paycheck_cycles.state (spec 3.5, VARCHAR(10)). A new cycle is OPEN. Phase 1 has no CONFIRMED
 * writer: under shadow a matched deposit closes the cycle SKIPPED with SHADOW_CONFIRMED (ruling R7).
 */
public enum PaycheckCycleState {
    OPEN, CONFIRMED, QUEUED, SKIPPED, VOIDED, MISSED
}
