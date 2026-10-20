-- The read-only role for whoever runs reports.
--
-- THIS FILE IS NOT A FLYWAY MIGRATION, ON PURPOSE. It lives outside db/migration so Flyway never
-- picks it up, and it is run by hand, once per environment, by someone with admin rights.
--
-- Why not a migration: creating a role and granting privileges requires CREATE ROLE and GRANT
-- OPTION. The application's own database user would have to hold those for a migration to do this,
-- and an application that can mint database accounts is a much bigger problem than a manual step.
-- A managed MySQL may also refuse the statement outright, and a migration that cannot run is a
-- deployment that will not boot. So: version-controlled and reviewable, but deliberately manual.
--
-- The role is scoped to the seven views and NOTHING ELSE. It cannot read users, cannot read
-- analytics_outbox, and therefore cannot reach an email, a name, a public_id or an amount. The
-- views expose counts, rates, medians and bands only; that is the whole point of granting on
-- views rather than on tables.
--
-- Run as an admin, substituting your database name:
--
--     mysql -u <admin> -p <database> < metrics_reader_role.sql
--
-- Then create a person's account and give them the role (keep the password out of this file and
-- out of git):
--
--     CREATE USER 'alice_metrics'@'%' IDENTIFIED BY '<a password you generate>';
--     GRANT 'fred_metrics_reader' TO 'alice_metrics'@'%';
--     SET DEFAULT ROLE 'fred_metrics_reader' TO 'alice_metrics'@'%';
--
-- To take access away:
--
--     REVOKE 'fred_metrics_reader' FROM 'alice_metrics'@'%';
--     DROP USER 'alice_metrics'@'%';

CREATE ROLE IF NOT EXISTS 'fred_metrics_reader';

GRANT SELECT ON v_activation_funnel       TO 'fred_metrics_reader';
GRANT SELECT ON v_time_to_funded          TO 'fred_metrics_reader';
GRANT SELECT ON v_contribution_retention  TO 'fred_metrics_reader';
GRANT SELECT ON v_schedule_churn          TO 'fred_metrics_reader';
GRANT SELECT ON v_kyc_outcomes            TO 'fred_metrics_reader';
GRANT SELECT ON v_consent                 TO 'fred_metrics_reader';
GRANT SELECT ON v_outbox_reconciliation   TO 'fred_metrics_reader';

-- Deliberately NOT granted, and worth keeping that way: SELECT on any base table, SHOW VIEW (which
-- would expose the view definitions and with them the column names of the underlying tables),
-- PROCESS, and anything at all on *.*.
