package com.investingapp.backend.service;

import com.investingapp.backend.dto.MonthlyFreedomUpdateDTO;
import com.investingapp.backend.dto.MonthlyFreedomUpdateDTO.MilestoneDTO;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class MonthlyFreedomUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyFreedomUpdateService.class);

    private static final double ASSUMED_ANNUAL_RETURN = 0.10;
    private static final double SAFE_WITHDRAWAL_RATE = 0.04;
    private static final double DEFAULT_TARGET_PORTFOLIO = 1_500_000.0;
    private static final BigDecimal FIFTY_DOLLARS = new BigDecimal("50");
    private static final BigDecimal TWENTY_FIVE_DOLLARS = new BigDecimal("25");

    // Age-based equity percentile lookup
    // Source: Federal Reserve Survey of Consumer Finances (SCF 2022)
    // Values represent median financial assets (stocks, bonds, retirement accounts) by age bracket
    // Key: age bracket upper bound, Value: median financial assets for that bracket
    private static final TreeMap<Integer, BigDecimal> AGE_EQUITY_ESTIMATES = new TreeMap<>();
    static {
        AGE_EQUITY_ESTIMATES.put(25, new BigDecimal("5400"));     // Under 25: ~$5.4k median financial assets
        AGE_EQUITY_ESTIMATES.put(30, new BigDecimal("11800"));    // 25-34: ~$11.8k
        AGE_EQUITY_ESTIMATES.put(35, new BigDecimal("11800"));    // (same bracket as 25-34)
        AGE_EQUITY_ESTIMATES.put(40, new BigDecimal("45740"));    // 35-44: ~$45.7k
        AGE_EQUITY_ESTIMATES.put(45, new BigDecimal("45740"));    // (same bracket as 35-44)
        AGE_EQUITY_ESTIMATES.put(50, new BigDecimal("80000"));    // 45-54: ~$80k
        AGE_EQUITY_ESTIMATES.put(55, new BigDecimal("80000"));    // (same bracket as 45-54)
        AGE_EQUITY_ESTIMATES.put(60, new BigDecimal("134000"));   // 55-64: ~$134k
        AGE_EQUITY_ESTIMATES.put(65, new BigDecimal("134000"));   // (same bracket as 55-64)
        AGE_EQUITY_ESTIMATES.put(75, new BigDecimal("164000"));   // 65-74: ~$164k
        AGE_EQUITY_ESTIMATES.put(100, new BigDecimal("143000"));  // 75+: ~$143k
    }

    // Investment count milestones
    private static final int[] INVESTMENT_COUNT_MILESTONES = {1, 10, 100, 1000, 10000};
    // Equity value milestones
    private static final BigDecimal[] EQUITY_VALUE_MILESTONES = {
        new BigDecimal("1000"),
        new BigDecimal("10000"),
        new BigDecimal("50000"),
        new BigDecimal("100000"),
        new BigDecimal("250000"),
        new BigDecimal("500000"),
        new BigDecimal("1000000")
    };

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InvestmentExecutionRepository executionRepository;

    @Autowired
    private InvestmentScheduleRepository scheduleRepository;

    @Autowired
    private PortfolioDashboardService portfolioDashboardService;

    // Self-reference (through the Spring proxy) so commitMfuSeen can invoke the @Transactional
    // applySeenCommit as a SEPARATE physical transaction: the recompute reads run first outside any
    // write tx (so a read failure can't poison the write), and the pessimistic row lock is held only
    // for the fast DB write, never across the Alpaca network calls.
    @Autowired
    @Lazy
    private MonthlyFreedomUpdateService self;

    /**
     * Check if the Monthly Freedom Update should be shown for the given user.
     * Returns a short DTO with just shouldShow and the user's lastLoggedInMonth.
     *
     * If lastLoggedInMonth is null (first login), initializes it to the current month
     * so the update will trigger next month.
     */
    @Transactional
    public MonthlyFreedomUpdateDTO checkShouldShow(User user, BigDecimal currentEquity) {
        MonthlyFreedomUpdateDTO dto = new MonthlyFreedomUpdateDTO();
        dto.setShouldShow(false);

        // Check if user has any MFU history (has had at least one MFU period generated)
        dto.setHasMfuHistory(user.getLastMfuPeriodStart() != null && !user.getLastMfuPeriodStart().isEmpty());

        String currentMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        dto.setGeneratedForMonth(currentMonth);
        String lastLoggedMonth = user.getLastLoggedInMonth();

        // If no last logged month, this is first time -> initialize it and don't show
        if (lastLoggedMonth == null || lastLoggedMonth.isEmpty()) {
            user.setLastLoggedInMonth(currentMonth);
            userRepository.save(user);
            dto.setShouldShow(false);
            return dto;
        }

        // If current month matches lastLoggedInMonth, already shown this month
        if (currentMonth.equals(lastLoggedMonth)) {
            dto.setShouldShow(false);
            return dto;
        }

        // Current equity must be > 0
        if (currentEquity == null || currentEquity.compareTo(BigDecimal.ZERO) <= 0) {
            dto.setShouldShow(false);
            return dto;
        }

        // Different month and equity > 0 → should show
        dto.setShouldShow(true);
        return dto;
    }

    /**
     * Generate the complete Monthly Freedom Update data.
     * Called when the modal should be shown or when reopened from FRED tab.
     *
     * @param user         The user
     * @param currentEquity Current equity value from Alpaca
     * @param portfolioHistory ALL-time portfolio history (timestamps as ISO dates, values as equity)
     * @param positions    Current real-time positions for position-based return calculation
     * @param isReopen     Whether this is a reopen from FRED tab (no 5-second lock)
     */
    @Transactional
    public MonthlyFreedomUpdateDTO generateUpdate(
            User user,
            BigDecimal currentEquity,
            PortfolioDashboardService.PortfolioHistory portfolioHistory,
            List<PortfolioDashboardService.Position> positions,
            boolean isReopen) {

        MonthlyFreedomUpdateDTO dto = new MonthlyFreedomUpdateDTO();
        dto.setReopen(isReopen);
        dto.setShouldShow(true);

        // Set mfuCount (current count before this update; will be incremented in updateUserState for non-reopen)
        Integer mfuCountVal = user.getMfuCount();
        dto.setMfuCount(mfuCountVal != null ? mfuCountVal : 0);

        String currentMonthStr = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        dto.setGeneratedForMonth(currentMonthStr);
        String lastLoggedMonth = user.getLastLoggedInMonth();

        // --- Period Calculation ---
        // On reopen, use stored period dates if available
        LocalDate periodStart;
        LocalDate periodEnd;

        if (isReopen && user.getLastMfuPeriodStart() != null && user.getLastMfuPeriodEnd() != null) {
            periodStart = LocalDate.parse(user.getLastMfuPeriodStart());
            periodEnd = LocalDate.parse(user.getLastMfuPeriodEnd());
        } else {
            YearMonth lastMonth = lastLoggedMonth != null && !lastLoggedMonth.isEmpty()
                    ? YearMonth.parse(lastLoggedMonth)
                    : YearMonth.now().minusMonths(1);
            YearMonth currentYM = YearMonth.now();

            // Period: first day of lastLoggedInMonth → last day of month before current
            periodStart = lastMonth.atDay(1);
            periodEnd = currentYM.minusMonths(1).atEndOfMonth();

            // If periodEnd < periodStart (same month scenario), adjust
            if (periodEnd.isBefore(periodStart)) {
                periodStart = currentYM.minusMonths(1).atDay(1);
                periodEnd = currentYM.minusMonths(1).atEndOfMonth();
            }
        }

        dto.setPeriodStart(periodStart.toString());
        dto.setPeriodEnd(periodEnd.toString());

        DateTimeFormatter labelFormat = DateTimeFormatter.ofPattern("MMM d");
        dto.setPeriodLabel(periodStart.format(labelFormat) + " to " + periodEnd.format(labelFormat));

        // --- Equity values from portfolio history ---
        BigDecimal startEquity = findEquityAtDate(portfolioHistory, periodStart);
        BigDecimal endEquity = findEquityAtDate(portfolioHistory, periodEnd);

        // If endEquity is still 0, use currentEquity as approximation
        if (endEquity.compareTo(BigDecimal.ZERO) == 0 && currentEquity.compareTo(BigDecimal.ZERO) > 0) {
            endEquity = currentEquity;
        }

        dto.setStartEquityValue(startEquity);
        dto.setEndEquityValue(endEquity);
        dto.setCurrentEquityValue(currentEquity);

        // --- Equity Level (1-6) based on endEquity ---
        dto.setEquityLevel(calculateEquityLevel(endEquity));

        BigDecimal periodDelta = endEquity.subtract(startEquity);
        dto.setPeriodProgressDelta(periodDelta);
        dto.setPositive(periodDelta.compareTo(BigDecimal.ZERO) > 0);

        // --- Status Percentile ---
        int age = user.getAge() != null ? user.getAge() : 35;
        int percentile = calculateStatusPercentile(user, currentEquity);
        dto.setStatusPercentile(percentile);
        dto.setAge(age);

        // --- Streak (read current value — managed independently by StreakService) ---
        int streak = user.getCurrentStreak() != null ? user.getCurrentStreak() : 0;
        dto.setCurrentStreak(streak);

        // --- Milestones ---
        long totalInvestmentCount = executionRepository.countCompletedExecutionsByUser(user.getId());
        List<MilestoneDTO> milestones;
        if (isReopen) {
            // On reopen, restore the milestones that were shown during the original period
            milestones = deserializeMilestones(user.getLastMfuMilestones());
            if (milestones == null || milestones.isEmpty()) {
                milestones = calculateMilestones(user, totalInvestmentCount, startEquity, endEquity);
            }
        } else {
            milestones = calculateMilestones(user, totalInvestmentCount, startEquity, endEquity);
        }
        dto.setMilestones(milestones);

        // --- Analytics Section ---
        LocalDateTime periodStartDT = periodStart.atStartOfDay();
        LocalDateTime periodEndDT = periodEnd.atTime(23, 59, 59);
        BigDecimal periodContributions = executionRepository.sumCompletedAmountsByUserAndDateRange(
                user.getId(), periodStartDT, periodEndDT);
        dto.setPeriodContributions(periodContributions);
        dto.setEquityValueChange(periodDelta);

        // Return rate = weighted average of individual position returns (by cost basis)
        // Each position: return = (currentPrice - avgCostBasis) / avgCostBasis
        // Portfolio return = sum(costBasis_i * return_i) / sum(costBasis_i)
        //                  = totalUnrealizedPL / totalCostBasis
        BigDecimal returnRate = BigDecimal.ZERO;
        if (positions != null && !positions.isEmpty()) {
            BigDecimal totalCostBasis = BigDecimal.ZERO;
            BigDecimal totalUnrealizedPL = BigDecimal.ZERO;
            for (PortfolioDashboardService.Position pos : positions) {
                if (pos.getCostBasis() != null && pos.getUnrealizedPL() != null) {
                    totalCostBasis = totalCostBasis.add(pos.getCostBasis());
                    totalUnrealizedPL = totalUnrealizedPL.add(pos.getUnrealizedPL());
                }
            }
            if (totalCostBasis.compareTo(BigDecimal.ZERO) > 0) {
                returnRate = totalUnrealizedPL
                        .divide(totalCostBasis, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        }
        dto.setReturnRate(returnRate);

        // --- Investment Schedule info ---
        Optional<InvestmentSchedule> scheduleOpt = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        BigDecimal investmentAmount = BigDecimal.ZERO;
        String frequency = "MONTHLY";
        if (scheduleOpt.isPresent()) {
            InvestmentSchedule schedule = scheduleOpt.get();
            investmentAmount = schedule.getInvestmentAmount();
            frequency = schedule.getFrequency();
        }
        dto.setRecurringInvestmentAmount(investmentAmount);
        dto.setInvestmentFrequency(frequency);
        dto.setCurrentInvestmentAmount(investmentAmount);

        // --- Frequency label for display ---
        String freqLabel = getFrequencyLabel(frequency);
        dto.setFrequencyLabel(freqLabel);

        // --- Projection: Freedom Year ---
        BigDecimal monthlyContribution = resolveMonthlyContribution(
                calculateMonthlyEquivalent(investmentAmount, frequency), user);
        double targetPortfolio = resolveTargetPortfolio(user);

        int freedomYear = calculateFreedomYear(currentEquity, monthlyContribution, new BigDecimal(targetPortfolio));
        dto.setProjectedFreedomYear(freedomYear);

        // --- Best Next Action (boost = a percentage of the member's OWN recurring amount) ---
        int mfuCountForCycle = (mfuCountVal != null ? mfuCountVal : 0);
        int mfuCycle = mfuCountForCycle % 3;
        BigDecimal boostAmount = calculateBoostAmount(investmentAmount, mfuCountForCycle);
        dto.setBestNextMoveBoostAmount(boostAmount);
        BigDecimal boostedMonthly = monthlyContribution.add(calculateMonthlyEquivalent(boostAmount, frequency));
        int boostedFreedomYear = calculateFreedomYear(currentEquity, boostedMonthly, new BigDecimal(targetPortfolio));
        int yearsEarlier = freedomYear - boostedFreedomYear;
        if (yearsEarlier < 0) yearsEarlier = 0;
        dto.setBestNextMoveYearsEarlier(yearsEarlier);
        dto.setBestNextMoveYear(boostedFreedomYear);

        // --- Days Bought Back (days freedom moved closer this period) ---
        BigDecimal targetBD = new BigDecimal(targetPortfolio);
        int daysBoughtBack = calculateDaysBoughtBack(startEquity, endEquity, monthlyContribution, targetBD, age);
        dto.setDaysBoughtBack(daysBoughtBack);

        // --- Quarterly Review (every 3rd MFU, i.e. mfuCycle == 2) ---
        boolean showQuarterly = mfuCycle == 2;
        dto.setShowQuarterlyCompare(showQuarterly);

        if (showQuarterly) {
            // Net worth change over past 12 months
            BigDecimal equity12MonthsAgo = findEquityAtDate(portfolioHistory, LocalDate.now().minusMonths(12));
            dto.setEquity12MonthsAgo(equity12MonthsAgo);
            dto.setNetWorthChange(currentEquity.subtract(equity12MonthsAgo));

            // Years closer to freedom (same logic as daysBoughtBack but with 12-month-ago equity, in years)
            double monthsFrom12Ago = calculateMonthsToTarget(equity12MonthsAgo, monthlyContribution, new BigDecimal(targetPortfolio));
            double monthsFromNow = calculateMonthsToTarget(currentEquity, monthlyContribution, new BigDecimal(targetPortfolio));
            double yearsDiff = (monthsFrom12Ago - monthsFromNow) / 12.0;
            // Round to 1 decimal
            BigDecimal yearsFreedomGained = new BigDecimal(yearsDiff).setScale(1, RoundingMode.HALF_UP);
            dto.setFreedomYearsChange(yearsFreedomGained);

            // Total contributions over past 12 months
            LocalDateTime yearAgoDT = LocalDate.now().minusMonths(12).atStartOfDay();
            LocalDateTime nowDT = LocalDate.now().atTime(23, 59, 59);
            BigDecimal yearlyContributions = executionRepository.sumCompletedAmountsByUserAndDateRange(
                    user.getId(), yearAgoDT, nowDT);
            if (yearlyContributions == null) yearlyContributions = BigDecimal.ZERO;
            dto.setYearlyContributions(yearlyContributions);
        }

        // NOTE: generateUpdate is intentionally PURE — it performs NO persistence. That makes it safe
        // to prefetch/retry (Stage 3 present-when-ready) and to call from commitMfuSeen() for the
        // server-authoritative recompute. The non-reopen "seen" side effects (lastLoggedInMonth,
        // mfuCount++, freedom-estimate rotation, milestone history, lastMfuPeriod*) are committed
        // exactly once by commitMfuSeen() when the update is dismissed / has been shown.
        return dto;
    }

    /**
     * Commit the "seen" side effects for the shown Monthly Freedom Update exactly once.
     *
     * Called from POST /dismiss (Stage 3 fires it at data-ready). Because generateUpdate() is now
     * pure, this is the ONLY place the month is marked seen and mfuCount / freedom-estimate are
     * advanced.
     *
     * Exactly-once is enforced two ways:
     *   - The user row is loaded under a pessimistic write-lock (findByIdForUpdate), so concurrent
     *     commits (double-tap, two devices) serialize on the row.
     *   - An advances-only guard no-ops when lastLoggedInMonth is already >= the stamp month.
     *
     * The persisted period / equities / milestones / freedomYear are RECOMPUTED server-side from the
     * (now warm) portfolio data — no client-supplied financial value is ever trusted. clientSeenMonth
     * is a server-produced hint (DTO.generatedForMonth) that can only pull the stamp EARLIER, toward
     * the month actually displayed, so a commit that lands after a midnight/month rollover still
     * stamps the shown month and the new month's update still fires next launch.
     */
    public void commitMfuSeen(Long userId, String clientSeenMonth) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            logger.warn("commitMfuSeen: user {} not found", userId);
            return;
        }
        User user = userOpt.get();

        // Phase 1 — recompute the displayed values from warm portfolio data (Alpaca-free within the
        // 60s dashboard cache). Reads ONLY, and NO write-lock is held yet: generateUpdate reads the
        // OLD lastLoggedInMonth so period + equities reproduce what the modal showed. Any failure here
        // (Alpaca down, a repo read error) is isolated from the phase-2 write transaction and simply
        // falls through to a month-only stamp.
        RecomputedSeen recomputed = null;
        try {
            PortfolioDashboardService.PortfolioDashboardData dashboard =
                    portfolioDashboardService.getPortfolioDashboard(user);
            BigDecimal currentEquity = dashboard.summary.equity;
            PortfolioDashboardService.PortfolioHistory history =
                    portfolioDashboardService.getPortfolioHistoryForPeriod(user, "ALL");

            MonthlyFreedomUpdateDTO dto = generateUpdate(user, currentEquity, history, dashboard.positions, false);
            long totalInvestmentCount = executionRepository.countCompletedExecutionsByUser(user.getId());

            recomputed = new RecomputedSeen(
                    dto.getProjectedFreedomYear(),
                    dto.getMilestones(),
                    dto.getStartEquityValue(),
                    dto.getEndEquityValue(),
                    LocalDate.parse(dto.getPeriodStart()),
                    LocalDate.parse(dto.getPeriodEnd()),
                    (int) totalInvestmentCount);
        } catch (Exception e) {
            logger.warn("commitMfuSeen: recompute failed for user {}, will stamp month only: {}",
                    userId, e.getMessage());
        }

        // Phase 2 — locked write in its OWN transaction (through the proxy so @Transactional applies).
        self.applySeenCommit(userId, clientSeenMonth, recomputed);
    }

    /**
     * Phase 2 of commitMfuSeen: under a pessimistic write-lock on the user row, no-op if the month is
     * already seen (advances-only), otherwise persist the recomputed state — or stamp the month only
     * when the recompute failed. Public + @Transactional so it runs through the Spring proxy as a
     * distinct physical transaction; call it via commitMfuSeen, not directly.
     */
    @Transactional
    public void applySeenCommit(Long userId, String clientSeenMonth, RecomputedSeen recomputed) {
        Optional<User> userOpt = userRepository.findByIdForUpdate(userId);
        if (userOpt.isEmpty()) {
            logger.warn("applySeenCommit: user {} not found", userId);
            return;
        }
        User user = userOpt.get();

        YearMonth now = YearMonth.now();
        YearMonth stamp = now;
        if (clientSeenMonth != null && !clientSeenMonth.isEmpty()) {
            try {
                YearMonth hinted = YearMonth.parse(clientSeenMonth);
                // The hint may only pull the stamp EARLIER (toward the shown month), never later.
                if (hinted.isBefore(now)) {
                    stamp = hinted;
                }
            } catch (Exception e) {
                logger.warn("applySeenCommit: ignoring unparseable seenMonth '{}'", clientSeenMonth);
            }
        }

        // Advances-only guard under the row lock: a duplicate (already stamped >= stamp) is a no-op,
        // so two concurrent commits cannot double-advance mfuCount / the freedom estimate.
        String lastLoggedMonth = user.getLastLoggedInMonth();
        if (lastLoggedMonth != null && !lastLoggedMonth.isEmpty()) {
            try {
                YearMonth last = YearMonth.parse(lastLoggedMonth);
                if (!last.isBefore(stamp)) {
                    logger.debug("applySeenCommit: already seen for {} (last={}), no-op", stamp, last);
                    return;
                }
            } catch (Exception e) {
                // Unparseable stored month -> treat as not-yet-seen and proceed.
            }
        }

        String stampStr = stamp.format(DateTimeFormatter.ofPattern("yyyy-MM"));

        if (recomputed != null) {
            updateUserState(user, stampStr, recomputed.freedomYear(), recomputed.totalInvestmentCount(),
                    recomputed.milestones(), recomputed.startEquity(), recomputed.endEquity(),
                    recomputed.periodStart(), recomputed.periodEnd());
        } else {
            // Degraded: recompute failed upstream, but still stamp so the popup does not re-fire.
            user.setLastLoggedInMonth(stampStr);
            userRepository.save(user);
        }
    }

    /** Values recomputed server-side in phase 1 of commitMfuSeen, persisted under the lock in phase 2. */
    private record RecomputedSeen(int freedomYear, List<MilestoneDTO> milestones,
                                  BigDecimal startEquity, BigDecimal endEquity,
                                  LocalDate periodStart, LocalDate periodEnd,
                                  int totalInvestmentCount) {
    }

    /**
     * Update user fields after showing the Monthly Freedom Update.
     */
    @Transactional
    public void updateUserState(User user, String currentMonth, int freedomYear,
                                int totalInvestmentCount, List<MilestoneDTO> newMilestones,
                                BigDecimal startEquity, BigDecimal endEquity,
                                LocalDate periodStart, LocalDate periodEnd) {

        // Update lastLoggedInMonth
        user.setLastLoggedInMonth(currentMonth);

        // Store period for reopen
        user.setLastMfuPeriodStart(periodStart.toString());
        user.setLastMfuPeriodEnd(periodEnd.toString());

        // Update userAccountLength
        if (user.getUserAccountLength() == null) {
            // Calculate from createDate
            if (user.getCreateDate() != null) {
                long months = ChronoUnit.MONTHS.between(
                        YearMonth.from(user.getCreateDate()),
                        YearMonth.now());
                user.setUserAccountLength((int) months);
            } else {
                user.setUserAccountLength(0);
            }
        } else {
            user.setUserAccountLength(user.getUserAccountLength() + 1);
        }

        // Update freedom estimates
        // If accountLength crosses 12-month boundary, save current as previous
        Integer accountLength = user.getUserAccountLength();
        if (accountLength != null && accountLength >= 12 && user.getPreviousFreedomEstimate() == null) {
            // First time reaching 12 months - save current estimate as previous
            Integer current = user.getCurrentFreedomEstimate();
            if (current != null) {
                user.setPreviousFreedomEstimate(current);
            } else {
                user.setPreviousFreedomEstimate(LocalDate.now().getYear());
            }
        }
        // Every 12 months, rotate current -> previous
        if (accountLength != null && accountLength > 0 && accountLength % 12 == 0) {
            Integer current = user.getCurrentFreedomEstimate();
            if (current != null) {
                user.setPreviousFreedomEstimate(current);
            }
        }
        user.setCurrentFreedomEstimate(freedomYear);

        // Update recurring investment count
        user.setRecurringInvestmentCount(totalInvestmentCount);

        // Increment MFU count (only non-reopen updates reach here)
        Integer currentMfuCount = user.getMfuCount();
        user.setMfuCount(currentMfuCount != null ? currentMfuCount + 1 : 1);

        // Note: streak is managed independently by StreakService, not updated here

        // Update milestone history
        updateMilestoneHistory(user, newMilestones);

        // Store milestones for reopen
        user.setLastMfuMilestones(serializeMilestones(newMilestones));

        userRepository.save(user);
    }



    /**
     * Merge new milestones into the user's milestone history (JSON string).
     */
    private void updateMilestoneHistory(User user, List<MilestoneDTO> newMilestones) {
        Set<String> existing = new HashSet<>();
        String history = user.getMilestoneHistory();
        if (history != null && !history.isEmpty()) {
            // Simple JSON array parsing: ["INVESTMENT_10","EQUITY_1K"]
            history = history.replace("[", "").replace("]", "").replace("\"", "");
            if (!history.isEmpty()) {
                existing.addAll(Arrays.asList(history.split(",")));
            }
        }

        for (MilestoneDTO m : newMilestones) {
            // Combined type keys may contain comma-separated values
            if (m.getType() != null && !"DEFAULT".equals(m.getType())) {
                for (String key : m.getType().split(",")) {
                    if (!key.trim().isEmpty()) {
                        existing.add(key.trim());
                    }
                }
            }
        }

        // Rebuild JSON array string
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : existing) {
            if (!first) sb.append(",");
            sb.append("\"").append(s).append("\"");
            first = false;
        }
        sb.append("]");
        user.setMilestoneHistory(sb.toString());
    }

    /**
     * Serialize milestones to a simple JSON string for storage on the user.
     */
    private String serializeMilestones(List<MilestoneDTO> milestones) {
        if (milestones == null || milestones.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < milestones.size(); i++) {
            MilestoneDTO m = milestones.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"").append(escapeJson(m.getType()))
              .append("\",\"label\":\"").append(escapeJson(m.getLabel()))
              .append("\",\"subtitle\":\"").append(escapeJson(m.getSubtitle()))
              .append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Deserialize milestones from the stored JSON string.
     */
    private List<MilestoneDTO> deserializeMilestones(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return null;
        List<MilestoneDTO> result = new ArrayList<>();
        try {
            // Simple JSON array of objects parsing
            // Remove outer brackets
            String inner = json.substring(1, json.length() - 1);
            // Split by },{ pattern
            String[] parts = inner.split("\\},\\{");
            for (String part : parts) {
                part = part.replace("{", "").replace("}", "");
                String type = extractJsonValue(part, "type");
                String label = extractJsonValue(part, "label");
                String subtitle = extractJsonValue(part, "subtitle");
                if (label != null) {
                    result.add(new MilestoneDTO(type, label, subtitle));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to deserialize MFU milestones: {}", e.getMessage());
            return null;
        }
        return result.isEmpty() ? null : result;
    }

    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Calculate status percentile based on age and equity compared to population estimates.
     */
    private int calculateStatusPercentile(User user, BigDecimal currentEquity) {
        Integer age = user.getAge();
        if (age == null) age = 35; // Default age

        // Find the median equity for the user's age bracket
        Map.Entry<Integer, BigDecimal> entry = AGE_EQUITY_ESTIMATES.ceilingEntry(age);
        if (entry == null) {
            entry = AGE_EQUITY_ESTIMATES.lastEntry();
        }
        BigDecimal medianEquity = entry.getValue();

        if (medianEquity.compareTo(BigDecimal.ZERO) == 0) return 50;

        // Simple percentile: assume normal-ish distribution
        // If equityValue == median → 50th percentile
        // Use ratio to estimate percentile (capped 1-99)
        double ratio = currentEquity.doubleValue() / medianEquity.doubleValue();

        // Map ratio to percentile using a simple logistic-like function
        // ratio=0 → ~1%, ratio=1 → 50%, ratio=2 → ~85%, ratio=4 → ~97%
        double percentile = 100.0 / (1.0 + Math.exp(-1.5 * (ratio - 1.0)));
        int result = (int) Math.round(percentile);
        return Math.max(1, Math.min(99, result));
    }

    /**
     * Calculate milestones achieved during the period.
     * Returns a single combined milestone or a default "no milestones" entry.
     * - Investment Count: prevCount < milestone <= currentCount
     * - Total Equity Value: startEquity <= milestone <= endEquity (highest only)
     * All milestones are combined into one card with a merged title and subtitle.
     */
    private List<MilestoneDTO> calculateMilestones(User user, long totalInvestmentCount,
                                                    BigDecimal startEquity, BigDecimal endEquity) {
        List<MilestoneDTO> result = new ArrayList<>();

        // Previous investment count (from last update)
        int prevCount = user.getRecurringInvestmentCount() != null ? user.getRecurringInvestmentCount() : 0;

        // Collect investment count milestones crossed: prevCount < milestone <= currentCount
        List<String> investmentLabels = new ArrayList<>();
        List<String> investmentKeys = new ArrayList<>();
        for (int milestone : INVESTMENT_COUNT_MILESTONES) {
            if (prevCount < milestone && totalInvestmentCount >= milestone) {
                investmentKeys.add("INVESTMENT_" + milestone);
                investmentLabels.add(formatOrdinal(milestone));
            }
        }

        // Collect equity milestone: startEquity <= milestone <= endEquity, take highest only
        String equityLabel = null;
        String equityKey = null;
        for (int i = EQUITY_VALUE_MILESTONES.length - 1; i >= 0; i--) {
            BigDecimal milestone = EQUITY_VALUE_MILESTONES[i];
            if (startEquity.compareTo(milestone) <= 0 && endEquity.compareTo(milestone) >= 0) {
                equityKey = "EQUITY_" + formatMilestoneKey(milestone);
                equityLabel = formatEquityLabel(milestone);
                break; // Take highest only
            }
        }

        // Build combined milestone
        int totalMilestones = investmentLabels.size() + (equityLabel != null ? 1 : 0);

        if (totalMilestones == 0) {
            // Default: no milestones reached
            result.add(new MilestoneDTO("DEFAULT", "No major milestones reached",
                    "FRED's still so proud of you though"));
        } else {
            // Build combined title
            StringBuilder title = new StringBuilder();
            if (!investmentLabels.isEmpty()) {
                title.append(String.join(" and ", investmentLabels));
                title.append(" Investment!");
            }
            if (equityLabel != null) {
                if (title.length() > 0) title.append(" ");
                title.append(equityLabel).append(" Total Equity Reached!");
            }

            // Build subtitle
            String subtitle;
            if (totalMilestones == 1) {
                subtitle = "You reached a major milestone.";
            } else {
                subtitle = "You reached " + totalMilestones + " major milestones.";
            }

            // Combined type key for milestone history
            String combinedType = String.join(",", investmentKeys);
            if (equityKey != null) {
                if (!combinedType.isEmpty()) combinedType += ",";
                combinedType += equityKey;
            }

            result.add(new MilestoneDTO(combinedType, title.toString(), subtitle));
        }

        return result;
    }

    /**
     * Format a number as an ordinal: 1→"1st", 10→"10th", 100→"100th", 1000→"1,000th", 10000→"10,000th"
     */
    private String formatOrdinal(int n) {
        String formatted;
        if (n >= 1000) {
            formatted = String.format("%,d", n);
        } else {
            formatted = String.valueOf(n);
        }

        if (n == 1) return formatted + "st";
        if (n == 2) return formatted + "nd";
        if (n == 3) return formatted + "rd";
        return formatted + "th";
    }

    /**
     * Format equity milestone label: $1k, $10k, $50k, $100k, $250k, $500k, $1M
     */
    private String formatEquityLabel(BigDecimal value) {
        double val = value.doubleValue();
        if (val >= 1_000_000) return "$" + ((int)(val / 1_000_000)) + "M";
        if (val >= 1_000) return "$" + ((int)(val / 1_000)) + "k";
        return "$" + (int)val;
    }

    private String formatMilestoneKey(BigDecimal value) {
        double val = value.doubleValue();
        if (val >= 1_000_000) return ((int)(val / 1_000_000)) + "M";
        if (val >= 1_000) return ((int)(val / 1_000)) + "K";
        return String.valueOf((int)val);
    }



    /**
     * Find equity value at a specific date from portfolio history.
     * Falls back to nearest previous date.
     */
    private BigDecimal findEquityAtDate(PortfolioDashboardService.PortfolioHistory history,
                                         LocalDate targetDate) {
        if (history == null || history.timestamps == null || history.timestamps.isEmpty()) {
            return BigDecimal.ZERO;
        }

        String targetStr = targetDate.toString(); // ISO format yyyy-MM-dd

        // Try exact match first
        for (int i = 0; i < history.timestamps.size(); i++) {
            if (history.timestamps.get(i).equals(targetStr)) {
                return history.values.get(i);
            }
        }

        // Find nearest date before target
        BigDecimal closestValue = BigDecimal.ZERO;
        for (int i = 0; i < history.timestamps.size(); i++) {
            String dateStr = history.timestamps.get(i);
            LocalDate date = LocalDate.parse(dateStr);
            if (!date.isAfter(targetDate)) {
                closestValue = history.values.get(i);
            } else {
                break; // Assuming timestamps are sorted ascending
            }
        }
        return closestValue;
    }

    /**
     * Resolve the projection contribution — the user's SAVED monthly investing
     * goal (profile/survey monthlyInvestment), falling back to the actual
     * schedule's monthly equivalent when no goal is set. Flipped 2026-08-01
     * (was schedule-else-survey) so my-profile saves always propagate to the
     * freedom date. Mirrors FreedomStatsService (frontend):
     * surveyMonthly > 0 ? surveyMonthly : scheduleMonthly.
     */
    BigDecimal resolveMonthlyContribution(BigDecimal scheduleMonthlyEquivalent, User user) {
        Double surveyMonthly = user.getMonthlyInvestment();
        if (surveyMonthly != null && surveyMonthly > 0) {
            return BigDecimal.valueOf(surveyMonthly);
        }
        if (scheduleMonthlyEquivalent != null && scheduleMonthlyEquivalent.compareTo(BigDecimal.ZERO) > 0) {
            return scheduleMonthlyEquivalent;
        }
        return BigDecimal.ZERO;
    }

    /**
     * Resolve the FI target portfolio — live income-based 4% rule, $1.5M default.
     * UNIFIED with the tab3 stat strip (frontend FreedomStatsService): both sides
     * compute retirementIncome / SAFE_WITHDRAWAL_RATE from the user's CURRENT
     * retirement income. The user.targetPortfolio signup snapshot is intentionally
     * not read — it goes stale the moment the user edits their retirement income.
     */
    double resolveTargetPortfolio(User user) {
        Double retirementIncome = user.getRetirementIncome();
        if (retirementIncome != null && retirementIncome > 0) {
            return retirementIncome / SAFE_WITHDRAWAL_RATE;
        }
        return DEFAULT_TARGET_PORTFOLIO;
    }

    /**
     * Calculate months to reach target portfolio with compound growth and monthly contributions.
     * Core formula: FV = PV*(1+r)^n + PMT*((1+r)^n - 1)/r
     * Solved for n.
     */
    private double calculateMonthsToTarget(BigDecimal currentEquity, BigDecimal monthlyContribution,
                                            BigDecimal targetPortfolio) {
        if (currentEquity.compareTo(targetPortfolio) >= 0) return 0;
        if (currentEquity.compareTo(BigDecimal.ZERO) <= 0 &&
            monthlyContribution.compareTo(BigDecimal.ZERO) <= 0) return 600;

        double r = ASSUMED_ANNUAL_RETURN / 12.0;
        double PV = currentEquity.doubleValue();
        double FV = targetPortfolio.doubleValue();
        double PMT = monthlyContribution.doubleValue();

        if (PMT <= 0) {
            // Growth only, no contributions
            if (PV <= 0) return 600;
            double months = Math.log(FV / PV) / Math.log(1 + r);
            if (Double.isNaN(months) || Double.isInfinite(months) || months < 0) return 600;
            return months;
        }

        double numerator = Math.log((FV + PMT / r) / (PV + PMT / r));
        double denominator = Math.log(1 + r);

        if (denominator == 0 || Double.isNaN(numerator) || Double.isInfinite(numerator)) return 600;
        double months = numerator / denominator;
        if (Double.isNaN(months) || Double.isInfinite(months) || months < 0) return 600;
        return months;
    }

    /**
     * Calculate time to reach target portfolio value.
     * Returns the projected year.
     */
    int calculateFreedomYear(BigDecimal currentEquity, BigDecimal monthlyContribution,
                             BigDecimal targetPortfolio) {
        double months = calculateMonthsToTarget(currentEquity, monthlyContribution, targetPortfolio);
        int years = (int) Math.ceil(months / 12.0);
        return Math.min(LocalDate.now().getYear() + years, LocalDate.now().getYear() + 100);
    }

    /**
     * Calculate days bought back toward financial freedom.
     *
     * Compares months-to-target from startEquity vs endEquity to find how many
     * months closer to freedom the user moved during the period, then converts
     * to days. The 59.5 retirement age reference is only used in the frontend
     * display text for context — it does not clamp this calculation.
     */
    private int calculateDaysBoughtBack(BigDecimal startEquity, BigDecimal endEquity,
                                         BigDecimal monthlyContribution, BigDecimal targetPortfolio, int userAge) {
        double monthsFromStart = calculateMonthsToTarget(startEquity, monthlyContribution, targetPortfolio);
        double monthsFromEnd = calculateMonthsToTarget(endEquity, monthlyContribution, targetPortfolio);
        double monthsDiff = monthsFromStart - monthsFromEnd;
        int days = (int) Math.round(monthsDiff * 30.44);
        return Math.max(0, days);
    }

    /**
     * Convert per-execution investment amount to monthly equivalent.
     */
    BigDecimal calculateMonthlyEquivalent(BigDecimal amount, String frequency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        switch (frequency.toUpperCase()) {
            case "WEEKLY":
                return amount.multiply(new BigDecimal("4.33")).setScale(2, RoundingMode.HALF_UP);
            case "BIWEEKLY":
                return amount.multiply(new BigDecimal("2.17")).setScale(2, RoundingMode.HALF_UP);
            case "SEMIMONTHLY":
            case "SEMI_MONTHLY":
                return amount.multiply(new BigDecimal("2")).setScale(2, RoundingMode.HALF_UP);
            case "MONTHLY":
                return amount;
            default:
                return amount;
        }
    }

    /**
     * Dynamic "invest more" boost: a percentage of the member's OWN recurring amount so the pitch
     * scales with them instead of a flat $50/$25 for everyone. Cycle 0 (mfuCount % 3 == 0) suggests
     * +20%, cycle 1 suggests +10% (the two cycles that show the CTA; cycle 2 is the quarterly card).
     * Rounded to the nearest $10, and never below the old flat floor ($50 on cycle 0, $25 on cycle 1)
     * so a small or zero contributor still gets a nudge worth acting on. {@code currentAmount} is the
     * member's per-schedule-period amount, matching how the CTA is displayed ("$X/{frequency}").
     */
    BigDecimal calculateBoostAmount(BigDecimal currentAmount, int mfuCount) {
        int cycle = ((mfuCount % 3) + 3) % 3; // guard a stray negative mfuCount
        BigDecimal percent = (cycle == 0) ? new BigDecimal("0.20") : new BigDecimal("0.10");
        BigDecimal floor = (cycle == 0) ? FIFTY_DOLLARS : TWENTY_FIVE_DOLLARS;
        BigDecimal base = (currentAmount == null) ? BigDecimal.ZERO : currentAmount;

        // Nearest $10: divide by 10, round to a whole number (HALF_UP), multiply back.
        BigDecimal roundedToTen = base.multiply(percent)
                .divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.TEN);
        return roundedToTen.max(floor);
    }

    /**
     * Map endEquity to a pig level (1–6).
     * Level 1: < $1,000
     * Level 2: $1,000 – $9,999.99
     * Level 3: $10,000 – $99,999.99
     * Level 4: $100,000 – $999,999.99
     * Level 5: $1,000,000 – $4,999,999.99
     * Level 6: >= $5,000,000
     */
    private int calculateEquityLevel(BigDecimal equity) {
        if (equity == null || equity.compareTo(new BigDecimal("1000")) < 0) return 1;
        if (equity.compareTo(new BigDecimal("10000")) < 0) return 2;
        if (equity.compareTo(new BigDecimal("100000")) < 0) return 3;
        if (equity.compareTo(new BigDecimal("1000000")) < 0) return 4;
        if (equity.compareTo(new BigDecimal("5000000")) < 0) return 5;
        return 6;
    }

    /**
     * Get a display-friendly frequency label.
     */
    private String getFrequencyLabel(String frequency) {
        switch (frequency.toUpperCase()) {
            case "WEEKLY": return "week";
            case "BIWEEKLY": return "paycheck";
            case "SEMI_MONTHLY": return "paycheck";
            case "MONTHLY": return "month";
            default: return "month";
        }
    }
}
