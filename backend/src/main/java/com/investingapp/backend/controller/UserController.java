package com.investingapp.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserProgress;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserProgressRepository;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.List;
import java.util.Random;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "*")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProgressRepository userProgressRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private com.investingapp.backend.security.jwt.JwtUtils jwtUtils;

    @Autowired
    private com.investingapp.backend.repository.UserSessionRepository userSessionRepository;

    @Autowired
    private com.investingapp.backend.service.UserService userService;

    @Autowired
    private com.investingapp.backend.service.SubscriptionService subscriptionService;

    public static class UserProgressResponse {
        private boolean getStartedCompleted;
        private boolean surveyInitialCompleted;
        private boolean fiPlanResultsCompleted;
        private boolean authFinalizeCompleted;
        private boolean kycVerificationCompleted;
        private boolean linkPlaidCompleted;
        private boolean investmentScheduleCompleted;
        private boolean investmentConfirmationCompleted;
        private Double monthlyInvestment;
        private Double retirementIncome;
        private String firstName;
        private String lastName;
        private String nextStep;
        private double completionPercentage;
        private String referralCode;
        private int referralCount;
        private boolean hasAppliedReferral;
        private boolean dripEnabled;
        private String selectedTier;
        private String billingPeriod;
        private boolean privateBeta;
        private String publicId;
        private boolean referralRewardTriggered;
        private Integer currentFreedomEstimate;
        private String timeToFI;

        public UserProgressResponse(User user) {
            UserProgress progress = user.getUserProgress();
            if (progress != null) {
                this.getStartedCompleted = progress.isGetStartedCompleted();
                this.surveyInitialCompleted = progress.isSurveyInitialCompleted();
                this.fiPlanResultsCompleted = progress.isFiPlanResultsCompleted();
                this.authFinalizeCompleted = progress.isAuthFinalizeCompleted();
                this.kycVerificationCompleted = progress.isKycVerificationCompleted();
                this.linkPlaidCompleted = progress.isLinkPlaidCompleted();
                this.investmentScheduleCompleted = progress.isInvestmentScheduleCompleted();
                this.investmentConfirmationCompleted = progress.isInvestmentConfirmationCompleted();
                this.nextStep = progress.getNextStep();
                this.completionPercentage = progress.getCompletionPercentage();
            } else {
                // Default values if no progress record
                this.getStartedCompleted = false;
                this.surveyInitialCompleted = false;
                this.fiPlanResultsCompleted = false;
                this.authFinalizeCompleted = false;
                this.kycVerificationCompleted = false;
                this.linkPlaidCompleted = false;
                this.investmentScheduleCompleted = false;
                this.investmentConfirmationCompleted = false;
                this.nextStep = "get-started";
                this.completionPercentage = 0.0;
            }
            // Populate fields directly from the User object
            this.monthlyInvestment = user.getMonthlyInvestment();
            this.retirementIncome = user.getRetirementIncome();
            this.firstName = user.getFirstName();
            this.lastName = user.getLastName();
            this.referralCode = user.getReferralCode();
            this.referralCount = user.getReferralCount() != null ? user.getReferralCount() : 0;
            this.hasAppliedReferral = Boolean.TRUE.equals(user.getHasAppliedReferral());
            // DRIP defaults to true if null (for existing users before this field was added)
            this.dripEnabled = !Boolean.FALSE.equals(user.getDripEnabled());
            this.selectedTier = user.getSelectedTier();
            this.billingPeriod = user.getBillingPeriod();
            this.privateBeta = Boolean.TRUE.equals(user.getPrivateBeta());
            this.publicId = user.getPublicId();
            this.referralRewardTriggered = Boolean.TRUE.equals(user.getReferralRewardTriggered());
            this.currentFreedomEstimate = user.getCurrentFreedomEstimate();
            this.timeToFI = user.getTimeToFI();
        }

        // Getters
        public boolean isGetStartedCompleted() {
            return getStartedCompleted;
        }

        public boolean isSurveyInitialCompleted() {
            return surveyInitialCompleted;
        }

        public boolean isFiPlanResultsCompleted() {
            return fiPlanResultsCompleted;
        }

        public boolean isAuthFinalizeCompleted() {
            return authFinalizeCompleted;
        }

        public boolean isKycVerificationCompleted() {
            return kycVerificationCompleted;
        }

        public boolean isLinkPlaidCompleted() {
            return linkPlaidCompleted;
        }

        public boolean isInvestmentScheduleCompleted() {
            return investmentScheduleCompleted;
        }

        public boolean isInvestmentConfirmationCompleted() {
            return investmentConfirmationCompleted;
        }

        public Double getMonthlyInvestment() {
            return monthlyInvestment;
        }

        public Double getRetirementIncome() {
            return retirementIncome;
        }

        public String getFirstName() {
            return firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public String getReferralCode() {
            return referralCode;
        }

        public int getReferralCount() {
            return referralCount;
        }

        public boolean isHasAppliedReferral() {
            return hasAppliedReferral;
        }

        public boolean isDripEnabled() {
            return dripEnabled;
        }

        public String getSelectedTier() {
            return selectedTier;
        }

        public String getBillingPeriod() {
            return billingPeriod;
        }

        public boolean isPrivateBeta() {
            return privateBeta;
        }

        /** Analytics identity, for clients holding a token issued before the pid claim existed. */
        public String getPublicId() {
            return publicId;
        }

        public boolean isReferralRewardTriggered() {
            return referralRewardTriggered;
        }

        public String getNextStep() {
            return nextStep;
        }

        public double getCompletionPercentage() {
            return completionPercentage;
        }

        public Integer getCurrentFreedomEstimate() {
            return currentFreedomEstimate;
        }

        public String getTimeToFI() {
            return timeToFI;
        }
    }

    /**
     * Toggle DRIP (Dividend Reinvestment Plan) for the current user
     * PUT /user/drip
     */
    @PutMapping("/drip")
    public ResponseEntity<?> toggleDrip(@RequestBody Map<String, Object> payload, Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(401).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String email = userDetails.getUsername();

            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                return ResponseEntity.notFound().build();
            }

            Object enabledVal = payload.get("enabled");
            if (enabledVal == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "'enabled' field is required"));
            }

            boolean enabled = Boolean.parseBoolean(enabledVal.toString());
            user.setDripEnabled(enabled);
            userRepository.save(user);

            logger.info("[UserController] DRIP {} for user: {}", enabled ? "enabled" : "disabled", email);

            return ResponseEntity.ok(Map.of(
                "message", "DRIP " + (enabled ? "enabled" : "disabled") + " successfully",
                "dripEnabled", enabled
            ));
        } catch (Exception e) {
            logger.error("[UserController] Error toggling DRIP: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get the current user's DRIP status
     * GET /user/drip
     */
    @GetMapping("/drip")
    public ResponseEntity<?> getDripStatus(Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(401).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String email = userDetails.getUsername();

            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                return ResponseEntity.notFound().build();
            }

            boolean dripEnabled = !Boolean.FALSE.equals(user.getDripEnabled());
            return ResponseEntity.ok(Map.of("dripEnabled", dripEnabled));
        } catch (Exception e) {
            logger.error("[UserController] Error getting DRIP status: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/referral/validate")
    public ResponseEntity<?> validateReferralCode(@RequestParam("code") String code) {
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false));
        }
        boolean exists = userRepository.existsByReferralCode(code.trim().toUpperCase());
        return ResponseEntity.ok(Map.of("valid", exists));
    }

    @Transactional
    @PostMapping("/referral/apply")
    public ResponseEntity<?> applyReferralCode(Authentication authentication, @RequestBody Map<String, String> payload) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return ResponseEntity.badRequest().body(Map.of("message", "User not found"));

        String code = payload.get("code");
        if (code == null || code.isBlank()) return ResponseEntity.badRequest().body(Map.of("message", "Referral code is required"));
        code = code.trim().toUpperCase();

        try {
            String status = userService.applyReferralCode(user.getId(), code);
            String message = switch (status) {
                case "APPLIED_IMMEDIATELY" -> "Referral applied!";
                case "PENDING_TRIAL" -> "Code saved! Complete the 14-day free trial and stay a paying subscriber for at least 1 month to apply this referral.";
                case "PENDING_30_DAYS" -> "Code saved! Stay a paying subscriber for 1 month after your trial ends to apply this referral.";
                default -> "Referral code applied successfully.";
            };
            return ResponseEntity.ok(Map.of("message", message, "status", status));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get the current user's progress through the onboarding flow
     * GET /user/progress
     */
    @Transactional(readOnly = true)
    @GetMapping("/progress")
    public ResponseEntity<UserProgressResponse> getUserProgress(Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                logger.warn("[UserController] No authentication found for progress request");
                return ResponseEntity.status(401).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String email = userDetails.getUsername();

            logger.info("[UserController] Getting progress for user: {}", email);

            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                logger.warn("[UserController] User not found: {}", email);
                return ResponseEntity.notFound().build();
            }

            // Ensure user has UserProgress record
            if (user.getUserProgress() == null) {
                UserProgress newProgress = new UserProgress();
                user.setUserProgress(newProgress);
                userRepository.save(user);
                logger.info("[UserController] Created new UserProgress record for user: {}", email);
            }

            // IMPORTANT: Clear the persistence context and re-fetch with EAGER loading
            // This prevents returning stale cached data from Hibernate's first-level cache
            entityManager.clear();
            user = userRepository.findByEmailWithProgress(email).orElse(null);
            if (user == null) {
                logger.error("[UserController] User disappeared after refresh: {}", email);
                return ResponseEntity.notFound().build();
            }
            logger.info("[UserController] Re-fetched user with EAGER UserProgress from database for: {}", email);

            UserProgressResponse progress = new UserProgressResponse(user);
            UserProgress userProgress = user.getUserProgress();
            logger.info(
                    "[UserController] Returning progress for user {}: getStarted={}, surveyInitial={}, fiPlanResults={}, authFinalize={}, kycVerification={}, linkPlaid={}, investmentSchedule={}, investmentConfirmation={}, nextStep={}, completion={}%",
                    email, progress.isGetStartedCompleted(), progress.isSurveyInitialCompleted(),
                    progress.isFiPlanResultsCompleted(),
                    progress.isAuthFinalizeCompleted(), progress.isKycVerificationCompleted(),
                    progress.isLinkPlaidCompleted(),
                    progress.isInvestmentScheduleCompleted(), progress.isInvestmentConfirmationCompleted(),
                    userProgress.getNextStep(), userProgress.getCompletionPercentage());

            return ResponseEntity.ok(progress);

        } catch (Exception e) {
            logger.error("[UserController] Error getting user progress: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Update the current user's progress through the onboarding flow
     * PUT /user/progress
     */
    @PutMapping("/progress")
    public ResponseEntity<?> updateUserProgress(@RequestBody Map<String, Object> progressUpdate,
            Authentication authentication,
            HttpServletRequest request) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                logger.warn("[UserController] No authentication found for progress update request");
                return ResponseEntity.status(401).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String email = userDetails.getUsername();

            logger.info("[UserController] Updating progress for user: {}", email);

            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                logger.warn("[UserController] User not found: {}", email);
                return ResponseEntity.notFound().build();
            }

            // Ensure user has UserProgress record
            UserProgress userProgress = user.getUserProgress();
            if (userProgress == null) {
                userProgress = new UserProgress();
                user.setUserProgress(userProgress);
                userRepository.save(user);
                logger.info("[UserController] Created new UserProgress record for user: {}", email);
            }

            // Update progress fields based on the request
            if (progressUpdate.containsKey("getStartedCompleted")) {
                userProgress.setGetStartedCompleted((Boolean) progressUpdate.get("getStartedCompleted"));
            }
            if (progressUpdate.containsKey("surveyInitialCompleted")) {
                userProgress.setSurveyInitialCompleted((Boolean) progressUpdate.get("surveyInitialCompleted"));
            }
            if (progressUpdate.containsKey("fiPlanResultsCompleted")) {
                userProgress.setFiPlanResultsCompleted((Boolean) progressUpdate.get("fiPlanResultsCompleted"));
            }
            if (progressUpdate.containsKey("authFinalizeCompleted")) {
                userProgress.setAuthFinalizeCompleted((Boolean) progressUpdate.get("authFinalizeCompleted"));

                // Record IP address and device ID when user completes authfinalize
                if ((Boolean) progressUpdate.get("authFinalizeCompleted")) {
                    String ipAddress = getClientIpAddress(request);
                    user.setRegistrationIpAddress(ipAddress);
                    logger.info("[UserController] Recorded IP address {} for user {} completing authfinalize",
                            ipAddress, email);

                    // Also record device ID if available (better for mobile tracking)
                    String deviceId = request.getHeader("X-Device-ID");
                    if (deviceId != null && !deviceId.isEmpty()) {
                        user.setDeviceId(deviceId);
                        logger.info("[UserController] Recorded device ID {} for user {} completing authfinalize",
                                deviceId.substring(0, Math.min(8, deviceId.length())) + "...", email);
                    }
                }
            }
            if (progressUpdate.containsKey("kycVerificationCompleted")) {
                userProgress.setKycVerificationCompleted((Boolean) progressUpdate.get("kycVerificationCompleted"));
            }
            if (progressUpdate.containsKey("linkPlaidCompleted")) {
                userProgress.setLinkPlaidCompleted((Boolean) progressUpdate.get("linkPlaidCompleted"));
            }
            if (progressUpdate.containsKey("investmentScheduleCompleted")) {
                userProgress
                        .setInvestmentScheduleCompleted((Boolean) progressUpdate.get("investmentScheduleCompleted"));
            }
            if (progressUpdate.containsKey("investmentConfirmationCompleted")) {
                userProgress.setInvestmentConfirmationCompleted(
                        (Boolean) progressUpdate.get("investmentConfirmationCompleted"));
            }

            // Ensure logical consistency: if later steps are completed, earlier steps
            // should be too
            validateAndFixStepDependencies(userProgress, email);

            userProgressRepository.save(userProgress);
            userRepository.save(user);

            UserProgressResponse updatedProgress = new UserProgressResponse(user);
            logger.info("[UserController] Successfully updated progress for user {}", email);

            return ResponseEntity.ok(updatedProgress);

        } catch (Exception e) {
            logger.error("[UserController] Error updating user progress: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Update the current user's profile data (User object direct update)
     * PUT /user/profile
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateUserProfile(@RequestBody Map<String, Object> profileUpdate,
            Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(401).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String email = userDetails.getUsername();

            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                return ResponseEntity.notFound().build();
            }

            boolean changed = false;

            if (profileUpdate.containsKey("monthlyInvestment")) {
                Object val = profileUpdate.get("monthlyInvestment");
                if (val instanceof Number) {
                    user.setMonthlyInvestment(((Number) val).doubleValue());
                    changed = true;
                }
            }
            if (profileUpdate.containsKey("retirementIncome")) {
                Object val = profileUpdate.get("retirementIncome");
                if (val instanceof Number) {
                    user.setRetirementIncome(((Number) val).doubleValue());
                    changed = true;
                }
            }
            if (profileUpdate.containsKey("selectedTier")) {
                Object val = profileUpdate.get("selectedTier");
                if (val == null) {
                    // Explicit null clears the subscription (no key = leave untouched).
                    user.setSelectedTier(null);
                    changed = true;
                } else if (val instanceof String) {
                    String tier = (String) val;
                    user.setSelectedTier(tier);
                    if (tier != null && user.getSubscriptionStartedAt() == null) {
                        user.setSubscriptionStartedAt(LocalDateTime.now());
                    }
                    changed = true;
                }
            }
            if (profileUpdate.containsKey("billingPeriod")) {
                Object val = profileUpdate.get("billingPeriod");
                if (val instanceof String) {
                    user.setBillingPeriod((String) val);
                    changed = true;
                }
            }
            if (profileUpdate.containsKey("agreedToMarketing")) {
                Object val = profileUpdate.get("agreedToMarketing");
                if (val instanceof Boolean) {
                    user.setAgreedToMarketing((Boolean) val);
                    changed = true;
                    if (Boolean.TRUE.equals(val)) {
                        // TODO: call marketing email provider API to add user to list
                        // e.g. Resend Audiences or Mailchimp — integrate once provider is chosen
                        logger.info("[Marketing] User opted in to marketing emails: {}", email);
                    }
                }
            }

            if (changed) {
                userRepository.save(user);
                logger.info("[UserController] Updated profile for user: {}", email);
            }

            // Return updated progress response (which includes User data) for convenience
            return ResponseEntity.ok(new UserProgressResponse(user));

        } catch (Exception e) {
            logger.error("[UserController] Error updating user profile: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Store or update the device push notification token for the current user
     * PATCH /user/push-token
     */
    @PatchMapping("/push-token")
    public ResponseEntity<?> updatePushToken(@RequestBody Map<String, String> payload,
            Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(401).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String email = userDetails.getUsername();

            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                return ResponseEntity.notFound().build();
            }

            String token = payload.get("pushToken");
            if (token == null || token.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "'pushToken' field is required"));
            }

            user.setDevicePushToken(token);
            userRepository.save(user);

            logger.info("[UserController] Updated push token for user: {}", email);

            return ResponseEntity.ok(Map.of("message", "Push token updated successfully"));
        } catch (Exception e) {
            logger.error("[UserController] Error updating push token: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Confirm a subscription purchase from the frontend (post Apple IAP / RevenueCat)
     * POST /user/subscription/confirm
     */
    @PostMapping("/subscription/confirm")
    public ResponseEntity<?> confirmSubscription(
            Authentication authentication,
            @RequestBody java.util.Map<String, String> body) {
        if (authentication == null) return ResponseEntity.status(401).build();
        org.springframework.security.core.userdetails.UserDetails userDetails =
                (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        String email = userDetails.getUsername();
        String tier = body.get("tier");
        String billingPeriod = body.getOrDefault("billingPeriod", "monthly");
        if (tier == null || tier.isBlank()) {
            return ResponseEntity.badRequest().body("tier is required");
        }
        subscriptionService.confirmSubscription(email, tier, billingPeriod);
        return ResponseEntity.ok().build();
    }

    // The unauthenticated RevenueCat-shaped webhook that used to live here (POST
    // /user/subscription/webhook) was removed: it was permitAll with no signature or header check,
    // so anyone who knew a user's email could change that user's tier, and nothing called it.
    // Billing is owned by a separate workstream.

    /**
     * Extract the client's IP address from the HTTP request, handling proxies and
     * load balancers
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            // X-Forwarded-For can contain multiple IPs, take the first one
            return normalizeIpForDeviceTracking(xForwardedFor.split(",")[0].trim());
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return normalizeIpForDeviceTracking(xRealIp);
        }

        String xForwardedProto = request.getHeader("X-Forwarded-Proto");
        if (xForwardedProto != null) {
            // If we're behind a proxy, try other headers
            String proxyClientIp = request.getHeader("Proxy-Client-IP");
            if (proxyClientIp != null && !proxyClientIp.isEmpty() && !"unknown".equalsIgnoreCase(proxyClientIp)) {
                return normalizeIpForDeviceTracking(proxyClientIp);
            }

            String wlProxyClientIp = request.getHeader("WL-Proxy-Client-IP");
            if (wlProxyClientIp != null && !wlProxyClientIp.isEmpty() && !"unknown".equalsIgnoreCase(wlProxyClientIp)) {
                return normalizeIpForDeviceTracking(wlProxyClientIp);
            }
        }

        // Fall back to remote address
        return normalizeIpForDeviceTracking(request.getRemoteAddr());
    }

    /**
     * Normalize IP address for reliable device tracking
     * For IPv6: Use network prefix (/64) to handle privacy extensions
     * For IPv4: Use full address as it's typically stable
     */
    private String normalizeIpForDeviceTracking(String rawIp) {
        if (rawIp == null || rawIp.isEmpty()) {
            return rawIp;
        }

        // Check if it's IPv6 (contains colons)
        if (rawIp.contains(":")) {
            // IPv6 - extract first 4 groups (64 bits) for network identification
            String[] parts = rawIp.split(":");
            if (parts.length >= 4) {
                // Take first 4 groups: xxxx:xxxx:xxxx:xxxx::/64
                String networkPrefix = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + "::/64";
                logger.debug("[UserController] Normalized IPv6 {} to network prefix {}", rawIp, networkPrefix);
                return networkPrefix;
            }
        }

        // IPv4 or malformed - return as-is
        return rawIp;
    }

    /**
     * Check if the current device should be prompted for passkey re-authentication
     * Simple logic: Only prompt if this exact device has completed auth-finalize
     * before
     * GET /user/should-prompt-reauth
     */
    @GetMapping("/should-prompt-reauth")
    public ResponseEntity<?> shouldPromptForReauth(HttpServletRequest request) {
        try {
            String deviceId = request.getHeader("X-Device-ID");

            if (deviceId != null && !deviceId.isEmpty()) {
                logger.info("[UserController] Checking device ID {} for passkey re-auth", deviceId);

                // Simple check: Has THIS specific device completed auth-finalize before?
                List<User> usersFromThisDevice = userRepository.findByDeviceIdAndAuthFinalizeCompleted(deviceId, true);

                if (!usersFromThisDevice.isEmpty()) {
                    logger.info("[UserController] Device ID {} has {} completed users - prompting for passkey",
                            deviceId, usersFromThisDevice.size());
                    return ResponseEntity.ok(Map.of(
                            "shouldPromptReauth", true,
                            "message", "This device has completed registration before",
                            "trackingMethod", "device-id"));
                } else {
                    logger.info("[UserController] Device ID {} is new - proceeding with fresh onboarding", deviceId);
                    return ResponseEntity.ok(Map.of(
                            "shouldPromptReauth", false,
                            "message", "New device - proceed with registration",
                            "trackingMethod", "device-id"));
                }
            } else {
                // No device ID provided - treat as new user
                logger.info("[UserController] No device ID provided - treating as new user");
                return ResponseEntity.ok(Map.of(
                        "shouldPromptReauth", false,
                        "message", "No device tracking available - proceed with registration",
                        "trackingMethod", "none"));
            }

        } catch (Exception e) {
            logger.error("[UserController] Error checking reauth prompt status: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "shouldPromptReauth", false,
                    "message", "Error occurred, defaulting to fresh registration"));
        }
    }

    /**
     * Ensure logical consistency of step completion
     * If later steps are completed, automatically complete earlier prerequisite
     * steps
     */
    private void validateAndFixStepDependencies(UserProgress userProgress, String userEmail) {
        boolean wasFixed = false;

        // Step dependency chain: getStarted → surveyInitial → fiPlanResults →
        // authFinalize → kycVerification → linkPlaid → investmentSchedule →
        // investmentConfirmation

        // If any step beyond getStarted is completed, getStarted should be completed
        if (!userProgress.isGetStartedCompleted() &&
                (userProgress.isSurveyInitialCompleted() || userProgress.isFiPlanResultsCompleted() ||
                        userProgress.isAuthFinalizeCompleted() || userProgress.isKycVerificationCompleted() ||
                        userProgress.isLinkPlaidCompleted() || userProgress.isInvestmentScheduleCompleted() ||
                        userProgress.isInvestmentConfirmationCompleted())) {
            userProgress.setGetStartedCompleted(true);
            wasFixed = true;
            logger.info("[UserController] Auto-completed getStarted for user {}", userEmail);
        }

        // If any step beyond surveyInitial is completed, surveyInitial should be
        // completed
        if (!userProgress.isSurveyInitialCompleted() &&
                (userProgress.isFiPlanResultsCompleted() || userProgress.isAuthFinalizeCompleted() ||
                        userProgress.isKycVerificationCompleted() || userProgress.isLinkPlaidCompleted() ||
                        userProgress.isInvestmentScheduleCompleted()
                        || userProgress.isInvestmentConfirmationCompleted())) {
            userProgress.setSurveyInitialCompleted(true);
            wasFixed = true;
            logger.info("[UserController] Auto-completed surveyInitial for user {}", userEmail);
        }

        // If any step beyond fiPlanResults is completed, fiPlanResults should be
        // completed
        if (!userProgress.isFiPlanResultsCompleted() &&
                (userProgress.isAuthFinalizeCompleted() || userProgress.isKycVerificationCompleted() ||
                        userProgress.isLinkPlaidCompleted() || userProgress.isInvestmentScheduleCompleted() ||
                        userProgress.isInvestmentConfirmationCompleted())) {
            userProgress.setFiPlanResultsCompleted(true);
            wasFixed = true;
            logger.info("[UserController] Auto-completed fiPlanResults for user {}", userEmail);
        }

        // If any step beyond authFinalize is completed, authFinalize should be
        // completed
        if (!userProgress.isAuthFinalizeCompleted() &&
                (userProgress.isKycVerificationCompleted() || userProgress.isLinkPlaidCompleted() ||
                        userProgress.isInvestmentScheduleCompleted()
                        || userProgress.isInvestmentConfirmationCompleted())) {
            userProgress.setAuthFinalizeCompleted(true);
            wasFixed = true;
            logger.info("[UserController] Auto-completed authFinalize for user {}", userEmail);
        }

        // If any step beyond kycVerification is completed, kycVerification should be
        // completed
        if (!userProgress.isKycVerificationCompleted() &&
                (userProgress.isLinkPlaidCompleted() || userProgress.isInvestmentScheduleCompleted() ||
                        userProgress.isInvestmentConfirmationCompleted())) {
            userProgress.setKycVerificationCompleted(true);
            wasFixed = true;
            logger.info("[UserController] Auto-completed kycVerification for user {}", userEmail);
        }

        // If any step beyond linkPlaid is completed, linkPlaid should be completed
        if (!userProgress.isLinkPlaidCompleted() &&
                (userProgress.isInvestmentScheduleCompleted() || userProgress.isInvestmentConfirmationCompleted())) {
            userProgress.setLinkPlaidCompleted(true);
            wasFixed = true;
            logger.info("[UserController] Auto-completed linkPlaid for user {}", userEmail);
        }

        // If investmentConfirmation is completed, investmentSchedule should be
        // completed
        if (!userProgress.isInvestmentScheduleCompleted() && userProgress.isInvestmentConfirmationCompleted()) {
            userProgress.setInvestmentScheduleCompleted(true);
            wasFixed = true;
            logger.info("[UserController] Auto-completed investmentSchedule for user {}", userEmail);
        }

        if (wasFixed) {
            logger.info("[UserController] Fixed step dependencies for user {}", userEmail);
        }
    }

    @PostMapping("/update-email")
    @Transactional
    public ResponseEntity<?> updateEmail(@RequestBody Map<String, String> request, Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal(); // Corrected casting
        String currentEmail = userDetails.getUsername();
        String newEmail = request.get("newEmail");

        if (newEmail == null || newEmail.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "New email is required"));
        }

        String providedCurrentEmail = request.get("currentEmail");
        if (providedCurrentEmail != null && !providedCurrentEmail.equals(currentEmail)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Current email does not match authenticated user"));
        }

        User user = userRepository.findByEmail(currentEmail).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("message", "User not found"));
        }

        if (userRepository.existsByEmail(newEmail)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email is already in use"));
        }

        user.setEmail(newEmail);
        userRepository.save(user);

        // Generate new JWT — carry next-step hint so cold-start navigation remains fast
        String nsEmail = (user.getUserProgress() != null) ? user.getUserProgress().getNextStep() : "get-started";
        String newJwt = jwtUtils.generateTokenFromUsername(newEmail, nsEmail, user.getPublicId());

        logger.info("Updated email for user ID {} from {} to {}", user.getId(), currentEmail, newEmail);

        return ResponseEntity.ok(Map.of(
                "message", "Email updated successfully",
                "newEmail", newEmail,
                "jwtToken", newJwt));
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> getUserSessions(Authentication authentication, HttpServletRequest request) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String email = userDetails.getUsername();
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("message", "User not found"));
        }

        List<com.investingapp.backend.model.UserSession> sessions = userSessionRepository
                .findByUserIdAndActiveTrue(user.getId());

        String authHeader = request.getHeader("Authorization");
        Long currentSessionId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            currentSessionId = jwtUtils.getSessionIdFromJwtToken(token);
        }

        return ResponseEntity.ok(Map.of(
                "currentSessionId", currentSessionId != null ? currentSessionId : -1L,
                "sessions", sessions));
    }

    @PostMapping("/sessions/{id}/revoke")
    @Transactional
    public ResponseEntity<?> revokeSession(@PathVariable Long id, Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String email = userDetails.getUsername();
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("message", "User not found"));
        }

        com.investingapp.backend.model.UserSession session = userSessionRepository.findById(id).orElse(null);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of("message", "Session not found"));
        }

        if (!session.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of("message", "Unauthorized"));
        }

        session.setActive(false);
        userSessionRepository.save(session);

        return ResponseEntity.ok(Map.of("message", "Session revoked successfully"));
    }
}
