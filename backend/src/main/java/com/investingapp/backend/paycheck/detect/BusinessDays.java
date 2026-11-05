package com.investingapp.backend.paycheck.detect;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Weekdays off InvestmentSchedule.isUSHoliday's table (pinned by parityWithScheduleAdjuster). */
public final class BusinessDays {

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !isHoliday(date);
    }

    public static LocalDate plus(LocalDate date, int n) {
        return step(date, n, n < 0 ? -1 : 1);
    }

    public static LocalDate minus(LocalDate date, int n) {
        return step(date, n, n < 0 ? 1 : -1);
    }

    private static LocalDate step(LocalDate date, int n, int direction) {
        LocalDate d = date;
        for (int left = Math.abs(n); left > 0; ) {
            d = d.plusDays(direction);
            if (isBusinessDay(d)) { left--; }
        }
        return d;
    }

    public static int between(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) { return -between(to, from); }
        int count = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (isBusinessDay(d)) { count++; }
        }
        return count;
    }

    public static LocalDate adjustBackward(LocalDate date) {
        LocalDate d = date;
        while (!isBusinessDay(d)) { d = d.minusDays(1); }
        return d;
    }

    static boolean isHoliday(LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        boolean monday = date.getDayOfWeek() == DayOfWeek.MONDAY;
        int nth = (day - 1) / 7 + 1;
        return (month == 1 && day == 1) || (month == 7 && day == 4) || (month == 12 && day == 25) || (month == 11 && day == 11)
                || (monday && month == 1 && nth == 3) || (monday && month == 2 && nth == 3)
                || (monday && month == 5 && day + 7 > date.lengthOfMonth())
                || (monday && month == 9 && nth == 1) || (monday && month == 10 && nth == 2)
                || (month == 11 && date.getDayOfWeek() == DayOfWeek.THURSDAY && nth == 4);
    }
}
