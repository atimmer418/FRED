package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.paycheck.detect.BusinessDays;
import com.investingapp.backend.paycheck.detect.PayCalendar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Spec 4.7 rules 1 to 7, pure. The caller has matched the event to the primary. Shadow tuning edits these constants. */
public final class CycleMatcher {

    public static final int LATE_SEEN_DAYS = 7;
    public static final BigDecimal AMOUNT_FLOOR = new BigDecimal("0.60");
    public static final BigDecimal AMOUNT_FLOOR_VOLATILE = new BigDecimal("0.45");
    public static final BigDecimal CV_VOLATILE = new BigDecimal("0.35");
    public static final double GAP_FRACTION = 0.60;
    public static final int GAP_MIN_BUSINESS_DAYS = 5;
    public static final int TRUST_MIN_DEPOSITS = 2;

    private static final Set<PaycheckStreamState> CONFIRMING_STATES =
            EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED, PaycheckStreamState.DORMANT);

    private CycleMatcher() {
    }

    /** lastConfirmed: latest posted date of a deposit already on a cycle of this stream (SHADOW_CONFIRMED included). */
    public static MatchDecision match(PaycheckEvent event, PaycheckStream stream, Optional<PaycheckCycle> open,
                Optional<LocalDate> lastConfirmed, LocalDateTime firstSeenET) {
        if (!CONFIRMING_STATES.contains(stream.getState())) {
            return MatchDecision.LOGGED_STREAM_STATE;
        }
        if (open.isEmpty() || open.get().getState() != PaycheckCycleState.OPEN) {
            return MatchDecision.PERIOD_TAKEN;
        }
        LocalDate posted = event.getPostedDate();
        if (ChronoUnit.DAYS.between(posted, firstSeenET.toLocalDate()) > LATE_SEEN_DAYS) {
            return MatchDecision.LATE_SEEN;
        }
        if (!amountMatches(event.getAmount(), stream)) {
            return MatchDecision.AMOUNT_MISMATCH;
        }
        boolean inWindow = inWindow(open.get(), posted);
        boolean gapHolds = lastConfirmed.map(last -> gapHolds(last, posted, stream.getCadence())).orElse(true);
        if (!gapHolds) {
            return inWindow ? MatchDecision.GAP_TOO_SHORT : MatchDecision.OUTSIDE_WINDOW_SECONDARY;
        }
        if (!inWindow && lastConfirmed.isEmpty()) { // rule 5 needs a known deposit to measure the gap from
            return MatchDecision.OUTSIDE_WINDOW_SECONDARY;
        }
        if (stream.getDepositsFound() < TRUST_MIN_DEPOSITS) { // T11's evaluate ran first and counted this deposit
            return MatchDecision.STREAM_UNTRUSTED;
        }
        return MatchDecision.CONFIRMABLE;
    }

    public static boolean inWindow(PaycheckCycle cycle, LocalDate date) {
        return !date.isBefore(cycle.getWindowStart()) && !date.isAfter(cycle.getWindowEnd());
    }

    private static boolean amountMatches(BigDecimal amount, PaycheckStream stream) {
        BigDecimal median = stream.getTrailingMedian();
        if (amount == null || median == null) {
            return false;
        }
        BigDecimal cv = stream.getTrailingCv();
        BigDecimal floor = cv != null && cv.compareTo(CV_VOLATILE) > 0 ? AMOUNT_FLOOR_VOLATILE : AMOUNT_FLOOR;
        return amount.compareTo(median.multiply(floor)) >= 0;
    }

    private static boolean gapHolds(LocalDate last, LocalDate posted, InvestmentFrequency cadence) {
        if (cadence == null) {
            return false;
        }
        double periodDays = PayCalendar.periodDays(cadence);
        return ChronoUnit.DAYS.between(last, posted) >= Math.ceil(GAP_FRACTION * periodDays)
                && !posted.isBefore(BusinessDays.plus(last, GAP_MIN_BUSINESS_DAYS));
    }
}
