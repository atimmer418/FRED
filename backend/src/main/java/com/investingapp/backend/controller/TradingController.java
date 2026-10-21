package com.investingapp.backend.controller;

import com.investingapp.backend.service.AlpacaService;
import com.investingapp.backend.service.AlpacaApiService;
import com.investingapp.backend.service.AnalyticsOutboxService;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PortfolioRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Controller for trading operations (buy, sell, withdraw)
 */
@RestController
@RequestMapping("/api/trading")
@CrossOrigin(origins = "*", maxAge = 3600)
public class TradingController {

    private static final Logger logger = LoggerFactory.getLogger(TradingController.class);
    
    @Autowired
    private AlpacaService alpacaService;

    @Autowired
    private AlpacaApiService alpacaApiService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InvestmentScheduleRepository investmentScheduleRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private AnalyticsOutboxService analyticsOutboxService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get current positions for user's account
     */
    @GetMapping("/positions")
    public ResponseEntity<Map<String, Object>> getPositions(Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

            if (user == null || user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User or Alpaca account not found"));
            }

            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

            String positionsJson = alpacaService.getCurrentPositions(accountId);

            if (positionsJson != null) {
                JsonNode positions = objectMapper.readTree(positionsJson);
                Map<String, Object> result = new HashMap<>();
                result.put("positions", positions);
                result.put("account_id", accountId);
                
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No positions found or unable to retrieve positions"));
            }
        } catch (Exception e) {
            logger.error("Error getting positions: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get positions"));
        }
    }

    /**
     * Sell a percentage of a specific stock position
     */
    @PostMapping("/sell/percentage")
    public ResponseEntity<Map<String, Object>> sellByPercentage(@RequestBody SellPercentageRequest request, Authentication authentication) {
        try {
            // Validate request
            if (request.getSymbol() == null || request.getSymbol().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Symbol is required"));
            }
            
            if (request.getPercentage() == null || request.getPercentage().compareTo(BigDecimal.ZERO) <= 0 
                || request.getPercentage().compareTo(new BigDecimal("100")) > 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Percentage must be between 0.01 and 100"));
            }

            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

            if (user == null || user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User or Alpaca account not found"));
            }

            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

            AlpacaService.AlpacaOrderResponse orderResponse = alpacaService.placeSellOrderByPercentage(
                accountId, request.getSymbol().toUpperCase(), request.getPercentage());
            
            Map<String, Object> result = new HashMap<>();
            result.put("order_id", orderResponse.id);
            result.put("symbol", orderResponse.symbol);
            result.put("status", orderResponse.status);
            result.put("percentage", request.getPercentage());
            result.put("submitted_at", orderResponse.submittedAt);
            result.put("success", orderResponse.isSuccess());
            
            if (orderResponse.errorMessage != null) {
                result.put("error", orderResponse.errorMessage);
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Error placing sell order: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to place sell order"));
        }
    }

    /**
     * Liquidate entire portfolio (sell all positions)
     */
    @PostMapping("/liquidate")
    public ResponseEntity<Map<String, Object>> liquidatePortfolio(Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

            if (user == null || user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User or Alpaca account not found"));
            }

            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

            AlpacaService.AlpacaOrderResponse orderResponse = alpacaService.liquidatePortfolio(accountId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("order_id", orderResponse.id);
            result.put("status", orderResponse.status);
            result.put("submitted_at", orderResponse.submittedAt);
            result.put("success", orderResponse.isSuccess());
            result.put("message", "Portfolio liquidation initiated");
            
            if (orderResponse.errorMessage != null) {
                result.put("error", orderResponse.errorMessage);
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Error liquidating portfolio: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to liquidate portfolio"));
        }
    }

    /**
     * Withdraw cash to bank account
     */
    @PostMapping("/withdraw")
    public ResponseEntity<Map<String, Object>> withdrawCash(@RequestBody WithdrawRequest request, Authentication authentication) {
        try {
            // Validate request
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Amount must be greater than 0"));
            }

            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

            if (user == null || user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User or Alpaca account not found"));
            }

            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());
            String relationshipId = user.getAlpacaAchRelationshipId() != null
                    ? encryptionService.decrypt(user.getAlpacaAchRelationshipId()) : null;

            if (relationshipId == null) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "No bank account linked for withdrawal"));
            }
            
            AlpacaService.AlpacaTransferResponse transferResponse = alpacaService.initiateWithdrawal(
                accountId, relationshipId, request.getAmount());
            
            Map<String, Object> result = new HashMap<>();
            result.put("transfer_id", transferResponse.id);
            result.put("status", transferResponse.status);
            result.put("amount", transferResponse.amount);
            result.put("initiated_at", transferResponse.createdAt);
            result.put("success", transferResponse.isSuccess());
            result.put("message", "Withdrawal initiated successfully. Funds typically arrive in your bank account within 1-3 business days.");
            
            if (transferResponse.errorMessage != null) {
                result.put("error", transferResponse.errorMessage);
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Error initiating withdrawal: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to initiate withdrawal"));
        }
    }

    /**
     * Get account balance and buying power
     */
    @GetMapping("/account/balance")
    public ResponseEntity<Map<String, Object>> getAccountBalance(Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

            if (user == null || user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User or Alpaca account not found"));
            }

            String accountInfo = alpacaApiService.getAccountStatus(encryptionService.decrypt(user.getAlpacaAccountId()));
            
            if (accountInfo != null) {
                JsonNode accountJson = objectMapper.readTree(accountInfo);
                
                Map<String, Object> result = new HashMap<>();
                result.put("cash", accountJson.path("cash").asText("0"));
                result.put("buying_power", accountJson.path("buying_power").asText("0"));
                result.put("portfolio_value", accountJson.path("portfolio_value").asText("0"));
                result.put("equity", accountJson.path("equity").asText("0"));
                result.put("account_status", accountJson.path("status").asText("unknown"));
                
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Account information not available"));
            }
            
        } catch (Exception e) {
            logger.error("Error getting account balance: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get account balance"));
        }
    }

    /**
     * Close the user's brokerage account and delete their FRED user record.
     * Prerequisites: all positions must be liquidated and all cash withdrawn.
     */
    @Transactional
    @PostMapping("/account/close")
    public ResponseEntity<Map<String, Object>> closeAccount(Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

            if (user == null || user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User or Alpaca account not found"));
            }

            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

            // Close the Alpaca brokerage account
            alpacaService.closeAccount(accountId);

            // Record the cancellation before anything is deleted: see recordScheduleCancelled.
            recordScheduleCancelled(user);

            // Delete investment schedules first (FK not covered by JPA cascade)
            investmentScheduleRepository.deleteAllByUser(user);

            // Delete user sessions (FK not covered by JPA cascade)
            userSessionRepository.deleteAllByUserId(user.getId());

            // Delete portfolio and its items (FK not covered by JPA cascade; items cascade from portfolio)
            portfolioRepository.findByUser(user).ifPresent(portfolioRepository::delete);

            // Delete the user from our database (cascades to passkey credentials, user progress, etc.)
            userRepository.delete(user);

            logger.info("Account closed and user deleted for alpaca account: {}", accountId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Your account has been permanently closed."
            ));

        } catch (Exception e) {
            logger.error("Error closing account: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to close account. Please ensure all positions are sold and all cash is withdrawn."));
        }
    }

    /**
     * Records schedule_cancelled for a user closing their own account.
     *
     * <p>Called from inside closeAccount's transaction, BEFORE the deletes, while the user still has
     * an id and a public_id for the outbox to copy: in a moment it will have neither. The outbox row
     * has no foreign key to users and denormalises public_id, so it outlives the schedule and the
     * person. AnalyticsOutboxService refuses to write outside a transaction, and closeAccount is
     * annotated {@code @Transactional}, so the event and the deletion commit together or not at all.
     *
     * <p>Only a REAL transition is recorded. The schedule's existence is read BEFORE it is changed,
     * and a user with no schedule emits nothing: deleteAllByUser is a no-op for them, and an event
     * there would invent a cancellation for everyone who closed an account without ever setting a
     * schedule up. These events exist to count churn, so a phantom would inflate the one number they
     * are for.
     *
     * <p>Two properties and nothing else, in this order: who caused it and why. No amount, no
     * frequency, no bank. The initiator is the user, because this endpoint is only ever reached by
     * the account holder asking for it.
     */
    private void recordScheduleCancelled(User user) {
        if (!investmentScheduleRepository.existsByUser(user)) {
            return;
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("initiator", ServerAnalyticsEvent.INITIATOR_USER);
        properties.put("reason", ServerAnalyticsEvent.REASON_ACCOUNT_CLOSED);

        analyticsOutboxService.record(ServerAnalyticsEvent.SCHEDULE_CANCELLED, user, properties);
    }

    // DTOs for request bodies
    public static class SellPercentageRequest {
        private String symbol;
        private BigDecimal percentage;
        
        public SellPercentageRequest() {}
        
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        
        public BigDecimal getPercentage() { return percentage; }
        public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
    }

    public static class WithdrawRequest {
        private BigDecimal amount;
        
        public WithdrawRequest() {}
        
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}