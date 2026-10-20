-- Where people stop, by signup week.
--
-- Every step is "ever reached", not "reached in order": a user who linked a bank before their KYC
-- was approved counts for both. The funnel answers "how far did this cohort get", not "what order
-- did they do it in", and the real flow does let steps overlap.
--
-- SOURCES. Each step reads the most durable thing that can answer it, which is the operational
-- table rather than the event ledger wherever one exists. The outbox only has rows from the day it
-- shipped; users, schedules and executions have the whole history, so a funnel built on them is
-- correct for cohorts that predate the analytics work.
--
-- THE ONE BIAS YOU MUST KNOW ABOUT: a rejected KYC HARD-DELETES the user row
-- (AccountStatusService.handleRejected). Rejected users are therefore absent from every count
-- here, including signed_up. That makes the approval rate in this view look better than reality.
-- v_kyc_outcomes reads the outbox, whose rows survive the deletion, and is the honest place to
-- see rejections. Do not compute an approval rate from this view.
--
-- Rates are expressed against the PREVIOUS step, which is what "where do they drop" means, and
-- every rate ships with its numerator and denominator so a 0.0 can be told apart from "nobody got
-- that far to begin with".

CREATE OR REPLACE VIEW v_activation_funnel AS
WITH cohort AS (
    SELECT
        u.id AS user_id,
        DATE(DATE_SUB(u.create_date, INTERVAL WEEKDAY(u.create_date) DAY)) AS cohort_week
    FROM users u
    WHERE u.create_date IS NOT NULL
),
steps AS (
    SELECT
        c.cohort_week,
        CASE WHEN u.plan_id IS NOT NULL THEN 1 ELSE 0 END AS created_plan,
        CASE WHEN EXISTS (SELECT 1 FROM passkey_credentials p WHERE p.user_id = u.id)
             THEN 1 ELSE 0 END AS created_passkey,
        -- alpaca_account_id covers users who submitted before kyc_submitted_at existed.
        CASE WHEN u.kyc_submitted_at IS NOT NULL OR u.alpaca_account_id IS NOT NULL
             THEN 1 ELSE 0 END AS submitted_kyc,
        CASE WHEN u.account_status IN ('APPROVED', 'ACTIVE') THEN 1 ELSE 0 END AS approved,
        CASE WHEN u.plaid_access_token IS NOT NULL THEN 1 ELSE 0 END AS linked_bank,
        CASE WHEN EXISTS (SELECT 1 FROM investment_schedules s WHERE s.user_id = u.id)
             THEN 1 ELSE 0 END AS created_schedule,
        -- Settled, not initiated: funding_completed_at is the money actually landing. The
        -- transfer id is required as well, because an investment paid from cash ALREADY at the
        -- broker (the buying-power path) also sets funding_completed_at, with no ACH and no money
        -- leaving a bank. The server event first_ach_transfer_completed excludes that case for
        -- the same reason, and the two must agree or the weekly reconciliation shows a gap that
        -- is not a delivery failure.
        CASE WHEN EXISTS (SELECT 1 FROM investment_executions e
                          WHERE e.user_id = u.id
                            AND e.funding_completed_at IS NOT NULL
                            AND e.alpaca_transfer_id IS NOT NULL)
             THEN 1 ELSE 0 END AS first_transfer
    FROM cohort c
    JOIN users u ON u.id = c.user_id
)
SELECT
    cohort_week,
    COUNT(*)                  AS signed_up,
    SUM(created_plan)         AS created_plan,
    SUM(created_passkey)      AS created_passkey,
    SUM(submitted_kyc)        AS submitted_kyc,
    SUM(approved)             AS approved,
    SUM(linked_bank)          AS linked_bank,
    SUM(created_schedule)     AS created_schedule,
    SUM(first_transfer)       AS funded,
    -- Step-to-step conversion, each against the step before it. COALESCE so an empty step reads
    -- 0.0 rather than NULL; the counts above say whether that 0 is "nobody converted" or
    -- "nobody arrived".
    COALESCE(ROUND(100.0 * SUM(created_plan)     / NULLIF(COUNT(*), 0), 1), 0)                  AS pct_plan_of_signup,
    COALESCE(ROUND(100.0 * SUM(created_passkey)  / NULLIF(SUM(created_plan), 0), 1), 0)         AS pct_passkey_of_plan,
    COALESCE(ROUND(100.0 * SUM(submitted_kyc)    / NULLIF(SUM(created_passkey), 0), 1), 0)      AS pct_kyc_of_passkey,
    COALESCE(ROUND(100.0 * SUM(approved)         / NULLIF(SUM(submitted_kyc), 0), 1), 0)        AS pct_approved_of_kyc,
    COALESCE(ROUND(100.0 * SUM(linked_bank)      / NULLIF(SUM(approved), 0), 1), 0)             AS pct_bank_of_approved,
    COALESCE(ROUND(100.0 * SUM(created_schedule) / NULLIF(SUM(linked_bank), 0), 1), 0)          AS pct_schedule_of_bank,
    COALESCE(ROUND(100.0 * SUM(first_transfer)   / NULLIF(SUM(created_schedule), 0), 1), 0)     AS pct_funded_of_schedule,
    COALESCE(ROUND(100.0 * SUM(first_transfer)   / NULLIF(COUNT(*), 0), 1), 0)                  AS pct_funded_of_signup
FROM steps
GROUP BY cohort_week;
