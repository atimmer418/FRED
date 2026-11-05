package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentSchedule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDaysTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void parityWithScheduleAdjuster() {
        for (LocalDate day = d("2025-01-01"); day.isBefore(d("2029-01-01")); day = day.plusDays(1)) {
            LocalDate next = day;
            while (!BusinessDays.isBusinessDay(next)) { next = next.plusDays(1); }
            assertThat(new InvestmentSchedule().adjustForBusinessDay(day)).as("adjusting %s", day).isEqualTo(next);
        }
    }

    @Test
    void plusMinusBetween() {
        assertThat(BusinessDays.plus(d("2026-11-25"), 1)).isEqualTo(d("2026-11-27")); // Thanksgiving
        assertThat(BusinessDays.minus(d("2026-11-30"), 2)).isEqualTo(d("2026-11-25"));
        assertThat(BusinessDays.between(d("2026-02-09"), d("2026-02-06"))).isEqualTo(-1);
    }

    @Test
    void adjustBackwardMovesEarlyNeverLate() {
        assertThat(BusinessDays.adjustBackward(d("2026-02-15"))).isEqualTo(d("2026-02-13"));
        assertThat(BusinessDays.adjustBackward(d("2026-01-01"))).isEqualTo(d("2025-12-31"));
        assertThat(BusinessDays.adjustBackward(d("2026-01-19"))).isEqualTo(d("2026-01-16")); // MLK Day
    }
}
