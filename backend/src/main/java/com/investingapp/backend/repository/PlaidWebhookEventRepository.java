package com.investingapp.backend.repository;

import com.investingapp.backend.model.PlaidWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface PlaidWebhookEventRepository extends JpaRepository<PlaidWebhookEvent, Long> {

    Optional<PlaidWebhookEvent> findByDedupeKey(String dedupeKey);

    /** Used by UserDeletionService (T11). A derived delete needs a read-write transaction. */
    @Transactional
    long deleteByUserId(Long userId);
}
