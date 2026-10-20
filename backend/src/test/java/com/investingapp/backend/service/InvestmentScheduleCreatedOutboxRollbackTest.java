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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Atomicity for investment_schedule_created, against the real database.
 *
 * <p>The whole reason the outbox exists is that the event and the fact it describes must land
 * together or not at all. A Mockito test cannot show that: it has no transaction, so it can only
 * show that {@code record()} was called. This one drives the real
 * {@link InvestmentScheduleService} and then forces the surrounding transaction to roll back, and
 * asserts the outbox is empty afterwards. If the emit ever drifts out of the committing transaction
 * (its own transaction, an async hand-off, an after-commit hook), this test fails.
 *
 * <p>Deliberately NOT annotated {@code @Transactional}: a test-managed rollback-everything
 * transaction would make both cases look identical. Each case manages its own boundary, and
 * {@link #cleanUp()} removes the rows by hand.
 *
 * <p>The user is loaded outside the service's transaction and passed in detached, which is exactly
 * what InvestmentScheduleController does.
 *
 * <p>{@link RequiresCiDatabase} skips the class, with its reason, where the `ci` database is not
 * reachable. (The repo's convention is this annotation rather than a bare
 * {@code @ExtendWith(...)}: the annotation is itself meta-annotated with the condition, which is
 * what {@code BackendApplicationTests} uses.)
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class InvestmentScheduleCreatedOutboxRollbackTest {

    private static final String EVENT = "investment_schedule_created";

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
        fresh.setEmail("analytics-schedule-" + UUID.randomUUID() + "@example.invalid");
        // create_date is set by @CreationTimestamp on insert, so days_since_signup_band is
        // 'same_day' for this user.
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
        jdbcTemplate.update(
                "DELETE FROM portfolio_items WHERE portfolio_id IN "
                        + "(SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    /** The committing transaction rolls back: neither the schedule nor its event survives. */
    @Test
    void rolledBackCreateLeavesNoOutboxRow() {
        transactions.executeWithoutResult(status -> {
            investmentScheduleService.createInvestmentSchedule(user, new BigDecimal("250.00"),
                    "BIWEEKLY", LocalDate.now().plusDays(3), LocalDate.now().plusDays(3));
            status.setRollbackOnly();
        });

        assertEquals(0, countRows("analytics_outbox"),
                "the event must not outlive the transaction that was rolled back");
        assertEquals(0, countRows("investment_schedules"),
                "sanity check: the rollback really did discard the state change too");
    }

    /** The committing transaction commits: exactly one event, with a usable id and no raw amount. */
    @Test
    void committedCreateWritesExactlyOneOutboxRow() {
        investmentScheduleService.createInvestmentSchedule(user, new BigDecimal("250.00"),
                "BIWEEKLY", LocalDate.now().plusDays(3), LocalDate.now().plusDays(3));

        assertEquals(1, countRows("investment_schedules"), "the schedule committed");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT event_name, event_id, properties, status FROM analytics_outbox "
                        + "WHERE user_id = ?", userId);

        assertEquals(1, rows.size(), "exactly one event for one created schedule");
        Map<String, Object> row = rows.get(0);
        assertEquals(EVENT, row.get("event_name"));
        assertNotNull(row.get("event_id"), "the publisher de-duplicates on this, so it must be set");
        assertEquals("PENDING", row.get("status"));
        assertEquals("{\"frequency\":\"biweekly\",\"amount_band\":\"250_499\","
                        + "\"days_since_signup_band\":\"same_day\"}",
                row.get("properties"),
                "banded scalars only: no raw amount, no dates, no free text");
    }

    /**
     * A second call is an edit, not a creation: the endpoint doubles as the edit endpoint and
     * investment_schedules has a UNIQUE key on user_id. There is no schedule_updated event, so the
     * count must still be one.
     */
    @Test
    void editDoesNotWriteASecondOutboxRow() {
        investmentScheduleService.createInvestmentSchedule(user, new BigDecimal("250.00"),
                "BIWEEKLY", LocalDate.now().plusDays(3), LocalDate.now().plusDays(3));
        investmentScheduleService.createInvestmentSchedule(user, new BigDecimal("600.00"),
                "MONTHLY", LocalDate.now().plusDays(3), LocalDate.now().plusDays(4));

        assertEquals(1, countRows("investment_schedules"), "the unique key means one schedule");
        assertEquals(1, countRows("analytics_outbox"), "the edit emitted nothing");
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }
}
