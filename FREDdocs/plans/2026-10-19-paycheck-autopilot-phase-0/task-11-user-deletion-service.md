### Task 11: user-deletion-service

One deletion path, `UserDeletionService.delete(user, reason)`, used by `AccountStatusService.handleRejected`, the close-account endpoint and a dev-profile admin verb. It refuses while money is in flight (any `FUNDING_INITIATED` execution) or settled fewer than 60 days ago (decision 22), deletes the Alpaca relationship outside any transaction, then in one transaction queues `/item/remove`, purges the webhook inbox and every FK-bearing child row, and deletes the user. Spec 7.9 (deletion bullet), 2.9 (last sentence), 15 decision 22.

Decision taken here (RISKS [execution], first bullet): V6 adds no `ON DELETE CASCADE` to `beneficiaries` (V1:40), `drip_executions` (V1:108), `investment_executions` (V1:143) or `investment_trades -> investment_executions` (V1:186), so the service deletes those rows explicitly, children before parents. By the time the 60-day window has passed every transfer is settled, so dropping the execution history with the user is acceptable; the authorization ledger (`ach_authorizations`, Phase 2) is the row that must outlive the user, and it has `user_id ON DELETE SET NULL` from V6.

How spec 7.9's purge list maps onto Phase 0: `paycheck_streams`, `paycheck_events` and `paycheck_cycles` have no entity yet and their `user_id` FKs are `ON DELETE CASCADE` in V6, so the user delete purges them; `plaid_sync_cursor` is a column on `users` and goes with the row; `plaid_webhook_events` and `plaid_item_removals` have no FK, so the inbox is purged explicitly here and the removal row is written on purpose (the drainer needs it after the user is gone); `ach_authorizations` has no writer in Phase 0, so there is nothing to anonymize, and the V6 `ON DELETE SET NULL` detaches any future row (anonymization lands with the Phase 2 entity). Tables that carry a `user_id` without any FK (`tlh_user_states`, `tlh_harvest_events`, `tlh_wash_windows`, `tax_lots`, `tax_lot_disposals`, `chat_messages`, `chat_action_audit`, `conversation_insights`, `email_log`, `analytics_outbox`) are left exactly as today's delete leaves them: orphaned rows, no constraint violation, out of scope for Phase 0.

Line numbers below are HEAD-accurate. T10 lands before this task and adds lines to `AccountStatusService.java` (a field, a ctor parameter, an assignment) and to `AccountStatusServiceKycDecidedTest.java` (one `@Mock`, one ctor argument), so anchor each Edit on the quoted text, not the number.

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/service/DeletionDeferredException.java`
- Create: `backend/src/main/java/com/investingapp/backend/service/UserDeletionService.java`
- Create: `backend/src/main/java/com/investingapp/backend/controller/DevUserController.java`
- Modify: `backend/src/main/java/com/investingapp/backend/repository/InvestmentExecutionRepository.java:131` (add `deleteAllByUser` after `existsByUserAndFundingCompletedAtIsNotNull`)
- Modify: `backend/src/main/java/com/investingapp/backend/repository/InvestmentTradeRepository.java:46-47` (add `deleteAllByUserId` after `findByUserIdAndSymbolAndStatusInAndCreatedAtAfter`)
- Modify: `backend/src/main/java/com/investingapp/backend/repository/DripExecutionRepository.java:47-48` (add `deleteAllByUser` after `existsByUserAndSymbolAndStatusIn`)
- Modify: `backend/src/main/java/com/investingapp/backend/repository/BeneficiaryRepository.java:3-5` (import) and `:113` (add `deleteAllByUser` after `findDuplicateBeneficiaries`)
- Modify: `backend/src/main/java/com/investingapp/backend/service/AccountStatusService.java:25-53` (field + ctor, after T10's `achRelationshipService`), `:127-157` (javadoc + `handleRejected`)
- Modify: `backend/src/main/java/com/investingapp/backend/controller/TradingController.java:3-24` (imports), `:45-52` (the three repository fields), `:297-346` (javadoc through the closing brace of `closeAccount`)
- Modify: `backend/src/main/java/com/investingapp/backend/config/DevToolsProdGuard.java:3` (import), `:43` (insert `addIfPresent` after this line)
- Test: `backend/src/test/java/com/investingapp/backend/service/UserDeletionServiceTest.java` (new)
- Test: `backend/src/test/java/com/investingapp/backend/service/AccountStatusServiceKycDecidedTest.java:51-66` (mocks), `:78-88` (ctor arity), `:154-174` (rejection verifies the one path), plus one new deferral case
- Test: `backend/src/test/java/com/investingapp/backend/controller/TradingControllerCloseAccountTest.java` (new)
- Test: `backend/src/test/java/com/investingapp/backend/controller/DevUserControllerTest.java` (new)
- Test: `backend/src/test/java/com/investingapp/backend/config/DevToolsGatingTest.java:3` (import), `:106` (new guard case after `prodGuardFailsStartupWhenDevBeanPresent`)

**Interfaces:**
- Consumes (T06): `InvestmentExecutionRepository.existsByUserAndStatus(User user, ExecutionStatus status) -> boolean` and `Optional<InvestmentExecution> findTopByUserAndFundingCompletedAtIsNotNullOrderByFundingCompletedAtDesc(User user)`
- Consumes (T10): `AlpacaApiService.deleteAchRelationship(String accountId, String relationshipId) -> boolean`
- Consumes (T09): `PlaidItemLifecycleService.enqueueRemoval(User user)` (`@Transactional(propagation = MANDATORY)`; copies `plaidAccessToken` and `plaidItemId` into `plaid_item_removals`)
- Consumes (T07): `PlaidWebhookEventRepository.deleteByUserId(Long userId) -> long`
- Consumes (T08): `com.investingapp.backend.dto.ErrorCodeResponse(String code, String message)` record
- Consumes (T02): `com.investingapp.backend.util.EasternTime.now() -> LocalDateTime`
- Consumes (existing): `InvestmentScheduleRepository.deleteAllByUser(User)` (:59), `UserSessionRepository.deleteAllByUserId(Long)` (:14; `UserSession.user` is a `@ManyToOne`, Spring Data resolves `user.id`), `PortfolioRepository.findByUser(User)` (:16), `EncryptionService.decrypt(String)` (:106; `decrypt(null)` returns null), `AlpacaService.closeAccount(String)` (:925, no checked exceptions, throws `RuntimeException` on a non-2xx), `UserRepository.findUsersWithPendingAccountStatus()` (:56-57, excludes REJECTED)
- Produces: `public class UserDeletionService { public enum DeletionReason { KYC_REJECTED, ACCOUNT_CLOSED, ADMIN } public Optional<String> deferralReason(User user); public void delete(User user, DeletionReason reason) throws DeletionDeferredException }` (not `@Transactional`; the `TransactionTemplate` inside uses default REQUIRED propagation so it joins the status poller's transaction and rolls back with the `kyc_decided` outbox row, and opens its own transaction when called from the controller)
- Produces: `public class DeletionDeferredException extends RuntimeException { public final String reason; }` with reasons `"FUNDING_INITIATED"` / `"SETTLEMENT_WINDOW"`
- Produces: repository derived deletes `InvestmentExecutionRepository.deleteAllByUser(User)`, `InvestmentTradeRepository.deleteAllByUserId(Long)`, `DripExecutionRepository.deleteAllByUser(User)`, `BeneficiaryRepository.deleteAllByUser(User)` (same shape as the existing `InvestmentScheduleRepository.deleteAllByUser`, no `@Transactional` needed: every caller is inside a transaction)
- Produces: `AccountStatusService` ctor gains a 10th parameter `UserDeletionService userDeletionService` (after T10's `AchRelationshipService achRelationshipService`); `investmentScheduleRepository` stays as field and ctor parameter even though `handleRejected` no longer uses it (two test classes construct the service positionally)
- Produces: `TradingController.closeAccount(Authentication) -> ResponseEntity<?>` (no `@Transactional`; 409 `ErrorCodeResponse("DELETION_DEFERRED", reason)`)
- Produces: `DevUserController` `POST /api/dev/users/{id}/delete` -> 200 `{deleted:true,userId}` / 404 / 409 `ErrorCodeResponse("DELETION_DEFERRED", reason)`; `@Profile({"local","dev","test","ci"})`, covered by `DevToolsKeyFilter` (`/api/dev/*`, DevToolsConfig.java:53; a servlet `/*` suffix matches every deeper path) and `SecurityConfig.java:118`

- [ ] **Step 1: Create the exception and the failing UserDeletionServiceTest**

`backend/src/main/java/com/investingapp/backend/service/DeletionDeferredException.java`:

```java
package com.investingapp.backend.service;

/** Thrown by UserDeletionService.delete when money is still in flight; the user row stays. */
public class DeletionDeferredException extends RuntimeException {

    /** "FUNDING_INITIATED" or "SETTLEMENT_WINDOW". */
    public final String reason;

    public DeletionDeferredException(String reason) {
        super("User deletion deferred: " + reason);
        this.reason = reason;
    }
}
```

`backend/src/test/java/com/investingapp/backend/service/UserDeletionServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.Portfolio;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.BeneficiaryRepository;
import com.investingapp.backend.repository.DripExecutionRepository;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.InvestmentTradeRepository;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.repository.PortfolioRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.service.UserDeletionService.DeletionReason;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one deletion path. What matters: it refuses while money is in flight, it talks to the broker
 * BEFORE the database transaction opens, and inside that transaction every FK-bearing child goes
 * before its parent with the user last. The transaction manager is a mock, so ordering is proved
 * against getTransaction/commit, not against a real database.
 */
class UserDeletionServiceTest {

    private UserRepository userRepository;
    private InvestmentExecutionRepository executionRepository;
    private InvestmentTradeRepository tradeRepository;
    private DripExecutionRepository dripExecutionRepository;
    private BeneficiaryRepository beneficiaryRepository;
    private InvestmentScheduleRepository investmentScheduleRepository;
    private UserSessionRepository userSessionRepository;
    private PortfolioRepository portfolioRepository;
    private PlaidWebhookEventRepository plaidWebhookEventRepository;
    private PlaidItemLifecycleService plaidItemLifecycleService;
    private AlpacaApiService alpacaApiService;
    private EncryptionService encryptionService;
    private PlatformTransactionManager transactionManager;
    private UserDeletionService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        executionRepository = mock(InvestmentExecutionRepository.class);
        tradeRepository = mock(InvestmentTradeRepository.class);
        dripExecutionRepository = mock(DripExecutionRepository.class);
        beneficiaryRepository = mock(BeneficiaryRepository.class);
        investmentScheduleRepository = mock(InvestmentScheduleRepository.class);
        userSessionRepository = mock(UserSessionRepository.class);
        portfolioRepository = mock(PortfolioRepository.class);
        plaidWebhookEventRepository = mock(PlaidWebhookEventRepository.class);
        plaidItemLifecycleService = mock(PlaidItemLifecycleService.class);
        alpacaApiService = mock(AlpacaApiService.class);
        encryptionService = mock(EncryptionService.class);
        transactionManager = mock(PlatformTransactionManager.class);

        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(executionRepository.existsByUserAndStatus(any(User.class), any(ExecutionStatus.class)))
                .thenReturn(false);
        when(executionRepository.findTopByUserAndFundingCompletedAtIsNotNullOrderByFundingCompletedAtDesc(any(User.class)))
                .thenReturn(Optional.empty());
        when(portfolioRepository.findByUser(any(User.class))).thenReturn(Optional.empty());

        service = new UserDeletionService(userRepository, executionRepository, tradeRepository,
                dripExecutionRepository, beneficiaryRepository, investmentScheduleRepository,
                userSessionRepository, portfolioRepository, plaidWebhookEventRepository,
                plaidItemLifecycleService, alpacaApiService, encryptionService, transactionManager);

        user = new User("delete-me@fredvested.com");
        user.setId(42L);
    }

    private InvestmentExecution settledAt(LocalDateTime at) {
        InvestmentExecution execution = new InvestmentExecution(
                user, at.minusDays(3), new BigDecimal("100.00"), "stock", "VTI", "bank");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution.setFundingCompletedAt(at);
        return execution;
    }

    @Test
    @DisplayName("a FUNDING_INITIATED execution defers the deletion and nothing is touched")
    void fundingInitiatedDefers() {
        when(executionRepository.existsByUserAndStatus(user, ExecutionStatus.FUNDING_INITIATED)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(user, DeletionReason.ADMIN))
                .isInstanceOf(DeletionDeferredException.class)
                .hasFieldOrPropertyWithValue("reason", "FUNDING_INITIATED");

        verifyNoInteractions(alpacaApiService, transactionManager);
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("a transfer that settled 59 days ago defers; one that settled 61 days ago does not")
    void settlementWindowIsSixtyDays() {
        when(executionRepository.findTopByUserAndFundingCompletedAtIsNotNullOrderByFundingCompletedAtDesc(user))
                .thenReturn(Optional.of(settledAt(EasternTime.now().minusDays(59))));
        assertThat(service.deferralReason(user)).contains("SETTLEMENT_WINDOW");

        when(executionRepository.findTopByUserAndFundingCompletedAtIsNotNullOrderByFundingCompletedAtDesc(user))
                .thenReturn(Optional.of(settledAt(EasternTime.now().minusDays(61))));
        assertThat(service.deferralReason(user)).isEmpty();
    }

    @Test
    @DisplayName("the broker relationship is deleted before the database transaction opens")
    void alpacaDeleteRunsBeforeTheTransaction() {
        user.setAlpacaAccountId("ENC:acct-1");
        user.setAlpacaAchRelationshipId("ENC:rel-1");
        when(encryptionService.decrypt("ENC:acct-1")).thenReturn("acct-1");
        when(encryptionService.decrypt("ENC:rel-1")).thenReturn("rel-1");
        when(alpacaApiService.deleteAchRelationship("acct-1", "rel-1")).thenReturn(true);

        service.delete(user, DeletionReason.ACCOUNT_CLOSED);

        InOrder order = inOrder(alpacaApiService, transactionManager);
        order.verify(alpacaApiService).deleteAchRelationship("acct-1", "rel-1");
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
    }

    @Test
    @DisplayName("a broker failure is best effort: the deletion still goes through")
    void alpacaFailureDoesNotBlockDeletion() {
        user.setAlpacaAccountId("ENC:acct-1");
        user.setAlpacaAchRelationshipId("ENC:rel-1");
        when(encryptionService.decrypt("ENC:acct-1")).thenReturn("acct-1");
        when(encryptionService.decrypt("ENC:rel-1")).thenReturn("rel-1");
        when(alpacaApiService.deleteAchRelationship("acct-1", "rel-1")).thenThrow(new RuntimeException("alpaca down"));

        service.delete(user, DeletionReason.ACCOUNT_CLOSED);

        verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("no relationship on file means no broker call at all")
    void noRelationshipMeansNoBrokerCall() {
        service.delete(user, DeletionReason.ADMIN);

        verifyNoInteractions(alpacaApiService);
        verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("an unlinked user queues no /item/remove")
    void unlinkedUserQueuesNoRemoval() {
        service.delete(user, DeletionReason.ADMIN);

        verify(plaidItemLifecycleService, never()).enqueueRemoval(any());
    }

    @Test
    @DisplayName("a linked Item is queued for /item/remove inside the transaction")
    void linkedItemIsQueuedInsideTheTransaction() {
        user.setPlaidAccessToken("ENC:access-token");
        user.setPlaidItemId("item-1");

        service.delete(user, DeletionReason.KYC_REJECTED);

        InOrder order = inOrder(transactionManager, plaidItemLifecycleService);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(plaidItemLifecycleService).enqueueRemoval(user);
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("children go before parents inside one transaction, and the user goes last")
    void deletesChildrenBeforeTheUserInsideOneTransaction() {
        Portfolio portfolio = new Portfolio();
        when(portfolioRepository.findByUser(user)).thenReturn(Optional.of(portfolio));

        service.delete(user, DeletionReason.ACCOUNT_CLOSED);

        InOrder order = inOrder(transactionManager, plaidWebhookEventRepository, tradeRepository,
                executionRepository, dripExecutionRepository, beneficiaryRepository,
                investmentScheduleRepository, userSessionRepository, portfolioRepository, userRepository);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(plaidWebhookEventRepository).deleteByUserId(42L);
        order.verify(tradeRepository).deleteAllByUserId(42L);
        order.verify(executionRepository).deleteAllByUser(user);
        order.verify(dripExecutionRepository).deleteAllByUser(user);
        order.verify(beneficiaryRepository).deleteAllByUser(user);
        order.verify(investmentScheduleRepository).deleteAllByUser(user);
        order.verify(userSessionRepository).deleteAllByUserId(42L);
        order.verify(portfolioRepository).delete(portfolio);
        order.verify(userRepository).delete(user);
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("a failure inside the transaction rolls it back and surfaces to the caller")
    void failureInsideTheTransactionRollsBack() {
        doThrow(new RuntimeException("fk violation")).when(userRepository).delete(user);

        assertThatThrownBy(() -> service.delete(user, DeletionReason.ADMIN))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("fk violation");

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }
}
```

- [ ] **Step 2: Run the test and watch it fail to compile**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.UserDeletionServiceTest'`

Expected: `compileTestJava FAILED` with `cannot find symbol: class UserDeletionService` and `cannot find symbol: method deleteAllByUser(User)` / `deleteAllByUserId(Long)` on the four repository mocks.

- [ ] **Step 3: Add the four derived deletes**

`InvestmentExecutionRepository.java`, after `existsByUserAndFundingCompletedAtIsNotNull` at :131 (keep the T06 additions above it; `User` is already imported at :5):

```java
    /** User deletion: every execution of this user. Trades reference executions, so they go first. */
    void deleteAllByUser(User user);
```

`InvestmentTradeRepository.java`, after `findByUserIdAndSymbolAndStatusInAndCreatedAtAfter` (:46-47):

```java
    /** User deletion: InvestmentTrade carries a bare user_id column (InvestmentTrade.java:15-16). */
    void deleteAllByUserId(Long userId);
```

`DripExecutionRepository.java`, after `existsByUserAndSymbolAndStatusIn` (:47-48; `User` already imported at :4):

```java
    /** User deletion: drip_executions.user_id has no ON DELETE CASCADE (V1:108). */
    void deleteAllByUser(User user);
```

`BeneficiaryRepository.java`, after `findDuplicateBeneficiaries` (:106-113); add `import com.investingapp.backend.model.User;` after the three `Beneficiary` imports at :3-5:

```java
    /** User deletion: beneficiaries.user_id has no ON DELETE CASCADE (V1:40). */
    void deleteAllByUser(User user);
```

- [ ] **Step 4: Write UserDeletionService**

`backend/src/main/java/com/investingapp/backend/service/UserDeletionService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.BeneficiaryRepository;
import com.investingapp.backend.repository.DripExecutionRepository;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.InvestmentTradeRepository;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.repository.PortfolioRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The one path that removes a FRED user (spec 7.9). Callers: AccountStatusService.handleRejected,
 * TradingController.closeAccount and the dev-profile admin verb in DevUserController.
 *
 * <p>Not @Transactional on purpose: the broker call happens before the database transaction opens,
 * and the transaction itself is a TransactionTemplate with default REQUIRED propagation, so a
 * caller that is already inside a transaction (the status poller) commits or rolls back as one.
 *
 * <p>Rows purged here are the ones with a foreign key to users and no ON DELETE CASCADE (V1), plus
 * the webhook inbox. Phase 2 tables created by V6 cascade on their own; ach_authorizations has no
 * writer in Phase 0 and detaches through ON DELETE SET NULL.
 */
@Service
public class UserDeletionService {

    private static final Logger logger = LoggerFactory.getLogger(UserDeletionService.class);

    /** A settled ACH transfer can still be returned for a while; the row stays until then (decision 22). */
    static final int SETTLEMENT_WINDOW_DAYS = 60;

    public enum DeletionReason { KYC_REJECTED, ACCOUNT_CLOSED, ADMIN }

    private final UserRepository userRepository;
    private final InvestmentExecutionRepository executionRepository;
    private final InvestmentTradeRepository tradeRepository;
    private final DripExecutionRepository dripExecutionRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final InvestmentScheduleRepository investmentScheduleRepository;
    private final UserSessionRepository userSessionRepository;
    private final PortfolioRepository portfolioRepository;
    private final PlaidWebhookEventRepository plaidWebhookEventRepository;
    private final PlaidItemLifecycleService plaidItemLifecycleService;
    private final AlpacaApiService alpacaApiService;
    private final EncryptionService encryptionService;
    private final TransactionTemplate deleteTx;

    public UserDeletionService(
            UserRepository userRepository,
            InvestmentExecutionRepository executionRepository,
            InvestmentTradeRepository tradeRepository,
            DripExecutionRepository dripExecutionRepository,
            BeneficiaryRepository beneficiaryRepository,
            InvestmentScheduleRepository investmentScheduleRepository,
            UserSessionRepository userSessionRepository,
            PortfolioRepository portfolioRepository,
            PlaidWebhookEventRepository plaidWebhookEventRepository,
            PlaidItemLifecycleService plaidItemLifecycleService,
            AlpacaApiService alpacaApiService,
            EncryptionService encryptionService,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.executionRepository = executionRepository;
        this.tradeRepository = tradeRepository;
        this.dripExecutionRepository = dripExecutionRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.investmentScheduleRepository = investmentScheduleRepository;
        this.userSessionRepository = userSessionRepository;
        this.portfolioRepository = portfolioRepository;
        this.plaidWebhookEventRepository = plaidWebhookEventRepository;
        this.plaidItemLifecycleService = plaidItemLifecycleService;
        this.alpacaApiService = alpacaApiService;
        this.encryptionService = encryptionService;
        this.deleteTx = new TransactionTemplate(transactionManager);
    }

    /** Empty when the user can be deleted now; otherwise "FUNDING_INITIATED" or "SETTLEMENT_WINDOW". */
    public Optional<String> deferralReason(User user) {
        if (executionRepository.existsByUserAndStatus(user, ExecutionStatus.FUNDING_INITIATED)) {
            return Optional.of("FUNDING_INITIATED");
        }
        LocalDateTime windowStart = EasternTime.now().minusDays(SETTLEMENT_WINDOW_DAYS);
        boolean settledRecently = executionRepository
                .findTopByUserAndFundingCompletedAtIsNotNullOrderByFundingCompletedAtDesc(user)
                .map(InvestmentExecution::getFundingCompletedAt)
                .filter(settledAt -> settledAt.isAfter(windowStart))
                .isPresent();
        return settledRecently ? Optional.of("SETTLEMENT_WINDOW") : Optional.empty();
    }

    /**
     * (1) refuse while money is in flight, (2) delete the Alpaca relationship best effort and outside
     * any transaction, (3) one transaction: queue /item/remove, purge the inbox, delete every
     * FK-bearing child (children before parents), then the user.
     */
    public void delete(User user, DeletionReason reason) {
        Long userId = user.getId();
        Optional<String> deferral = deferralReason(user);
        if (deferral.isPresent()) {
            logger.warn("User deletion deferred user={} reason={} deferral={}", userId, reason, deferral.get());
            throw new DeletionDeferredException(deferral.get());
        }

        deleteAlpacaRelationshipBestEffort(user);

        deleteTx.executeWithoutResult(status -> {
            if (user.getPlaidAccessToken() != null) {
                plaidItemLifecycleService.enqueueRemoval(user);
            }
            plaidWebhookEventRepository.deleteByUserId(userId);
            tradeRepository.deleteAllByUserId(userId);
            executionRepository.deleteAllByUser(user);
            dripExecutionRepository.deleteAllByUser(user);
            beneficiaryRepository.deleteAllByUser(user);
            investmentScheduleRepository.deleteAllByUser(user);
            userSessionRepository.deleteAllByUserId(userId);
            portfolioRepository.findByUser(user).ifPresent(portfolioRepository::delete);
            userRepository.delete(user);
        });
        logger.info("User deleted user={} reason={}", userId, reason);
    }

    private void deleteAlpacaRelationshipBestEffort(User user) {
        if (user.getAlpacaAccountId() == null || user.getAlpacaAchRelationshipId() == null) {
            return;
        }
        try {
            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());
            String relationshipId = encryptionService.decrypt(user.getAlpacaAchRelationshipId());
            if (!alpacaApiService.deleteAchRelationship(accountId, relationshipId)) {
                logger.warn("Alpaca ACH relationship delete did not succeed user={} relationship={}",
                        user.getId(), relationshipId);
            }
        } catch (RuntimeException e) {
            logger.warn("Alpaca ACH relationship delete failed user={}: {}", user.getId(), e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Run the service test and see it pass**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.UserDeletionServiceTest'`

Expected: `BUILD SUCCESSFUL`, 9 tests passed.

- [ ] **Step 6: Make the rejection test demand the one path (failing)**

In `AccountStatusServiceKycDecidedTest.java`: after the last `@Mock` (T10 added `@Mock private AchRelationshipService achRelationshipService;` after the `analyticsOutboxService` mock at :65-66) add:

```java
    @Mock
    private UserDeletionService userDeletionService;
```

In `setUp()` (the `service = new AccountStatusService(` call at :80-88) make the constructor call end with both new parameters:

```java
        service = new AccountStatusService(
                userRepository,
                investmentScheduleRepository,
                alpacaApiService,
                encryptionService,
                emailService,
                plaidToAlpacaService,
                investmentScheduleService,
                analyticsOutboxService,
                achRelationshipService,
                userDeletionService);
```

In `rejectionIsRecordedBeforeTheHardDelete` (:154-174) replace the tail of the method, from the line `InOrder order = inOrder(analyticsOutboxService, userRepository);` through the closing `assertThat(identityAtRecordTime.get())...` line, with:

```java
        InOrder order = inOrder(analyticsOutboxService, userDeletionService);
        order.verify(analyticsOutboxService)
                .record(eq(ServerAnalyticsEvent.KYC_DECIDED), eq(user), anyMap());
        order.verify(userDeletionService).delete(user, UserDeletionService.DeletionReason.KYC_REJECTED);
        verify(userRepository, never()).delete(any());
        // The outbox copies both of these out of the user; neither can be recovered afterwards.
        assertThat(identityAtRecordTime.get()).isEqualTo("42|" + PUBLIC_ID);
```

Add this test directly after it. `doThrow` is not imported yet: add `import static org.mockito.Mockito.doThrow;` to the static imports at :28-33. `DeletionDeferredException` and `UserDeletionService` are in the test's own package, no import needed.

```java
    @Test
    @DisplayName("a deferred deletion parks the user as REJECTED so the poller stops re-deciding")
    void deferredRejectionParksTheUserAsRejected() {
        brokerSays("REJECTED");
        doThrow(new DeletionDeferredException("FUNDING_INITIATED"))
                .when(userDeletionService).delete(user, UserDeletionService.DeletionReason.KYC_REJECTED);

        service.checkAndUpdateAccountStatuses();

        assertOneDecisionEvent("rejected", "1_3");
        assertThat(user.getAccountStatus()).isEqualTo("REJECTED");
        verify(userRepository).save(user);
        verify(userRepository, never()).delete(any());
    }
```

(`checkAndUpdateAccountStatuses` at :92-102 dispatches `REJECTED` straight to `handleRejected` without saving first, so `verify(userRepository).save(user)` means exactly one save, the parking one.)

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AccountStatusServiceKycDecidedTest'`

Expected: `compileTestJava FAILED`, `constructor AccountStatusService in class AccountStatusService cannot be applied to given types` (9 parameters after T10, 10 arguments given).

- [ ] **Step 7: Route handleRejected through UserDeletionService**

`AccountStatusService.java`: add the field after T10's `private final AchRelationshipService achRelationshipService;` and the constructor parameter after `AchRelationshipService achRelationshipService`:

```java
    private final UserDeletionService userDeletionService;
```

```java
            AchRelationshipService achRelationshipService,
            UserDeletionService userDeletionService) {
```

and in the constructor body, after `this.achRelationshipService = achRelationshipService;`:

```java
        this.userDeletionService = userDeletionService;
```

Replace the javadoc and method that start at `/**` + ` * Handle an account that has been REJECTED.` (:127) and end at the closing brace of `handleRejected` (:157) with:

```java
    /**
     * Handle an account that has been REJECTED.
     * Records the decision, emails the user, then deletes the user through the one deletion path.
     * A REJECTED account never reached ACH setup (attemptAchCreation only runs for ACTIVE users and
     * an ACTIVE user is never re-polled), so the broker call inside that path is skipped and
     * nothing talks to Alpaca from inside this transaction.
     */
    @Transactional
    public void handleRejected(User user) {
        logger.warn("Account REJECTED for user {}: sending rejection email and deleting user record",
                user.getEmail());

        // Record the decision FIRST, while the user still has an id and a public_id to copy.
        // Everything below runs in this same transaction, including the hard delete, so the event
        // and the deletion land together or not at all. The outbox row has no foreign key to users
        // and denormalises public_id, so it outlives the row it describes.
        recordKycDecision(user, "REJECTED");

        // Notify the user before deleting their record
        emailService.sendEmail(
                user.getEmail(),
                "Your FRED account application was not approved",
                "We're sorry, but we were unable to approve your FRED brokerage account application at this time. "
                + "If you have questions, please contact our support team.");

        try {
            userDeletionService.delete(user, UserDeletionService.DeletionReason.KYC_REJECTED);
            logger.info("Hard-deleted user record for rejected user {}", user.getEmail());
        } catch (DeletionDeferredException deferred) {
            // Park the row as REJECTED: findUsersWithPendingAccountStatus excludes it, so the poller
            // stops re-deciding, and the admin verb finishes the job once the money has settled.
            user.setAccountStatus("REJECTED");
            userRepository.save(user);
            logger.error("alert.user_deletion.deferred user={} reason=KYC_REJECTED deferral={}",
                    user.getId(), deferred.reason);
        }
    }
```

`investmentScheduleRepository` is no longer referenced by this method. Leave the field, the constructor parameter and the assignment in place: `AccountStatusServiceKycDecidedTest` builds the service positionally and `AccountStatusServiceKycDecidedRollbackTest` autowires it; an unused private field compiles cleanly.

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AccountStatusServiceKycDecidedTest'`

Expected: `BUILD SUCCESSFUL`, 12 tests passed (the 11 existing at HEAD plus `deferredRejectionParksTheUserAsRejected`; one more for each case T10 added to this class).

- [ ] **Step 8: Write the failing close-account controller test**

`backend/src/test/java/com/investingapp/backend/controller/TradingControllerCloseAccountTest.java`:

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AlpacaService;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.UserDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Close-account must refuse BEFORE closing the brokerage account: a closed Alpaca account with a
 * surviving FRED row is a half-deleted person nobody can finish.
 */
class TradingControllerCloseAccountTest {

    private static final String EMAIL = "close@fredvested.com";

    private TradingController controller;
    private AlpacaService alpacaService;
    private UserDeletionService userDeletionService;
    private Authentication authentication;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new TradingController();
        UserRepository userRepository = mock(UserRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        alpacaService = mock(AlpacaService.class);
        userDeletionService = mock(UserDeletionService.class);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(controller, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(controller, "userDeletionService", userDeletionService);

        user = new User(EMAIL);
        user.setId(42L);
        user.setAlpacaAccountId("ENC:acct");

        UserDetails principal = mock(UserDetails.class);
        when(principal.getUsername()).thenReturn(EMAIL);
        authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt("ENC:acct")).thenReturn("acct");
    }

    @Test
    @DisplayName("a deferred deletion answers 409 and never closes the brokerage account")
    void deferredIs409AndTheBrokerIsNeverCalled() {
        when(userDeletionService.deferralReason(user)).thenReturn(Optional.of("FUNDING_INITIATED"));

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "FUNDING_INITIATED"));
        verify(alpacaService, never()).closeAccount(anyString());
        verify(userDeletionService, never()).delete(any(), any());
    }

    @Test
    @DisplayName("closes the brokerage account, then deletes through the one path")
    @SuppressWarnings("unchecked")
    void closesTheBrokerAccountThenDeletes() {
        when(userDeletionService.deferralReason(user)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("success", true);
        InOrder order = inOrder(alpacaService, userDeletionService);
        order.verify(alpacaService).closeAccount("acct");
        order.verify(userDeletionService).delete(user, UserDeletionService.DeletionReason.ACCOUNT_CLOSED);
    }

    @Test
    @DisplayName("a deferral raised by the delete itself still answers 409, not 500")
    void lateDeferralIs409() {
        when(userDeletionService.deferralReason(user)).thenReturn(Optional.empty());
        doThrow(new DeletionDeferredException("SETTLEMENT_WINDOW"))
                .when(userDeletionService).delete(user, UserDeletionService.DeletionReason.ACCOUNT_CLOSED);

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "SETTLEMENT_WINDOW"));
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.TradingControllerCloseAccountTest'`

Expected: the class compiles (today's `ResponseEntity<Map<String, Object>>` return type assigns to `ResponseEntity<?>`, and `ErrorCodeResponse`, `UserDeletionService` and `DeletionDeferredException` all exist by now), then all 3 tests error in `setUp` with `IllegalArgumentException: Could not find field 'userDeletionService' of type [null] on target object` from `ReflectionTestUtils.setField`. `BUILD FAILED`, 3 tests, 3 failures.

- [ ] **Step 9: Rewrite closeAccount**

`TradingController.java` imports: delete :7 (`InvestmentScheduleRepository`), :8 (`PortfolioRepository`), :10 (`UserSessionRepository`) and :20 (`org.springframework.transaction.annotation.Transactional`); add after :5 (`import com.investingapp.backend.service.EncryptionService;`):

```java
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import com.investingapp.backend.dto.ErrorCodeResponse;
```

Replace the three `@Autowired` fields at :45-52 (`investmentScheduleRepository`, `userSessionRepository`, `portfolioRepository`; `encryptionService` at :54-55 stays) with:

```java
    @Autowired
    private UserDeletionService userDeletionService;
```

Replace :297-346 (the javadoc starting ` * Close the user's brokerage account and delete their FRED user record.` through the closing brace of `closeAccount`) with:

```java
    /**
     * Close the user's brokerage account and delete their FRED user record.
     * Prerequisites: all positions must be liquidated and all cash withdrawn.
     * Not transactional: the broker calls run outside the database transaction that
     * UserDeletionService opens for the row deletes.
     */
    @PostMapping("/account/close")
    public ResponseEntity<?> closeAccount(Authentication authentication) {
        try {
            if (authentication == null || authentication.getPrincipal() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);

            if (user == null || user.getAlpacaAccountId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User or Alpaca account not found"));
            }

            // Refuse before touching the broker: closing the brokerage account and then keeping
            // the FRED row would leave the user half deleted.
            Optional<String> deferral = userDeletionService.deferralReason(user);
            if (deferral.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("DELETION_DEFERRED", deferral.get()));
            }

            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

            // Close the Alpaca brokerage account
            alpacaService.closeAccount(accountId);

            // One path for every row that references the user (schedules, sessions, portfolio, ...)
            userDeletionService.delete(user, UserDeletionService.DeletionReason.ACCOUNT_CLOSED);

            logger.info("Account closed and user deleted for alpaca account: {}", accountId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Your account has been permanently closed."
            ));

        } catch (DeletionDeferredException deferred) {
            // A transfer landed between the pre-check and the delete. The brokerage account is
            // already closed, so a person finishes this one through the admin verb.
            logger.error("alert.user_deletion.deferred reason=ACCOUNT_CLOSED deferral={}", deferred.reason);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorCodeResponse("DELETION_DEFERRED", deferred.reason));
        } catch (Exception e) {
            logger.error("Error closing account: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to close account. Please ensure all positions are sold and all cash is withdrawn."));
        }
    }
```

(`Optional` is covered by the existing `import java.util.*;` at :24.) Frontend: `trading.service.ts:256-262` posts `/trading/account/close`; `sell-withdraw.page.ts:557` awaits it with `toPromise()`, so a 409 rejects the promise and the catch block at `:571-583` shows the generic "Failed to close account" toast. No frontend change in Phase 0.

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.TradingControllerCloseAccountTest'`

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 10: Failing tests for the dev admin verb and the prod guard**

`backend/src/test/java/com/investingapp/backend/controller/DevUserControllerTest.java`:

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevUserControllerTest {

    private UserRepository userRepository;
    private UserDeletionService userDeletionService;
    private DevUserController controller;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userDeletionService = mock(UserDeletionService.class);
        controller = new DevUserController(userRepository, userDeletionService);
        user = new User("admin-delete@fredvested.com");
        user.setId(7L);
    }

    @Test
    @DisplayName("an unknown id is 404 and nothing is deleted")
    void unknownUserIs404() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(controller.deleteUser(7L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(userDeletionService);
    }

    @Test
    @DisplayName("a known id is deleted with reason ADMIN")
    @SuppressWarnings("unchecked")
    void deletesAsAdmin() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.deleteUser(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("deleted", true).containsEntry("userId", 7L);
        verify(userDeletionService).delete(user, UserDeletionService.DeletionReason.ADMIN);
    }

    @Test
    @DisplayName("a deferral is 409 with the DELETION_DEFERRED code")
    void deferredIs409() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        doThrow(new DeletionDeferredException("SETTLEMENT_WINDOW"))
                .when(userDeletionService).delete(user, UserDeletionService.DeletionReason.ADMIN);

        ResponseEntity<?> response = controller.deleteUser(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "SETTLEMENT_WINDOW"));
    }
}
```

`DevToolsGatingTest.java`: add `import com.investingapp.backend.controller.DevUserController;` after :3 (`import com.investingapp.backend.controller.DevAuthController;`), and this test after `prodGuardFailsStartupWhenDevBeanPresent` (its closing brace is :106). `DevToolsProdGuard.PROD_PROFILE` is package-private and the test shares the package.

```java
    @Test
    @DisplayName("prod refuses to start when the dev user-deletion verb is registered anyway")
    void prodGuardFailsStartupWhenDevUserControllerPresent() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("devUserController", new RootBeanDefinition(DevUserController.class));

        DevToolsProdGuard guard = new DevToolsProdGuard();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles(DevToolsProdGuard.PROD_PROFILE);
        guard.setEnvironment(prod);

        assertThatThrownBy(() -> guard.postProcessBeanFactory(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("devUserController");
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.DevUserControllerTest' --tests 'com.investingapp.backend.config.DevToolsGatingTest'`

Expected: `compileTestJava FAILED`, `cannot find symbol: class DevUserController` (in both test files).

- [ ] **Step 11: Create DevUserController and register it with the prod guard**

`backend/src/main/java/com/investingapp/backend/controller/DevUserController.java`:

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * DEV ONLY admin verb for user deletion (spec 7.9; the prod admin surface is a later phase).
 * Registered only in local/dev/test/ci; every request needs X-Dev-Tools-Key (DevToolsKeyFilter on
 * /api/dev/*), and DevToolsProdGuard refuses to start prod if this bean is ever registered.
 */
@RestController
@RequestMapping("/api/dev/users")
@CrossOrigin(origins = "*")
@Profile({"local", "dev", "test", "ci"})
public class DevUserController {

    private static final Logger logger = LoggerFactory.getLogger(DevUserController.class);

    private final UserRepository userRepository;
    private final UserDeletionService userDeletionService;

    public DevUserController(UserRepository userRepository, UserDeletionService userDeletionService) {
        this.userRepository = userRepository;
        this.userDeletionService = userDeletionService;
    }

    /** POST /api/dev/users/{id}/delete: 200 deleted, 404 unknown id, 409 DELETION_DEFERRED. */
    @PostMapping("/{id}/delete")
    public ResponseEntity<?> deleteUser(@PathVariable("id") Long id) {
        Optional<User> user = userRepository.findById(id);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            userDeletionService.delete(user.get(), UserDeletionService.DeletionReason.ADMIN);
        } catch (DeletionDeferredException deferred) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("DELETION_DEFERRED", deferred.reason));
        }
        logger.warn("[DevUserController] deleted user id={} reason=ADMIN", id);
        return ResponseEntity.ok(Map.of("deleted", true, "userId", id));
    }
}
```

`DevToolsProdGuard.java`: add `import com.investingapp.backend.controller.DevUserController;` after :3 (`import com.investingapp.backend.controller.DevAuthController;`), and after :43 (`addIfPresent(beanFactory, DevAuthController.class, offenders);`):

```java
        addIfPresent(beanFactory, DevUserController.class, offenders);
```

(The name scan at :47-54 only matches "DevAuth" / "Simulated", so without this line the guard would let `DevUserController` through.)

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.DevUserControllerTest' --tests 'com.investingapp.backend.config.DevToolsGatingTest'`

Expected: `BUILD SUCCESSFUL`, 3 + 8 tests passed (`DevToolsGatingTest` has 7 at HEAD plus the new case).

- [ ] **Step 12: Full task gate and leftover grep**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.UserDeletionServiceTest' --tests 'com.investingapp.backend.service.AccountStatusServiceKycDecidedTest' --tests 'com.investingapp.backend.controller.TradingControllerCloseAccountTest' --tests 'com.investingapp.backend.controller.DevUserControllerTest' --tests 'com.investingapp.backend.config.DevToolsGatingTest'`

Expected: `BUILD SUCCESSFUL`, all five classes green.

Run: `grep -n "investmentScheduleRepository\|userSessionRepository\|portfolioRepository\|Transactional" backend/src/main/java/com/investingapp/backend/controller/TradingController.java`

Expected: no output (at HEAD those names appear only at :7-8, :10, :20, :45-52, :301, :323-329, all replaced above).

Run: `grep -rn "userRepository.delete(" backend/src/main/java`

Expected: exactly one hit, `service/UserDeletionService.java` (the one path; at HEAD the other two hits were `TradingController.java:332` and `AccountStatusService.java:155`).

Em-dash check, limited to lines this task adds (`AccountStatusService.java` carries pre-existing em dashes outside this task's hunks, which are not in scope):

```
git diff HEAD -- backend/src/main/java/com/investingapp/backend/service/AccountStatusService.java backend/src/main/java/com/investingapp/backend/controller/TradingController.java backend/src/main/java/com/investingapp/backend/config/DevToolsProdGuard.java backend/src/main/java/com/investingapp/backend/repository/InvestmentExecutionRepository.java backend/src/main/java/com/investingapp/backend/repository/InvestmentTradeRepository.java backend/src/main/java/com/investingapp/backend/repository/DripExecutionRepository.java backend/src/main/java/com/investingapp/backend/repository/BeneficiaryRepository.java backend/src/test/java/com/investingapp/backend/service/AccountStatusServiceKycDecidedTest.java backend/src/test/java/com/investingapp/backend/config/DevToolsGatingTest.java | grep -n $'^+.*\xe2\x80\x94'
grep -n $'\xe2\x80\x94' backend/src/main/java/com/investingapp/backend/service/DeletionDeferredException.java backend/src/main/java/com/investingapp/backend/service/UserDeletionService.java backend/src/main/java/com/investingapp/backend/controller/DevUserController.java backend/src/test/java/com/investingapp/backend/service/UserDeletionServiceTest.java backend/src/test/java/com/investingapp/backend/controller/TradingControllerCloseAccountTest.java backend/src/test/java/com/investingapp/backend/controller/DevUserControllerTest.java
```

Expected: no output from either command.

Optional database proof when the ci MySQL is reachable (RequiresCiDatabase skips otherwise): `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.AccountStatusServiceKycDecidedRollbackTest'`. Expected: `rolledBackRejectionLeavesNoEvent` still finds the user (the deletion's TransactionTemplate joined the rolled-back transaction) and `committedRejectionKeepsTheEventAfterTheUserIsGone` finds it gone.

- [ ] **Step 13: Commit**

Hunk-verify first (shared dirty tree): run `git diff HEAD -- <file>` for every pre-existing file this task modifies and confirm every hunk is yours:

```
git diff HEAD -- backend/src/main/java/com/investingapp/backend/service/AccountStatusService.java backend/src/main/java/com/investingapp/backend/controller/TradingController.java backend/src/main/java/com/investingapp/backend/config/DevToolsProdGuard.java backend/src/main/java/com/investingapp/backend/repository/InvestmentExecutionRepository.java backend/src/main/java/com/investingapp/backend/repository/InvestmentTradeRepository.java backend/src/main/java/com/investingapp/backend/repository/DripExecutionRepository.java backend/src/main/java/com/investingapp/backend/repository/BeneficiaryRepository.java backend/src/test/java/com/investingapp/backend/service/AccountStatusServiceKycDecidedTest.java backend/src/test/java/com/investingapp/backend/config/DevToolsGatingTest.java
```

Then stage by full path and commit:

```
git add backend/src/main/java/com/investingapp/backend/service/DeletionDeferredException.java backend/src/main/java/com/investingapp/backend/service/UserDeletionService.java backend/src/main/java/com/investingapp/backend/controller/DevUserController.java backend/src/main/java/com/investingapp/backend/repository/InvestmentExecutionRepository.java backend/src/main/java/com/investingapp/backend/repository/InvestmentTradeRepository.java backend/src/main/java/com/investingapp/backend/repository/DripExecutionRepository.java backend/src/main/java/com/investingapp/backend/repository/BeneficiaryRepository.java backend/src/main/java/com/investingapp/backend/service/AccountStatusService.java backend/src/main/java/com/investingapp/backend/controller/TradingController.java backend/src/main/java/com/investingapp/backend/config/DevToolsProdGuard.java backend/src/test/java/com/investingapp/backend/service/UserDeletionServiceTest.java backend/src/test/java/com/investingapp/backend/service/AccountStatusServiceKycDecidedTest.java backend/src/test/java/com/investingapp/backend/controller/TradingControllerCloseAccountTest.java backend/src/test/java/com/investingapp/backend/controller/DevUserControllerTest.java backend/src/test/java/com/investingapp/backend/config/DevToolsGatingTest.java
git commit -m "feat(users): one deletion path with a settlement-window hold"
```