package com.investingapp.backend.repository;

import com.investingapp.backend.model.AnalyticsOutboxEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AnalyticsOutboxRepository extends JpaRepository<AnalyticsOutboxEntry, Long> {

    /**
     * The publisher's claim query: pending rows whose backoff has elapsed, oldest first so the
     * funnel arrives in the order it happened.
     */
    @Query("SELECT e FROM AnalyticsOutboxEntry e WHERE e.status = :status "
            + "AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now) ORDER BY e.id ASC")
    List<AnalyticsOutboxEntry> findPublishable(@Param("status") String status,
                                               @Param("now") LocalDateTime now,
                                               Pageable pageable);

    /** Used to decide is_first_* without a second source of truth. */
    long countByUserIdAndEventName(Long userId, String eventName);

    boolean existsByUserIdAndEventName(Long userId, String eventName);
}
