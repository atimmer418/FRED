package com.investingapp.backend.paycheck;

/**
 * Why PaycheckDetectionService.evaluate ran (spec 4). Phase 1 runs the same full recompute for
 * every trigger and logs which one it was; INCREMENTAL is also the path T12's evaluateAndApply
 * takes before it applies new deposits to the open cycle.
 */
public enum EvaluationTrigger {
    /** History completed or an admin asked: every posted inflow event, from scratch. */
    FULL,
    /** A sync added deposits after history completed. */
    INCREMENTAL,
    /** /transactions/recurring/get changed the stream catalog. */
    STREAMS_CHANGED,
    /** The nightly reconcile or an admin evaluate verb. */
    RECONCILE
}
