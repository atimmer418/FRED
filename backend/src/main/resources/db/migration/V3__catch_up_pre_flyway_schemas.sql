-- Catch-up for databases that predate Flyway.
--
-- baseline-on-migrate stamps an existing schema at V1 WITHOUT running it, so anything V1 contains
-- that the target database lacks would never be created, and ddl-auto=validate would then refuse to
-- start the app. That is the real situation on Railway dev: it runs develop @ 7c0d5e8 (2026-07-15)
-- and its schema was built by ddl-auto from those entities, so it is missing everything added since.
--
-- The gap was measured, not guessed: the 7c0d5e8 entities were booted against a scratch database and
-- its schema compared column by column with the schema these migrations build. The difference is
-- exactly the two TLH tables and the two users columns below (public_id is handled by V2).
--
-- Every statement is guarded, so this is a no-op on a database that already ran V1.

CREATE TABLE IF NOT EXISTS `tlh_cycle_runs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `candidates` int NOT NULL,
  `failed` int NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `harvested` int NOT NULL,
  `mode` varchar(10) NOT NULL,
  `notes` text,
  `run_date` date NOT NULL,
  `run_trigger` varchar(10) NOT NULL,
  `skipped` int NOT NULL,
  `started_at` datetime(6) NOT NULL,
  `users_evaluated` int NOT NULL,
  `vetoed` int NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `tlh_user_states` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `backfill_pages_done` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `cursor_activity_id` varchar(64) DEFAULT NULL,
  `cursor_after` varchar(40) DEFAULT NULL,
  `last_sync_at` datetime(6) DEFAULT NULL,
  `ledger_status` enum('BACKFILLING','DEGRADED','READY') NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tlh_user_states_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN tlh_enabled TINYINT(1) NOT NULL DEFAULT 0',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'tlh_enabled');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN tlh_enrolled_at DATETIME(6) DEFAULT NULL',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'tlh_enrolled_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;
