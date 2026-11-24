package com.investingapp.backend.paycheck.cycle;

public enum MatchDecision {
    CONFIRMABLE, LOGGED_STREAM_STATE, LATE_SEEN, AMOUNT_MISMATCH, GAP_TOO_SHORT, OUTSIDE_WINDOW_SECONDARY, PERIOD_TAKEN,
    STREAM_UNTRUSTED
}
