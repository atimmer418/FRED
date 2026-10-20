package com.investingapp.backend.service;

import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.model.PasskeyCredential;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.PasskeyCredentialRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Atomicity for the two registration events, against the real database.
 *
 * <p>The promise the outbox makes is that an event and the state change it describes land together
 * or not at all. A Mockito test cannot show that, because it has no transaction. So these drive the
 * real {@link WebAuthnService} through a real {@link TransactionTemplate}, force a rollback from
 * inside it, and look for the row afterwards.
 *
 * <p>Each pair checks both directions, because only the pair is meaningful: a rollback test alone
 * passes just as well against code that never writes anything.
 *
 * <p>{@link RequiresCiDatabase} skips the class, with its reason, where no `ci` database exists. It
 * is the repository's own annotation and carries the {@code @ExtendWith} condition itself.
 *
 * <p>One limitation worth stating. The passkey pair drives
 * {@link WebAuthnService#countPasskeysBeforeSave} and
 * {@link WebAuthnService#recordPasskeyCreated}, the two methods finishRegistrationFlow is wired
 * from, alongside a real credential save in the same transaction. The full finishRegistrationFlow
 * cannot be driven headlessly: it needs a genuine attestation from a platform authenticator, which
 * no test in this repository can produce.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class RegistrationAnalyticsOutboxRollbackTest {

    /** The literal wire names, so a rename of the constants cannot pass unnoticed. */
    private static final String FREEDOM_PLAN_CREATED = "freedom_plan_created";
    private static final String PASSKEY_CREATED = "passkey_created";

    @Autowired
    private WebAuthnService webAuthnService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasskeyCredentialRepository passkeyCredentialRepository;
    @Autowired
    private AnalyticsOutboxRepository outboxRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private String email;
    /**
     * The bean behind the transactional proxy. startRegistrationFlow is called through the proxy,
     * because its boundary is the thing under test; the two package-private passkey helpers are
     * called on the target, so the test does not depend on how a proxy treats package-private
     * methods. Neither helper opens a transaction, so both run in the template's.
     */
    private WebAuthnService target;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        target = AopTestUtils.getUltimateTargetObject(webAuthnService);
        // A fresh address every run: these tests commit, and the email column is unique.
        email = "analytics-registration-" + UUID.randomUUID() + "@example.invalid";
    }

    /**
     * Rows in this table are never deleted in production, but a test must not leave its own
     * fixtures behind. Credentials and progress go with the user by cascade; the outbox has no
     * foreign key, by design, so its rows are removed here explicitly.
     */
    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(status -> userRepository.findByEmail(email).ifPresent(user -> {
            passkeyCredentialRepository.deleteAll(passkeyCredentialRepository.findAllByUser(user));
            outboxRepository.deleteAll(outboxRepository.findAll().stream()
                    .filter(row -> user.getId().equals(row.getUserId()))
                    .toList());
            userRepository.delete(user);
        }));
    }

    // --- freedom_plan_created ------------------------------------------------------------------

    @Test
    @DisplayName("freedom_plan_created: rolled back with the user row it describes")
    void freedomPlanCreatedIsRolledBackWithTheUserRow() {
        long[] userId = new long[1];

        tx.executeWithoutResult(status -> {
            webAuthnService.startRegistrationFlow(
                    email, "yield", "12.5", 1_500_000.0, 60_000.0, 400.0);

            User created = userRepository.findByEmail(email).orElseThrow();
            userId[0] = created.getId();
            assertEquals(1L, outboxRepository.countByUserIdAndEventName(
                            created.getId(), FREEDOM_PLAN_CREATED),
                    "the event is written inside the transaction, not after it");

            status.setRollbackOnly();
        });

        assertTrue(userRepository.findByEmail(email).isEmpty(),
                "the user row did not survive the rollback");
        assertEquals(0L, outboxRepository.countByUserIdAndEventName(userId[0], FREEDOM_PLAN_CREATED),
                "and neither did the event that described it");
    }

    @Test
    @DisplayName("freedom_plan_created: one committed row, named, identified and banded")
    void freedomPlanCreatedCommitsExactlyOneRow() {
        tx.executeWithoutResult(status -> webAuthnService.startRegistrationFlow(
                email, "yield", "12.5", 1_500_000.0, 60_000.0, 400.0));

        User created = userRepository.findByEmail(email).orElseThrow();
        List<AnalyticsOutboxEntry> rows = rowsFor(created.getId(), FREEDOM_PLAN_CREATED);

        assertEquals(1, rows.size(), "exactly one event for one created plan");
        AnalyticsOutboxEntry row = rows.get(0);
        assertEquals(FREEDOM_PLAN_CREATED, row.getEventName());
        assertNotNull(row.getEventId(), "the publisher de-duplicates on this, so it is never null");
        assertEquals(created.getPublicId(), row.getPublicId(), "the distinct id is the public id");
        assertNotNull(row.getPersonProperties());
        // 60,000 a year is 5,000 a month; 400 a month invested; 12.5 years.
        assertEquals("{\"strategy\":\"yield\",\"monthly_income_band\":\"5k_9999\","
                        + "\"monthly_invest_band\":\"250_499\",\"years_to_freedom\":\"11_20\"}",
                row.getProperties(),
                "banded, in taxonomy order, with no raw amount anywhere in it");
    }

    // --- passkey_created ----------------------------------------------------------------------

    @Test
    @DisplayName("passkey_created: rolled back with the credential it describes")
    void passkeyCreatedIsRolledBackWithTheCredential() {
        User user = registerAndCommit();
        String externalId = "ci-rollback-" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            User inTransaction = userRepository.findByEmail(email).orElseThrow();

            Long before = target.countPasskeysBeforeSave(inTransaction);
            assertEquals(0L, before, "a fresh registration has no credential yet");

            passkeyCredentialRepository.save(credential(inTransaction, externalId));
            target.recordPasskeyCreated(inTransaction, before);

            assertEquals(1L, outboxRepository.countByUserIdAndEventName(user.getId(), PASSKEY_CREATED),
                    "the event is written inside the transaction, not after it");

            status.setRollbackOnly();
        });

        assertTrue(passkeyCredentialRepository.findByExternalId(externalId).isEmpty(),
                "the credential did not survive the rollback");
        assertEquals(0L, outboxRepository.countByUserIdAndEventName(user.getId(), PASSKEY_CREATED),
                "and neither did the event that described it");
    }

    @Test
    @DisplayName("passkey_created: one committed row, and the count says it is the first")
    void passkeyCreatedCommitsExactlyOneRow() {
        User user = registerAndCommit();
        String externalId = "ci-commit-" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            User inTransaction = userRepository.findByEmail(email).orElseThrow();
            Long before = target.countPasskeysBeforeSave(inTransaction);
            passkeyCredentialRepository.save(credential(inTransaction, externalId));
            target.recordPasskeyCreated(inTransaction, before);
        });

        List<AnalyticsOutboxEntry> rows = rowsFor(user.getId(), PASSKEY_CREATED);

        assertEquals(1, rows.size(), "exactly one event for one credential");
        AnalyticsOutboxEntry row = rows.get(0);
        assertEquals(PASSKEY_CREATED, row.getEventName());
        assertNotNull(row.getEventId());
        assertEquals(user.getPublicId(), row.getPublicId());
        assertEquals("{\"is_first_passkey\":true}", row.getProperties());
        assertTrue(passkeyCredentialRepository.findByExternalId(externalId).isPresent(),
                "the fact the event describes is committed too");
    }

    /**
     * A second credential for the same user reports false, from the live count: the question the
     * property answers is "is this their first passkey right now".
     */
    @Test
    @DisplayName("passkey_created: a second credential reports is_first_passkey false")
    void secondPasskeyCommitsIsFirstFalse() {
        User user = registerAndCommit();

        tx.executeWithoutResult(status -> {
            User inTransaction = userRepository.findByEmail(email).orElseThrow();
            passkeyCredentialRepository.save(
                    credential(inTransaction, "ci-first-" + UUID.randomUUID()));
        });

        tx.executeWithoutResult(status -> {
            User inTransaction = userRepository.findByEmail(email).orElseThrow();
            Long before = target.countPasskeysBeforeSave(inTransaction);
            assertEquals(1L, before, "the committed credential is counted");
            passkeyCredentialRepository.save(
                    credential(inTransaction, "ci-second-" + UUID.randomUUID()));
            target.recordPasskeyCreated(inTransaction, before);
        });

        List<AnalyticsOutboxEntry> rows = rowsFor(user.getId(), PASSKEY_CREATED);
        assertEquals(1, rows.size());
        assertEquals("{\"is_first_passkey\":false}", rows.get(0).getProperties());
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Commits a real registration, so a passkey test starts from a user that exists. */
    private User registerAndCommit() {
        tx.executeWithoutResult(status -> webAuthnService.startRegistrationFlow(
                email, "yield", "12.5", 1_500_000.0, 60_000.0, 400.0));
        return userRepository.findByEmail(email).orElseThrow();
    }

    private static PasskeyCredential credential(User user, String externalId) {
        PasskeyCredential credential =
                new PasskeyCredential(user, externalId, "Y2ktdGVzdC1wdWJsaWMta2V5", 0L, "public-key");
        credential.setFriendlyName("ci test");
        return credential;
    }

    /**
     * Filtered in memory rather than with a derived query: the repository has no finder for this,
     * and the table is a test-sized ledger in CI.
     */
    private List<AnalyticsOutboxEntry> rowsFor(Long userId, String eventName) {
        return outboxRepository.findAll().stream()
                .filter(row -> userId.equals(row.getUserId()) && eventName.equals(row.getEventName()))
                .toList();
    }
}
