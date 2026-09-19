package com.investingapp.backend.controller;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.PortfolioDashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/portfolio")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PortfolioDashboardController {
    
    private static final Logger logger = LoggerFactory.getLogger(PortfolioDashboardController.class);
    
    @Autowired
    private PortfolioDashboardService portfolioDashboardService;
    
    @Autowired
    private UserRepository userRepository;
    
    /**
     * Get comprehensive portfolio dashboard data
     */
    @GetMapping("/dashboard")
    public ResponseEntity<?> getPortfolioDashboard() {
        try {
            User user = getCurrentUser();
            
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not authenticated"));
            }
            
            logger.info("Fetching portfolio dashboard for user: {}", user.getEmail());
            
            PortfolioDashboardService.PortfolioDashboardData dashboardData = 
                portfolioDashboardService.getPortfolioDashboard(user);
            
            return ResponseEntity.ok(dashboardData);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request for portfolio dashboard: {}", e.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logger.error("Error fetching portfolio dashboard", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"error\":\"Failed to fetch portfolio data\"}");
        }
    }
    
    /**
     * Get portfolio history for specific time period
     */
    @GetMapping("/history")
    public ResponseEntity<?> getPortfolioHistory(
            @RequestParam(defaultValue = "1M") String period) {
        try {
            User user = getCurrentUser();
            
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not authenticated"));
            }
            
            logger.info("Fetching portfolio history for user: {} with period: {}", user.getEmail(), period);
            
            // Validate period parameter
            if (!isValidPeriod(period)) {
                return ResponseEntity.badRequest().body("{\"error\":\"Invalid period. Use 1D, 1W, 1M, 3M, 1Y, or ALL\"}");
            }
            
            if (user.getAlpacaAccountId() == null) {
                return ResponseEntity.badRequest().body("{\"error\":\"User does not have an Alpaca account\"}");
            }
            
            // Get portfolio history for the specific period
            PortfolioDashboardService.PortfolioHistory portfolioHistory = 
                portfolioDashboardService.getPortfolioHistoryForPeriod(user, period);
            
            // Return the history object directly (it contains timestamps/values/profitLoss/profitLossPercent)
            // The frontend expects the history object itself, not wrapped in "history" key for this endpoint?
            // Checking frontend service: getPortfolioHistory returns Observable<PortfolioHistory>
            // So we should return portfolioHistory directly.
            // Previous code: result.put("history", portfolioHistory); return result;
            // But PortfolioService.ts expects: timestamps, values.
            // If we wrapped it in { history: ... } frontend would need data.history.timestamps
            // Checking PortfolioService.ts: getPortfolioHistory calls .get<any>(...).pipe(...)
            // It seems acceptable to return just the object.
            
            return ResponseEntity.ok(portfolioHistory);
            
        } catch (Exception e) {
            logger.error("Error fetching portfolio history", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"error\":\"Failed to fetch portfolio history\"}");
        }
    }
    
    /**
     * Get current positions/holdings
     */
    @GetMapping("/positions")
    public ResponseEntity<?> getCurrentPositions() {
        try {
            User user = getCurrentUser();
            
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not authenticated"));
            }
            
            logger.info("Fetching current positions for user: {}", user.getEmail());
            
            if (user.getAlpacaAccountId() == null) {
                return ResponseEntity.badRequest().body("{\"error\":\"User does not have an Alpaca account\"}");
            }
            
            PortfolioDashboardService.PortfolioDashboardData dashboardData = 
                portfolioDashboardService.getPortfolioDashboard(user);
            
            // Return just the positions portion
            Map<String, Object> result = new HashMap<>();
            result.put("positions", dashboardData.positions);
            result.put("summary", dashboardData.summary);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Error fetching current positions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"error\":\"Failed to fetch positions\"}");
        }
    }
    
    /**
     * Get performance metrics summary
     */
    @GetMapping("/performance")
    public ResponseEntity<?> getPerformanceMetrics() {
        try {
            User user = getCurrentUser();
            
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not authenticated"));
            }
            
            logger.info("Fetching performance metrics for user: {}", user.getEmail());
            
            if (user.getAlpacaAccountId() == null) {
                return ResponseEntity.badRequest().body("{\"error\":\"User does not have an Alpaca account\"}");
            }
            
            PortfolioDashboardService.PortfolioDashboardData dashboardData = 
                portfolioDashboardService.getPortfolioDashboard(user);
            
            // Return performance-focused data as an array of period-based metrics
            List<Map<String, Object>> performanceMetrics = new ArrayList<>();
            
            // Daily performance
            Map<String, Object> dailyPerf = new HashMap<>();
            dailyPerf.put("period", "Today");
            
            // Check if today is a trading day
            if (!isTradingDay()) {
                // Market is closed today, so no intraday change
                BigDecimal yesterdayEODValue = dashboardData.summary.portfolioValue;
                
                dailyPerf.put("startValue", yesterdayEODValue);
                dailyPerf.put("endValue", yesterdayEODValue);  // No change since market is closed
                dailyPerf.put("totalReturn", BigDecimal.ZERO);   // No change when market is closed
                dailyPerf.put("totalReturnPercent", BigDecimal.ZERO);  // No change when market is closed
                logger.info("Market is closed today, setting today's performance to zero");
            } else {
                // Market is open, calculate real performance
                // For today's performance: yesterday's EOD close → current real-time value
                // portfolioValue is now the Current Real-Time Value (updated in Service)
                BigDecimal currentRealTimeValue = dashboardData.summary.portfolioValue;
                BigDecimal yesterdayEODValue = currentRealTimeValue.subtract(dashboardData.summary.todayChange);
                
                dailyPerf.put("startValue", yesterdayEODValue);     // Yesterday's EOD closing value
                dailyPerf.put("endValue", currentRealTimeValue);   // Current real-time value  
                dailyPerf.put("totalReturn", dashboardData.summary.todayChange);
                dailyPerf.put("totalReturnPercent", dashboardData.summary.todayChangePercent);
                logger.info("Market is open, calculated today's performance: {} ({}%)", 
                    dashboardData.summary.todayChange, dashboardData.summary.todayChangePercent);
            }
            performanceMetrics.add(dailyPerf);

            // Middle period rows: 1W, 1M, 3M, 6M, YTD, 1Y — windows older than the account are omitted
            BigDecimal currentEquity = dashboardData.summary.portfolioValue;
            List<Map<String, Object>> periodRows =
                    portfolioDashboardService.computePerformancePeriodRows(user, currentEquity);
            performanceMetrics.addAll(periodRows);

            // Overall performance since investment start
            Map<String, Object> totalPerf = new HashMap<>();
            totalPerf.put("period", "Total");
            totalPerf.put("startValue", dashboardData.totalInvested);
            totalPerf.put("endValue", dashboardData.summary.portfolioValue);
            totalPerf.put("totalReturn", dashboardData.totalGainLoss);
            totalPerf.put("totalReturnPercent", dashboardData.totalGainLossPercent);
            performanceMetrics.add(totalPerf);
            
            return ResponseEntity.ok(performanceMetrics);
            
        } catch (Exception e) {
            logger.error("Error fetching performance metrics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"error\":\"Failed to fetch performance metrics\"}");
        }
    }
    
    /**
     * Check if today is a trading day using the same logic as InvestmentSchedule
     * Based on US federal holidays and weekends
     */
    private boolean isTradingDay() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // Check if it's a weekend
        if (today.getDayOfWeek().getValue() >= 6) {  // Saturday = 6, Sunday = 7
            return false;
        }
        
        // Check if it's a US federal holiday using same logic as InvestmentSchedule
        return !isUSHoliday(today);
    }
    
    /**
     * Check if date is a US federal holiday (same logic as InvestmentSchedule)
     */
    private boolean isUSHoliday(LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        
        // New Year's Day
        if (month == 1 && day == 1) return true;
        
        // Independence Day
        if (month == 7 && day == 4) return true;
        
        // Christmas Day
        if (month == 12 && day == 25) return true;
        
        // Martin Luther King Jr. Day (3rd Monday in January)
        if (month == 1 && isNthWeekdayOfMonth(date, DayOfWeek.MONDAY, 3)) return true;
        
        // Presidents Day (3rd Monday in February)
        if (month == 2 && isNthWeekdayOfMonth(date, DayOfWeek.MONDAY, 3)) return true;
        
        // Memorial Day (last Monday in May)
        if (month == 5 && isLastWeekdayOfMonth(date, DayOfWeek.MONDAY)) return true;
        
        // Labor Day (1st Monday in September)
        if (month == 9 && isNthWeekdayOfMonth(date, DayOfWeek.MONDAY, 1)) return true;
        
        // Columbus Day (2nd Monday in October)
        if (month == 10 && isNthWeekdayOfMonth(date, DayOfWeek.MONDAY, 2)) return true;
        
        // Veterans Day (November 11)
        if (month == 11 && day == 11) return true;
        
        // Thanksgiving (4th Thursday in November)
        if (month == 11 && isNthWeekdayOfMonth(date, DayOfWeek.THURSDAY, 4)) return true;
        
        return false;
    }
    
    /**
     * Check if date is the nth occurrence of a weekday in the month
     */
    private boolean isNthWeekdayOfMonth(LocalDate date, DayOfWeek weekday, int n) {
        if (date.getDayOfWeek() != weekday) return false;
        
        LocalDate firstOfMonth = date.withDayOfMonth(1);
        LocalDate nthWeekday = firstOfMonth;
        
        // Find first occurrence of the weekday
        while (nthWeekday.getDayOfWeek() != weekday) {
            nthWeekday = nthWeekday.plusDays(1);
        }
        
        // Add (n-1) weeks to get nth occurrence
        nthWeekday = nthWeekday.plusWeeks(n - 1);
        
        return date.equals(nthWeekday);
    }
    
    /**
     * Check if date is the last occurrence of a weekday in the month
     */
    private boolean isLastWeekdayOfMonth(LocalDate date, DayOfWeek weekday) {
        if (date.getDayOfWeek() != weekday) return false;
        
        LocalDate lastOfMonth = date.withDayOfMonth(date.lengthOfMonth());
        
        // Find last occurrence of the weekday
        while (lastOfMonth.getDayOfWeek() != weekday) {
            lastOfMonth = lastOfMonth.minusDays(1);
        }
        
        return date.equals(lastOfMonth);
    }
    
    private boolean isValidPeriod(String period) {
        return period.matches("^(1D|1W|1M|3M|6M|1Y|ALL)$");
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
