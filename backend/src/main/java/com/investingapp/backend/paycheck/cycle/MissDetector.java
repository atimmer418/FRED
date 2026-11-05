package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckStream;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class MissDetector {

    public static final int MISS_GRACE_DAYS = 7;
    public static final int STALE_CONFIRMED_DAYS = 7;
    public static final int DORMANT_RETIRE_DAYS = 120;

    private MissDetector() {
    }

    public static boolean missed(PaycheckCycle open, LocalDate todayET) {
        return open.getState() == PaycheckCycleState.OPEN && open.getWindowEnd() != null
                && open.getWindowEnd().isBefore(todayET.minusDays(MISS_GRACE_DAYS));
    }

    public static boolean stale(PaycheckCycle cycle, LocalDateTime nowET) {
        return cycle.getState() == PaycheckCycleState.CONFIRMED && cycle.getConfirmedAt() != null
                && cycle.getConfirmedAt().isBefore(nowET.minusDays(STALE_CONFIRMED_DAYS));
    }

    public static Optional<LocalDate> dormantSince(PaycheckStream stream, List<PaycheckCycle> cycles) {
        return cycles.stream()
                .filter(c -> c.getState() == PaycheckCycleState.MISSED && stream.getId().equals(c.getStreamId()))
                .filter(c -> stream.getLastDate() == null || c.getExpectedDate().isAfter(stream.getLastDate()))
                .map(PaycheckCycle::getWindowEnd)
                .min(Comparator.naturalOrder());
    }

    public static boolean retireDue(LocalDate dormantSince, LocalDate todayET) {
        return !todayET.isBefore(dormantSince.plusDays(DORMANT_RETIRE_DAYS));
    }
}
