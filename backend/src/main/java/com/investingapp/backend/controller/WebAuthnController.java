// src/main/java/com/investingapp/backend/controller/WebAuthnController.java
package com.investingapp.backend.controller;

import com.investingapp.backend.security.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;

// imports below are for simulating passkey
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserProgress;
import org.springframework.http.HttpStatus;
import java.util.Map;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.investingapp.backend.repository.UserRepository;  // Your UserRepository interface
import org.springframework.security.core.userdetails.UserDetailsService;
import com.investingapp.backend.security.jwt.JwtUtils;  // Your JWT helper class

import com.investingapp.backend.dto.RegistrationFinishRequest;
import com.investingapp.backend.dto.RegistrationFinishResponse; // <-- IMPORT YOUR DTO
import com.investingapp.backend.dto.RegistrationStartRequest;
import com.investingapp.backend.dto.RegistrationStartResponse;
import com.investingapp.backend.security.DevToolsKeyValidator;
import com.investingapp.backend.service.AccountRecoveryService;
import com.investingapp.backend.service.SimulatedAttestationService;
import com.investingapp.backend.service.WebAuthnService;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/passkey")
@CrossOrigin(origins = "*")
public class WebAuthnController {

    private static final Logger logger = LoggerFactory.getLogger(WebAuthnController.class);

    private final WebAuthnService webAuthnService;
    private final Cache<String, PublicKeyCredentialCreationOptions> challengeCache;
    
    // Cache for authentication challenges (email-less authentication)
    private final Cache<String, PublicKeyCredentialRequestOptions> authChallengeCache;
    
    // ObjectMapper for JSON serialization - injected from Spring configuration
    private final ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository; // Your user repo interface

    @Autowired
    private UserDetailsService userDetailsService; // Usually your Spring Security user details service

    @Autowired
    private JwtUtils jwtUtils; // Your JWT utility/service class

    @Autowired
    private com.investingapp.backend.repository.PasskeyCredentialRepository passkeyCredentialRepository;

    @Autowired
    private AccountRecoveryService accountRecoveryService;

    static final String RECOVERY_EXPIRED_MESSAGE = "Your recovery session has ended. Please start again.";

    /**
     * Null outside the local/dev/test/ci profiles, where the bean is not registered at all. A null
     * here is what makes SIMULATED_ATTESTATION impossible in prod.
     */
    @Autowired(required = false)
    private SimulatedAttestationService simulatedAttestationService;

    @Autowired(required = false)
    private com.investingapp.backend.service.UserSessionService userSessionService;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    public WebAuthnController(WebAuthnService webAuthnService,
            Cache<String, PublicKeyCredentialCreationOptions> challengeCache,
            Cache<String, PublicKeyCredentialRequestOptions> authChallengeCache,
            ObjectMapper objectMapper) {
        this.webAuthnService = webAuthnService;
        this.challengeCache = challengeCache;
        this.authChallengeCache = authChallengeCache;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/register/start")
    public ResponseEntity<?> startRegistration(@Valid @RequestBody RegistrationStartRequest registrationRequest,
            HttpServletRequest request) {
        // Per email, whatever address it comes from: the per-address limit in the filter can be
        // dodged by changing address, this one cannot.
        rateLimiter.require(RateLimiter.Rule.SIGN_UP_PER_EMAIL, registrationRequest.getEmail());
        logger.info("Received passkey registration start request for email: {}", registrationRequest.getEmail());
        String origin = request.getHeader("Origin");
        logger.info("Received request from ORIGIN: {}", origin);
        try {
            PublicKeyCredentialCreationOptions options = webAuthnService.startRegistrationFlow(
                    registrationRequest.getEmail(),
                    registrationRequest.getPlanId(),
                    registrationRequest.getTimeToFI(),
                    registrationRequest.getTargetPortfolio(),
                    registrationRequest.getRetirementIncome(),
                    registrationRequest.getMonthlyInvestment());
            challengeCache.put(registrationRequest.getEmail(), options);
            logger.info("Registration options stored in cache for user: {}", registrationRequest.getEmail());
            
            // Use the WebAuthn library's built-in JSON serialization
            String optionsJson = options.toCredentialsCreateJson();
            
            // Parse the JSON to extract just the publicKey part
            JsonNode fullResponse = objectMapper.readTree(optionsJson);
            JsonNode publicKeyNode = fullResponse.get("publicKey");
            String publicKeyJson = objectMapper.writeValueAsString(publicKeyNode);
            
            return ResponseEntity.ok(new RegistrationStartResponse(publicKeyJson));
        } catch (IllegalArgumentException e) {
            logger.warn("Registration failed for email {}: {}", registrationRequest.getEmail(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Email is already taken.");
        } catch (Exception e) {
            logger.error("Failed to generate registration options for user: {}", registrationRequest.getEmail(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error generating registration options.");
        }
    }

    /**
     * Starts registering the one new passkey that follows account recovery.
     *
     * SECURITY: the only thing that opens this is the one-time recovery token from
     * POST /api/auth/recovery/verify. A session token does not, whatever account it is for. It used
     * to, and that let a stolen token add the thief's own passkey to an account, either straight
     * away or in the moments after the real owner recovered it.
     */
    @PostMapping("/register/recovery/start")
    public ResponseEntity<?> startRecoveryRegistration(@RequestBody(required = false) Map<String, Object> body) {
        Object tokenValue = (body != null) ? body.get("recoveryToken") : null;
        String recoveryToken = (tokenValue instanceof String text) ? text : null;

        Long userId = accountRecoveryService.userForRecoveryToken(recoveryToken).orElse(null);
        User user = (userId != null) ? userRepository.findById(userId).orElse(null) : null;
        if (user == null && accountRecoveryService.alreadyFinished(recoveryToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("This account already has a passkey. Sign in with it.");
        }
        if (user == null) {
            logger.warn("Refused recovery passkey registration: no live recovery token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(RECOVERY_EXPIRED_MESSAGE);
        }

        // Recovery removed every passkey, so there should be none. If one is there anyway, stop.
        if (passkeyCredentialRepository.countByUser_Id(userId) > 0) {
            logger.warn("Refused recovery passkey registration for user {}: account already has a passkey", userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("This account already has a passkey. Sign in with it.");
        }

        try {
            PublicKeyCredentialCreationOptions options = webAuthnService.startRecoveryRegistrationFlow(user.getEmail());
            // Kept with the recovery token, not in the sign-up challenge cache, so only a finish
            // request that carries the same token can use it.
            if (!accountRecoveryService.rememberPasskeyChallenge(recoveryToken, options)) {
                logger.warn("Refused recovery passkey registration for user {}: recovery token used up", userId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(RECOVERY_EXPIRED_MESSAGE);
            }

            String optionsJson = options.toCredentialsCreateJson();
            JsonNode fullResponse = objectMapper.readTree(optionsJson);
            JsonNode publicKeyNode = fullResponse.get("publicKey");
            String publicKeyJson = objectMapper.writeValueAsString(publicKeyNode);

            return ResponseEntity.ok(new RegistrationStartResponse(publicKeyJson));
        } catch (Exception e) {
            logger.error("Failed to generate recovery registration options for user {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error generating registration options");
        }
    }

    @PostMapping("/register/finish")
    public ResponseEntity<RegistrationFinishResponse> finishRegistration(
            @Valid @RequestBody RegistrationFinishRequest finishRequest,
            HttpServletRequest httpRequest) {
        logger.info("Received passkey registration finish request for email: {}", finishRequest.getEmail());
        String email = finishRequest.getEmail();

        JsonNode credential = finishRequest.getCredential();
        String attestationObject = credential.path("response").path("attestationObject").asText();

        if ("SIMULATED_ATTESTATION".equals(attestationObject)) {
            // Dev tool: the service bean only exists in the local/dev/test/ci profiles, and it also
            // requires the dev-tools key. Anywhere else this is a plain rejection.
            RegistrationFinishResponse simulated = (simulatedAttestationService == null)
                    ? null
                    : simulatedAttestationService.register(
                            email, httpRequest.getHeader(DevToolsKeyValidator.HEADER));

            if (simulated == null) {
                logger.warn("Rejected SIMULATED_ATTESTATION registration for email: {}", email);
                return ResponseEntity.badRequest().body(new RegistrationFinishResponse(
                        false,
                        "Simulated registration is not available.",
                        null, null, email));
            }

            return ResponseEntity.ok(simulated);
        }

        String recoveryToken = finishRequest.getRecoveryToken();
        boolean afterRecovery = recoveryToken != null && !recoveryToken.isBlank();

        PublicKeyCredentialCreationOptions options;
        if (afterRecovery) {
            // The passkey that follows account recovery. The challenge and the account both come
            // from the recovery token; the email in the request is not trusted for either.
            AccountRecoveryService.PendingPasskey pending =
                    accountRecoveryService.takePasskeyChallenge(recoveryToken).orElse(null);
            User recovered = (pending != null) ? userRepository.findById(pending.userId()).orElse(null) : null;
            if (recovered == null) {
                logger.warn("Refused recovery passkey registration finish: no waiting challenge for this recovery token");
                return ResponseEntity.badRequest().body(new RegistrationFinishResponse(
                        false, RECOVERY_EXPIRED_MESSAGE, null, null, email));
            }
            options = pending.options();
            email = recovered.getEmail();
            accountRecoveryService.waitUntilNewTokensAreGood(pending.userId());
        } else {
            // A new sign-up. Taken in one step, so one challenge can finish one registration.
            options = challengeCache.asMap().remove(email);

            // SECURITY: this path is for an account that has never had a passkey. An account that
            // has one gets another only through account recovery, which needs the recovery token.
            Long existingUserId = userRepository.findByEmail(email).map(User::getId).orElse(null);
            if (options != null && existingUserId != null
                    && passkeyCredentialRepository.countByUser_Id(existingUserId) > 0) {
                logger.warn("Refused sign-up passkey registration for user {}: account already has a passkey", existingUserId);
                options = null;
            }
        }

        if (options == null) {
            logger.warn("No registration challenge found or challenge expired for email: {}", email);
            RegistrationFinishResponse errorResponse = new RegistrationFinishResponse(
                    false,
                    "No registration challenge found or challenge expired. Please start over.",
                    null, null, email);
            return ResponseEntity.badRequest().body(errorResponse);
        }

        RegistrationFinishResponse serviceResponse = webAuthnService.finishRegistrationFlow(
                email,
                finishRequest.getCredential(),
                options);

        if (serviceResponse.isSuccess() && afterRecovery) {
            // The new passkey is in place: the recovery token is used up.
            accountRecoveryService.recoveryFinished(recoveryToken);
        }

        if (serviceResponse.isSuccess()) {
            logger.info("Passkey registration and login successful for: {}", email);
            return ResponseEntity.ok(serviceResponse);
        } else {
            logger.warn("Passkey registration or subsequent login failed for: {}. Reason: {}", email,
                    serviceResponse.getMessage());
            // serviceResponse already contains success=false and the message
            return ResponseEntity.badRequest().body(serviceResponse);
        }
    }

    // === PASSKEY AUTHENTICATION ENDPOINTS ===
    
    /**
     * Start passkey authentication.
     * We ALWAYS use Usernameless (Discoverable) flow here to ensure robustness.
     * 
     * Why?
     * If we use Step-Up (Targeted) auth by providing the username, the backend populates 'allowCredentials'
     * with the specific key IDs registered in the DB.
     * If the user's current device doesn't have those specific keys (e.g. different device, cleared storage, 
     * or dev/prod DB mismatch), the browser's navigator.credentials.get() will fail immediately 
     * with NotAllowedError, often without even showing a UI.
     * 
     * By using Usernameless flow (empty allowCredentials), the browser will:
     * 1. Look for any keys for this RP ID.
     * 2. If found, let the user select one.
     * 3. If not found, ask the user to insert/touch a security key.
     * 
     * We then enforce the identity check in the /finish endpoint to ensure the user 
     * re-authenticating matches the currently logged-in user.
     */
    @PostMapping("/authenticate/start")
    public ResponseEntity<?> startAuthentication(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        logger.info("Authentication request from ORIGIN: {}", origin);
        
        try {
            String email = (body != null) ? (String) body.get("email") : null;
            
            PublicKeyCredentialRequestOptions options;
            if (email != null && !email.isBlank() && Boolean.TRUE.equals(userRepository.existsByEmail(email))) {
                // Account-specific flow: restricts allowCredentials to this user's passkeys
                logger.info("Starting account-specific passkey authentication for: {}", email);
                options = webAuthnService.startAuthenticationFlow(email);
            } else {
                // No email, or an email no account has. The second case is a device that still
                // remembers an address from before an email change: it must still be able to sign
                // in, and the passkey itself says who the user is.
                // Usernameless (discoverable) flow: any passkey on the device
                logger.info("Starting discoverable passkey authentication (Usernameless flow)");
                options = webAuthnService.startAuthenticationFlow();
            }
            
            // Generate a temporary session ID to cache the challenge
            String sessionId = java.util.UUID.randomUUID().toString();
            authChallengeCache.put(sessionId, options);
            
            logger.info("Authentication challenge generated and cached with session ID: {}", sessionId);
            
            // Use the WebAuthn library's built-in JSON serialization for proper binary data handling
            String optionsJson = options.toCredentialsGetJson();
            
            // Return both the options and session ID to frontend
            return ResponseEntity.ok(Map.of(
                "requestOptions", optionsJson,
                "sessionId", sessionId
            ));
            
        } catch (Exception e) {
            logger.error("Unexpected error during authentication start: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Authentication start failed.");
        }
    }

    /**
     * Finish passkey authentication - discovers user from passkey response
     */
    @PostMapping("/authenticate/finish")
    public ResponseEntity<?> finishAuthentication(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        String sessionId = (String) request.get("sessionId");
        Object credentialObj = request.get("credential");
        
        logger.info("Processing passkey authentication finish for session: {}", sessionId);
        
        if (sessionId == null || credentialObj == null) {
            return ResponseEntity.badRequest().body("Missing sessionId or credential");
        }
        
        // Convert credential object to JsonNode
        JsonNode credentialResponse;
        try {
            credentialResponse = objectMapper.valueToTree(credentialObj);
        } catch (Exception e) {
            logger.error("Failed to convert credential to JsonNode: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid credential format");
        }
        
        // Get original challenge from cache
        PublicKeyCredentialRequestOptions originalOptions = authChallengeCache.getIfPresent(sessionId);
        authChallengeCache.invalidate(sessionId); // Invalidate immediately
        
        if (originalOptions == null) {
            logger.warn("No authentication challenge found for session: {}", sessionId);
            return ResponseEntity.badRequest().body("Authentication challenge expired or not found");
        }
        
        try {
            // Convert PublicKeyCredentialRequestOptions to AssertionRequest
            com.yubico.webauthn.AssertionRequest assertionRequest =
                com.investingapp.backend.util.WebAuthnConversionUtil.toAssertionRequest(originalOptions);
            WebAuthnService.AuthenticationFinishResponse serviceResponse = 
                webAuthnService.finishAuthenticationFlow(credentialResponse, assertionRequest, httpRequest);
            
            if (serviceResponse.isSuccess()) {
                // Enforce Step-Up Authentication Identity Check
                String authHeader = httpRequest.getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);
                    if (jwtUtils.validateJwtToken(token)) {
                        String loggedInEmail = jwtUtils.getUserNameFromJwtToken(token);
                        String authenticatedEmail = serviceResponse.getEmail();
                        
                        // A token whose email no account has any more (it was issued before an
                        // email change) names nobody, so there is no logged-in account to match.
                        // The passkey alone decides who this is, as in a normal sign-in.
                        boolean tokenNamesAnAccount = Boolean.TRUE.equals(userRepository.existsByEmail(loggedInEmail));
                        if (tokenNamesAnAccount && !loggedInEmail.equalsIgnoreCase(authenticatedEmail)) {
                            logger.warn("Step-Up Auth Mismatch! Logged in: {}, Authenticated: {}", loggedInEmail, authenticatedEmail);
                            // The passkey was good, so a session was started for its owner, but its
                            // token is not being handed out. End it; nobody should hold it.
                            if (userSessionService != null) {
                                userSessionService.end(jwtUtils.getSessionIdFromJwtToken(serviceResponse.getJwtToken()));
                            }
                            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body("Authentication failed: You must authenticate with the currently logged-in account.");
                        }
                        logger.info("Step-Up Auth Verified: {} matches logged-in user.", authenticatedEmail);
                    }
                }

                logger.info("Passkey authentication successful for user: {}", serviceResponse.getEmail());
                return ResponseEntity.ok(serviceResponse);
            } else {
                logger.warn("Passkey authentication failed: {}", serviceResponse.getMessage());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(serviceResponse);
            }
            
        } catch (Exception e) {
            logger.error("Unexpected error during authentication finish: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Authentication finish failed");
        }
    }
}