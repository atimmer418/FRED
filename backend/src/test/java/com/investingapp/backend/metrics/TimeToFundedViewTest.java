package com.investingapp.backend.metrics;

import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v_time_to_funded against datasets whose medians can be worked out on paper.
 *
 * Every test here owns the week of Monday 2024-04-08 and signs its users up on Wednesday
 * 2024-04-10, so the single row the view reports for that week describes exactly the users the test
 * seeded. @BeforeEach wipes the week first, so a re-run cannot double a count.
 *
 * Signups and settlements are seeded at the SAME time of day on purpose: the view measures with
 * TIMESTAMPDIFF(DAY, ...), which counts whole 24-hour periods, so matching clock times make the day
 * gaps exactly the numbers written in the comments. One test deliberately breaks that rule to pin
 * what happens on a partial day.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class TimeToFundedViewTest {

    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;
    private LocalDate cohortWeek;
    private LocalDateTime signupAt;
    private int transferSeq;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        // A Wednesday in the week of Monday 2024-04-08, which only this test uses.
        signupAt = LocalDateTime.of(2024, 4, 10, 10, 0);
        cohortWeek = MetricsViewSeed.mondayOf(signupAt.toLocalDate());
        transferSeq = 0;
        seed.cleanCohort(cohortWeek);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM v_time_to_funded WHERE cohort_week = ?", cohortWeek);
    }

    private long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private double pct(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    /** The medians come back as decimals, so they are read as doubles rather than longs. */
    private double days(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    /** A user who signed up with the cohort and settled their first transfer N days later. */
    private long fundedAfter(int days) {
        long user = seed.user(signupAt);
        seed.settledTransfer(user, signupAt.plusDays(days), "ttf-" + (++transferSeq));
        return user;
    }

    @Test
    @DisplayName("odd number of funded users: the median is the middle day count")
    void oddFundedCountTakesTheMiddleValue() {
        fundedAfter(2);
        fundedAfter(5);
        fundedAfter(9);

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(3);
        assertThat(num(row, "funded")).isEqualTo(3);
        // Sorted: 2, 5, 9. Three values, so the median is the 2nd = 5.
        assertThat(days(row, "median_days_to_funded")).isEqualTo(5.0);
        // p75 = the value at rank CEIL(3 * 0.75) = CEIL(2.25) = 3 = 9.
        assertThat(num(row, "p75_days_to_funded")).isEqualTo(9);

        assertThat(num(row, "funded_within_7d")).isEqualTo(2);   // 2 and 5
        assertThat(num(row, "funded_within_14d")).isEqualTo(3);  // 2, 5 and 9
        assertThat(num(row, "funded_within_30d")).isEqualTo(3);

        assertThat(pct(row, "pct_funded_within_7d")).isEqualTo(66.7);   // 2 of 3 signups = 66.666 -> 66.7
        assertThat(pct(row, "pct_funded_within_14d")).isEqualTo(100.0); // 3 of 3
        assertThat(pct(row, "pct_funded_within_30d")).isEqualTo(100.0); // 3 of 3
    }

    @Test
    @DisplayName("even number of funded users: the median is the mean of the two middle day counts")
    void evenFundedCountAveragesTheTwoMiddleValues() {
        fundedAfter(2);
        fundedAfter(3);
        fundedAfter(6);
        fundedAfter(10);

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(4);
        assertThat(num(row, "funded")).isEqualTo(4);
        // Sorted: 2, 3, 6, 10. Four values, so the median is (3 + 6) / 2 = 4.5.
        // 4.5 is none of the inputs, so an implementation that picked one row would fail here.
        assertThat(days(row, "median_days_to_funded")).isEqualTo(4.5);
        // p75 = the value at rank CEIL(4 * 0.75) = 3 = 6.
        assertThat(num(row, "p75_days_to_funded")).isEqualTo(6);

        assertThat(num(row, "funded_within_7d")).isEqualTo(3);   // 2, 3 and 6
        assertThat(num(row, "funded_within_14d")).isEqualTo(4);  // all four
        assertThat(num(row, "funded_within_30d")).isEqualTo(4);

        assertThat(pct(row, "pct_funded_within_7d")).isEqualTo(75.0);   // 3 of 4
        assertThat(pct(row, "pct_funded_within_14d")).isEqualTo(100.0); // 4 of 4
        assertThat(pct(row, "pct_funded_within_30d")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("the 7, 14 and 30 day bounds are inclusive: exactly 7 days counts as within 7")
    void withinBoundsAreInclusive() {
        // Seeded out of order, because the view has to sort them itself.
        fundedAfter(31);
        fundedAfter(7);
        fundedAfter(15);
        fundedAfter(8);
        fundedAfter(30);
        fundedAfter(14);

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(6);
        assertThat(num(row, "funded")).isEqualTo(6);

        // Sorted: 7, 8, 14, 15, 30, 31. Six values, so the median is (14 + 15) / 2 = 14.5.
        assertThat(days(row, "median_days_to_funded")).isEqualTo(14.5);
        // p75 = the value at rank CEIL(6 * 0.75) = CEIL(4.5) = 5 = 30.
        assertThat(num(row, "p75_days_to_funded")).isEqualTo(30);

        // Exactly 7 is in, 8 is out: the bound is <=.
        assertThat(num(row, "funded_within_7d")).isEqualTo(1);   // just the 7
        assertThat(num(row, "funded_within_14d")).isEqualTo(3);  // 7, 8, 14 (exactly 14 is in, 15 is out)
        assertThat(num(row, "funded_within_30d")).isEqualTo(5);  // 7, 8, 14, 15, 30 (31 is out)

        assertThat(pct(row, "pct_funded_within_7d")).isEqualTo(16.7);  // 1 of 6 = 16.666 -> 16.7
        assertThat(pct(row, "pct_funded_within_14d")).isEqualTo(50.0); // 3 of 6
        assertThat(pct(row, "pct_funded_within_30d")).isEqualTo(83.3); // 5 of 6 = 83.333 -> 83.3
    }

    @Test
    @DisplayName("a user who never funded is a signup only, and is in no median and no within-N count")
    void neverFundedUserCountsOnlyAsASignup() {
        fundedAfter(2);
        fundedAfter(4);
        seed.user(signupAt); // signed up, never funded, no executions at all

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(3);
        assertThat(num(row, "funded")).isEqualTo(2);

        // Only the two funded day counts are ranked: (2 + 4) / 2 = 3.0. If the never-funded user
        // were counted as some large number the median would be 4 instead, so 3.0 pins the
        // exclusion.
        assertThat(days(row, "median_days_to_funded")).isEqualTo(3.0);
        // p75 over two values = the value at rank CEIL(2 * 0.75) = 2 = 4.
        assertThat(num(row, "p75_days_to_funded")).isEqualTo(4);

        assertThat(num(row, "funded_within_7d")).isEqualTo(2);   // the never-funded user is not here
        assertThat(num(row, "funded_within_14d")).isEqualTo(2);
        assertThat(num(row, "funded_within_30d")).isEqualTo(2);

        // The shares are of EVERYONE who signed up, so the never-funded user is in the denominator
        // and drags the share down: 2 of 3, not 2 of 2.
        assertThat(pct(row, "pct_funded_within_7d")).isEqualTo(66.7);
        assertThat(pct(row, "pct_funded_within_14d")).isEqualTo(66.7);
        assertThat(pct(row, "pct_funded_within_30d")).isEqualTo(66.7);
    }

    @Test
    @DisplayName("a cohort where nobody funded: shares are 0.0, medians are NULL")
    void cohortWithNobodyFundedIsZeroSharesAndNullMedians() {
        seed.user(signupAt);
        seed.user(signupAt);

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(2);
        assertThat(num(row, "funded")).isZero();

        assertThat(num(row, "funded_within_7d")).isZero();
        assertThat(num(row, "funded_within_14d")).isZero();
        assertThat(num(row, "funded_within_30d")).isZero();

        // "None of them funded in a week" is a real answer, so these must be 0.0 and not NULL.
        assertThat(row.get("pct_funded_within_7d")).isNotNull();
        assertThat(row.get("pct_funded_within_14d")).isNotNull();
        assertThat(row.get("pct_funded_within_30d")).isNotNull();
        assertThat(pct(row, "pct_funded_within_7d")).isZero();
        assertThat(pct(row, "pct_funded_within_14d")).isZero();
        assertThat(pct(row, "pct_funded_within_30d")).isZero();

        // There is no middle value of an empty set, so these must be NULL and not 0: a 0 here would
        // read as "the whole cohort funded the day they signed up".
        assertThat(row.get("median_days_to_funded")).isNull();
        assertThat(row.get("p75_days_to_funded")).isNull();
    }

    @Test
    @DisplayName("an initiated transfer that never settled is not funding")
    void onlySettledTransfersCountAsFunding() {
        // Started an ACH on day 1 that never settled, then settled one on day 5. Day 5 is funding.
        long retried = seed.user(signupAt);
        seed.unsettledTransfer(retried, signupAt.plusDays(1));
        seed.settledTransfer(retried, signupAt.plusDays(5), "ttf-settled");

        // Never got money in at all.
        long stuck = seed.user(signupAt);
        seed.unsettledTransfer(stuck, signupAt.plusDays(2));

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(2);
        assertThat(num(row, "funded")).isEqualTo(1);

        // One funded value, 5. Not 1: the unsettled attempt on day 1 is not the clock stopping.
        assertThat(days(row, "median_days_to_funded")).isEqualTo(5.0);
        assertThat(num(row, "p75_days_to_funded")).isEqualTo(5);

        assertThat(num(row, "funded_within_7d")).isEqualTo(1);
        assertThat(pct(row, "pct_funded_within_7d")).isEqualTo(50.0);  // 1 of 2 signups
        assertThat(pct(row, "pct_funded_within_14d")).isEqualTo(50.0);
        assertThat(pct(row, "pct_funded_within_30d")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("with several settled transfers, the EARLIEST one is the one measured")
    void earliestSettledTransferStopsTheClock() {
        long user = seed.user(signupAt);
        // Inserted late-first, so the view cannot be relying on insertion order.
        seed.settledTransfer(user, signupAt.plusDays(12), "ttf-late");
        seed.settledTransfer(user, signupAt.plusDays(4), "ttf-early");

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(1);
        assertThat(num(row, "funded")).isEqualTo(1);           // one user, not two transfers
        assertThat(days(row, "median_days_to_funded")).isEqualTo(4.0);  // MIN(4, 12)
        assertThat(num(row, "p75_days_to_funded")).isEqualTo(4);
        assertThat(num(row, "funded_within_7d")).isEqualTo(1);  // 4 days, so inside the week
        assertThat(pct(row, "pct_funded_within_7d")).isEqualTo(100.0);  // 1 of 1
    }

    @Test
    @DisplayName("day counts are whole 24-hour periods, not calendar days")
    void dayCountsAreElapsedNotCalendar() {
        // Signed up late at night, settled six days and ten hours later: 6, not the 7 calendar
        // days a date-only subtraction would give.
        LocalDateTime lateNight = LocalDateTime.of(2024, 4, 10, 23, 0);
        long nightOwl = seed.user(lateNight);
        seed.settledTransfer(nightOwl, LocalDateTime.of(2024, 4, 17, 9, 0), "ttf-night");

        // Signed up in the morning, settled seven days and twenty-three hours later: 7, so it still
        // lands inside the 7-day bound even though it is nearly eight calendar days out.
        long morning = seed.user(LocalDateTime.of(2024, 4, 10, 9, 0));
        seed.settledTransfer(morning, LocalDateTime.of(2024, 4, 18, 8, 0), "ttf-morning");

        Map<String, Object> row = row();

        assertThat(num(row, "signed_up")).isEqualTo(2);
        assertThat(num(row, "funded")).isEqualTo(2);
        // Day counts 6 and 7, so the median is (6 + 7) / 2 = 6.5.
        assertThat(days(row, "median_days_to_funded")).isEqualTo(6.5);
        assertThat(num(row, "p75_days_to_funded")).isEqualTo(7);       // rank CEIL(2 * 0.75) = 2
        assertThat(num(row, "funded_within_7d")).isEqualTo(2);         // 6 <= 7 and 7 <= 7
        assertThat(pct(row, "pct_funded_within_7d")).isEqualTo(100.0); // 2 of 2
    }

    @Test
    @DisplayName("a user with no signup date is in no cohort at all")
    void nullSignupDateIsExcluded() {
        long user = fundedAfter(3);
        jdbc.update("UPDATE users SET create_date = NULL WHERE id = ?", user);
        try {
            // Funded, but with nothing to measure from, so the cohort has no row at all.
            assertThat(jdbc.queryForList("SELECT * FROM v_time_to_funded WHERE cohort_week = ?",
                    cohortWeek)).isEmpty();
        } finally {
            // Put the signup date back, or cleanCohort can never find this user again and its
            // settled transfer would outlive the test.
            jdbc.update("UPDATE users SET create_date = ? WHERE id = ?", signupAt, user);
        }
    }
}
