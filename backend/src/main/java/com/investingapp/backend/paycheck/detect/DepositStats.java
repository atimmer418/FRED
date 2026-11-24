package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Trailing statistics over the last six amounts. */
public final class DepositStats {

    public static final int TRAILING_COUNT = 6;

    public static BigDecimal trailingMedian(List<BigDecimal> lastSix) {
        List<BigDecimal> sorted = lastSixOf(lastSix).stream().sorted().toList();
        int n = sorted.size();
        if (n == 0) { return null; }
        BigDecimal median = n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return median.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal trailingCv(List<BigDecimal> lastSix) {
        List<BigDecimal> values = lastSixOf(lastSix);
        double mean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        if (values.size() < 2 || mean <= 0) { return BigDecimal.ZERO.setScale(4); }
        double variance = values.stream().mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2)).sum() / values.size();
        return BigDecimal.valueOf(Math.sqrt(variance) / mean).setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal monthlyNormalized(BigDecimal perPaycheck, InvestmentFrequency cadence) {
        return cadence.toMonthly(perPaycheck);
    }

    private static List<BigDecimal> lastSixOf(List<BigDecimal> values) {
        return values == null ? List.of() : values.subList(Math.max(0, values.size() - TRAILING_COUNT), values.size());
    }
}
