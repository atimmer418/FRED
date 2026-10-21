package com.investingapp.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.controller.TradingController;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Atomicity for schedule_cancelled, against the real database.
 *
 * <p>Both call sites destroy the schedule AND the user in one transaction: the broker rejecting a
 * KYC submission (AccountStatusService.handleRejected) and the user closing their own account
 * (TradingController.closeAccount). A Mockito test can only show that {@code record()} was called;
 * it has no transaction, so it cannot show that the event shares one with the deletion. These tests
 * drive the real beans and control the boundary themselves: rolled back, the row must be absent;
 * committed, there must be exactly one, and it must outlive the person it describes.
 *
 * <p>The committed cases also prove something the annotations alone cannot. AnalyticsOutboxService
 * REFUSES to write outside a transaction, so if the {@code @Transactional} on either call site ever
 * stopped applying, the row would simply not be there and these tests would fail.
 *
 * <p>Deliberately NOT annotated {@code @Transactional}: a test-managed rollback-everything
 * transaction would make the committed and rolled-back cases look identical. Each test manages its
 * own boundary and {@link #cleanUp()} removes the rows by hand.
 *
 * <p>{@link AlpacaService} is the one collaborator replaced by a mock. The ci profile points Alpaca
 * at a dead local port, so the real broker call inside closeAccount would throw and the method would
 * never reach the deletes this test is about.
 *
 * <p>{@link RequiresCiDatabase} skips the whole class, with its reason, where no `ci` database is
 * reachable, which is the repo's idiom for database-backed tests. (The annotation is itself
 * meta-annotated {@code @ExtendWith(RequiresCiDatabase.Condition.class)}; the extension is the
 * nested Condition, not the annotation type.)
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ScheduleCancelledOutboxRollbackTest {

    private static final String EVENT = "schedule_cancelled";

    @Autowired
    private TradingController tradingController;
    @Autowired
    private AccountStatusService accountStatusService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InvestmentScheduleRepository investmentScheduleRepository;
    @Autowired
    private EncryptionService encryptionService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    /** The broker call inside closeAccount. The ci profile has nothing listening for it. */
    @MockitoBean
    private AlpacaService alpacaService;

    private TransactionTemplate transaction;

    private Long userId;
    private String publicId;
    private String email;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        // Production never deletes outbox rows; a test has to, or it leaves the ledger it just
        // wrote behind in whatever schema it ran against.
        jdbcTemplate.update("DELETE FROM analytics_outbox WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM email_log WHERE recipient = ?", email);
        jdbcTemplate.update("DELETE FROM investment_schedules WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_sessions WHERE user_id = ?", userId);
        jdbcTemplate.update(
                "DELETE FROM portfolio_items WHERE portfolio_id IN "
                        + "(SELECT id FROM portfolios WHERE user_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM portfolios WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        userId = null;
        publicId = null;
        email = null;
    }

    /** A committed user with a brokerage account, which closeAccount requires. */
    private User givenCommittedUser() {
        email = "schedule-cancelled-" + UUID.randomUUID() + "@fredvested.test";
        User fresh = new User(email);
        fresh.setAccountStatus("APPROVAL_PENDING");
        fresh.setKycSubmittedAt(LocalDateTime.now().minusDays(2));
        fresh.setAlpacaAccountId(encryptionService.encrypt("acct-" + UUID.randomUUID()));
        User saved = userRepository.save(fresh);
        userId = saved.getId();
        publicId = saved.getPublicId();
        assertThat(userId).as("the test user must be committed before the real path runs").isNotNull();
        return saved;
    }

    /** A committed, running schedule for that user: the state a cancellation needs to cancel. */
    private void givenCommittedSchedule(User owner) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setUser(owner);
        schedule.setStartDate(LocalDate.now());
        schedule.setNextInvestmentDate(LocalDate.now().plusDays(7));
        schedule.setInvestmentAmount(new BigDecimal("100.00"));
        schedule.setMonthlyAmount(new BigDecimal("200.00"));
        schedule.setFrequency("BIWEEKLY");
        schedule.setStatus(com.investingapp.backend.model.InvestmentSchedule.Status.ACTIVE);
        investmentScheduleRepository.save(schedule);
        assertThat(countSchedules()).isEqualTo(1);
    }

    private Authentication authenticationFor(String username) {
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("not-used")
                .authorities("ROLE_USER")
                .build();
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbcTemplate.queryForList(
                "SELECT event_name, event_id, properties, person_properties, status, user_id, public_id "
                        + "FROM analytics_outbox WHERE user_id = ? ORDER BY id", userId);
    }

    private List<Map<String, Object>> cancellationRows() {
        return outboxRows().stream().filter(row -> EVENT.equals(row.get("event_name"))).toList();
    }

    private int countSchedules() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM investment_schedules WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> propertiesOf(Map<String, Object> row) {
        try {
            return objectMapper.readValue((String) row.get("properties"),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new AssertionError("properties was not readable JSON: " + row.get("properties"), e);
        }
    }

    /** Nothing identifying may reach the row, including through the person properties. */
    private void assertNothingIdentifying(Map<String, Object> row) {
        String wholeRow = row.get("properties") + "" + row.get("person_properties");
        assertThat(wholeRow)
                .doesNotContain(email)
                .doesNotContain("fredvested");
    }

    /** The two enum values this event is allowed to carry, and nothing else. */
    private void assertExactly(Map<String, Object> row, String initiator, String reason) {
        assertThat(row.get("event_name")).isEqualTo(EVENT);
        assertThat(row.get("event_id")).isNotNull();
        assertThat(row.get("public_id")).isEqualTo(publicId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(propertiesOf(row)).containsExactly(
                entry("initiator", initiator),
                entry("reason", reason));
        assertNothingIdentifying(row);
    }

    // --- The user closes their own account ------------------------------------------------------

    @Test
    @DisplayName("a committed closure leaves exactly one cancellation, and it survives the user's deletion")
    void committedClosureKeepsTheCancellationAfterTheUserIsGone() {
        User user = givenCommittedUser();
        givenCommittedSchedule(user);

        // No surrounding transaction on purpose: the only transaction here is the one the
        // @Transactional controller method opens. The outbox refuses to write without one, so a row
        // appearing at all is the proof that the event and the deletes share it.
        ResponseEntity<Map<String, Object>> response =
                tradingController.closeAccount(authenticationFor(email));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.existsById(userId)).isFalse();
        assertThat(countSchedules()).isZero();

        List<Map<String, Object>> rows = cancellationRows();
        assertThat(rows).hasSize(1);
        assertExactly(rows.get(0), "user", "account_closed");
    }

    @Test
    @DisplayName("a rolled back closure leaves no cancellation, and the schedule is still there")
    void rolledBackClosureLeavesNoCancellation() {
        User user = givenCommittedUser();
        givenCommittedSchedule(user);

        transaction.execute(status -> {
            ResponseEntity<Map<String, Object>> response =
                    tradingController.closeAccount(authenticationFor(email));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // The state change is abandoned, so the event describing it must be abandoned too.
            status.setRollbackOnly();
            return null;
        });

        assertThat(cancellationRows()).isEmpty();
        // Sanity check: the rollback really did discard the state change too.
        assertThat(userRepository.existsById(userId)).isTrue();
        assertThat(countSchedules()).isEqualTo(1);
    }

    @Test
    @DisplayName("closing an account that never had a schedule commits no cancellation at all")
    void closureWithoutAScheduleCommitsNoEvent() {
        givenCommittedUser();

        ResponseEntity<Map<String, Object>> response =
                tradingController.closeAccount(authenticationFor(email));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.existsById(userId)).isFalse();
        // There was nothing to cancel. An event here would invent churn for every user who closed
        // an account without ever setting a schedule up.
        assertThat(outboxRows()).isEmpty();
    }

    // --- The broker rejects the KYC submission --------------------------------------------------

    @Test
    @DisplayName("a committed rejection cancels the schedule and the event outlives both")
    void committedRejectionKeepsTheCancellation() {
        User user = givenCommittedUser();
        givenCommittedSchedule(user);
        Long id = userId;

        transaction.executeWithoutResult(status ->
                accountStatusService.handleRejected(userRepository.findById(id).orElseThrow()));

        assertThat(userRepository.existsById(id)).isFalse();
        assertThat(countSchedules()).isZero();

        // Two events on this path: the decision, then the cancellation it caused.
        assertThat(outboxRows()).extracting(row -> row.get("event_name"))
                .containsExactly("kyc_decided", EVENT);

        List<Map<String, Object>> rows = cancellationRows();
        assertThat(rows).hasSize(1);
        assertExactly(rows.get(0), "system", "kyc_rejected");
    }

    @Test
    @DisplayName("a rolled back rejection leaves no cancellation, and the schedule is still there")
    void rolledBackRejectionLeavesNoCancellation() {
        User user = givenCommittedUser();
        givenCommittedSchedule(user);
        Long id = userId;

        transaction.execute(status -> {
            accountStatusService.handleRejected(userRepository.findById(id).orElseThrow());
            status.setRollbackOnly();
            return null;
        });

        assertThat(outboxRows()).isEmpty();
        assertThat(userRepository.existsById(id)).isTrue();
        assertThat(countSchedules()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rejected user who never had a schedule gets a decision but no cancellation")
    void rejectionWithoutAScheduleCommitsNoCancellation() {
        givenCommittedUser();
        Long id = userId;

        transaction.executeWithoutResult(status ->
                accountStatusService.handleRejected(userRepository.findById(id).orElseThrow()));

        assertThat(userRepository.existsById(id)).isFalse();
        assertThat(outboxRows()).extracting(row -> row.get("event_name"))
                .containsExactly("kyc_decided");
    }
}
