package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Atomicity, against the real database: the settlement event and the FUNDING_COMPLETED status
 * change are one write or neither.
 *
 * <p>The unit test beside this one proves what is emitted. Only this one can prove WHEN: it drives
 * the real {@link InvestmentExecutionService} inside a transaction it then forces to roll back, and
 * shows the outbox row goes with it. If anyone later moves the record() call outside the committing
 * transaction, or calls it from a method with no transaction at all, this test is what fails.
 *
 * <p>Needs the MySQL the `ci` profile describes; {@link RequiresCiDatabase} skips it, with its
 * reason, anywhere that database is not reachable. Alpaca, encryption and email are mocked because
 * the `ci` profile points every external integration at a dead port on purpose; everything that
 * matters here, the service, the repositories, the outbox and the transaction, is real.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class InvestmentExecutionAchAnalyticsRollbackTest {

    private static final String TRANSFER = "xfer-rollback-test";

    @Autowired
    private InvestmentExecutionService investmentExecutionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InvestmentExecutionRepository executionRepository;

    @Autowired
    private InvestmentScheduleRepository scheduleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AlpacaService alpacaService;

    @MockitoBean
    private EncryptionService encryptionService;

    @MockitoBean
    private EmailService emailService;

    private TransactionTemplate transactions;
    private Long userId;
    private Long executionId;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);

        // Committed before the test body, so a rollback inside the body leaves the fixture alone.
        User user = new User("ach-rollback-" + UUID.randomUUID() + "@fredvested.com");
        user = userRepository.save(user);
        userId = user.getId();

        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setUser(user);
        schedule.setInvestmentAmount(new BigDecimal("400.00"));
        schedule.setMonthlyAmount(new BigDecimal("800.00"));
        schedule.setFrequency("BIWEEKLY");
        schedule.setStartDate(LocalDate.now().minusDays(20));
        schedule.setNextInvestmentDate(LocalDate.now().plusDays(1));
        schedule = scheduleRepository.save(schedule);
        // createdAt is stamped by @PrePersist, so the age this event reports has to be set in SQL.
        jdbcTemplate.update("UPDATE investment_schedules SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(20)), schedule.getId());

        InvestmentExecution execution = new InvestmentExecution(
                user, LocalDateTime.now().minusDays(2), new BigDecimal("400.00"), "stock", "VTI", "bank");
        execution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_INITIATED);
        // Older than the sweep's ten-minute cutoff, or it is not picked up at all.
        execution.setExecutionDate(LocalDateTime.now().minusDays(2));
        execution.setAlpacaTransferId(TRANSFER);
        execution.setAlpacaAccountId("acct-ci");
        executionId = executionRepository.save(execution).getId();

        when(encryptionService.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        // Only OUR transfer settles. Anything else the sweep happens to find in this schema stays
        // pending and untouched, so the assertions below are about this user only.
        when(alpacaService.checkTransferStatus(any(), any())).thenReturn(
                new AlpacaService.AlpacaTransferResponse(null, "PENDING", null, null, null));
        when(alpacaService.checkTransferStatus(any(), eq(TRANSFER))).thenReturn(
                new AlpacaService.AlpacaTransferResponse(
                        TRANSFER, "COMPLETE", new BigDecimal("400.00"), null, null));
        // Trading is not under test. It must simply not reach a network or throw.
        when(alpacaService.isFractionable(anyString())).thenReturn(true);
        when(alpacaService.placeBuyOrder(any(), anyString(), any())).thenReturn(
                new AlpacaService.AlpacaOrderResponse(null, "VTI", "FAILED", null,
                        new BigDecimal("400.00"), BigDecimal.ZERO, null, null, "ci: no broker"));
    }

    @AfterEach
    void tearDown() {
        // This test's own fixture, in a throwaway schema. The no-delete rule is about the real
        // ledger, not about rows a test invented.
        jdbcTemplate.update("DELETE FROM analytics_outbox WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM investment_trades WHERE investment_execution_id IN "
                + "(SELECT id FROM investment_executions WHERE user_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM investment_executions WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM investment_schedules WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbcTemplate.queryForList(
                "SELECT event_id, event_name, properties, status FROM analytics_outbox WHERE user_id = ?",
                userId);
    }

    private InvestmentExecution reloadExecution() {
        return executionRepository.findById(executionId).orElseThrow();
    }

    @Test
    @DisplayName("the transaction rolls back: no event row, and no settled execution either")
    void rollbackTakesTheEventWithIt() {
        transactions.execute(status -> {
            // Joins this transaction (REQUIRED), so the service cannot commit on its own.
            investmentExecutionService.checkFundingStatus();
            status.setRollbackOnly();
            return null;
        });

        assertThat(outboxRows())
                .as("an event that outlived its rollback would describe money that never moved")
                .isEmpty();

        InvestmentExecution execution = reloadExecution();
        assertThat(execution.getStatus()).isEqualTo(InvestmentExecution.ExecutionStatus.FUNDING_INITIATED);
        assertThat(execution.getFundingCompletedAt()).isNull();
    }

    @Test
    @DisplayName("the transaction commits: exactly one event row, banded, with its own event id")
    void commitLeavesExactlyOneEvent() {
        transactions.execute(status -> {
            investmentExecutionService.checkFundingStatus();
            return null;
        });

        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("event_name"))
                .isEqualTo(ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED);
        assertThat((String) rows.get(0).get("event_id")).isNotBlank();
        assertThat(rows.get(0).get("status")).isEqualTo("PENDING");
        // Bands, not dollars and not dates: the whole payload, asserted whole.
        assertThat(rows.get(0).get("properties"))
                .isEqualTo("{\"amount_band\":\"250_499\",\"days_since_schedule_band\":\"15_30\"}");

        assertThat(reloadExecution().getFundingCompletedAt())
                .as("the event and the fact it describes commit together")
                .isNotNull();
    }
}
