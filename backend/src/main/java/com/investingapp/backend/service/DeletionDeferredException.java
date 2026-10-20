package com.investingapp.backend.service;

/** Thrown by UserDeletionService.delete when money is still in flight; the user row stays. */
public class DeletionDeferredException extends RuntimeException {

    /** "FUNDING_INITIATED" or "SETTLEMENT_WINDOW". */
    public final String reason;

    public DeletionDeferredException(String reason) {
        super("User deletion deferred: " + reason);
        this.reason = reason;
    }
}
