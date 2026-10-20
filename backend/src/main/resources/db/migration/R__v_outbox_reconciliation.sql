-- Weekly reconciliation: what we recorded against what we managed to publish.
--
-- The outbox is the source of truth; PostHog is a copy. This view is how you find out when the
-- copy has silently drifted, without having to trust the copy to tell you.
--
-- It reports, per week and per event, how many rows we WROTE and how many of those reached the
-- vendor, split by why the rest did not: suppressed (the user had opted out, which is correct and
-- expected), parked (a human needs to look), or still pending (in flight, or the publisher is off
-- or broken).
--
-- HOW TO USE IT. sent_count here is the number PostHog should be able to show for that week and
-- event name. Run the matching query against PostHog for the same window and compare. A gap in
-- either direction is a real problem:
--   PostHog LOWER than sent_count   — events were accepted by us and lost in transit, or the
--                                     vendor dropped them. The uuid on each row means a resend is
--                                     safe; see FREDdocs/metrics.md.
--   PostHog HIGHER than sent_count  — something is emitting outside the outbox, which means a
--                                     second source of truth has appeared and the two will
--                                     disagree forever.
--
-- pending_count is the one to watch day to day: a number that grows week on week means the
-- publisher is not draining, and every hour it stays up is latency on the numbers below it.
--
-- TWO DELIVERY RATES, and the difference between them matters. pct_of_sendable is the real health
-- signal: it excludes suppressed rows, because an opted-out user is a row we were never going to
-- send and a cohort with 20% opt-out would otherwise read as 80% delivery with nothing wrong at
-- all. pct_of_recorded keeps the raw share for anyone reconciling against the total.
--
-- other_count exists so the buckets always add up. Without it a status outside the four known ones
-- would sit in recorded_count, depress the rate, and appear in no column, which is the kind of
-- discrepancy that costs an afternoon to find.

CREATE OR REPLACE VIEW v_outbox_reconciliation AS
SELECT
    DATE(DATE_SUB(o.occurred_at, INTERVAL WEEKDAY(o.occurred_at) DAY)) AS week_start,
    o.event_name,
    COUNT(*) AS recorded_count,
    SUM(CASE WHEN o.status = 'SENT'       THEN 1 ELSE 0 END) AS sent_count,
    SUM(CASE WHEN o.status = 'SUPPRESSED' THEN 1 ELSE 0 END) AS suppressed_count,
    SUM(CASE WHEN o.status = 'PARKED'     THEN 1 ELSE 0 END) AS parked_count,
    SUM(CASE WHEN o.status = 'PENDING'    THEN 1 ELSE 0 END) AS pending_count,
    SUM(CASE WHEN o.status NOT IN ('SENT', 'SUPPRESSED', 'PARKED', 'PENDING')
             THEN 1 ELSE 0 END) AS other_count,
    -- The health signal: of the rows we were actually going to send, how many landed.
    COALESCE(ROUND(100.0 * SUM(CASE WHEN o.status = 'SENT' THEN 1 ELSE 0 END)
                   / NULLIF(SUM(CASE WHEN o.status = 'SUPPRESSED' THEN 0 ELSE 1 END), 0), 1), 0)
        AS pct_of_sendable,
    -- The raw share, including rows we correctly never sent.
    COALESCE(ROUND(100.0 * SUM(CASE WHEN o.status = 'SENT' THEN 1 ELSE 0 END)
                   / NULLIF(COUNT(*), 0), 1), 0) AS pct_of_recorded,
    MAX(o.attempts) AS max_attempts
FROM analytics_outbox o
GROUP BY DATE(DATE_SUB(o.occurred_at, INTERVAL WEEKDAY(o.occurred_at) DAY)), o.event_name
ORDER BY week_start, event_name;
