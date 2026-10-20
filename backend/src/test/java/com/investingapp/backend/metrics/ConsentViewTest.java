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
 * v_consent against a dataset whose answers are known by construction.
 *
 * This test owns two cohort weeks that nobody else uses: the week of Monday 2024-08-12 (users sign
 * up on Wednesday the 14th) and the week after it, Monday 2024-08-19 (Wednesday the 21st). Both are
 * cleaned before every test, so the numbers below describe exactly the users seeded in that test
 * and nothing else in the schema.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ConsentViewTest {

    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;
    private LocalDate cohortWeek;
    private LocalDateTime signupAt;
    private LocalDate nextCohortWeek;
    private LocalDateTime nextWeekSignupAt;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        // Wednesday 2024-08-14, which belongs to the week of Monday 2024-08-12.
        signupAt = LocalDateTime.of(2024, 8, 14, 9, 30);
        cohortWeek = MetricsViewSeed.mondayOf(signupAt.toLocalDate());
        // Wednesday 2024-08-21, a separate cohort: the week of Monday 2024-08-19.
        nextWeekSignupAt = LocalDateTime.of(2024, 8, 21, 9, 30);
        nextCohortWeek = MetricsViewSeed.mondayOf(nextWeekSignupAt.toLocalDate());

        seed.cleanCohort(cohortWeek);
        seed.cleanCohort(nextCohortWeek);
    }

    private Map<String, Object> row(LocalDate week) {
        return jdbc.queryForMap("SELECT * FROM v_consent WHERE cohort_week = ?", week);
    }

    private long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private double pct(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    @Test
    @DisplayName("four signups with one opt-out is a quarter of the cohort unmeasured")
    void oneOptOutOfFour() {
        // The Java and the SQL must agree on which Monday owns a Wednesday signup.
        assertThat(cohortWeek).isEqualTo(LocalDate.of(2024, 8, 12));

        seed.user(signupAt);
        seed.user(signupAt);
        seed.user(signupAt);
        long quiet = seed.user(signupAt);
        seed.optOut(quiet);

        Map<String, Object> row = row(cohortWeek);

        assertThat(num(row, "users")).isEqualTo(4);
        assertThat(num(row, "opted_out")).isEqualTo(1);
        assertThat(num(row, "opted_in")).isEqualTo(3);        // 4 seeded - 1 opted out
        assertThat(pct(row, "pct_opted_out")).isEqualTo(25.0); // 1 of 4 = 25.0
    }

    @Test
    @DisplayName("a cohort where nobody opted out reads 0.0, never NULL")
    void nobodyOptedOut() {
        seed.user(signupAt);
        seed.user(signupAt);
        seed.user(signupAt);

        Map<String, Object> row = row(cohortWeek);

        assertThat(num(row, "users")).isEqualTo(3);
        assertThat(num(row, "opted_out")).isZero();
        assertThat(num(row, "opted_in")).isEqualTo(3);        // all 3 default to opted in
        // A missing rate would read as "no data" on a dashboard, which is the opposite of the truth.
        assertThat(row.get("pct_opted_out")).isNotNull();
        assertThat(pct(row, "pct_opted_out")).isZero();        // 0 of 3 = 0.0
    }

    @Test
    @DisplayName("a cohort where everybody opted out is 100% invisible to PostHog")
    void everybodyOptedOut() {
        long a = seed.user(signupAt);
        long b = seed.user(signupAt);
        long c = seed.user(signupAt);
        seed.optOut(a);
        seed.optOut(b);
        seed.optOut(c);

        Map<String, Object> row = row(cohortWeek);

        assertThat(num(row, "users")).isEqualTo(3);
        assertThat(num(row, "opted_out")).isEqualTo(3);
        assertThat(num(row, "opted_in")).isZero();              // 3 seeded - 3 opted out
        assertThat(pct(row, "pct_opted_out")).isEqualTo(100.0); // 3 of 3 = 100.0
    }

    @Test
    @DisplayName("the rate keeps one decimal place")
    void rateIsRoundedToOneDecimalPlace() {
        seed.user(signupAt);
        seed.user(signupAt);
        long quiet = seed.user(signupAt);
        seed.optOut(quiet);

        Map<String, Object> row = row(cohortWeek);

        assertThat(num(row, "users")).isEqualTo(3);
        assertThat(num(row, "opted_out")).isEqualTo(1);
        assertThat(pct(row, "pct_opted_out")).isEqualTo(33.3); // 1 of 3 = 33.333..., rounded to 33.3
    }

    @Test
    @DisplayName("a user with no signup date is in no cohort at all")
    void nullSignupDateIsInNoCohort() {
        seed.user(signupAt);
        seed.user(signupAt);
        seed.user(signupAt);

        // A fourth user, opted out, whose signup date is then removed. If the view counted them,
        // both the denominator and the opt-out numerator below would move.
        long dateless = seed.user(signupAt);
        seed.optOut(dateless);
        jdbc.update("UPDATE users SET create_date = NULL WHERE id = ?", dateless);

        try {
            Map<String, Object> row = row(cohortWeek);

            assertThat(num(row, "users")).isEqualTo(3);        // the dateless user is not here
            assertThat(num(row, "opted_out")).isZero();
            assertThat(num(row, "opted_in")).isEqualTo(3);
            assertThat(pct(row, "pct_opted_out")).isZero();     // 0 of 3 = 0.0

            // Nor do they land in a cohort of their own: the view drops them entirely.
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM v_consent WHERE cohort_week IS NULL", Long.class))
                    .isZero();
        } finally {
            // cleanCohort() finds users by create_date, so it can never reclaim this row. Remove it
            // here or it stays in the users table forever and drifts every future total.
            jdbc.update("DELETE FROM users WHERE id = ?", dateless);
        }
    }

    @Test
    @DisplayName("the following week is its own cohort and leaves this week's numbers alone")
    void anotherWeekIsASeparateCohort() {
        seed.user(signupAt);
        seed.user(signupAt);
        seed.user(signupAt);
        long quiet = seed.user(signupAt);
        seed.optOut(quiet);

        // One opted-out user seven days later: a different Monday, so a different row.
        long nextWeek = seed.user(nextWeekSignupAt);
        seed.optOut(nextWeek);

        Map<String, Object> mine = row(cohortWeek);

        assertThat(num(mine, "users")).isEqualTo(4);            // still only my four
        assertThat(num(mine, "opted_out")).isEqualTo(1);
        assertThat(num(mine, "opted_in")).isEqualTo(3);
        assertThat(pct(mine, "pct_opted_out")).isEqualTo(25.0); // 1 of 4 = 25.0, not 2 of 5 = 40.0

        Map<String, Object> neighbour = row(nextCohortWeek);

        assertThat(neighbour.get("cohort_week").toString()).isEqualTo("2024-08-19");
        assertThat(num(neighbour, "users")).isEqualTo(1);
        assertThat(num(neighbour, "opted_out")).isEqualTo(1);
        assertThat(num(neighbour, "opted_in")).isZero();
        assertThat(pct(neighbour, "pct_opted_out")).isEqualTo(100.0); // 1 of 1 = 100.0
    }
}
