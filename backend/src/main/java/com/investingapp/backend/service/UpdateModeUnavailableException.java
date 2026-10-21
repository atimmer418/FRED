package com.investingapp.backend.service;

/** Plaid will not mint an update-mode token, or there is no Item to update: the client falls back to link. */
public class UpdateModeUnavailableException extends RuntimeException {
    public UpdateModeUnavailableException(String message) {
        super(message);
    }
}
