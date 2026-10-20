-- How much of PostHog is missing, by signup week.
--
-- Every opted-out user is a person whose behaviour PostHog never sees. This view is what turns
-- "the funnel says 40%" into "the funnel says 40% of the 85% we can see", and it is the reason the
-- money numbers live in MySQL rather than in the vendor: this database has everyone.
--
-- Consent defaults to opted IN for a new user, so a high opt-out share is a signal worth acting
-- on, not background noise.
--
-- The same deletion caveat as everywhere else: a rejected KYC removes the user row, so rejected
-- users are in neither the numerator nor the denominator here.

CREATE OR REPLACE VIEW v_consent AS
SELECT
    DATE(DATE_SUB(u.create_date, INTERVAL WEEKDAY(u.create_date) DAY)) AS cohort_week,
    COUNT(*) AS users,
    SUM(CASE WHEN u.analytics_opt_out = 1 THEN 1 ELSE 0 END) AS opted_out,
    SUM(CASE WHEN u.analytics_opt_out = 1 THEN 0 ELSE 1 END) AS opted_in,
    COALESCE(ROUND(100.0 * SUM(CASE WHEN u.analytics_opt_out = 1 THEN 1 ELSE 0 END)
                   / NULLIF(COUNT(*), 0), 1), 0) AS pct_opted_out
FROM users u
WHERE u.create_date IS NOT NULL
GROUP BY DATE(DATE_SUB(u.create_date, INTERVAL WEEKDAY(u.create_date) DAY));
