package com.investingapp.backend.service.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.service.goals.PaydayCalendar.PayRule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaydayCalendarTest {

    private static final InvestmentSchedule CALENDAR = new InvestmentSchedule();

    private static LocalDate adjusted(LocalDate nominal) {
        return CALENDAR.adjustForBusinessDay(nominal);
    }

    @Test
    void semiMonthlyIsFirstAndFifteenthPushedOffWeekends() {
        PayRule rule = new PayRule(InvestmentFrequency.SEMI_MONTHLY, LocalDate.of(2026, 11, 2), null, null);

        List<LocalDate> result = PaydayCalendar.paydays(rule, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30));

        // Nov 1 2026 is a Sunday, so it moves to Monday Nov 2. Nov 15 is also a Sunday, so Nov 16.
        assertEquals(List.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 16)), result);
        assertEquals(LocalDate.of(2026, 11, 2), adjusted(LocalDate.of(2026, 11, 1)));
    }

    @Test
    void weeklyListsEveryPreferredWeekdayInWindow() {
        PayRule rule = new PayRule(InvestmentFrequency.WEEKLY, LocalDate.of(2026, 11, 5), DayOfWeek.THURSDAY, null);

        List<LocalDate> result = PaydayCalendar.paydays(rule, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 3));

        List<LocalDate> expected = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 11, 5); !d.isAfter(LocalDate.of(2026, 12, 3)); d = d.plusWeeks(1)) {
            expected.add(adjusted(d));
        }
        assertEquals(expected, result);
        // Thanksgiving (Thu Nov 26) is pushed to Friday Nov 27.
        assertTrue(result.contains(LocalDate.of(2026, 11, 27)));
        assertEquals(5, result.size());
    }

    @Test
    void biweeklyKeepsParityAcrossAHolidayPush() {
        // Anchor Mon Jan 4 2027. Jan 18 is MLK Day, so it lands Tue Jan 19; the next one is Feb 1.
        PayRule rule = new PayRule(InvestmentFrequency.BIWEEKLY, LocalDate.of(2027, 1, 4), DayOfWeek.MONDAY, null);

        List<LocalDate> result = PaydayCalendar.paydays(rule, LocalDate.of(2027, 1, 4), LocalDate.of(2027, 2, 5));

        assertEquals(LocalDate.of(2027, 1, 19), adjusted(LocalDate.of(2027, 1, 18)));
        assertEquals(List.of(LocalDate.of(2027, 1, 19), LocalDate.of(2027, 2, 1)), result);
    }

    @Test
    void monthlyClampsToShortMonths() {
        PayRule rule = new PayRule(InvestmentFrequency.MONTHLY, LocalDate.of(2027, 1, 31), null, 31);

        // Jan 31 2027 is a Sunday and lands Mon Feb 1, so start after that.
        List<LocalDate> result = PaydayCalendar.paydays(rule, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 3, 2));

        // Feb 2027 has 28 days and Feb 28 is a Sunday, so it moves forward to Monday Mar 1.
        LocalDate expected = adjusted(LocalDate.of(2027, 2, 28));
        assertEquals(LocalDate.of(2027, 3, 1), expected);
        assertEquals(List.of(expected), result);
    }

    @Test
    void windowIsExclusiveStartInclusiveEnd() {
        PayRule rule = new PayRule(InvestmentFrequency.WEEKLY, LocalDate.of(2026, 11, 5), DayOfWeek.THURSDAY, null);

        List<LocalDate> result = PaydayCalendar.paydays(rule, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 19));

        assertEquals(List.of(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 19)), result);
    }

    @Test
    void parityWithSchedulerFromARealPayday() {
        LocalDate chosen = LocalDate.of(2026, 11, 19); // a Thursday
        for (InvestmentFrequency frequency : InvestmentFrequency.values()) {
            InvestmentSchedule schedule = new InvestmentSchedule();
            schedule.setFrequency(frequency.name());
            schedule.setStartDate(chosen);
            schedule.setChosenDate(chosen);
            PayRule rule = PaydayCalendar.fromSchedule(schedule);

            LocalDate payday = adjusted(chosen);
            // Walk eight paydays so weekends, Thanksgiving, Christmas, New Year and MLK are crossed.
            for (int i = 0; i < 8; i++) {
                LocalDate fromCalendar = PaydayCalendar.paydays(rule, payday, payday.plusDays(60)).get(0);
                LocalDate fromScheduler = schedule.calculateNextInvestmentDate(payday);
                assertEquals(fromScheduler, fromCalendar, frequency + " from " + payday);
                payday = fromScheduler;
            }
        }
    }

    @Test
    void parityHoldsForMonthlyOnTheThirtyFirstAndStoredDayFields() {
        // Starts at Nov 30 so no adjusted payday crosses into the next month (the scheduler's
        // MONTHLY branch anchors on the from-date's month; see the task report).
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setFrequency("MONTHLY");
        schedule.setStartDate(LocalDate.of(2026, 11, 30));
        schedule.setDayOfMonth(31);
        PayRule rule = PaydayCalendar.fromSchedule(schedule);

        LocalDate payday = adjusted(LocalDate.of(2026, 11, 30));
        LocalDate fromCalendar = PaydayCalendar.paydays(rule, payday, payday.plusDays(60)).get(0);
        assertEquals(schedule.calculateNextInvestmentDate(payday), fromCalendar);
    }

    @Test
    void monthlyOnAnchorDayWhenNoSchedule() {
        PayRule rule = PaydayCalendar.monthlyOn(LocalDate.of(2026, 11, 18));

        List<LocalDate> result = PaydayCalendar.paydays(rule, LocalDate.of(2026, 11, 18), LocalDate.of(2027, 1, 31));

        // Jan 18 2027 is MLK Day, so the adjusted date is checked through the scheduler's own logic.
        assertEquals(
                List.of(
                        adjusted(LocalDate.of(2026, 12, 18)),
                        adjusted(LocalDate.of(2027, 1, 18))),
                result);
        assertEquals(LocalDate.of(2027, 1, 19), result.get(1));
    }
}
