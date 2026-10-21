package com.investingapp.backend.service;

import com.investingapp.backend.model.AnalyticsPersonDeletion;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsPersonDeletionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * Records that a person must be deleted at the analytics vendor.
 *
 * Called from inside the transaction that deletes the account, so the promise commits with the
 * deletion. Like the event outbox it refuses to write outside a transaction, for the same reason:
 * a request committed beside a rolled-back closure would delete the analytics of a user who still
 * exists, and that is worse than not having written it.
 *
 * There is deliberately NO consent check anywhere in this path. A deletion is an obligation, not
 * analytics, and an opted-out user is exactly the one whose older person record needs removing.
 */
@Service
public class AnalyticsPersonDeletionService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsPersonDeletionService.class);

    private final AnalyticsPersonDeletionRepository repository;

    public AnalyticsPersonDeletionService(AnalyticsPersonDeletionRepository repository) {
        this.repository = repository;
    }

    /**
     * Requests deletion of this user's analytics person record. Safe to call twice: the public_id
     * is unique, and a second request for the same person is the same request.
     *
     * @return the row, or null when nothing was written
     */
    public AnalyticsPersonDeletion request(User user) {
        try {
            if (user == null || user.getPublicId() == null || user.getPublicId().isBlank()) {
                // Nothing to ask the vendor to delete: without a distinct id they never had a
                // person record in the first place.
                return null;
            }
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                logger.error("analytics: refusing to record a person deletion outside a transaction; "
                        + "it would outlive a rolled-back account closure");
                return null;
            }

            return repository.findByPublicId(user.getPublicId()).orElseGet(() -> {
                AnalyticsPersonDeletion deletion = new AnalyticsPersonDeletion();
                deletion.setPublicId(user.getPublicId());
                deletion.setRequestedAt(LocalDateTime.now());
                deletion.setStatus(AnalyticsPersonDeletion.STATUS_PENDING);
                return repository.save(deletion);
            });
        } catch (Exception e) {
            // Never break the closure itself. A lost request is recoverable by hand; a user who
            // cannot close their account is not.
            logger.error("analytics: failed to record a person deletion request", e);
            return null;
        }
    }
}
