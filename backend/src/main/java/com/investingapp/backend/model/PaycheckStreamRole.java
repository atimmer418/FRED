package com.investingapp.backend.model;

/** paycheck_streams.role (spec 3.3, VARCHAR(12); INTERRUPTION is exactly 12). A new stream is NONE. */
public enum PaycheckStreamRole {
    PRIMARY, SECONDARY, INTERRUPTION, NONE
}
