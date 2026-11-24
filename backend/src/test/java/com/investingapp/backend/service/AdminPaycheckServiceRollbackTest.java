package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** clear-review on a real database: hold, outbox row and audit row commit or roll back together. Not @Transactional. */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class AdminPaycheckServiceRollbackTest {

    @Autowired
    private AdminPaycheckService adminPaycheckService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate transactions;
    private Long userId;

    @BeforeEach
    void createCommittedUser() {
        transactions = new TransactionTemplate(transactionManager);
        User fresh = new User();
        fresh.setEmail("admin-paycheck-" + UUID.randomUUID() + "@example.invalid");
        fresh.setAccountStatus("ACTIVE");
        userId = transactions.execute(status -> userRepository.saveAndFlush(fresh)).getId();
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("admin_actions", "analytics_outbox", "investment_schedules")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
        }
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private long seedActiveScheduleInReview() {
        LocalDate today = EasternTime.today();
        jdbc.update("INSERT INTO investment_schedules (user_id, created_at, frequency, investment_amount, monthly_amount,"
                        + " monthly_streak, next_investment_date, start_date, status, mode, hold_reason, ach_request_id)"
                        + " VALUES (?, NOW(), 'BIWEEKLY', 250.00, 542.50, 0, ?, ?, 'ACTIVE', 'MANUAL', 'MANUAL_REVIEW', 'ach-1')",
                userId, today.plusDays(3), today);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void committedClearReviewWritesStateOutboxAndAuditTogether() {
        long scheduleId = seedActiveScheduleInReview();

        assertEquals(200, adminPaycheckService.clearReview("ops-test", userId, "resolved").httpStatus());

        assertNull(holdReason(scheduleId));
        assertEquals("{\"initiator\":\"system\",\"reason\":\"review_cleared\"}", jdbc.queryForObject(
                "SELECT properties FROM analytics_outbox WHERE user_id = ? AND event_name = 'schedule_resumed'",
                String.class, userId));
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor, verb, before_json, after_json FROM admin_actions WHERE user_id = ?", userId);
        assertEquals(AdminPaycheckService.VERB_CLEAR_REVIEW, audit.get("verb"));
        assertTrue(String.valueOf(audit.get("before_json")).contains("MANUAL_REVIEW"));
        assertFalse(String.valueOf(audit.get("after_json")).contains("MANUAL_REVIEW"));
    }

    @Test
    void mutatingVerbRollbackLeavesNoAdminAction() {
        long scheduleId = seedActiveScheduleInReview();

        transactions.executeWithoutResult(status -> {
            adminPaycheckService.clearReview("ops-test", userId, "resolved");
            status.setRollbackOnly();
        });

        assertEquals(0, count("admin_actions"), "the audit row must not outlive the state change it describes");
        assertEquals(0, count("analytics_outbox"));
        assertEquals("MANUAL_REVIEW", holdReason(scheduleId), "the rollback really discarded the hold change");
    }

    private String holdReason(long scheduleId) {
        return jdbc.queryForObject("SELECT hold_reason FROM investment_schedules WHERE id = ?", String.class, scheduleId);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }
}
