-- Recurring schedules, by calendar month: are they accumulating or leaking?
--
-- This view used to report only what the schedules table could answer, because that table holds
-- ONE CURRENT ROW per user with a flag rather than a history: nothing recorded when a schedule was
-- paused, a schedule is created paused on purpose, and a cancellation is a row delete that leaves
-- nothing behind. Pause, resume and cancel are now recorded as events in analytics_outbox at the
-- moment the state actually changes, in the same transaction as the change, so they can be counted
-- honestly here.
--
-- CHURN IS NOT LIFECYCLE, and the split is the point of this view. Each event carries why it
-- happened:
--   user_request    the user pressed pause or resume                            -> CHURN
--   account_closed  the user closed their account                                -> CHURN
--   kyc_rejected    the broker rejected them and we deleted everything           -> not their doing
--
-- schedule_paused and schedule_resumed are now USER-ONLY BY CONSTRUCTION. Under the status model a
-- bank change moves a schedule to PENDING_BANK and the ACH landing moves it to ACTIVE; neither is
-- a pause or a resume, and neither emits. The only system event left is a kyc_rejected
-- cancellation. The *_by_user columns are kept because cancellations still split two ways, and so
-- a future system pause, if one is ever introduced, cannot silently land in the churn number.
-- The *_by_user columns are the ones to read as churn. The totals are there so the two can be
-- reconciled, and so a surge of system pauses (a Plaid outage, say) is visible rather than hidden
-- inside a churn number.
--
-- A schedule being CREATED paused emits nothing: that is how a schedule is born, not a pause, and
-- investment_schedule_created already records it. Counting it would make every new user look like
-- immediate churn.
--
-- WHAT IS STILL TRUE OF THE SNAPSHOT COLUMNS. active_at_month_end and
-- schedules_existing_at_month_end read the CURRENT schedules table, so they are accurate for the
-- month in progress and approximate for history: they cannot reconstruct what the pause flag was
-- back then, and a cancelled schedule has left the table entirely, taking its creation with it.
-- The event columns do not have that problem, because an event is a fact about a moment. Prefer
-- them when comparing months.

CREATE OR REPLACE VIEW v_schedule_churn AS
WITH months AS (
    SELECT DATE(DATE_FORMAT(s.created_at, '%Y-%m-01')) AS month_start
    FROM investment_schedules s
    WHERE s.created_at IS NOT NULL
    UNION
    SELECT DATE(DATE_FORMAT(o.occurred_at, '%Y-%m-01'))
    FROM analytics_outbox o
    WHERE o.event_name IN ('investment_schedule_created', 'schedule_paused',
                           'schedule_resumed', 'schedule_cancelled')
),
events AS (
    SELECT
        DATE(DATE_FORMAT(o.occurred_at, '%Y-%m-01')) AS month_start,
        o.event_name,
        JSON_UNQUOTE(JSON_EXTRACT(o.properties, '$.initiator')) AS initiator,
        JSON_UNQUOTE(JSON_EXTRACT(o.properties, '$.reason'))    AS reason
    FROM analytics_outbox o
    WHERE o.event_name IN ('schedule_paused', 'schedule_resumed', 'schedule_cancelled')
)
SELECT
    m.month_start,
    -- Created, from the schedules table. See the caveat above: a cancelled schedule's creation
    -- leaves with it, so this can shrink for a past month.
    (SELECT COUNT(*) FROM investment_schedules s
      WHERE DATE(DATE_FORMAT(s.created_at, '%Y-%m-01')) = m.month_start) AS schedules_created,

    -- Transitions, from the events. These are facts about a moment and do not change afterwards.
    (SELECT COUNT(*) FROM events e
      WHERE e.month_start = m.month_start AND e.event_name = 'schedule_paused') AS paused,
    (SELECT COUNT(*) FROM events e
      WHERE e.month_start = m.month_start AND e.event_name = 'schedule_paused'
        AND e.initiator = 'user') AS paused_by_user,
    (SELECT COUNT(*) FROM events e
      WHERE e.month_start = m.month_start AND e.event_name = 'schedule_resumed') AS resumed,
    (SELECT COUNT(*) FROM events e
      WHERE e.month_start = m.month_start AND e.event_name = 'schedule_resumed'
        AND e.initiator = 'user') AS resumed_by_user,
    (SELECT COUNT(*) FROM events e
      WHERE e.month_start = m.month_start AND e.event_name = 'schedule_cancelled') AS cancelled,
    (SELECT COUNT(*) FROM events e
      WHERE e.month_start = m.month_start AND e.event_name = 'schedule_cancelled'
        AND e.initiator = 'user') AS cancelled_by_user,

    -- Net user-driven movement in the month: resumes minus pauses and cancellations. Negative is
    -- a month where more people stopped than restarted.
    (SELECT COUNT(*) FROM events e
      WHERE e.month_start = m.month_start AND e.event_name = 'schedule_resumed'
        AND e.initiator = 'user')
    - (SELECT COUNT(*) FROM events e
        WHERE e.month_start = m.month_start AND e.initiator = 'user'
          AND e.event_name IN ('schedule_paused', 'schedule_cancelled')) AS net_user_change,

    -- Snapshot columns. Accurate for the month in progress, approximate for history.
    (SELECT COUNT(*) FROM investment_schedules s
      WHERE s.created_at < m.month_start + INTERVAL 1 MONTH
        AND s.status = 'ACTIVE') AS active_at_month_end,
    (SELECT COUNT(*) FROM investment_schedules s
      WHERE s.created_at < m.month_start + INTERVAL 1 MONTH) AS schedules_existing_at_month_end
FROM months m
ORDER BY m.month_start;
