package com.investingapp.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;

/**
 * The last resort: a failure that no route and no other handler dealt with.
 *
 * <p>It is logged in full, and the caller gets a fixed sentence. Last in line on purpose, so every
 * more specific handler (a route's own, the rate limit's, the bad-request ones) goes first.
 *
 * <p>Some failures are not this handler's to answer and are passed straight back to Spring: the
 * ones Spring already knows the right status for (a missing parameter, an unsupported method, a
 * status a route chose), the ones security answers, and anything on a reply that has already
 * started, such as a chat stream or a caller who hung up.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class UnexpectedErrorAdvice {

    private static final Logger logger = LoggerFactory.getLogger(UnexpectedErrorAdvice.class);

    static final String MESSAGE = "Something went wrong. Please try again.";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        if (isSpringsToAnswer(e) || e instanceof IOException || response.isCommitted()) {
            throw e;
        }
        logger.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", MESSAGE, "error", MESSAGE));
    }

    /**
     * True for failures Spring MVC and Spring Security already turn into the right status: a
     * wrongly typed id in the address (400), a missing parameter (400), an unknown route (404), an
     * unsupported method (405), a status a route chose itself, a refused sign-in. Handing them
     * back keeps every one of those answers exactly as it was.
     *
     * <p>Decided by where the exception comes from, not by a list of names, so a kind nobody
     * thought of is still left to Spring. The exceptions from calling another service
     * (web.client) are not Spring's to answer: those are ours.
     */
    static boolean isSpringsToAnswer(Exception e) {
        if (e instanceof ErrorResponse || e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            return true;
        }
        String name = e.getClass().getName();
        if (name.startsWith("org.springframework.web.client.")) {
            return false;
        }
        return name.startsWith("org.springframework.web.") || name.startsWith("org.springframework.beans.")
                || name.startsWith("org.springframework.validation.") || name.startsWith("org.springframework.http.")
                || name.startsWith("org.springframework.core.convert.") || name.startsWith("org.springframework.security.");
    }
}
