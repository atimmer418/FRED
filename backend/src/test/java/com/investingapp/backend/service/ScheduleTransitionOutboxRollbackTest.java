package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Atomicity for schedule_paused and schedule_resumed, and the status transitions underneath them,
 * against the real database.
 *
 * <p>The whole reason the outbox exists is that the event and the fact it describes must land
 * together or not at all. A Mockito test cannot show that: it has no transaction, so it can only
 * show that {@code record()} was called. {@link ScheduleTransitionAnalyticsTest} does that part.
 * This one drives the real {@link InvestmentScheduleService} against a real schedule row and reads
 * the committed {@code status} column back through JDBC.
 *
 * <p>Since investment_schedules.is_paused became a three-state status, only TWO of the four call
 * sites emit anything at all:
 *
 * <pre>
 *   pauseSchedule          ACTIVE         -> PAUSED_BY_USER   schedule_paused  {user, user_request}
 *   resumeSchedule         PAUSED_BY_USER -> ACTIVE           schedule_resumed {user, user_request}
 *   resetForBankChange     ACTIVE         -> PENDING_BANK     nothing
 *   updateWithAchRequestId PENDING_BANK   -> ACTIVE           nothing
 * </pre>
 *
 * <p>The last two used to emit a system pause and a system resume. They no longer emit anything,
 * because under this model they are not pauses or resumes, they are lifecycle: a schedule going
 * back to waiting for a bank, and a schedule starting for the first time. schedule_paused and
 * schedule_resumed are therefore user-only BY CONSTRUCTION, which is what makes v_schedule_churn
 * countable. Those two sites are still exercised here, for the status transition and to pin that
 * NO row is written.
 *
 * <p>The single behaviour this whole model exists to guarantee gets its own case:
 * {@link #achConfirmationCannotRestartAScheduleTheUserPaused()}. The old boolean let the ACH retry
 * scheduler force-unpause a schedule the user had deliberately switched off, because to the code
 * both reasons were simply {@code is_paused = 1}.
 *
 * <p>For the two emitting sites, each case comes in halves: the event commits with the status
 * change, and after {@code setRollbackOnly()} neither the status nor the event survives. If an emit
 * ever drifts out of the committing transaction (its own transaction, an async hand-off, an
 * after-commit hook), the rollback cases fail. Every rollback case also asserts that the status
 * really reverted, so a passing rollback case can never be a false pass from the state change
 * having never happened in the first place.
 *
 * <p>No boundary was added for any of this. InvestmentScheduleService carries a type-level
 * {@code @Transactional}, so each method under test already runs in one REQUIRED transaction, and
 * the TransactionTemplate here simply becomes that transaction when the test supplies one.
 *
 * <p>Deliberately NOT annotated {@code @Transactional}: a test-managed rollback-everything
 * transaction would make the commit and rollback cases look identical. Each case manages its own
 * boundary and {@link #cleanUp()} removes the rows by hand.
 *
 * <p>The user is loaded outside the service's transaction and passed in detached, which is exactly
 * what InvestmentScheduleController does.
 *
 * <p>{@link RequiresCiDatabase} skips the class, with its reason, where the `ci` database is not
 * reachable. (The repo's convention is this annotation rather than a bare
 * {@code @ExtendWith(...)}.)
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ScheduleTransitionOutboxRollbackTest {

    private static final String PAUSED = "schedule_paused";
    private static final String RESUMED = "schedule_resumed";

    /**
     * Both surviving events carry these same two properties. That is not a coincidence to be
     * tidied away: initiator is 'user' and reason is 'user_request' on every one of them now,
     * because the system-initiated transitions do not emit at all.
     */
    private static final String USER_TRANSITION_PROPS =
            "{\"initiator\":\"user\",\"reason\":\"user_request\"}";

    private static final String PENDING_BANK = "PENDING_BANK";
    private static final String ACTIVE = "ACTIVE";
    private static final String PAUSED_BY_USER = "PAUSED_BY_USER";

    @Autowired
    private InvestmentScheduleService investmentScheduleService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate transactions;
    private Long userId;
    private User user;

    @BeforeEach
    void createCommittedUser() {
        transactions = new TransactionTemplate(transactionManager);

        User fresh = new User();
        fresh.setEmail("analytics-transition-" + UUID.randomUUID() + "@example.invalid");
        // resumeSchedule refuses to run unless the brokerage account is ACTIVE.
        fresh.setAccountStatus("ACTIVE");
        user = transactions.execute(tx -> userRepository.saveAndFlush(fresh));
        userId = user.getId();
        assertNotNull(userId, "the test user must be committed before the service runs");
    }

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM analytics_outbox WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM investment_schedules WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    // --- Site 2: pauseSchedule, the genuine user pause ------------------------------------------

    @Test
    void committedUserPauseWritesExactlyOneOutboxRow() {
        long scheduleId = seedSchedule(ACTIVE);

        investmentScheduleService.pauseSchedule(user, scheduleId);

        assertEquals(PAUSED_BY_USER, statusInDatabase(scheduleId), "the state change committed");
        assertSoleEvent(PAUSED, USER_TRANSITION_PROPS);
    }

    @Test
    void rolledBackUserPauseLeavesNoOutboxRow() {
        long scheduleId = seedSchedule(ACTIVE);

        transactions.executeWithoutResult(tx -> {
            investmentScheduleService.pauseSchedule(user, scheduleId);
            tx.setRollbackOnly();
        });

        assertEquals(0, countOutboxRows(),
                "the event must not outlive the transaction that was rolled back");
        assertEquals(ACTIVE, statusInDatabase(scheduleId),
                "sanity check: the rollback really did discard the state change too");
    }

    /** The no-op. Nothing stopped, so the churn count must not move. */
    @Test
    void pausingAScheduleTheUserAlreadyPausedWritesNoOutboxRow() {
        long scheduleId = seedSchedule(PAUSED_BY_USER);

        investmentScheduleService.pauseSchedule(user, scheduleId);

        assertEquals(PAUSED_BY_USER, statusInDatabase(scheduleId), "still paused, as it already was");
        assertEquals(0, countOutboxRows(), "a repeated pause is not a transition");
    }

    /**
     * Pausing a schedule that has never run. The old boolean could not tell this from a real
     * pause; it is not churn, so it must neither emit nor overwrite the waiting-for-bank status.
     */
    @Test
    void pausingAScheduleWaitingForItsBankWritesNoOutboxRow() {
        long scheduleId = seedSchedule(PENDING_BANK);

        investmentScheduleService.pauseSchedule(user, scheduleId);

        assertEquals(PENDING_BANK, statusInDatabase(scheduleId),
                "a schedule that has never run is not moved to PAUSED_BY_USER");
        assertEquals(0, countOutboxRows(), "stopping something that never started is not churn");
    }

    // --- Site 3: resumeSchedule, the genuine user resume ----------------------------------------

    @Test
    void committedUserResumeWritesExactlyOneOutboxRow() {
        long scheduleId = seedSchedule(PAUSED_BY_USER);

        investmentScheduleService.resumeSchedule(user, scheduleId);

        assertEquals(ACTIVE, statusInDatabase(scheduleId), "the state change committed");
        assertSoleEvent(RESUMED, USER_TRANSITION_PROPS);
    }

    @Test
    void rolledBackUserResumeLeavesNoOutboxRow() {
        long scheduleId = seedSchedule(PAUSED_BY_USER);

        transactions.executeWithoutResult(tx -> {
            investmentScheduleService.resumeSchedule(user, scheduleId);
            tx.setRollbackOnly();
        });

        assertEquals(0, countOutboxRows(),
                "the event must not outlive the transaction that was rolled back");
        assertEquals(PAUSED_BY_USER, statusInDatabase(scheduleId),
                "sanity check: the rollback really did discard the state change too");
    }

    @Test
    void resumingAnAlreadyRunningScheduleWritesNoOutboxRow() {
        long scheduleId = seedSchedule(ACTIVE);

        investmentScheduleService.resumeSchedule(user, scheduleId);

        assertEquals(ACTIVE, statusInDatabase(scheduleId), "still running, as it already was");
        assertEquals(0, countOutboxRows(), "a repeated resume is not a transition");
    }

    /**
     * A schedule waiting for its bank is started by the ACH path, not by the resume button. The
     * user cannot skip the bank, and nothing is recorded if they try.
     */
    @Test
    void resumingAScheduleWaitingForItsBankWritesNoOutboxRow() {
        long scheduleId = seedSchedule(PENDING_BANK);

        investmentScheduleService.resumeSchedule(user, scheduleId);

        assertEquals(PENDING_BANK, statusInDatabase(scheduleId),
                "only the ACH path may start a schedule that has never run");
        assertEquals(0, countOutboxRows(), "and nothing resumed, so nothing is recorded");
    }

    /** The ACCOUNT_NOT_ACTIVE gate throws before the flip, so there is nothing to record. */
    @Test
    void resumeBlockedByAnInactiveAccountWritesNoOutboxRow() {
        long scheduleId = seedSchedule(PAUSED_BY_USER);
        User pending = transactions.execute(tx -> {
            User loaded = userRepository.findById(userId).orElseThrow();
            loaded.setAccountStatus("APPROVAL_PENDING");
            return userRepository.saveAndFlush(loaded);
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> investmentScheduleService.resumeSchedule(pending, scheduleId));
        assertEquals("ACCOUNT_NOT_ACTIVE", thrown.getMessage());

        assertEquals(PAUSED_BY_USER, statusInDatabase(scheduleId), "the schedule was never touched");
        assertEquals(0, countOutboxRows(), "a refused resume is not a transition");
    }

    // --- Site 4: resetForBankChange, lifecycle, no longer an event ------------------------------

    @Test
    void bankChangeSendsARunningScheduleBackToPendingBankAndRecordsNothing() {
        long scheduleId = seedSchedule(ACTIVE, "ach-rel-seed");

        investmentScheduleService.resetForBankChange(user);

        assertEquals(PENDING_BANK, statusInDatabase(scheduleId),
                "it cannot run until the new ACH relationship lands");
        assertNull(achRequestIdInDatabase(scheduleId), "and the old ACH id is gone");
        assertEquals(0, countOutboxRows(),
                "a bank change is lifecycle, not a pause: emitting here would put Plaid outages "
                        + "back into the churn number");
    }

    @Test
    void rolledBackBankChangeRevertsTheStatusAndTheAchId() {
        long scheduleId = seedSchedule(ACTIVE, "ach-rel-seed");

        transactions.executeWithoutResult(tx -> {
            investmentScheduleService.resetForBankChange(user);
            tx.setRollbackOnly();
        });

        assertEquals(ACTIVE, statusInDatabase(scheduleId),
                "the status change belongs to the caller's transaction");
        assertEquals("ach-rel-seed", achRequestIdInDatabase(scheduleId), "and the ACH id with it");
        assertEquals(0, countOutboxRows(), "nothing was emitted to roll back in the first place");
    }

    /**
     * The user's intent outlives a bank change. The bank is cleared, because it really did change,
     * but the schedule stays switched off so that the ACH landing cannot restart it.
     */
    @Test
    void bankChangeLeavesAUserPausedSchedulePausedWhileStillClearingTheAchId() {
        long scheduleId = seedSchedule(PAUSED_BY_USER, "ach-rel-seed");

        investmentScheduleService.resetForBankChange(user);

        assertEquals(PAUSED_BY_USER, statusInDatabase(scheduleId),
                "a bank change must not quietly convert a user pause into a pending bank");
        assertNull(achRequestIdInDatabase(scheduleId), "the ACH id is still cleared");
        assertEquals(0, countOutboxRows(), "nothing transitioned");
    }

    /** A bank change on a schedule that was already waiting for its bank stopped nothing. */
    @Test
    void bankChangeOfAScheduleAlreadyWaitingForItsBankRecordsNothing() {
        long scheduleId = seedSchedule(PENDING_BANK);

        investmentScheduleService.resetForBankChange(user);

        assertEquals(PENDING_BANK, statusInDatabase(scheduleId));
        assertEquals(0, countOutboxRows(), "a re-reset is not a transition");
    }

    // --- Site 5: updateWithAchRequestId, lifecycle, no longer an event --------------------------

    @Test
    void achConfirmationStartsAPendingScheduleAndRecordsNothing() {
        long scheduleId = seedSchedule(PENDING_BANK);

        investmentScheduleService.updateWithAchRequestId(user, "ach-rel-1");

        assertEquals(ACTIVE, statusInDatabase(scheduleId), "the ACH relationship landed");
        assertEquals("ach-rel-1", achRequestIdInDatabase(scheduleId));
        assertEquals(0, countOutboxRows(),
                "a schedule starting for the first time is not a resume: "
                        + "investment_schedule_created already recorded that it exists");
    }

    @Test
    void rolledBackAchConfirmationRevertsTheStatusAndTheAchId() {
        long scheduleId = seedSchedule(PENDING_BANK);

        transactions.executeWithoutResult(tx -> {
            investmentScheduleService.updateWithAchRequestId(user, "ach-rel-1");
            tx.setRollbackOnly();
        });

        assertEquals(PENDING_BANK, statusInDatabase(scheduleId),
                "the status change belongs to the caller's transaction");
        assertNull(achRequestIdInDatabase(scheduleId), "and the ACH id with it");
        assertEquals(0, countOutboxRows(), "nothing was emitted to roll back in the first place");
    }

    /**
     * An ACH id rewritten on a schedule that is already live. Both the "ACH already exists" branch
     * of AccountStatusService.attemptAchCreation and the ACH retry scheduler reach this.
     */
    @Test
    void achUpdateOfAnAlreadyRunningScheduleWritesNoOutboxRow() {
        long scheduleId = seedSchedule(ACTIVE, "ach-rel-seed");

        investmentScheduleService.updateWithAchRequestId(user, "ach-rel-2");

        assertEquals("ach-rel-2", achRequestIdInDatabase(scheduleId), "the id still updates");
        assertEquals(ACTIVE, statusInDatabase(scheduleId), "it was running and stays running");
        assertEquals(0, countOutboxRows(), "but nothing started, so nothing is recorded");
    }

    /**
     * THE reason the three-state status exists.
     *
     * <p>The ACH retry scheduler and the "ACH already exists" branch both call this on whatever
     * schedule the user has. Under the old boolean that call force-unpaused it, so a user who
     * switched their schedule off and later relinked a bank found it running again, with no event
     * and no explanation. The ACH path now only ever moves PENDING_BANK to ACTIVE, which makes
     * that override impossible rather than merely discouraged.
     */
    @Test
    void achConfirmationCannotRestartAScheduleTheUserPaused() {
        long scheduleId = seedSchedule(PAUSED_BY_USER);

        investmentScheduleService.updateWithAchRequestId(user, "ach-rel-3");

        assertEquals(PAUSED_BY_USER, statusInDatabase(scheduleId),
                "the ACH path must never override a deliberate pause");
        assertEquals("ach-rel-3", achRequestIdInDatabase(scheduleId),
                "the new relationship is still recorded, ready for when the user resumes");
        assertEquals(0, countOutboxRows(), "and no resume is claimed on the user's behalf");
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * A committed schedule in a known status, inserted directly so the starting state is exactly
     * what each case needs and owes nothing to the create path.
     *
     * @param status PENDING_BANK, ACTIVE or PAUSED_BY_USER, written as the stored string so a
     *               renamed enum constant has to be dealt with here rather than silently passing
     */
    private long seedSchedule(String status) {
        return seedSchedule(status, null);
    }

    /**
     * As {@link #seedSchedule(String)}, with an existing ACH relationship id on the row, so that a
     * case which expects the id to be cleared or rewritten has something to observe.
     */
    private long seedSchedule(String status, String achRequestId) {
        LocalDate today = LocalDate.now();
        jdbcTemplate.update(
                "INSERT INTO investment_schedules "
                        + "(user_id, created_at, frequency, investment_amount, monthly_amount, "
                        + " monthly_streak, next_investment_date, start_date, status) "
                        + "VALUES (?, NOW(), 'BIWEEKLY', 250.00, 542.50, 0, ?, ?, ?)",
                userId, today.plusDays(3), today, status);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertNotNull(id);
        // Set as a second statement rather than a null bind parameter in the insert: the column
        // stays NULL by default and no driver has to be asked what type a null is.
        if (achRequestId != null) {
            jdbcTemplate.update("UPDATE investment_schedules SET ach_request_id = ? WHERE id = ?",
                    achRequestId, id);
        }
        assertEquals(status, statusInDatabase(id), "the seed must start in the status it asked for");
        return id;
    }

    /** Exactly one outbox row for this user, with this name and these two properties verbatim. */
    private void assertSoleEvent(String eventName, String propertiesJson) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT event_name, event_id, properties, status FROM analytics_outbox "
                        + "WHERE user_id = ?", userId);

        assertEquals(1, rows.size(), "exactly one event for one transition");
        Map<String, Object> row = rows.get(0);
        assertEquals(eventName, row.get("event_name"));
        assertNotNull(row.get("event_id"), "the publisher de-duplicates on this, so it must be set");
        assertEquals("PENDING", row.get("status"));
        assertEquals(propertiesJson, row.get("properties"),
                "two enum values and nothing else: no amount, no frequency, no bank, no id");
    }

    private int countOutboxRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_outbox WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** Read straight through JDBC, so a rolled-back in-memory entity cannot answer for the row. */
    private String statusInDatabase(long scheduleId) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM investment_schedules WHERE id = ?", String.class, scheduleId);
        assertNotNull(status, "the schedule row must exist, with a status");
        return status;
    }

    private String achRequestIdInDatabase(long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT ach_request_id FROM investment_schedules WHERE id = ?", String.class,
                scheduleId);
    }
}
