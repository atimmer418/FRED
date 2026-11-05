package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckEvent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Spec 4.5 score, capped at 100; shadow tuning edits these numbers. */
public final class StreamScorer {

    public static final int MAX_SCORE = 100;
    static final BigDecimal ROUND_UNIT = new BigDecimal("50");

    public static ScoreBreakdown score(DepositGroup group, Optional<StreamFacts> facts, CadenceResult cadence,
            BigDecimal median, BigDecimal cv) {
        List<PaycheckEvent> deposits = group.depositsAsc();
        int pfc = pfcPoints(HardGates.latestPfcDetailed(deposits, facts), deposits);
        String status = facts.map(StreamFacts::plaidStatus).orElse("");
        int plaid = "MATURE".equals(status) ? 20 : "EARLY_DETECTION".equals(status) ? 5 : 0;
        int med = medianPoints(median == null ? null : DepositStats.monthlyNormalized(median, cadence.cadence()));
        int fit = cadence.allInBand() ? 10 : cadence.outsideBand() == 1 ? 5 : 0;
        int stability = stabilityPoints(cv, group.lastSixAmounts());
        int payroll = deposits.stream().anyMatch(e -> DescriptorSignals.hasPayrollToken(e.getRawDescriptor())
                || DescriptorSignals.hasPayrollToken(e.getMerchantName())) ? 10 : 0;
        return new ScoreBreakdown(pfc, plaid, med, fit, stability, payroll, Math.min(MAX_SCORE, pfc + plaid + med + fit + stability + payroll));
    }

    static int pfcPoints(String detailed, List<PaycheckEvent> deposits) {
        return switch (detailed == null ? "" : detailed) {
            case "INCOME_SALARY", "INCOME_WAGES" -> 40;
            case "INCOME_MILITARY" -> deposits.stream().anyMatch(e -> DescriptorSignals.containsToken(e.getRawDescriptor(), "DFAS")) ? 40 : 10;
            case "INCOME_CONTRACTOR", "INCOME_GIG_ECONOMY" -> 20;
            default -> 10;
        };
    }

    static int medianPoints(BigDecimal monthly) {
        if (monthly == null) { return 0; }
        return monthly.compareTo(new BigDecimal("1500")) >= 0 ? 15 : monthly.compareTo(new BigDecimal("800")) >= 0 ? 10
                : monthly.compareTo(new BigDecimal("400")) >= 0 ? 5 : 0;
    }

    static int stabilityPoints(BigDecimal cv, List<BigDecimal> lastSix) {
        int points = cv == null ? 0 : cv.compareTo(new BigDecimal("0.15")) <= 0 ? 10 : cv.compareTo(new BigDecimal("0.35")) <= 0 ? 7
                : cv.compareTo(new BigDecimal("0.60")) <= 0 ? 3 : 0;
        boolean pennyRound = lastSix.size() >= 2 && lastSix.stream().allMatch(a -> a.compareTo(lastSix.get(0)) == 0)
                && lastSix.get(0).remainder(ROUND_UNIT).signum() == 0;
        return pennyRound ? Math.max(0, points - 4) : points;
    }
}
