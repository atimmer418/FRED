package com.investingapp.backend.service;

/** A second public-token exchange for the same user inside the 60 second window. Maps to 409. */
public class LinkInProgressException extends RuntimeException {

    public LinkInProgressException() {
        super("A bank link is already in progress");
    }
}
