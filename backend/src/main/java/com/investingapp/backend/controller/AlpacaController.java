package com.investingapp.backend.controller;

import com.investingapp.backend.dto.AccountStatusResponse;
import com.investingapp.backend.dto.CreateAlpacaAccountRequest;
import com.investingapp.backend.dto.UpdateKycRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.investingapp.backend.service.AlpacaApiService;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.KycSubmissionService;
import com.investingapp.backend.service.PlaidToAlpacaService;
import com.investingapp.backend.security.services.UserDetailsImpl;
import java.util.Map;
import java.util.HashMap;
import com.investingapp.backend.model.User;
import com.investingapp.backend.service.AlpacaService;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api/alpaca")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AlpacaController {

    private static final Logger logger = LoggerFactory.getLogger(AlpacaController.class);
    private final AlpacaApiService alpacaApiService;
    private final AlpacaService alpacaService;
    private final PlaidToAlpacaService plaidToAlpacaService;
    private final com.investingapp.backend.repository.UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final KycSubmissionService kycSubmissionService;

    public AlpacaController(AlpacaApiService alpacaApiService, AlpacaService alpacaService, PlaidToAlpacaService plaidToAlpacaService,
            com.investingapp.backend.repository.UserRepository userRepository,
            EncryptionService encryptionService,
            KycSubmissionService kycSubmissionService) {
        this.alpacaApiService = alpacaApiService;
        this.alpacaService = alpacaService;
        this.plaidToAlpacaService = plaidToAlpacaService;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.kycSubmissionService = kycSubmissionService;
    }

    @GetMapping("/account")
    public ResponseEntity<String> getAccount() {
        try {
            String accountInfo = alpacaApiService.getAccountInfo();
            return ResponseEntity.ok(accountInfo);
        } catch (Exception e) {
            logger.error("Error getting account info: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"Failed to get account info\"}");
        }
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl) {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            return userRepository.findById(userDetails.getId()).orElse(null);
        }
        return null;
    }

    /**
     * The authenticated user's id, straight from the JWT principal and with no database read.
     * Used where the row is loaded inside a transaction instead of here, so the entity is managed
     * by the transaction that writes it and is not loaded twice.
     */
    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl) {
            return ((UserDetailsImpl) authentication.getPrincipal()).getId();
        }
        return null;
    }

    @PostMapping("/create-account")
    public ResponseEntity<Map<String, Object>> createAccount(
            @Valid @RequestBody CreateAlpacaAccountRequest request,
            HttpServletRequest httpRequest) {
        try {
            logger.info("Creating Alpaca account (full KYC) for email: {}", request.getEmailAddress());

            // CF-Connecting-IP is set by Cloudflare and cannot be spoofed by the client
            String clientIp = httpRequest.getHeader("CF-Connecting-IP");
            if (clientIp == null || clientIp.isBlank()) {
                clientIp = httpRequest.getRemoteAddr();
            }

            // A second submission used to create a SECOND brokerage account at the broker, and
            // then overwrite the stored account id with it, orphaning the first. Rejected here,
            // before the broker call, because once that call succeeds the duplicate account exists
            // whatever we do locally. A real resubmission (the broker asked for more information)
            // goes through PATCH /account/kyc and the document upload, not through this endpoint.
            Long currentUserId = getCurrentUserId();
            if (currentUserId != null) {
                User existing = userRepository.findById(currentUserId).orElse(null);
                if (existing != null && existing.getAlpacaAccountId() != null
                        && !existing.getAlpacaAccountId().isBlank()) {
                    logger.warn("Refused a second create-account for user {}: a brokerage account "
                            + "already exists", currentUserId);
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                            "error", "An account application already exists for this user.",
                            "code", "ACCOUNT_ALREADY_EXISTS"));
                }
            }

            Map<String, Object> result = alpacaApiService.createAccount(request, clientIp);

            if (result.containsKey("error")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
            }

            // Persist Alpaca Account ID, Number, encrypted SSN, and account status, and record
            // kyc_submitted in the same transaction. The persistence moved to KycSubmissionService
            // because this method is not transactional: the analytics event has to commit with the
            // state change or not at all, and the Alpaca call above must stay outside that
            // transaction.
            // SECURITY: Use authenticated user from JWT, NOT request body email (prevents privilege escalation)
            if (kycSubmissionService.persistSubmission(getCurrentUserId(), result, request)) {
                logger.info("Saved Alpaca account details and set accountStatus=SUBMITTED for user: {}",
                        request.getEmailAddress());
            } else {
                logger.warn("User not found for email: {}, could not save Alpaca details locally",
                        request.getEmailAddress());
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Error creating Alpaca account", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/my-account-status")
    public ResponseEntity<?> getMyAccountStatus() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
            }

            String status = user.getAccountStatus();
            boolean hasActionRequired = "ACTION_REQUIRED".equals(status);
            return ResponseEntity.ok(new AccountStatusResponse(status, hasActionRequired));

        } catch (Exception e) {
            logger.error("Error fetching account status for current user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve account status"));
        }
    }

    @PostMapping("/upload-document")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @Valid @RequestBody UploadDocumentRequest request) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "User not authenticated");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
            }

            if (!"ACTION_REQUIRED".equals(user.getAccountStatus())) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Document upload is only allowed when account status is ACTION_REQUIRED");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
            }

            if (user.getAlpacaAccountId() == null) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "No Alpaca account found for this user");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
            }

            String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());

            Map<String, Object> result = alpacaApiService.uploadDocument(
                    alpacaAccountId,
                    request.getDocumentType(),
                    request.getMimeType(),
                    request.getContent());

            if (result.containsKey("error")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Error uploading document", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/assets")
    public ResponseEntity<String> getAssets(
            @RequestParam(value = "status", defaultValue = "active") String status,
            @RequestParam(value = "asset_class", defaultValue = "us_equity") String assetClass,
            @RequestParam(value = "search", required = false) String search) {
        try {
            logger.info("Getting assets with status: {}, asset_class: {}, search: {}", status, assetClass, search);
            String assets = alpacaApiService.getAssets(status, assetClass, search);
            return ResponseEntity.ok(assets);
        } catch (Exception e) {
            logger.error("Error getting assets: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"Failed to get assets\"}");
        }
    }

    @PostMapping("/accounts/{accountId}/ach-relationships")
    public ResponseEntity<Map<String, Object>> createAchRelationship(
            @PathVariable String accountId,
            @RequestBody CreateAchRelationshipRequest request) {
        try {
            logger.info("Creating ACH relationship for account: {}", accountId);

            Map<String, Object> result = alpacaApiService.createAchRelationship(
                    accountId,
                    request.getAccountOwnerName(),
                    request.getBankAccountType(),
                    request.getBankAccountNumber(),
                    request.getBankRoutingNumber(),
                    request.getNickname());

            if (result.containsKey("error")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Error creating ACH relationship: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to create ACH relationship: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/accounts/{accountId}/ach-relationships")
    public ResponseEntity<String> getAchRelationships(@PathVariable String accountId) {
        try {
            String relationships = alpacaApiService.getAchRelationships(accountId);
            return ResponseEntity.ok(relationships);
        } catch (Exception e) {
            logger.error("Error getting ACH relationships: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"Failed to get ACH relationships\"}");
        }
    }

    @GetMapping("/account/{accountId}/status")
    public ResponseEntity<String> getAccountStatus(@PathVariable String accountId) {
        try {
            String status = alpacaApiService.getAccountStatus(accountId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("Error getting account status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"Failed to get account status\"}");
        }
    }

    // DTO for document upload request
    public static class UploadDocumentRequest {
        @jakarta.validation.constraints.NotBlank(message = "Document type is required")
        private String documentType;
        @jakarta.validation.constraints.NotBlank(message = "MIME type is required")
        private String mimeType;
        @jakarta.validation.constraints.NotBlank(message = "Document content is required")
        private String content;

        public String getDocumentType() { return documentType; }
        public void setDocumentType(String documentType) { this.documentType = documentType; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    @PostMapping("/acats/transfer")
    public ResponseEntity<?> initiateAcatsTransfer(@RequestBody AcatsTransferRequest request) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
            }
            if (user.getAlpacaAccountId() == null) {
                return ResponseEntity.badRequest().body("User does not have an Alpaca account");
            }

            String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
            String transferId = alpacaService.initiateAcatsTransfer(
                alpacaAccountId,
                request.getAccountNumber(),
                "BROKERAGE",
                request.getDtcNumber()
            );
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("transferId", transferId);
            response.put("message", "ACATS transfer initiated successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error initiating ACATS transfer", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to initiate transfer: " + e.getMessage()));
        }
    }

    public static class AcatsTransferRequest {
        private String dtcNumber;
        private String accountNumber;
        
        public String getDtcNumber() { return dtcNumber; }
        public void setDtcNumber(String dtcNumber) { this.dtcNumber = dtcNumber; }
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    }

    /**
     * GET /api/alpaca/account/kyc
     * Returns the current user's KYC data from Alpaca (contact, identity, disclosures).
     * Used by the kyc-verification component to pre-fill the edit form.
     */
    @GetMapping("/account/kyc")
    public ResponseEntity<?> getAccountKyc() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
            }
            if (user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No Alpaca account found"));
            }

            String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
            String kycData = alpacaApiService.getAccountKyc(alpacaAccountId);
            return ResponseEntity.ok(kycData);

        } catch (Exception e) {
            logger.error("Error fetching KYC data for current user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to retrieve KYC data: " + e.getMessage()));
        }
    }

    /**
     * PATCH /api/alpaca/account/kyc
     * Updates the current user's KYC data via Alpaca's PATCH /accounts/{account_id} API.
     * Only fields included in the request body are updated.
     */
    @PatchMapping("/account/kyc")
    public ResponseEntity<?> patchAccountKyc(@RequestBody UpdateKycRequest request) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
            }
            if (user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No Alpaca account found"));
            }

            String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
            logger.info("Patching KYC for user {} (Alpaca account {})", user.getId(), alpacaAccountId);

            String result = alpacaApiService.patchAccountKyc(alpacaAccountId, request);

            // Update name fields locally if provided
            if (request.getGivenName() != null) {
                user.setFirstName(request.getGivenName());
            }
            if (request.getFamilyName() != null) {
                user.setLastName(request.getFamilyName());
            }
            if (request.getGivenName() != null || request.getFamilyName() != null) {
                userRepository.save(user);
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Error patching KYC for current user", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage() != null ? e.getMessage() : "Failed to update KYC data");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // DTO for ACH relationship creation request
    public static class CreateAchRelationshipRequest {
        private String accountOwnerName;
        private String bankAccountType; // CHECKING or SAVINGS
        private String bankAccountNumber;
        private String bankRoutingNumber;
        private String nickname;

        // Getters and setters
        public String getAccountOwnerName() {
            return accountOwnerName;
        }

        public void setAccountOwnerName(String accountOwnerName) {
            this.accountOwnerName = accountOwnerName;
        }

        public String getBankAccountType() {
            return bankAccountType;
        }

        public void setBankAccountType(String bankAccountType) {
            this.bankAccountType = bankAccountType;
        }

        public String getBankAccountNumber() {
            return bankAccountNumber;
        }

        public void setBankAccountNumber(String bankAccountNumber) {
            this.bankAccountNumber = bankAccountNumber;
        }

        public String getBankRoutingNumber() {
            return bankRoutingNumber;
        }

        public void setBankRoutingNumber(String bankRoutingNumber) {
            this.bankRoutingNumber = bankRoutingNumber;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }
    }

}