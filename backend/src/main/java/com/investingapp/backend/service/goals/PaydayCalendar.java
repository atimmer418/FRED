package com.investingapp.backend.service.goals;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Lists a user's paydays in a date window, from the investment schedule's rule fields.
 *
 * <p>Pure date math. Each nominal date is pushed off weekends and holidays with
 * {@link InvestmentSchedule#adjustForBusinessDay}, the same logic the real scheduler uses.
 * That method is an instance method that reads no fields, so a throwaway entity is used to
 * reach it. The scheduler itself is not touched.
 */
public final class PaydayCalendar {

    /** Shared only to reach the stateless business-day logic. */
    private static final InvestmentSchedule BUSINESS_DAYS = new InvestmentSchedule();

    private PaydayCalendar() {
    }

    /**
     * The fields that decide when a user is paid. dayOfWeek is used by WEEKLY and BIWEEKLY,
     * dayOfMonth by MONTHLY; either may be null and then the anchor's value is used.
     */
    public record PayRule(InvestmentFrequency frequency, LocalDate anchor, DayOfWeek dayOfWeek, Integer dayOfMonth) {
    }

    /**
     * Reads the rule the way {@code calculateNextInvestmentDate} does: the exact chosen date wins
     * (anchor, weekday and day of month all come from it), otherwise the stored day fields, and
     * the start date fills any gap.
     */
    public static PayRule fromSchedule(InvestmentSchedule s) {
        InvestmentFrequency frequency = InvestmentFrequency.parse(s.getFrequency());
        LocalDate chosen = s.getChosenDate();
        if (chosen != null) {
            return new PayRule(frequency, chosen, chosen.getDayOfWeek(), chosen.getDayOfMonth());
        }
        LocalDate anchor = s.getStartDate();
        DayOfWeek dow = s.getDayOfWeek() != null ? DayOfWeek.valueOf(s.getDayOfWeek()) : anchor.getDayOfWeek();
        Integer dom = s.getDayOfMonth() != null ? s.getDayOfMonth() : anchor.getDayOfMonth();
        return new PayRule(frequency, anchor, dow, dom);
    }

    /** For a user with no schedule: paid monthly on the anchor's day of the month. */
    public static PayRule monthlyOn(LocalDate anchorDate) {
        return new PayRule(InvestmentFrequency.MONTHLY, anchorDate, anchorDate.getDayOfWeek(),
                anchorDate.getDayOfMonth());
    }

    /** Paydays strictly after {@code afterExclusive} and not after {@code throughInclusive}, sorted, unique. */
    public static List<LocalDate> paydays(PayRule rule, LocalDate afterExclusive, LocalDate throughInclusive) {
        // A nominal date up to a few days outside the window can be pushed into it.
        LocalDate from = afterExclusive.minusDays(7);
        LocalDate to = throughInclusive.plusDays(7);

        TreeSet<LocalDate> result = new TreeSet<>();
        for (LocalDate nominal : nominalDates(rule, from, to)) {
            LocalDate payday = BUSINESS_DAYS.adjustForBusinessDay(nominal);
            if (payday.isAfter(afterExclusive) && !payday.isAfter(throughInclusive)) {
                result.add(payday);
            }
        }
        return new ArrayList<>(result);
    }

    private static List<LocalDate> nominalDates(PayRule rule, LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        switch (rule.frequency()) {
            case WEEKLY, BIWEEKLY -> {
                DayOfWeek dow = rule.dayOfWeek() != null ? rule.dayOfWeek() : rule.anchor().getDayOfWeek();
                LocalDate d = from;
                while (d.getDayOfWeek() != dow) {
                    d = d.plusDays(1);
                }
                for (; !d.isAfter(to); d = d.plusWeeks(1)) {
                    // Parity is judged on the nominal date, so a holiday push never shifts the cycle.
                    if (rule.frequency() == InvestmentFrequency.WEEKLY
                            || ChronoUnit.WEEKS.between(rule.anchor(), d) % 2 == 0) {
                        out.add(d);
                    }
                }
            }
            case SEMI_MONTHLY -> {
                for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
                    out.add(m.atDay(1));
                    out.add(m.atDay(15));
                }
            }
            case MONTHLY -> {
                int dom = rule.dayOfMonth() != null ? rule.dayOfMonth() : rule.anchor().getDayOfMonth();
                for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
                    out.add(m.atDay(Math.min(dom, m.lengthOfMonth())));
                }
            }
        }
        return out;
    }
}
