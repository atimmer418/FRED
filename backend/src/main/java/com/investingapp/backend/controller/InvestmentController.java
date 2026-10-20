package com.investingapp.backend.controller;

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
            response.put("executions", executions);
            response.put("nextInvestmentDate", user.getNextInvestmentDate());
            response.put("monthlyInvestment", user.getMonthlyInvestment());
            response.put("payFrequency", user.getPayFrequency());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message",
                            "Error retrieving investment history: " + e.getMessage()));
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

            Optional<InvestmentExecution> executionOpt = executionRepository.findById(executionId);

            if (executionOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Investment execution not found"));
            }

            InvestmentExecution execution = executionOpt.get();

            // Verify the execution belongs to the authenticated user
            if (!execution.getUser().getId().equals(userOpt.get().getId())) {
                return ResponseEntity.status(403)
                        .body(Map.of("success", false, "message", "Access denied"));
            }

            // Get associated trades
            List<InvestmentTrade> trades = tradeRepository.findByInvestmentExecutionId(executionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("execution", execution);
            response.put("trades", trades);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Error retrieving execution details: " + e.getMessage()));
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
                String frequency = (String) request.get("payFrequency");
                if (isValidPayFrequency(frequency)) {
                    user.setPayFrequency(frequency);
                }
            }

            if (request.containsKey("nextInvestmentDate")) {
                String dateStr = (String) request.get("nextInvestmentDate");
                try {
                    LocalDate nextDate = LocalDate.parse(dateStr);
                    user.setNextInvestmentDate(nextDate);
                } catch (Exception e) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Invalid date format"));
                }
            }

            userRepository.save(user);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Investment schedule updated successfully",
                    "nextInvestmentDate", user.getNextInvestmentDate(),
                    "monthlyInvestment", user.getMonthlyInvestment(),
                    "payFrequency", user.getPayFrequency()));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Error updating investment schedule: " + e.getMessage()));
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
                investmentType = (String) request.get("type");
            }

            String selectedSymbol = null;
            if ("stock".equals(investmentType)) {
                if (!request.containsKey("symbol") || request.get("symbol") == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message",
                                    "Symbol is required for individual stock investments"));
                }
                selectedSymbol = (String) request.get("symbol");

                if (selectedSymbol.trim().isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Invalid stock symbol"));
                }
            }

            // Parse funding source
            String fundingSource = "bank"; // default
            if (request.containsKey("fundingSource")) {
                fundingSource = (String) request.get("fundingSource");
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

            } catch (IllegalArgumentException rejectedException) {
                // Request rejected before any money moved (e.g. TLH wash-sale
                // window on an explicit stock buy) — a 400 with the reason
                execution.setStatus(InvestmentExecution.ExecutionStatus.FAILED);
                execution.setErrorMessage(rejectedException.getMessage());
                executionRepository.save(execution);

                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "success", false,
                                "message", rejectedException.getMessage(),
                                "executionId", execution.getId()));
            } catch (Exception processingException) {
                // If processing fails, update execution status and inform user
                execution.setStatus(InvestmentExecution.ExecutionStatus.FAILED);
                execution.setErrorMessage("Failed to initiate investment: " + processingException.getMessage());
                executionRepository.save(execution);

                return ResponseEntity.internalServerError()
                        .body(Map.of(
                                "success", false,
                                "message", "Failed to initiate investment: " + processingException.getMessage(),
                                "executionId", execution.getId()));
            }

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Error creating lump sum investment: " + e.getMessage()));
        }
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
            dashboard.put("recentExecutions", recentExecutions);

            return ResponseEntity.ok(dashboard);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to load dashboard: " + e.getMessage());
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
