package com.investingapp.backend.util;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.Temporal;
import java.util.List;

/**
 * The banding vocabulary for server-side analytics.
 *
 * A raw dollar amount must never leave this server, so every money value becomes one of a fixed
 * set of buckets before it is sent. The amount, count, years and percent tables are a MIRROR of
 * frontend/src/app/services/analytics/analytics-bands.ts, deliberately: a client event and a
 * server event that band the same number differently cannot be analysed together, and the whole
 * point of the funnel is to read both sides as one. AnalyticsBandsParityTest reads the TypeScript
 * file and fails if the two ever drift.
 *
 * The day bands have no client counterpart. They exist only here, because only the server knows
 * when things actually happened.
 */
public final class AnalyticsBands {

    private AnalyticsBands() {
    }

    // --- Money -------------------------------------------------------------------------------
    // Deliberately coarse at the top, where few users are and detail identifies.

    public static final List<String> AMOUNT_BANDS = List.of(
            "under_50", "50_99", "100_249", "250_499", "500_999", "1k_2499",
            "2500_4999", "5k_9999", "10k_49k", "50k_249k", "250k_999k", "1m_plus");

    /** Upper bound (exclusive) of each band except the last, in the same order as AMOUNT_BANDS. */
    public static final List<Long> AMOUNT_BOUNDS = List.of(
            50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 50_000L, 250_000L, 1_000_000L);

    /**
     * Buckets a dollar amount. Broken input never throws, because analytics must not break a
     * caller: null and negatives fall to the lowest bucket.
     */
    public static String amount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return AMOUNT_BANDS.get(0);
        }
        for (int i = 0; i < AMOUNT_BOUNDS.size(); i++) {
            if (value.compareTo(BigDecimal.valueOf(AMOUNT_BOUNDS.get(i))) < 0) {
                return AMOUNT_BANDS.get(i);
            }
        }
        return AMOUNT_BANDS.get(AMOUNT_BANDS.size() - 1);
    }

    public static String amount(Double value) {
        if (value == null || value.isNaN()) {
            return AMOUNT_BANDS.get(0);
        }
        if (value.isInfinite()) {
            // "Very large" belongs at the top. Sending it to the bottom would quietly under-report
            // instead of looking wrong.
            return value > 0 ? AMOUNT_BANDS.get(AMOUNT_BANDS.size() - 1) : AMOUNT_BANDS.get(0);
        }
        return amount(BigDecimal.valueOf(value));
    }

    // --- Counts ------------------------------------------------------------------------------

    public static final List<String> COUNT_BANDS = List.of("0", "1", "2_3", "4_9", "10_plus");

    public static String count(long value) {
        if (value <= 0) {
            return "0";
        }
        if (value == 1) {
            return "1";
        }
        if (value <= 3) {
            return "2_3";
        }
        return value <= 9 ? "4_9" : "10_plus";
    }

    // --- Years -------------------------------------------------------------------------------

    public static final List<String> YEARS_BANDS =
            List.of("under_1", "1_2", "3_5", "6_10", "11_20", "over_20", "never");

    /** Null means an unreachable date, which is a fact worth keeping rather than a missing value. */
    public static String years(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return "never";
        }
        if (value < 1) {
            return "under_1";
        }
        if (value <= 2) {
            return "1_2";
        }
        if (value <= 5) {
            return "3_5";
        }
        if (value <= 10) {
            return "6_10";
        }
        return value <= 20 ? "11_20" : "over_20";
    }

    // --- Percentages -------------------------------------------------------------------------

    public static final List<String> PERCENT_BANDS =
            List.of("0_9", "10_24", "25_49", "50_74", "75_99", "100");

    /** `value` is 0-100. */
    public static String percent(Double value) {
        if (value == null || value.isNaN()) {
            return "0_9";
        }
        if (value.isInfinite()) {
            return value > 0 ? "100" : "0_9";
        }
        if (value < 10) {
            return "0_9";
        }
        if (value < 25) {
            return "10_24";
        }
        if (value < 50) {
            return "25_49";
        }
        if (value < 75) {
            return "50_74";
        }
        return value < 100 ? "75_99" : "100";
    }

    // --- Days --------------------------------------------------------------------------------
    // Server-only. The client has no equivalent, because only the server knows when a KYC
    // decision landed or when a transfer settled.

    public static final List<String> DAY_BANDS =
            List.of("same_day", "1_3", "4_7", "8_14", "15_30", "31_90", "over_90", "unknown");

    /** Null at either end means we cannot say, which is reported as such rather than as zero. */
    public static String daysBetween(Temporal from, Temporal to) {
        if (from == null || to == null) {
            return "unknown";
        }
        long days;
        try {
            days = Duration.between(from, to).toDays();
        } catch (RuntimeException e) {
            return "unknown";
        }
        return days(days);
    }

    public static String days(long days) {
        if (days < 0) {
            // Clock skew between two writers, or a backfill. Not a negative age.
            return "unknown";
        }
        if (days == 0) {
            return "same_day";
        }
        if (days <= 3) {
            return "1_3";
        }
        if (days <= 7) {
            return "4_7";
        }
        if (days <= 14) {
            return "8_14";
        }
        if (days <= 30) {
            return "15_30";
        }
        return days <= 90 ? "31_90" : "over_90";
    }
}
