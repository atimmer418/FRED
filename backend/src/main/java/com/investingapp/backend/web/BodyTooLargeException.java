package com.investingapp.backend.web;

import java.io.IOException;

/** Thrown while reading a request body that turns out to be larger than this route allows. */
public class BodyTooLargeException extends IOException {

    public BodyTooLargeException(long limit) {
        super("request body is larger than " + limit + " bytes");
    }
}
