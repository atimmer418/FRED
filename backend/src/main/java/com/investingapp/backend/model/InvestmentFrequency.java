package com.investingapp.backend.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place the paycheck cadence factors live.
 *
 * <p>Every backend reader (InvestmentScheduleService, InvestmentSchedule,
 * MonthlyFreedomUpdateService) delegates here, and the client table in
 * frontend/src/app/utils/investment-frequency.utils.ts mirrors these values in hundredths.
 * FrequencyFactorParityTest pins both sides.
 *
 * <p>InvestmentSchedule.frequency stays a VARCHAR(20) String column holding one of these names.
 * This enum is for parsing and money math only, never a mapped column type.
 */
public enum InvestmentFrequency {
    WEEKLY("4.33", 52),
    BIWEEKLY("2.17", 26),
    SEMI_MONTHLY("2", 24),
    MONTHLY("1", 12);

    /** Money is stored at scale 2 (investment_amount and monthly_amount are DECIMAL(10,2)). */
    private static final int MONEY_SCALE = 2;

    /** Legacy spelling still sent by older clients and stored on older rows. */
    private static final String SEMI_MONTHLY_ALIAS = "SEMIMONTHLY";

    private final BigDecimal monthlyFactor;
    private final int perYear;

    InvestmentFrequency(String monthlyFactor, int perYear) {
        this.monthlyFactor = new BigDecimal(monthlyFactor);
        this.perYear = perYear;
    }

    /** Paychecks per month: 4.33, 2.17, 2 or 1. */
    public BigDecimal monthlyFactor() {
        return monthlyFactor;
    }

    /** Paychecks per year: 52, 26, 24 or 12. */
    public int perYear() {
        return perYear;
    }

    /** Per-paycheck amount to its monthly equivalent, rounded to cents HALF_UP. */
    public BigDecimal toMonthly(BigDecimal perPeriod) {
        Objects.requireNonNull(perPeriod, "perPeriod");
        return perPeriod.multiply(monthlyFactor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Monthly amount to its per-paycheck equivalent, rounded to cents HALF_UP. */
    public BigDecimal fromMonthly(BigDecimal monthly) {
        Objects.requireNonNull(monthly, "monthly");
        return monthly.divide(monthlyFactor, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Strict parse: trims, upper-cases, maps the SEMIMONTHLY alias, throws on anything else. */
    public static InvestmentFrequency parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Investment frequency is null");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if (SEMI_MONTHLY_ALIAS.equals(key)) {
            key = SEMI_MONTHLY.name();
        }
        try {
            return valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown investment frequency: " + raw, e);
        }
    }

    /** Lenient parse: empty for null, blank or unknown input, never throws. */
    public static Optional<InvestmentFrequency> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
