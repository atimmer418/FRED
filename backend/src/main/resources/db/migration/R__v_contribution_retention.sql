-- THE HEADLINE. Do people keep contributing after the first time?
--
-- For each signup-week cohort, the share of FUNDED users who had at least one settled contribution
-- in month 1, 2, 3 and 6 after their own first transfer. A month is a 30-day window measured from
-- that user's first transfer, not a calendar month, so every user is measured on the same clock
-- regardless of when in the month they joined.
--
--   month 1 = (first_transfer, first_transfer + 30 days]
--   month 2 = (first_transfer + 30, first_transfer + 60]
--   month 3 = (first_transfer + 60, first_transfer + 90]
--   month 6 = (first_transfer + 150, first_transfer + 180]
--
-- The first transfer itself is at offset 0 and is deliberately OUTSIDE every window: retention
-- means coming back, and counting the transfer that defines the cohort would report 100% in month
-- one forever.
--
-- THE TRAP THIS VIEW AVOIDS. A user who funded ten days ago cannot possibly have a month-6
-- contribution yet. Counting them in the denominator would drag every recent cohort's month-6
-- retention toward zero and make it look like churn. Each month therefore has its OWN denominator:
-- only users whose window has fully elapsed are eligible. Both numbers are exposed, so a low rate
-- can be told apart from a small sample, and eligible_mN = 0 is how you know a cohort is simply
-- too young to answer.
--
-- Contributions are counted per TRANSFER, not per execution row: one ACH transfer can fund several
-- executions at once, and counting rows would turn one contribution into several.

CREATE OR REPLACE VIEW v_contribution_retention AS
WITH cohort AS (
    SELECT
        u.id AS user_id,
        DATE(DATE_SUB(u.create_date, INTERVAL WEEKDAY(u.create_date) DAY)) AS cohort_week
    FROM users u
    WHERE u.create_date IS NOT NULL
),
settled AS (
    -- One row per settled ACH transfer per user. The transfer id is REQUIRED, not coalesced: an
    -- investment paid from cash already at the broker (the buying-power path) also sets
    -- funding_completed_at, with no ACH and no money leaving a bank. Counting those would inflate
    -- the headline retention number with contributions nobody made from their bank, and would
    -- disagree with the ach_transfer_completed events, which exclude them.
    SELECT
        e.user_id,
        e.alpaca_transfer_id AS transfer_key,
        MIN(e.funding_completed_at) AS settled_at
    FROM investment_executions e
    WHERE e.funding_completed_at IS NOT NULL
      AND e.alpaca_transfer_id IS NOT NULL
    GROUP BY e.user_id, e.alpaca_transfer_id
),
first_transfer AS (
    SELECT user_id, MIN(settled_at) AS first_funded_at
    FROM settled
    GROUP BY user_id
),
funded_users AS (
    SELECT c.cohort_week, f.user_id, f.first_funded_at
    FROM cohort c
    JOIN first_transfer f ON f.user_id = c.user_id
),
per_user AS (
    SELECT
        fu.cohort_week,
        fu.user_id,
        -- Has the window closed yet? Only then is this user evidence either way.
        CASE WHEN fu.first_funded_at + INTERVAL 30  DAY <= NOW() THEN 1 ELSE 0 END AS eligible_m1,
        CASE WHEN fu.first_funded_at + INTERVAL 60  DAY <= NOW() THEN 1 ELSE 0 END AS eligible_m2,
        CASE WHEN fu.first_funded_at + INTERVAL 90  DAY <= NOW() THEN 1 ELSE 0 END AS eligible_m3,
        CASE WHEN fu.first_funded_at + INTERVAL 180 DAY <= NOW() THEN 1 ELSE 0 END AS eligible_m6,
        CASE WHEN EXISTS (SELECT 1 FROM settled s WHERE s.user_id = fu.user_id
                          AND s.settled_at >  fu.first_funded_at
                          AND s.settled_at <= fu.first_funded_at + INTERVAL 30 DAY)
             THEN 1 ELSE 0 END AS retained_m1,
        CASE WHEN EXISTS (SELECT 1 FROM settled s WHERE s.user_id = fu.user_id
                          AND s.settled_at >  fu.first_funded_at + INTERVAL 30 DAY
                          AND s.settled_at <= fu.first_funded_at + INTERVAL 60 DAY)
             THEN 1 ELSE 0 END AS retained_m2,
        CASE WHEN EXISTS (SELECT 1 FROM settled s WHERE s.user_id = fu.user_id
                          AND s.settled_at >  fu.first_funded_at + INTERVAL 60 DAY
                          AND s.settled_at <= fu.first_funded_at + INTERVAL 90 DAY)
             THEN 1 ELSE 0 END AS retained_m3,
        CASE WHEN EXISTS (SELECT 1 FROM settled s WHERE s.user_id = fu.user_id
                          AND s.settled_at >  fu.first_funded_at + INTERVAL 150 DAY
                          AND s.settled_at <= fu.first_funded_at + INTERVAL 180 DAY)
             THEN 1 ELSE 0 END AS retained_m6
    FROM funded_users fu
)
SELECT
    cohort_week,
    COUNT(*) AS funded_users,
    SUM(eligible_m1) AS eligible_m1,
    SUM(eligible_m2) AS eligible_m2,
    SUM(eligible_m3) AS eligible_m3,
    SUM(eligible_m6) AS eligible_m6,
    -- A user can only be retained if their window closed, so the numerators are gated too.
    SUM(retained_m1 * eligible_m1) AS retained_m1,
    SUM(retained_m2 * eligible_m2) AS retained_m2,
    SUM(retained_m3 * eligible_m3) AS retained_m3,
    SUM(retained_m6 * eligible_m6) AS retained_m6,
    COALESCE(ROUND(100.0 * SUM(retained_m1 * eligible_m1) / NULLIF(SUM(eligible_m1), 0), 1), 0) AS pct_retained_m1,
    COALESCE(ROUND(100.0 * SUM(retained_m2 * eligible_m2) / NULLIF(SUM(eligible_m2), 0), 1), 0) AS pct_retained_m2,
    COALESCE(ROUND(100.0 * SUM(retained_m3 * eligible_m3) / NULLIF(SUM(eligible_m3), 0), 1), 0) AS pct_retained_m3,
    COALESCE(ROUND(100.0 * SUM(retained_m6 * eligible_m6) / NULLIF(SUM(eligible_m6), 0), 1), 0) AS pct_retained_m6
FROM per_user
GROUP BY cohort_week;
