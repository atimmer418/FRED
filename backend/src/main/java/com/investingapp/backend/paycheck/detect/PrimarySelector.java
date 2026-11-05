package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Spec 4.5 primary selection; every tie ends on the lower id. */
public final class PrimarySelector {

    public record Selection(Long primaryStreamId, List<Long> secondaryStreamIds, boolean needsChooser, boolean lowConfidence) {
    }

    public static final BigDecimal LOW_CONFIDENCE_SHARE = new BigDecimal("0.40");
    public static final BigDecimal LOW_CONFIDENCE_GOAL_MULTIPLE = new BigDecimal("4");

    private static final Comparator<PaycheckStream> BY_ID = Comparator.comparing(PaycheckStream::getId);
    private static final Comparator<PaycheckStream> BY_MEDIAN_DESC =
            Comparator.comparing(PaycheckStream::getTrailingMedian, Comparator.nullsLast(Comparator.reverseOrder()));
    private static final Comparator<PaycheckStream> SAME_PAYER = Comparator
            .comparing((PaycheckStream s) -> s.getCadence() == null ? 0 : s.getCadence().perYear(), Comparator.reverseOrder())
            .thenComparing(PaycheckStream::getTrailingCv, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(BY_ID);
    private static final Comparator<PaycheckStream> BY_SCORE = Comparator
            .comparing((PaycheckStream s) -> (int) s.getScore(), Comparator.reverseOrder()).thenComparing(BY_MEDIAN_DESC).thenComparing(BY_ID);

    public static Selection select(List<PaycheckStream> qualifying, BigDecimal monthlyGoal) {
        if (qualifying == null || qualifying.isEmpty()) { return new Selection(null, List.of(), false, false); }
        Map<String, List<PaycheckStream>> byPayer = new TreeMap<>();
        qualifying.forEach(s -> byPayer.computeIfAbsent(s.getPayerToken(), k -> new ArrayList<>()).add(s));
        List<PaycheckStream> winners = byPayer.values().stream().map(l -> l.stream().min(SAME_PAYER).orElseThrow()).toList();
        List<PaycheckStream> confirmed = winners.stream().filter(s -> s.getState() == PaycheckStreamState.CONFIRMED).toList();
        boolean needsChooser = confirmed.size() >= 2;
        PaycheckStream primary = needsChooser ? confirmed.stream().min(BY_MEDIAN_DESC.thenComparing(BY_ID)).orElseThrow()
                : winners.stream().min(BY_SCORE).orElseThrow();
        List<Long> secondary = qualifying.stream().map(PaycheckStream::getId).filter(id -> !id.equals(primary.getId())).sorted().toList();
        return new Selection(primary.getId(), secondary, needsChooser, lowConfidence(primary, monthlyGoal));
    }

    static boolean lowConfidence(PaycheckStream primary, BigDecimal monthlyGoal) {
        if (primary.getCadence() == null || primary.getTrailingMedian() == null) { return false; }
        BigDecimal goal = monthlyGoal == null || monthlyGoal.signum() <= 0 ? BigDecimal.valueOf(AmountPrefill.DEFAULT_GOAL) : monthlyGoal;
        BigDecimal threshold = goal.multiply(LOW_CONFIDENCE_GOAL_MULTIPLE).multiply(LOW_CONFIDENCE_SHARE);
        return DepositStats.monthlyNormalized(primary.getTrailingMedian(), primary.getCadence()).compareTo(threshold) < 0;
    }
}
