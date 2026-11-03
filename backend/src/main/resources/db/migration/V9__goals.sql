-- Per-user savings goals (Goals v3).
--
-- One row per goal. The type-specific settings (target amount, date, and so on) live in `config`
-- as JSON written by GoalConfigCodec. It is a TEXT column rather than the JSON type because that
-- is the house pattern and keeps `ddl-auto=validate` happy against a plain String field.
--
-- plan_rate is the numeric growth rate the anchor (anchor_balance, anchor_monthly) was computed
-- at, so the plan can be re-derived later without guessing which rate was in force.
--
-- There is NO ON DELETE CASCADE. UserDeletionService deletes a user's children explicitly, in
-- order, and goals are one of them.
--
-- Idempotent: safe to run against a database that already has the table.

CREATE TABLE IF NOT EXISTS `goals` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `client_id` varchar(36) DEFAULT NULL,
  `type` varchar(20) NOT NULL,
  `name` varchar(50) NOT NULL,
  `icon` varchar(30) NOT NULL,
  `config` text NOT NULL,
  `current_balance` decimal(12,2) NOT NULL DEFAULT '0.00',
  `balance_updated_at` datetime(6) DEFAULT NULL,
  `anchor_balance` decimal(12,2) NOT NULL DEFAULT '0.00',
  `anchor_monthly` decimal(10,2) NOT NULL DEFAULT '0.00',
  `plan_rate` decimal(5,4) NOT NULL,
  `plan_rate_kind` varchar(10) NOT NULL,
  `plan_anchored_at` datetime(6) NOT NULL,
  `completed` bit(1) NOT NULL DEFAULT b'0',
  `sort_order` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_goals_user_client` (`user_id`, `client_id`),
  KEY `ix_goals_user` (`user_id`),
  CONSTRAINT `fk_goals_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
