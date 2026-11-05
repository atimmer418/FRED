package com.investingapp.backend.repository;

import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlaidWebhookEventRepository extends JpaRepository<PlaidWebhookEvent, Long> {

    Optional<PlaidWebhookEvent> findByDedupeKey(String dedupeKey);

    /** RECEIVED rows, oldest first, locked until the caller's transaction ends; rows another claimer holds are skipped (MySQL 8.0). */
    @Query(value = "SELECT id FROM plaid_webhook_events WHERE status = 'RECEIVED' ORDER BY received_at "
            + "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Long> claimReceivedIds(@Param("limit") int limit);

    /** processed_at doubles as the claim stamp while PROCESSING (V6 has no claim column). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE plaid_webhook_events SET status = 'PROCESSING', processed_at = :claimedAt WHERE id IN (:ids)",
            nativeQuery = true)
    int markProcessing(@Param("ids") List<Long> ids, @Param("claimedAt") LocalDateTime claimedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE plaid_webhook_events SET status = 'RECEIVED' WHERE status = 'PROCESSING' AND processed_at < :before",
            nativeQuery = true)
    int reclaimStale(@Param("before") LocalDateTime before);

    /** For the admin GET /webhooks?status=FAILED verb (T18). */
    List<PlaidWebhookEvent> findByStatusOrderByReceivedAtAsc(PlaidWebhookEventStatus status);

    /** Used by UserDeletionService (T11). A derived delete needs a read-write transaction. */
    @Transactional
    long deleteByUserId(Long userId);
}
