package com.investingapp.backend.metrics;

import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v_outbox_reconciliation against a dataset whose answers are known by construction.
 *
 * The view groups by the week of occurred_at AND by event_name, so one week with two event names is
 * two rows. Every assertion below reads a single (week, event_name) row and the numbers in it were
 * worked out by hand from the rows seeded immediately above.
 *
 * ISOLATION. This test owns the week of Monday 2024-09-09 and signs its user up on Wednesday
 * 2024-09-11. cleanCohort(cohortWeek) in @BeforeEach deletes that user and, with it, every outbox
 * row pointing at it, which is why every event seeded here is attached to that one user: it is the
 * only handle the cleanup has. The one event deliberately placed in the FOLLOWING week is attached
 * to the same user for exactly that reason, so it is removed on the next run even though its
 * occurred_at is outside this test's week.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class OutboxReconciliationViewTest {

    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;
    private LocalDate cohortWeek;
    private LocalDateTime occurredAt;

    /** The user every seeded event hangs off, so cleanCohort can find the events again. */
    private long userId;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        // Wednesday of a week this test owns.
        occurredAt = LocalDateTime.of(2024, 9, 11, 10, 0);
        cohortWeek = MetricsViewSeed.mondayOf(occurredAt.toLocalDate());
        assertThat(cohortWeek).isEqualTo(LocalDate.of(2024, 9, 9));

        seed.cleanCohort(cohortWeek);
        userId = seed.user(occurredAt);
    }

    /** The one row for this week and event name. Throws if there is none, or more than one. */
    private Map<String, Object> row(String eventName) {
        return jdbc.queryForMap(
                "SELECT * FROM v_outbox_reconciliation WHERE week_start = ? AND event_name = ?",
                cohortWeek, eventName);
    }

    private long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private double pct(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    /** An event in this test's week, on this test's user. */
    private void event(String eventName, String status) {
        seed.outboxEvent(userId, eventName, occurredAt, "{}", status);
    }

    @Test
    @DisplayName("splits one week's rows by status, and rates it two ways")
    void countsByStatusAndPctSent() {
        // kyc_submitted, five rows: 3 SENT, 1 SUPPRESSED, 1 PENDING, 0 PARKED.
        event(ServerAnalyticsEvent.KYC_SUBMITTED, "SENT");
        event(ServerAnalyticsEvent.KYC_SUBMITTED, "SENT");
        event(ServerAnalyticsEvent.KYC_SUBMITTED, "SENT");
        event(ServerAnalyticsEvent.KYC_SUBMITTED, "SUPPRESSED");
        event(ServerAnalyticsEvent.KYC_SUBMITTED, "PENDING");

        Map<String, Object> row = row(ServerAnalyticsEvent.KYC_SUBMITTED);

        assertThat(num(row, "recorded_count")).isEqualTo(5);
        assertThat(num(row, "sent_count")).isEqualTo(3);
        assertThat(num(row, "suppressed_count")).isEqualTo(1);
        assertThat(num(row, "parked_count")).isZero();
        assertThat(num(row, "pending_count")).isEqualTo(1);
        assertThat(num(row, "other_count")).isZero();
        // The two rates diverge exactly here, which is why both exist. Of the 4 rows we were ever
        // going to send, 3 landed: 75.0, and that is the health signal. Of all 5 recorded, 3
        // landed: 60.0, which counts the correctly-suppressed row as if it were a loss.
        assertThat(pct(row, "pct_of_sendable")).isEqualTo(75.0);
        assertThat(pct(row, "pct_of_recorded")).isEqualTo(60.0);
        // Nothing has been retried, and the seed writes attempts = 0.
        assertThat(num(row, "max_attempts")).isZero();
    }

    @Test
    @DisplayName("two event names in one week are two rows with independent counts")
    void twoEventNamesAreTwoRows() {
        // kyc_submitted: 2 SENT, 1 PARKED.
        event(ServerAnalyticsEvent.KYC_SUBMITTED, "SENT");
        event(ServerAnalyticsEvent.KYC_SUBMITTED, "SENT");
        event(ServerAnalyticsEvent.KYC_SUBMITTED, "PARKED");
        // plaid_linked: 1 SENT, 3 PENDING.
        event(ServerAnalyticsEvent.PLAID_LINKED, "SENT");
        event(ServerAnalyticsEvent.PLAID_LINKED, "PENDING");
        event(ServerAnalyticsEvent.PLAID_LINKED, "PENDING");
        event(ServerAnalyticsEvent.PLAID_LINKED, "PENDING");

        Map<String, Object> kyc = row(ServerAnalyticsEvent.KYC_SUBMITTED);
        assertThat(num(kyc, "recorded_count")).isEqualTo(3);
        assertThat(num(kyc, "sent_count")).isEqualTo(2);
        assertThat(num(kyc, "parked_count")).isEqualTo(1);
        assertThat(num(kyc, "pending_count")).isZero();
        // No suppressed rows here, so the two rates agree.
        assertThat(pct(kyc, "pct_of_sendable")).isEqualTo(66.7);   // 2 of 3
        assertThat(pct(kyc, "pct_of_recorded")).isEqualTo(66.7);

        Map<String, Object> plaid = row(ServerAnalyticsEvent.PLAID_LINKED);
        assertThat(num(plaid, "recorded_count")).isEqualTo(4);
        assertThat(num(plaid, "sent_count")).isEqualTo(1);
        assertThat(num(plaid, "parked_count")).isZero();
        assertThat(num(plaid, "pending_count")).isEqualTo(3);
        assertThat(pct(plaid, "pct_of_sendable")).isEqualTo(25.0);  // 1 of 4
        assertThat(pct(plaid, "pct_of_recorded")).isEqualTo(25.0);

        // And the week really is two rows, not one merged row and not three.
        List<Map<String, Object>> weekRows = jdbc.queryForList(
                "SELECT event_name, recorded_count FROM v_outbox_reconciliation "
                        + "WHERE week_start = ? ORDER BY event_name", cohortWeek);
        assertThat(weekRows).hasSize(2);
        assertThat(weekRows.get(0).get("event_name")).isEqualTo(ServerAnalyticsEvent.KYC_SUBMITTED);
        assertThat(weekRows.get(1).get("event_name")).isEqualTo(ServerAnalyticsEvent.PLAID_LINKED);
    }

    @Test
    @DisplayName("a week with events but none sent reports 0.0, never NULL")
    void nothingSentIsZeroNotNull() {
        // investment_schedule_created: 2 PENDING, 1 PARKED, nothing sent. This is what a stopped
        // publisher looks like.
        event(ServerAnalyticsEvent.INVESTMENT_SCHEDULE_CREATED, "PENDING");
        event(ServerAnalyticsEvent.INVESTMENT_SCHEDULE_CREATED, "PENDING");
        event(ServerAnalyticsEvent.INVESTMENT_SCHEDULE_CREATED, "PARKED");

        Map<String, Object> row = row(ServerAnalyticsEvent.INVESTMENT_SCHEDULE_CREATED);

        assertThat(num(row, "recorded_count")).isEqualTo(3);
        assertThat(num(row, "sent_count")).isZero();
        assertThat(num(row, "pending_count")).isEqualTo(2);
        assertThat(num(row, "parked_count")).isEqualTo(1);
        assertThat(row.get("pct_of_sendable")).isNotNull();
        assertThat(pct(row, "pct_of_sendable")).isZero();   // 0 of 3
        assertThat(pct(row, "pct_of_recorded")).isZero();
    }

    @Test
    @DisplayName("max_attempts is the highest attempt count in the group")
    void maxAttemptsIsTheGroupMaximum() {
        // Three ach_transfer_completed rows, told apart by their properties blob so the attempt
        // counts can be set on exactly the row each belongs to. MetricsViewSeed.outboxEvent always
        // writes attempts = 0 and does not hand back the row it inserted, so the attempt counts go
        // on afterwards with a direct update.
        seed.outboxEvent(userId, ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED, occurredAt,
                "{\"probe\":\"retried-hard\"}", "SENT");
        seed.outboxEvent(userId, ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED, occurredAt,
                "{\"probe\":\"retried-once\"}", "PENDING");
        seed.outboxEvent(userId, ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED, occurredAt,
                "{\"probe\":\"first-time\"}", "SENT");

        setAttempts("{\"probe\":\"retried-hard\"}", 4);
        setAttempts("{\"probe\":\"retried-once\"}", 2);
        // "first-time" keeps attempts = 0.

        Map<String, Object> row = row(ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED);

        assertThat(num(row, "recorded_count")).isEqualTo(3);
        assertThat(num(row, "sent_count")).isEqualTo(2);
        assertThat(num(row, "pending_count")).isEqualTo(1);
        assertThat(pct(row, "pct_of_sendable")).isEqualTo(66.7);   // 2 of 3
        assertThat(num(row, "max_attempts")).isEqualTo(4);  // max(4, 2, 0)
    }

    @Test
    @DisplayName("an event in the next week is its own row and leaves this week's counts alone")
    void anotherWeekIsAnotherRow() {
        // subscription_billed, twice in this week: 1 SENT, 1 PENDING.
        event(ServerAnalyticsEvent.SUBSCRIPTION_BILLED, "SENT");
        event(ServerAnalyticsEvent.SUBSCRIPTION_BILLED, "PENDING");
        // And once on Wednesday of the NEXT week, same event name, SENT. Still on this test's user
        // so cleanCohort removes it.
        LocalDate nextWeek = cohortWeek.plusWeeks(1);
        seed.outboxEvent(userId, ServerAnalyticsEvent.SUBSCRIPTION_BILLED,
                occurredAt.plusWeeks(1), "{}", "SENT");

        Map<String, Object> mine = row(ServerAnalyticsEvent.SUBSCRIPTION_BILLED);
        assertThat(num(mine, "recorded_count")).isEqualTo(2);   // 2, not the 3 rows in the table
        assertThat(num(mine, "sent_count")).isEqualTo(1);
        assertThat(num(mine, "pending_count")).isEqualTo(1);
        assertThat(pct(mine, "pct_of_sendable")).isEqualTo(50.0);      // 1 of 2

        Map<String, Object> next = jdbc.queryForMap(
                "SELECT * FROM v_outbox_reconciliation WHERE week_start = ? AND event_name = ?",
                nextWeek, ServerAnalyticsEvent.SUBSCRIPTION_BILLED);
        assertThat(num(next, "recorded_count")).isEqualTo(1);
        assertThat(num(next, "sent_count")).isEqualTo(1);
        assertThat(pct(next, "pct_of_sendable")).isEqualTo(100.0);     // 1 of 1
    }

    /** Sets attempts on the one row of this test's user carrying that properties blob. */
    private void setAttempts(String propertiesJson, int attempts) {
        int updated = jdbc.update(
                "UPDATE analytics_outbox SET attempts = ? WHERE user_id = ? AND properties = ?",
                attempts, userId, propertiesJson);
        assertThat(updated).isEqualTo(1);
    }
}
