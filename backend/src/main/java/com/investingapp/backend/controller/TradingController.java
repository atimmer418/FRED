package com.investingapp.backend.controller;

import com.investingapp.backend.service.AlpacaService;
import com.investingapp.backend.service.AlpacaApiService;
import com.investingapp.backend.service.AnalyticsOutboxService;
import com.investingapp.backend.service.AnalyticsPersonDeletionService;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    private UserDeletionService userDeletionService;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private AnalyticsOutboxService analyticsOutboxService;

    @Autowired
    private OpsAlertService opsAlertService;

    @Autowired
    private com.investingapp.backend.service.AccountClosureService accountClosureService;



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
     * Not transactional: the broker calls run outside the database transaction that
     * UserDeletionService opens for the row deletes. The cancellation event is handed to that
     * service so it is written inside that one transaction: see recordScheduleCancelled.
     */
    @PostMapping("/account/close")
    public ResponseEntity<?> closeAccount(Authentication authentication) {
        Long closingUserId = null;
        User closingUser = null;
        // True from the moment the broker has closed the brokerage account. After that there is
        // no way back, so whatever goes wrong next, the FRED account must end up shut.
        boolean brokerClosed = false;
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
            closingUserId = user.getId();
            closingUser = user;

            // Refuse before touching the broker: closing the brokerage account and then keeping
            // the FRED row would leave the user half deleted.
            Optional<String> deferral = userDeletionService.deferralReason(user);
            if (deferral.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("DELETION_DEFERRED", deferral.get()));
            }

            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

            // Close the Alpaca brokerage account
            alpacaService.closeAccount(accountId);
            brokerClosed = true;

            // One path for every row that references the user (schedules, sessions, portfolio, ...),
            // with the cancellation recorded as the first statement inside that same transaction:
            // see recordScheduleCancelled. The analytics vendor's person record is deleted inside
            // that transaction too, by UserDeletionService itself, so every deletion reason gets it
            // and not just this one. See FREDdocs/what-we-send.md.
            userDeletionService.delete(user, UserDeletionService.DeletionReason.ACCOUNT_CLOSED,
                () -> recordScheduleCancelled(user));

            logger.info("Account closed and user deleted for alpaca account: {}", accountId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Your account has been permanently closed."
            ));

        } catch (DeletionDeferredException deferred) {
            // A transfer landed between the pre-check and the delete. The brokerage account is
            // already closed, so a person finishes this one through the admin verb.
            logger.error("alert.user_deletion.deferred reason=ACCOUNT_CLOSED deferral={}", deferred.reason);
            opsAlertService.raise(OpsAlerts.USER_DELETION_DEFERRED,
                    closingUserId == null ? OpsAlertService.GLOBAL_SUBJECT : String.valueOf(closingUserId),
                    "brokerage account closed, FRED row deletion deferred",
                    Map.of("reason", "ACCOUNT_CLOSED", "deferral", String.valueOf(deferred.reason)));
            if (brokerClosed) {
                return shutWithRowKept(closingUser, deferred); // not in the reply
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorCodeResponse("DELETION_DEFERRED", deferred.reason));
        } catch (Exception e) {
            logger.error("Error closing account: {}", e.getMessage(), e);
            if (brokerClosed) {
                // The brokerage account is closed and the deletion rolled back. Same position as
                // a deferred deletion: an operator finishes it, and the user is shut out now.
                logger.error("alert.user_deletion.deferred reason=ACCOUNT_CLOSED deferral=DELETE_FAILED");
                opsAlertService.raise(OpsAlerts.USER_DELETION_DEFERRED,
                        closingUserId == null ? OpsAlertService.GLOBAL_SUBJECT : String.valueOf(closingUserId),
                        "brokerage account closed, FRED row deletion failed",
                        Map.of("reason", "ACCOUNT_CLOSED", "deferral", "DELETE_FAILED"));
                return shutWithRowKept(closingUser, e); // not in the reply
            }
            // RETHROW rather than return. The rollback itself is no longer this method's doing:
            // the deletes run inside UserDeletionService's TransactionTemplate, which rolls back
            // on its own when a statement throws. What this keeps is the other half of the fix,
            // that a failed close leaves as a hard failure instead of a tidy return, so a partial
            // close can never be reported as an orderly one. The handler below turns it into the
            // same response the caller used to get.
            throw new AccountCloseFailedException(e);
        }
    }

    /**
     * The brokerage account is closed but the FRED row is staying for now. To the user the account
     * IS closed, so every way into it is removed and the app is told the close succeeded; it then
     * shows the closed screen like any other close. The row waits for an operator (the ops alert
     * has already been raised by the caller).
     *
     * <p>If even this fails, the close is reported as the hard failure it is.
     */
    private ResponseEntity<?> shutWithRowKept(User user, Exception why) {
        try {
            // The user's schedule is over as of now, so the cancellation is recorded here, in the
            // same transaction as the CLOSED mark: the deletion that would have recorded it did
            // not happen, and the operator's later delete records an admin action, not this.
            accountClosureService.shut(user.getId(), () -> recordScheduleCancelled(user));
        } catch (RuntimeException shutFailed) {
            logger.error("Could not shut account after the broker closed it user={}: {}",
                    user.getId(), shutFailed.getMessage(), shutFailed);
            throw new AccountCloseFailedException(why);
        }
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Your account has been permanently closed.",
            "deletionPending", true
        ));
    }

    /** Signals a failed account closure after the transaction has been rolled back. */
    static class AccountCloseFailedException extends RuntimeException {
        AccountCloseFailedException(Throwable cause) {
            super("Failed to close account", cause);
        }
    }

    /**
     * Runs after the transactional proxy has unwound, so the rollback is already done by the time
     * the caller is answered. The body is unchanged from when this was a swallowed exception.
     */
    @ExceptionHandler(AccountCloseFailedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountCloseFailure(
            AccountCloseFailedException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error",
                        "Failed to close account. Please ensure all positions are sold and all cash is withdrawn."));
    }

    /**
     * Records schedule_cancelled for a user closing their own account.
     *
     * <p>Called BEFORE the deletes, while the user still has an id and a public_id for the outbox to
     * copy: in a moment it will have neither. The outbox row has no foreign key to users and
     * denormalises public_id, so it outlives the schedule and the person.
     *
     * <p>Transaction boundary: closeAccount is deliberately NOT {@code @Transactional}, because the
     * broker calls must not run inside a database transaction, and AnalyticsOutboxService refuses to
     * write without one. So this runs as the hook UserDeletionService.delete invokes as the first
     * statement inside its own deletion transaction: the event and the deletes it describes commit
     * together or not at all, and a delete that throws or is deferred leaves no event behind
     * claiming a cancellation whose rows are still there.
     *
     * <p>Only a REAL transition is recorded. The schedule's existence is read BEFORE it is changed,
     * and a user with no schedule emits nothing: the deletion removes no schedule for them,
     * and an event there would invent a cancellation for everyone who closed an account without
     * ever setting a schedule up. These events exist to count churn, so a phantom would inflate the one number they
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