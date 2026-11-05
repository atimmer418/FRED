package com.investingapp.backend.model;

/** paycheck_streams.reject_reason (VARCHAR(32)): the hard gate a stream failed (spec 4.2 to 4.4). */
public enum PaycheckRejectReason {
    NOT_INFLOW, NOT_DEPOSITORY, PLAID_TOMBSTONED, CADENCE_UNSUPPORTED, PFC_EXCLUDED, DESCRIPTOR_EXCLUDED, LOW_MEDIAN,
    STALE, BENEFITS_SIGNAL
}
