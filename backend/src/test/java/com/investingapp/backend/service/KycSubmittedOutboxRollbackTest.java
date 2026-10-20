package com.investingapp.backend.service;

import com.investingapp.backend.dto.CreateAlpacaAccountRequest;
import com.investingapp.backend.model.User;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The atomicity promise, against a real database: a kyc_submitted row exists if and only if the
 * submission it describes committed.
 *
 * This needs the MySQL the `ci` profile describes, and {@link RequiresCiDatabase} skips it with a
 * printed reason where there is none, so a developer without that database still gets a green
 * suite. The test class is deliberately NOT @Transactional: it has to commit and roll back on its
 * own terms, and a test-managed transaction would roll the whole thing back and prove nothing.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class KycSubmittedOutboxRollbackTest {

    @Autowired
    private KycSubmissionService kycSubmissionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private Long userId;

    @BeforeEach
    void createUser() {
        transactions = new TransactionTemplate(transactionManager);
        User user = new User("kyc-outbox-" + UUID.randomUUID() + "@fredvested.com");
        userId = userRepository.save(user).getId();
    }

    @AfterEach
    void removeUser() {
        if (userId != null) {
            // The outbox is never pruned in production; a test's own rows are a different matter.
            jdbcTemplate.update("DELETE FROM analytics_outbox WHERE user_id = ?", userId);
            userRepository.deleteById(userId);
        }
    }

    private static Map<String, Object> brokerResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account_id", "alpaca-account-id");
        result.put("account_number", "alpaca-account-number");
        return result;
    }

    private static CreateAlpacaAccountRequest request() {
        CreateAlpacaAccountRequest request = new CreateAlpacaAccountRequest();
        request.setEmailAddress("kyc-outbox@fredvested.com");
        request.setGivenName("Ada");
        request.setFamilyName("Lovelace");
        request.setTaxId("000-00-0000");
        return request;
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbcTemplate.queryForList(
                "SELECT event_name, event_id, properties, person_properties FROM analytics_outbox "
                        + "WHERE user_id = ? ORDER BY id ASC", userId);
    }

    @Test
    @DisplayName("a committed submission leaves exactly one kyc_submitted row")
    void successWritesExactlyOneRow() {
        Boolean persisted = transactions.execute(status ->
                kycSubmissionService.persistSubmission(userId, brokerResult(), request()));

        assertThat(persisted).isTrue();

        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("event_name")).isEqualTo(ServerAnalyticsEvent.KYC_SUBMITTED);
        assertThat(rows.get(0).get("event_id")).isNotNull();
        assertThat(rows.get(0).get("properties")).isEqualTo("{\"attempt\":\"1\"}");

        // Nothing identifying reached the row, including through the person properties.
        String wholeRow = rows.get(0).get("properties") + "" + rows.get(0).get("person_properties");
        assertThat(wholeRow)
                .doesNotContain("Ada")
                .doesNotContain("Lovelace")
                .doesNotContain("000-00-0000")
                .doesNotContain("fredvested.com");

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getAccountStatus()).isEqualTo("SUBMITTED");
        assertThat(reloaded.getKycAttempts()).isEqualTo(1);
        assertThat(reloaded.getKycSubmittedAt()).isNotNull();
    }

    @Test
    @DisplayName("a rolled-back submission leaves no event behind")
    void rollbackLeavesNoOutboxRow() {
        transactions.execute(status -> {
            boolean persisted = kycSubmissionService.persistSubmission(userId, brokerResult(), request());
            assertThat(persisted).isTrue();
            // Whatever made the submission fail after the fact: the event must not survive it.
            status.setRollbackOnly();
            return null;
        });

        assertThat(outboxRows()).isEmpty();

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getAccountStatus()).isNull();
        assertThat(reloaded.getKycAttempts()).isZero();
        assertThat(reloaded.getKycSubmittedAt()).isNull();
    }

    @Test
    @DisplayName("the attempt counter is durable, so a resubmission bands as 2")
    void theAttemptCounterSurvivesAcrossTransactions() {
        // Nothing stops a second POST /create-account today: this is what the counter records.
        transactions.execute(status ->
                kycSubmissionService.persistSubmission(userId, brokerResult(), request()));
        transactions.execute(status ->
                kycSubmissionService.persistSubmission(userId, brokerResult(), request()));

        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("properties")).isEqualTo("{\"attempt\":\"1\"}");
        assertThat(rows.get(1).get("properties")).isEqualTo("{\"attempt\":\"2\"}");
        assertThat(userRepository.findById(userId).orElseThrow().getKycAttempts()).isEqualTo(2);
    }
}
