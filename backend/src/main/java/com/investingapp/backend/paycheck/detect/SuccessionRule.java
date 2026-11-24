package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Spec 6.1 conditions (1) to (3) without the recency exception (R5). */
public final class SuccessionRule {

    public static final double QUIET_PERIODS = 1.5;

    public static boolean succeeds(PaycheckStream s1, PaycheckStream s2, LocalDate todayET) {
        if (s1 == null || s2 == null || Objects.equals(s1.getPayerToken(), s2.getPayerToken())) { return false; }
        boolean trusted = (s2.getState() == PaycheckStreamState.PROVISIONAL || s2.getState() == PaycheckStreamState.CONFIRMED)
                && s2.getDepositsFound() >= 2 && s2.getRole() != PaycheckStreamRole.INTERRUPTION;
        return trusted && s1Gone(s1, todayET);
    }

    static boolean s1Gone(PaycheckStream s1, LocalDate todayET) {
        if (s1.getMissedCount() >= 1 || Boolean.FALSE.equals(s1.getPlaidIsActive()) || "TOMBSTONED".equals(s1.getPlaidStatus())) {
            return true;
        }
        return s1.getLastDate() != null && s1.getCadence() != null
                && ChronoUnit.DAYS.between(s1.getLastDate(), todayET) > QUIET_PERIODS * PayCalendar.periodDays(s1.getCadence());
    }
}
