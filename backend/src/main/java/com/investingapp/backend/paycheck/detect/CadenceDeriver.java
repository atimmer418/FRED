package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.PaycheckCadenceSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Spec 4.4 cadence from the last six posted dates. */
public final class CadenceDeriver {

    public static final int MAX_DEPOSITS = 6;
    public static final int OUTLIER_MAX_DAYS = 4;
    public static final int OBSERVED_FROM_INTERVALS = 3;
    public static final int ANCHOR_TOLERANCE_BUSINESS_DAYS = 2;
    public static final int CLUSTER_SPREAD_DAYS = 4;
    public static final int MONTH_START_THROUGH_DAY = 3;
    public static final int MONTH_END_FROM_DAY = 28;
    public static final int CONSTANT_SPACING_DAYS = 28;
    public static final String ANCHOR_FIRST = "1";
    public static final String ANCHOR_LAST_BD = "LAST_BD";

    private record Fit(InvestmentFrequency cadence, Integer dow, LocalDate parity, String a1, String a2, int outside) {
    }

    /** {band low, band high, median low, median high} in days. */
    static int[] band(InvestmentFrequency cadence) {
        return switch (cadence) {
            case WEEKLY -> new int[] {5, 9, 6, 8};
            case BIWEEKLY -> new int[] {12, 16, 13, 15};
            case SEMI_MONTHLY -> new int[] {10, 20, 13, 17};
            case MONTHLY -> new int[] {26, 35, 28, 31};
        };
    }

    public static Optional<CadenceResult> derive(List<LocalDate> postedDatesAsc, Optional<String> plaidFrequencyWire) {
        if (postedDatesAsc == null || postedDatesAsc.size() < 2) { return Optional.empty(); }
        List<LocalDate> dates = postedDatesAsc.subList(Math.max(0, postedDatesAsc.size() - MAX_DEPOSITS), postedDatesAsc.size());
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) { gaps.add((int) ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i))); }
        Optional<List<String>> labels = anchorLabels(dates);
        List<Fit> fits = new ArrayList<>();
        for (InvestmentFrequency f : InvestmentFrequency.values()) {
            int outside = outsideCount(gaps, f);
            if (outside < 0) { continue; }
            if (f == InvestmentFrequency.WEEKLY || (f == InvestmentFrequency.BIWEEKLY && !alternates(gaps))) {
                LocalDate parity = parityDate(dates);
                fits.add(new Fit(f, parity.getDayOfWeek().getValue(), parity, null, null, outside));
            } else if (f == InvestmentFrequency.SEMI_MONTHLY && labels.isPresent() && takenInTurn(labels.get())) {
                List<String> anchors = distinctAnchors(labels.get());
                fits.add(new Fit(f, null, null, anchors.get(0), anchors.get(1), outside));
            } else if (f == InvestmentFrequency.MONTHLY && labels.isPresent() && distinctAnchors(labels.get()).size() == 1
                    && !gaps.stream().allMatch(g -> g == CONSTANT_SPACING_DAYS)) {
                fits.add(new Fit(f, null, null, labels.get().get(0), null, outside));
            }
        }
        if (fits.isEmpty()) { return Optional.empty(); }
        List<Fit> clean = fits.stream().filter(fit -> fit.outside() == 0).toList();
        List<Fit> pool = clean.isEmpty() ? fits : clean;
        Optional<InvestmentFrequency> plaid = plaidFrequencyWire.flatMap(InvestmentFrequency::tryParse);
        Fit chosen = pool.stream().filter(fit -> plaid.isPresent() && fit.cadence() == plaid.get()).findFirst()
                .or(() -> pool.stream().filter(fit -> fit.cadence() == InvestmentFrequency.BIWEEKLY).findFirst())
                .orElse(pool.get(0));
        PaycheckCadenceSource source = gaps.size() >= OBSERVED_FROM_INTERVALS && pool.size() == 1
                ? PaycheckCadenceSource.OBSERVED : PaycheckCadenceSource.PLAID;
        return Optional.of(new CadenceResult(chosen.cadence(), source, chosen.dow(), chosen.parity(), chosen.a1(), chosen.a2(),
                gaps.size(), chosen.outside() == 0, chosen.outside()));
    }

    private static int outsideCount(List<Integer> gaps, InvestmentFrequency f) {
        int[] b = band(f);
        List<Integer> s = gaps.stream().sorted().toList();
        int n = s.size();
        double median = n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
        if (median < b[2] || median > b[3]) { return -1; }
        int outside = 0;
        for (int g : gaps) {
            int distance = g < b[0] ? b[0] - g : Math.max(0, g - b[1]);
            if (distance > OUTLIER_MAX_DAYS) { return -1; }
            if (distance > 0) { outside++; }
        }
        return outside <= (gaps.size() >= 3 ? 1 : 0) ? outside : -1;
    }

    static boolean alternates(List<Integer> gaps) {
        if (gaps.size() < 3) { return false; }
        for (int i = 1; i < gaps.size(); i++) {
            int diff = gaps.get(i) - gaps.get(i - 1);
            if (Math.abs(diff) < 2 || (i > 1 && Integer.signum(diff) == Integer.signum(gaps.get(i - 1) - gaps.get(i - 2)))) {
                return false;
            }
        }
        return true;
    }

    static Optional<List<String>> anchorLabels(List<LocalDate> dates) {
        List<Integer> numericDays = dates.stream().filter(d -> fixedClass(d) == null).map(LocalDate::getDayOfMonth).toList();
        Map<Integer, String> numericAnchor = numericAnchors(numericDays);
        List<String> labels = new ArrayList<>();
        for (LocalDate d : dates) {
            String label = fixedClass(d) != null ? fixedClass(d) : numericAnchor.get(d.getDayOfMonth());
            LocalDate target = BusinessDays.adjustBackward(PayCalendar.nominal(YearMonth.from(d), label));
            if (Math.abs(BusinessDays.between(target, d)) > ANCHOR_TOLERANCE_BUSINESS_DAYS) { return Optional.empty(); }
            labels.add(label);
        }
        return Optional.of(labels);
    }

    private static String fixedClass(LocalDate d) {
        if (d.getDayOfMonth() <= MONTH_START_THROUGH_DAY) { return ANCHOR_FIRST; }
        boolean monthEnd = d.getDayOfMonth() >= MONTH_END_FROM_DAY || d.equals(PayCalendar.nominal(YearMonth.from(d), ANCHOR_LAST_BD));
        return monthEnd ? ANCHOR_LAST_BD : null;
    }

    private static Map<Integer, String> numericAnchors(List<Integer> days) {
        Map<Integer, String> anchorOf = new HashMap<>();
        List<Integer> sorted = days.stream().distinct().sorted().toList();
        for (int i = 0; i < sorted.size(); ) {
            int start = sorted.get(i);
            List<Integer> cluster = new ArrayList<>();
            while (i < sorted.size() && sorted.get(i) - start <= CLUSTER_SPREAD_DAYS) { cluster.add(sorted.get(i++)); }
            int modal = cluster.get(0);
            long best = -1;
            for (int day : cluster) {
                long count = days.stream().filter(x -> x == day).count();
                if (count >= best) { best = count; modal = day; }
            }
            for (int day : cluster) { anchorOf.put(day, Integer.toString(modal)); }
        }
        return anchorOf;
    }

    private static boolean takenInTurn(List<String> labels) {
        for (int i = 1; i < labels.size(); i++) {
            if (labels.get(i).equals(labels.get(i - 1))) { return false; }
        }
        return distinctAnchors(labels).size() == 2;
    }

    static List<String> distinctAnchors(List<String> labels) {
        return labels.stream().distinct()
                .sorted(Comparator.comparingInt(a -> ANCHOR_LAST_BD.equals(a) ? 99 : Integer.parseInt(a))).toList();
    }

    /** The latest date on the weekday seen most often. */
    private static LocalDate parityDate(List<LocalDate> dates) {
        int[] counts = new int[8];
        dates.forEach(d -> counts[d.getDayOfWeek().getValue()]++);
        int max = Arrays.stream(counts).max().orElse(0);
        for (int i = dates.size() - 1; i > 0; i--) {
            if (counts[dates.get(i).getDayOfWeek().getValue()] == max) { return dates.get(i); }
        }
        return dates.get(0);
    }
}
