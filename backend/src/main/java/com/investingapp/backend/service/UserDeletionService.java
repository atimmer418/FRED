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
