-- Server-side analytics consent.
--
-- The client used to decide this locally, which meant consent lived on one device and could not be
-- honoured by anything server side. The server now owns it: the login payload carries the value,
-- the client gate reads it instead of a local default, and the settings toggle writes it back.
--
-- Defaults to 0 (opted IN): new users are measured unless they turn it off in Settings. An opt-in
-- default would mean most users never turn it on, so every funnel would be built from a small
-- self-selected group, which is worse than useless for finding where people drop out. The toggle
-- and the privacy disclosure are what make that fair; see FREDdocs/analytics-events.md.
--
-- Note this is the default for a NEW ROW only. The client still treats consent as unknown, and
-- therefore opted out, until the session payload tells it otherwise, so a failure to read consent
-- still fails closed.
--
-- Column type matches the tlh_enabled precedent: MySQL BOOLEAN is TINYINT(1), which is what the
-- entity's columnDefinition maps to.

ALTER TABLE users ADD COLUMN analytics_opt_out TINYINT(1) NOT NULL DEFAULT 0;
