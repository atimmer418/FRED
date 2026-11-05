package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckRejectReason;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Spec 4.2 gates in order; one deposit past the first five is a CANDIDATE, not REJECTED. */
public final class HardGates {

    public static final double STALE_PERIODS = 2.5;
    public static final BigDecimal LOW_MEDIAN_WEEKLY = new BigDecimal("92");
    public static final BigDecimal LOW_MEDIAN_BIWEEKLY = new BigDecimal("184");
    public static final BigDecimal LOW_MEDIAN_SEMI_MONTHLY = new BigDecimal("200");
    public static final BigDecimal LOW_MEDIAN_MONTHLY = new BigDecimal("400");
    public static final Set<String> EXCLUDED_PFC_DETAILED = Set.of("INCOME_UNEMPLOYMENT", "INCOME_RETIREMENT_PENSION",
            "INCOME_LONG_TERM_DISABILITY", "INCOME_CHILD_SUPPORT", "INCOME_RENTAL", "INCOME_TAX_REFUND", "INCOME_DIVIDENDS",
            "INCOME_INTEREST_EARNED");
    static final Set<String> HIGH_CONFIDENCE = Set.of("HIGH", "VERY_HIGH");

    public static Optional<PaycheckRejectReason> evaluate(DepositGroup group, Optional<StreamFacts> facts,
            Optional<CadenceResult> cadence, BigDecimal median, LocalDate todayET) {
        List<PaycheckEvent> deposits = group.depositsAsc();
        if (deposits.isEmpty() || deposits.stream().anyMatch(e -> e.getRawAmount() == null || e.getRawAmount().signum() >= 0)) {
            return Optional.of(PaycheckRejectReason.NOT_INFLOW);
        }
        String status = facts.map(StreamFacts::plaidStatus).orElse(null);
        if ("TOMBSTONED".equals(status) || "UNKNOWN".equals(status)) { return Optional.of(PaycheckRejectReason.PLAID_TOMBSTONED); }
        if (majority(deposits, e -> DescriptorSignals.hasBenefitsToken(e.getRawDescriptor()))) {
            return Optional.of(PaycheckRejectReason.BENEFITS_SIGNAL);
        }
        if (EXCLUDED_PFC_DETAILED.contains(String.valueOf(latestPfcDetailed(deposits, facts)))
                || majority(deposits, e -> "TRANSFER_IN".equals(e.getPfcPrimary()) && e.getPfcConfidence() != null
                        && HIGH_CONFIDENCE.contains(e.getPfcConfidence().toUpperCase(Locale.ROOT)))
                || majority(deposits, e -> "payment_app".equals(e.getCounterpartyType()))) {
            return Optional.of(PaycheckRejectReason.PFC_EXCLUDED);
        }
        if (majority(deposits, e -> DescriptorSignals.hasNonPayrollChannelToken(e.getRawDescriptor()))) {
            return Optional.of(PaycheckRejectReason.DESCRIPTOR_EXCLUDED);
        }
        if (deposits.size() < 2) { return Optional.empty(); }
        if (cadence.isEmpty()) { return Optional.of(PaycheckRejectReason.CADENCE_UNSUPPORTED); }
        InvestmentFrequency f = cadence.get().cadence();
        if (median == null || median.compareTo(lowMedianFloor(f)) < 0) { return Optional.of(PaycheckRejectReason.LOW_MEDIAN); }
        long sinceLast = ChronoUnit.DAYS.between(deposits.get(deposits.size() - 1).getPostedDate(), todayET);
        return sinceLast > STALE_PERIODS * PayCalendar.periodDays(f) ? Optional.of(PaycheckRejectReason.STALE) : Optional.empty();
    }

    public static BigDecimal lowMedianFloor(InvestmentFrequency cadence) {
        return switch (cadence) {
            case WEEKLY -> LOW_MEDIAN_WEEKLY;
            case BIWEEKLY -> LOW_MEDIAN_BIWEEKLY;
            case SEMI_MONTHLY -> LOW_MEDIAN_SEMI_MONTHLY;
            case MONTHLY -> LOW_MEDIAN_MONTHLY;
        };
    }

    /** The latest deposit's PFC detailed value (Plaid recategorizes), else the Plaid stream's. */
    static String latestPfcDetailed(List<PaycheckEvent> deposits, Optional<StreamFacts> facts) {
        for (int i = deposits.size() - 1; i >= 0; i--) {
            if (deposits.get(i).getPfcDetailed() != null) { return deposits.get(i).getPfcDetailed(); }
        }
        return facts.map(StreamFacts::plaidPfcDetailed).orElse(null);
    }

    static boolean majority(List<PaycheckEvent> deposits, Predicate<PaycheckEvent> signal) {
        return deposits.stream().filter(signal).count() * 2 > deposits.size();
    }
}
