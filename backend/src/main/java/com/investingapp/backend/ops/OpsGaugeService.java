package com.investingapp.backend.ops;

import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads every heartbeat gauge with plain SQL (no Micrometer exists, design Decision 8) and no
 * repository methods.
 *
 * <p>Clock rule: no query reads the SQL clock. received_at, confirmed_at, detection_started_at and
 * last_raised_at hold Eastern wall time while the datasource session is UTC, so the caller binds
 * nowET and every cut-off is derived from it here. Temporal parameters are bound as
 * java.sql.Timestamp and java.sql.Date, the JDBC types Hibernate 6 binds for LocalDateTime and
 * LocalDate, so the driver treats the gauge's parameters exactly as it treated the stored values.
 * Windows are bounded on both sides so a bound clock in the past counts only rows inside it.
 *
 * <p>One read-only transaction gives every gauge the same MySQL consistent-read view.
 */
@Service
public class OpsGaugeService {

    static final String ACCOUNT_REJECTED = "REJECTED";

    static final String SQL_USERS_BY_STATE =
            "SELECT detection_state, COUNT(*) FROM investment_schedules "
                    + "WHERE detection_state IS NOT NULL GROUP BY detection_state";
    static final String SQL_ITEMS_BY_STATUS =
            "SELECT plaid_item_status, COUNT(*) FROM users "
                    + "WHERE plaid_access_token IS NOT NULL GROUP BY plaid_item_status";
    static final String SQL_ITEMS_BILLABLE =
            "SELECT COUNT(*) FROM users WHERE plaid_access_token IS NOT NULL "
                    + "AND plaid_last_sync_at IS NOT NULL AND plaid_item_status <> ?";
    static final String SQL_HOLDS_BY_REASON =
            "SELECT hold_reason, COUNT(*) FROM investment_schedules "
                    + "WHERE hold_reason IS NOT NULL GROUP BY hold_reason";
    static final String SQL_MANUAL_REVIEW_OPEN =
            "SELECT COUNT(*) FROM investment_schedules s JOIN users u ON u.id = s.user_id "
                    + "WHERE s.hold_reason = ? AND (u.account_status IS NULL OR u.account_status <> ?)";
    static final String SQL_INBOX_IN_STATUS =
            "SELECT COUNT(*) FROM plaid_webhook_events WHERE status IN (?, ?)";
    static final String SQL_INBOX_FAILED =
            "SELECT COUNT(*) FROM plaid_webhook_events WHERE status = ?";
    static final String SQL_INBOX_OLDEST =
            "SELECT MIN(received_at) FROM plaid_webhook_events WHERE status IN (?, ?)";
    static final String SQL_WEBHOOKS_WINDOW =
            "SELECT COUNT(*) FROM plaid_webhook_events WHERE received_at > ? AND received_at <= ?";
    static final String SQL_REMOVALS_PENDING =
            "SELECT COUNT(*) FROM plaid_item_removals WHERE removed_at IS NULL";
    static final String SQL_CYCLES_SHADOW_WINDOW =
            "SELECT COUNT(*) FROM paycheck_cycles WHERE state = ? AND skip_reason = ? "
                    + "AND confirmed_at > ? AND confirmed_at <= ?";
    static final String SQL_ALERTS_WINDOW =
            "SELECT COUNT(*) FROM ops_alerts WHERE last_raised_at > ? AND last_raised_at <= ? "
                    + "AND alert_type NOT IN (?, ?)";
    static final String SQL_SEARCHING_SINCE_BEFORE =
            "SELECT COUNT(*) FROM investment_schedules s JOIN users u ON u.id = s.user_id "
                    + "WHERE (s.detection_state = ? OR s.detection_state IS NULL) AND s.detection_started_at < ? "
                    + "AND (u.account_status IS NULL OR u.account_status <> ?)";
    static final String SQL_ALERT_ON_DAY =
            "SELECT COUNT(*) FROM ops_alerts WHERE alert_type = ? AND alert_day = ?";

    private final JdbcTemplate jdbc;

    public OpsGaugeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OpsGaugeSnapshot snapshot(LocalDateTime nowET) {
        Timestamp now = Timestamp.valueOf(nowET);
        Timestamp dayAgo = Timestamp.valueOf(nowET.minusHours(24));
        Timestamp weekAgo = Timestamp.valueOf(nowET.minusDays(7));
        Date yesterday = Date.valueOf(nowET.toLocalDate().minusDays(1));
        String received = PlaidWebhookEventStatus.RECEIVED.name();
        String processing = PlaidWebhookEventStatus.PROCESSING.name();

        Timestamp oldest = jdbc.queryForObject(SQL_INBOX_OLDEST, Timestamp.class, received, processing);
        long oldestAgeSec = oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest.toLocalDateTime(), nowET).getSeconds());

        return new OpsGaugeSnapshot(
                buckets(SQL_USERS_BY_STATE),
                buckets(SQL_ITEMS_BY_STATUS),
                count(SQL_ITEMS_BILLABLE, PlaidItemStatus.REMOVED.name()),
                buckets(SQL_HOLDS_BY_REASON),
                count(SQL_INBOX_IN_STATUS, received, processing),
                count(SQL_INBOX_FAILED, PlaidWebhookEventStatus.FAILED.name()),
                oldestAgeSec,
                count(SQL_WEBHOOKS_WINDOW, dayAgo, now),
                count(SQL_REMOVALS_PENDING),
                count(SQL_CYCLES_SHADOW_WINDOW, PaycheckCycleState.SKIPPED.name(),
                        PaycheckSkipReason.SHADOW_CONFIRMED.name(), weekAgo, now),
                count(SQL_ALERTS_WINDOW, dayAgo, now, OpsAlerts.HEARTBEAT, OpsAlerts.RECONCILE_RAN),
                count(SQL_SEARCHING_SINCE_BEFORE, DetectionState.SEARCHING.name(), dayAgo, ACCOUNT_REJECTED),
                count(SQL_MANUAL_REVIEW_OPEN, HoldReason.MANUAL_REVIEW.name(), ACCOUNT_REJECTED),
                count(SQL_ALERT_ON_DAY, OpsAlerts.RECONCILE_RAN, yesterday) > 0);
    }

    private Map<String, Long> buckets(String sql) {
        Map<String, Long> buckets = new TreeMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> buckets.put(rs.getString(1), rs.getLong(2)));
        return buckets;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
