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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Atomicity for schedule_paused and schedule_resumed, against the real database.
 *
 * <p>The whole reason the outbox exists is that the event and the fact it describes must land
 * together or not at all. A Mockito test cannot show that: it has no transaction, so it can only
 * show that {@code record()} was called. {@link ScheduleTransitionAnalyticsTest} does that part.
 * This one drives the real {@link InvestmentScheduleService} against a real schedule row, and for
 * each of the four call sites proves both halves: the event commits with the pause flag, and after
 * {@code setRollbackOnly()} neither the flag nor the event survives. If an emit ever drifts out of
 * the committing transaction (its own transaction, an async hand-off, an after-commit hook), the
 * rollback cases fail.
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

    private static final String USER_PAUSE_PROPS = "{\"initiator\":\"user\",\"reason\":\"user_request\"}";
    private static final String USER_RESUME_PROPS = "{\"initiator\":\"user\",\"reason\":\"user_request\"}";
    private static final String SYSTEM_PAUSE_PROPS = "{\"initiator\":\"system\",\"reason\":\"bank_change\"}";
    private static final String SYSTEM_RESUME_PROPS = "{\"initiator\":\"system\",\"reason\":\"ach_confirmed\"}";

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
        user = transactions.execute(status -> userRepository.saveAndFlush(fresh));
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

    /**
     * A committed schedule in a known pause state, inserted directly so the starting state is
     * exactly what each case needs and owes nothing to the create path.
     */
    private long seedSchedule(boolean paused) {
        LocalDate today = LocalDate.now();
        jdbcTemplate.update(
                "INSERT INTO investment_schedules "
                        + "(user_id, created_at, frequency, investment_amount, monthly_amount, "
                        + " monthly_streak, next_investment_date, start_date, is_paused) "
                        + "VALUES (?, NOW(), 'BIWEEKLY', 250.00, 542.50, 0, ?, ?, ?)",
                userId, today.plusDays(3), today, paused);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertNotNull(id);
        return id;
    }

    // --- Site 2: pauseSchedule, the genuine user pause ------------------------------------------

    @Test
    void committedUserPauseWritesExactlyOneOutboxRow() {
        long scheduleId = seedSchedule(false);

        investmentScheduleService.pauseSchedule(user, scheduleId);

        assertTrue(isPausedInDatabase(scheduleId), "the state change committed");
        assertSoleEvent(PAUSED, USER_PAUSE_PROPS);
    }

    @Test
    void rolledBackUserPauseLeavesNoOutboxRow() {
        long scheduleId = seedSchedule(false);

        transactions.executeWithoutResult(status -> {
            investmentScheduleService.pauseSchedule(user, scheduleId);
            status.setRollbackOnly();
        });

        assertEquals(0, countOutboxRows(),
                "the event must not outlive the transaction that was rolled back");
        assertFalse(isPausedInDatabase(scheduleId),
                "sanity check: the rollback really did discard the state change too");
    }

    /** The no-op. Nothing stopped, so the churn count must not move. */
    @Test
    void pausingAnAlreadyPausedScheduleWritesNoOutboxRow() {
        long scheduleId = seedSchedule(true);

        investmentScheduleService.pauseSchedule(user, scheduleId);

        assertTrue(isPausedInDatabase(scheduleId), "still paused, as it already was");
        assertEquals(0, countOutboxRows(), "a repeated pause is not a transition");
    }

    // --- Site 3: resumeSchedule, the genuine user resume ----------------------------------------

    @Test
    void committedUserResumeWritesExactlyOneOutboxRow() {
        long scheduleId = seedSchedule(true);

        investmentScheduleService.resumeSchedule(user, scheduleId);

        assertFalse(isPausedInDatabase(scheduleId), "the state change committed");
        assertSoleEvent(RESUMED, USER_RESUME_PROPS);
    }

    @Test
    void rolledBackUserResumeLeavesNoOutboxRow() {
        long scheduleId = seedSchedule(true);

        transactions.executeWithoutResult(status -> {
            investmentScheduleService.resumeSchedule(user, scheduleId);
            status.setRollbackOnly();
        });

        assertEquals(0, countOutboxRows(),
                "the event must not outlive the transaction that was rolled back");
        assertTrue(isPausedInDatabase(scheduleId),
                "sanity check: the rollback really did discard the state change too");
    }

    @Test
    void resumingAnAlreadyRunningScheduleWritesNoOutboxRow() {
        long scheduleId = seedSchedule(false);

        investmentScheduleService.resumeSchedule(user, scheduleId);

        assertFalse(isPausedInDatabase(scheduleId), "still running, as it already was");
        assertEquals(0, countOutboxRows(), "a repeated resume is not a transition");
    }

    /** The ACCOUNT_NOT_ACTIVE gate throws before the flip, so there is nothing to record. */
    @Test
    void resumeBlockedByAnInactiveAccountWritesNoOutboxRow() {
        long scheduleId = seedSchedule(true);
        User pending = transactions.execute(status -> {
            User loaded = userRepository.findById(userId).orElseThrow();
            loaded.setAccountStatus("APPROVAL_PENDING");
            return userRepository.saveAndFlush(loaded);
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> investmentScheduleService.resumeSchedule(pending, scheduleId));
        assertEquals("ACCOUNT_NOT_ACTIVE", thrown.getMessage());

        assertTrue(isPausedInDatabase(scheduleId), "the schedule was never touched");
        assertEquals(0, countOutboxRows(), "a refused resume is not a transition");
    }

    // --- Site 4: resetForBankChange, the system pause -------------------------------------------

    @Test
    void committedBankChangePauseWritesExactlyOneSystemOutboxRow() {
        long scheduleId = seedSchedule(false);

        investmentScheduleService.resetForBankChange(user);

        assertTrue(isPausedInDatabase(scheduleId), "the state change committed");
        assertSoleEvent(PAUSED, SYSTEM_PAUSE_PROPS);
    }

    @Test
    void rolledBackBankChangePauseLeavesNoOutboxRow() {
        long scheduleId = seedSchedule(false);

        transactions.executeWithoutResult(status -> {
            investmentScheduleService.resetForBankChange(user);
            status.setRollbackOnly();
        });

        assertEquals(0, countOutboxRows(),
                "the event must not outlive the transaction that was rolled back");
        assertFalse(isPausedInDatabase(scheduleId),
                "sanity check: the rollback really did discard the state change too");
    }

    /** A bank change on a schedule that was already paused stopped nothing. */
    @Test
    void bankChangePauseOfAnAlreadyPausedScheduleWritesNoOutboxRow() {
        long scheduleId = seedSchedule(true);

        investmentScheduleService.resetForBankChange(user);

        assertTrue(isPausedInDatabase(scheduleId));
        assertEquals(0, countOutboxRows(), "a re-pause is not a transition");
    }

    // --- Site 5: updateWithAchRequestId, the system resume --------------------------------------

    @Test
    void committedAchConfirmationWritesExactlyOneSystemOutboxRow() {
        long scheduleId = seedSchedule(true);

        investmentScheduleService.updateWithAchRequestId(user, "ach-rel-1");

        assertFalse(isPausedInDatabase(scheduleId), "the state change committed");
        assertEquals("ach-rel-1", achRequestIdInDatabase(scheduleId));
        assertSoleEvent(RESUMED, SYSTEM_RESUME_PROPS);
    }

    @Test
    void rolledBackAchConfirmationLeavesNoOutboxRow() {
        long scheduleId = seedSchedule(true);

        transactions.executeWithoutResult(status -> {
            investmentScheduleService.updateWithAchRequestId(user, "ach-rel-1");
            status.setRollbackOnly();
        });

        assertEquals(0, countOutboxRows(),
                "the event must not outlive the transaction that was rolled back");
        assertTrue(isPausedInDatabase(scheduleId),
                "sanity check: the rollback really did discard the state change too");
        assertNull(achRequestIdInDatabase(scheduleId), "and the ACH id with it");
    }

    /**
     * An ACH id rewritten on a schedule that is already live. Both the "ACH already exists" branch
     * of AccountStatusService.attemptAchCreation and the ACH retry scheduler reach this, and it
     * resumed nothing.
     */
    @Test
    void achUpdateOfAnAlreadyRunningScheduleWritesNoOutboxRow() {
        long scheduleId = seedSchedule(false);

        investmentScheduleService.updateWithAchRequestId(user, "ach-rel-2");

        assertEquals("ach-rel-2", achRequestIdInDatabase(scheduleId), "the id still updates");
        assertEquals(0, countOutboxRows(), "but no resume happened");
    }

    // --- helpers --------------------------------------------------------------------------------

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
    private boolean isPausedInDatabase(long scheduleId) {
        Boolean paused = jdbcTemplate.queryForObject(
                "SELECT is_paused FROM investment_schedules WHERE id = ?", Boolean.class, scheduleId);
        assertNotNull(paused, "the schedule row must exist");
        return paused;
    }

    private String achRequestIdInDatabase(long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT ach_request_id FROM investment_schedules WHERE id = ?", String.class,
                scheduleId);
    }
}
