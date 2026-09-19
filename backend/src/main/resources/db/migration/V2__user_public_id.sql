-- Opaque per-user identifier for analytics.
--
-- users.id is a sequential BIGINT and users.email is PII that the user can change, so neither can
-- be the identity we send to a third-party analytics tool. public_id is a random UUID v4 with no
-- meaning outside our own database.
--
-- Existing rows are filled first, then the column is made NOT NULL, so the migration is safe on a
-- populated database. MySQL's UUID() is evaluated per row.

ALTER TABLE users ADD COLUMN public_id VARCHAR(36) NULL AFTER id;

UPDATE users SET public_id = UUID() WHERE public_id IS NULL;

ALTER TABLE users MODIFY COLUMN public_id VARCHAR(36) NOT NULL;

ALTER TABLE users ADD CONSTRAINT uq_users_public_id UNIQUE (public_id);
