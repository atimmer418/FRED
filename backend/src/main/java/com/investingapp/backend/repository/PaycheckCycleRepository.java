package com.investingapp.backend.repository;

import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** paycheck_cycles. Delete rules as on PaycheckStreamRepository. */
@Repository
public interface PaycheckCycleRepository extends JpaRepository<PaycheckCycle, Long> {

    /** Unique in V6 (uq_paycheck_cycles_stream_period). */
    Optional<PaycheckCycle> findByStreamIdAndPeriodKey(Long streamId, String periodKey);

    List<PaycheckCycle> findByUserIdAndState(Long userId, PaycheckCycleState state);

    /** Overdue cycles for the reconcile's miss pass; windowEnd is an Eastern date. */
    List<PaycheckCycle> findByStateAndWindowEndBefore(PaycheckCycleState state, LocalDate windowEnd);

    /** Stale confirmations for the reconcile; confirmedAt is Eastern wall time. */
    List<PaycheckCycle> findByStateAndConfirmedAtBefore(PaycheckCycleState state, LocalDateTime confirmedAt);

    /** The shadow report's cycles: SHADOW_CONFIRMED skips since a point in Eastern time. */
    List<PaycheckCycle> findByUserIdAndSkipReasonAndConfirmedAtAfter(Long userId, PaycheckSkipReason skipReason,
            LocalDateTime confirmedAt);

    @Transactional
    long deleteByUserId(Long userId);
}
