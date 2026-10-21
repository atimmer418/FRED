package com.investingapp.backend.metrics;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Seeds rows for a metrics-view test.
 *
 * Every test owns its own COHORT WEEK, chosen far enough in the past that no other test and no
 * bootstrap data can land in it. The assertions then read the single row for that week, so the
 * tests are independent of each other and of whatever else is in the schema. That matters more
 * here than usual: these views aggregate the whole table, so a shared dataset would make every
 * expectation depend on every other test.
 */
public class MetricsViewSeed {

    private final JdbcTemplate jdbc;

    public MetricsViewSeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Removes everything belonging to the users who signed up in this week, so a test is
     * repeatable. The child tables are DISCOVERED from the schema rather than listed, because a
     * hard-coded list silently rots the next time someone adds a table that references users, and
     * the failure would look like a flaky test rather than a missing delete.
     */
    public void cleanCohort(LocalDate monday) {
        LocalDateTime from = monday.atStartOfDay();
        LocalDateTime to = monday.plusDays(7).atStartOfDay();

        List<Long> userIds = jdbc.queryForList(
                "SELECT id FROM users WHERE create_date >= ? AND create_date < ?",
                Long.class, from, to);

        // analytics_outbox has no foreign key on purpose (the ledger outlives the user), so it is
        // named explicitly; everything else is whatever currently points at users.
        jdbc.update("DELETE FROM analytics_outbox WHERE user_id IN "
                + "(SELECT id FROM users WHERE create_date >= ? AND create_date < ?)", from, to);

        if (!userIds.isEmpty()) {
            List<String> childTables = jdbc.queryForList(
                    "SELECT DISTINCT TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = 'users'",
                    String.class);
            String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            for (String table : childTables) {
                String column = jdbc.queryForObject(
                        "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                                + "AND REFERENCED_TABLE_NAME = 'users' LIMIT 1",
                        String.class, table);
                // Grandchildren first: delete anything pointing at the rows we are about to remove.
                deleteGrandchildren(table, column, ids);
                jdbc.update("DELETE FROM `" + table + "` WHERE `" + column + "` IN (" + ids + ")");
            }
            jdbc.update("DELETE FROM users WHERE id IN (" + ids + ")");
        }
    }

    /**
     * One level is enough for this schema: trades hang off executions, nothing hangs off trades.
     * The subquery is wrapped in a derived table because a table can reference itself
     * (ach_authorizations.superseded_by since V6), and MySQL refuses to delete from a table it is
     * also selecting from in the same statement (error 1093) unless the selection is materialised.
     */
    private void deleteGrandchildren(String parentTable, String parentColumn, String userIds) {
        List<String> tables = jdbc.queryForList(
                "SELECT DISTINCT TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = ?",
                String.class, parentTable);
        for (String table : tables) {
            String column = jdbc.queryForObject(
                    "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                            + "AND REFERENCED_TABLE_NAME = ? LIMIT 1",
                    String.class, table, parentTable);
            jdbc.update("DELETE FROM `" + table + "` WHERE `" + column + "` IN "
                    + "(SELECT id FROM (SELECT id FROM `" + parentTable + "` WHERE `" + parentColumn
                    + "` IN (" + userIds + ")) AS cohort_rows)");
        }
    }

    /** The Monday of the week containing the given date, which is what the views group by. */
    public static LocalDate mondayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    /** A user who signed up at the given moment. Returns the new id. */
    public long user(LocalDateTime signedUpAt) {
        String email = "metrics-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out) "
                + "VALUES (?, ?, ?, 0)", email, signedUpAt, UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void optOut(long userId) {
        jdbc.update("UPDATE users SET analytics_opt_out = 1 WHERE id = ?", userId);
    }

    public void plan(long userId) {
        jdbc.update("UPDATE users SET plan_id = 'guardrails' WHERE id = ?", userId);
    }

    public void passkey(long userId) {
        jdbc.update("INSERT INTO passkey_credentials "
                + "(user_id, external_id, public_key_cose, signature_count, created_date) "
                + "VALUES (?, ?, 'cose', 0, NOW())", userId, UUID.randomUUID().toString());
    }

    public void kycSubmitted(long userId, LocalDateTime at) {
        jdbc.update("UPDATE users SET kyc_submitted_at = ?, alpaca_account_id = 'ENC:acct' "
                + "WHERE id = ?", at, userId);
    }

    public void accountStatus(long userId, String status) {
        jdbc.update("UPDATE users SET account_status = ? WHERE id = ?", status, userId);
    }

    public void bankLinked(long userId) {
        jdbc.update("UPDATE users SET plaid_access_token = 'ENC:token' WHERE id = ?", userId);
    }

    public void schedule(long userId, LocalDateTime createdAt, boolean paused) {
        jdbc.update("INSERT INTO investment_schedules "
                + "(user_id, created_at, frequency, investment_amount, monthly_amount, "
                + " monthly_streak, next_investment_date, start_date, is_paused) "
                + "VALUES (?, ?, 'BIWEEKLY', 100.00, 200.00, 0, ?, ?, ?)",
                userId, createdAt, createdAt.toLocalDate(), createdAt.toLocalDate(), paused);
    }

    /**
     * A SETTLED transfer. transferId groups executions that one ACH funded: pass the same id twice
     * to model one transfer paying for two investments, which must count as ONE contribution.
     */
    public void settledTransfer(long userId, LocalDateTime settledAt, String transferId) {
        jdbc.update("INSERT INTO investment_executions "
                + "(user_id, amount, created_at, scheduled_date, status, updated_at, "
                + " funding_completed_at, alpaca_transfer_id) "
                + "VALUES (?, 100.00, ?, ?, 'FUNDING_COMPLETED', ?, ?, ?)",
                userId, settledAt, settledAt, settledAt, settledAt, transferId);
    }

    /** An execution that never settled: initiated and still unfunded. */
    public void unsettledTransfer(long userId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO investment_executions "
                + "(user_id, amount, created_at, scheduled_date, status, updated_at) "
                + "VALUES (?, 100.00, ?, ?, 'FUNDING_INITIATED', ?)",
                userId, createdAt, createdAt, createdAt);
    }

    public void outboxEvent(Long userId, String eventName, LocalDateTime occurredAt,
                            String propertiesJson, String status) {
        jdbc.update("INSERT INTO analytics_outbox "
                + "(event_id, event_name, user_id, public_id, properties, occurred_at, status, "
                + " attempts, consent_opt_out_at_write) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)",
                UUID.randomUUID().toString(), eventName, userId,
                UUID.randomUUID().toString(), propertiesJson, occurredAt, status);
    }
}
