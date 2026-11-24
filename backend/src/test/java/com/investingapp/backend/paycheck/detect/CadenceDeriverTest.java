package com.investingapp.backend.paycheck.detect;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.investingapp.backend.model.InvestmentFrequency.BIWEEKLY;
import static com.investingapp.backend.model.InvestmentFrequency.MONTHLY;
import static com.investingapp.backend.model.InvestmentFrequency.SEMI_MONTHLY;
import static com.investingapp.backend.model.InvestmentFrequency.WEEKLY;
import static com.investingapp.backend.model.PaycheckCadenceSource.OBSERVED;
import static com.investingapp.backend.model.PaycheckCadenceSource.PLAID;
import static com.investingapp.backend.paycheck.detect.CadenceDeriver.ANCHOR_LAST_BD;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.d;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.dates;
import static org.assertj.core.api.Assertions.assertThat;

class CadenceDeriverTest {

    private static Optional<CadenceResult> derive(String plaid, String... isoDates) {
        return CadenceDeriver.derive(dates(isoDates), Optional.ofNullable(plaid));
    }

    @Test
    void weeklyFridays() {
        assertThat(derive(null, "2026-01-02", "2026-01-09", "2026-01-16", "2026-01-23", "2026-01-30", "2026-02-06"))
                .contains(new CadenceResult(WEEKLY, OBSERVED, 5, d("2026-02-06"), null, null, 5, true, 0));
    }

    @Test
    void biweeklyFridays() {
        assertThat(derive(null, "2026-01-09", "2026-01-23", "2026-02-06", "2026-02-20", "2026-03-06", "2026-03-20"))
                .contains(new CadenceResult(BIWEEKLY, OBSERVED, 5, d("2026-03-20"), null, null, 5, true, 0));
    }

    @Test
    void semiMonthlyFifteenthAndLastBusinessDay() {
        assertThat(derive(null, "2026-04-15", "2026-04-30", "2026-05-15", "2026-05-29", "2026-06-15", "2026-06-30"))
                .contains(new CadenceResult(SEMI_MONTHLY, OBSERVED, null, null, "15", ANCHOR_LAST_BD, 5, true, 0));
    }

    @Test
    void monthlyLastBusinessDay() {
        assertThat(derive(null, "2026-01-30", "2026-02-27", "2026-03-31", "2026-04-30"))
                .contains(new CadenceResult(MONTHLY, OBSERVED, null, null, ANCHOR_LAST_BD, null, 3, true, 0));
    }

    @Test
    void constantTwentyEightDaySpacingIsRejectedNotMonthly() {
        assertThat(derive("MONTHLY", "2026-01-02", "2026-01-30", "2026-02-27", "2026-03-27")).isEmpty();
        assertThat(derive("MONTHLY", "2026-01-30", "2026-02-27")).isEmpty(); // one anchor: only the 28-day rule rejects
    }

    @Test
    void plaidFrequencyBreaksABiweeklyOrSemiMonthlyTie() {
        String[] tie = {"2026-01-15", "2026-01-30", "2026-02-13"};
        assertThat(derive("SEMI_MONTHLY", tie)).contains(new CadenceResult(SEMI_MONTHLY, PLAID, null, null, "15", ANCHOR_LAST_BD, 2, true, 0));
        assertThat(derive(null, tie).orElseThrow().cadence()).isEqualTo(BIWEEKLY);
    }

    @Test
    void observedOnlyOnceThreeIntervalsAgree() {
        assertThat(derive(null, "2026-01-02", "2026-01-09", "2026-01-16").orElseThrow().source()).isEqualTo(PLAID);
        assertThat(derive(null, "2026-01-02", "2026-01-09", "2026-01-16", "2026-01-23").orElseThrow().source()).isEqualTo(OBSERVED);
    }

    // Final review DC-1: one skipped biweekly period splits into two periods only against a known cadence.
    @Test
    void skippedBiweeklyPeriodHoldsAgainstTheStoredCadence() {
        String[] skipped = {"2026-08-14", "2026-08-28", "2026-09-11", "2026-09-25", "2026-10-09", "2026-11-06"};
        assertThat(CadenceDeriver.derive(dates(skipped), Optional.empty(), Optional.of(BIWEEKLY)).orElseThrow().cadence())
                .isEqualTo(BIWEEKLY);
        assertThat(CadenceDeriver.derive(dates(skipped), Optional.empty(), Optional.empty())).isEmpty();
        assertThat(derive(null, skipped)).isEmpty();
    }

    // Final review DC-5: a 1st moved back onto the previous month's last business day is still the 1st.
    @Test
    void firstAndFifteenthAcrossAWeekendFirst() {
        // Aug 1 2026 is a Saturday (paid Fri Jul 31); Aug 15 is a Saturday (paid Fri Aug 14).
        CadenceResult result = derive("SEMI_MONTHLY",
                "2026-06-01", "2026-06-15", "2026-07-01", "2026-07-15", "2026-07-31", "2026-08-14").orElseThrow();
        assertThat(result.cadence()).isEqualTo(SEMI_MONTHLY);
        assertThat(result.anchorDay1()).isEqualTo("1");
        assertThat(result.anchorDay2()).isEqualTo("15");
    }

    @Test
    void monthlyOnTheFirstAcrossWeekendFirsts() {
        // Mar 1 2026 is a Sunday (paid Fri Feb 27); Aug 1 2026 is a Saturday (paid Fri Jul 31).
        CadenceResult result = derive(null,
                "2026-02-27", "2026-04-01", "2026-05-01", "2026-06-01", "2026-07-01", "2026-07-31").orElseThrow();
        assertThat(result.cadence()).isEqualTo(MONTHLY);
        assertThat(result.anchorDay1()).isEqualTo("1");
    }

    @Test
    void monthlyOnTheFirstAcrossNewYear() {
        // Nov 1 2026 is a Sunday (paid Fri Oct 30); Jan 1 2027 is a holiday (paid Thu Dec 31).
        CadenceResult result = derive(null,
                "2026-09-01", "2026-10-01", "2026-10-30", "2026-12-01", "2026-12-31", "2027-02-01").orElseThrow();
        assertThat(result.cadence()).isEqualTo(MONTHLY);
        assertThat(result.anchorDay1()).isEqualTo("1");
    }

    @Test
    void fifteenthAndLastBusinessDayEndingOnAMovedBackFirstKeepsLastBd() {
        CadenceResult result = derive(null,
                "2026-05-15", "2026-05-29", "2026-06-15", "2026-06-30", "2026-07-15", "2026-07-31").orElseThrow();
        assertThat(result.cadence()).isEqualTo(SEMI_MONTHLY);
        assertThat(result.anchorDay1()).isEqualTo("15");
        assertThat(result.anchorDay2()).isEqualTo(ANCHOR_LAST_BD);
    }

    @Test
    void oneLateDepositIsOneOutside() {
        assertThat(derive(null, "2026-01-09", "2026-01-23", "2026-02-06", "2026-02-20", "2026-03-06", "2026-03-23"))
                .contains(new CadenceResult(BIWEEKLY, OBSERVED, 5, d("2026-03-06"), null, null, 5, false, 1));
    }
}
