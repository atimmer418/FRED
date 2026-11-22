-- Goal tally: a server-owned "Saved so far" ledger, the credits window, and the unit target.
-- Idempotent: information_schema guards plus PREPARE, like V6 and V7.

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE goals ADD COLUMN credited_through DATE NULL',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goals' AND COLUMN_NAME = 'credited_through');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE goals ADD COLUMN credits_until DATE NULL',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goals' AND COLUMN_NAME = 'credits_until');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE goals ADD COLUMN target DECIMAL(12,2) NULL',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goals' AND COLUMN_NAME = 'target');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

-- credit_key makes a CREDIT unique per unit and day, while adjustments (MISSED, EXTRA, SET) may repeat.
CREATE TABLE IF NOT EXISTS `goal_ledger_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `goal_id` bigint NOT NULL,
  `unit` varchar(40) NOT NULL,
  `entry_date` date NOT NULL,
  `kind` varchar(8) NOT NULL,
  `amount` decimal(12,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `credit_key` varchar(80) GENERATED ALWAYS AS (IF(`kind` = 'CREDIT', CONCAT(`unit`, ':', `entry_date`), NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_goal_ledger_credit` (`goal_id`, `credit_key`),
  KEY `ix_goal_ledger_goal_unit_date` (`goal_id`, `unit`, `entry_date`),
  CONSTRAINT `fk_goal_ledger_goal` FOREIGN KEY (`goal_id`) REFERENCES `goals` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
