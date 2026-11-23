package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserSession;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.EmailChangeService;
import com.investingapp.backend.service.EmailChangeService.Outcome;
import com.investingapp.backend.service.EmailChangeService.Status;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Change of email, in two steps (see EmailChangeService for why).
 *
 * The account acted on is always the signed-in one, read from the authenticated principal. The
 * request bodies carry no user id and no "current email"; anything of that kind a client sends is
 * simply not read.
 */
@RestController
@RequestMapping("/api/user/email-change")
public class EmailChangeController {

    /** What the two steps accept. Unknown fields are ignored. */
    public record StartRequest(String newEmail, String passkeySessionId, JsonNode credential) {
    }

    public record ConfirmRequest(String code) {
    }

    private final EmailChangeService emailChangeService;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final UserSessionRepository userSessionRepository;

    public EmailChangeController(EmailChangeService emailChangeService, UserRepository userRepository,
            JwtUtils jwtUtils, UserSessionRepository userSessionRepository) {
        this.emailChangeService = emailChangeService;
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
        this.userSessionRepository = userSessionRepository;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody(required = false) StartRequest request) {
        Long userId = signedInUserId();
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        if (request == null) {
            return refused(Status.REAUTH_REQUIRED);
        }

        Outcome outcome = emailChangeService.start(
                userId, request.newEmail(), request.passkeySessionId(), request.credential());
        if (!outcome.ok()) {
            return refused(outcome.status());
        }
        return ResponseEntity.ok(Map.of(
                "message", "We sent a code to your new email.",
                "expiresInMinutes", EmailChangeService.CODE_MINUTES));
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody(required = false) ConfirmRequest request,
            HttpServletRequest httpRequest) {
        Long userId = signedInUserId();
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        Outcome outcome = emailChangeService.confirm(userId, request == null ? null : request.code());
        if (!outcome.ok()) {
            return refused(outcome.status());
        }

        // The token names the user by email, so the old one stops working now. Hand back a new one
        // for this device, tied to the same session so it can still be revoked. Other devices have
        // to sign in again with the passkey.
        String currentToken = jwtUtils.parseJwt(httpRequest);
        Long sessionId = currentToken == null ? null : jwtUtils.getSessionIdFromJwtToken(currentToken);
        // Sign the account out everywhere else. Those devices hold tokens that name the old email;
        // ending their sessions means such a token can never be honoured again, even if another
        // account later takes the old address. They sign back in with the passkey.
        List<UserSession> others = userSessionRepository.findByUserIdAndActiveTrue(userId).stream()
                .filter(session -> !session.getId().equals(sessionId))
                .toList();
        others.forEach(session -> session.setActive(false));
        userSessionRepository.saveAll(others);

        User user = userRepository.findByEmailWithProgress(outcome.newEmail()).orElse(null);
        String nextStep = (user != null && user.getUserProgress() != null)
                ? user.getUserProgress().getNextStep() : "get-started";
        String newToken = jwtUtils.generateTokenFromUsername(
                outcome.newEmail(), sessionId, nextStep, user != null ? user.getPublicId() : null);

        return ResponseEntity.ok(Map.of(
                "message", "Email updated successfully",
                "newEmail", outcome.newEmail(),
                "jwtToken", newToken));
    }

    /** The signed-in user's id, from the principal. Null when nobody is signed in. */
    private Long signedInUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl principal) {
            return principal.getId();
        }
        return null;
    }

    private static ResponseEntity<ErrorCodeResponse> refused(Status status) {
        return switch (status) {
            case REAUTH_REQUIRED -> error(403, status, "Confirm it is you with your passkey to change your email.");
            case INVALID_EMAIL -> error(400, status, "Enter a valid email address.");
            case SAME_EMAIL -> error(400, status, "That is already your email.");
            case EMAIL_IN_USE -> error(409, status, "That email is already in use.");
            case EMAIL_UNAVAILABLE -> error(503, status, "Email changes are not available right now.");
            case TOO_MANY_REQUESTS -> error(429, status, "Too many codes requested. Try again in an hour.");
            case NO_PENDING_CHANGE -> error(400, status, "There is no email change waiting. Start again.");
            case CODE_EXPIRED -> error(400, status, "That code has expired. Start again.");
            case TOO_MANY_ATTEMPTS -> error(429, status, "Too many wrong codes. Start again.");
            case INVALID_CODE -> error(400, status, "That code is not right. Check it and try again.");
            case OK -> throw new IllegalStateException("OK is not a refusal");
        };
    }

    private static ResponseEntity<ErrorCodeResponse> error(int httpStatus, Status status, String message) {
        return ResponseEntity.status(httpStatus).body(new ErrorCodeResponse(status.name(), message));
    }
}
