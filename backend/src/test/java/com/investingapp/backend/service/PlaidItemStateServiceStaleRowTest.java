package com.investingapp.backend.service;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AdminPaycheckService.VerbResult;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final review MS-1, MS-2, TXC-2: a Phase 1 writer that locks the user row must decide from, and
 * write, the committed row, not from a copy the request thread already holds.
 *
 * <p>Each case opens what open-in-view gives a request thread (an EntityManager bound to the thread
 * with no transaction), loads the User and schedule through it as a controller would, commits a
 * change from a separate connection, and then runs the handler on that thread. Not
 * {@code @Transactional}: the boundaries are the subject; every assertion reads through JDBC.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class PlaidItemStateServiceStaleRowTest {

    private static final String ITEM = "item-stale-row";

    @Autowired
    private PlaidItemStateService itemStateService;
    @Autowired
    private AdminPaycheckService adminPaycheckService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InvestmentScheduleRepository scheduleRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void createCommittedUser() {
        User fresh = new User();
        fresh.setEmail("stale-row-" + UUID.randomUUID() + "@example.invalid");
        fresh.setAccountStatus("ACTIVE");
        fresh.setPlaidItemId(ITEM);
        fresh.setPlaidAccessToken("ENC:stale-row-token");
        fresh.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        fresh.setPlaidSyncCursor("C0");
        fresh.setAlpacaAchStatus("QUEUED");
        userId = new TransactionTemplate(transactionManager).execute(status -> userRepository.saveAndFlush(fresh)).getId();
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("admin_actions", "analytics_outbox", "plaid_item_removals", "investment_schedules")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
        }
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    /** What OSIV hands a request thread: an EntityManager bound to it, no transaction. */
    private <T> T onRequestThread(Supplier<T> body) {
        EntityManager em = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(em));
        try {
            return body.get();
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            em.close();
        }
    }

    private long seedSchedule() {
        LocalDate today = EasternTime.today();
        jdbc.update("INSERT INTO investment_schedules (user_id, created_at, frequency, investment_amount, monthly_amount,"
                        + " monthly_streak, next_investment_date, start_date, status, mode, hold_reason, ach_request_id)"
                        + " VALUES (?, NOW(), 'BIWEEKLY', 250.00, 542.50, 0, ?, ?, 'ACTIVE', 'MANUAL', NULL, NULL)",
                userId, today.plusDays(3), today);
        return jdbc.queryForObject("SELECT id FROM investment_schedules WHERE user_id = ?", Long.class, userId);
    }

    @Test
    void repairAfterAConcurrentRepairIsANoOpAndKeepsTheNewCursor() {
        ItemStateResult result = onRequestThread(() -> {
            User held = userRepository.findById(userId).orElseThrow();
            assertThat(held.getPlaidItemStatus()).as("sanity check: the request's copy is broken").isEqualTo(PlaidItemStatus.LOGIN_REQUIRED);
            jdbc.update("UPDATE users SET plaid_item_status = 'OK', plaid_sync_cursor = 'C1' WHERE id = ?", userId);
            return itemStateService.onLoginRepaired(userId, ITEM, RepairPath.UPDATE_MODE);
        });

        assertThat(result).isEqualTo(ItemStateResult.NO_OP);
        assertThat(column("plaid_sync_cursor")).isEqualTo("C1");
        assertThat(column("plaid_item_status")).isEqualTo("OK");
    }

    @Test
    void relinkCommittedMeanwhileMakesTheOldItemStale() {
        ItemStateResult result = onRequestThread(() -> {
            userRepository.findById(userId).orElseThrow();
            jdbc.update("UPDATE users SET plaid_item_id = 'item-new', plaid_access_token = 'ENC:new-token',"
                    + " plaid_sync_cursor = NULL, plaid_item_status = 'OK' WHERE id = ?", userId);
            return itemStateService.onLoginRepaired(userId, ITEM, RepairPath.UPDATE_MODE);
        });

        assertThat(result).isEqualTo(ItemStateResult.STALE_ITEM);
        assertThat(column("plaid_item_id")).isEqualTo("item-new");
        assertThat(column("plaid_access_token")).isEqualTo("ENC:new-token");
        assertThat(column("plaid_sync_cursor")).isNull();
    }

    @Test
    void appliedHandlerKeepsAColumnAnotherTransactionChanged() {
        ItemStateResult result = onRequestThread(() -> {
            userRepository.findById(userId).orElseThrow();
            jdbc.update("UPDATE users SET alpaca_ach_status = 'APPROVED', plaid_sync_cursor = 'C1' WHERE id = ?", userId);
            return itemStateService.onLoginRepaired(userId, ITEM, RepairPath.LOGIN_REPAIRED);
        });

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(column("plaid_item_status")).as("the handler's own write landed").isEqualTo("OK");
        assertThat(column("alpaca_ach_status")).isEqualTo("APPROVED");
        assertThat(column("plaid_sync_cursor")).isEqualTo("C1");
    }

    @Test
    void itemHoldNeverOverwritesAManualReviewCommittedAfterTheRead() {
        jdbc.update("UPDATE users SET plaid_item_status = 'OK' WHERE id = ?", userId);
        long scheduleId = seedSchedule();

        ItemStateResult result = onRequestThread(() -> {
            User held = userRepository.findById(userId).orElseThrow();
            InvestmentSchedule stale = scheduleRepository.findTopByUserOrderByCreatedAtDesc(held).orElseThrow();
            assertThat(stale.getHoldReason()).as("sanity check: the request's copy has no hold").isNull();
            jdbc.update("UPDATE investment_schedules SET hold_reason = 'MANUAL_REVIEW' WHERE id = ?", scheduleId);
            return itemStateService.onLoginRequired(userId, ITEM, "ITEM_LOGIN_REQUIRED");
        });

        assertThat(result).isEqualTo(ItemStateResult.APPLIED);
        assertThat(scheduleColumn(scheduleId, "hold_reason")).isEqualTo("MANUAL_REVIEW");
        assertThat(scheduleColumn(scheduleId, "detection_state")).as("the handler's own write landed").isEqualTo("ITEM_BROKEN");
        assertThat(column("plaid_item_status")).isEqualTo("LOGIN_REQUIRED");
    }

    @Test
    void adminHoldIsRefusedOverAManualReviewCommittedAfterTheRead() {
        jdbc.update("UPDATE users SET plaid_item_status = 'OK' WHERE id = ?", userId);
        long scheduleId = seedSchedule();

        VerbResult result = onRequestThread(() -> {
            User held = userRepository.findById(userId).orElseThrow();
            scheduleRepository.findTopByUserOrderByCreatedAtDesc(held).orElseThrow();
            jdbc.update("UPDATE investment_schedules SET hold_reason = 'MANUAL_REVIEW' WHERE id = ?", scheduleId);
            return adminPaycheckService.hold("ops-test", userId, "ITEM_LOGIN_REQUIRED", "stale copy");
        });

        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(((ErrorCodeResponse) result.body()).code()).isEqualTo("HOLD_BLOCKING");
        assertThat(scheduleColumn(scheduleId, "hold_reason")).isEqualTo("MANUAL_REVIEW");
    }

    private String column(String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM users WHERE id = ?", String.class, userId);
    }

    private String scheduleColumn(long scheduleId, String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM investment_schedules WHERE id = ?", String.class, scheduleId);
    }
}
