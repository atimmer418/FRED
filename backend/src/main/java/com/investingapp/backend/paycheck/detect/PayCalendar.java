package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Spec 4.4 next date and labels, 4.6 keys and windows; display data, never a trigger. */
public final class PayCalendar {

    public record CycleWindow(LocalDate start, LocalDate end) {
    }

    public static final int WINDOW_BEFORE_BUSINESS_DAYS = 5;
    public static final int WINDOW_AFTER_BUSINESS_DAYS = 7;
    public static final int SEMI_MONTHLY_WIDEN_BUSINESS_DAYS = 2;

    public static int periodDays(InvestmentFrequency cadence) {
        return switch (cadence) {
            case WEEKLY -> 7;
            case BIWEEKLY -> 14;
            case SEMI_MONTHLY -> 15;
            case MONTHLY -> 30;
        };
    }

    public static LocalDate predictedNext(CadenceResult cadence, LocalDate lastDate) {
        LocalDate guard = lastDate.plusDays(periodDays(cadence.cadence()) / 2);
        LocalDate next = null;
        if (isWeekBased(cadence)) {
            next = cadence.anchorParityDate() != null ? cadence.anchorParityDate() : lastDate;
            while (!next.isAfter(guard)) { next = next.plusDays(periodDays(cadence.cadence())); }
        } else {
            for (int m = 0; m <= 2; m++) {
                for (String anchor : anchors(cadence)) {
                    LocalDate candidate = nominal(YearMonth.from(lastDate).plusMonths(m), anchor);
                    if (candidate.isAfter(guard) && (next == null || candidate.isBefore(next))) { next = candidate; }
                }
            }
        }
        return BusinessDays.adjustBackward(next);
    }

    public static String periodKey(CadenceResult cadence, LocalDate expected) {
        if (isWeekBased(cadence)) {
            int dow = cadence.anchorDow() != null ? cadence.anchorDow() : expected.getDayOfWeek().getValue();
            LocalDate nominal = expected.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dow)));
            boolean offParity = cadence.cadence() == InvestmentFrequency.BIWEEKLY && cadence.anchorParityDate() != null
                    && Math.floorMod(ChronoUnit.DAYS.between(cadence.anchorParityDate(), nominal), 14) != 0;
            return (offParity ? nominal.plusDays(7) : nominal).toString();
        }
        List<String> anchors = anchors(cadence);
        LocalDate best = null;
        int bestIndex = 0;
        for (int m = -1; m <= 1; m++) {
            for (int i = 0; i < anchors.size(); i++) {
                LocalDate candidate = nominal(YearMonth.from(expected).plusMonths(m), anchors.get(i));
                if (!BusinessDays.adjustBackward(candidate).isBefore(expected) && (best == null || candidate.isBefore(best))) {
                    best = candidate;
                    bestIndex = i;
                }
            }
        }
        String suffix = cadence.cadence() == InvestmentFrequency.SEMI_MONTHLY ? (bestIndex == 0 ? "-A" : "-B") : "";
        return YearMonth.from(best) + suffix;
    }

    public static CycleWindow window(InvestmentFrequency cadence, LocalDate expected) {
        int widen = cadence == InvestmentFrequency.SEMI_MONTHLY ? SEMI_MONTHLY_WIDEN_BUSINESS_DAYS : 0;
        return new CycleWindow(BusinessDays.minus(expected, WINDOW_BEFORE_BUSINESS_DAYS + widen),
                BusinessDays.plus(expected, WINDOW_AFTER_BUSINESS_DAYS + widen));
    }

    public static String cadenceLabel(CadenceResult cadence) {
        return switch (cadence.cadence()) {
            case WEEKLY -> "Every " + weekday(cadence);
            case BIWEEKLY -> "Every other " + weekday(cadence);
            case SEMI_MONTHLY -> "Twice a month (" + dayLabel(cadence.anchorDay1()) + " and " + dayLabel(cadence.anchorDay2()) + ")";
            case MONTHLY -> "Monthly on the " + dayLabel(cadence.anchorDay1());
        };
    }

    static LocalDate nominal(YearMonth month, String anchor) {
        if (CadenceDeriver.ANCHOR_LAST_BD.equals(anchor)) { return BusinessDays.adjustBackward(month.atEndOfMonth()); }
        return month.atDay(Math.min(Integer.parseInt(anchor), month.lengthOfMonth()));
    }

    private static boolean isWeekBased(CadenceResult cadence) {
        return cadence.cadence() == InvestmentFrequency.WEEKLY || cadence.cadence() == InvestmentFrequency.BIWEEKLY;
    }

    private static List<String> anchors(CadenceResult cadence) {
        return Stream.of(cadence.anchorDay1(), cadence.anchorDay2()).filter(a -> a != null).toList();
    }

    private static String weekday(CadenceResult cadence) {
        return DayOfWeek.of(cadence.anchorDow()).getDisplayName(TextStyle.FULL, Locale.US);
    }

    private static String dayLabel(String anchor) {
        if (CadenceDeriver.ANCHOR_LAST_BD.equals(anchor)) { return "last business day"; }
        int day = Integer.parseInt(anchor);
        return day + (day >= 11 && day <= 13 ? "th" : switch (day % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; });
    }
}
