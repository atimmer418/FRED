package com.investingapp.backend.ops;

import com.investingapp.backend.metrics.MetricsViewSeed;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gauge SQL against the migrated schema (V1 to V11 under ddl-auto=validate).
 *
 * Two isolation tricks, because every gauge aggregates a whole table. Window tests bind a clock
 * in 2020, years before any real row, and bound every window on both sides, so their counts are
 * exact. Totals that cannot be windowed (billable Items, holds, removals) are measured as a delta
 * between two snapshots. Users belong to the cohort week of Monday 2019-11-04, which no other test
 * uses, and are removed before and after every test.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class OpsGaugeServiceDatabaseTest {

    /** The bound Eastern clock: a query that read the SQL clock instead would be off by years. */
    private static final LocalDateTime NOW_ET = LocalDateTime.of(2020, 1, 1, 12, 0);
    /** Wednesday 2019-11-06, in the cohort week of Monday 2019-11-04. */
    private static final LocalDateTime SIGNUP = LocalDateTime.of(2019, 11, 6, 9, 30);
    private static final String TEST_ALERT_TYPE = "test.ops_gauge";

    @Autowired
    private OpsGaugeService gauges;
    @Autowired
    private PlaidWebhookEventRepository webhookEvents;
    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        seed.cleanCohort(MetricsViewSeed.mondayOf(SIGNUP.toLocalDate()));
        jdbc.update("DELETE FROM plaid_webhook_events WHERE dedupe_key LIKE 'opsgauge%'");
        jdbc.update("DELETE FROM plaid_item_removals WHERE item_id LIKE 'item-opsgauge-%'");
        jdbc.update("DELETE FROM ops_alerts WHERE alert_day < ? AND (alert_type LIKE 'test.ops_gauge%' "
                + "OR alert_type IN (?, ?))", Date.valueOf("2020-01-10"), OpsAlerts.HEARTBEAT, OpsAlerts.RECONCILE_RAN);
    }

    private static Timestamp ts(LocalDateTime value) {
        return Timestamp.valueOf(value);
    }

    /** Written through Hibernate on purpose: the gauge must read what the inbox service writes. */
    private void webhook(LocalDateTime receivedAt, PlaidWebhookEventStatus status) {
        PlaidWebhookEvent event = new PlaidWebhookEvent();
        event.setDedupeKey("opsgauge" + UUID.randomUUID().toString().replace("-", ""));
        event.setWebhookType("TRANSACTIONS");
        event.setWebhookCode("SYNC_UPDATES_AVAILABLE");
        event.setEnvironment("sandbox");
        event.setPayloadJson("{}");
        event.setStatus(status);
        event.setJwtIat(1577880000L);
        event.setReceivedAt(receivedAt);
        webhookEvents.save(event);
    }

    private long userWithSchedule(String holdReason) {
        long userId = seed.user(SIGNUP);
        seed.scheduleWithStatus(userId, SIGNUP, "ACTIVE");
        if (holdReason != null) {
            jdbc.update("UPDATE investment_schedules SET hold_reason = ? WHERE user_id = ?", holdReason, userId);
        }
        return userId;
    }

    private void searchingSince(long userId, LocalDateTime startedAt) {
        jdbc.update("UPDATE investment_schedules SET detection_state = ?, detection_started_at = ? WHERE user_id = ?",
                DetectionState.SEARCHING.name(), ts(startedAt), userId);
    }

    private long stream(long userId) {
        jdbc.update("INSERT INTO paycheck_streams (user_id, plaid_account_id, payer_token, payer_display_name, "
                + "state, role, created_at) VALUES (?, 'acct-opsgauge', 'opsgauge', 'Ops Gauge Payer', "
                + "'PROVISIONAL', 'PRIMARY', ?)", userId, ts(SIGNUP));
        return jdbc.queryForObject("SELECT id FROM paycheck_streams WHERE user_id = ? AND payer_token = 'opsgauge'",
                Long.class, userId);
    }

    private void shadowCycle(long userId, long streamId, String periodKey, LocalDateTime confirmedAt) {
        LocalDate day = confirmedAt.toLocalDate();
        jdbc.update("INSERT INTO paycheck_cycles (user_id, stream_id, period_key, state, expected_date, "
                        + "window_start, window_end, skip_reason, confirmed_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, streamId, periodKey, PaycheckCycleState.SKIPPED.name(), Date.valueOf(day),
                Date.valueOf(day.minusDays(2)), Date.valueOf(day.plusDays(2)),
                PaycheckSkipReason.SHADOW_CONFIRMED.name(), ts(confirmedAt), ts(confirmedAt));
    }

    private void alert(String type, LocalDateTime raisedAt) {
        jdbc.update("INSERT INTO ops_alerts (alert_type, subject_id, alert_day, message, occurrences, "
                        + "first_raised_at, last_raised_at, delivery_status) VALUES (?, ?, ?, 'test', 1, ?, ?, 'LOGGED')",
                type, "opsgauge-" + UUID.randomUUID().toString().substring(0, 8),
                Date.valueOf(raisedAt.toLocalDate()), ts(raisedAt), ts(raisedAt));
    }

    private void item(long userId, String status, boolean synced) {
        jdbc.update("UPDATE users SET plaid_access_token = 'ENC:opsgauge', plaid_item_status = ? WHERE id = ?",
                status, userId);
        if (synced) {
            jdbc.update("UPDATE users SET plaid_last_sync_at = ? WHERE id = ?", ts(SIGNUP), userId);
        }
    }

    private static long delta(Map<String, Long> after, Map<String, Long> before, String key) {
        return after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
    }

    @Test
    @DisplayName("every gauge query runs against the migrated schema on the real clock")
    void everyGaugeQueryRunsAgainstTheMigratedSchema() {
        OpsGaugeSnapshot snapshot = gauges.snapshot(EasternTime.now());

        assertThat(snapshot.toContext().keySet()).containsExactlyElementsOf(OpsGaugeSnapshot.GAUGE_KEYS);
        assertThat(snapshot.inboxOldestAgeSec()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("a row received 10 minutes before the bound Eastern clock is 600 seconds old")
    void agesUseTheBoundEasternClock() {
        webhook(NOW_ET.minusMinutes(10), PlaidWebhookEventStatus.RECEIVED);
        webhook(NOW_ET.minusMinutes(5), PlaidWebhookEventStatus.PROCESSING);

        assertThat(gauges.snapshot(NOW_ET).inboxOldestAgeSec()).isEqualTo(600);
    }

    @Test
    @DisplayName("the 24-hour and 7-day windows hang off the bound clock, not the SQL one")
    void windowsAreBoundToTheEasternClock() {
        webhook(NOW_ET.minusMinutes(10), PlaidWebhookEventStatus.PROCESSED);
        webhook(NOW_ET.minusHours(25), PlaidWebhookEventStatus.PROCESSED);
        long stale = userWithSchedule(null);
        searchingSince(stale, NOW_ET.minusHours(25));
        long fresh = userWithSchedule(null);
        searchingSince(fresh, NOW_ET.minusHours(23));
        long notYetEvaluated = userWithSchedule(null); // stamped by INIT, mirror still null (R1)
        jdbc.update("UPDATE investment_schedules SET detection_started_at = ? WHERE user_id = ?",
                ts(NOW_ET.minusHours(25)), notYetEvaluated);
        long rejected = userWithSchedule(null); // R8: never evaluated again, so never alerted on
        searchingSince(rejected, NOW_ET.minusHours(25));
        seed.accountStatus(rejected, "REJECTED");
        long streamId = stream(stale);
        shadowCycle(stale, streamId, "P-IN", NOW_ET.minusDays(3));
        shadowCycle(stale, streamId, "P-OUT", NOW_ET.minusDays(8));
        alert(TEST_ALERT_TYPE, NOW_ET.minusHours(4));
        alert(TEST_ALERT_TYPE + ".old", NOW_ET.minusHours(30));
        alert(OpsAlerts.HEARTBEAT, NOW_ET.minusHours(3)); // the digest itself is not an alert

        OpsGaugeSnapshot snapshot = gauges.snapshot(NOW_ET);

        assertThat(snapshot.webhooksReceived24h()).isEqualTo(1);
        assertThat(snapshot.searchingOver24h()).isEqualTo(2);
        assertThat(snapshot.cyclesShadowConfirmed7d()).isEqualTo(1);
        assertThat(snapshot.opsAlerts24h()).isEqualTo(1);
    }

    @Test
    @DisplayName("reconcile-ran reads yesterday's Eastern day and nothing else")
    void reconcileRanReadsYesterdaysRow() {
        alert(OpsAlerts.RECONCILE_RAN, LocalDateTime.of(2019, 12, 31, 22, 30));

        assertThat(gauges.snapshot(LocalDateTime.of(2020, 1, 1, 9, 7)).reconcileRanYesterday()).isTrue();
        assertThat(gauges.snapshot(LocalDateTime.of(2020, 1, 3, 9, 7)).reconcileRanYesterday()).isFalse();
    }

    @Test
    @DisplayName("billable means a token, a completed sync, and an Item that is not REMOVED")
    void billableCountsSyncedLiveItemsOnly() {
        OpsGaugeSnapshot before = gauges.snapshot(EasternTime.now());
        item(seed.user(SIGNUP), "OK", true);
        item(seed.user(SIGNUP), "REMOVED", true);
        item(seed.user(SIGNUP), "OK", false);
        seed.user(SIGNUP); // no Item at all

        OpsGaugeSnapshot after = gauges.snapshot(EasternTime.now());

        assertThat(after.plaidItemsBillable() - before.plaidItemsBillable()).isEqualTo(1);
        assertThat(delta(after.plaidItemsByStatus(), before.plaidItemsByStatus(), "OK")).isEqualTo(2);
        assertThat(delta(after.plaidItemsByStatus(), before.plaidItemsByStatus(), "REMOVED")).isEqualTo(1);
    }

    @Test
    @DisplayName("holds count every reason; the review alert ignores rejected applicants")
    void manualReviewOpenIgnoresRejectedApplicants() {
        OpsGaugeSnapshot before = gauges.snapshot(EasternTime.now());
        userWithSchedule("MANUAL_REVIEW");
        long rejected = userWithSchedule("MANUAL_REVIEW");
        seed.accountStatus(rejected, "REJECTED");
        userWithSchedule("BANK_CHANGE");

        OpsGaugeSnapshot after = gauges.snapshot(EasternTime.now());

        assertThat(delta(after.holdsByReason(), before.holdsByReason(), "MANUAL_REVIEW")).isEqualTo(2);
        assertThat(delta(after.holdsByReason(), before.holdsByReason(), "BANK_CHANGE")).isEqualTo(1);
        assertThat(after.manualReviewOpen() - before.manualReviewOpen()).isEqualTo(1);
    }

    @Test
    @DisplayName("a pending removal is one whose removed_at is still empty")
    void removalsPendingCountsUnremovedRowsOnly() {
        OpsGaugeSnapshot before = gauges.snapshot(EasternTime.now());
        jdbc.update("INSERT INTO plaid_item_removals (item_id, access_token_enc, requested_at, attempts) "
                + "VALUES ('item-opsgauge-pending', 'ENC:opsgauge', ?, 0)", ts(SIGNUP));
        jdbc.update("INSERT INTO plaid_item_removals (item_id, access_token_enc, requested_at, removed_at, attempts) "
                + "VALUES ('item-opsgauge-done', 'ENC:opsgauge', ?, ?, 1)", ts(SIGNUP), ts(SIGNUP.plusMinutes(15)));

        OpsGaugeSnapshot after = gauges.snapshot(EasternTime.now());

        assertThat(after.removalsPending() - before.removalsPending()).isEqualTo(1);
    }
}
