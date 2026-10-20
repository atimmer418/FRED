-- How long it takes a cohort to get money in, by signup week.
--
-- Measured from signup to the FIRST SETTLED transfer. Settled, not initiated: an ACH that was
-- started and failed is not money in, and counting it would make the funnel look faster than the
-- bank actually is.
--
-- MySQL has no percentile function, so the median and p75 are computed the long way, by ranking
-- each cohort's day counts and picking the middle (or the average of the two middles, for an even
-- count). That is the textbook definition rather than an approximation.
--
-- Users who have not funded are absent from the day counts entirely rather than counted as a large
-- number: a user who signed up yesterday and has not funded is not evidence of a slow funnel. The
-- funded and signed_up counts are both exposed so the medians can be read against how many people
-- they describe.

CREATE OR REPLACE VIEW v_time_to_funded AS
WITH cohort AS (
    SELECT
        u.id AS user_id,
        DATE(DATE_SUB(u.create_date, INTERVAL WEEKDAY(u.create_date) DAY)) AS cohort_week,
        u.create_date
    FROM users u
    WHERE u.create_date IS NOT NULL
),
first_transfer AS (
    -- An ACH that settled. The transfer id is required: the buying-power path also sets
    -- funding_completed_at, for an investment paid from cash already at the broker, where no
    -- money left a bank at all. first_ach_transfer_completed excludes it and so does this.
    SELECT e.user_id, MIN(e.funding_completed_at) AS first_funded_at
    FROM investment_executions e
    WHERE e.funding_completed_at IS NOT NULL
      AND e.alpaca_transfer_id IS NOT NULL
    GROUP BY e.user_id
),
funded_days AS (
    SELECT
        c.cohort_week,
        c.user_id,
        TIMESTAMPDIFF(DAY, c.create_date, f.first_funded_at) AS days_to_funded
    FROM cohort c
    JOIN first_transfer f ON f.user_id = c.user_id
    -- A transfer that predates the signup is a data repair or a recreated row, not a user who
    -- funded before they existed. Left in, its negative day count satisfies every <= bound and
    -- silently drags the median down.
    WHERE f.first_funded_at >= c.create_date
),
ranked AS (
    SELECT
        cohort_week,
        days_to_funded,
        ROW_NUMBER() OVER (PARTITION BY cohort_week ORDER BY days_to_funded) AS rn,
        COUNT(*)     OVER (PARTITION BY cohort_week)                         AS funded_count
    FROM funded_days
),
percentiles AS (
    SELECT
        cohort_week,
        funded_count,
        -- Median: the middle value, or the mean of the two middle values.
        AVG(CASE WHEN rn IN (FLOOR((funded_count + 1) / 2), CEIL((funded_count + 1) / 2))
                 THEN days_to_funded END) AS median_days_to_funded,
        -- p75: the smallest value at or above three quarters of the way through.
        MAX(CASE WHEN rn = GREATEST(1, CEIL(funded_count * 0.75))
                 THEN days_to_funded END) AS p75_days_to_funded
    FROM ranked
    GROUP BY cohort_week, funded_count
),
cohort_totals AS (
    SELECT
        c.cohort_week,
        COUNT(*) AS signed_up,
        SUM(CASE WHEN f.first_funded_at >= c.create_date THEN 1 ELSE 0 END) AS funded,
        SUM(CASE WHEN f.first_funded_at >= c.create_date
                  AND TIMESTAMPDIFF(DAY, c.create_date, f.first_funded_at) <= 7
                 THEN 1 ELSE 0 END) AS funded_within_7d,
        SUM(CASE WHEN f.first_funded_at >= c.create_date
                  AND TIMESTAMPDIFF(DAY, c.create_date, f.first_funded_at) <= 14
                 THEN 1 ELSE 0 END) AS funded_within_14d,
        SUM(CASE WHEN f.first_funded_at >= c.create_date
                  AND TIMESTAMPDIFF(DAY, c.create_date, f.first_funded_at) <= 30
                 THEN 1 ELSE 0 END) AS funded_within_30d
    FROM cohort c
    LEFT JOIN first_transfer f ON f.user_id = c.user_id
    GROUP BY c.cohort_week
)
SELECT
    t.cohort_week,
    t.signed_up,
    t.funded,
    -- NULL, not 0, when nobody in the cohort has funded: there is no middle value of an empty set,
    -- and a 0 here would read as "they funded the same day".
    p.median_days_to_funded,
    p.p75_days_to_funded,
    t.funded_within_7d,
    t.funded_within_14d,
    t.funded_within_30d,
    -- Shares are of everyone who signed up, so they can be compared across cohorts of any size.
    -- 0.0 rather than NULL, because "none of them funded in a week" is a real answer.
    COALESCE(ROUND(100.0 * t.funded_within_7d  / NULLIF(t.signed_up, 0), 1), 0) AS pct_funded_within_7d,
    COALESCE(ROUND(100.0 * t.funded_within_14d / NULLIF(t.signed_up, 0), 1), 0) AS pct_funded_within_14d,
    COALESCE(ROUND(100.0 * t.funded_within_30d / NULLIF(t.signed_up, 0), 1), 0) AS pct_funded_within_30d
FROM cohort_totals t
LEFT JOIN percentiles p ON p.cohort_week = t.cohort_week;
