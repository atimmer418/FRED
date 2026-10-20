-- Broker decisions, by calendar month.
--
-- THIS VIEW READS THE OUTBOX, and it is the one place that has to. A rejected KYC hard-deletes the
-- user row, so rejections are invisible in `users` by the time anyone asks. The outbox row has no
-- foreign key and denormalises nothing identifying, so it survives the deletion and remains the
-- only record that the rejection happened at all.
--
-- Submissions are counted in the month they were submitted; decisions in the month they were
-- decided. The two will not balance within a month, and should not: a submission late in a month
-- is usually decided in the next one.
--
-- Days to decision is computed from the outbox's own occurred_at timestamps rather than from the
-- banded day property on the event, because a band cannot be turned back into a median. It pairs
-- each decision with that user's most recent prior submission.
--
-- SUPPRESSED and PENDING rows are included. This is our ledger, not PostHog's: a user who opted
-- out of analytics still had their KYC decided, and the compliance funnel must not develop holes
-- because of a marketing preference. Nothing identifying leaves this view either way.

CREATE OR REPLACE VIEW v_kyc_outcomes AS
WITH submissions AS (
    SELECT o.user_id, o.occurred_at
    FROM analytics_outbox o
    WHERE o.event_name = 'kyc_submitted'
),
decisions AS (
    SELECT
        o.user_id,
        o.occurred_at,
        JSON_UNQUOTE(JSON_EXTRACT(o.properties, '$.outcome')) AS outcome
    FROM analytics_outbox o
    WHERE o.event_name = 'kyc_decided'
),
decided_with_lag AS (
    SELECT
        d.user_id,
        d.occurred_at,
        d.outcome,
        TIMESTAMPDIFF(DAY,
            (SELECT MAX(s.occurred_at) FROM submissions s
              WHERE s.user_id = d.user_id AND s.occurred_at <= d.occurred_at),
            d.occurred_at) AS days_to_decision
    FROM decisions d
),
ranked AS (
    SELECT
        DATE(DATE_FORMAT(occurred_at, '%Y-%m-01')) AS month_start,
        days_to_decision,
        ROW_NUMBER() OVER (PARTITION BY DATE(DATE_FORMAT(occurred_at, '%Y-%m-01'))
                           ORDER BY days_to_decision) AS rn,
        COUNT(*) OVER (PARTITION BY DATE(DATE_FORMAT(occurred_at, '%Y-%m-01'))) AS decided_with_lag_count
    FROM decided_with_lag
    WHERE days_to_decision IS NOT NULL
),
median AS (
    SELECT
        month_start,
        AVG(CASE WHEN rn IN (FLOOR((decided_with_lag_count + 1) / 2),
                             CEIL((decided_with_lag_count + 1) / 2))
                 THEN days_to_decision END) AS median_days_to_decision
    FROM ranked
    GROUP BY month_start, decided_with_lag_count
),
months AS (
    SELECT DATE(DATE_FORMAT(occurred_at, '%Y-%m-01')) AS month_start FROM submissions
    UNION
    SELECT DATE(DATE_FORMAT(occurred_at, '%Y-%m-01')) FROM decisions
)
SELECT
    m.month_start,
    (SELECT COUNT(*) FROM submissions s
      WHERE DATE(DATE_FORMAT(s.occurred_at, '%Y-%m-01')) = m.month_start) AS submissions,
    (SELECT COUNT(*) FROM decisions d
      WHERE DATE(DATE_FORMAT(d.occurred_at, '%Y-%m-01')) = m.month_start) AS decisions,
    (SELECT COUNT(*) FROM decisions d
      WHERE DATE(DATE_FORMAT(d.occurred_at, '%Y-%m-01')) = m.month_start
        AND d.outcome = 'approved') AS approved,
    (SELECT COUNT(*) FROM decisions d
      WHERE DATE(DATE_FORMAT(d.occurred_at, '%Y-%m-01')) = m.month_start
        AND d.outcome = 'rejected') AS rejected,
    (SELECT COUNT(*) FROM decisions d
      WHERE DATE(DATE_FORMAT(d.occurred_at, '%Y-%m-01')) = m.month_start
        AND d.outcome = 'needs_more_info') AS needs_more_info,
    -- So the three buckets plus this one always add up to `decisions`. A decision whose outcome is
    -- missing or is a value we have not seen before would otherwise count in the total and appear
    -- in none of the breakdowns, which reads as arithmetic that does not work.
    (SELECT COUNT(*) FROM decisions d
      WHERE DATE(DATE_FORMAT(d.occurred_at, '%Y-%m-01')) = m.month_start
        AND (d.outcome IS NULL
             OR d.outcome NOT IN ('approved', 'rejected', 'needs_more_info'))) AS other_outcome,
    med.median_days_to_decision
FROM months m
LEFT JOIN median med ON med.month_start = m.month_start
ORDER BY m.month_start;
