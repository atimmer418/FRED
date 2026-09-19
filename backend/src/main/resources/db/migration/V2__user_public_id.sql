-- Opaque per-user identifier for analytics.
--
-- users.id is a sequential BIGINT and users.email is PII that the user can change, so neither can
-- be the identity we send to a third-party analytics tool. public_id is a random UUID v4 with no
-- meaning outside our own database.
--
-- The backfill builds v4 values from RANDOM_BYTES rather than MySQL's UUID(), which returns a
-- version-1 UUID carrying the database host's MAC address and a timestamp. Those are exactly the
-- properties this column exists to avoid, and they would have been visible to the analytics vendor.
--
-- The column stays NULLable here on purpose. Making it NOT NULL in the same release that teaches the
-- app to populate it would break inserts from the old container during a rolling deploy (and a user
-- registering mid-migration would fail the NOT NULL switch itself). A later migration tightens it
-- once this code is live everywhere. MySQL allows multiple NULLs under a UNIQUE key, so uniqueness
-- can be enforced from the start.

ALTER TABLE users ADD COLUMN public_id VARCHAR(36) NULL AFTER id;

UPDATE users
SET public_id = LOWER(CONCAT(
        HEX(RANDOM_BYTES(4)), '-',
        HEX(RANDOM_BYTES(2)), '-',
        '4', SUBSTR(HEX(RANDOM_BYTES(2)), 2, 3), '-',
        SUBSTR('89ab', 1 + FLOOR(RAND() * 4), 1), SUBSTR(HEX(RANDOM_BYTES(2)), 2, 3), '-',
        HEX(RANDOM_BYTES(6))))
WHERE public_id IS NULL;

ALTER TABLE users ADD CONSTRAINT uq_users_public_id UNIQUE (public_id);
