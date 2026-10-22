package com.investingapp.backend.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shape of V6 without a database: every table from spec section 3 is CREATE TABLE IF NOT
 * EXISTS and comes before the first guard, every ALTER sits inside the V3 information_schema plus
 * PREPARE guard, and every spec column, index and key name is present. The validate gate
 * (BackendApplicationTests under ci) proves the SQL runs; this proves nothing was dropped.
 */
class V6PaycheckAutopilotMigrationTest {

    private static final String FILE = "db/migration/V6__paycheck_autopilot.sql";
    private static final String COLUMNS = """
            users: plaid_account_mask plaid_sync_cursor plaid_history_complete plaid_transactions_update_status plaid_transactions_consented plaid_item_status plaid_item_error_code plaid_disconnect_at plaid_webhook_acked_at plaid_last_sync_at plaid_streams_read_at plaid_item_linked_at previous_ach_relationship_id paycheck_autopilot_opt_out
            investment_schedules: mode detection_state hold_reason paused_by_user_at primary_stream_id authorization_id authorized_amount amount_source next_expected_date first_anchor second_anchor missed_count dormant_since snoozed_until last_cadence_check_at detection_started_at
            investment_executions: schedule_id authorization_id cycle_id plaid_transaction_id source_key night_key batch_night transfer_attempted_at ach_return_code ach_return_class returned_after_settlement
            user_sessions: passkey_credential_id auth_method
            """;
    private static final String[] INDEXES = {"idx_users_plaid_item_id", "uk_investment_executions_source_key",
            "uk_investment_executions_night_key"};
    private static final String[] KEYS = {"fk_investment_schedules_stream", "fk_investment_schedules_authorization",
            "fk_investment_executions_schedule", "fk_investment_executions_cycle", "fk_investment_executions_authorization"};

    private static String sql;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = V6PaycheckAutopilotMigrationTest.class.getClassLoader().getResourceAsStream(FILE)) {
            assertThat(in).as(FILE + " must exist on the classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void newTablesAreIfNotExistsAndComeFirst() {
        assertThat(Pattern.compile("(?m)^CREATE TABLE(?! IF NOT EXISTS)").matcher(sql).find()).isFalse();
        for (String t : "paycheck_streams paycheck_events ach_authorizations paycheck_cycles plaid_webhook_events plaid_item_removals job_locks".split(" ")) {
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS `" + t + "` (");
        }
        assertThat(sql.lastIndexOf("CREATE TABLE")).isLessThan(sql.indexOf("SET @stmt :="));
    }

    @Test
    void everyAlterIsGuardedAndEverySpecNameIsPresent() {
        assertThat(Pattern.compile("(?m)^(ALTER TABLE|CREATE (UNIQUE )?INDEX)").matcher(sql).find()).isFalse();
        int guards = INDEXES.length + KEYS.length;
        for (String line : COLUMNS.strip().split("\n")) {
            String[] tableAndColumns = line.strip().split(": ");
            for (String c : tableAndColumns[1].split(" ")) {
                assertThat(sql).contains("TABLE_NAME = '" + tableAndColumns[0] + "' AND COLUMN_NAME = '" + c + "'");
                guards++;
            }
        }
        for (String i : INDEXES) {
            assertThat(sql).contains("INDEX_NAME = '" + i + "'");
        }
        for (String k : KEYS) {
            assertThat(sql).contains("CONSTRAINT_NAME = '" + k + "' AND CONSTRAINT_TYPE = 'FOREIGN KEY'");
        }
        assertThat(guards).isEqualTo(51);
        for (String marker : new String[] {"SET @stmt := (SELECT IF(COUNT(*) = 0,", "'ALTER TABLE ",
                "PREPARE add_column FROM @stmt;", "EXECUTE add_column;", "DEALLOCATE PREPARE add_column;"}) {
            assertThat(count(marker)).as(marker).isEqualTo(guards);
        }
        assertThat(sql).contains("DEFAULT ''OK''").contains("DEFAULT ''MANUAL''").doesNotContain("—");
    }

    private static int count(String needle) {
        return sql.split(Pattern.quote(needle), -1).length - 1;
    }
}
