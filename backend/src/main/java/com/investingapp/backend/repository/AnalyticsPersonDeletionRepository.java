package com.investingapp.backend.repository;

import com.investingapp.backend.model.AnalyticsPersonDeletion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AnalyticsPersonDeletionRepository extends JpaRepository<AnalyticsPersonDeletion, Long> {

    @Query("SELECT d FROM AnalyticsPersonDeletion d WHERE d.status = :status "
            + "AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now) ORDER BY d.id ASC")
    List<AnalyticsPersonDeletion> findSendable(@Param("status") String status,
                                               @Param("now") LocalDateTime now,
                                               Pageable pageable);

    Optional<AnalyticsPersonDeletion> findByPublicId(String publicId);
}
