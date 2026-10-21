package com.investingapp.backend.controller;

import com.investingapp.backend.security.plaid.PlaidWebhookVerificationFilter;
import com.investingapp.backend.service.PlaidWebhookInboxService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/plaid/webhook: permitAll in SecurityConfig, verified by PlaidWebhookVerificationFilter.
 * Reads the verified bytes and JWT iat from the filter's request attributes, never @RequestBody,
 * stores the row and answers 200 within Plaid's 10 seconds. Any failure to store is a 503 so Plaid
 * retries; only the filter answers 401.
 */
@RestController
public class PlaidWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookController.class);

    @Autowired
    private PlaidWebhookInboxService inboxService;

    @PostMapping("/api/plaid/webhook")
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        Object body = request.getAttribute(PlaidWebhookVerificationFilter.ATTR_RAW_BODY);
        Object iat = request.getAttribute(PlaidWebhookVerificationFilter.ATTR_JWT_IAT);
        if (!(body instanceof byte[] rawBody) || !(iat instanceof Long jwtIat)) {
            logger.error("plaid.webhook.rejected reason=unverified_request_reached_controller");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            inboxService.record(rawBody, jwtIat);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            logger.error("plaid.webhook.rejected reason=not_stored detail=\"{}\"", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
