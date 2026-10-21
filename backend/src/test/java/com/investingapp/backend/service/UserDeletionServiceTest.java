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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
    @DisplayName("the caller's hook runs inside the transaction, after it opens and before the first delete")
    void hookRunsInsideTheTransactionBeforeAnyDelete() {
        AtomicInteger deletesWhenTheHookRan = new AtomicInteger(-1);
        AtomicInteger deletes = new AtomicInteger();
        doAnswer(invocation -> {
            deletes.incrementAndGet();
            return null;
        }).when(plaidWebhookEventRepository).deleteByUserId(42L);
        Runnable hook = () -> deletesWhenTheHookRan.set(deletes.get());

        service.delete(user, DeletionReason.ACCOUNT_CLOSED, hook);

        assertThat(deletesWhenTheHookRan.get())
                .as("the hook must see the rows it describes, so it runs before any of them go")
                .isZero();
        InOrder order = inOrder(transactionManager, plaidWebhookEventRepository, userRepository);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(plaidWebhookEventRepository).deleteByUserId(42L);
        order.verify(userRepository).delete(user);
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("a deferred deletion never runs the hook, so nothing records a deletion that did not happen")
    void deferredDeletionNeverRunsTheHook() {
        when(executionRepository.existsByUserAndStatus(user, ExecutionStatus.FUNDING_INITIATED)).thenReturn(true);
        AtomicInteger hookRuns = new AtomicInteger();

        assertThatThrownBy(() -> service.delete(user, DeletionReason.ACCOUNT_CLOSED, hookRuns::incrementAndGet))
                .isInstanceOf(DeletionDeferredException.class);

        assertThat(hookRuns.get()).isZero();
        verifyNoInteractions(transactionManager);
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
