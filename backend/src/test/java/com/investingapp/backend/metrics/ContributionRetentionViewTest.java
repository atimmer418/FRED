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
 * v_contribution_retention against datasets whose answers are worked out by hand.
 *
 * The cohort week (Monday 2024-05-13) is one nobody else uses, and every user below signs up on
 * Wednesday 2024-05-15, so the single row these tests read describes exactly the users each test
 * seeds and nothing else in the schema. {@code cleanCohort} in {@link #setUp()} makes a re-run
 * repeatable rather than cumulative.
 *
 * <p>Two clocks matter in this view and they are deliberately kept apart here. The COHORT comes
 * from the signup date. ELIGIBILITY comes from the user's own first transfer against NOW(), so the
 * fixed 2024 transfer dates below are permanently elapsed, and the one "recent" user is seeded
 * relative to {@code now()} on purpose.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ContributionRetentionViewTest {

    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;
    private LocalDate cohortWeek;
    private LocalDateTime signupAt;
    /** Every seeded user's first settled transfer, so "+N days" below is readable. */
    private LocalDateTime firstAt;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        // A Wednesday in the week this test owns.
        signupAt = LocalDateTime.of(2024, 5, 15, 10, 0);
        cohortWeek = MetricsViewSeed.mondayOf(signupAt.toLocalDate());
        firstAt = signupAt.plusDays(2);     // 2024-05-17 10:00, so every window is long closed
        seed.cleanCohort(cohortWeek);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM v_contribution_retention WHERE cohort_week = ?",
                cohortWeek);
    }

    private long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private double pct(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    @Test
    @DisplayName("counts a user as retained in each 30-day window they came back in")
    void retentionPerMonth() {
        // A: came back inside month 1 and month 2, then stopped.
        long cameBackTwice = seed.user(signupAt);
        seed.settledTransfer(cameBackTwice, firstAt, "a-first");
        seed.settledTransfer(cameBackTwice, firstAt.plusDays(10), "a-m1");   // (0, 30]  -> m1
        seed.settledTransfer(cameBackTwice, firstAt.plusDays(45), "a-m2");   // (30, 60] -> m2

        // B: funded once and never again. Counted everywhere, retained nowhere.
        long fundedOnce = seed.user(signupAt);
        seed.settledTransfer(fundedOnce, firstAt, "b-first");

        // C: came back on day 29, went quiet, then came back on day 160.
        long cameBackEarlyAndLate = seed.user(signupAt);
        seed.settledTransfer(cameBackEarlyAndLate, firstAt, "c-first");
        seed.settledTransfer(cameBackEarlyAndLate, firstAt.plusDays(29), "c-m1");   // (0, 30]    -> m1
        seed.settledTransfer(cameBackEarlyAndLate, firstAt.plusDays(160), "c-m6");  // (150, 180] -> m6

        // D: silent for two months, back on day 75.
        long returnedLate = seed.user(signupAt);
        seed.settledTransfer(returnedLate, firstAt, "d-first");
        seed.settledTransfer(returnedLate, firstAt.plusDays(75), "d-m3");     // (60, 90] -> m3

        Map<String, Object> row = row();

        assertThat(num(row, "funded_users")).isEqualTo(4);          // A, B, C, D
        // Every first transfer is in May 2024, so all four windows closed years ago.
        assertThat(num(row, "eligible_m1")).isEqualTo(4);
        assertThat(num(row, "eligible_m2")).isEqualTo(4);
        assertThat(num(row, "eligible_m3")).isEqualTo(4);
        assertThat(num(row, "eligible_m6")).isEqualTo(4);

        assertThat(num(row, "retained_m1")).isEqualTo(2);           // A, C
        assertThat(num(row, "retained_m2")).isEqualTo(1);           // A
        assertThat(num(row, "retained_m3")).isEqualTo(1);           // D
        assertThat(num(row, "retained_m6")).isEqualTo(1);           // C

        assertThat(pct(row, "pct_retained_m1")).isEqualTo(50.0);    // 2 of 4 eligible = 50.0
        assertThat(pct(row, "pct_retained_m2")).isEqualTo(25.0);    // 1 of 4 eligible = 25.0
        assertThat(pct(row, "pct_retained_m3")).isEqualTo(25.0);    // 1 of 4 eligible = 25.0
        assertThat(pct(row, "pct_retained_m6")).isEqualTo(25.0);    // 1 of 4 eligible = 25.0
    }

    @Test
    @DisplayName("a contribution at exactly 30 days is month 1, not month 2")
    void thirtyDayBoundaryBelongsToMonthOne() {
        // The top of the window is inclusive: (first, first + 30 days].
        long onTheLine = seed.user(signupAt);
        seed.settledTransfer(onTheLine, firstAt, "x-first");
        seed.settledTransfer(onTheLine, firstAt.plusDays(30), "x-day-30");

        Map<String, Object> row = row();

        assertThat(num(row, "funded_users")).isEqualTo(1);
        assertThat(num(row, "retained_m1")).isEqualTo(1);           // day 30 is inside (0, 30]
        assertThat(num(row, "retained_m2")).isZero();               // and not inside (30, 60]
        assertThat(num(row, "retained_m3")).isZero();
        assertThat(num(row, "retained_m6")).isZero();

        assertThat(pct(row, "pct_retained_m1")).isEqualTo(100.0);   // 1 of 1 eligible = 100.0
        assertThat(pct(row, "pct_retained_m2")).isZero();           // 0 of 1 eligible = 0.0
    }

    @Test
    @DisplayName("a contribution at exactly 60 days is month 2, not month 3")
    void sixtyDayBoundaryBelongsToMonthTwo() {
        // The same rule one window along, so the boundary is not an accident of month 1.
        long onTheLine = seed.user(signupAt);
        seed.settledTransfer(onTheLine, firstAt, "y-first");
        seed.settledTransfer(onTheLine, firstAt.plusDays(60), "y-day-60");

        Map<String, Object> row = row();

        assertThat(num(row, "funded_users")).isEqualTo(1);
        assertThat(num(row, "retained_m1")).isZero();               // day 60 is past (0, 30]
        assertThat(num(row, "retained_m2")).isEqualTo(1);           // inside (30, 60]
        assertThat(num(row, "retained_m3")).isZero();               // not inside (60, 90]

        assertThat(pct(row, "pct_retained_m1")).isZero();           // 0 of 1 eligible = 0.0
        assertThat(pct(row, "pct_retained_m2")).isEqualTo(100.0);   // 1 of 1 eligible = 100.0
    }

    @Test
    @DisplayName("the first transfer itself is not retention")
    void firstTransferIsNotRetention() {
        // One settled transfer and nothing after it. Retention means coming BACK, so this user is
        // funded and fully eligible evidence, and the answer they give is "no".
        long neverCameBack = seed.user(signupAt);
        seed.settledTransfer(neverCameBack, firstAt, "z-first");

        Map<String, Object> row = row();

        assertThat(num(row, "funded_users")).isEqualTo(1);
        assertThat(num(row, "eligible_m1")).isEqualTo(1);
        assertThat(num(row, "retained_m1")).isZero();               // 0 of 1 eligible
        assertThat(pct(row, "pct_retained_m1")).isZero();           // = 0.0, not 100.0
    }

    @Test
    @DisplayName("a cohort that funded but never came back reports 0.0, never NULL")
    void noRetentionReportsZeroNotNull() {
        // Three users, one settled transfer each, no second contribution anywhere.
        for (int i = 0; i < 3; i++) {
            long user = seed.user(signupAt);
            seed.settledTransfer(user, firstAt, "none-" + i);
        }

        Map<String, Object> row = row();

        assertThat(num(row, "funded_users")).isEqualTo(3);
        assertThat(num(row, "eligible_m1")).isEqualTo(3);
        assertThat(num(row, "eligible_m6")).isEqualTo(3);
        assertThat(num(row, "retained_m1")).isZero();

        // The division is 0/3, not 0/0, but every rate column must still read as a number.
        assertThat(row.get("pct_retained_m1")).isNotNull();
        assertThat(row.get("pct_retained_m2")).isNotNull();
        assertThat(row.get("pct_retained_m3")).isNotNull();
        assertThat(row.get("pct_retained_m6")).isNotNull();
        assertThat(pct(row, "pct_retained_m1")).isZero();           // 0 of 3 eligible = 0.0
        assertThat(pct(row, "pct_retained_m2")).isZero();
        assertThat(pct(row, "pct_retained_m3")).isZero();
        assertThat(pct(row, "pct_retained_m6")).isZero();
    }

    @Test
    @DisplayName("a user who funded five days ago is counted, but is not yet evidence either way")
    void recentlyFundedUserIsNotYetEligible() {
        // The trap this view exists to avoid. E funded in 2024 and came back inside month 1.
        long fundedLongAgo = seed.user(signupAt);
        seed.settledTransfer(fundedLongAgo, firstAt, "e-first");
        seed.settledTransfer(fundedLongAgo, firstAt.plusDays(7), "e-m1");

        // F signed up in the same week but only funded five days ago. No window of theirs has
        // closed, so they cannot answer "did you come back" yet.
        long fundedFiveDaysAgo = seed.user(signupAt);
        seed.settledTransfer(fundedFiveDaysAgo, LocalDateTime.now().minusDays(5), "f-first");

        Map<String, Object> row = row();

        assertThat(num(row, "funded_users")).isEqualTo(2);          // E and F both funded
        assertThat(num(row, "eligible_m1")).isEqualTo(1);           // only E: F is 25 days short
        assertThat(num(row, "eligible_m2")).isEqualTo(1);
        assertThat(num(row, "eligible_m3")).isEqualTo(1);
        assertThat(num(row, "eligible_m6")).isEqualTo(1);

        assertThat(num(row, "retained_m1")).isEqualTo(1);           // E
        // F is excluded from the denominator, so the rate is 1 of 1 and NOT 1 of 2 = 50.0.
        assertThat(pct(row, "pct_retained_m1")).isEqualTo(100.0);   // 1 of 1 eligible = 100.0
        assertThat(pct(row, "pct_retained_m2")).isZero();           // 0 of 1 eligible = 0.0
        assertThat(pct(row, "pct_retained_m3")).isZero();
        assertThat(pct(row, "pct_retained_m6")).isZero();
    }

    @Test
    @DisplayName("one transfer funding several executions is one contribution")
    void transfersAreCountedOncePerTransfer() {
        // G: a second ACH funded two investments at once. Two execution rows, one transfer id,
        // one contribution, inside month 1.
        long oneAchTwoExecutions = seed.user(signupAt);
        seed.settledTransfer(oneAchTwoExecutions, firstAt, "g-first");
        seed.settledTransfer(oneAchTwoExecutions, firstAt.plusDays(10), "g-ach");
        seed.settledTransfer(oneAchTwoExecutions, firstAt.plusDays(12), "g-ach");

        // H: two genuinely separate transfers in month 1. Still ONE retained user.
        long twoSeparateAchs = seed.user(signupAt);
        seed.settledTransfer(twoSeparateAchs, firstAt, "h-first");
        seed.settledTransfer(twoSeparateAchs, firstAt.plusDays(5), "h-ach-1");
        seed.settledTransfer(twoSeparateAchs, firstAt.plusDays(20), "h-ach-2");

        // I: their ONLY money was one ACH funding two investments on day one. The siblings were
        // marked minutes apart, which must not read as "came back" two minutes after funding.
        long onlyEverOneAch = seed.user(signupAt);
        seed.settledTransfer(onlyEverOneAch, firstAt, "i-ach");
        seed.settledTransfer(onlyEverOneAch, firstAt.plusMinutes(2), "i-ach");

        Map<String, Object> row = row();

        assertThat(num(row, "funded_users")).isEqualTo(3);          // G, H, I: one row per user
        assertThat(num(row, "eligible_m1")).isEqualTo(3);
        assertThat(num(row, "retained_m1")).isEqualTo(2);           // G and H, counted once each
        assertThat(num(row, "retained_m2")).isZero();
        assertThat(pct(row, "pct_retained_m1")).isEqualTo(66.7);    // 2 of 3 eligible = 66.666 -> 66.7
        assertThat(pct(row, "pct_retained_m2")).isZero();           // 0 of 3 eligible = 0.0
    }

    @Test
    @DisplayName("an execution that never settled is not a contribution")
    void unsettledExecutionIsNotRetention() {
        // Money that never landed is not a contribution, however many rows it left behind.
        long triedAndFailed = seed.user(signupAt);
        seed.settledTransfer(triedAndFailed, firstAt, "j-first");
        seed.unsettledTransfer(triedAndFailed, firstAt.plusDays(10));

        Map<String, Object> row = row();

        assertThat(num(row, "funded_users")).isEqualTo(1);
        assertThat(num(row, "eligible_m1")).isEqualTo(1);
        assertThat(num(row, "retained_m1")).isZero();               // 0 of 1 eligible
        assertThat(pct(row, "pct_retained_m1")).isZero();           // = 0.0
    }
}
