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
 * v_kyc_outcomes against a dataset whose answers are worked out by hand.
 *
 * This view reads analytics_outbox rather than users, because a rejected KYC hard-deletes the user
 * row. That makes the isolation story different from the other metrics tests: the rows under test
 * are not reachable from `users`, so cleanCohort cannot find them once a user is gone. Two things
 * keep this class repeatable:
 *
 *  - every seeded user signs up in the week this test owns (Monday 2024-07-08), so cleanCohort
 *    removes the users and their still-attached outbox rows;
 *  - every seeded event occurs in July or August 2024, months this test owns, and @BeforeEach
 *    clears the two kyc event names across that window with a direct delete. That second step is
 *    what makes the deleted-user test below repeatable, since it deliberately orphans outbox rows
 *    that no longer point at any user.
 *
 * Nothing else in the schema puts kyc events in those two months, so the month rows these
 * assertions read describe exactly the events seeded in each test and nothing more.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class KycOutcomesViewTest {

    /** A Wednesday in the signup week this test owns. */
    private static final LocalDateTime SIGNUP = LocalDateTime.of(2024, 7, 10, 9, 0);

    /** The view groups by month, and month_start is a 'YYYY-MM-01' string. */
    private static final String JULY = "2024-07-01";
    private static final String AUGUST = "2024-08-01";

    private static final LocalDateTime WINDOW_FROM = LocalDateTime.of(2024, 7, 1, 0, 0);
    private static final LocalDateTime WINDOW_TO = LocalDateTime.of(2024, 9, 1, 0, 0);

    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;
    private LocalDate cohortWeek;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        cohortWeek = MetricsViewSeed.mondayOf(SIGNUP.toLocalDate());
        seed.cleanCohort(cohortWeek);
        // Outbox rows whose user was deleted survive cleanCohort by design: it finds them through
        // `users`, and there is nothing left to find. They are this test's rows, in this test's
        // months, so they go by date instead. Only the two event names the view reads are touched.
        jdbc.update("DELETE FROM analytics_outbox "
                + "WHERE event_name IN ('kyc_submitted', 'kyc_decided') "
                + "  AND occurred_at >= ? AND occurred_at < ?", WINDOW_FROM, WINDOW_TO);
    }

    private Map<String, Object> row(String monthStart) {
        return jdbc.queryForMap("SELECT * FROM v_kyc_outcomes WHERE month_start = ?", monthStart);
    }

    private long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    /** The median is an average, and it is NULL for a month with nothing to pair. */
    private Double dbl(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private long user() {
        return seed.user(SIGNUP);
    }

    private void submitted(long userId, LocalDateTime at) {
        submitted(userId, at, "SENT");
    }

    private void submitted(long userId, LocalDateTime at, String status) {
        seed.outboxEvent(userId, "kyc_submitted", at, "{\"attempt\":\"1\"}", status);
    }

    private void decided(long userId, LocalDateTime at, String outcome, String band) {
        decided(userId, at, outcome, band, "SENT");
    }

    private void decided(long userId, LocalDateTime at, String outcome, String band, String status) {
        seed.outboxEvent(userId, "kyc_decided", at,
                "{\"outcome\":\"" + outcome + "\",\"days_to_decision_band\":\"" + band + "\"}",
                status);
    }

    @Test
    @DisplayName("a submission and its decision are counted in their own months, not balanced within one")
    void submissionAndDecisionAreCountedInTheirOwnMonths() {
        long applicant = user();
        submitted(applicant, LocalDateTime.of(2024, 7, 25, 9, 0));
        // Band says 1_3 and is wrong on purpose: the view must not read it.
        decided(applicant, LocalDateTime.of(2024, 8, 2, 9, 0), "approved", "1_3");

        Map<String, Object> july = row(JULY);
        assertThat(num(july, "submissions")).isEqualTo(1);
        assertThat(num(july, "decisions")).isZero();          // decided in August, not here
        assertThat(num(july, "approved")).isZero();
        assertThat(num(july, "rejected")).isZero();
        assertThat(num(july, "needs_more_info")).isZero();
        assertThat(dbl(july, "median_days_to_decision")).isNull();  // no decision landed in July

        Map<String, Object> august = row(AUGUST);
        assertThat(num(august, "submissions")).isZero();       // submitted in July, not here
        assertThat(num(august, "decisions")).isEqualTo(1);
        assertThat(num(august, "approved")).isEqualTo(1);
        // 2024-07-25 09:00 -> 2024-08-02 09:00 = 8 days. The event's band claims 1_3.
        assertThat(dbl(august, "median_days_to_decision")).isEqualTo(8.0);
    }

    @Test
    @DisplayName("outcomes are counted from the properties JSON, and an even count averages the two middles")
    void outcomesComeFromThePropertiesJson() {
        LocalDateTime submittedAt = LocalDateTime.of(2024, 8, 5, 9, 0);

        long a = user();
        submitted(a, submittedAt);
        decided(a, LocalDateTime.of(2024, 8, 7, 9, 0), "approved", "1_3");        // lag 2

        long b = user();
        submitted(b, submittedAt);
        decided(b, LocalDateTime.of(2024, 8, 9, 9, 0), "approved", "4_7");        // lag 4

        long c = user();
        submitted(c, submittedAt);
        decided(c, LocalDateTime.of(2024, 8, 11, 9, 0), "rejected", "4_7");       // lag 6

        long d = user();
        submitted(d, submittedAt);
        decided(d, LocalDateTime.of(2024, 8, 15, 9, 0), "needs_more_info", "8_14"); // lag 10

        Map<String, Object> august = row(AUGUST);
        assertThat(num(august, "submissions")).isEqualTo(4);
        assertThat(num(august, "decisions")).isEqualTo(4);
        assertThat(num(august, "approved")).isEqualTo(2);
        assertThat(num(august, "rejected")).isEqualTo(1);
        assertThat(num(august, "needs_more_info")).isEqualTo(1);
        // Lags 2, 4, 6, 10. Four values, so the mean of the middle two: (4 + 6) / 2 = 5.0
        assertThat(dbl(august, "median_days_to_decision")).isEqualTo(5.0);
    }

    @Test
    @DisplayName("median days to decision comes from the outbox timestamps, not the banded property")
    void medianDaysToDecisionComesFromTheTimestamps() {
        LocalDateTime submittedAt = LocalDateTime.of(2024, 8, 4, 9, 0);

        // Every band below says 15_30. Every real lag is smaller. If the view read the band, the
        // median would be unreadable; it reads the timestamps, so it is 4.
        long fast = user();
        submitted(fast, submittedAt);
        decided(fast, LocalDateTime.of(2024, 8, 5, 9, 0), "approved", "15_30");   // lag 1

        long middle = user();
        submitted(middle, submittedAt);
        decided(middle, LocalDateTime.of(2024, 8, 8, 9, 0), "approved", "15_30"); // lag 4

        long slow = user();
        submitted(slow, submittedAt);
        decided(slow, LocalDateTime.of(2024, 8, 13, 9, 0), "rejected", "15_30");  // lag 9

        Map<String, Object> august = row(AUGUST);
        assertThat(num(august, "submissions")).isEqualTo(3);
        assertThat(num(august, "decisions")).isEqualTo(3);
        assertThat(num(august, "approved")).isEqualTo(2);
        assertThat(num(august, "rejected")).isEqualTo(1);
        // Lags 1, 4, 9. Three values, so the middle one: 4.0
        assertThat(dbl(august, "median_days_to_decision")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("a decision for a user who was since deleted still counts, which is why the view reads the outbox")
    void aDecisionForADeletedUserStillCounts() {
        long keeper = user();
        submitted(keeper, LocalDateTime.of(2024, 8, 1, 9, 0));
        decided(keeper, LocalDateTime.of(2024, 8, 3, 9, 0), "approved", "1_3");   // lag 2

        long rejectedUser = user();
        submitted(rejectedUser, LocalDateTime.of(2024, 8, 1, 9, 0));
        decided(rejectedUser, LocalDateTime.of(2024, 8, 6, 9, 0), "rejected", "4_7"); // lag 5

        Map<String, Object> before = row(AUGUST);
        assertThat(num(before, "submissions")).isEqualTo(2);
        assertThat(num(before, "decisions")).isEqualTo(2);
        assertThat(num(before, "approved")).isEqualTo(1);
        assertThat(num(before, "rejected")).isEqualTo(1);
        // Lags 2 and 5: (2 + 5) / 2 = 3.5
        assertThat(dbl(before, "median_days_to_decision")).isEqualTo(3.5);

        // What a rejection does in production: the user row goes away.
        jdbc.update("DELETE FROM users WHERE id = ?", rejectedUser);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?",
                Long.class, rejectedUser)).isZero();

        Map<String, Object> after = row(AUGUST);
        assertThat(num(after, "submissions")).isEqualTo(2);
        assertThat(num(after, "decisions")).isEqualTo(2);
        assertThat(num(after, "approved")).isEqualTo(1);
        assertThat(num(after, "rejected")).isEqualTo(1);   // the rejection is still on the ledger
        assertThat(dbl(after, "median_days_to_decision")).isEqualTo(3.5);  // and still in the median
    }

    @Test
    @DisplayName("SUPPRESSED and PENDING rows count: a marketing preference must not hole the compliance funnel")
    void suppressedAndPendingRowsAreCounted() {
        long optedOut = user();
        seed.optOut(optedOut);
        submitted(optedOut, LocalDateTime.of(2024, 8, 2, 9, 0), "SUPPRESSED");
        // Never sent to PostHog, because this user opted out. Still a decided KYC.
        decided(optedOut, LocalDateTime.of(2024, 8, 5, 9, 0), "approved", "1_3", "SUPPRESSED");

        long stillQueued = user();
        submitted(stillQueued, LocalDateTime.of(2024, 8, 2, 9, 0), "PENDING");
        decided(stillQueued, LocalDateTime.of(2024, 8, 7, 9, 0), "rejected", "4_7", "PENDING");

        // If either status were filtered out there would be no August row at all.
        Map<String, Object> august = row(AUGUST);
        assertThat(num(august, "submissions")).isEqualTo(2);
        assertThat(num(august, "decisions")).isEqualTo(2);
        assertThat(num(august, "approved")).isEqualTo(1);     // the SUPPRESSED one
        assertThat(num(august, "rejected")).isEqualTo(1);     // the PENDING one
        // Lags 3 and 5: (3 + 5) / 2 = 4.0
        assertThat(dbl(august, "median_days_to_decision")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("a decision with no prior submission is counted but left out of the median")
    void aDecisionWithNoSubmissionIsCountedButNotInTheMedian() {
        // No kyc_submitted event at all: the lag is NULL, not zero.
        long orphanDecision = user();
        decided(orphanDecision, LocalDateTime.of(2024, 8, 10, 9, 0), "approved", "unknown");

        long quick = user();
        submitted(quick, LocalDateTime.of(2024, 8, 1, 9, 0));
        decided(quick, LocalDateTime.of(2024, 8, 4, 9, 0), "approved", "1_3");    // lag 3

        long slow = user();
        submitted(slow, LocalDateTime.of(2024, 8, 1, 9, 0));
        decided(slow, LocalDateTime.of(2024, 8, 8, 9, 0), "rejected", "4_7");     // lag 7

        Map<String, Object> august = row(AUGUST);
        assertThat(num(august, "submissions")).isEqualTo(2);
        assertThat(num(august, "decisions")).isEqualTo(3);    // the orphan is still a decision
        assertThat(num(august, "approved")).isEqualTo(2);
        assertThat(num(august, "rejected")).isEqualTo(1);
        // Only the two pairable lags, 3 and 7: (3 + 7) / 2 = 5.0. Counting the NULL as a zero
        // would give the median of 0, 3, 7 = 3.0, and counting it as a third value would shift
        // the middle. Neither happens.
        assertThat(dbl(august, "median_days_to_decision")).isEqualTo(5.0);
    }

    @Test
    @DisplayName("a submission after the decision does not pair with it")
    void aSubmissionAfterTheDecisionDoesNotPair() {
        // A resubmission that arrives after this decision. The decision cannot have been about it.
        long applicant = user();
        decided(applicant, LocalDateTime.of(2024, 8, 15, 9, 0), "needs_more_info", "unknown");
        submitted(applicant, LocalDateTime.of(2024, 8, 20, 9, 0));

        Map<String, Object> august = row(AUGUST);
        assertThat(num(august, "submissions")).isEqualTo(1);
        assertThat(num(august, "decisions")).isEqualTo(1);
        assertThat(num(august, "needs_more_info")).isEqualTo(1);
        // Nothing left to pair, so no median: NULL, not 0, because 0 days would be a lie.
        assertThat(dbl(august, "median_days_to_decision")).isNull();
    }

    @Test
    @DisplayName("a decision pairs with the most recent submission before it, not the first one")
    void pairsWithTheMostRecentPriorSubmission() {
        long applicant = user();
        submitted(applicant, LocalDateTime.of(2024, 7, 28, 9, 0));   // first attempt
        submitted(applicant, LocalDateTime.of(2024, 8, 5, 9, 0));    // resubmitted after more info
        decided(applicant, LocalDateTime.of(2024, 8, 9, 9, 0), "approved", "8_14");

        Map<String, Object> july = row(JULY);
        assertThat(num(july, "submissions")).isEqualTo(1);
        assertThat(num(july, "decisions")).isZero();

        Map<String, Object> august = row(AUGUST);
        assertThat(num(august, "submissions")).isEqualTo(1);
        assertThat(num(august, "decisions")).isEqualTo(1);
        assertThat(num(august, "approved")).isEqualTo(1);
        // 2024-08-05 -> 2024-08-09 = 4 days. From the first submission it would be 12.
        assertThat(dbl(august, "median_days_to_decision")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("a month with no kyc events produces no row at all")
    void aMonthWithNoEventsProducesNoRow() {
        // Nothing seeded. The months are derived from the events themselves, so an empty month is
        // absent rather than a row of zeros. Anything reading this view has to expect the gap.
        assertThat(jdbc.queryForList("SELECT * FROM v_kyc_outcomes WHERE month_start IN (?, ?)",
                JULY, AUGUST)).isEmpty();
    }
}
