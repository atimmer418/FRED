package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** First /transactions/sync from the status endpoint (spec 8.1, R4a); writes only detection_started_at. */
@Service
public class InitSyncEnqueuer {

    private static final Logger logger = LoggerFactory.getLogger(InitSyncEnqueuer.class);

    private final InvestmentScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final PaycheckSyncPipeline pipeline;
    private final PaycheckAutopilotProperties props;
    private final TransactionTemplate writeTx;
    private final Executor plaidExecutor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @PersistenceContext
    private EntityManager entityManager;

    public InitSyncEnqueuer(InvestmentScheduleRepository scheduleRepository, UserRepository userRepository,
            PaycheckSyncPipeline pipeline, PaycheckAutopilotProperties props,
            PlatformTransactionManager transactionManager,
            @Qualifier("plaidExecutor") Executor plaidExecutor) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.pipeline = pipeline;
        this.props = props;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.plaidExecutor = plaidExecutor;
    }

    public boolean enqueueIfNeeded(User user) {
        if (DetectionEligibility.exclusion(user, props).isPresent()
                || user.getPlaidSyncCursor() != null
                || Boolean.FALSE.equals(user.getPlaidTransactionsConsented())) {
            return false;
        }
        String itemId = user.getPlaidItemId();
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        Optional<InvestmentSchedule> row = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        if (row.isPresent() && row.get().getDetectionStartedAt() != null) {
            return false;
        }
        Long userId = user.getId();
        if (!inFlight.add(userId)) {
            return false;
        }
        boolean claimed;
        try {
            claimed = row.isPresent()
                    ? Boolean.TRUE.equals(writeTx.execute(status -> stampAndSchedule(userId, itemId)))
                    : submitAfterCommitOrNow(userId, itemId);
        } catch (RuntimeException e) {
            inFlight.remove(userId);
            logger.warn("paycheck.init.enqueue_failed user_id={} error_class={}", userId, e.getClass().getSimpleName());
            return false;
        }
        if (!claimed) {
            inFlight.remove(userId);
        }
        return claimed;
    }

    private boolean stampAndSchedule(Long userId, String itemId) {
        User locked = userRepository.findByIdForUpdate(userId).orElse(null);
        if (locked == null) {
            return false;
        }
        // Open-in-view hands back cached copies unread (see PlaidService): refresh both, so the re-check
        // is real and the full-row save never writes a stale status over a fresh pause.
        entityManager.refresh(locked);
        if (locked.getPlaidSyncCursor() != null || !itemId.equals(locked.getPlaidItemId())) {
            return false;
        }
        InvestmentSchedule schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(locked).orElse(null);
        if (schedule == null) {
            return submitAfterCommitOrNow(userId, itemId);
        }
        entityManager.refresh(schedule, LockModeType.PESSIMISTIC_WRITE);
        if (schedule.getDetectionStartedAt() != null) {
            return false;
        }
        schedule.setDetectionStartedAt(EasternTime.now());
        scheduleRepository.save(schedule);
        return submitAfterCommitOrNow(userId, itemId);
    }

    private boolean submitAfterCommitOrNow(Long userId, String itemId) {
        Runnable init = () -> runInit(userId, itemId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(userId, init);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        inFlight.remove(userId);
                    }
                }
            });
            return true;
        }
        return submit(userId, init);
    }

    private boolean submit(Long userId, Runnable init) {
        try {
            plaidExecutor.execute(init);
        } catch (RuntimeException e) {
            // plaidExecutor keeps AbortPolicy; a full queue must not fail the status call.
            inFlight.remove(userId);
            logger.warn("paycheck.init.rejected user_id={} error_class={}", userId, e.getClass().getSimpleName());
            return false;
        }
        logger.info("paycheck.init.enqueued");
        return true;
    }

    private void runInit(Long userId, String itemId) {
        try {
            pipeline.run(userId, itemId, SyncReason.INIT);
        } catch (RuntimeException e) {
            logger.warn("paycheck.init.failed user_id={} error_class={}", userId, e.getClass().getSimpleName());
        } finally {
            inFlight.remove(userId);
        }
    }
}
