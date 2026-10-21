-- Deletion requests to send to the analytics vendor.
--
-- Closing an account deletes the user from our database, but PostHog keeps its own copy of that
-- person: the opaque public_id and the events attached to it. Until this existed, "we deleted your
-- account" was only true of our side, and FREDdocs/what-we-send.md had to say so.
--
-- Shaped like analytics_outbox and drained by the same publisher pass, for the same reason: the
-- row is written in the SAME transaction as the account closure, so a deletion we promised cannot
-- be lost by a failed network call, and a closure that rolls back does not leave a request to
-- delete a person who still exists.
--
-- Two deliberate differences from the outbox:
--
--   * There is NO consent check. A deletion is an obligation, not analytics. An opted-out user
--     may well have a person record from before they opted out, and that is exactly the record
--     that has to go.
--   * public_id is the only thing stored. By the time this is sent, the user row is gone, so
--     there is nothing to join to and nothing else worth keeping.
--
-- Rows are kept after completion as the record that the request was made and honoured, which is
-- the thing anyone asking "did you actually delete it" needs to see.
--
-- Idempotent: safe to run against a database that already has the table.

CREATE TABLE IF NOT EXISTS `analytics_person_deletions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_id` varchar(36) NOT NULL,
  `requested_at` datetime(6) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `completed_at` datetime(6) DEFAULT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(6) DEFAULT NULL,
  `last_error` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_person_deletion_public_id` (`public_id`),
  KEY `ix_person_deletion_pending` (`status`, `next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
