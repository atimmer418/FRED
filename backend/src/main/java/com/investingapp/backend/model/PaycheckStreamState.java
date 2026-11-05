package com.investingapp.backend.model;

/** paycheck_streams.state (spec 3.3, VARCHAR(12)). A new stream starts as CANDIDATE. */
public enum PaycheckStreamState {
    CANDIDATE, PROVISIONAL, CONFIRMED, DORMANT, RETIRED, REJECTED
}
