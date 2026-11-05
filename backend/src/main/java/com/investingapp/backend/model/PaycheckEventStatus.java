package com.investingapp.backend.model;

/** paycheck_events.status (spec 3.4, VARCHAR(10); SUPERSEDED is exactly 10). A new event is ACTIVE. */
public enum PaycheckEventStatus {
    ACTIVE, REMOVED, MODIFIED, SUPERSEDED
}
