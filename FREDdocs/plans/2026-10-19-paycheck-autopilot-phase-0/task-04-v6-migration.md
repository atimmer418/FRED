### Task 4: v6-migration (V6__paycheck_autopilot.sql)

Spec section 3 (FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md:103-141) says V5, but `V5__analytics_outbox.sql` is already committed (1453c9a), so the file is **V6**. It carries ALL of section 3 so Phases 1 to 3 add no DDL; Phase 0 maps only the columns it uses, and `ddl-auto=validate` ignores unmapped columns. The validate gate is `BackendApplicationTests` under the ci profile on a scratch schema (RequiresCiDatabase.java:48-50 reads `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`; :67-69 SKIPS, not fails, when no database answers). Spec 3.2's "`frequency` validation gains SEMI_MONTHLY" needs nothing here: `frequency` stays `VARCHAR(20)` (InvestmentSchedule.java:38-39) and T03's `InvestmentFrequency.parse` accepts it.

Three deliberate foreign-key choices on `paycheck_cycles` go beyond the literal spec text and are documented in the file header so a reader can object: `event_id` is `ON DELETE SET NULL` (spec 3.5 says only "FK"; with the default RESTRICT a user delete can fail depending on which `users` cascade InnoDB runs first, because `paycheck_events` rows cascade away while a cycle still points at them); `stream_id` gets `fk_paycheck_cycles_stream ... ON DELETE CASCADE` and `schedule_id` gets `fk_paycheck_cycles_schedule ... ON DELETE SET NULL` (spec 3.5 lists both ids without an FK; the keys cost nothing and keep the table consistent with every other id column in section 3).

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__paycheck_autopilot.sql`
- Create (all in `backend/src/main/java/com/investingapp/backend/model/`): `PlaidItemStatus.java`, `ScheduleMode.java`, `HoldReason.java`, `PlaidWebhookEventStatus.java`, `PlaidWebhookEvent.java`, `PlaidItemRemoval.java`, `JobLock.java`
- Modify: `backend/src/main/java/com/investingapp/backend/model/User.java:176-178` (insert after `alpacaAchStatus`, before `account_status`); `backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java:55-57` (insert after `achRequestId`, before `monthly_streak`; T02 and T03 edit other lines of this file, no overlap); `backend/src/main/java/com/investingapp/backend/model/InvestmentExecution.java:5` (import), `:61` (fields), `:167` (hand-written accessors; this entity has no Lombok)
- Test: `backend/src/test/java/com/investingapp/backend/migration/V6PaycheckAutopilotMigrationTest.java` (new package dir), `backend/src/test/java/com/investingapp/backend/model/PhaseZeroEntityDefaultsTest.java` (new package dir), `backend/src/test/java/com/investingapp/backend/BackendApplicationTests.java` (existing; the validate gate)

**Interfaces:**
- Consumes: V3 guard block (V3__catch_up_pre_flyway_schemas.sql:45-52, names `@stmt` and `add_column`); CREATE TABLE house form (V5__analytics_outbox.sql:40-59: backtick-quoted names, two-space indent, `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`); boolean convention `nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE"` (User.java:273-274); enum-on-varchar `@Enumerated(EnumType.STRING)` + `@Column(length = n)` (TlhUserState.java:31-33); lazy FK (InvestmentExecution.java:16-18); `TEXT` via `columnDefinition = "TEXT"` (User.java:233, InvestmentExecution.java:39). Hibernate 6.6.15 (gradle cache): `Dialect.equivalentTypes` accepts an entity ENUM against a DB VARCHAR, and `ColumnDefinitions.hasMatchingType` compares `normalize(stripArgs(columnDefinition))` with the DB type name, so `columnDefinition = "CHAR(64)"` matches a `char` column where a bare String (varchar) fails (flyway-baseline.md:84-90 records that failure).
- Produces (package `com.investingapp.backend.model`): `enum PlaidItemStatus { OK, LOGIN_REQUIRED, PENDING_DISCONNECT, REVOKED, REMOVED, SYNC_LIMIT, NOT_SUPPORTED }`; `enum ScheduleMode { MANUAL, DETECTED }`; `enum HoldReason { AWAITING_AUTHORIZATION, BANK_CHANGE, ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED, ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, DORMANT, KILL_SWITCH, MANUAL_REVIEW }`; `enum PlaidWebhookEventStatus { RECEIVED, PROCESSING, PROCESSED, FAILED, IGNORED }`. Lombok accessors on `User`: `String getPlaidAccountMask()`, `Boolean getPlaidTransactionsConsented()`, `PlaidItemStatus getPlaidItemStatus()` (defaults to `OK`), `String getPlaidItemErrorCode()`, `LocalDateTime getPlaidItemLinkedAt()`, `LocalDateTime getPlaidWebhookAckedAt()`, `String getPreviousAchRelationshipId()` plus setters. Lombok accessors on `InvestmentSchedule`: `ScheduleMode getMode()` (defaults to `MANUAL`), `HoldReason getHoldReason()` plus setters. Hand-written pairs on `InvestmentExecution`: `InvestmentSchedule getSchedule()/setSchedule`, `String getSourceKey()/setSourceKey`, `String getNightKey()/setNightKey`, `LocalDate getBatchNight()/setBatchNight`, `LocalDateTime getTransferAttemptedAt()/setTransferAttemptedAt`. Entities `PlaidWebhookEvent` (`@Table(name = "plaid_webhook_events")`), `PlaidItemRemoval` (`@Table(name = "plaid_item_removals")`), `JobLock` (`@Table(name = "job_locks")`, `@Id String name`), all `@Getter @Setter @NoArgsConstructor`.

- [ ] **Step 1: Write the failing migration-shape test**

Create `backend/src/test/java/com/investingapp/backend/migration/V6PaycheckAutopilotMigrationTest.java`:

```java
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
```

- [ ] **Step 2: Run it and watch it fail**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.migration.V6PaycheckAutopilotMigrationTest'`

Expected: BUILD FAILED. The class fails in `@BeforeAll` before either test method runs; `backend/build/reports/tests/test/index.html` shows the AssertJ message `[db/migration/V6__paycheck_autopilot.sql must exist on the classpath]` followed by `Expecting actual not to be null`.

- [ ] **Step 3: Start V6 with the header and the two Plaid tables**

Create `backend/src/main/resources/db/migration/V6__paycheck_autopilot.sql` with exactly this content (more is appended in Steps 4 to 8):

```sql
-- Paycheck Autopilot: all of spec section 3 (FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md).
-- The spec calls this file V5; V5__analytics_outbox.sql was already committed, so it is V6.
--
-- Everything the feature will ever need in DDL lands here, so Phases 1 to 3 add no migration.
-- Phase 0 maps only a few of these columns onto entities; ddl-auto=validate ignores the rest.
--
-- Shape, per FREDdocs/flyway-baseline.md: new tables use CREATE TABLE IF NOT EXISTS, and every
-- ALTER on an existing table sits inside the V3 information_schema plus PREPARE guard (COLUMNS for
-- columns, STATISTICS for indexes, TABLE_CONSTRAINTS for foreign keys), so a re-run is a no-op.
-- New tables come first because the guarded foreign keys on investment_schedules and
-- investment_executions point at them.
--
-- Deliberate choices beyond the literal spec text, on paycheck_cycles:
--   event_id is ON DELETE SET NULL (spec 3.5 says only FK). With the default RESTRICT a user delete
--   can fail: InnoDB cascades paycheck_events away under users.id before it reaches the cycles that
--   still point at those events, and the order it picks is not ours to choose.
--   stream_id and schedule_id get foreign keys (CASCADE and SET NULL) although spec 3.5 lists them
--   as bare ids; they keep the table consistent with every other id column in section 3.

CREATE TABLE IF NOT EXISTS `paycheck_streams` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `plaid_item_id` varchar(80) DEFAULT NULL,
  `plaid_account_id` varchar(80) NOT NULL,
  `plaid_stream_id` varchar(80) DEFAULT NULL,
  `payer_token` varchar(64) NOT NULL,
  `payer_display_name` varchar(120) NOT NULL,
  `raw_descriptor` varchar(255) DEFAULT NULL,
  `state` varchar(12) NOT NULL,
  `role` varchar(12) NOT NULL,
  `cadence` varchar(12) DEFAULT NULL,
  `cadence_source` varchar(10) DEFAULT NULL,
  `anchor_dow` tinyint DEFAULT NULL,
  `anchor_parity_date` date DEFAULT NULL,
  `anchor_day_1` varchar(8) DEFAULT NULL,
  `anchor_day_2` varchar(8) DEFAULT NULL,
  `score` smallint NOT NULL DEFAULT 0,
  `score_breakdown_json` text,
  `reject_reason` varchar(32) DEFAULT NULL,
  `retired_reason` varchar(32) DEFAULT NULL,
  `plaid_status` varchar(32) DEFAULT NULL,
  `plaid_frequency` varchar(32) DEFAULT NULL,
  `plaid_pfc_detailed` varchar(32) DEFAULT NULL,
  `plaid_pfc_confidence` varchar(32) DEFAULT NULL,
  `plaid_is_active` tinyint(1) DEFAULT NULL,
  `plaid_predicted_next_date` date DEFAULT NULL,
  `deposits_found` smallint NOT NULL DEFAULT 0,
  `first_date` date DEFAULT NULL,
  `last_date` date DEFAULT NULL,
  `last_amount` decimal(12,2) DEFAULT NULL,
  `trailing_median` decimal(12,2) DEFAULT NULL,
  `trailing_cv` decimal(6,4) DEFAULT NULL,
  `predicted_next_date` date DEFAULT NULL,
  `missed_count` smallint NOT NULL DEFAULT 0,
  `retired_at` datetime(6) DEFAULT NULL,
  `plaid_snapshot_json` text,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_paycheck_streams_payer` (`user_id`, `plaid_account_id`, `payer_token`),
  KEY `ix_paycheck_streams_user_state` (`user_id`, `state`),
  CONSTRAINT `fk_paycheck_streams_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `paycheck_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `stream_id` bigint DEFAULT NULL,
  `plaid_transaction_id` varchar(80) NOT NULL,
  `pending_transaction_id` varchar(80) DEFAULT NULL,
  `plaid_account_id` varchar(80) NOT NULL,
  `amount` decimal(12,2) NOT NULL,
  `raw_amount` decimal(12,2) NOT NULL,
  `posted_date` date NOT NULL,
  `authorized_date` date DEFAULT NULL,
  `pending` tinyint(1) NOT NULL,
  `status` varchar(10) NOT NULL,
  `first_seen_at` datetime(6) NOT NULL,
  `removed_at` datetime(6) DEFAULT NULL,
  `modified_at` datetime(6) DEFAULT NULL,
  `raw_descriptor` varchar(255) DEFAULT NULL,
  `merchant_name` varchar(120) DEFAULT NULL,
  `counterparty_name` varchar(120) DEFAULT NULL,
  `counterparty_type` varchar(32) DEFAULT NULL,
  `pfc_primary` varchar(40) DEFAULT NULL,
  `pfc_detailed` varchar(40) DEFAULT NULL,
  `pfc_confidence` varchar(40) DEFAULT NULL,
  `payment_channel` varchar(12) DEFAULT NULL,
  `ppd_id` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_paycheck_events_plaid_transaction` (`plaid_transaction_id`),
  KEY `ix_paycheck_events_user_posted` (`user_id`, `posted_date`),
  KEY `ix_paycheck_events_stream_posted` (`stream_id`, `posted_date`),
  KEY `ix_paycheck_events_pending` (`pending_transaction_id`),
  CONSTRAINT `fk_paycheck_events_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_paycheck_events_stream` FOREIGN KEY (`stream_id`) REFERENCES `paycheck_streams` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

```

- [ ] **Step 4: Append ach_authorizations and paycheck_cycles**

Append to `V6__paycheck_autopilot.sql` (ach_authorizations first: paycheck_cycles references it):

```sql
CREATE TABLE IF NOT EXISTS `ach_authorizations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `user_public_id` varchar(64) NOT NULL,
  `mode` varchar(10) NOT NULL,
  `text_version` varchar(16) NOT NULL,
  `text_hash` char(64) NOT NULL,
  `rendered_text` text NOT NULL,
  `amount_rule` varchar(16) NOT NULL,
  `amount` decimal(12,2) NOT NULL,
  `timing_rule` varchar(32) NOT NULL,
  `cadence_label` varchar(40) DEFAULT NULL,
  `paychecks_per_year` smallint DEFAULT NULL,
  `payer_display_name` varchar(120) DEFAULT NULL,
  `plaid_item_id` varchar(80) DEFAULT NULL,
  `plaid_account_id` varchar(80) NOT NULL,
  `plaid_account_mask` varchar(4) DEFAULT NULL,
  `funding_account_label` varchar(80) NOT NULL,
  `alpaca_account_id_enc` varchar(512) NOT NULL,
  `alpaca_relationship_id_enc` varchar(512) DEFAULT NULL,
  `passkey_credential_id` varchar(512) DEFAULT NULL,
  `user_session_id` varchar(64) NOT NULL,
  `auth_method` varchar(16) DEFAULT NULL,
  `ip_address` varchar(45) DEFAULT NULL,
  `user_agent` varchar(255) DEFAULT NULL,
  `authorized_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `revoke_reason` varchar(32) DEFAULT NULL,
  `superseded_by` bigint DEFAULT NULL,
  `email_log_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_ach_authorizations_user_revoked` (`user_id`, `revoked_at`),
  CONSTRAINT `fk_ach_authorizations_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_ach_authorizations_superseded_by` FOREIGN KEY (`superseded_by`) REFERENCES `ach_authorizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `paycheck_cycles` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `schedule_id` bigint DEFAULT NULL,
  `stream_id` bigint NOT NULL,
  `period_key` varchar(16) NOT NULL,
  `state` varchar(10) NOT NULL,
  `expected_date` date NOT NULL,
  `window_start` date NOT NULL,
  `window_end` date NOT NULL,
  `event_id` bigint DEFAULT NULL,
  `deposit_amount` decimal(12,2) DEFAULT NULL,
  `confirmed_at` datetime(6) DEFAULT NULL,
  `amount_to_pull` decimal(12,2) DEFAULT NULL,
  `authorization_id` bigint DEFAULT NULL,
  `skip_reason` varchar(32) DEFAULT NULL,
  `voided_reason` varchar(32) DEFAULT NULL,
  `execution_id` bigint DEFAULT NULL,
  `queued_night` date DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_paycheck_cycles_stream_period` (`stream_id`, `period_key`),
  UNIQUE KEY `uq_paycheck_cycles_event` (`event_id`),
  KEY `ix_paycheck_cycles_user_state` (`user_id`, `state`),
  CONSTRAINT `fk_paycheck_cycles_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_paycheck_cycles_schedule` FOREIGN KEY (`schedule_id`) REFERENCES `investment_schedules` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_paycheck_cycles_stream` FOREIGN KEY (`stream_id`) REFERENCES `paycheck_streams` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_paycheck_cycles_event` FOREIGN KEY (`event_id`) REFERENCES `paycheck_events` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_paycheck_cycles_authorization` FOREIGN KEY (`authorization_id`) REFERENCES `ach_authorizations` (`id`),
  CONSTRAINT `fk_paycheck_cycles_execution` FOREIGN KEY (`execution_id`) REFERENCES `investment_executions` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

```

- [ ] **Step 5: Append the inbox, the removal queue and job_locks**

Append to `V6__paycheck_autopilot.sql`:

```sql
CREATE TABLE IF NOT EXISTS `plaid_webhook_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `dedupe_key` char(64) NOT NULL,
  `webhook_type` varchar(40) NOT NULL,
  `webhook_code` varchar(40) NOT NULL,
  `plaid_item_id` varchar(80) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `environment` varchar(12) NOT NULL,
  `payload_json` text NOT NULL,
  `status` varchar(12) NOT NULL,
  `attempts` smallint NOT NULL DEFAULT 0,
  `last_error` varchar(512) DEFAULT NULL,
  `jwt_iat` bigint NOT NULL,
  `received_at` datetime(6) NOT NULL,
  `processed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_plaid_webhook_events_dedupe_key` (`dedupe_key`),
  KEY `idx_pwe_status_received` (`status`, `received_at`),
  KEY `idx_pwe_item_received` (`plaid_item_id`, `received_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `plaid_item_removals` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `item_id` varchar(80) NOT NULL,
  `access_token_enc` varchar(512) NOT NULL,
  `requested_at` datetime(6) NOT NULL,
  `removed_at` datetime(6) DEFAULT NULL,
  `attempts` int NOT NULL DEFAULT 0,
  `last_error` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pir_pending` (`removed_at`, `requested_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `job_locks` (
  `name` varchar(64) NOT NULL,
  `locked_until` datetime(6) NOT NULL,
  `locked_by` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

```

- [ ] **Step 6: Append the guarded users columns and index**

Append to `V6__paycheck_autopilot.sql`. Every block is the V3 shape (V3:45-52); a quoted default is doubled inside the guard string (`''OK''`), which MySQL reads as `'OK'`:

```sql
-- users (spec 3.1)

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_account_mask VARCHAR(4)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_account_mask');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_sync_cursor TEXT',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_sync_cursor');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_history_complete TINYINT(1) NOT NULL DEFAULT 0',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_history_complete');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_transactions_update_status VARCHAR(40)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_transactions_update_status');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_transactions_consented TINYINT(1)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_transactions_consented');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_item_status VARCHAR(24) NOT NULL DEFAULT ''OK''',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_item_status');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_item_error_code VARCHAR(64)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_item_error_code');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_disconnect_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_disconnect_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_webhook_acked_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_webhook_acked_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_last_sync_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_last_sync_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_streams_read_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_streams_read_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN plaid_item_linked_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'plaid_item_linked_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN previous_ach_relationship_id VARCHAR(512)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'previous_ach_relationship_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN paycheck_autopilot_opt_out TINYINT(1) NOT NULL DEFAULT 0',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'paycheck_autopilot_opt_out');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD INDEX idx_users_plaid_item_id (plaid_item_id)',
        'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_plaid_item_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

```

- [ ] **Step 7: Append the guarded investment_schedules columns and foreign keys**

Append to `V6__paycheck_autopilot.sql`:

```sql
-- investment_schedules (spec 3.2)

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN mode VARCHAR(10) NOT NULL DEFAULT ''MANUAL''',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'mode');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN detection_state VARCHAR(16)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'detection_state');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN hold_reason VARCHAR(32)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'hold_reason');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN paused_by_user_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'paused_by_user_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN primary_stream_id BIGINT',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'primary_stream_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN authorization_id BIGINT',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'authorization_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN authorized_amount DECIMAL(12,2)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'authorized_amount');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN amount_source VARCHAR(20)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'amount_source');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN next_expected_date DATE',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'next_expected_date');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN first_anchor VARCHAR(8)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'first_anchor');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN second_anchor VARCHAR(8)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'second_anchor');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN missed_count SMALLINT NOT NULL DEFAULT 0',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'missed_count');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN dormant_since DATE',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'dormant_since');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN snoozed_until DATE',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'snoozed_until');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN last_cadence_check_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'last_cadence_check_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN detection_started_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND COLUMN_NAME = 'detection_started_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD CONSTRAINT fk_investment_schedules_stream FOREIGN KEY (primary_stream_id) REFERENCES paycheck_streams (id) ON DELETE SET NULL',
        'DO 0')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND CONSTRAINT_NAME = 'fk_investment_schedules_stream' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD CONSTRAINT fk_investment_schedules_authorization FOREIGN KEY (authorization_id) REFERENCES ach_authorizations (id) ON DELETE SET NULL',
        'DO 0')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules' AND CONSTRAINT_NAME = 'fk_investment_schedules_authorization' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

```

- [ ] **Step 8: Append investment_executions and user_sessions**

Append to `V6__paycheck_autopilot.sql` (two UNIQUE keys allow many NULLs in MySQL, which is what `night_key` relies on):

```sql
-- investment_executions (spec 3.8)

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN schedule_id BIGINT',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'schedule_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN authorization_id BIGINT',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'authorization_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN cycle_id BIGINT',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'cycle_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN plaid_transaction_id VARCHAR(80)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'plaid_transaction_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN source_key VARCHAR(120)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'source_key');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN night_key VARCHAR(40)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'night_key');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN batch_night DATE',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'batch_night');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN transfer_attempted_at DATETIME(6)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'transfer_attempted_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN ach_return_code VARCHAR(8)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'ach_return_code');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN ach_return_class VARCHAR(24)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'ach_return_class');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD COLUMN returned_after_settlement TINYINT(1) NOT NULL DEFAULT 0',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND COLUMN_NAME = 'returned_after_settlement');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD UNIQUE KEY uk_investment_executions_source_key (source_key)',
        'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND INDEX_NAME = 'uk_investment_executions_source_key');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD UNIQUE KEY uk_investment_executions_night_key (night_key)',
        'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND INDEX_NAME = 'uk_investment_executions_night_key');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD CONSTRAINT fk_investment_executions_schedule FOREIGN KEY (schedule_id) REFERENCES investment_schedules (id) ON DELETE SET NULL',
        'DO 0')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND CONSTRAINT_NAME = 'fk_investment_executions_schedule' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD CONSTRAINT fk_investment_executions_cycle FOREIGN KEY (cycle_id) REFERENCES paycheck_cycles (id) ON DELETE SET NULL',
        'DO 0')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND CONSTRAINT_NAME = 'fk_investment_executions_cycle' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_executions ADD CONSTRAINT fk_investment_executions_authorization FOREIGN KEY (authorization_id) REFERENCES ach_authorizations (id)',
        'DO 0')
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_executions' AND CONSTRAINT_NAME = 'fk_investment_executions_authorization' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

-- user_sessions (spec 3.9); UserSession.java maps neither column in Phase 0

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE user_sessions ADD COLUMN passkey_credential_id VARCHAR(512)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_sessions' AND COLUMN_NAME = 'passkey_credential_id');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE user_sessions ADD COLUMN auth_method VARCHAR(16)',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_sessions' AND COLUMN_NAME = 'auth_method');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;
```

- [ ] **Step 9: Run the shape test to green**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.migration.V6PaycheckAutopilotMigrationTest'`

Expected: BUILD SUCCESSFUL, 2 tests pass. A guard count other than 51 (users 14 + 1 index, investment_schedules 16 + 2 keys, investment_executions 11 + 2 unique + 3 keys, user_sessions 2) means a block was dropped; the failing marker name is in the assertion description.

- [ ] **Step 10: Write the failing entity test**

Create `backend/src/test/java/com/investingapp/backend/model/PhaseZeroEntityDefaultsTest.java`:

```java
package com.investingapp.backend.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The V6 defaults (plaid_item_status 'OK', mode 'MANUAL', attempts 0) must be mirrored by field
 * initialisers, because User(String) and the InvestmentExecution constructors never set them and
 * Hibernate validate checks column types only, never defaults.
 */
class PhaseZeroEntityDefaultsTest {

    @Test
    void userStartsLinkedOkWithUnknownConsent() {
        User user = new User("phase0@fredvested.com");
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(user.getPlaidTransactionsConsented()).isNull();
        assertThat(user.getPlaidAccountMask()).isNull();
        assertThat(user.getPlaidItemErrorCode()).isNull();
        assertThat(user.getPlaidItemLinkedAt()).isNull();
        assertThat(user.getPlaidWebhookAckedAt()).isNull();
        assertThat(user.getPreviousAchRelationshipId()).isNull();
    }

    @Test
    void scheduleIsManualWithNoHold() {
        InvestmentSchedule schedule = new InvestmentSchedule();
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        assertThat(schedule.getHoldReason()).isNull();
    }

    @Test
    void executionIdempotencyFieldsStartEmpty() {
        InvestmentExecution execution = new InvestmentExecution();
        assertThat(execution.getSchedule()).isNull();
        assertThat(execution.getSourceKey()).isNull();
        assertThat(execution.getNightKey()).isNull();
        assertThat(execution.getBatchNight()).isNull();
        assertThat(execution.getTransferAttemptedAt()).isNull();
    }

    @Test
    void countersStartAtZero() {
        assertThat(new PlaidWebhookEvent().getAttempts()).isEqualTo((short) 0);
        assertThat(new PlaidItemRemoval().getAttempts()).isZero();
    }

    @Test
    void enumNamesFitTheirColumns() {
        assertThat(longest(PlaidItemStatus.values())).isLessThanOrEqualTo(24);
        assertThat(longest(ScheduleMode.values())).isLessThanOrEqualTo(10);
        assertThat(longest(HoldReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(PlaidWebhookEventStatus.values())).isLessThanOrEqualTo(12);
        assertThat(HoldReason.values()).hasSize(10);
        assertThat(PlaidItemStatus.values()).hasSize(7);
    }

    private static int longest(Enum<?>[] values) {
        return Arrays.stream(values).mapToInt(v -> v.name().length()).max().orElse(0);
    }
}
```

- [ ] **Step 11: Run it and watch it fail to compile**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.model.PhaseZeroEntityDefaultsTest'`

Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` for `PlaidItemStatus` (and the other new types) and for `getPlaidItemStatus()`, `getMode()`, `getSchedule()`.

- [ ] **Step 12: Create the four enums**

Create `backend/src/main/java/com/investingapp/backend/model/PlaidItemStatus.java`:

```java
package com.investingapp.backend.model;

/** users.plaid_item_status (spec 3.1). Written by the webhook processor from Phase 1 on. */
public enum PlaidItemStatus {
    OK, LOGIN_REQUIRED, PENDING_DISCONNECT, REVOKED, REMOVED, SYNC_LIMIT, NOT_SUPPORTED
}
```

Create `backend/src/main/java/com/investingapp/backend/model/ScheduleMode.java`:

```java
package com.investingapp.backend.model;

/** investment_schedules.mode (spec 3.2). Phase 0 never produces DETECTED. */
public enum ScheduleMode {
    MANUAL, DETECTED
}
```

Create `backend/src/main/java/com/investingapp/backend/model/HoldReason.java`:

```java
package com.investingapp.backend.model;

/**
 * investment_schedules.hold_reason (spec 3.2): the system's stop. is_paused stays the user's stop;
 * null means no system hold.
 */
public enum HoldReason {
    AWAITING_AUTHORIZATION, BANK_CHANGE, ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED,
    ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, DORMANT, KILL_SWITCH, MANUAL_REVIEW
}
```

Create `backend/src/main/java/com/investingapp/backend/model/PlaidWebhookEventStatus.java`:

```java
package com.investingapp.backend.model;

/** plaid_webhook_events.status (spec 3.6). Phase 0 writes RECEIVED and IGNORED only. */
public enum PlaidWebhookEventStatus {
    RECEIVED, PROCESSING, PROCESSED, FAILED, IGNORED
}
```

- [ ] **Step 13: Create the three new entities**

Create `backend/src/main/java/com/investingapp/backend/model/PlaidWebhookEvent.java` (dedupe_key declares `CHAR(64)` so validate matches the V6 column; a bare String maps to varchar and fails, flyway-baseline.md:84-90):

```java
package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One row per Plaid webhook delivery, written by the verification endpoint (spec 3.6). */
@Entity
@Table(name = "plaid_webhook_events")
@Getter
@Setter
@NoArgsConstructor
public class PlaidWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, unique = true, columnDefinition = "CHAR(64)")
    private String dedupeKey;

    @Column(name = "webhook_type", nullable = false, length = 40)
    private String webhookType;

    @Column(name = "webhook_code", nullable = false, length = 40)
    private String webhookCode;

    @Column(name = "plaid_item_id", length = 80)
    private String plaidItemId;

    /** No foreign key: the inbox row outlives the user (spec 3.6). */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "environment", nullable = false, length = 12)
    private String environment;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private PlaidWebhookEventStatus status;

    @Column(name = "attempts", nullable = false)
    private short attempts = 0;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "jwt_iat", nullable = false)
    private long jwtIat;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
```

Create `backend/src/main/java/com/investingapp/backend/model/PlaidItemRemoval.java`:

```java
package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A Plaid Item waiting for /item/remove (spec 3.9). Carries its own encrypted token copy because a
 * relink overwrites users.plaid_access_token before the old Item is removed.
 */
@Entity
@Table(name = "plaid_item_removals")
@Getter
@Setter
@NoArgsConstructor
public class PlaidItemRemoval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** No foreign key: the row outlives the user. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "item_id", nullable = false, length = 80)
    private String itemId;

    @Column(name = "access_token_enc", nullable = false, length = 512)
    private String accessTokenEnc;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;
}
```

Create `backend/src/main/java/com/investingapp/backend/model/JobLock.java`:

```java
package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A lease held by one scheduled job at a time (spec 3.9); the name is the assigned id. */
@Entity
@Table(name = "job_locks")
@Getter
@Setter
@NoArgsConstructor
public class JobLock {

    @Id
    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "locked_until", nullable = false)
    private LocalDateTime lockedUntil;

    @Column(name = "locked_by", length = 64)
    private String lockedBy;
}
```

- [ ] **Step 14: Add the Phase 0 fields to User**

In `backend/src/main/java/com/investingapp/backend/model/User.java`, insert after line 177 (the blank line under `private String alpacaAchStatus;`) and before line 178 (`@Column(name = "account_status", length = 30)`). `jakarta.persistence.*` (:4) already covers `Enumerated` and `EnumType`; `java.time.LocalDateTime` is imported at :17:

```java
    // Paycheck Autopilot, Phase 0 (V6). The rest of spec 3.1 stays unmapped until a later phase.
    @Column(name = "plaid_account_mask", length = 4)
    private String plaidAccountMask;

    /** Tri-state: null unknown, true consented, false refused (spec 2.3). Nullable, so no DEFAULT. */
    @Column(name = "plaid_transactions_consented", columnDefinition = "BOOLEAN")
    private Boolean plaidTransactionsConsented;

    @Enumerated(EnumType.STRING)
    @Column(name = "plaid_item_status", length = 24, nullable = false)
    private PlaidItemStatus plaidItemStatus = PlaidItemStatus.OK;

    @Column(name = "plaid_item_error_code", length = 64)
    private String plaidItemErrorCode;

    @Column(name = "plaid_item_linked_at")
    private LocalDateTime plaidItemLinkedAt;

    @Column(name = "plaid_webhook_acked_at")
    private LocalDateTime plaidWebhookAckedAt;

    @Column(name = "previous_ach_relationship_id", length = 512)
    private String previousAchRelationshipId; // Stored as ENC:<base64(iv+ciphertext)>, encrypted at rest

```

- [ ] **Step 15: Add mode and holdReason to InvestmentSchedule**

In `backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java`, insert after line 56 (the blank line under `private String achRequestId;`) and before line 57 (`@Column(name = "monthly_streak", nullable = false)`). `@Data` (:11) generates the accessors. `@AllArgsConstructor` (:12) gains two parameters; no caller in `backend/src` uses it, so nothing breaks:

```java
    // Paycheck Autopilot, Phase 0 (V6). Phase 0 never produces DETECTED.
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 10, nullable = false)
    private ScheduleMode mode = ScheduleMode.MANUAL;

    /** The system's stop; null means none. is_paused stays the user's stop. */
    @Enumerated(EnumType.STRING)
    @Column(name = "hold_reason", length = 32)
    private HoldReason holdReason;

```

- [ ] **Step 16: Add the idempotency fields and accessors to InvestmentExecution**

In `backend/src/main/java/com/investingapp/backend/model/InvestmentExecution.java`, after line 5 (`import java.time.LocalDateTime;`) add:

```java
import java.time.LocalDate;
```

After line 61 (`private LocalDateTime updatedAt;`, now shifted one line down by the import) insert:

```java

    // Paycheck Autopilot, Phase 0 (V6).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private InvestmentSchedule schedule;

    /** SCHED:{scheduleId}:{dueDate} or LUMP:{userId}:{clientUuid}; UNIQUE in V6. */
    @Column(name = "source_key", length = 120, unique = true)
    private String sourceKey;

    /** {userId}:{queuedNightET}, recurring only; UNIQUE in V6, NULLs allowed. */
    @Column(name = "night_key", length = 40, unique = true)
    private String nightKey;

    @Column(name = "batch_night")
    private LocalDate batchNight;

    /** Stamped by the 11:59 batch claim before the Alpaca POST (spec 5.5). */
    @Column(name = "transfer_attempted_at")
    private LocalDateTime transferAttemptedAt;
```

After the line `public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }` (line 167 before this step), before the closing brace, insert:

```java

    public InvestmentSchedule getSchedule() { return schedule; }
    public void setSchedule(InvestmentSchedule schedule) { this.schedule = schedule; }

    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }

    public String getNightKey() { return nightKey; }
    public void setNightKey(String nightKey) { this.nightKey = nightKey; }

    public LocalDate getBatchNight() { return batchNight; }
    public void setBatchNight(LocalDate batchNight) { this.batchNight = batchNight; }

    public LocalDateTime getTransferAttemptedAt() { return transferAttemptedAt; }
    public void setTransferAttemptedAt(LocalDateTime transferAttemptedAt) { this.transferAttemptedAt = transferAttemptedAt; }
```

- [ ] **Step 17: Run both new tests and the full compile**

`cd backend && ./gradlew compileJava compileTestJava test --tests 'com.investingapp.backend.model.PhaseZeroEntityDefaultsTest' --tests 'com.investingapp.backend.migration.V6PaycheckAutopilotMigrationTest'`

Expected: BUILD SUCCESSFUL, 7 tests pass (5 entity + 2 migration shape).

- [ ] **Step 18: Prove validate on a scratch schema, twice**

Local MySQL listens on 127.0.0.1:3306. Use a throwaway schema, never `investing_dev`. The root password is the value of `spring.datasource.password` at `backend/src/main/resources/application-local.properties:3`; the first line below reads it into a shell variable without printing it. Run each fenced block as ONE shell invocation (the variable does not survive between separate invocations), from the repo root:

```bash
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties) \
&& MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u root -e "CREATE DATABASE IF NOT EXISTS fred_ci_scratch" \
&& (cd backend && SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_ci_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$MYSQL_PW" ./gradlew cleanTest test --tests 'com.investingapp.backend.BackendApplicationTests' \
&& grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml)
```

Expected: BUILD SUCCESSFUL and the last line `tests="1" skipped="0" failures="0" errors="0"`. `skipped="1"` means the database was not reached (RequiresCiDatabase.java:67-69 prints the cause); that is not a pass. On an empty schema Flyway runs V1 through V6 and then the repeatable `R__v_*.sql` views. A `Schema-validation: wrong column type encountered in column [x] in table [y]` line names the entity annotation to fix. A failure inside an `R__v_*.sql` view is untracked WIP from another session, not this task: report it and do not touch those files.

Run the identical block a second time: every guard resolves to `DO 0`, every CREATE TABLE is skipped, and the result line is identical. Then drop the schema:

```bash
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties) \
&& MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u root -e "DROP DATABASE fred_ci_scratch"
```

CI repeats the validate run against its mysql:8.0 service (`.github/workflows/verify.yml:24-31`, `:60-66`).

- [ ] **Step 19: Commit exactly these files**

Shared dirty tree: first confirm the three modified entities carry only this task's hunks, then stage by full path (seven untracked `R__v_*.sql` files sit in the migration directory and must stay untracked):

```bash
git diff HEAD -- backend/src/main/java/com/investingapp/backend/model/User.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/model/InvestmentExecution.java
```

Expected: only the Step 14, 15 and 16 hunks. If a hunk you did not write appears, stage with `git add -p` and skip it. Then:

```bash
git add backend/src/main/resources/db/migration/V6__paycheck_autopilot.sql \
  backend/src/main/java/com/investingapp/backend/model/PlaidItemStatus.java \
  backend/src/main/java/com/investingapp/backend/model/ScheduleMode.java \
  backend/src/main/java/com/investingapp/backend/model/HoldReason.java \
  backend/src/main/java/com/investingapp/backend/model/PlaidWebhookEventStatus.java \
  backend/src/main/java/com/investingapp/backend/model/PlaidWebhookEvent.java \
  backend/src/main/java/com/investingapp/backend/model/PlaidItemRemoval.java \
  backend/src/main/java/com/investingapp/backend/model/JobLock.java \
  backend/src/main/java/com/investingapp/backend/model/User.java \
  backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java \
  backend/src/main/java/com/investingapp/backend/model/InvestmentExecution.java \
  backend/src/test/java/com/investingapp/backend/migration/V6PaycheckAutopilotMigrationTest.java \
  backend/src/test/java/com/investingapp/backend/model/PhaseZeroEntityDefaultsTest.java
git status --short backend/src/main/resources/db/migration/
git commit -m "feat(db): V6 paycheck autopilot schema and the Phase 0 entity fields"
```

Expected: 13 files in the commit; `git status --short` on the migration directory still lists the seven `R__v_*.sql` files as `??`.