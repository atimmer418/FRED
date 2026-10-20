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

CREATE TABLE IF NOT EXISTS `ach_authorizations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `user_public_id` varchar(64) NOT NULL,
  `mode` varchar(10) NOT NULL,
  `text_version` varchar(32) NOT NULL,
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
  `step_up` varchar(8) DEFAULT NULL,
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
