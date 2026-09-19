// src/main/java/com/investingapp/backend/service/WebAuthnService.java
package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.investingapp.backend.dto.RegistrationFinishResponse;
import com.investingapp.backend.model.PasskeyCredential;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserProgress;
import com.investingapp.backend.model.UserSession;
import com.investingapp.backend.repository.PasskeyCredentialRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserProgressRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.security.services.UserDetailsServiceImpl;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class WebAuthnService {

    private static final Logger logger = LoggerFactory.getLogger(WebAuthnService.class);
    private final SecureRandom random = new SecureRandom();

    private final RelyingParty relyingParty;
    private final UserRepository userRepository;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final UserProgressRepository userProgressRepository;
    private final JwtUtils jwtUtils;
    private final UserDetailsServiceImpl userDetailsService;
    private final PlaidService plaidService;
    private final EncryptionService encryptionService;
    private final UserService userService;

    @Autowired
    public WebAuthnService(RelyingParty relyingParty,
                           UserRepository userRepository,
                           PasskeyCredentialRepository passkeyCredentialRepository,
                           UserProgressRepository userProgressRepository,
                           JwtUtils jwtUtils,
                           UserDetailsServiceImpl userDetailsService,
                           PlaidService plaidService,
                           EncryptionService encryptionService,
                           UserService userService) {
        this.relyingParty = relyingParty;
        this.userRepository = userRepository;
        this.passkeyCredentialRepository = passkeyCredentialRepository;
        this.userProgressRepository = userProgressRepository;
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
        this.plaidService = plaidService;
        this.encryptionService = encryptionService;
        this.userService = userService;
    }

    @Transactional
    public PublicKeyCredentialCreationOptions startRegistrationFlow(String email, String planId, String timeToFI, Double targetPortfolio, Double retirementIncome, Double monthlyInvestment) {
        logger.info("Starting passkey registration for email: {}, planId: {}, timeToFI: {}, targetPortfolio: {}, retirementIncome: {}, monthlyInvestment: {}", email, planId, timeToFI, targetPortfolio, retirementIncome, monthlyInvestment);

        // Check if user already exists
        if (userRepository.findByEmail(email).isPresent()) {
            logger.warn("Registration attempt failed - email already exists: {}", email);
            throw new IllegalArgumentException("Email is already taken");
        }
        
        // Create new user since email is available
        logger.info("Email available, creating new user for: {}", email);
        User user = new User(email);
        
        // Generate user handle
        byte[] handleBytes = new byte[16];
        random.nextBytes(handleBytes);
        user.setUserHandle(Base64.getUrlEncoder().withoutPadding().encodeToString(handleBytes));
        
        // Generate and set unique referral code using email as base
        user.setReferralCode(userService.generateUniqueReferralCode(email));

        // Store FI plan data (add these fields to your User model)
        user.setPlanId(planId);
        user.setTimeToFI(timeToFI);
        user.setTargetPortfolio(targetPortfolio);
        user.setRetirementIncome(retirementIncome);
        user.setMonthlyInvestment(monthlyInvestment);
        
        // Create and associate UserProgress (will be saved with cascade)
        UserProgress userProgress = new UserProgress();
        user.setUserProgress(userProgress);
        
        user = userRepository.save(user);
        logger.info("New user created successfully for email: {}", email);

        UserIdentity userIdentity = UserIdentity.builder()
                .name(user.getEmail())
                .displayName(user.getEmail())
                .id(PasskeyCredential.base64UrlToByteArray(user.getUserHandle()))
                .build();

        StartRegistrationOptions optionsToPassToRp = StartRegistrationOptions.builder()
            .user(userIdentity)
            .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.PREFERRED)
                .userVerification(UserVerificationRequirement.PREFERRED)
                .build())
            .build();
        
        return relyingParty.startRegistration(optionsToPassToRp);
    }

    @Transactional(readOnly = true)
    public PublicKeyCredentialCreationOptions startRecoveryRegistrationFlow(String email) {
        logger.info("Starting recovery passkey re-registration for existing user: {}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email));

        UserIdentity userIdentity = UserIdentity.builder()
                .name(user.getEmail())
                .displayName(user.getEmail())
                .id(PasskeyCredential.base64UrlToByteArray(user.getUserHandle()))
                .build();

        StartRegistrationOptions optionsToPassToRp = StartRegistrationOptions.builder()
                .user(userIdentity)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                    .residentKey(ResidentKeyRequirement.PREFERRED)
                    .userVerification(UserVerificationRequirement.PREFERRED)
                    .build())
                .build();

        return relyingParty.startRegistration(optionsToPassToRp);
    }

    @Transactional
    public RegistrationFinishResponse finishRegistrationFlow(String userEmail, JsonNode registrationJsonFromClient, PublicKeyCredentialCreationOptions requestOptionsFromServer) {
        logger.info("Finishing passkey registration for email: {}", userEmail);
        RegistrationResult registrationResult; 

        try {
            User user = userRepository.findByEmail(userEmail)
                    .orElseThrow(() -> new RegistrationFailedException(new IllegalArgumentException("User not found during finish flow: " + userEmail)));

            PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc =
                PublicKeyCredential.parseRegistrationResponseJson(registrationJsonFromClient.toString());

            // If relyingParty.finishRegistration() completes without throwing an exception,
            // the cryptographic checks and registration process are considered successful by the Yubico library.
            registrationResult = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(requestOptionsFromServer)
                    .response(pkc)
                    .build());

            // --- If we reach here, Yubico library considers the registration successful. ---
            // The RegistrationResult object now contains details of the *successful* registration.
            // No need to check an "isSuccess()" method on registrationResult for this primary success determination.
            // The fact that no exception was thrown is the key indicator from the Yubico library.

            PasskeyCredential newCredential = new PasskeyCredential(
                    user,
                    PasskeyCredential.byteArrayToBase64Url(registrationResult.getKeyId().getId()),
                    PasskeyCredential.byteArrayToBase64Url(registrationResult.getPublicKeyCose()),
                    registrationResult.getSignatureCount(),
                    "public-key" // Type or description
            );
            newCredential.setFriendlyName("Passkey - " + LocalDateTime.now().withNano(0));
            newCredential.setLastUsedDate(LocalDateTime.now());
            passkeyCredentialRepository.save(newCredential);
            logger.info("Passkey successfully registered for user {} with credential ID: {}", userEmail, newCredential.getExternalId());

            // Mark authFinalize complete transactionally with credential save so the
            // client-side progress GET always sees the flag set, even if the separate
            // frontend PUT to /user/progress fails or races with the GET.
            UserProgress userProgress = user.getUserProgress();
            if (userProgress != null && !userProgress.isAuthFinalizeCompleted()) {
                userProgress.setAuthFinalizeCompleted(true);
                userProgressRepository.save(userProgress);
                logger.info("Marked authFinalizeCompleted=true for user {}", userEmail);
            }

            // --- User is registered with passkey, now generate JWT ---
            UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            logger.info("User {} authenticated via passkey registration and set in SecurityContext.", userEmail);

            String nsReg = (user.getUserProgress() != null) ? user.getUserProgress().getNextStep() : "get-started";
            String jwt = jwtUtils.generateJwtToken(authentication, nsReg);
            logger.info("JWT generated for user {} (ns={}).", userEmail, nsReg);

            return new RegistrationFinishResponse(
                    true, // Success is true because no exception was thrown and we saved the credential
                    "Passkey registration successful. User logged in.",
                    jwt,
                    user.getId(),
                    user.getEmail(),
                    user.getPublicId()
            );

        } catch (RegistrationFailedException | IOException e) {
            // This catches exceptions from relyingParty.finishRegistration(), JSON parsing, or user not found.
            logger.error("Passkey registration failed for user {}: {}", userEmail, e.getMessage(), e);
            // Optionally, you could provide more specific error messages based on the exception type if needed.
            return new RegistrationFinishResponse(false, "Passkey registration failed: " + e.getMessage(), null, null, userEmail);
        } catch (Exception e) { // Catch any other unexpected errors during the process
            logger.error("Unexpected error during passkey registration finish for user {}: {}", userEmail, e.getMessage(), e);
            return new RegistrationFinishResponse(false, "An unexpected error occurred during registration.", null, null, userEmail);
        }
    }

    /**
     * Start authentication flow - NO EMAIL REQUIRED!
     * WebAuthn with discoverable credentials can identify the user from the passkey itself
     */
    public PublicKeyCredentialRequestOptions startAuthenticationFlow() {
        logger.info("Starting usernameless passkey authentication flow");
        
        StartAssertionOptions options = StartAssertionOptions.builder()
            .userVerification(UserVerificationRequirement.PREFERRED)
            // No allowCredentials - this enables usernameless/discoverable credential authentication
            .build();
            
        AssertionRequest assertionRequest = relyingParty.startAssertion(options);
        PublicKeyCredentialRequestOptions requestOptions = assertionRequest.getPublicKeyCredentialRequestOptions();
        logger.info("Authentication challenge generated for usernameless login");
        
        return requestOptions;
    }

    /**
     * Start authentication flow for a specific user (Step-Up Authentication)
     * This populates allowCredentials to ensure we authenticate the correct user
     * and supports non-discoverable credentials.
     */
    public PublicKeyCredentialRequestOptions startAuthenticationFlow(String email) {
        logger.info("Starting passkey authentication flow for user: {}", email);
        
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
            
        // Start assertion with username to populate allowCredentials
        StartAssertionOptions options = StartAssertionOptions.builder()
            .username(email)
            .userVerification(UserVerificationRequirement.PREFERRED)
            .build();
            
        AssertionRequest assertionRequest = relyingParty.startAssertion(options);
        PublicKeyCredentialRequestOptions requestOptions = assertionRequest.getPublicKeyCredentialRequestOptions();
        logger.info("Authentication challenge generated for user: {}", email);
        
        return requestOptions;
    }

    @Autowired
    private UserSessionRepository userSessionRepository;

    /**
     * Finish authentication flow - discovers user from passkey response
     */
    @Transactional
    public AuthenticationFinishResponse finishAuthenticationFlow(JsonNode credentialResponse, AssertionRequest originalRequestOptions, jakarta.servlet.http.HttpServletRequest httpRequest) {
        logger.info("Processing passkey authentication finish");
        
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> credential =
                PublicKeyCredential.parseAssertionResponseJson(credentialResponse.toString());
            
            FinishAssertionOptions options = FinishAssertionOptions.builder()
                .request(originalRequestOptions)
                .response(credential)
                .build();
            
            AssertionResult result = relyingParty.finishAssertion(options);
            
            if (result.isSuccess()) {
                // Get user handle from the assertion to identify the user
                ByteArray userHandle = result.getCredential().getUserHandle();
                String userHandleStr = userHandle.getBase64Url();
                
                logger.info("Passkey authentication successful for user handle: {}", userHandleStr);
                
                // Find user by userHandle
                User user = userRepository.findByUserHandle(userHandleStr).orElse(null);
                if (user == null) {
                    logger.warn("User not found for user handle: {}", userHandleStr);
                    return new AuthenticationFinishResponse(false, "User not found", null, null, null);
                }
                
                logger.info("User identified from passkey: {}", user.getEmail());
                
                // Create authentication and set in security context
                UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                
                // Update device ID if provided (for manual re-auth scenarios)
                String deviceId = httpRequest.getHeader("X-Device-ID");
                if (deviceId != null && !deviceId.isEmpty()) {
                    String currentDeviceId = user.getDeviceId();
                    if (!deviceId.equals(currentDeviceId)) {
                        user.setDeviceId(deviceId);
                        userRepository.save(user);
                        logger.info("Updated device ID for user {} from {} to {} (manual re-auth)", 
                                   user.getEmail(), 
                                   currentDeviceId != null ? currentDeviceId.substring(0, Math.min(8, currentDeviceId.length())) + "..." : "null",
                                   deviceId.substring(0, Math.min(8, deviceId.length())) + "...");
                    } else {
                        logger.debug("Device ID unchanged for user {} during re-auth", user.getEmail());
                    }
                } else {
                    logger.debug("No device ID header provided during authentication for user {}", user.getEmail());
                }

                // Create or Update User Session
                UserSession session = null;
                try {
                    // Try to find existing session for this device
                    if (deviceId != null) {
                        session = userSessionRepository.findByUserIdAndDeviceId(user.getId(), deviceId).orElse(null);
                    }
                    
                    if (session == null) {
                        session = new UserSession();
                        session.setUser(user);
                        session.setDeviceId(deviceId);
                    }
                    
                    // Update fields
                    // Use X-Device-Name header if available (from Capacitor), otherwise fallback to User-Agent
                    String deviceName = httpRequest.getHeader("X-Device-Name");
                    String userAgent = httpRequest.getHeader("User-Agent");
                    
                    if (deviceName != null && !deviceName.isEmpty()) {
                        session.setDeviceInfo(deviceName);
                    } else {
                        session.setDeviceInfo(userAgent != null ? userAgent : "Unknown Device");
                    }
                    
                    session.setIpAddress(httpRequest.getRemoteAddr());
                    session.setActive(true);
                    session.setLastActive(java.time.LocalDateTime.now());
                    
                    session = userSessionRepository.save(session);
                    logger.info("Updated/Created active session for user {} on device {}", user.getEmail(), deviceId);
                } catch (Exception e) {
                    logger.error("Failed to manage user session: {}", e.getMessage());
                    // Don't fail auth if session creation fails
                    session = new UserSession(); // Fallback
                    session.setId(-1L);
                }
                
                // Compute next-step hint for the JWT "ns" claim
                String ns = (user.getUserProgress() != null) ? user.getUserProgress().getNextStep() : "get-started";

                // Generate JWT token
                String jwt;
                if (session.getId() != null && session.getId() != -1L) {
                    jwt = jwtUtils.generateJwtToken(authentication, session.getId(), ns);
                } else {
                    jwt = jwtUtils.generateJwtToken(authentication, ns);
                }
                logger.info("JWT generated for authenticated user: {} (ns={})", user.getEmail(), ns);
                
                return new AuthenticationFinishResponse(
                    true,
                    "Passkey authentication successful",
                    jwt,
                    user.getId(),
                    user.getEmail(),
                    user.getPublicId()
                );
                
            } else {
                logger.warn("Passkey authentication failed - assertion verification failed");
                return new AuthenticationFinishResponse(false, "Authentication failed", null, null, null);
            }
            
        } catch (AssertionFailedException e) {
            logger.error("Passkey authentication failed: {}", e.getMessage(), e);
            return new AuthenticationFinishResponse(false, "Authentication failed: " + e.getMessage(), null, null, null);
        } catch (Exception e) {
            logger.error("Unexpected error during passkey authentication: {}", e.getMessage(), e);
            return new AuthenticationFinishResponse(false, "An unexpected error occurred", null, null, null);
        }
    }
    
    /**
     * Response class for authentication finish
     */
    public static class AuthenticationFinishResponse {
        private boolean success;
        private String message;
        private String jwtToken;
        private Long userId;
        private String email;
        /** Opaque analytics identity; null on failure responses. */
        private String publicId;
        
        public AuthenticationFinishResponse(boolean success, String message, String jwtToken, Long userId, String email) {
            this(success, message, jwtToken, userId, email, null);
        }

        public AuthenticationFinishResponse(boolean success, String message, String jwtToken, Long userId,
                String email, String publicId) {
            this.success = success;
            this.message = message;
            this.jwtToken = jwtToken;
            this.userId = userId;
            this.email = email;
            this.publicId = publicId;
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getJwtToken() { return jwtToken; }
        public Long getUserId() { return userId; }
        public String getEmail() { return email; }
        public String getPublicId() { return publicId; }
    }
}