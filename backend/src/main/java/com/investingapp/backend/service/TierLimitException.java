package com.investingapp.backend.service;

/** Thrown when a user on a tier without multi-goal access tries to add another active goal. */
public class TierLimitException extends RuntimeException {
    public TierLimitException(String message) {
        super(message);
    }
}
