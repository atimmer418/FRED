package com.investingapp.backend.controller;

import com.investingapp.backend.model.Beneficiary;
import com.investingapp.backend.model.User;
import com.investingapp.backend.service.BeneficiaryService;
import com.investingapp.backend.service.BeneficiaryService.BeneficiaryAllocationSummary;
import com.investingapp.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST Controller for managing account beneficiaries
 */
@RestController
@RequestMapping("/api/beneficiaries")
public class BeneficiaryController {
    
    private static final Logger logger = LoggerFactory.getLogger(BeneficiaryController.class);
    
    @Autowired
    private BeneficiaryService beneficiaryService;
    
    @Autowired
    private UserRepository userRepository;
    
    /**
     * Get all beneficiaries for the authenticated user
     */
    @GetMapping
    public ResponseEntity<List<Beneficiary>> getBeneficiaries(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            List<Beneficiary> beneficiaries = beneficiaryService.getBeneficiariesForUser(user);
            
            logger.info("Retrieved {} beneficiaries for user: {}", beneficiaries.size(), user.getEmail());
            return ResponseEntity.ok(beneficiaries);
            
        } catch (Exception e) {
            logger.error("Error retrieving beneficiaries: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get active beneficiaries for the authenticated user
     */
    @GetMapping("/active")
    public ResponseEntity<List<Beneficiary>> getActiveBeneficiaries(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            List<Beneficiary> beneficiaries = beneficiaryService.getActiveBeneficiariesForUser(user);
            
            logger.info("Retrieved {} active beneficiaries for user: {}", beneficiaries.size(), user.getEmail());
            return ResponseEntity.ok(beneficiaries);
            
        } catch (Exception e) {
            logger.error("Error retrieving active beneficiaries: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get beneficiary allocation summary
     */
    @GetMapping("/summary")
    public ResponseEntity<BeneficiaryAllocationSummary> getAllocationSummary(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            BeneficiaryAllocationSummary summary = beneficiaryService.getAllocationSummary(user);
            
            logger.info("Retrieved allocation summary for user: {}", user.getEmail());
            return ResponseEntity.ok(summary);
            
        } catch (Exception e) {
            logger.error("Error retrieving allocation summary: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Create a new beneficiary
     */
    @PostMapping
    public ResponseEntity<?> createBeneficiary(
            @Valid @RequestBody Beneficiary beneficiary,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            Beneficiary createdBeneficiary = beneficiaryService.createBeneficiary(user, beneficiary);
            
            logger.info("Created beneficiary {} for user: {}", createdBeneficiary.getId(), user.getEmail());
            return ResponseEntity.ok(createdBeneficiary);
            
        } catch (com.investingapp.backend.web.UserFacingException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage())); // user-facing
            
        } catch (Exception e) {
            logger.error("Error creating beneficiary: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new ErrorResponse("Failed to create beneficiary"));
        }
    }
    
    /**
     * Update an existing beneficiary
     */
    @PutMapping("/{beneficiaryId}")
    public ResponseEntity<?> updateBeneficiary(
            @PathVariable Long beneficiaryId,
            @Valid @RequestBody Beneficiary beneficiary,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            Beneficiary updatedBeneficiary = beneficiaryService.updateBeneficiary(user, beneficiaryId, beneficiary);
            
            logger.info("Updated beneficiary {} for user: {}", beneficiaryId, user.getEmail());
            return ResponseEntity.ok(updatedBeneficiary);
            
        } catch (NoSuchElementException e) {
            // Not this user's beneficiary, or no such beneficiary. Same answer for both.
            logger.warn("Beneficiary {} not found for the signed-in user", beneficiaryId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Beneficiary not found"));

        } catch (com.investingapp.backend.web.UserFacingException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage())); // user-facing
            
        } catch (Exception e) {
            logger.error("Error updating beneficiary {}: {}", beneficiaryId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new ErrorResponse("Failed to update beneficiary"));
        }
    }
    
    /**
     * Delete a beneficiary
     */
    @DeleteMapping("/{beneficiaryId}")
    public ResponseEntity<?> deleteBeneficiary(
            @PathVariable Long beneficiaryId,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            beneficiaryService.deleteBeneficiary(user, beneficiaryId);
            
            logger.info("Deleted beneficiary {} for user: {}", beneficiaryId, user.getEmail());
            return ResponseEntity.ok().build();
            
        } catch (NoSuchElementException e) {
            // Not this user's beneficiary, or no such beneficiary. Same answer for both.
            logger.warn("Beneficiary {} not found for the signed-in user", beneficiaryId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Beneficiary not found"));

        } catch (com.investingapp.backend.web.UserFacingException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage())); // user-facing
            
        } catch (Exception e) {
            logger.error("Error deleting beneficiary {}: {}", beneficiaryId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new ErrorResponse("Failed to delete beneficiary"));
        }
    }
    
    /**
     * Submit beneficiary to Alpaca broker system
     */
    @PostMapping("/{beneficiaryId}/submit")
    public ResponseEntity<?> submitToAlpaca(
            @PathVariable Long beneficiaryId,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            beneficiaryService.submitToAlpaca(user, beneficiaryId);
            
            logger.info("Submitted beneficiary {} to Alpaca for user: {}", beneficiaryId, user.getEmail());
            return ResponseEntity.ok().body(new SuccessResponse("Beneficiary submitted to Alpaca successfully"));
            
        } catch (NoSuchElementException e) {
            // Not this user's beneficiary, or no such beneficiary. Same answer for both.
            logger.warn("Beneficiary {} not found for the signed-in user", beneficiaryId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Beneficiary not found"));

        } catch (com.investingapp.backend.web.UserFacingException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage())); // user-facing
            
        } catch (Exception e) {
            logger.error("Error submitting beneficiary {} to Alpaca: {}", beneficiaryId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new ErrorResponse("Failed to submit beneficiary to Alpaca"));
        }
    }
    
    /**
     * Submit all approved beneficiaries to Alpaca broker system
     * This is more efficient than submitting individually since Alpaca replaces the entire beneficiaries array
     */
    @PostMapping("/submit-all")
    public ResponseEntity<?> submitAllToAlpaca(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            beneficiaryService.submitAllBeneficiariesToAlpaca(user);
            
            logger.info("Submitted all beneficiaries to Alpaca for user: {}", user.getEmail());
            return ResponseEntity.ok().body(new SuccessResponse("All beneficiaries submitted to Alpaca successfully"));
            
        } catch (com.investingapp.backend.web.UserFacingException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage())); // user-facing
            
        } catch (Exception e) {
            logger.error("Error submitting all beneficiaries to Alpaca for user {}: {}", authentication.getName(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new ErrorResponse("Failed to submit all beneficiaries to Alpaca"));
        }
    }
    
    /**
     * Get primary beneficiaries
     */
    @GetMapping("/primary")
    public ResponseEntity<List<Beneficiary>> getPrimaryBeneficiaries(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            List<Beneficiary> beneficiaries = beneficiaryService.getPrimaryBeneficiaries(user);
            
            logger.info("Retrieved {} primary beneficiaries for user: {}", beneficiaries.size(), user.getEmail());
            return ResponseEntity.ok(beneficiaries);
            
        } catch (Exception e) {
            logger.error("Error retrieving primary beneficiaries: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Get contingent beneficiaries
     */
    @GetMapping("/contingent")
    public ResponseEntity<List<Beneficiary>> getContingentBeneficiaries(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            List<Beneficiary> beneficiaries = beneficiaryService.getContingentBeneficiaries(user);
            
            logger.info("Retrieved {} contingent beneficiaries for user: {}", beneficiaries.size(), user.getEmail());
            return ResponseEntity.ok(beneficiaries);
            
        } catch (Exception e) {
            logger.error("Error retrieving contingent beneficiaries: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * A request body that fails validation (a mistyped SSN, a missing name).
     *
     * Handled here so the answer and the log line are ours. Left to Spring, the log line would
     * include the rejected value, and the rejected value can be a Social Security Number typed
     * without its dashes. This says which rule failed and never what was sent.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .collect(Collectors.joining(". "));
        if (message.isEmpty()) {
            message = "Invalid beneficiary";
        }
        logger.warn("Beneficiary request failed validation on: {}", e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField).sorted().distinct().collect(Collectors.joining(", ")));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    /**
     * Helper method to get current authenticated user
     */
    private User getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        Optional<User> userOpt = userRepository.findByEmail(email);
        
        if (userOpt.isEmpty()) {
            throw new IllegalStateException("Signed-in user has no account row");
        }
        
        return userOpt.get();
    }
    
    /**
     * Error response DTO
     */
    public static class ErrorResponse {
        private final String error;
        private final long timestamp;
        
        public ErrorResponse(String error) {
            this.error = error;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getError() { return error; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * Success response DTO
     */
    public static class SuccessResponse {
        private final String message;
        private final long timestamp;
        
        public SuccessResponse(String message) {
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }
    }
}
