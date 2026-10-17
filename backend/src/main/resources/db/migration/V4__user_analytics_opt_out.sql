-- Server-side analytics consent.
--
-- The client used to decide this locally, which meant consent lived on one device and could not be
-- honoured by anything server side. The server now owns it: the login payload carries the value,
-- the client gate reads it instead of a local default, and the settings toggle writes it back.
--
-- Defaults to 1 (opted OUT), so a user who has never been asked is not measured, and so a bug that
-- fails to read this value fails closed. Column type matches the tlh_enabled precedent: MySQL
-- BOOLEAN is TINYINT(1), which is what the entity's columnDefinition maps to.

ALTER TABLE users ADD COLUMN analytics_opt_out TINYINT(1) NOT NULL DEFAULT 1;
