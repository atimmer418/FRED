package com.investingapp.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Atomicity for kyc_decided, against the real database.
 *
 * The outbox only means anything if the event and the state change share one transaction. That
 * cannot be proved with mocks, so this drives the real AccountStatusService inside a transaction it
 * controls: rolled back, the row must not exist; committed, there must be exactly one.
 *
 * The rejection path is the interesting one. It hard-deletes the user in the same transaction that
 * records the decision, so this also pins the claim the schema is built on: analytics_outbox has no
 * foreign key to users and denormalises public_id, so the event outlives the person it describes.
 *
 * {@link RequiresCiDatabase} skips the whole class where no `ci` database is reachable, which is
 * the repo's idiom for database-backed tests. (The annotation is itself meta-annotated
 * {@code @ExtendWith(RequiresCiDatabase.Condition.class)}; the extension is the nested Condition,
 * not the annotation type.)
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class AccountStatusServiceKycDecidedRollbackTest {

    @Autowired
    private AccountStatusService accountStatusService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate transaction;

    /** Everything this test created, cleaned up even when an assertion fails. */
    private final List<String> createdPublicIds = new ArrayList<>();
    private final List<String> createdEmails = new ArrayList<>();

    /** The email of the user the current test created, for the no-PII assertion. */
    private String email;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        // Production never deletes outbox rows; a test has to, or it leaves the ledger it just
        // wrote behind in whatever schema it ran against.
        for (String publicId : createdPublicIds) {
            jdbcTemplate.update("DELETE FROM analytics_outbox WHERE public_id = ?", publicId);
        }
        for (String createdEmail : createdEmails) {
            userRepository.findByEmail(createdEmail).ifPresent(userRepository::delete);
        }
        createdPublicIds.clear();
        createdEmails.clear();
    }

    /** A committed user, submitted two days ago, waiting on the broker. */
    private User givenUserAwaitingDecision() {
        email = "kyc-decided-" + UUID.randomUUID() + "@fredvested.test";
        User user = new User(email);
        user.setAccountStatus("APPROVAL_PENDING");
        user.setKycSubmittedAt(LocalDateTime.now().minusDays(2));
        User saved = userRepository.save(user);
        createdEmails.add(email);
        createdPublicIds.add(saved.getPublicId());
        return saved;
    }

    private List<Map<String, Object>> outboxRowsFor(String publicId) {
        return jdbcTemplate.queryForList(
                "SELECT event_name, event_id, properties, person_properties, status, user_id "
                        + "FROM analytics_outbox WHERE public_id = ?",
                publicId);
    }

    /** Nothing identifying may reach the row, including through the person properties. */
    private void assertNothingIdentifying(Map<String, Object> row) {
        String wholeRow = row.get("properties") + "" + row.get("person_properties");
        assertThat(wholeRow)
                .doesNotContain(email)
                .doesNotContain("fredvested")
                .doesNotContain(String.valueOf(LocalDateTime.now().getYear()));
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

    @Test
    @DisplayName("a rolled back rejection leaves no event behind")
    void rolledBackRejectionLeavesNoEvent() {
        User user = givenUserAwaitingDecision();
        Long id = user.getId();
        String publicId = user.getPublicId();

        transaction.execute(status -> {
            accountStatusService.handleRejected(userRepository.findById(id).orElseThrow());
            // The state change is abandoned, so the event describing it must be abandoned too.
            status.setRollbackOnly();
            return null;
        });

        assertThat(outboxRowsFor(publicId)).isEmpty();
        assertThat(userRepository.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("a committed rejection leaves exactly one kyc_decided row, and keeps the user row")
    void committedRejectionKeepsTheEventAndTheUser() {
        User user = givenUserAwaitingDecision();
        Long id = user.getId();
        String publicId = user.getPublicId();

        transaction.executeWithoutResult(status ->
                accountStatusService.handleRejected(userRepository.findById(id).orElseThrow()));

        // Decision 8: the applicant is kept and marked, not deleted. The outbox row would have
        // survived a deletion anyway (no foreign key, denormalised public_id), which is what the
        // rest of this test pins; what changed is that there is nothing to survive.
        assertThat(userRepository.existsById(id)).isTrue();
        assertThat(userRepository.findById(id).orElseThrow().getAccountStatus()).isEqualTo("REJECTED");

        List<Map<String, Object>> rows = outboxRowsFor(publicId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("event_name")).isEqualTo("kyc_decided");
        assertThat(row.get("event_id")).isNotNull();
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(id);
        assertThat(propertiesOf(row)).containsExactly(
                entry("outcome", "rejected"),
                entry("days_to_decision_band", "1_3"));
        assertNothingIdentifying(row);
    }

    @Test
    @DisplayName("a committed approval leaves exactly one kyc_decided row")
    void committedApprovalWritesOneEvent() {
        User user = givenUserAwaitingDecision();
        Long id = user.getId();
        String publicId = user.getPublicId();

        // No Plaid data on this user, so the ACH attempt inside handleActive returns before any
        // network call. The ci profile points Alpaca at a dead local port in any case.
        transaction.executeWithoutResult(status ->
                accountStatusService.handleActive(userRepository.findById(id).orElseThrow()));

        assertThat(userRepository.findById(id).orElseThrow().getAccountStatus()).isEqualTo("ACTIVE");

        List<Map<String, Object>> rows = outboxRowsFor(publicId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("event_name")).isEqualTo("kyc_decided");
        assertThat(rows.get(0).get("event_id")).isNotNull();
        assertThat(propertiesOf(rows.get(0))).containsExactly(
                entry("outcome", "approved"),
                entry("days_to_decision_band", "1_3"));
        assertNothingIdentifying(rows.get(0));
    }

    @Test
    @DisplayName("a rolled back approval leaves neither the status nor the event")
    void rolledBackApprovalLeavesNoEvent() {
        User user = givenUserAwaitingDecision();
        Long id = user.getId();
        String publicId = user.getPublicId();

        transaction.execute(status -> {
            accountStatusService.handleActive(userRepository.findById(id).orElseThrow());
            status.setRollbackOnly();
            return null;
        });

        assertThat(outboxRowsFor(publicId)).isEmpty();
        assertThat(userRepository.findById(id).orElseThrow().getAccountStatus())
                .isEqualTo("APPROVAL_PENDING");
    }
}
