package com.investingapp.backend.service;

import com.investingapp.backend.dto.PaycheckSourceDto;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserPaycheckConfig;
import com.investingapp.backend.repository.UserPaycheckConfigRepository;
import com.investingapp.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PaycheckDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckDetectionService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPaycheckConfigRepository paycheckConfigRepository;

    @Autowired
    private PlaidService plaidService;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private PlaidWebhookService plaidWebhookService;

    /**
     * Scheduled job that runs daily to check for undetected paychecks
     * Runs every day at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void detectPaychecksDaily() {
        logger.info("Starting daily paycheck detection job");
        
        try {
            List<UserPaycheckConfig> undetectedPaychecks = paycheckConfigRepository.findByPaycheckDetectedFalse();
            logger.info("Found {} undetected paycheck configurations to process", undetectedPaychecks.size());
            
            for (UserPaycheckConfig config : undetectedPaychecks) {
                try {
                    processPaycheckDetection(config);
                } catch (Exception e) {
                    logger.error("Error processing paycheck detection for config ID {}: {}", config.getId(), e.getMessage(), e);
                    // Continue with next config even if this one fails
                }
            }
            
        } catch (Exception e) {
            logger.error("Error in daily paycheck detection job: {}", e.getMessage(), e);
        }
        
        logger.info("Completed daily paycheck detection job");
    }

    /**
     * Manual trigger for paycheck detection - can be called via API endpoint
     */
    @Transactional
    public void detectPaychecksForUser(Long userId) {
        logger.info("Manually triggering paycheck detection for user ID: {}", userId);
        
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            logger.warn("User not found with ID: {}", userId);
            return;
        }
        
        List<UserPaycheckConfig> configs = paycheckConfigRepository.findByUserAndPaycheckDetectedFalse(user);
        logger.info("Found {} undetected paycheck configurations for user {}", configs.size(), userId);
        
        for (UserPaycheckConfig config : configs) {
            try {
                processPaycheckDetection(config);
            } catch (Exception e) {
                logger.error("Error processing paycheck detection for config ID {}: {}", config.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Core logic to detect and match a paycheck configuration with Plaid recurring transactions
     */
    private void processPaycheckDetection(UserPaycheckConfig config) {
        logger.info("Processing paycheck detection for config ID: {} (user: {})", config.getId(), config.getUser().getId());
        
        // Update last detection attempt
        config.setLastDetectionAttempt(LocalDateTime.now());
        paycheckConfigRepository.save(config);
        
        User user = config.getUser();
        if (user.getPlaidAccessToken() == null) {
            logger.warn("User {} has no Plaid access token, skipping paycheck detection", user.getId());
            return;
        }
        
        try {
            // Decrypt access token and get recurring income
            String decryptedToken = encryptionService.decrypt(user.getPlaidAccessToken());
            List<PaycheckSourceDto> recurringIncome = plaidService.getRecurringIncome(decryptedToken);
            
            if (recurringIncome.isEmpty()) {
                logger.info("No recurring income found for user {}, paycheck detection not ready yet", user.getId());
                return;
            }
            
            // Try to match the config with detected recurring income
            PaycheckSourceDto matchedPaycheck = findMatchingPaycheck(config, recurringIncome);
            
            if (matchedPaycheck != null) {
                logger.info("Successfully matched paycheck config ID {} with Plaid stream for account {}", 
                    config.getId(), matchedPaycheck.getAccountId());
                
                // Update config with detection info
                config.setPaycheckDetected(true);
                config.setPaycheckDetectedAt(LocalDateTime.now());
                config.setPlaidStreamId(matchedPaycheck.getAccountId()); // Using accountId as stream identifier
                paycheckConfigRepository.save(config);
                
                // Set up webhook for this paycheck
                setupWebhookForPaycheck(config, matchedPaycheck);
                
            } else {
                logger.info("No matching paycheck found for config ID {} yet", config.getId());
            }
            
        } catch (IOException e) {
            logger.error("Error fetching recurring income for user {}: {}", user.getId(), e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error during paycheck detection for config ID {}: {}", config.getId(), e.getMessage(), e);
        }
    }

    /**
     * Match a user's paycheck configuration with detected recurring income from Plaid
     */
    private PaycheckSourceDto findMatchingPaycheck(UserPaycheckConfig config, List<PaycheckSourceDto> recurringIncome) {
        logger.info("Attempting to match config: name='{}', employer='{}', expectedAmount={}, frequency='{}'",
            config.getName(), config.getEmployerName(), config.getExpectedAmount(), config.getFrequency());
        
        for (PaycheckSourceDto paycheck : recurringIncome) {
            logger.info("Checking paycheck: name='{}', amount={}, frequency='{}'", 
                paycheck.getName(), paycheck.getLastAmount(), paycheck.getFrequency());
            
            boolean isMatch = isPaycheckMatch(config, paycheck);
            if (isMatch) {
                logger.info("Found matching paycheck: {}", paycheck.getName());
                return paycheck;
            }
        }
        
        return null;
    }

    /**
     * Determine if a Plaid recurring transaction matches a user's paycheck configuration
     */
    private boolean isPaycheckMatch(UserPaycheckConfig config, PaycheckSourceDto paycheck) {
        int matchScore = 0;
        int totalCriteria = 0;
        
        // 1. Check frequency match (high importance)
        if (config.getFrequency() != null && paycheck.getFrequency() != null) {
            totalCriteria++;
            if (frequenciesMatch(config.getFrequency(), paycheck.getFrequency())) {
                matchScore++;
                logger.debug("Frequency match: {} == {}", config.getFrequency(), paycheck.getFrequency());
            }
        }
        
        // 2. Check amount match (high importance) - within 20% tolerance
        if (config.getExpectedAmount() != null && paycheck.getLastAmount() != null && 
            config.getExpectedAmount().compareTo(BigDecimal.ZERO) > 0) {
            totalCriteria++;
            BigDecimal expectedAmount = config.getExpectedAmount();
            BigDecimal actualAmount = paycheck.getLastAmount();
            
            // Calculate percentage difference
            BigDecimal difference = expectedAmount.subtract(actualAmount).abs();
            BigDecimal percentageDiff = difference.divide(expectedAmount, 4, java.math.RoundingMode.HALF_UP);
            
            if (percentageDiff.compareTo(new BigDecimal("0.20")) <= 0) { // Within 20%
                matchScore++;
                logger.debug("Amount match within tolerance: expected={}, actual={}, diff={}%", 
                    expectedAmount, actualAmount, percentageDiff.multiply(new BigDecimal("100")));
            }
        }
        
        // 3. Check name/employer match (medium importance)
        if (config.getEmployerName() != null && paycheck.getName() != null) {
            totalCriteria++;
            String configEmployer = config.getEmployerName().toLowerCase().trim();
            String paycheckName = paycheck.getName().toLowerCase().trim();
            
            // Check if employer name is contained in the paycheck name or vice versa
            if (paycheckName.contains(configEmployer) || configEmployer.contains(paycheckName)) {
                matchScore++;
                logger.debug("Name/employer match: '{}' matches '{}'", configEmployer, paycheckName);
            }
        }
        
        // 4. Check account ID match (if manually configured)
        if (config.getAccountId() != null && paycheck.getAccountId() != null) {
            totalCriteria++;
            if (config.getAccountId().equals(paycheck.getAccountId())) {
                matchScore++;
                logger.debug("Account ID exact match: {}", config.getAccountId());
            }
        }
        
        // Require at least 2 matches out of available criteria, or 1 match if only 1-2 criteria available
        boolean isMatch = false;
        if (totalCriteria >= 3) {
            isMatch = matchScore >= 2; // Need at least 2 matches for 3+ criteria
        } else if (totalCriteria >= 1) {
            isMatch = matchScore >= 1; // Need at least 1 match for 1-2 criteria
        }
        
        logger.debug("Match evaluation: {}/{} criteria matched, result: {}", matchScore, totalCriteria, isMatch);
        return isMatch;
    }

    /**
     * Check if frequency strings represent the same frequency
     */
    private boolean frequenciesMatch(String configFreq, String plaidFreq) {
        if (configFreq == null || plaidFreq == null) return false;
        
        String config = configFreq.toUpperCase().trim();
        String plaid = plaidFreq.toUpperCase().trim();
        
        // Direct match
        if (config.equals(plaid)) return true;
        
        // Handle common variations
        if ((config.equals("BIWEEKLY") || config.equals("BI_WEEKLY")) && 
            (plaid.equals("BIWEEKLY") || plaid.equals("BI_WEEKLY"))) {
            return true;
        }
        
        if ((config.equals("SEMI_MONTHLY") || config.equals("SEMIMONTHLY")) && 
            (plaid.equals("SEMI_MONTHLY") || plaid.equals("SEMIMONTHLY"))) {
            return true;
        }
        
        return false;
    }

    /**
     * Set up webhook configuration for a successfully detected paycheck
     */
    private void setupWebhookForPaycheck(UserPaycheckConfig config, PaycheckSourceDto matchedPaycheck) {
        try {
            logger.info("Setting up webhook for paycheck config ID: {}", config.getId());
            
            // For now, we'll just mark the webhook as configured
            // The actual webhook setup depends on your Plaid webhook configuration
            // which should already be configured at the application level
            config.setWebhookConfigured(true);
            config.setWebhookConfiguredAt(LocalDateTime.now());
            paycheckConfigRepository.save(config);
            
            logger.info("Webhook configured for paycheck config ID: {}", config.getId());
            
        } catch (Exception e) {
            logger.error("Error setting up webhook for config ID {}: {}", config.getId(), e.getMessage(), e);
        }
    }
}
