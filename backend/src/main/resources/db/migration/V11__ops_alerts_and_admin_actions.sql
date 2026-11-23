-- Paycheck Autopilot Phase 1: the ops alert ledger (spec 12.3) and the admin audit trail (spec 12.2).
--
-- V6 says Phases 1 to 3 add no migration. That held for spec section 3, the data model. These two
-- tables come from section 12, the ops tooling, which V6 never built: OpsAlertService needs a
-- ledger to dedupe on, and the prod-registered /api/admin/** surface needs one row per call.
--
-- Numbered V11: Goals v3 owns version 9 (goals.sql) and version 10 (goal_ledger.sql). Two files with one
-- version number merge cleanly in git and then stop Flyway at boot.
--
-- Shape, per V6 and V8: CREATE TABLE IF NOT EXISTS, so a re-run is a no-op. There is no ALTER, so
-- no information_schema guard is needed. No view changes. No foreign keys: both tables outlive the
-- users they name, an alert subject is often not a user at all, and MetricsViewSeed.cleanCohort
-- deletes every table with a foreign key to users, so a key here would change what it deletes.
--
-- ops_alerts: one row per (alert_type, subject_id, alert_day). alert_day is the Eastern calendar
-- day. A repeat on the same day bumps occurrences and last_raised_at and sends nothing. Both
-- timestamps are Eastern wall time, set by OpsAlertService. delivery_status is PENDING until the
-- delivery attempt records SENT, FAILED or LOGGED. The application sets every column on insert,
-- so the DEFAULTs below are for hand-written rows only; the entity mirrors them in Java.
--
-- admin_actions: one row per /api/admin/** call. user_id is kept after the user is deleted, on
-- purpose: the record of who did what to an account has to survive the account. created_at is
-- Eastern wall time, set by AdminAuditService.
--
-- Content rule for both tables: ids, counts, error codes and closed-vocabulary words only. Never an
-- amount, a payer name or descriptor, a Plaid token or cursor, an email, or a free-text error body.
--
-- Idempotent: safe to run against a database that already has both tables.

CREATE TABLE IF NOT EXISTS `ops_alerts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `alert_type` varchar(64) NOT NULL,
  `subject_id` varchar(80) NOT NULL,
  `alert_day` date NOT NULL,
  `message` varchar(500) NOT NULL,
  `context_json` text,
  `occurrences` int NOT NULL DEFAULT 1,
  `first_raised_at` datetime(6) NOT NULL,
  `last_raised_at` datetime(6) NOT NULL,
  `delivery_status` varchar(12) NOT NULL DEFAULT 'PENDING',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ops_alerts_type_subject_day` (`alert_type`, `subject_id`, `alert_day`),
  KEY `ix_ops_alerts_day` (`alert_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `admin_actions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actor` varchar(80) NOT NULL,
  `user_id` bigint DEFAULT NULL,
  `verb` varchar(64) NOT NULL,
  `target_id` varchar(80) DEFAULT NULL,
  `before_json` text,
  `after_json` text,
  `reason` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_admin_actions_user_created` (`user_id`, `created_at`),
  KEY `ix_admin_actions_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
