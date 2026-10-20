-- The transactional outbox for server-side analytics, plus the two KYC columns the events need.
--
-- Why an outbox at all: the funnel and retention numbers are built from facts about money moving
-- and about compliance decisions. Those must not depend on a phone being awake, on a network call
-- succeeding, or on a third party staying up. So the event row is written in the SAME database
-- transaction as the state change it describes: if the KYC submission commits, its event commits
-- with it, and if the submission rolls back the event is not there to describe something that
-- never happened. A scheduled publisher drains the table afterwards.
--
-- Rows are NEVER deleted. Checkpoint G's SQL metrics read this table directly, so it is the source
-- of truth for the money and compliance funnel whatever PostHog does with its copy.
--
-- Columns worth explaining:
--   event_id        The uuid sent to PostHog as the event's own id. Stable for the life of the
--                   row, so a retry after a timeout that actually succeeded is de-duplicated by
--                   PostHog rather than double counted by us.
--   public_id       Denormalised, not joined. A rejected KYC deletes the user row
--                   (AccountStatusService.handleRejected), and the ledger must still be able to
--                   describe what happened without resurrecting a person.
--   user_id         For debugging and for Checkpoint G's joins. Deliberately NO foreign key, for
--                   the same reason: the ledger outlives the user.
--   properties      JSON as TEXT, hand-serialised. Matches the house pattern (email_log,
--                   conversation insights); no new mapping machinery, and nothing here is queried
--                   by key today.
--   status          PENDING -> SENT | SUPPRESSED | PARKED. SUPPRESSED is a user who had opted out
--                   when the row came up for publishing: the row stays as our own record and is
--                   never sent. PARKED is a row that cannot be sent as it stands (no public_id)
--                   and needs a human, rather than being retried forever.
--   next_attempt_at Backoff. Null means "eligible now".
--   consent_opt_out_at_write
--                   Consent as it stood when the row was written. Consent is checked against the
--                   LIVE user at publish time, which is the promise we make; this snapshot is the
--                   fallback for the one case where that is impossible, a KYC rejection, which
--                   hard-deletes the user in the same transaction that records the decision
--                   (AccountStatusService.handleRejected). Without it a rejection could only be
--                   published by guessing at consent, and guessing wrong is not an option.
--
-- Idempotent: safe to run against a database that already has the table or the columns.

CREATE TABLE IF NOT EXISTS `analytics_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` varchar(36) NOT NULL,
  `event_name` varchar(64) NOT NULL,
  `public_id` varchar(36) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `properties` text NOT NULL,
  `person_properties` text,
  `occurred_at` datetime(6) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `sent_at` datetime(6) DEFAULT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(6) DEFAULT NULL,
  `last_error` text,
  `consent_opt_out_at_write` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_analytics_outbox_event_id` (`event_id`),
  KEY `ix_analytics_outbox_unsent` (`status`, `next_attempt_at`),
  KEY `ix_analytics_outbox_user` (`user_id`, `event_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- kyc_submitted carries which attempt this is, and kyc_decided carries how long the decision took.
-- Neither can be derived from what the schema holds today: there is no submission timestamp and no
-- attempt counter anywhere, and users.create_date includes all the onboarding drift before KYC.

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN kyc_submitted_at DATETIME(6) DEFAULT NULL',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'kyc_submitted_at');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN kyc_attempts INT NOT NULL DEFAULT 0',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'kyc_attempts');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;
