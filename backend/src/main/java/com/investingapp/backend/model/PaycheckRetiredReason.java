package com.investingapp.backend.model;

/** paycheck_streams.retired_reason (VARCHAR(32)): succession, 120 days dormant, or the admin verb. */
public enum PaycheckRetiredReason {
    SUCCEEDED, DORMANT_120, ADMIN
}
