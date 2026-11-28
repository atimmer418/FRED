package com.investingapp.backend.controller;

import com.investingapp.backend.dto.MonthlyFreedomUpdateDTO;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.MonthlyFreedomUpdateService;
import com.investingapp.backend.service.PortfolioDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/monthly-freedom-update")
@CrossOrigin(origins = "*", maxAge = 3600)
public class MonthlyFreedomUpdateController {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyFreedomUpdateController.class);

    @Autowired
    private MonthlyFreedomUpdateService monthlyFreedomUpdateService;

    @Autowired
    private PortfolioDashboardService portfolioDashboardService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Check if the Monthly Freedom Update should be shown.
     * Called on app launch / tab1 load.
     * Returns { shouldShow: true/false }
     */
    @GetMapping("/check")
    public ResponseEntity<?> checkShouldShow() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "User not authenticated"));
            }

            // Get current equity from portfolio dashboard
            BigDecimal currentEquity = BigDecimal.ZERO;
            try {
                if (user.getAlpacaAccountId() != null) {
                    PortfolioDashboardService.PortfolioDashboardData dashboard =
                            portfolioDashboardService.getPortfolioDashboard(user);
                    currentEquity = dashboard.summary.equity;
                }
            } catch (Exception e) {
                logger.warn("Could not fetch portfolio data for MFU check: {}", e.getMessage());
            }

            MonthlyFreedomUpdateDTO result = monthlyFreedomUpdateService.checkShouldShow(user, currentEquity);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Error checking Monthly Freedom Update status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to check monthly update status"));
        }
    }

    /**
     * Generate and return the full Monthly Freedom Update data.
     * Called when shouldShow=true, or when reopened from FRED tab.
     */
    @GetMapping("/generate")
    public ResponseEntity<?> generateUpdate(@RequestParam(defaultValue = "false") boolean reopen) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "User not authenticated"));
            }

            if (user.getAlpacaAccountId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "User does not have an Alpaca account"));
            }

            // Get current portfolio data
            PortfolioDashboardService.PortfolioDashboardData dashboard =
                    portfolioDashboardService.getPortfolioDashboard(user);
            BigDecimal currentEquity = dashboard.summary.equity;

            // Get ALL-time portfolio history for equity lookups
            PortfolioDashboardService.PortfolioHistory history =
                    portfolioDashboardService.getPortfolioHistoryForPeriod(user, "ALL");

            MonthlyFreedomUpdateDTO result = monthlyFreedomUpdateService.generateUpdate(
                    user, currentEquity, history, dashboard.positions, reopen);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Error generating Monthly Freedom Update", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate monthly update"));
        }
    }

    /**
     * Mark the update as seen (update lastLoggedInMonth without re-generating).
     * Called when user dismisses the modal.
     */
    @PostMapping("/dismiss")
    public ResponseEntity<?> dismissUpdate(@RequestBody(required = false) Map<String, String> body) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "User not authenticated"));
            }

            // seenMonth is the server-produced DTO.generatedForMonth echoed by the client (may be
            // absent — an empty/legacy body degrades to now()). commitMfuSeen marks the month seen and
            // advances mfuCount / freedom-estimate exactly once under a pessimistic row lock.
            String seenMonth = body != null ? body.get("seenMonth") : null;
            monthlyFreedomUpdateService.commitMfuSeen(user.getId(), seenMonth);

            return ResponseEntity.ok(Map.of("success", true));

        } catch (Exception e) {
            logger.error("Error dismissing Monthly Freedom Update", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to dismiss monthly update"));
        }
    }

    /**
     * Helper method to get current authenticated user
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication.getPrincipal() instanceof UserDetailsImpl)) {
            logger.warn("Authentication principal is not an instance of UserDetailsImpl");
            return null;
        }

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        Optional<User> userOpt = userRepository.findById(userDetails.getId());

        if (userOpt.isEmpty()) {
            logger.warn("User ID {} not found from authenticated principal", userDetails.getId());
            return null;
        }

        return userOpt.get();
    }
}
