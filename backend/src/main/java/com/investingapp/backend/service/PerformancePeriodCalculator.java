package com.investingapp.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure utility for computing performance period rows (1W, 1M, 3M, 6M, YTD, 1Y)
 * from portfolio history. No Spring dependencies — exercisable with plain JUnit 5.
 *
 * <p>Return methodology:
 * <ul>
 *   <li>startValue = equity at or the closest available trading day before the window
 *       start, sourced from Alpaca portfolio history (null-skipped).</li>
 *   <li>endValue = current portfolio equity (same value as the Total row).</li>
 *   <li>totalReturn = endValue − startValue (simple return; mid-window deposits
 *       inflate the figure — accepted for v1, consistent with the Total row).</li>
 *   <li>Windows older than the account's earliest history point are omitted.</li>
 *   <li>YTD clamp rule: YTD is omitted when the earliest history point is after
 *       Jan 1 of the current year. A "YTD" label implies a full calendar-year view;
 *       showing partial-year data from an arbitrary mid-year account open date
 *       would be misleading, so we omit rather than clamp.</li>
 * </ul>
 */
public final class PerformancePeriodCalculator {

    private static final Logger logger = LoggerFactory.getLogger(PerformancePeriodCalculator.class);

    /** Guard against division by near-zero startValue. */
    static final BigDecimal NEAR_ZERO_THRESHOLD = new BigDecimal("0.01");

    /** Market timezone, consistent with existing isTradingDay() usage. */
    static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private PerformancePeriodCalculator() {}

    /**
     * Compute period rows for 1W, 1M, 3M, 6M, YTD, and 1Y windows, in that order
     * (the chart's marker set plus 1W and YTD; "All" is the separate Total row).
     * Windows older than the earliest history point are omitted (not zero-filled).
     *
     * @param dates     ISO-8601 date strings in ascending chronological order
     *                  (from null-skipped Alpaca history)
     * @param values    Equity values parallel to {@code dates}
     * @param endValue  Current portfolio equity (same as Total row's endValue)
     * @param today     Reference date for window boundaries (America/New_York)
     * @return          Ordered list of period maps; omitted windows are absent
     */
    public static List<Map<String, Object>> computePeriods(
            List<String> dates,
            List<BigDecimal> values,
            BigDecimal endValue,
            LocalDate today) {

        List<Map<String, Object>> rows = new ArrayList<>();

        if (dates == null || dates.isEmpty()) {
            return rows;
        }

        String[] periodLabels   = { "1W", "1M", "3M", "6M", "YTD", "1Y" };
        LocalDate[] windowStarts = {
            today.minusWeeks(1),
            today.minusMonths(1),
            today.minusMonths(3),
            today.minusMonths(6),
            LocalDate.of(today.getYear(), 1, 1),
            today.minusYears(1)
        };

        for (int i = 0; i < periodLabels.length; i++) {
            String    label       = periodLabels[i];
            LocalDate windowStart = windowStarts[i];
            try {
                Optional<BigDecimal> startOpt = findStartValue(dates, values, windowStart);
                if (startOpt.isEmpty()) {
                    logger.info("Omitting period {} — window start {} is older than earliest history point",
                            label, windowStart);
                    continue;
                }
                BigDecimal startValue         = startOpt.get();
                BigDecimal totalReturn        = endValue.subtract(startValue);
                BigDecimal totalReturnPercent = computePercent(totalReturn, startValue);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("period",             label);
                row.put("startValue",         startValue);
                row.put("endValue",           endValue);
                row.put("totalReturn",        totalReturn);
                row.put("totalReturnPercent", totalReturnPercent);
                rows.add(row);
            } catch (Exception e) {
                logger.warn("Skipping period {} computation due to error: {}", label, e.getMessage());
            }
        }

        return rows;
    }

    /**
     * Find the equity value at or at the closest available point before
     * {@code windowStart}. Returns empty if the window start predates all history.
     *
     * @param dates       ISO-8601 date strings in ascending order
     * @param values      Equity values parallel to {@code dates}
     * @param windowStart Target date (inclusive; the window boundary)
     * @return            Closest-at-or-before equity, or empty when windowStart
     *                    precedes the earliest history entry
     */
    static Optional<BigDecimal> findStartValue(
            List<String> dates,
            List<BigDecimal> values,
            LocalDate windowStart) {

        BigDecimal result = null;
        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = LocalDate.parse(dates.get(i));
            if (!d.isAfter(windowStart)) {
                result = values.get(i);
            } else {
                break; // ascending order — no earlier entry will appear after this point
            }
        }
        return Optional.ofNullable(result);
    }

    /**
     * Compute return percent, guarding against zero or near-zero startValue to
     * prevent division-by-zero or astronomically large percentages.
     */
    static BigDecimal computePercent(BigDecimal totalReturn, BigDecimal startValue) {
        if (startValue.abs().compareTo(NEAR_ZERO_THRESHOLD) <= 0) {
            return BigDecimal.ZERO;
        }
        return totalReturn
                .divide(startValue, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }
}
