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
-- schedule_paused and schedule_resumed are emitted by BOTH actors, so every transition column here
-- filters on initiator = 'user'. Status and holds are orthogonal: a user pause moves the status
-- (ACTIVE <-> PAUSED_BY_USER) and emits initiator=user, while a bank change sets hold_reason and
-- emits initiator=system, leaving the status alone. One real restart can therefore produce a
-- system resume from AchRelationshipService and a user resume from InvestmentScheduleService; the
-- initiator filter is what keeps the system half out of churn.
--
-- Churn and net_user_change are about INTENT, so they pair with active_by_intent_at_month_end and
-- not with active_investing_at_month_end: a schedule stopped by a hold has not churned, and its
-- owner has not left.
--
-- The *_by_user columns are the ones to read as churn. The totals are there so the two can be
-- reconciled, and so a surge of system pauses (a Plaid outage, say) is visible rather than hidden
-- inside a churn number.
--
-- A schedule being CREATED paused emits nothing: that is how a schedule is born, not a pause, and
-- investment_schedule_created already records it. Counting it would make every new user look like
-- immediate churn.
--
-- WHAT IS STILL TRUE OF THE SNAPSHOT COLUMNS. active_by_intent_at_month_end,
-- active_investing_at_month_end, held_at_month_end and schedules_existing_at_month_end read the
-- CURRENT schedules table, so they are accurate for the month in progress and approximate for
-- history: they cannot reconstruct what the status or the hold was back then, and a cancelled
-- schedule has left the table entirely, taking its creation with it.
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
    --
    -- "Active" has TWO honest meanings once holds exist, so both are published rather than one
    -- being picked. Read active_by_intent for anything about users leaving, and active_investing
    -- for anything about money actually moving. See metrics.md.
    --
    -- Intent: the user wants this schedule running. Holds are ignored on purpose, because a hold is
    -- an operational stop and not churn: a Plaid outage or a routine bank change must not read as
    -- users leaving. This is the column the transition counts above pair with.
    (SELECT COUNT(*) FROM investment_schedules s
      WHERE s.created_at < m.month_start + INTERVAL 1 MONTH
        AND s.status = 'ACTIVE') AS active_by_intent_at_month_end,

    -- Reality: the schedule would actually invest. Mirrors BLOCKING_HOLDS in
    -- InvestmentSchedule.isReadyForInvestment, which deliberately does NOT include BANK_CHANGE: a
    -- bank change already stops investing by clearing ach_request_id, so listing it here as well
    -- would be the same stop counted twice. If BLOCKING_HOLDS changes, change this list with it;
    -- ScheduleChurnViewTest pins the pairing.
    (SELECT COUNT(*) FROM investment_schedules s
      WHERE s.created_at < m.month_start + INTERVAL 1 MONTH
        AND s.status = 'ACTIVE'
        AND (s.hold_reason IS NULL
             OR s.hold_reason NOT IN ('ACH_RETURN_UNAUTHORIZED', 'ACH_RETURN_ACCOUNT',
                                      'MANUAL_REVIEW'))) AS active_investing_at_month_end,

    -- Held but not paused: the status says the user still wants it, a system hold is on it. ANY
    -- hold, blocking or not, so the operational state is visible and the arithmetic reconciles.
    -- The gap between the two columns above is the blocking subset of this one.
    (SELECT COUNT(*) FROM investment_schedules s
      WHERE s.created_at < m.month_start + INTERVAL 1 MONTH
        AND s.status = 'ACTIVE'
        AND s.hold_reason IS NOT NULL) AS held_at_month_end,
    (SELECT COUNT(*) FROM investment_schedules s
      WHERE s.created_at < m.month_start + INTERVAL 1 MONTH) AS schedules_existing_at_month_end
FROM months m
ORDER BY m.month_start;
