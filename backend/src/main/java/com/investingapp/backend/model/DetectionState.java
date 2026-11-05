package com.investingapp.backend.model;

/**
 * investment_schedules.detection_state (spec 3.2, VARCHAR(16)). Null until detection has run for
 * the user. Ruling R1 amends the spec's "null in MANUAL": shadow mode mirrors detection onto the
 * MANUAL row in every mode, and nothing reads it on the wire until Phase 2.
 */
public enum DetectionState {
    SEARCHING, NEEDS_CONSENT, UNDETECTABLE, PROVISIONAL, CONFIRMED, DORMANT, ITEM_BROKEN, REVOKED
}
