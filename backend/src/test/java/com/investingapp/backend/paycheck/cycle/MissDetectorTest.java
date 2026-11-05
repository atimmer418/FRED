package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MissDetectorTest {

    private static PaycheckCycle cycle(Long streamId, PaycheckCycleState state, LocalDate expected, LocalDate windowEnd) {
        PaycheckCycle c = new PaycheckCycle();
        c.setStreamId(streamId);
        c.setState(state);
        c.setExpectedDate(expected);
        c.setWindowEnd(windowEnd);
        return c;
    }

    @Test
    void windowPastSevenDaysIsMissed() {
        PaycheckCycle open = cycle(7L, PaycheckCycleState.OPEN, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 6));
        assertThat(MissDetector.missed(open, LocalDate.of(2026, 10, 13))).isFalse();
        assertThat(MissDetector.missed(open, LocalDate.of(2026, 10, 14))).isTrue();
        open.setState(PaycheckCycleState.SKIPPED);
        assertThat(MissDetector.missed(open, LocalDate.of(2026, 12, 31))).isFalse();
    }

    @Test
    void retireDueAt120DaysFromTheFirstMissOfTheSpell() {
        PaycheckStream stream = new PaycheckStream();
        stream.setId(7L);
        stream.setLastDate(LocalDate.of(2026, 5, 1));
        List<PaycheckCycle> missed = List.of(
                cycle(7L, PaycheckCycleState.MISSED, LocalDate.of(2026, 4, 17), LocalDate.of(2026, 4, 28)), // before last deposit
                cycle(7L, PaycheckCycleState.MISSED, LocalDate.of(2026, 5, 29), LocalDate.of(2026, 6, 9)),
                cycle(7L, PaycheckCycleState.MISSED, LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 26)),
                cycle(8L, PaycheckCycleState.MISSED, LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 19)));
        assertThat(MissDetector.dormantSince(stream, missed)).contains(LocalDate.of(2026, 5, 26));
        assertThat(MissDetector.retireDue(LocalDate.of(2026, 5, 26), LocalDate.of(2026, 9, 22))).isFalse();
        assertThat(MissDetector.retireDue(LocalDate.of(2026, 5, 26), LocalDate.of(2026, 9, 23))).isTrue();
    }
}
