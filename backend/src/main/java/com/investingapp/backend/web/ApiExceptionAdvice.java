package com.investingapp.backend.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * The answers every route shares for two kinds of bad request.
 *
 * <p>Both keys, {@code message} and {@code error}, carry the same sentence, because the app's
 * screens read one or the other.
 */
@RestControllerAdvice
@org.springframework.core.annotation.Order(0)
public class ApiExceptionAdvice {

    private static final Logger logger = LoggerFactory.getLogger(ApiExceptionAdvice.class);

    /** A refusal written for the user: shown as it is. */
    @ExceptionHandler(UserFacingException.class)
    public ResponseEntity<Map<String, Object>> userFacing(UserFacingException e) {
        return ResponseEntity.badRequest().body(body(e.getMessage()));
    }

    /**
     * A body that could not be read: broken JSON, a value of the wrong type, or a body cut off for
     * being too large. The parser's own message quotes the request and names Java classes, so it
     * is logged, not sent.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        if (RequestBodySizeFilter.causedByBodyTooLarge(e)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body(RequestBodySizeFilter.MESSAGE));
        }
        logger.warn("Unreadable request body: {}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(body("That request could not be read."));
    }

    private static Map<String, Object> body(String message) {
        return Map.of("success", false, "message", message, "error", message);
    }
}
