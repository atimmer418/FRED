package com.investingapp.backend.repository;

import com.investingapp.backend.model.PlaidItemRemoval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** plaid_item_removals: the queue PlaidItemLifecycleService.drainRemovals works through. */
@Repository
public interface PlaidItemRemovalRepository extends JpaRepository<PlaidItemRemoval, Long> {

    /** Still to retry: not removed yet and requested after the given floor (now minus 7 days). */
    List<PlaidItemRemoval> findByRemovedAtIsNullAndRequestedAtAfterOrderByRequestedAtAsc(LocalDateTime since);

    /** Exhausted: not removed and requested before the floor; alerted on, never retried. */
    List<PlaidItemRemoval> findByRemovedAtIsNullAndRequestedAtBeforeOrderByRequestedAtAsc(LocalDateTime cutoff);

    /** Used by UserDeletionService (T11) inside its own transaction. */
    long deleteByUserId(Long userId);
}
