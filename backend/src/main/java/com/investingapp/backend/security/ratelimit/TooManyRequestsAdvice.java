package com.investingapp.backend.security.ratelimit;

import com.investingapp.backend.dto.MessageResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One answer for every limit: 429, how long to wait, and a sentence a person can read. */
@RestControllerAdvice
@org.springframework.core.annotation.Order(0) // ahead of the last-resort handler in web/UnexpectedErrorAdvice
public class TooManyRequestsAdvice {

    public static final String MESSAGE = "Too many requests. Please wait a few minutes and try again.";

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<MessageResponse> tooMany(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.rule().window().toSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MessageResponse(MESSAGE));
    }
}
