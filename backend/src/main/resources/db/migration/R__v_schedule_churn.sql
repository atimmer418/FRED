-- Recurring schedules, by calendar month.
--
-- READ THIS BEFORE USING THE VIEW. Only two of the four numbers asked for can honestly be produced
-- from the schema as it stands, and inventing the other two would be worse than leaving them out.
--
-- What EXISTS: investment_schedules holds one CURRENT row per user (there is a unique key on
-- user_id), with created_at, updated_at and an is_paused flag. That is a snapshot, not a history.
--
-- What therefore CANNOT be counted today:
--   * paused    — nothing records WHEN a schedule was paused, or how often. Worse, a schedule is
--                 created paused on purpose ("always start paused until ACH is confirmed"), so
--                 is_paused = 1 mostly means "not funded yet", not "the user paused it".
--   * resumed   — nothing records a transition at all.
--   * cancelled — cancellation is a row DELETE, which leaves nothing behind to count. Deletion
--                 also happens for an unrelated reason: a rejected KYC deletes the user's
--                 schedules along with the user.
--
-- Fixing this needs either three new server events (schedule_paused, schedule_resumed,
-- schedule_cancelled) written to the outbox at the transitions, or a state-transition table. Both
-- are decisions about the taxonomy, not something to bolt on inside a view, so this view reports
-- what is true and the gap is documented in FREDdocs/metrics.md.
--
-- active_at_month_end is a point-in-time read of the CURRENT snapshot, so it is only accurate for
-- the month in progress. Historical months report the schedules that existed then and are still
-- unpaused NOW; it cannot reconstruct what the flag was back then. Treat the current month as the
-- live number and earlier months as "still active today, created by then".

CREATE OR REPLACE VIEW v_schedule_churn AS
WITH months AS (
    SELECT DISTINCT DATE(DATE_FORMAT(s.created_at, '%Y-%m-01')) AS month_start
    FROM investment_schedules s
    WHERE s.created_at IS NOT NULL
),
created AS (
    SELECT
        DATE(DATE_FORMAT(s.created_at, '%Y-%m-01')) AS month_start,
        COUNT(*) AS schedules_created
    FROM investment_schedules s
    WHERE s.created_at IS NOT NULL
    GROUP BY DATE(DATE_FORMAT(s.created_at, '%Y-%m-01'))
)
SELECT
    m.month_start,
    COALESCE(c.schedules_created, 0) AS schedules_created,
    -- Created on or before the end of this month and not paused as the table stands now.
    (SELECT COUNT(*)
       FROM investment_schedules s
      WHERE s.created_at < m.month_start + INTERVAL 1 MONTH
        AND s.is_paused = 0) AS active_at_month_end,
    (SELECT COUNT(*)
       FROM investment_schedules s
      WHERE s.created_at < m.month_start + INTERVAL 1 MONTH) AS schedules_existing_at_month_end
FROM months m
LEFT JOIN created c ON c.month_start = m.month_start
ORDER BY m.month_start;
