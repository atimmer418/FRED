// src/main/java/com/investingapp/backend/controller/AuthController.java
package com.investingapp.backend.controller;

import com.investingapp.backend.dto.JwtResponse;
import com.investingapp.backend.dto.LoginRequest;
import com.investingapp.backend.dto.MessageResponse; // You created this earlier
import com.investingapp.backend.dto.RegisterRequest;
import com.investingapp.backend.model.User;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.UserService;
import com.yubico.webauthn.AssertionRequest;

import jakarta.validation.Valid;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
// import org.springframework.security.core.GrantedAuthority; // For roles later
import org.springframework.web.bind.annotation.*;
import com.investingapp.backend.service.WebAuthnService;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse; // For parsing client response
import com.yubico.webauthn.data.AuthenticatorAttestationResponse; // For parsing client response
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.fasterxml.jackson.databind.ObjectMapper; // For parsing JSON from client if needed
import org.springframework.web.bind.annotation.RequestBody; // Make sure this is used for complex objects


// import java.util.List; // For roles later
// import java.util.stream.Collectors; // For roles later

@CrossOrigin(origins = "*", maxAge = 3600) // Allow all origins for now (dev)
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    @Autowired
    private WebAuthnService webAuthnService;
    
    @Autowired // For parsing JSON string from client if not automatically mapped
    private ObjectMapper objectMapper;

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    UserService userService; // You already have this

    @Autowired
    JwtUtils jwtUtils;

    @Autowired
    com.investingapp.backend.security.TokenCutoffRegistry tokenCutoffs;

    @Autowired
    com.investingapp.backend.repository.UserSessionRepository userSessionRepository;

    @GetMapping("/testuser")
    public ResponseEntity<String> userAccess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentPrincipalName = "N/A";
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal().toString())) {
            currentPrincipalName = authentication.getName(); // This will be the email
        } else if (authentication != null) {
            currentPrincipalName = "Anonymous or not fully authenticated: " + authentication.getPrincipal().toString();
        }

        String responseMessage = ">>> User Contents! Accessed by: " + currentPrincipalName;
        System.out.println("[HelloController] /api/test/user accessed. Response: " + responseMessage); // Server-side
                                                                                                       // log
        return ResponseEntity.ok(responseMessage);
    }

    // @PostMapping("/register")
    // public ResponseEntity<?> registerUser(@Valid @RequestBody RegisterRequest registerRequest) {
    //     try {
    //         logger.info("Attempting to register user: {}", registerRequest.getEmail());
    //         User registeredUser = userService.registerUser(registerRequest);
    //         logger.info("User {} registered successfully. ID: {}.", registeredUser.getEmail(), registeredUser.getId());

    //         // Respond with success and the user's email.
    //         // No JWT is issued yet.
    //         return ResponseEntity.ok(Map.of(
    //                 "message", "Registration successful.",
    //                 "email", registeredUser.getEmail(), // Send email back for frontend to use
    //                 "userId", registeredUser.getId() // Optional: send userId if useful for frontend state
    //         ));
    //     } catch (RuntimeException e) {
    //         logger.error("Registration failed for email {}: {}", registerRequest.getEmail(), e.getMessage());
    //         return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
    //     }
    // }

    // --- LOGIN FLOW (Handles users with and without 2FA enabled) ---
    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
        // ... (existing login logic that checks if 2FA is enabled and returns 202
        // Accepted if so) ...
        // This was the refined version from before.
        logger.info("Attempting to authenticate user: {}", loginRequest.getEmail());
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword()));

            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            User user = userService.getUserById(userDetails.getId()); // Fetch full User entity

            if (user != null) {
                boolean otpSent = true;
                if (otpSent) {
                    logger.info("2FA enabled for user {}. OTP sent for login verification.", user.getEmail());
                    return ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(Map.of(
                                    "message", "2FA required. OTP sent to your phone.",
                                    "email", user.getEmail(), // Send email for frontend to use in next step
                                    "twoFactorRequired", true));
                } else {
                    /* ... error handling ... */ }
            } else {
                // Password login never issues a token. Sign-in is by passkey, which starts a session.
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("Login failed."));
            }
        } catch (Exception e) { /* ... error handling ... */
            logger.error("Login failed for {}: {}", loginRequest.getEmail(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Login failed: " + e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new MessageResponse("Login process error.")); // Should not reach here
    }

    /**
     * Refresh JWT token endpoint.
     * Accepts a valid (not-yet-expired) JWT and returns a new one with a fresh expiration.
     * The "ns" (next-step) hint from the incoming token is carried forward at zero DB cost —
     * the "complete" case is monotone so it never goes stale-wrong across refreshes.
     * The frontend calls this proactively when the token is close to expiring and the user is active.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(jakarta.servlet.http.HttpServletRequest request) {
        // The request already passed through AuthTokenFilter, so if we get here
        // the user is authenticated with a valid JWT
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal().toString())) {
            logger.warn("Token refresh attempted without valid authentication");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("No valid token to refresh"));
        }

        String username = authentication.getName();

        // Carry the ns hint forward from the incoming token (zero DB cost).
        String oldToken = jwtUtils.parseJwt(request);
        String ns = (oldToken != null) ? jwtUtils.getNextStepFromJwtToken(oldToken) : null;
        // Look up the user first: the refreshed token carries the pid (analytics identity) claim.
        User user = userService.getUserByEmail(username);
        if (user == null) {
            logger.error("Token refresh failed: user not found for email {}", username);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("User not found"));
        }

        // Account recovery may have ended this token after it passed the filter. Look again here,
        // as late as possible, so a refresh in flight cannot turn a dead token into a live one.
        if (oldToken == null || tokenCutoffs.isEnded(user.getId(), jwtUtils.getIssuedAtFromToken(oldToken))) {
            logger.warn("Token refresh refused for user {}: token was ended by account recovery", user.getId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Session has been revoked"));
        }

        // A sign-in on the same device may have replaced this session after the token passed the
        // filter. A new token for a session that is already over would be dead on arrival, and the
        // app would store it over the good one it just got.
        Long sessionId = jwtUtils.getSessionIdFromJwtToken(oldToken);
        if (sessionId == null || !userSessionRepository.existsByIdAndUserIdAndActiveTrue(sessionId, user.getId())) {
            logger.info("Token refresh refused for user {}: its session is no longer active", user.getId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Session has been revoked"));
        }

        // The new token stays in the same session and keeps the same session end. Refreshing keeps
        // an active user signed in; it never extends how long one passkey sign-in lasts. When the
        // session has run out (or the token is from before sessions had an end), the answer is
        // 401 and the app asks for the passkey again.
        String newToken = jwtUtils.continueSessionToken(oldToken, username, ns, user.getPublicId()).orElse(null);
        if (newToken == null) {
            logger.info("Token refresh refused for user {}: the session has ended", user.getId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Session has ended. Sign in again."));
        }

        logger.info("JWT token refreshed for user: {}", username);
        
        // A HashMap, not Map.of: users.public_id is nullable until the migration that tightens it,
        // and Map.of throws on a null value, which would turn a missing public_id into a 500 on
        // every token refresh for that user.
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("success", true);
        body.put("jwtToken", newToken);
        body.put("id", user.getId());
        body.put("publicId", user.getPublicId());
        body.put("analyticsOptOut", !Boolean.FALSE.equals(user.getAnalyticsOptOut()));
        body.put("email", user.getEmail());

        return ResponseEntity.ok(body);
    }

}