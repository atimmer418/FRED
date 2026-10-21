package com.investingapp.backend.controller;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * closeAccount used to swallow every exception and return a 500. Because it is @Transactional, and
 * a @Transactional method that RETURNS commits, a failure partway through left the account half
 * closed: schedules deleted, the schedule_cancelled row committed, and the user still present,
 * with the caller told only that something went wrong.
 *
 * These tests pin the fix from both ends: the failure path leaves NOTHING behind, and the success
 * path still works.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class CloseAccountRollbackTest {

    @Autowired
    private TradingController controller;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InvestmentScheduleRepository scheduleRepository;

    @Autowired
    private AnalyticsOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** The broker call is mocked so the test never leaves the machine. */
    @MockitoBean
    private com.investingapp.backend.service.AlpacaService alpacaService;

    /**
     * The stored account id is a fabricated 'ENC:acct', which the real service cannot decrypt.
     * Mocked so the test exercises the transaction, not the cipher.
     */
    @MockitoBean
    private com.investingapp.backend.service.EncryptionService encryptionService;

    private Long userId;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (userId != null) {
            jdbc.update("DELETE FROM analytics_outbox WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM investment_schedules WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    private void seedUserWithSchedule() {
        org.mockito.Mockito.when(encryptionService.decrypt(anyString())).thenReturn("acct-1");
        String email = "close-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, "
                + "alpaca_account_id) VALUES (?, ?, ?, 0, 'ENC:acct')",
                email, LocalDateTime.now(), UUID.randomUUID().toString());
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO investment_schedules (user_id, created_at, frequency, "
                + "investment_amount, monthly_amount, monthly_streak, next_investment_date, "
                + "start_date, status) VALUES (?, NOW(), 'BIWEEKLY', 100.00, 200.00, 0, "
                + "CURDATE(), CURDATE(), 'ACTIVE')", userId);

        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(email).password("x").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private long outboxRowsForUser() {
        return outboxRepository.countByUserIdAndEventName(
                userId, ServerAnalyticsEvent.SCHEDULE_CANCELLED);
    }

    @Test
    @DisplayName("a failure rolls back everything, including the analytics row")
    void failureRollsBackTheWholeTransaction() {
        seedUserWithSchedule();
        // The broker call succeeds; the failure happens AFTER the schedule delete and the outbox
        // write, which is exactly the window that used to commit a half-closed account.
        doThrow(new RuntimeException("broker said no")).when(alpacaService).closeAccount(anyString());

        assertThatThrownBy(() -> controller.closeAccount(
                SecurityContextHolder.getContext().getAuthentication()))
                .isInstanceOf(TradingController.AccountCloseFailedException.class);

        // Nothing may have survived: not the deletion, not the event.
        assertThat(userRepository.findById(userId)).isPresent();
        User user = userRepository.findById(userId).orElseThrow();
        assertThat(scheduleRepository.existsByUser(user)).isTrue();
        assertThat(outboxRowsForUser()).isZero();
    }

    @Test
    @DisplayName("the happy path still closes the account and records one cancellation")
    void successStillWorks() {
        seedUserWithSchedule();

        controller.closeAccount(SecurityContextHolder.getContext().getAuthentication());

        assertThat(userRepository.findById(userId)).isEmpty();
        List<Long> schedules = jdbc.queryForList(
                "SELECT id FROM investment_schedules WHERE user_id = ?", Long.class, userId);
        assertThat(schedules).isEmpty();
        // The ledger outlives the user it describes, which is the whole design of the outbox.
        assertThat(outboxRowsForUser()).isEqualTo(1);
    }
}
