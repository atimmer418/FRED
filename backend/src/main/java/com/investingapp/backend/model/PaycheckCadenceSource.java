package com.investingapp.backend.model;

/** paycheck_streams.cadence_source (spec 3.3, VARCHAR(10)): derived from deposits, or Plaid's tiebreak. */
public enum PaycheckCadenceSource {
    OBSERVED, PLAID
}
