package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ruling R19: a schedule write must not lose another transaction's write.
 *
 * <p>InvestmentSchedule has no version column, so the only thing between a stale copy and the
 * row is which columns the UPDATE names. Without {@code @DynamicUpdate} Hibernate writes every
 * mapped column from the in-memory entity, and a writer that loaded the row before another
 * transaction committed writes that transaction's columns back to their old values.
 *
 * <p>Each case opens transaction A and loads the schedule, commits a separate transaction B that
 * changes one column, then has A change a different column and commit. The row must end with both
 * changes. The first case uses the real 11:55 per-schedule path for B, because the column it
 * advances (next_investment_date) is the one whose loss is an extra pull.
 *
 * <p>Deliberately NOT annotated {@code @Transactional}: the boundaries are the subject. Every
 * column check goes through JDBC after A has committed, or inside a REQUIRES_NEW transaction
 * while A is open (A's own connection would answer from A's snapshot); the one entity read, the
 * final runnable check, loads a fresh copy after A has committed.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ScheduleStaleWriteTest {

    private static final String ACH_ID = "ach-stale-write";

    @Autowired
    private InvestmentScheduleRepository investmentScheduleRepository;
    @Autowired
    private ScheduledInvestmentProcessor scheduledInvestmentProcessor;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Transaction A, the writer holding the stale copy. */
    private TransactionTemplate outer;
    /** Transaction B (and the in-flight reads): always a new transaction on its own connection. */
    private TransactionTemplate separate;
    private Long userId;
    private LocalDate today;

    @BeforeEach
    void createCommittedUser() {
        outer = new TransactionTemplate(transactionManager);
        separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        today = EasternTime.today();

        User fresh = new User();
        fresh.setEmail("stale-write-" + UUID.randomUUID() + "@example.invalid");
        // processOne re-checks the brokerage account inside its own transaction.
        fresh.setAccountStatus("ACTIVE");
        User saved = outer.execute(status -> userRepository.saveAndFlush(fresh));
        assertNotNull(saved);
        userId = saved.getId();
        assertNotNull(userId, "the test user must be committed before any schedule is seeded");
    }

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM investment_executions WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM analytics_outbox WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM investment_schedules WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    /**
     * A committed MANUAL schedule that the 11:55 job would run tonight: ACTIVE, ACH linked, no
     * hold, next date today. The start date is two weeks back so only the next date makes it due
     * (isReadyForInvestment also treats "start date is today" as due).
     */
    private long seedDueSchedule() {
        jdbcTemplate.update(
                "INSERT INTO investment_schedules "
                        + "(user_id, created_at, frequency, investment_amount, monthly_amount, "
                        + " monthly_streak, next_investment_date, start_date, status, mode, "
                        + " hold_reason, ach_request_id) "
                        + "VALUES (?, NOW(), 'BIWEEKLY', 250.00, 542.50, 0, ?, ?, 'ACTIVE', 'MANUAL', NULL, ?)",
                userId, today, today.minusDays(14), ACH_ID);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM investment_schedules WHERE user_id = ?", Long.class, userId);
        assertNotNull(id);
        return id;
    }

    @Test
    void staleHoldWriteDoesNotRevertNextInvestmentDate() {
        long scheduleId = seedDueSchedule();
        AtomicReference<LocalDate> advanced = new AtomicReference<>();

        outer.executeWithoutResult(a -> {
            // A: a Phase 1 writer (an Item-state hold, a detection mirror) loads the row.
            InvestmentSchedule stale = investmentScheduleRepository.findById(scheduleId).orElseThrow();
            assertEquals(today, stale.getNextInvestmentDate(), "sanity check: A's copy says the schedule is due today");

            // B, committed while A is still open: tonight's run queues and advances the schedule.
            assertEquals(ProcessOutcome.QUEUED, scheduledInvestmentProcessor.processOne(scheduleId, today),
                    "sanity check: the 11:55 path queued this schedule");
            advanced.set(separate.execute(b -> nextDateInDatabase(scheduleId)));
            assertTrue(advanced.get().isAfter(today), "sanity check: B moved the date forward");

            // A changes only its own column and commits.
            stale.setHoldReason(HoldReason.ITEM_LOGIN_REQUIRED);
        });

        assertEquals(advanced.get(), nextDateInDatabase(scheduleId),
                "the 11:55 advance must survive a stale hold write, or the schedule is due again: an extra pull");
        assertEquals(HoldReason.ITEM_LOGIN_REQUIRED.name(), holdReasonInDatabase(scheduleId),
                "A's own write landed");
        assertEquals(1, monthlyStreakInDatabase(scheduleId), "the processor's streak write survived too");
        assertFalse(investmentScheduleRepository.findById(scheduleId).orElseThrow().isReadyForInvestment(today),
                "and the schedule is not runnable again");
    }

    @Test
    void staleDateWriteDoesNotClearAHold() {
        long scheduleId = seedDueSchedule();
        AtomicReference<LocalDate> written = new AtomicReference<>();

        outer.executeWithoutResult(a -> {
            // A: a writer loads the row while it has no hold.
            InvestmentSchedule stale = investmentScheduleRepository.findById(scheduleId).orElseThrow();
            assertNull(stale.getHoldReason(), "A's copy has no hold");

            // B, committed while A is still open: a system stop lands (the KYC rejection hold).
            separate.executeWithoutResult(b -> jdbcTemplate.update(
                    "UPDATE investment_schedules SET hold_reason = 'MANUAL_REVIEW' WHERE id = ?", scheduleId));

            // A advances the date from its stale copy and commits.
            written.set(stale.calculateNextInvestmentDate(today));
            stale.setNextInvestmentDate(written.get());
        });

        assertEquals(HoldReason.MANUAL_REVIEW.name(), holdReasonInDatabase(scheduleId),
                "a stale date write must not clear a blocking hold that landed after the read");
        assertEquals(written.get(), nextDateInDatabase(scheduleId), "A's own write landed");
    }

    // --- helpers: straight through JDBC, so no persistence context can answer for the row ------

    private LocalDate nextDateInDatabase(long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_investment_date FROM investment_schedules WHERE id = ?", LocalDate.class, scheduleId);
    }

    private String holdReasonInDatabase(long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT hold_reason FROM investment_schedules WHERE id = ?", String.class, scheduleId);
    }

    private int monthlyStreakInDatabase(long scheduleId) {
        Integer streak = jdbcTemplate.queryForObject(
                "SELECT monthly_streak FROM investment_schedules WHERE id = ?", Integer.class, scheduleId);
        return streak == null ? 0 : streak;
    }
}
