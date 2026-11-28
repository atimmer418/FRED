package com.investingapp.backend.controller;

import com.investingapp.backend.dto.InvestmentExecutionResponse;
import com.investingapp.backend.dto.InvestmentTradeResponse;
import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.InvestmentTrade;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentTradeRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.InvestmentExecutionService;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ExecutionKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/investments")
@CrossOrigin(origins = "*")
public class InvestmentController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(InvestmentController.class);

    @Autowired
    private InvestmentExecutionRepository executionRepository;

    @Autowired
    private InvestmentTradeRepository tradeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InvestmentExecutionService investmentExecutionService;

    /**
     * Get investment history for the authenticated user
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getInvestmentHistory(Authentication authentication) {
        try {
            String email = authentication.getName();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "User not found"));
            }

            User user = userOpt.get();

            // Get recent executions (last 6 months)
            LocalDateTime since = EasternTime.now().minusMonths(6);
            List<InvestmentExecution> executions = executionRepository
                    .findRecentExecutionsForUser(user.getId(), since);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            // A fixed list of fields per execution. The entity itself must never go in a response:
            // it leads to the whole user record.
            response.put("executions", executions.stream().map(InvestmentExecutionResponse::of).toList());
            response.put("nextInvestmentDate", user.getNextInvestmentDate());
            response.put("monthlyInvestment", user.getMonthlyInvestment());
            response.put("payFrequency", user.getPayFrequency());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error retrieving investment history", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Error retrieving investment history"));
        }
    }

    /**
     * Get details for a specific investment execution
     */
    @GetMapping("/execution/{executionId}")
    public ResponseEntity<Map<String, Object>> getExecutionDetails(
            @PathVariable Long executionId,
            Authentication authentication) {
        try {
            String email = authentication.getName();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "User not found"));
            }

            // Not there, or somebody else's: the same answer, so an id cannot be probed.
            InvestmentExecution execution = executionRepository.findById(executionId)
                    .filter(found -> found.getUser().getId().equals(userOpt.get().getId()))
                    .orElse(null);
            if (execution == null) {
                return ResponseEntity.status(404)
                        .body(Map.of("success", false, "message", "Investment execution not found"));
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("execution", InvestmentExecutionResponse.of(execution));
            response.put("trades", tradeRepository.findByInvestmentExecutionId(executionId).stream()
                    .map(InvestmentTradeResponse::of).toList());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error retrieving execution details", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Error retrieving execution details"));
        }
    }

    /**
     * Update investment schedule settings
     */
    @PostMapping("/schedule")
    public ResponseEntity<Map<String, Object>> updateInvestmentSchedule(
            @RequestBody Map<String, Object> request,
            Authentication authentication) {
        try {
            String email = authentication.getName();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "User not found"));
            }

            User user = userOpt.get();

            // Update investment schedule fields
            if (request.containsKey("monthlyInvestment")) {
                Object amount = request.get("monthlyInvestment");
                if (amount instanceof Number) {
                    user.setMonthlyInvestment(((Number) amount).doubleValue());
                }
            }

            if (request.containsKey("payFrequency")) {
                String frequency = com.investingapp.backend.web.RequestValues.string(request, "payFrequency");
                if (isValidPayFrequency(frequency)) {
                    user.setPayFrequency(frequency);
                }
            }

            if (request.containsKey("nextInvestmentDate")) {
                String dateStr = com.investingapp.backend.web.RequestValues.string(request, "nextInvestmentDate");
                try {
                    LocalDate nextDate = LocalDate.parse(dateStr);
                    user.setNextInvestmentDate(nextDate);
                } catch (Exception e) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Invalid date format"));
                }
            }

            userRepository.save(user);

            // A HashMap, not Map.of: any of these three can be unset, and Map.of throws on null,
            // which used to turn a saved update into a 500.
            Map<String, Object> updated = new HashMap<>();
            updated.put("success", true);
            updated.put("message", "Investment schedule updated successfully");
            updated.put("nextInvestmentDate", user.getNextInvestmentDate());
            updated.put("monthlyInvestment", user.getMonthlyInvestment());
            updated.put("payFrequency", user.getPayFrequency());
            return ResponseEntity.ok(updated);

        } catch (com.investingapp.backend.web.UserFacingException e) {
            throw e; // a bad value in the request: answered as a 400 by ApiExceptionAdvice
        } catch (Exception e) {
            logger.error("Error updating investment schedule", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Error updating investment schedule"));
        }
    }

    /**
     * Create a one-time investment execution (lump sum investment)
     * This immediately initiates ACH transfer and begins the investment process
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> createManualInvestment(
            @RequestBody Map<String, Object> request,
            Authentication authentication) {
        try {
            String email = authentication.getName();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "User not found"));
            }

            User user = userOpt.get();

            // Validate user has required setup for investments
            if (user.getAlpacaAccountId() == null || user.getPlaidRelationshipId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message",
                                "Account setup incomplete. Please complete your bank account and investment account setup first."));
            }

            // Validate required fields
            if (!request.containsKey("amount")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Amount is required"));
            }

            Object amountObj = request.get("amount");
            BigDecimal amount;
            if (amountObj instanceof Number) {
                amount = new BigDecimal(amountObj.toString());
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Invalid amount format"));
            }

            // Validate minimum amount
            if (amount.compareTo(BigDecimal.ONE) < 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Minimum investment amount is $1"));
            }

            // Validate maximum amount (reasonable limit)
            if (amount.compareTo(new BigDecimal("1000000")) > 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Maximum investment amount is $1,000,000"));
            }

            // Parse investment type (portfolio vs individual stock)
            String investmentType = "portfolio"; // default
            if (request.containsKey("type")) {
                investmentType = com.investingapp.backend.web.RequestValues.string(request, "type");
            }

            String selectedSymbol = null;
            if ("stock".equals(investmentType)) {
                if (!request.containsKey("symbol") || request.get("symbol") == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message",
                                    "Symbol is required for individual stock investments"));
                }
                selectedSymbol = com.investingapp.backend.web.RequestValues.string(request, "symbol");

                if (selectedSymbol.trim().isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Invalid stock symbol"));
                }
            }

            // Parse funding source
            String fundingSource = "bank"; // default
            if (request.containsKey("fundingSource")) {
                fundingSource = com.investingapp.backend.web.RequestValues.string(request, "fundingSource");
            }

            // Idempotency (decision 18): one row per tap. A retry carrying the same clientUuid
            // finds the first row and makes no second one. Older clients send none, so a
            // server-side UUID keeps the current app working.
            String clientUuid = resolveClientUuid(request.get("clientUuid"));
            if (clientUuid == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Invalid clientUuid"));
            }
            String sourceKey = ExecutionKeys.lump(user.getId(), clientUuid);
            Optional<InvestmentExecution> existing = executionRepository.findBySourceKey(sourceKey);
            if (existing.isPresent()) {
                InvestmentExecution duplicate = existing.get();
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Investment already queued.",
                        "executionId", duplicate.getId(),
                        "amount", duplicate.getAmount(),
                        "status", duplicate.getStatus(),
                        "queued", true,
                        "duplicate", true));
            }

            // Create investment execution with type and symbol information
            InvestmentExecution execution = new InvestmentExecution(
                    user,
                    EasternTime.now(),
                    amount,
                    investmentType,
                    selectedSymbol,
                    fundingSource);
            execution.setSourceKey(sourceKey);
            // nightKey stays null: one-per-night is for recurring rows only (spec 5.4)

            execution = executionRepository.save(execution);

            try {
                // Process based on funding source
                if ("buying_power".equals(fundingSource)) {
                // Buying power executions are immediate
                investmentExecutionService.processInvestmentExecutionImmediately(execution);
            } else {
                // Bank fundings are queued for daily batch to prevent multiple ACH transfers
                // The QUEUE_LUMP_SUMS_UNTIL_EOD flag in service determines if it waits or runs immediately
                // but processInvestmentExecutionImmediately handles that check internally now
                investmentExecutionService.processInvestmentExecutionImmediately(execution);
            }

            // Return response based on execution status
            execution = executionRepository.findById(execution.getId()).orElse(execution);

            if (InvestmentExecution.ExecutionStatus.SCHEDULED.equals(execution.getStatus()) &&
                    execution.getErrorMessage() != null &&
                    (execution.getErrorMessage().contains("Queued for batch processing") ||
                            execution.getErrorMessage().contains("Queued for end-of-day"))) {

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message",
                        "Investment queued successfully. All investments will be processed at end of day.",
                        "executionId", execution.getId(),
                        "amount", execution.getAmount(),
                        "status", execution.getStatus(),
                        "queued", true));
            } else {
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "message",
                            "Investment queued successfully. All investments will be processed at end of day.",
                            "executionId", execution.getId(),
                            "amount", execution.getAmount(),
                            "status", execution.getStatus(),
                            "queued", true));
                }

            } catch (com.investingapp.backend.web.UserFacingException refused) {
                // Refused before any money moved, for a reason written for the user: a tax-loss
                // harvest window on a stock buy, or not enough buying power. A 400 with the reason.
                String reason = refused.getMessage(); // user-facing
                execution.setStatus(InvestmentExecution.ExecutionStatus.FAILED);
                execution.setErrorMessage(reason);
                executionRepository.save(execution);

                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "success", false,
                                "message", reason,
                                "executionId", execution.getId()));
            } catch (Exception processingException) {
                // Anything else is ours or the broker's to explain, not the user's to read. The
                // detail stays in the log and on the execution row (which no reply carries).
                logger.error("Failed to initiate investment for execution {}", execution.getId(), processingException);
                execution.setStatus(InvestmentExecution.ExecutionStatus.FAILED);
                execution.setErrorMessage("Failed to initiate investment: " + detailOf(processingException));
                executionRepository.save(execution);

                return ResponseEntity.internalServerError()
                        .body(Map.of(
                                "success", false,
                                "message", "Failed to initiate investment. Please try again.",
                                "executionId", execution.getId()));
            }

        } catch (com.investingapp.backend.web.UserFacingException e) {
            throw e; // a bad value in the request: answered as a 400 by ApiExceptionAdvice
        } catch (Exception e) {
            logger.error("Error creating lump sum investment", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Error creating lump sum investment"));
        }
    }

    /** The detail of a failure, for the execution row only. It is never put in a reply. */
    private static String detailOf(Exception failure) {
        return String.valueOf(failure.getMessage());
    }

    /**
     * Get investment dashboard summary
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard(@AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = userRepository.findByEmail(userDetails.getUsername())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Get investment statistics
            List<InvestmentExecution> executions = executionRepository.findByUserOrderByCreatedAtDesc(user);

            double totalInvested = executions.stream()
                    .filter(e -> e.getStatus() == ExecutionStatus.COMPLETED)
                    .mapToDouble(e -> e.getAmount().doubleValue())
                    .sum();

            long pendingCount = executions.stream()
                    .filter(e -> e.getStatus() == ExecutionStatus.SCHEDULED ||
                            e.getStatus() == ExecutionStatus.FUNDING_INITIATED ||
                            e.getStatus() == ExecutionStatus.TRADING_INITIATED)
                    .count();

            long failedCount = executions.stream()
                    .filter(e -> e.getStatus() == ExecutionStatus.FAILED ||
                            e.getStatus() == ExecutionStatus.FUNDING_FAILED ||
                            e.getStatus() == ExecutionStatus.TRADING_FAILED)
                    .count();

            // Get recent executions (last 10)
            List<InvestmentExecution> recentExecutions = executions.stream()
                    .limit(10)
                    .collect(Collectors.toList());

            Map<String, Object> dashboard = new HashMap<>();
            dashboard.put("success", true);
            dashboard.put("totalInvested", totalInvested);
            dashboard.put("pendingCount", pendingCount);
            dashboard.put("failedCount", failedCount);
            dashboard.put("nextInvestmentDate", user.getNextInvestmentDate());
            dashboard.put("monthlyInvestment", user.getMonthlyInvestment() != null ? user.getMonthlyInvestment() : 0.0);
            dashboard.put("payFrequency", user.getPayFrequency() != null ? user.getPayFrequency() : "Not set");
            dashboard.put("recentExecutions", recentExecutions.stream().map(InvestmentExecutionResponse::of).toList());

            return ResponseEntity.ok(dashboard);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            logger.error("Failed to load investment dashboard", e);
            errorResponse.put("message", "Failed to load dashboard");
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    private boolean isValidPayFrequency(String frequency) {
        return frequency != null &&
                (frequency.equals("weekly") || frequency.equals("biweekly") ||
                        frequency.equals("monthly") || frequency.equals("semimonthly"));
    }

    /** The client's per-tap UUID normalized to lowercase; a fresh one when absent; null when present but not a UUID. */
    static String resolveClientUuid(Object raw) {
        if (raw == null || raw.toString().isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(raw.toString().trim().toLowerCase(Locale.ROOT)).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
