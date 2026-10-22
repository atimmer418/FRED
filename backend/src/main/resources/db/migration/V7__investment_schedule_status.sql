-- Replace investment_schedules.is_paused with an explicit status.
--
-- A boolean could not say WHY a schedule was stopped, and the two reasons are nothing alike. A
-- schedule waiting for its bank has never run; a schedule the user paused was running and they
-- turned it off. Counting both as "paused" made the churn number meaningless, and worse, it let
-- the ACH machinery "resume" a schedule the user had deliberately paused, because to the code
-- both were simply is_paused = 1.
--
-- The three states, and the only transitions allowed between them:
--
--   PENDING_BANK     created, waiting for an ACH relationship. Has never run.
--   ACTIVE           running.
--   PAUSED_BY_USER   was running, the user turned it off.
--
--   PENDING_BANK  -> ACTIVE          the ACH relationship landed. The ONLY thing this path does.
--   ACTIVE        -> PAUSED_BY_USER  the user pressed pause.          (schedule_paused)
--   PAUSED_BY_USER-> ACTIVE          the user pressed resume.         (schedule_resumed)
--
-- Note what is now IMPOSSIBLE rather than merely discouraged: the ACH path cannot touch a
-- PAUSED_BY_USER schedule, so the retry scheduler can no longer override a deliberate pause.
--
-- THE STATUS IS NOT THE ONLY THING THAT CAN STOP A SCHEDULE. Paycheck Autopilot's V6 added
-- investment_schedules.hold_reason, and the two are ORTHOGONAL by design:
--
--   status       the user and bank lifecycle. Only the user's own actions and the ACH landing
--                move it. A system hold never does.
--   hold_reason  a system stop with a reason (BANK_CHANGE, MANUAL_REVIEW, an ACH return, ...).
--                Set and cleared by the system. The user cannot clear one by pressing resume.
--
-- A schedule is runnable only when status = 'ACTIVE' AND no blocking hold is set; that rule lives
-- in InvestmentSchedule.isReadyForInvestment and nowhere else. A bank change is therefore a HOLD,
-- not a status change: it leaves status alone so a Plaid outage cannot read as churn, and so a
-- schedule the user had paused is not silently restarted when the new bank lands.
--
-- The backfill reads the old flag plus the ACH state, which is the best evidence available. It
-- does not read hold_reason, and does not write it:
--   is_paused = 0                          -> ACTIVE          (it was running)
--   is_paused = 1 and ach_request_id set   -> PAUSED_BY_USER  (it had a bank, so it had run)
--   is_paused = 1 and no ach_request_id    -> PENDING_BANK    (it never got a bank)
--
-- Against a database that already carries V6's shape, a schedule mid-bank-change (is_paused = 0,
-- ach_request_id NULL, hold_reason = 'BANK_CHANGE') lands on ACTIVE and keeps its hold, which is
-- the orthogonal reading: the user still wants it, the system is holding it. The first branch is
-- what makes this work, so the CASE order below is load-bearing.
--
-- Idempotent: safe to run against a database that already has the column.

SET @stmt := (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE investment_schedules ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''PENDING_BANK''',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules'
      AND COLUMN_NAME = 'status');
PREPARE add_column FROM @stmt;
EXECUTE add_column;
DEALLOCATE PREPARE add_column;

-- Backfill only where the old column still exists, so a re-run cannot undo later edits.
SET @stmt := (SELECT IF(COUNT(*) = 1,
        'UPDATE investment_schedules SET status = CASE
             WHEN is_paused = 0 THEN ''ACTIVE''
             WHEN ach_request_id IS NOT NULL THEN ''PAUSED_BY_USER''
             ELSE ''PENDING_BANK''
         END',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules'
      AND COLUMN_NAME = 'is_paused');
PREPARE backfill FROM @stmt;
EXECUTE backfill;
DEALLOCATE PREPARE backfill;

SET @stmt := (SELECT IF(COUNT(*) = 1,
        'ALTER TABLE investment_schedules DROP COLUMN is_paused',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'investment_schedules'
      AND COLUMN_NAME = 'is_paused');
PREPARE drop_column FROM @stmt;
EXECUTE drop_column;
DEALLOCATE PREPARE drop_column;
